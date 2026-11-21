package dev.atoms.agent.pattern;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;

/** 保存可恢复的模板定义，例如先执行 requirements，再执行 summary。 */
public record WorkflowTemplate(String name, String description, String version, List<Node> nodes, List<Edge> edges,
                               Defaults defaults) {
    public WorkflowTemplate {
        if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}"))
            throw new IllegalArgumentException("模板名称无效");
        description = description == null ? "" : description;
        version = version == null ? "1.0.0" : version;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        var ids = new HashSet<String>();
        for (var node : nodes) {
            if (node.metadata() != null && !node.metadata().roleTools().values().stream().allMatch(node.toolsAllowlist()::containsAll))
                throw new IllegalArgumentException("角色工具必须属于节点工具白名单");
            if ("dag".equals(node.type())) {
                if (node.metadata() == null) throw new IllegalArgumentException("DAG 节点须声明 metadata.tasks");
                DagExecutor.validate(node.compileTasks((id, query, outputs) -> ""), node.metadata().parallelism());
            } else if (!Set.of("simple", "cognitive", "supervisor", "swarm").contains(Objects.toString(node.type(), ""))
                    || node.metadata() != null && !node.metadata().tasks().isEmpty()) {
                throw new IllegalArgumentException("模板节点类型或 metadata.tasks 无效：" + node.id());
            }
            if ("cognitive".equals(node.type()) && (node.strategy() == null
                    || !Set.of("react", "reflection", "chain_of_thought", "debate", "tree_of_thoughts").contains(node.strategy()))
                    || !"cognitive".equals(node.type()) && node.strategy() != null && !"react".equals(node.strategy()))
                throw new IllegalArgumentException("模板节点策略当前不受支持：" + node.id());
            if (node.onFail() != null && node.onFail().degradeTo() != null && !"cognitive".equals(node.type()))
                throw new IllegalArgumentException("degrade_to 仅适用于 cognitive 节点：" + node.id());
            if (node.onFail() != null && node.onFail().escalateTo() != null && !"cognitive".equals(node.type()))
                throw new IllegalArgumentException("escalate_to 仅适用于 cognitive 节点：" + node.id());
            if (!ids.add(node.id())) throw new IllegalArgumentException("模板节点重复：" + node.id());
        }
        for (var edge : edges)
            if (!ids.contains(edge.from()) || !ids.contains(edge.to()))
                throw new IllegalArgumentException("模板边引用未知节点");
        DagExecutor.validate(compile(nodes, edges, (node, outputs) -> ""), 2);
    }

    /** 描述一个分析节点，例如通过 metadata.query 指定验收分析问题。 */
    public record Node(String id, String type, @JsonProperty("depends_on") List<String> dependsOn, Metadata metadata,
                       @JsonProperty("budget_max") Integer budgetMax, @JsonProperty("on_fail") Failure onFail,
                       String strategy, @JsonProperty("tools_allowlist") List<String> toolsAllowlist) {
        public Node {
            if (id == null || !id.matches("[a-zA-Z0-9_-]{1,48}"))
                throw new IllegalArgumentException("模板节点 ID 无效");
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            toolsAllowlist = toolsAllowlist == null ? List.of() : List.copyOf(toolsAllowlist);
            if (!Set.of("READ_FILE", "LIST_FILES", "SEARCH_DOCS").containsAll(toolsAllowlist)
                    || new HashSet<>(toolsAllowlist).size() != toolsAllowlist.size())
                throw new IllegalArgumentException("模板工具白名单仅支持 READ_FILE、LIST_FILES 和 SEARCH_DOCS，且不可重复");
            if (budgetMax != null && budgetMax < 0) throw new IllegalArgumentException("节点预算不能为负数");
        }

        public List<DagExecutor.Node> compileTasks(TaskAnalysis analysis) {
            String prefix = "template." + id + ".";
            if (metadata == null) return List.of();
            var graph = new ArrayList<DagExecutor.Node>();
            String previous = null;
            for (var task : metadata.tasks()) {
                var parents = new LinkedHashSet<>(task.dependsOn());
                if ("parallel".equals(metadata.execution()) && !parents.isEmpty())
                    throw new IllegalArgumentException("parallel 子任务不能声明依赖：" + task.id());
                if ("sequential".equals(metadata.execution()) && previous != null) parents.add(previous);
                graph.add(new DagExecutor.Node(prefix + task.id(), parents.stream().map(parent -> prefix + parent).toList(),
                        outputs -> {
                            var inputs = new LinkedHashMap<String, String>();
                            parents.forEach(parent -> inputs.put(parent, outputs.get(prefix + parent)));
                            return analysis.run(task.id(), task.instructions(), inputs);
                        }));
                previous = task.id();
            }
            return List.copyOf(graph);
        }
    }

    /** 指定节点默认预算，例如 budget_agent_max: 5000。 */
    public record Defaults(@JsonProperty("budget_agent_max") int budgetAgentMax,
                           @JsonProperty("require_approval") Boolean requireApproval) {
        public Defaults {
            if (budgetAgentMax < 0) throw new IllegalArgumentException("模板默认预算不能为负数");
        }
    }

    /** 配置暂时性模型失败的额外尝试次数，例如 retry: 1。 */
    public record Failure(int retry, @JsonProperty("degrade_to") String degradeTo,
                          @JsonProperty("escalate_to") String escalateTo) {
        public Failure {
            if (retry < 0 || retry > 5) throw new IllegalArgumentException("节点重试次数须为 0～5");
            if (degradeTo != null && !Set.of("react", "reflection", "chain_of_thought").contains(degradeTo))
                throw new IllegalArgumentException("节点失败降级策略无效");
            if (escalateTo != null && !"supervisor".equals(escalateTo))
                throw new IllegalArgumentException("节点失败升级当前仅支持 supervisor");
        }
    }

    public int budgetFor(Node node) {
        return node.budgetMax() != null ? node.budgetMax() : defaults == null ? 0 : defaults.budgetAgentMax();
    }

    public boolean requiresApproval() {
        return defaults != null && Boolean.TRUE.equals(defaults.requireApproval());
    }

    public static String cognitiveStrategy(String strategy, int budget) {
        if (budget <= 0) return strategy;
        if ("tree_of_thoughts".equals(strategy) && budget < 8000) strategy = "chain_of_thought";
        if ("debate".equals(strategy) && budget < 6000) strategy = "reflection";
        if ("reflection".equals(strategy) && budget < 3000
                || "chain_of_thought".equals(strategy) && budget < 4000) return "react";
        return strategy;
    }

    /** 提供节点可选查询，例如“列出需要保留的项目行为”。 */
    public record Metadata(String query, List<Task> tasks, String execution,
                           @JsonProperty("max_concurrency") Integer maxConcurrency,
                           @JsonProperty("role_tools") Map<String, List<String>> roleTools) {
        public Metadata {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
            var roles = new LinkedHashMap<String, List<String>>();
            if (roleTools != null) roleTools.forEach((role, tools) -> {
                if (role == null || !role.matches("[a-zA-Z0-9_-]{1,40}") || tools == null)
                    throw new IllegalArgumentException("角色工具配置无效");
                roles.put(role, List.copyOf(tools));
            });
            roleTools = Map.copyOf(roles);
            if (execution != null && !Set.of("sequential", "parallel", "hybrid").contains(execution))
                throw new IllegalArgumentException("子任务执行方式无效");
            if (maxConcurrency != null && (maxConcurrency < 1 || maxConcurrency > 4
                    || "sequential".equals(execution) && maxConcurrency != 1))
                throw new IllegalArgumentException("子任务并发须为 1～4，sequential 固定为 1");
        }

        public int parallelism() {
            if ("sequential".equals(execution)) return 1;
            return maxConcurrency == null ? (execution == null ? 1 : 2) : maxConcurrency;
        }
    }

    /** 定义 DAG 中的分析子任务，例如在 requirements 完成后汇总验收条件。 */
    public record Task(String id, String description, String query, String prompt,
                       @JsonProperty("depends_on") List<String> dependsOn, String role) {
        public Task {
            if (id == null || !id.matches("[a-zA-Z0-9_-]{1,20}"))
                throw new IllegalArgumentException("模板子任务 ID 无效");
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            if (role != null && !role.matches("[a-zA-Z0-9_-]{1,40}"))
                throw new IllegalArgumentException("分析角色名称无效");
        }

        public String goal() {
            return java.util.stream.Stream.of(description, query, prompt)
                    .filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
        }

        String instructions() {
            return role == null ? goal() : "分析角色：" + role + "\n" + goal();
        }
    }

    /** 接收当前子任务和直接依赖，例如汇总两个已完成分析的结论。 */
    @FunctionalInterface
    public interface TaskAnalysis {
        String run(String id, String query, Map<String, String> outputs) throws Exception;
    }

    /** 声明一条依赖边，例如 requirements → summary。 */
    public record Edge(String from, String to) {}

    /** 接收节点及其直接依赖输出，例如调用模型生成验收清单。 */
    @FunctionalInterface
    public interface Analysis {
        String run(Node node, Map<String, String> outputs) throws Exception;
    }

    public List<DagExecutor.Node> compile(Analysis analysis) {
        return compile(nodes, edges, analysis);
    }

    private static List<DagExecutor.Node> compile(List<Node> nodes, List<Edge> edges, Analysis analysis) {
        return nodes.stream().map(node -> {
            var dependencies = new LinkedHashSet<>(node.dependsOn());
            edges.stream().filter(edge -> node.id().equals(edge.to())).map(Edge::from).forEach(dependencies::add);
            return new DagExecutor.Node("template." + node.id(), dependencies.stream().map(id -> "template." + id).toList(),
                    outputs -> {
                        var inputs = new LinkedHashMap<String, String>();
                        dependencies.forEach(id -> inputs.put(id, outputs.get("template." + id)));
                        return analysis.run(node, inputs);
                    });
        }).toList();
    }
}
