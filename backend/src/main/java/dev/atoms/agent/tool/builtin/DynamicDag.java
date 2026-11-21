package dev.atoms.agent.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.agent.pattern.DagExecutor;

import java.util.*;

/**
 * 将模型提出的分析任务转换成受限的节点图，例如先分析数据结构，再分析界面依赖。
 */
final class DynamicDag {
    static final String PLAN = "v2.plan";
    static final String SUMMARY = "v2.synthesis";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private DynamicDag() {}

    static String execute(ToolContext context) throws Exception {
        var executor = new DagExecutor();
        var persisted = executor.execute(List.of(new DagExecutor.Node(PLAN, List.of(), ignored ->
                context.proposeDag())), context, 1);
        var nodes = parse(persisted.get(PLAN), context);
        return executor.execute(nodes, context, 2).get(SUMMARY);
    }

    record PlannedNode(String id, String task, String editTask, List<String> dependencies) {}

    static List<DagExecutor.Node> parse(String proposal, ToolContext context) {
        var nodes = new ArrayList<DagExecutor.Node>();
        for (var planned : parsePlan(proposal)) {
            String nodeId = "v2.analysis." + planned.id();
            nodes.add(new DagExecutor.Node(nodeId, planned.dependencies().stream().map(id -> "v2.analysis." + id).toList(),
                    outputs -> context.analyze(nodeId, "只返回项目分析与验收建议：\n" + planned.task()
                            + "\n依赖节点输出：\n" + context.encode(outputs))));
        }
        var inputs = nodes.stream().map(DagExecutor.Node::id).toList();
        nodes.add(new DagExecutor.Node(SUMMARY, inputs, outputs ->
                context.analyze("synthesis", "汇总实现约束和验收清单：\n" + context.encode(outputs))));
        return List.copyOf(nodes);
    }

    static List<PlannedNode> parsePlan(String proposal) {
        try {
            var list = JSON.readTree(proposal);
            if (list == null || !list.isArray() || list.isEmpty() || list.size() > 5)
                throw new IllegalArgumentException("DAG 计划必须包含 1～5 个分析节点");
            var nodes = new ArrayList<PlannedNode>();
            for (var entry : list) {
                var id = entry.path("id");
                var task = entry.path("task");
                var dependencies = entry.path("dependsOn");
                if (!id.isTextual() || !id.textValue().matches("[a-z][a-z0-9_-]{0,23}")
                        || !task.isTextual() || task.textValue().isBlank() || task.textValue().length() > 400
                        || !dependencies.isArray() || dependencies.size() > 4)
                    throw new IllegalArgumentException("DAG 节点的标识、分析任务或依赖无效");
                var parents = new ArrayList<String>();
                for (var dependency : dependencies) {
                    if (!dependency.isTextual()) throw new IllegalArgumentException("DAG 依赖必须是节点标识");
                    parents.add(dependency.textValue());
                }
                var edit = entry.get("editTask");
                if (edit != null && (!edit.isTextual() || edit.asText().isBlank() || edit.asText().length() > 400))
                    throw new IllegalArgumentException("DAG 源码任务描述无效");
                nodes.add(new PlannedNode(id.textValue(), task.textValue(),
                        edit == null ? task.textValue() : edit.textValue(), List.copyOf(parents)));
            }
            return List.copyOf(nodes);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("DAG 计划必须是合法 JSON 数组", e);
        }
    }
}
