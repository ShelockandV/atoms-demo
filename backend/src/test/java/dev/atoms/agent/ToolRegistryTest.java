package dev.atoms.agent;



import dev.atoms.agent.model.ModelClient;

import dev.atoms.workspace.ProjectContext;

import dev.atoms.agent.tool.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证工具元数据、注册行为和参数协议。
 *
 * <p>示例：用 READ_FILE 的 schema 核对 path 是否必填。</p>
 */
class ToolRegistryTest {
    final ObjectMapper json = new ObjectMapper();
    final ToolRegistry<dev.atoms.agent.tool.builtin.ToolContext> registry = ToolConfiguration.registry();

    @Test void metadataAndModelSchemasComeFromTheSameCatalog() {
        var names = registry.catalog().stream().map(item -> ((ToolMetadata) item.get("metadata")).name()).toList();
        assertEquals(List.of("PLAN_DAG", "LIST_FILES", "READ_FILE", "GENERATE", "VALIDATE", "REVIEW", "FINISH"), names);
        var read = registry.modelDefinitions().stream()
                .map(definition -> (Map<String, Object>) definition.get("function"))
                .filter(function -> function.get("name").equals("READ_FILE")).findFirst().orElseThrow();
        var parameters = (Map<String, Object>) read.get("parameters");
        assertEquals(List.of("path"), parameters.get("required"));
        assertEquals(false, parameters.get("additionalProperties"));
        assertEquals(240, ((Map<?, ?>) ((Map<?, ?>) parameters.get("properties")).get("path")).get("maxLength"));
    }

    @Test void parameterSchemaIsEnforcedForRequiredTypesLimitsAndUnknownFields() {
        var read = registry.require("READ_FILE");
        assertThrows(IllegalArgumentException.class, () -> registry.validate(read, Map.of(), json));
        assertThrows(IllegalArgumentException.class, () -> registry.validate(read, Map.of("path", 3), json));
        assertThrows(IllegalArgumentException.class, () -> registry.validate(read, Map.of("path", " "), json));
        assertThrows(IllegalArgumentException.class, () -> registry.validate(read, Map.of("path", "x".repeat(241)), json));
        assertThrows(IllegalArgumentException.class, () -> registry.validate(read, Map.of("path", "index.html", "shell", "cmd"), json));
        assertDoesNotThrow(() -> registry.validate(read, Map.of("path", "index.html"), json));
        assertThrows(IllegalArgumentException.class, () -> registry.validate(registry.require("FINISH"), Map.of("validated", true), json));
    }

    @Test void registeredExtensionIsVisibleToModelWithoutEditingTheLoopOrPrompt() throws Exception {
        AgentTool<dev.atoms.agent.tool.builtin.ToolContext> custom = new AgentTool<>() {
            public ToolMetadata metadata() { return new ToolMetadata("CUSTOM_CHECK", "1", "检查自定义项目约束", "quality", 5, false, false, true, ToolMetadata.Scope.VIRTUAL_WORKSPACE); }
            public List<ToolParameter> parameters() { return List.of(new ToolParameter("level", ToolParameter.Type.STRING, "检查级别", true, 10, List.of("basic", "full"))); }
            public String execute(dev.atoms.agent.tool.builtin.ToolContext context, Map<String, Object> arguments) { return "ok"; }
        };
        var customRegistry = new ToolRegistry<>(List.of(custom));
        var model = new ModelClient(json, false, "http://unused", "test", "test", 5, 100, customRegistry) {
            @Override public String completion(List<Map<String, String>> messages, String selectedModel) {
                assertTrue(messages.getFirst().get("content").contains("CUSTOM_CHECK"));
                assertTrue(messages.getFirst().get("content").contains("检查自定义项目约束"));
                return "{\"action\":\"CUSTOM_CHECK\",\"arguments\":{\"level\":\"full\"}}";
            }
        };
        var decision = AgentLoop.chooseAction(model, "目标", "计划", new ProjectContext("项目", null, List.of(), Map.of()),
                new AgentLoop.Snapshot(0, 0, Map.of(), false, false, List.of(), null, false), new AgentSettings(12, 2, 6), "test");
        assertEquals("CUSTOM_CHECK", decision.action());
        customRegistry.validate(custom, decision.arguments(), json);
        assertThrows(IllegalArgumentException.class, () -> customRegistry.validate(custom, Map.of("level", "unsupported"), json));
        assertThrows(IllegalArgumentException.class, () -> new ToolRegistry<>(List.of(custom, custom)));
    }

    @Test void legacyPathCheckpointsStillDecodeToStructuredArguments() throws Exception {
        var decision = json.readValue("{\"action\":\"READ_FILE\",\"path\":\"index.html\",\"summary\":\"恢复\"}", AgentLoop.Decision.class);
        assertEquals(Map.of("path", "index.html"), decision.arguments());
        assertEquals(decision, json.readValue(json.writeValueAsString(decision), AgentLoop.Decision.class));
    }
}
