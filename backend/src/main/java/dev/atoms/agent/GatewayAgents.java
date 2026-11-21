package dev.atoms.agent;

import dev.atoms.agent.model.ModelClient;
import dev.atoms.agent.persistence.Store;
import dev.atoms.agent.tool.ToolExecutor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Component
public final class GatewayAgents {
    public static final String MODE = "gateway-agent";
    private static final Set<String> WORKSPACE_READERS = Set.of("LIST_FILES", "READ_FILE", "SEARCH_DOCS");

    private final Map<String, Map<String, Object>> agents = new TreeMap<>();
    private final ModelClient model;

    GatewayAgents(Environment environment, ModelClient model) {
        this.model = model;
        var configured = Binder.get(environment).bind("atoms.gateway.agents", Bindable.mapOf(String.class, Definition.class))
                .orElse(Map.of());
        configured.forEach((id, definition) -> {
            if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}") || definition.name() == null || definition.name().isBlank()
                    || definition.description() == null || definition.description().isBlank()
                    || definition.category() == null || definition.category().isBlank()
                    || definition.tool() == null || !toolAllowed(model, definition.tool()))
                throw new IllegalArgumentException("Gateway Agent 配置无效：" + id);
            var schema = model.toolCatalog().stream()
                    .filter(entry -> ((dev.atoms.agent.tool.ToolMetadata) entry.get("metadata")).name().equals(definition.tool()))
                    .findFirst().orElseThrow().get("parameters");
            agents.put(id, Map.of("id", id, "name", definition.name(), "description", definition.description(),
                    "category", definition.category(), "tool", definition.tool(), "input_schema", schema));
        });
    }

    record Definition(String name, String description, String category, String tool) {}

    public List<Map<String, Object>> list() { return List.copyOf(agents.values()); }

    public Map<String, Object> get(String id) {
        var agent = agents.get(id);
        if (agent == null) throw Store.error(404, "Gateway Agent 不存在");
        return agent;
    }

    public String validate(String id, Map<String, Object> input) {
        String tool = (String) get(id).get("tool");
        validateArguments(tool, input);
        return tool;
    }

    public void validateDirectTool(String name, Map<String, Object> arguments) {
        try {
            model.tools().require(name);
        } catch (IllegalArgumentException unknown) {
            throw Store.error(404, "工具不存在");
        }
        if (!toolAllowed(model, name)) throw Store.error(403, "此工具不可通过 Gateway 直接执行");
        validateArguments(name, arguments);
    }

    private void validateArguments(String tool, Map<String, Object> arguments) {
        model.tools().validate(model.tools().require(tool), arguments, model.json());
    }

    static boolean toolAllowed(ModelClient model, String name) {
        var tool = model.tools().require(name);
        var metadata = tool.metadata();
        return !metadata.requiresAuth() && !metadata.dangerous()
                && (WORKSPACE_READERS.contains(name) || ToolExecutor.isConfiguredReadTool(tool));
    }
}
