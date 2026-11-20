package dev.atoms.agent.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** 一个已解析的 Shannon 格式技能版本。例如 code-review@1.0.0。 */
public record Skill(String name, String version, String author, String category, String description,
                    @JsonProperty("requires_tools") List<String> requiresTools,
                    @JsonProperty("requires_role") String requiresRole,
                    @JsonProperty("budget_max") long budgetMax, boolean dangerous, boolean enabled,
                    Map<String, Object> metadata, String content) {
    public Skill {
        requiresTools = List.copyOf(requiresTools);
        metadata = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
    }

    public String reference() { return name + "@" + version; }

    public Map<String, Object> summary() {
        return Map.of("name", name, "version", version, "author", author, "description", description,
                "category", category, "requires_tools", requiresTools, "requires_role", requiresRole,
                "budget_max", budgetMax, "dangerous", dangerous, "enabled", enabled);
    }
}
