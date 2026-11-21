package dev.atoms.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import dev.atoms.agent.tool.builtin.BuiltinTools;
import dev.atoms.agent.tool.builtin.ToolContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 Spring 管理的内置工具注册表。
 *
 * <p>示例：ToolConfiguration.registry() 创建可查询内置动作的注册表。</p>
 */
@Configuration(proxyBeanMethods = false)
class ToolConfiguration {
    static ToolRegistry<ToolContext> registry() {
        return new ToolRegistry<>(BuiltinTools.create());
    }

    @Bean
    static ToolRegistry<ToolContext> configuredTools(ObjectMapper json,
            @Value("${atoms.agent.http-tools-file:}") String file,
            @Value("${atoms.agent.openapi-tools-file:}") String openApiFile,
            @Value("${atoms.agent.mcp-tools-file:}") String mcpFile) throws java.io.IOException {
        if (file.isBlank() && openApiFile.isBlank() && mcpFile.isBlank()) return registry();
        var tools = new java.util.ArrayList<AgentTool<ToolContext>>(BuiltinTools.create());
        if (!file.isBlank()) {
            try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(file))) {
                tools.addAll(HttpToolFactory.load(json, input.readNBytes(65_537)));
            }
        }
        if (!openApiFile.isBlank()) {
            try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(openApiFile))) {
                tools.addAll(OpenApiToolImport.load(json, input.readNBytes(262_145)));
            }
        }
        if (!mcpFile.isBlank()) {
            try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(mcpFile))) {
                tools.addAll(McpToolFactory.load(json, input.readNBytes(65_537)));
            }
        }
        return new ToolRegistry<>(tools);
    }
}
