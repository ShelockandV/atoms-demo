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
        var tools = McpToolFactory.load(json, MANIFEST.getBytes(StandardCharsets.UTF_8), (uri, allowed, body, name, max, sse) -> {
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

    @Test void extractsMatchingResponseFromEventStreamBeforeTheServerClosesIt() throws Exception {
        var tools = McpToolFactory.load(json, MANIFEST.getBytes(StandardCharsets.UTF_8),
                (uri, allowed, request, name, max, sse) -> {
                    String id = json.readTree(request).path("id").asText();
                    String wire = ": ready\r\n\r\n"
                            + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\r\n\r\n"
                            + "data: {\"jsonrpc\":\"2.0\",\"id\":\"some-other-id\",\"result\":{}}\n\n"
                            + "data: {\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\n"
                            + "data: \"result\":{\"content\":[{\"type\":\"text\",\"text\":\"stream result\"}]}}\n\n"
                            + "data: malformed event after result\n\n";
                    var stream = new java.io.ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8));
                    return new PublicHttps.Download(sse.read(stream, max), "application/json");
                });
        assertEquals("stream result", tools.getFirst().execute(null, Map.of("q", "java")));
    }

    @Test void rejectsMissingResponsesAndOversizedEventStreams() {
        var noResponse = new java.io.ByteArrayInputStream("data: {}\n\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> McpEventStream.read(noResponse, 16384, json, "request"));
        var oversized = new java.io.ByteArrayInputStream(("data: " + "x".repeat(100)).getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> McpEventStream.read(oversized, 30, json, "request"));
    }

    @Test void discoversApprovedToolAcrossPagesAndUsesItsSchema() throws Exception {
        String discovered = MANIFEST.replace("""
                "parameters":[{"name":"q","description":"关键词","required":true}]
                """.strip(), "\"discover\":true");
        var tools = McpToolFactory.load(json, discovered.getBytes(StandardCharsets.UTF_8),
                (uri, allowed, body, name, max, sse) -> {
                    var request = json.readTree(body);
                    var params = request.path("params");
                    Object result;
                    if ("tools/list".equals(request.path("method").asText())) {
                        result = !params.has("cursor") ? Map.of("tools", java.util.List.of(), "nextCursor", "page-two")
                                : json.readTree("""
                                    {"tools":[{"name":"search","annotations":{"readOnlyHint":true},
                                     "inputSchema":{"type":"object","properties":{"budget":{"type":"integer"}},
                                                    "required":["budget"]}}]}
                                    """);
                    } else {
                        assertEquals(4, params.at("/arguments/budget").asInt());
                        result = Map.of("structuredContent", Map.of("found", true));
                    }
                    return new PublicHttps.Download(json.writeValueAsBytes(Map.of("jsonrpc", "2.0",
                            "id", request.path("id").asText(), "result", result)), "application/json");
                });
        assertEquals(ToolParameter.Type.INTEGER, tools.getFirst().parameters().getFirst().type());
        assertEquals("{\"found\":true}", tools.getFirst().execute(null, Map.of("budget", 4)));
    }

    @Test void initializesLegacySseAndPostsToTheAnnouncedEndpoint() throws Exception {
        var methods = new java.util.ArrayList<String>();
        try (var output = new java.io.PipedOutputStream(); var input = new java.io.PipedInputStream(output, 4096)) {
            output.write("event: endpoint\ndata: /messages?sessionId=test\n\n".getBytes(StandardCharsets.UTF_8));
            var result = McpToolFactory.legacyExchange(json, input, (path, bytes) -> {
                assertEquals("/messages?sessionId=test", path);
                var request = json.readTree(bytes);
                String method = request.path("method").asText();
                methods.add(method);
                if (!request.has("id")) return;
                var response = Map.of("jsonrpc", "2.0", "id", request.path("id").asText(), "result",
                        "initialize".equals(method) ? Map.of("protocolVersion", "2024-11-05") : Map.of("ok", true));
                output.write(("event: message\ndata: " + json.writeValueAsString(response) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            }, "tools/call", Map.of("name", "search"));
            assertEquals(java.util.List.of("initialize", "notifications/initialized", "tools/call"), methods);
            assertTrue(result.path("ok").asBoolean());
        }
    }

    @Test void oauthClientCredentialsFetchAndCacheABearerToken() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var oauth = new McpOAuth(java.net.URI.create("https://auth.example.com/token"), "client", "local-test-secret",
                "tools:read", "https://docs.example.com/mcp", json, (endpoint, form, auth) -> {
            calls.incrementAndGet();
            assertEquals("auth.example.com", endpoint.getHost());
            assertTrue(auth.startsWith("Basic "));
            assertTrue(form.contains("grant_type=client_credentials"));
            assertTrue(form.contains("scope=tools%3Aread"));
            return new PublicHttps.Download("""
                    {"access_token":"mock.token","token_type":"Bearer","expires_in":600}
                    """.getBytes(StandardCharsets.UTF_8), "application/json");
        });
        assertEquals("mock.token", oauth.token());
        assertEquals("mock.token", oauth.token());
        assertEquals(1, calls.get());
    }

    @Test void requiresExplicitReadOnlyAndRejectsMismatchedResponses() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> McpToolFactory.load(json,
                MANIFEST.replace("\"readOnly\":true", "\"readOnly\":false").getBytes(StandardCharsets.UTF_8)));
        var tool = McpToolFactory.load(json, MANIFEST.getBytes(StandardCharsets.UTF_8),
                (uri, allowed, body, name, max, sse) -> new PublicHttps.Download(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"wrong\",\"result\":{}}".getBytes(StandardCharsets.UTF_8),
                        "application/json")).getFirst();
        assertThrows(IllegalArgumentException.class, () -> tool.execute(null, Map.of("q", "java")));
    }
}
