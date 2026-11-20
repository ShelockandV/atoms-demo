package dev.atoms.agent.pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/** 按得分优先探索方案。例如，剪除无法满足验收条件的方案，再细化最高分方案。 */
public final class TreeOfThoughts {
    private static final int WIDTH = 3;
    private static final int MAX_DEPTH = 3;
    private static final int MAX_BRANCHES = 15;
    private static final double MIN_SCORE = 0.3;

    /** 调用模型生成或汇总方案，例如根据父方案列出三个可评分的替代方案。 */
    @FunctionalInterface
    public interface Analysis {
        String run(String stage, String instructions) throws Exception;
    }

    /** 保存候选方案和归一化评分，例如得分 0.8 的最小改动方案。 */
    private record Candidate(String proposal, Double score, boolean terminal) {}

    /** 保存当前搜索路径，例如从初始架构方案延伸到具体实现方案。 */
    private record Path(List<String> proposals, double score, boolean terminal) {}

    public String execute(String prefix, DagExecutor.Runtime runtime, ObjectMapper json, Analysis analysis) throws Exception {
        var order = Comparator.comparingDouble(Path::score).reversed()
                .thenComparing(path -> String.join("\n", path.proposals()));
        var queue = new PriorityQueue<>(order);
        queue.add(new Path(List.of(), 1, false));
        Path best = null;
        int explored = 0;
        while (!queue.isEmpty() && explored < MAX_BRANCHES) {
            var parent = queue.remove();
            String stage = prefix + "tree.expand." + explored;
            int width = Math.min(WIDTH, MAX_BRANCHES - explored);
            String source = "当前方案路径：" + json.writeValueAsString(parent.proposals())
                    + "\n提出最多 " + width + " 个可行分支。只返回 JSON 数组。每项须包含 proposal（不超过 300 字）、"
                    + "score（0 到 1，按可行性与需求覆盖评分）和 terminal（布尔值）。";
            String saved = checkpoint(stage, runtime, () -> {
                var candidates = parse(analysis.run(stage, source), json, width);
                return json.writeValueAsString(candidates);
            });
            var candidates = parse(saved, json, width);
            explored += candidates.size();
            for (var candidate : candidates) {
                if (candidate.score() < MIN_SCORE) continue;
                var proposals = new ArrayList<>(parent.proposals());
                proposals.add(candidate.proposal());
                var branch = new Path(List.copyOf(proposals), candidate.score(), candidate.terminal());
                if (best == null || order.compare(branch, best) < 0) best = branch;
                if (!branch.terminal() && proposals.size() < MAX_DEPTH) queue.add(branch);
            }
        }
        if (best == null) return "没有候选方案达到模板要求的最低评分。";
        String stage = prefix + "tree.synthesis";
        String path = json.writeValueAsString(best.proposals());
        return checkpoint(stage, runtime, () -> analysis.run(stage, "将选中的方案路径汇总为实施和验收计划：" + path));
    }

    private static String checkpoint(String id, DagExecutor.Runtime runtime, java.util.concurrent.Callable<String> work)
            throws Exception {
        return new DagExecutor().execute(List.of(new DagExecutor.Node(id, List.of(), ignored -> work.call())),
                runtime, 1).get(id);
    }

    private static List<Candidate> parse(String text, ObjectMapper json, int width) throws Exception {
        var value = json.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > width)
            throw new IllegalArgumentException("Tree of Thoughts 要求返回 1～" + width + " 个候选方案");
        var result = new ArrayList<Candidate>();
        for (var entry : value) {
            var proposal = entry.path("proposal");
            var score = entry.path("score");
            var terminal = entry.path("terminal");
            if (!proposal.isTextual() || proposal.asText().isBlank() || proposal.asText().length() > 300
                    || !score.isNumber() || !Double.isFinite(score.asDouble()) || score.asDouble() < 0
                    || score.asDouble() > 1 || !terminal.isBoolean())
                throw new IllegalArgumentException("Tree of Thoughts 候选方案、评分或终止标志无效");
            result.add(new Candidate(proposal.asText(), score.asDouble(), terminal.asBoolean()));
        }
        return result;
    }
}
