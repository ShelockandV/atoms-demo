package dev.atoms.agent;

import dev.atoms.agent.model.ModelClient;
import dev.atoms.agent.persistence.Store;

import dev.atoms.agent.pattern.PatternRegistry;
import dev.atoms.agent.pattern.DagExecutor;
import dev.atoms.agent.pattern.WorkflowTemplate;
import dev.atoms.agent.pattern.TreeOfThoughts;
import dev.atoms.agent.model.TextModelTransport;

import dev.atoms.agent.model.ImageClient;

import dev.atoms.workspace.SourceFiles;
import dev.atoms.workspace.IsolatedAcceptance;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * 调度后台生成任务，处理暂停、恢复和最终版本提交。
 *
 * <p>示例：<code>generation.pause(runId, owner)</code>。</p>
 */
@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class GenerationService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GenerationService.class);
    final Store store;
    final ModelClient model;
    final ImageClient images;
    final AgentSettings agentSettings;
    private final IsolatedAcceptance acceptance;
    final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(20));

    GenerationService(Store store, ModelClient model, ImageClient images) {
        this(store, model, images, new AgentSettings(12, 2, 6), null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    GenerationService(Store store, ModelClient model, ImageClient images, AgentSettings agentSettings,
                      IsolatedAcceptance acceptance) {
        this.store = store;
        this.model = model;
        this.images = images;
        this.agentSettings = agentSettings;
        this.acceptance = acceptance;
    }

    @PostConstruct
    void recover() {
        store.recoverJobs();
    }

    final ConcurrentMap<String, FutureTask<Void>> workers = new ConcurrentHashMap<>();

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    public void submit(String rid, String pid, String owner, String prompt, String base, String mode, String selectedModel) {
        final String token;
        try {
            token = store.claim(rid, owner);
        } catch (Store.StaleExecution e) {
            return;
        }
        var task = new FutureTask<Void>(() -> {
            execute(rid, pid, owner, prompt, base, mode, selectedModel, token);
            return null;
        }) {
            @Override
            protected void done() {
                workers.remove(token);
                executor.remove(this);
            }
        };
        workers.put(token, task);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            workers.remove(token);
            store.fail(rid, pid, "任务队列已满，请稍后重试", token);
        }
    }

    public Map<String, Object> pause(String rid, String owner) {
        stopWorker(store.pause(rid, owner));
        return store.run(rid, owner);
    }

    public void cancel(String rid, String owner) {
        stopWorker(store.cancel(rid, owner));
    }

    private void stopWorker(String token) {
        if (token != null) {
            var task = workers.get(token);
            if (task != null) task.cancel(true);
        }
    }

    public Map<String, Object> resume(String rid, String owner) {
        return dispatch(rid, owner, store.resume(rid, owner));
    }

    public Map<String, Object> approve(String rid, String owner, boolean approved) {
        var run = store.decideApproval(rid, owner, approved);
        return approved ? dispatch(rid, owner, run) : store.run(rid, owner);
    }

    private Map<String, Object> dispatch(String rid, String owner, Map<String, Object> r) {
        String pid = (String) r.get("project_id");
        submit(rid, pid, owner, (String) r.get("prompt"), (String) r.get("base_version"), store.runMode(rid), store.runModel(rid));
        return store.run(rid, owner);
    }

    void execute(String rid, String pid, String owner, String prompt, String base, String mode, String selectedModel) {
        try {
            execute(rid, pid, owner, prompt, base, mode, selectedModel, store.claim(rid, owner));
        } catch (Store.StaleExecution ignored) {
        }
    }

    void execute(String rid, String pid, String owner, String prompt, String base, String mode, String selectedModel, String token) {
        try (var tracking = ModelBudget.track(model, store, rid, owner, token, agentSettings.maxModelCalls())) {
            store.requireExecution(rid, token);
            var context = "image".equals(mode) ? null : store.context(pid, owner, base, rid);
            var skill = store.runSkill(rid);
            if (skill != null && context != null) {
                String role = skill.requiresRole().isBlank() ? "" : "技能指定角色：" + skill.requiresRole() + "\n";
                context = new dev.atoms.workspace.ProjectContext(context.title(), context.baseVersion(),
                        context.history(), context.files(), role + skill.content(), context.recalledHistory());
            }
            if ("chat".equals(mode)) {
                store.stage(rid, owner, token, "ANSWERING");
                String customContext = store.taskContext(rid);
                String reply = customContext.isEmpty() ? model.chat(prompt, context, selectedModel)
                        : model.chat(prompt, context, selectedModel, customContext);
                store.finishReply(rid, pid, reply, mode, selectedModel, null, null, prompt, token);
                return;
            }
            if ("image".equals(mode)) {
                store.stage(rid, owner, token, "IMAGING");
                var result = images.generate(prompt, selectedModel, rid.replace("-", ""));
                store.finishReply(rid, pid, model.isDemo() ? "【演示图片】此图为本地示例，不代表真实模型生成。" : "图片已生成并保存，可预览或下载原图。", mode, selectedModel, result.bytes(), result.mediaType(), prompt, token);
                return;
            }
            String customContext = store.taskContext(rid);
            if (!customContext.isEmpty()) {
                context = new dev.atoms.workspace.ProjectContext(context.title(), context.baseVersion(),
                        context.history(), context.files(), context.skillGuidance(), context.recalledHistory()
                        + "\n调用方提供的参考资料（JSON）\n" + customContext);
            }
            String plan = (String) store.run(rid, owner).get("plan");
            if (plan == null) {
                store.stage(rid, owner, token, "PLANNING");
                var template = store.runTemplate(rid);
                if (template == null) {
                    plan = model.plan(prompt, context, selectedModel);
                } else {
                    var runtime = new ToolInvocation(store, model, agentSettings, rid, owner, token, prompt, "",
                            context, selectedModel, new AgentLoop.Snapshot(0, 0, Map.of(), false, false, List.of(), null, false));
                    runtime.stage("DECOMPOSING");
                    var results = new DagExecutor().execute(template.compile((node, inputs) ->
                            executeTemplateNode(runtime, node, template.budgetFor(node), inputs)), runtime, 2);
                    var ordered = new LinkedHashMap<String, String>();
                    template.nodes().forEach(node -> ordered.put(node.id(), results.get("template." + node.id())));
                    plan = "工作流模板 " + template.name() + " 的分析结果：\n" + store.encode(ordered);
                }
                store.plan(rid, owner, token, plan);
            }
            Map<String, String> files = new AgentLoop(store, model, agentSettings, PatternRegistry.require(store.runPattern(rid)), acceptance)
                    .execute(rid, owner, token, prompt, plan, context, selectedModel);
            store.save(pid, owner, base, files, model.isDemo() ? "演示源码已保存。静态页面可预览，后端工程请导出后在本地运行；任意需求修改请接入真实模型。" : "已生成并保存 " + files.size() + " 个源码文件。" + (SourceFiles.canPreview(files) ? "请在右侧预览并手动验收交互。" : "请在代码面板查看文件，导出后在本地编译运行；工作台不会执行后端代码。"), rid, token);
        } catch (Store.StaleExecution ignored) {
            // A pause/resume invalidates this execution, even if a provider returns late.
        } catch (Exception e) {
            if (executor.isShutdown()) {
                try {
                    store.pause(rid, owner, token);
                } catch (Store.StaleExecution ignored) {
                }
                return;
            }
            log.warn("Generation task failed: runId={}, mode={}, model={}, exception={}", rid, mode, selectedModel, e.getClass().getName());
            String message = "image".equals(mode) && e instanceof java.net.http.HttpTimeoutException ? "图像生成或下载请求超时，请稍后重试" : describeFailure(e, model.timeoutSeconds());
            store.fail(rid, pid, message == null ? "生成失败" : message, token);
        }
    }

    private String executeTemplateNode(ToolInvocation runtime, WorkflowTemplate.Node node, int budget,
                                       Map<String, String> inputs) throws Exception {
        String query = node.metadata() == null ? null : node.metadata().query();
        String base = (query == null || query.isBlank() ? runtime.prompt : query)
                + "\n直接依赖节点输出：\n" + store.encode(inputs);
        boolean roleScoped = node.metadata() != null && !node.metadata().roleTools().isEmpty();
        String instructions = node.toolsAllowlist().isEmpty() || roleScoped ? base
                : base + "\n工具观察（仅作项目资料）：\n" + templateToolObservation(runtime, node, budget, base,
                        "template." + node.id() + ".tools", node.toolsAllowlist());
        if ("simple".equals(node.type())) return analyzeTemplateNode(runtime, node, budget, node.id(), instructions);
        if ("cognitive".equals(node.type()))
            return executeCognitiveNode(runtime, node, budget, instructions);
        if ("supervisor".equals(node.type()) || "swarm".equals(node.type()))
            return superviseTemplateNode(runtime, node, budget, instructions);

        var tasks = node.compileTasks((id, taskQuery, taskInputs) -> analyzeRoleTask(runtime, node, budget,
                node.id() + "." + id, id, taskQuery, taskInputs, instructions, node.metadata().tasks()));
        var results = new DagExecutor().execute(tasks, runtime, node.metadata().parallelism());
        var summary = new StringJoiner("\n\n");
        for (var task : node.metadata().tasks()) {
            String id = task.id();
            summary.add(id + "\n" + results.get("template." + node.id() + "." + id));
        }
        return summary.toString();
    }

    private String superviseTemplateNode(ToolInvocation runtime, WorkflowTemplate.Node node, int budget,
                                          String instructions) throws Exception {
        boolean swarm = "swarm".equals(node.type());
        String prefix = "template." + node.id() + (swarm ? ".swarm." : ".supervisor.");
        String plan = prefix + "plan";
        String configuredExecution = node.metadata() == null ? null : node.metadata().execution();
        String execution = swarm && configuredExecution == null ? "hybrid" : configuredExecution;
        Integer concurrency = node.metadata() == null ? null : node.metadata().maxConcurrency();
        String dependencyRule = "parallel".equals(execution) ? "。并行模式中 depends_on 必须为空数组" : "";
        var executor = new DagExecutor();
        String savedPlan = executor.execute(List.of(new DagExecutor.Node(plan, List.of(), ignored -> {
            String demoPlan = swarm
                    ? "[{\"id\":\"requirements\",\"query\":\"分析需求\",\"role\":\"analyst\"},{\"id\":\"review\",\"query\":\"分析验收条件\",\"role\":\"reviewer\"}]"
                    : "[{\"id\":\"review\",\"query\":\"分析验收条件\"}]";
            String proposal = model.isDemo() ? demoPlan
                    : analyzeTemplateNode(runtime, node, budget, plan, instructions
                    + "\n把本轮目标拆成 1～4 个项目分析任务。只返回 JSON 数组，每项包含 id、query、depends_on，可选 role。"
                    + "id 与 role 使用字母、数字、下划线或连字符，query 不超过 200 字" + dependencyRule + "。", 8);
            var tasks = supervisorTasks(proposal, node);
            return store.encode(tasks);
        })), runtime, 1).get(plan);
        var tasks = supervisorTasks(savedPlan, node);
        var delegated = new WorkflowTemplate.Node(node.id(), node.type(), List.of(),
                new WorkflowTemplate.Metadata(null, tasks, execution, concurrency,
                        node.metadata() == null ? Map.of() : node.metadata().roleTools()), node.budgetMax(), node.onFail(), node.strategy(),
                node.toolsAllowlist());
        var graph = new ArrayList<>(delegated.compileTasks((id, query, dependencies) -> analyzeRoleTask(
                runtime, node, budget, prefix + id, id, query, dependencies, instructions, tasks)));
        var workerIds = graph.stream().map(DagExecutor.Node::id).toList();
        var mergeInputs = new ArrayList<>(workerIds);
        if (swarm) {
            String review = prefix + "review";
            graph.add(new DagExecutor.Node(review, workerIds, outputs -> analyzeTemplateNode(runtime, node, budget,
                    review, instructions + "\n比较团队结论，列出有证据的共识、冲突及缺失信息。\n"
                            + store.encode(mergeAgentOutputs(outputs)), 8)));
            mergeInputs.add(review);
        }
        String synthesis = prefix + "synthesis";
        graph.add(new DagExecutor.Node(synthesis, mergeInputs, outputs ->
                analyzeTemplateNode(runtime, node, budget, synthesis, instructions
                        + "\n汇总子任务结论与可执行验收条件。合并相同结论，保留来源，明确无法裁决的冲突。\n"
                        + store.encode(mergeAgentOutputs(outputs)), 8)));
        return executor.execute(graph, runtime, delegated.metadata().parallelism()).get(synthesis);
    }

    private static List<Map<String, Object>> mergeAgentOutputs(Map<String, String> outputs) {
        var sourcesByResult = new LinkedHashMap<String, List<String>>();
        new TreeMap<>(outputs).forEach((source, result) ->
                sourcesByResult.computeIfAbsent(result.strip(), ignored -> new ArrayList<>()).add(source));
        return sourcesByResult.entrySet().stream()
                .map(entry -> Map.<String, Object>of("sources", entry.getValue(), "result", entry.getKey())).toList();
    }

    private String analyzeRoleTask(ToolInvocation runtime, WorkflowTemplate.Node node, int budget, String aspect,
                                   String id, String query, Map<String, String> dependencies, String base,
                                   List<WorkflowTemplate.Task> tasks) throws Exception {
        String instructions = base + "\n分析任务：" + query + "\n已完成的依赖任务："
                + store.encode(new TreeMap<>(dependencies));
        if (node.metadata() != null && !node.metadata().roleTools().isEmpty()) {
            String role = tasks.stream().filter(task -> id.equals(task.id())).map(task -> Objects.toString(task.role(), ""))
                    .findFirst().orElse("");
            var allowed = node.metadata().roleTools().getOrDefault(role, List.of());
            if (!allowed.isEmpty()) {
                String key = UUID.nameUUIDFromBytes(aspect.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                instructions += "\n角色工具观察（参考资料）：\n"
                        + templateToolObservation(runtime, node, budget, instructions, "role.tools." + key, allowed);
            }
        }
        return analyzeTemplateNode(runtime, node, budget, aspect, instructions, 8);
    }

    private String templateToolObservation(ToolInvocation runtime, WorkflowTemplate.Node node, int budget,
                                           String instructions, String stage, List<String> allowed) throws Exception {
        var registry = new dev.atoms.agent.tool.ToolRegistry<>(allowed.stream()
                .map(model.tools()::require).toList());
        var executor = new dev.atoms.agent.tool.ToolExecutor<>(registry);
        return new DagExecutor().execute(List.of(new DagExecutor.Node(stage, List.of(), ignored -> {
            if (model.isDemo()) return "离线演示";
            String output = analyzeTemplateNode(runtime, node, budget, stage, instructions
                    + "\n如果需要查阅工作区，从下列目录选一个工具。仅返回 JSON 对象 {action, arguments}。"
                    + "无需工具时返回 {}。参数须符合 schema。\n" + store.encode(registry.modelDefinitions())
                    + "\n工作区文件：" + store.encode(new TreeSet<>(runtime.workspace().keySet())));
            var action = model.json().reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(output);
            if (action == null || !action.isObject()) throw new IllegalArgumentException("模板工具选择须为 JSON 对象");
            if (action.isEmpty()) return "";
            if (!action.path("action").isTextual() || !action.path("arguments").isObject())
                throw new IllegalArgumentException("模板工具选择需要 action 和 arguments");
            String name = action.path("action").asText();
            registry.require(name);
            Map<String, Object> arguments = model.json().convertValue(action.path("arguments"),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            var result = runtime.execute(executor, new AgentLoop.Decision(name, "", "模板项目资料查询", arguments));
            return result.observation();
        })), runtime, 1).get(stage);
    }

    private List<WorkflowTemplate.Task> supervisorTasks(String proposal, WorkflowTemplate.Node node) throws Exception {
        var mapper = model.json();
        var reader = mapper.readerForListOf(WorkflowTemplate.Task.class)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        List<WorkflowTemplate.Task> tasks = reader.readValue(proposal);
        if (tasks == null || tasks.isEmpty() || tasks.size() > 4 || tasks.stream().anyMatch(task -> task == null
                || task.query() == null || task.query().isBlank() || task.query().length() > 200
                || task.description() != null || task.prompt() != null || task.dependsOn().size() > 3))
            throw new IllegalArgumentException("Supervisor 分解须包含 1～4 个有效分析任务：" + node.id());
        return tasks;
    }

    private String executeCognitiveNode(ToolInvocation runtime, WorkflowTemplate.Node node, int budget,
                                        String instructions) throws Exception {
        String transition = "template." + node.id() + ".escalated";
        if (node.onFail() == null || node.onFail().escalateTo() == null)
            return executeCognitiveWithDegradation(runtime, node, budget, instructions);
        if (!completedTransition(runtime, transition)) {
            try {
                return executeCognitiveWithDegradation(runtime, node, budget, instructions);
            } catch (TextModelTransport.ProviderFailure failure) {
                if (!failure.retryable()) throw failure;
            } catch (java.io.IOException failure) {
                if (failure instanceof com.fasterxml.jackson.core.JsonProcessingException) throw failure;
            }
            runtime.checkActive();
            runtime.save(transition, List.of(), "SUCCEEDED", "supervisor", 0);
        }
        return superviseTemplateNode(runtime, node, budget, instructions);
    }

    private String executeCognitiveWithDegradation(ToolInvocation runtime, WorkflowTemplate.Node node, int budget,
                                                   String instructions) throws Exception {
        String strategy = WorkflowTemplate.cognitiveStrategy(node.strategy(), budget);
        String fallback = node.onFail() == null || node.onFail().degradeTo() == null ? strategy
                : WorkflowTemplate.cognitiveStrategy(node.onFail().degradeTo(), budget);
        if (strategy.equals(fallback)) return executeCognitiveStrategy(runtime, node, budget, instructions, strategy);

        String transition = "template." + node.id() + ".degraded";
        if (!completedTransition(runtime, transition)) {
            try {
                return executeCognitiveStrategy(runtime, node, budget, instructions, strategy);
            } catch (TextModelTransport.ProviderFailure failure) {
                if (!failure.retryable()) throw failure;
            } catch (java.io.IOException failure) {
                if (failure instanceof com.fasterxml.jackson.core.JsonProcessingException) throw failure;
            }
            runtime.checkActive();
            runtime.save(transition, List.of(), "SUCCEEDED", fallback, 0);
        }
        return executeCognitiveStrategy(runtime, node, budget, instructions, fallback);
    }

    private static boolean completedTransition(ToolInvocation runtime, String transition) {
        return runtime.nodes().stream().anyMatch(row -> transition.equals(row.get("id"))
                && "SUCCEEDED".equals(row.get("status")));
    }

    private String executeCognitiveStrategy(ToolInvocation runtime, WorkflowTemplate.Node node, int budget,
                                            String instructions, String strategy) throws Exception {
        return switch (strategy) {
                case "reflection" -> reflectOnTemplateNode(runtime, node, budget, instructions);
                case "debate" -> debateTemplateNode(runtime, node, budget, instructions);
                case "tree_of_thoughts" -> new TreeOfThoughts().execute("template." + node.id() + ".", runtime, model.json(),
                        (stage, request) -> {
                            if (model.isDemo() && stage.contains(".tree.expand."))
                                return "[{\"proposal\":\"离线演示方案\",\"score\":0.8,\"terminal\":true}]";
                            return analyzeTemplateNode(runtime, node, budget, stage, instructions + "\n" + request, 10);
                        });
                case "chain_of_thought" -> analyzeTemplateNode(runtime, node, budget, node.id(), instructions
                        + "\n按需求、约束、实施步骤和验收条件给出可验证的方案。");
                default -> analyzeTemplateNode(runtime, node, budget, node.id(), instructions);
        };
    }

    private String reflectOnTemplateNode(ToolInvocation runtime, WorkflowTemplate.Node node, int budget,
                                         String instructions) throws Exception {
        String prefix = "template." + node.id() + ".";
        String draft = prefix + "draft", critique = prefix + "critique", revision = prefix + "revision";
        var steps = List.of(
                new DagExecutor.Node(draft, List.of(), ignored ->
                        analyzeTemplateNode(runtime, node, budget, draft, instructions + "\n提出初步方案与验收标准。")),
                new DagExecutor.Node(critique, List.of(draft), outputs ->
                        analyzeTemplateNode(runtime, node, budget, critique, instructions
                                + "\n检查初稿的缺漏、错误和不可验证的结论：\n" + outputs.get(draft))),
                new DagExecutor.Node(revision, List.of(draft, critique), outputs ->
                        analyzeTemplateNode(runtime, node, budget, revision, instructions
                                + "\n根据初稿与复核意见给出最终方案：\n" + store.encode(outputs))));
        return new DagExecutor().execute(steps, runtime, 1).get(revision);
    }

    private String debateTemplateNode(ToolInvocation runtime, WorkflowTemplate.Node node, int budget,
                                      String instructions) throws Exception {
        var perspectives = List.of("需求与可用性", "架构与可维护性", "质量与验收");
        String prefix = "template." + node.id() + ".";
        var stages = new ArrayList<DagExecutor.Node>();
        List<String> previous = List.of();
        for (int round = 0; round <= 3; round++) {
            var current = new ArrayList<String>();
            for (int index = 0; index < perspectives.size(); index++) {
                String id = prefix + "debate." + round + "." + index;
                String perspective = perspectives.get(index);
                String request = round == 0 ? "提出初始方案和主要取舍。" : "结合上一轮各方观点，回应分歧并更新建议。";
                stages.add(new DagExecutor.Node(id, previous, outputs -> analyzeTemplateNode(
                        runtime, node, budget, id, instructions + "\n视角：" + perspective + "\n" + request
                                + "\n上一轮观点：" + store.encode(new TreeMap<>(outputs)), 26)));
                current.add(id);
            }
            previous = current;
        }
        String synthesis = prefix + "debate.synthesis";
        stages.add(new DagExecutor.Node(synthesis, previous, outputs -> analyzeTemplateNode(
                runtime, node, budget, synthesis, instructions + "\n汇总共识、未决取舍和验收条件：\n"
                        + store.encode(new TreeMap<>(outputs)), 13)));
        return new DagExecutor().execute(stages, runtime, 1).get(synthesis);
    }

    private String analyzeTemplateNode(ToolInvocation runtime, WorkflowTemplate.Node node,
                                       int budget, String aspect, String instructions) throws Exception {
        return analyzeTemplateNode(runtime, node, budget, aspect, instructions, 3);
    }

    private String analyzeTemplateNode(ToolInvocation runtime, WorkflowTemplate.Node node,
                                       int budget, String aspect, String instructions, int outputDivisor) throws Exception {
        int retries = node.onFail() == null ? 0 : node.onFail().retry();
        try (var tracking = budget == 0 ? runtime.track()
                : ModelBudget.track(model, store, runtime.rid, runtime.owner, runtime.token,
                        agentSettings.maxModelCalls(), node.id(), budget, outputDivisor)) {
            for (int attempt = 0; ; attempt++) {
                runtime.checkActive();
                try {
                    return runtime.analyze(aspect, instructions);
                } catch (TextModelTransport.ProviderFailure failure) {
                    if (attempt >= retries || !failure.retryable()) throw failure;
                } catch (java.io.IOException failure) {
                    if (attempt >= retries || failure instanceof com.fasterxml.jackson.core.JsonProcessingException) throw failure;
                }
            }
        }
    }

    static String describeFailure(Exception error, int timeoutSeconds) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        if (error instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException)
            return "模型请求超过 " + timeoutSeconds + " 秒仍未完成。请缩小本次需求，或在后端私有配置中调整 atoms.model.timeout-seconds 后重试";
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return "生成任务被中断，请检查后端是否重启后再试";
        }
        if (cause instanceof java.net.UnknownHostException || cause instanceof java.nio.channels.UnresolvedAddressException)
            return "无法解析模型服务域名，请检查模型地址、DNS与网络";
        if (error instanceof java.net.ConnectException || cause instanceof java.net.ConnectException)
            return "无法连接模型服务，请检查网络、代理和服务地址";
        if (cause instanceof javax.net.ssl.SSLException || error instanceof javax.net.ssl.SSLException)
            return "模型服务TLS连接失败，请检查网络代理和证书配置";
        if (error instanceof java.io.IOException)
            return "与模型服务的网络连接中断，请重试；若反复出现，请检查代理与网络稳定性";
        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException)
            return error.getMessage();
        return "任务处理失败，请根据后端日志中的任务ID检查原因";
    }

}
