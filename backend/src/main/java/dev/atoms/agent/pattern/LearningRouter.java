package dev.atoms.agent.pattern;

import dev.atoms.agent.persistence.Store;
import dev.atoms.workspace.PromptSimilarity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/** 根据项目内的执行结果选择策略，例如复用相似任务中表现最好的 Reflection。 */
@Component
public final class LearningRouter {
    private final Store store;
    private final boolean enabled;

    public LearningRouter(Store store, @Value("${atoms.agent.learning-router-enabled:false}") boolean enabled) {
        this.store = store;
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    public String choose(String project, String owner, String prompt, boolean hasBaseVersion) {
        if (!enabled) return "react";
        return recommend(prompt, store.routingHistory(project, owner, hasBaseVersion));
    }

    static String recommend(String prompt, List<Map<String, Object>> history) {
        var similarity = new PromptSimilarity(prompt);
        var outcomes = new TreeMap<String, int[]>();
        for (var run : history) {
            if (similarity.score((String) run.get("prompt")) < 0.8) continue;
            var counts = outcomes.computeIfAbsent((String) run.get("pattern"), ignored -> new int[2]);
            counts[0]++;
            if ("SUCCEEDED".equals(run.get("status"))) counts[1]++;
        }
        return outcomes.entrySet().stream()
                .filter(entry -> entry.getValue()[1] * 10L > entry.getValue()[0] * 7L)
                .max(Comparator.<Map.Entry<String, int[]>>comparingDouble(entry ->
                                (double) entry.getValue()[1] / entry.getValue()[0])
                        .thenComparingInt(entry -> entry.getValue()[1]))
                .map(Map.Entry::getKey).orElse("react");
    }

}
