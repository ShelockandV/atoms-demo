package dev.atoms.agent;

import dev.atoms.agent.model.ModelClient;
import dev.atoms.agent.persistence.Store;

import dev.atoms.workspace.ProjectContext;
import dev.atoms.workspace.IsolatedAcceptance;

import dev.atoms.agent.pattern.ExecutionPattern;
import dev.atoms.agent.pattern.PatternRegistry;

import dev.atoms.agent.tool.*;

import java.util.*;

/**
 * 加载检查点，选择并执行动作，再保存观察与候选源码状态。
 *
 * <p>示例：生成、校验和必需的复核通过后，FINISH 将候选快照交给任务调度层。</p>
 *
 * A persisted Reason-Act-Observe loop over a virtual source workspace. No host execution.
 */
public final class AgentLoop {

    /**
     * 表示待执行动作，兼容旧检查点中的 path 字段。
     *
     * <p>示例：<code>new Decision("READ_FILE", "index.html", "检查首页")</code>。</p>
     */
    public record Decision(String action, String path, String summary, Map<String, Object> arguments) {
        public Decision(String action, String path, String summary) { this(action, path, summary, null); }
        public Decision {
            if (action == null || action.isBlank()) throw new IllegalArgumentException("Agent 必须选择一个动作");
            path = Objects.requireNonNullElse(path, "");
            summary = clip(Objects.requireNonNullElse(summary, ""), 500);
            // Older persisted decisions stored READ_FILE's argument directly in path.
            arguments = arguments == null ? (path.isBlank() ? Map.of() : Map.of("path", path))
                    : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }
    }
    /**
     * 保存循环进度、候选文件和待执行动作，供暂停后恢复。
     *
     * <p>示例：pending 指向 GENERATE 时，恢复后继续执行该已选动作。</p>
     */
    public record Snapshot(int step, int generations, Map<String, String> files, boolean validated,
                    boolean finished, List<String> observations, Decision pending, boolean reviewed) {
        public Snapshot {
            files = Map.copyOf(files);
            observations = List.copyOf(observations);
        }
    }

    private final Store store;
    private final ModelClient model;
    private final AgentSettings settings;
    private final ToolExecutor<? super ToolInvocation> tools;
    private final ExecutionPattern pattern;
    private final IsolatedAcceptance acceptance;
    private final TemporalTaskRuntime temporal;
    private final TaskApprovalPolicy approvalPolicy;

    AgentLoop(Store store, ModelClient model, AgentSettings settings) {
        this(store, model, settings, PatternRegistry.require("react"));
    }

    AgentLoop(Store store, ModelClient model, AgentSettings settings, ExecutionPattern pattern) {
        this(store, model, settings, pattern, null);
    }

    AgentLoop(Store store, ModelClient model, AgentSettings settings, ExecutionPattern pattern,
              IsolatedAcceptance acceptance) {
        this(store, model, settings, pattern, acceptance, null);
    }

    AgentLoop(Store store, ModelClient model, AgentSettings settings, ExecutionPattern pattern,
              IsolatedAcceptance acceptance, TemporalTaskRuntime temporal) {
        this(store, model, settings, pattern, acceptance, temporal, null);
    }

    AgentLoop(Store store, ModelClient model, AgentSettings settings, ExecutionPattern pattern,
              IsolatedAcceptance acceptance, TemporalTaskRuntime temporal, TaskApprovalPolicy approvalPolicy) {
        this.approvalPolicy = approvalPolicy;
        this.store = store;
        this.model = model;
        this.settings = settings;
        this.tools = new ToolExecutor<>(model.tools());
        this.pattern = pattern;
        this.acceptance = acceptance;
        this.temporal = temporal;
    }

    Map<String, String> execute(String rid, String owner, String token, String prompt,
                                String plan, ProjectContext context, String selectedModel) throws Exception {
        return execute(rid, owner, token, prompt, plan, context, selectedModel, false);
    }

