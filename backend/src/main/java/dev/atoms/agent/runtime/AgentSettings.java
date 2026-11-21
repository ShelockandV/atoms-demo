package dev.atoms.agent.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 定义 Agent 的动作、生成次数、观察窗口和模型调用限制。
 *
 * <p>示例：<code>new AgentSettings(12, 2, 6, true, 20)</code>。</p>
 *
 * Limits apply to the entire persisted run, including resumed executions.
 */
@Component
public record AgentSettings(int maxSteps, int maxGenerations, int observationWindow, boolean requireReview,
                            int maxModelCalls) {
    // Compatibility constructor for callers using only the static validation policy.
    public AgentSettings(int maxSteps, int maxGenerations, int observationWindow) {
        this(maxSteps, maxGenerations, observationWindow, false, 20);
    }

    public AgentSettings(int maxSteps, int maxGenerations, int observationWindow, boolean requireReview) {
        this(maxSteps, maxGenerations, observationWindow, requireReview, 20);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentSettings(
            @Value("${atoms.agent.max-steps:12}") int maxSteps,
            @Value("${atoms.agent.max-generations:2}") int maxGenerations,
            @Value("${atoms.agent.observation-window:6}") int observationWindow,
            @Value("${atoms.agent.require-review:true}") boolean requireReview,
            @Value("${atoms.agent.max-model-calls:20}") int maxModelCalls) {
        if (maxSteps < 3 || maxSteps > 100) throw new IllegalArgumentException("atoms.agent.max-steps 须为3～100");
        if (maxGenerations < 1 || maxGenerations > 10)
            throw new IllegalArgumentException("atoms.agent.max-generations 须为1～10");
        if (observationWindow < 1 || observationWindow > 20)
            throw new IllegalArgumentException("atoms.agent.observation-window 须为1～20");
        if (maxModelCalls < 1 || maxModelCalls > 200)
            throw new IllegalArgumentException("atoms.agent.max-model-calls 须为1～200");
        this.maxSteps = maxSteps;
        this.maxGenerations = maxGenerations;
        this.observationWindow = observationWindow;
        this.requireReview = requireReview;
        this.maxModelCalls = maxModelCalls;
    }
}
