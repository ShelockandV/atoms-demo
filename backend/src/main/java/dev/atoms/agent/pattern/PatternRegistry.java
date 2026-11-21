package dev.atoms.agent.pattern;

import java.util.*;

import dev.atoms.agent.runtime.AgentLoop.Decision;

/**
 * 按策略 ID 查找对应的执行策略，并提供策略目录。
 *
 * <p>示例：<code>PatternRegistry.require("reflection")</code>。</p>
 */
public final class PatternRegistry {
    private static final Map<String, ExecutionPattern> PATTERNS;

    static {
        var patterns = new LinkedHashMap<String, ExecutionPattern>();
        for (var pattern : List.of(new ReAct(), new Reflection(), new Dag())) patterns.put(pattern.name(), pattern);
        PATTERNS = Collections.unmodifiableMap(patterns);
    }

    private PatternRegistry() {
    }

    public static ExecutionPattern require(String name) {
        String selected = name == null || name.isBlank() ? "react" : name;
        var pattern = PATTERNS.get(selected);
        if (pattern == null) throw new IllegalArgumentException("不支持的执行策略：" + selected);
        return pattern;
    }

    public static List<Map<String, String>> catalog() {
        return PATTERNS.values().stream().map(p -> Map.of("id", p.name(), "description", p.description())).toList();
    }

    public static String resolveName(String requested) {
        return require(requested).name();
    }

    /**
     * 让模型结合当前任务进度和近期观察选择下一步。
     *
     * <p>示例：模型可在生成前选择 READ_FILE 查看基准源码。</p>
     */
    private static final class ReAct implements ExecutionPattern {
        public String name() {
            return "react";
        }

        public String description() {
            return "自主选择工具，逐步检查和开发应用";
        }

        public Decision select(Input input) throws Exception {
            return input.modelAction().select();
        }
    }

    /**
     * 先运行并行分析及汇总，再按复核流程生成应用。
     *
     * <p>示例：需求分析和架构分析都完成后才执行汇总。</p>
     */
    private static final class Dag implements ExecutionPattern {
        private final Reflection development = new Reflection();

        public String name() {
            return "dag";
        }

        public String description() {
            return "并行分析需求与架构，汇总后按复核流程开发";
        }

        public Decision select(Input input) {
            var state = input.state();
            if (state.generations() == 0 && !state.hasFiles()
                    && state.observations().stream().noneMatch(o -> o.startsWith("PLAN_DAG: ")))
                return new Decision("PLAN_DAG", "", "并行分析需求与架构，再汇总实现约束");
            return development.select(input);
        }
    }

    public static Decision offlineDecision(ExecutionPattern.Progress state) {
        if (state.validated()) return new Decision("FINISH", "", "保存通过静态校验的源码");
        if (state.hasFiles() && (state.observations().isEmpty()
                || state.observations().getLast().startsWith("GENERATE: 源码生成完成")))
            return new Decision("VALIDATE", "", "检查文件结构与语法");
        return new Decision("GENERATE", "", state.generations() == 0 ? "根据需求编写源码" : "根据上轮观察修复源码");
    }

    /**
     * 根据候选源码的校验与复核状态推进流程。
     *
     * <p>示例：复核发现功能缺漏时回到 GENERATE，让下一次生成处理反馈。</p>
     */
    private static final class Reflection implements ExecutionPattern {
        public String name() {
            return "reflection";
        }

        public String description() {
            return "按固定流程生成、校验、复核，并根据反馈修复";
        }

        public Decision select(Input input) {
            var state = input.state();
            if (state.validated()) {
                if (!state.reviewed() && !input.demo()) {
                    // Failed review must lead to a new generation rather than re-reviewing unchanged code.
                    if (!state.observations().isEmpty() && state.observations().getLast().startsWith("REVIEW: "))
                        return new Decision("GENERATE", "", "根据复核反馈修复源码");
                    return new Decision("REVIEW", "", "复核本轮需求是否落实");
                }
                return new Decision("FINISH", "", "保存已通过检查的源码");
            }
            return offlineDecision(state);
        }
    }
}
