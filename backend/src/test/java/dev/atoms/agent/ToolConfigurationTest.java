package dev.atoms.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 Spring 启动时将运维配置的工具加入统一注册表。 */
class ToolConfigurationTest {
    @TempDir Path directory;

    @Test void springWiresBothFactoriesIntoTheRegistry() throws Exception {
        var http = Files.writeString(directory.resolve("http.json"), """
                [{"name":"HTTP_DOCS","description":"公开文档","url":"https://docs.example.com/search","query":[]}]
                """);
        var openapi = Files.writeString(directory.resolve("api.json"), """
                {"baseUrl":"https://docs.example.com","operations":{"search":"HTTP_OPENAPI_DOCS"},
                 "spec":{"openapi":"3.1.0","paths":{"/search":{"get":{"operationId":"search"}}}}}
                """);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("tools-test", Map.of(
                    "atoms.agent.http-tools-file", http.toString(), "atoms.agent.openapi-tools-file", openapi.toString())));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(ToolConfiguration.class);
            context.refresh();
            var registry = context.getBean(ToolRegistry.class);
            assertEquals(9, registry.catalog().size());
            assertEquals("HTTP_DOCS", registry.require("HTTP_DOCS").metadata().name());
            assertEquals("HTTP_OPENAPI_DOCS", registry.require("HTTP_OPENAPI_DOCS").metadata().name());
        }
    }
}
