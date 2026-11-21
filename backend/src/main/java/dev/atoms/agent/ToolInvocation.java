package dev.atoms.agent;

import dev.atoms.agent.model.ModelClient;
import dev.atoms.agent.persistence.Store;

import dev.atoms.workspace.ProjectContext;
import dev.atoms.workspace.IsolatedAcceptance;

import dev.atoms.agent.tool.*;
import dev.atoms.agent.tool.builtin.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把单次工具调用所需的工作区、模型与检查点操作适配成工具上下文。
 *
 * <p>示例：READ_FILE 通过 workspace() 读取当前候选或基准文件。</p>
 *
 * Mutable state is confined to one tool worker and discarded if it times out or is cancelled.
 */
final class ToolInvocation implements ToolContext {
    final Store store;
    final ModelClient model;
    final AgentSettings settings;
    final String rid, owner, token, prompt, plan, selectedModel;
    final ProjectContext project;
    final AgentLoop.Snapshot original;
    Map<String, String> files;
    int generations;
    boolean validated, reviewed, finished;
    IsolatedAcceptance acceptance;
    TemporalTaskRuntime analysisWorker;
    private List<Map<String, String>> supervisorMessages;
    private int supervisorOutputLimit;

    public String verifyCandidate() throws Exception {
        return acceptance == null ? "" : acceptance.verify(files);
    }

    ToolInvocation(Store store, ModelClient model, AgentSettings settings, String rid, String owner,
                String token, String prompt, String plan, ProjectContext project, String selectedModel,
                AgentLoop.Snapshot state) {
        this.store = store;
        this.model = model;
        this.settings = settings;
        this.rid = rid;
        this.owner = owner;
        this.token = token;
        this.prompt = prompt;
        this.plan = plan;
        this.project = project;
        this.selectedModel = selectedModel;
        original = state;
        files = state.files();
        generations = state.generations();
        validated = state.validated();
        reviewed = state.reviewed();
    }

    @Override
    public String searchDocuments(String query) {
        return store.encode(store.searchRunDocuments(rid, owner, query));
    }

    public void checkActive() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("工具执行已中断");
        store.requireExecution(rid, token);
    }

    public java.util.List<Map<String, Object>> nodes() { return store.dagNodes(rid); }

    public void save(String id, java.util.List<String> dependencies, String status, String output, long elapsedMs) {
        store.dagNode(rid, owner, token, id, dependencies, status, output, elapsedMs);
    }

    public AutoCloseable track() { return ModelBudget.track(model, store, rid, owner, token, settings.maxModelCalls()); }

    public boolean stale(Exception error) { return error instanceof Store.StaleExecution; }

    public void stage(String stage) throws InterruptedException {
        checkActive();
        store.stage(rid, owner, token, stage);
    }

    public Map<String, String> workspace() { return files.isEmpty() ? project.files() : files; }

    ToolResult execute(ToolExecutor<? super ToolInvocation> executor, AgentLoop.Decision decision) throws Exception {
        return executor.execute(decision.action(), decision.arguments(), this, model.json(), owner, this::checkActive,
                this::track);
    }

    AgentLoop.Snapshot snapshot() {
        return new AgentLoop.Snapshot(original.step(), generations, files, validated, finished,
                original.observations(), original.pending(), reviewed);
    }
    public Map<String, String> baseline() { return project.files(); }
    public Map<String, String> files() { return files; }
    public void files(Map<String, String> value) { files = value; }
    public int generations() { return generations; }
    public void generations(int value) { generations = value; }
    public int maxGenerations() { return settings.maxGenerations(); }
    public boolean validated() { return validated; }
    public void validated(boolean value) { validated = value; }
    public boolean reviewed() { return reviewed; }
    public void reviewed(boolean value) { reviewed = value; }
    public void finished(boolean value) { finished = value; }
    public boolean requiresReview() { return settings.requireReview() && !model.isDemo(); }
    public String encode(Object value) { return store.encode(value); }

    public boolean dynamicDagEnabled() { return model.dynamicDagEnabled(); }

    public boolean dynamicDagEditsEnabled() { return model.dynamicDagEditsEnabled(); }

    public String proposeDagEdit(String aspect, String instructions) throws Exception {
        if (analysisWorker != null) {
            return analysisWorker.agentNode(this, new TemporalTaskRuntime.AgentNodeInput(rid, token, null, 0,
                    "build.dag-edit." + aspect, null, aspect, Map.of(), instructions, null));
        }
        return model.proposeDagEdit(prompt, project, instructions, selectedModel);
    }

    public String proposeDag() throws Exception {
        if (analysisWorker != null) {
            var input = new TemporalTaskRuntime.AgentNodeInput(rid, token, null, 0, "build.dag-plan",
                    null, "", Map.of(), plan, null);
            return analysisWorker.agentNode(this, input);
        }
        return model.proposeDag(prompt, plan, project, store.stageModel(rid, "planning", selectedModel));
    }

    void configureTextSupervisor() throws com.fasterxml.jackson.core.JsonProcessingException {
        var request = model.json().readTree(store.taskContext(rid));
        List<Map<String, String>> messages = model.json().convertValue(request.path("messages"),
                new com.fasterxml.jackson.core.type.TypeReference<>() {});
        supervisorMessages = messages.stream().filter(message -> !"tool".equals(message.get("role")))
                .filter(message -> !message.get("content").isBlank())
                .map(message -> Map.of("role", message.get("role"), "content", message.get("content"))).toList();
        supervisorOutputLimit = request.path("max_tokens").asInt();
    }

    boolean isTextSupervisor() {
        return supervisorMessages != null;
    }

    public String analyze(String aspect, String instructions) throws Exception {
        if (isTextSupervisor()) {
            boolean synthesis = aspect.endsWith(".supervisor.synthesis");
            if (model.isDemo()) return "【演示多角色回答】" + prompt;
            var messages = new ArrayList<>(supervisorMessages);
            messages.add(Map.of("role", "user", "content", instructions));
            try (var tracking = ModelBudget.trackWithOutputLimit(model, store, rid, owner, token,
                    settings.maxModelCalls(), synthesis ? supervisorOutputLimit : 0)) {
                return model.completion(messages, selectedModel);
            }
        }
        if (analysisWorker != null) {
            var input = new TemporalTaskRuntime.AgentNodeInput(rid, token, null, 0, "build.analysis." + aspect,
                    null, aspect, Map.of(), instructions, null);
            return analysisWorker.agentNode(this, input);
        }
        return model.analyze(prompt, project, aspect, instructions, store.stageModel(rid, "planning", selectedModel));
    }

    public Map<String, String> generate(String planDetails) throws Exception {
        return model.generate(prompt, plan + planDetails, project, String.join("\n", original.observations()), selectedModel);
    }

    public Review review(Map<String, String> candidate) throws Exception {
        var result = model.review(prompt, project, candidate, store.stageModel(rid, "review", selectedModel));
        return new Review(result.passed(), result.feedback());
    }
}
