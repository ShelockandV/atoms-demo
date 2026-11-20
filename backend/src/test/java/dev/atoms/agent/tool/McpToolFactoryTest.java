package dev.atoms.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.agent.network.PublicHttps;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证已批准的 MCP 工具通过固定端点发送请求，并校验远程结果。 */
class McpToolFactoryTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String MANIFEST = """
            [{"name":"MCP_DOCS","description":"查询公开文档","endpoint":"https://docs.example.com/mcp",
              "remoteName":"search","readOnly":true,
              "parameters":[{"name":"q","description":"关键词","required":true}]}]
            """;

    @Test void executesRegisteredToolWithAStatelessJsonRpcRequest() throws Exception {
        var tools = McpToolFactory.load(json, MANIFEST.getBytes(StandardCharsets.UTF_8), (uri, allowed, body, name, max) -> {
            assertEquals("https://docs.example.com/mcp", uri.toString());
            assertTrue(allowed.test("docs.example.com"));
            assertFalse(allowed.test("other.example.com"));
            assertEquals("search", name);
            var message = json.readTree(body);
            var params = message.path("params");
            assertEquals("tools/call", message.path("method").asText());
            assertEquals("search", params.path("name").asText());
            assertEquals("java", params.path("arguments").path("q").asText());
            assertEquals("2026-07-28", params.path("_meta").path("io.modelcontextprotocol/protocolVersion").asText());
            var response = Map.of("jsonrpc", "2.0", "id", message.path("id").asText(),
                    "result", Map.of("content", java.util.List.of(Map.of("type", "text", "text", "文档结果"))));
            return new PublicHttps.Download(json.writeValueAsBytes(response), "application/json");
        });
        var executor = new ToolExecutor<>(new ToolRegistry<>(tools));
        assertFalse(executor.execute("MCP_DOCS", Map.of("q", "java", "url", "other"),
                null, json, "owner", () -> {}, () -> () -> {}).success());
        var result = executor.execute("MCP_DOCS", Map.of("q", "java"), null, json, "owner", () -> {}, () -> () -> {});
        assertTrue(result.success());
        assertEquals("文档结果", result.output());
    }

    @Test void requiresExplicitReadOnlyAndRejectsMismatchedResponses() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> McpToolFactory.load(json,
                MANIFEST.replace("\"readOnly\":true", "\"readOnly\":false").getBytes(StandardCharsets.UTF_8)));
        var tool = McpToolFactory.load(json, MANIFEST.getBytes(StandardCharsets.UTF_8),
                (uri, allowed, body, name, max) -> new PublicHttps.Download(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"wrong\",\"result\":{}}".getBytes(StandardCharsets.UTF_8),
                        "application/json")).getFirst();
        assertThrows(IllegalArgumentException.class, () -> tool.execute(null, Map.of("q", "java")));
    }
}
