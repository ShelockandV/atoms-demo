package dev.atoms.agent.tool.builtin;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.agent.pattern.DagExecutor;
import dev.atoms.workspace.SourceFiles;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class DagSourceEdits {
    private static final int OUTPUT_LIMIT = 48_000;
    private static final int RECONCILIATION_LIMIT = 24_000;
    private static final String PREFIX = "v3.edits.";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private DagSourceEdits() {}

    static Map<String, String> generate(ToolContext context, String constraints, String plan) throws Exception {
        var planned = DynamicDag.parsePlan(plan);
        var graph = planned.stream().map(node -> new DagExecutor.Node(PREFIX + node.id(),
                node.dependencies().stream().map(id -> PREFIX + id).toList(), parentOutputs -> {
                    String proposal = context.proposeDagEdit(PREFIX + node.id(),
                            "节点负责：" + node.editTask() + constraints
                                    + "\n依赖节点的改动建议（相对于同一基准）：" + context.encode(parentOutputs));
                    parse(proposal);
                    return proposal;
                }, OUTPUT_LIMIT)).toList();
        Map<String, String> outputs;
        try {
            outputs = new DagExecutor().execute(graph, context, 2);
        } catch (IllegalArgumentException invalid) {
            return context.generate(constraints + "\n分节点源码输出无效。请按本轮要求生成完整源码。");
        }
        Map<String, String> candidate;
        try {
            var ordered = graph.stream().map(node -> outputs.get(node.id())).toList();
            candidate = merge(context.baseline(), ordered);
            SourceFiles.validate(candidate);
        } catch (IllegalArgumentException conflict) {
            String proposals = context.encode(outputs);
            return context.generate(constraints + "\n分节点改动须统一合并：" + conflict.getMessage()
                    + "\n请解决冲突并输出完整源码，保留未受影响的基准文件。分节点建议："
                    + proposals.substring(0, Math.min(RECONCILIATION_LIMIT, proposals.length())));
        }
        if (candidate.equals(context.baseline()))
            return context.generate(constraints + "\n分节点源码未产生实际改动。请完成本轮需求。");
        return candidate;
    }

    static Map<String, String> merge(Map<String, String> baseline, Iterable<String> outputs) {
        var changes = new LinkedHashMap<String, String>();
        var spellings = new LinkedHashMap<String, String>();
        baseline.keySet().forEach(path -> spellings.put(path.toLowerCase(Locale.ROOT), path));
        for (String output : outputs) {
            parse(output).forEach((path, value) -> {
                String folded = path.toLowerCase(Locale.ROOT);
                String previous = spellings.putIfAbsent(folded, path);
                if (previous != null && !previous.equals(path))
                    throw new IllegalArgumentException("文件路径大小写冲突：" + previous + " / " + path);
                if (changes.containsKey(path) && !Objects.equals(changes.get(path), value))
                    throw new IllegalArgumentException("多个节点对同一文件的修改冲突：" + path);
                changes.put(path, value);
            });
        }
        var result = new LinkedHashMap<>(baseline);
        changes.forEach((path, value) -> {
            if (value == null) result.remove(path);
            else result.put(path, value);
        });
        return result;
    }

    private static Map<String, String> parse(String output) {
        if (output == null || output.length() > OUTPUT_LIMIT)
            throw new IllegalArgumentException("DAG 节点源码输出超过长度限制");
        try {
            var tree = JSON.readTree(output);
            if (tree == null || !tree.isObject() || tree.size() > 12)
                throw new IllegalArgumentException("DAG 节点须输出最多 12 个路径的对象");
            var edits = new LinkedHashMap<String, String>();
            tree.fields().forEachRemaining(entry -> {
                SourceFiles.validatePath(entry.getKey());
                var value = entry.getValue();
                if (!value.isNull() && !value.isTextual())
                    throw new IllegalArgumentException("DAG 文件改动须为完整源码或 null");
                edits.put(entry.getKey(), value.textValue());
            });
            return edits;
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("DAG 文件改动不是合法 JSON", invalid);
        }
    }
}
