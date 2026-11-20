package dev.atoms.agent.pattern;

import dev.atoms.agent.AgentLoop.Decision;

import java.util.List;

/**
 * 定义基于当前任务进度选择下一步动作的接口。
 *
 * <p>示例：<code>PatternRegistry.require("dag").select(input)</code>。</p>
 *
 * Strategies choose the next action; tool implementations and persistence are shared.
 */
public interface ExecutionPattern {
    /**
     * 把演示模式标记、执行进度和模型动作选择器交给策略。
     *
     * <p>示例：<code>new Input(false, progress, selector)</code>。</p>
     */
    record Input(boolean demo, Progress state, Selector modelAction) {
    }

    /**
     * 提供选动作所需的生成次数、候选文件状态及近期观察。
     *
     * <p>示例：<code>new Progress(0, false, false, false, List.of())</code>。</p>
     */
    record Progress(int generations, boolean hasFiles, boolean validated, boolean reviewed, List<String> observations) {
    }

    /**
     * 延迟执行模型动作选择，由需要它的策略调用。
     *
     * <p>示例：<code>Selector selector = () -&gt; decision;</code>。</p>
     */
    @FunctionalInterface
    interface Selector {
        Decision select() throws Exception;
    }

    String name();

    String description();

    Decision select(Input input) throws Exception;
}