    Map<String, String> execute(String rid, String owner, String token, String prompt, String plan,
                                ProjectContext context, String selectedModel, boolean singleAction) throws Exception {
        Snapshot state = store.agentSnapshot(rid);
        if (state == null) {
            // Adopt checkpoints created by the previous generation/repair implementation.
            var legacy = store.checkpoint(rid);
            boolean hasFiles = legacy.get("files") != null;
            int attempts = ((Number) legacy.get("attempt")).intValue();
            String failure = (String) legacy.get("failure");
            state = new Snapshot(0, attempts + (hasFiles ? 1 : 0),
                    hasFiles ? store.files((String) legacy.get("files")) : Map.of(), false, false,
                    failure == null || failure.isBlank() ? List.of() : List.of(failure), null, false);
        }
        while (!state.finished()) {
            store.requireExecution(rid, token);
            if (state.pending() == null) {
                if (state.step() >= settings.maxSteps())
                    throw new IllegalArgumentException("Agent 已达到动作上限（" + settings.maxSteps() + "），请缩小需求后重试");
                // A restored, completed generation should be validated before spending another model call.
                Decision decision;
                if (state.step() == 0 && !state.files().isEmpty()) {
                    decision = new Decision("VALIDATE", "", "校验恢复的源码检查点");
                } else {
                    store.stage(rid, owner, token, "REASONING");
                    try {
                        var current = state;
                        decision = pattern.select(new ExecutionPattern.Input(model.isDemo(), progress(current),
                                () -> decideWithRuntime(rid, owner, token, prompt, plan, context, current, selectedModel)));
                    } catch (IllegalArgumentException e) {
                        decision = new Decision("INVALID", "", e.getMessage());
                    }
                }
                state = new Snapshot(state.step() + 1, state.generations(), state.files(),
                        state.validated(), false, state.observations(), decision, state.reviewed());
                // Save the selected action before execution. A resumed run replays this action.
                store.agentStep(rid, owner, token, state, "RUNNING", decision.summary());
            }
            Decision decision = state.pending();
            if (approvalPolicy != null && approvalPolicy.evaluatesTool(decision.action())
                    && toolEligibleForApproval(decision)
                    && !store.toolApprovalGranted(rid, state.step(), decision.action())) {
                String reason = approvalPolicy.toolRequirement(owner, rid, state.step(), decision.action());
                if (reason != null) {
                    store.awaitToolApproval(rid, owner, token, state.step(), decision.action(), reason);
                    return null;
                }
            }
            var invocation = new ToolInvocation(store, model, settings, rid, owner, token, prompt, plan, context, selectedModel, state);
            invocation.acceptance = acceptance;
            invocation.analysisWorker = temporal;
            ToolResult result = invocation.execute(tools, decision);
            String observation = result.success() ? result.observation() : clip(result.observation(), 2000);
            String outcome = result.success() ? "SUCCEEDED" : "FAILED";
            Snapshot updated = invocation.snapshot();
            var observations = new ArrayList<>(state.observations());
            observations.add(decision.action() + ": " + observation);
            if (observations.size() > settings.observationWindow())
                observations = new ArrayList<>(observations.subList(observations.size() - settings.observationWindow(), observations.size()));
            // Checkpoint and event completion commit in the same fenced transaction.
            Snapshot completed = new Snapshot(state.step(), updated.generations(), updated.files(), updated.validated(),
                    updated.finished(), observations, null, updated.reviewed());
            store.agentStep(rid, owner, token, completed, outcome, clip(observation, 1000), result.executionTimeMs());
            state = completed;
            if (!result.success() && state.generations() >= settings.maxGenerations()
                    && Set.of("GENERATE", "VALIDATE", "REVIEW").contains(decision.action()))
                throw new IllegalArgumentException(observation);
            if (singleAction && !state.finished()) return null;
        }
        if (state.files().isEmpty() || !state.validated()
                || (!model.isDemo() && (settings.requireReview() || !pattern.name().equals("react")) && !state.reviewed()))
            throw new IllegalArgumentException("Agent 结束前必须满足源码校验与所需复核条件");
        return state.files();
    }

    private boolean toolEligibleForApproval(Decision decision) {
        try {
            var registry = model.tools();
            var tool = registry.require(decision.action());
            registry.validate(tool, decision.arguments(), model.json());
            return !tool.metadata().dangerous() && !tool.metadata().requiresAuth();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static Map<String, String> workspace(Snapshot state, ProjectContext context) {
        return state.files().isEmpty() ? context.files() : state.files();
    }

    /** Offline policy also serves as a deterministic execution adapter in tests. */
    static Decision chooseAction(ModelClient model, String prompt, String plan, ProjectContext project,
                                 Snapshot state, AgentSettings settings, String selectedModel) throws Exception {
        if (model.isDemo()) return offlineDecision(state);
        return decide(model, prompt, plan, project, actionInput(state, project, settings), selectedModel);
    }

    private Decision decideWithRuntime(String rid, String owner, String token, String prompt, String plan,
                                       ProjectContext context, Snapshot state, String selectedModel) throws Exception {
        if (temporal == null || model.isDemo())
            return chooseAction(model, prompt, plan, context, state, settings, selectedModel);
        var runtime = new ToolInvocation(store, model, settings, rid, owner, token, prompt, plan, context, selectedModel, state);
        var input = new TemporalTaskRuntime.AgentNodeInput(rid, token, null, 0,
                "build.agent-decision." + state.step(), null, store.encode(actionInput(state, context, settings)),
                Map.of(), plan, null);
        return model.json().readValue(temporal.agentNode(runtime, input), Decision.class);
    }

    private static ModelClient.ActionInput actionInput(Snapshot state, ProjectContext project, AgentSettings settings) {
        return new ModelClient.ActionInput(workspace(state, project).keySet(), state.step(), settings.maxSteps(),
                state.generations(), settings.maxGenerations(), !state.files().isEmpty(), state.validated(),
                settings.requireReview(), state.reviewed(), state.observations());
    }

    static Decision decide(ModelClient model, String prompt, String plan, ProjectContext project,
                           ModelClient.ActionInput input, String selectedModel) throws Exception {
        var selected = model.decide(prompt, plan, project, input, selectedModel);
        return new Decision(selected.action(), selected.path(), selected.summary(), selected.arguments());
    }

    static Decision offlineDecision(Snapshot state) {
        return PatternRegistry.offlineDecision(progress(state));
    }

    private static ExecutionPattern.Progress progress(Snapshot state) {
        return new ExecutionPattern.Progress(state.generations(), !state.files().isEmpty(),
                state.validated(), state.reviewed(), state.observations());
    }

    static String clip(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "\n[内容已截断]";
    }
}
