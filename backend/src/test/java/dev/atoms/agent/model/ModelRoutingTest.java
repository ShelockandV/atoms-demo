package dev.atoms.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** 验证自动选型与 HTTP 故障降级。例如构建模型返回 503 时尝试一次备用模型。 */
class ModelRoutingTest {
    @Test void autoRoutesOnlyWhenRequestedAndUsesTheDefaultForComplexBuilds() {
        var catalog = catalog();
        assertEquals("fast", catalog.route("build", "auto", "新建页面", null, "react"));
        assertEquals("primary", catalog.route("build", "auto", "修改页面", "v1", "react"));
        assertEquals("primary", catalog.route("build", "auto", "新建页面", null, "dag"));
        assertEquals("primary", catalog.route("build", "primary", "新建页面", null, "react"));
        ReflectionTestUtils.setField(catalog, "buildFallback", "unknown");
        assertThrows(IllegalArgumentException.class, catalog::validateRouting);
    }

    @Test void retryableHttpFailureChargesEachAttemptAndSettlesOnlyReportedUsage() throws Exception {
        var json = new ObjectMapper();
        var attempts = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                String model = json.readTree(exchange.getRequestBody()).path("model").asText();
                attempts.add(model);
                boolean primary = model.equals("primary");
                byte[] response = (primary ? "{}" :
                        "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(primary ? 503 : 200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var model = new ModelClient(json, false, "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test", "primary", 5, 16);
            ReflectionTestUtils.setField(model, "catalog", catalog());
            ReflectionTestUtils.setField(model, "circuit", new ModelCircuitBreaker(1, Long.MAX_VALUE, System::nanoTime));
            var reserves = new ArrayList<Long>();
            var settlements = new ArrayList<Long>();
            try (var scope = model.track(reserves::add, (estimate, input, output) ->
                    settlements.add(input + output), () -> "build")) {
                assertEquals("ok", model.completion(List.of(Map.of("role", "user", "content", "hi")), "primary"));
                assertEquals("ok", model.completion(List.of(Map.of("role", "user", "content", "hi")), "primary"));
            }
            assertEquals(List.of("primary", "fallback", "fallback"), attempts);
            assertEquals(3, reserves.size());
            assertEquals(List.of(15L, 15L), settlements);
        } finally {
            server.stop(0);
        }
    }

    @Test void fallbackUsesSeparateProtocolCredentialsAndAliasForSettlement() throws Exception {
        var json = new ObjectMapper();
        var requests = new CopyOnWriteArrayList<String>();
        var primary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var fallback = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        primary.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                var request = json.readTree(exchange.getRequestBody());
                requests.add(exchange.getRequestHeaders().getFirst("Authorization") + ":" + request.path("model").asText());
                exchange.sendResponseHeaders(503, -1);
            }
        });
        fallback.createContext("/v1/messages", exchange -> {
            try (exchange) {
                var request = json.readTree(exchange.getRequestBody());
                requests.add(exchange.getRequestHeaders().getFirst("x-api-key") + ":" + request.path("model").asText());
                requests.add(request.path("system").asText() + ":" + request.path("messages").size());
                byte[] response = """
                        {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
                         "usage":{"input_tokens":12,"cache_read_input_tokens":2,"output_tokens":3}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        primary.start();
        fallback.start();
        try {
            var properties = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of(
                    "atoms.model.providers.primary.protocol", "openai-compatible",
                    "atoms.model.providers.primary.base-url", "http://127.0.0.1:" + primary.getAddress().getPort() + "/v1",
                    "atoms.model.providers.primary.api-key", "key-primary",
                    "atoms.model.providers.primary.models.primary", "upstream-primary",
                    "atoms.model.providers.fallback.protocol", "anthropic",
                    "atoms.model.providers.fallback.base-url", "http://127.0.0.1:" + fallback.getAddress().getPort() + "/v1",
                    "atoms.model.providers.fallback.api-key", "key-fallback",
                    "atoms.model.providers.fallback.models.fallback", "upstream-fallback"));
            var providers = new org.springframework.boot.context.properties.bind.Binder(properties)
                    .bind("atoms.model", TextProviders.class).get();
            var client = new ModelClient(json, false, "http://unused", "", "primary", 5, 64);
            client.configureProviders(providers);
            ReflectionTestUtils.setField(client, "catalog", catalog());
            assertTrue(client.isConfigured());
            var attempts = new ArrayList<Long>();
            var usage = new ArrayList<String>();
            try (var scope = client.trackByModel(attempts::add,
                    (alias, estimate, input, output) -> usage.add(alias + ":" + input + ":" + output), () -> "build", 0, null)) {
                assertEquals("ok", client.completion(List.of(Map.of("role", "system", "content", "rules"),
                        Map.of("role", "user", "content", "hello")), "primary"));
            }
            assertEquals(List.of("Bearer key-primary:upstream-primary", "key-fallback:upstream-fallback", "rules:1"), requests);
            assertEquals(2, attempts.size());
            assertEquals(List.of("fallback:14:3"), usage);
        } finally {
            primary.stop(0);
            fallback.stop(0);
        }
    }

    @Test void anthropicAdapterConvertsNativeToolCalls() throws Exception {
        var json = new ObjectMapper();
        var input = AnthropicMessages.request(json, List.of(Map.of("role", "user", "content", "read")),
                Map.of("tool_choice", "required", "tools", List.of(Map.of("function", Map.of(
                        "name", "READ_FILE", "description", "read", "parameters", Map.of("type", "object"))))));
        var request = json.valueToTree(input);
        assertEquals("any", request.path("tool_choice").path("type").asText());
        assertTrue(request.path("tool_choice").path("disable_parallel_tool_use").asBoolean());
        assertEquals("object", request.path("tools").get(0).path("input_schema").path("type").asText());
        var result = AnthropicMessages.message(json, json.readTree("""
                {"content":[{"type":"tool_use","id":"tool-use","name":"READ_FILE","input":{"path":"index.html"}}]}
                """));
        var function = result.path("tool_calls").get(0).path("function");
        assertEquals("READ_FILE", function.path("name").asText());
        assertEquals("index.html", json.readTree(function.path("arguments").asText()).path("path").asText());
    }

    @Test void nativeProvidersTranslateParallelToolResultsAndPreserveGeminiSignature() throws Exception {
        var json = new ObjectMapper();
        var gemini = GeminiContent.message(json, json.readTree("""
                {"content":{"parts":[
                  {"functionCall":{"id":"gem-one","name":"lookup","args":{"item":"one"}},"thoughtSignature":"c2ln"},
                  {"functionCall":{"name":"lookup","args":{"item":"two"}}}
                ]}}
                """));
        var calls = gemini.path("tool_calls");
        String secondId = calls.get(1).path("id").asText();
        assertFalse(secondId.isBlank());
        var history = List.of(Map.of("role", "user", "content", "check status"),
                Map.of("role", "assistant", "content", "", "tool_calls", calls.toString()),
                Map.of("role", "tool", "content", "second", "tool_call_id", secondId),
                Map.of("role", "tool", "content", "first", "tool_call_id", "gem-one"));
        var claude = json.valueToTree(AnthropicMessages.request(json, history, Map.of()));
        assertEquals("tool_use", claude.at("/messages/1/content/0/type").asText());
        assertEquals("one", claude.at("/messages/1/content/0/input/item").asText());
        assertEquals(secondId, claude.at("/messages/2/content/0/tool_use_id").asText());
        assertEquals("first", claude.at("/messages/2/content/1/content").asText());
        var google = json.valueToTree(GeminiContent.request(json, history, Map.of(), 500));
        assertEquals("c2ln", google.at("/contents/1/parts/0/thoughtSignature").asText());
        assertEquals("gem-one", google.at("/contents/2/parts/0/functionResponse/id").asText());
        assertEquals("first", google.at("/contents/2/parts/0/functionResponse/response/output").asText());
        assertEquals("second", google.at("/contents/2/parts/1/functionResponse/response/output").asText());
        assertTrue(google.at("/contents/2/parts/1/functionResponse/id").isMissingNode());
    }

    @Test void geminiUsesNativeEndpointAndSettlesToolAndThoughtTokens() throws Exception {
        var json = new ObjectMapper();
        var requests = new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var keys = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/upstream-gemini:generateContent", exchange -> {
            try (exchange) {
                keys.add(Objects.toString(exchange.getRequestHeaders().getFirst("x-goog-api-key"), ""));
                requests.add(json.readTree(exchange.getRequestBody()));
                byte[] response = """
                        {"candidates":[{"content":{"parts":[
                          {"thought":true,"text":"hidden"},
                          {"functionCall":{"name":"READ_FILE","args":{"path":"index.html"}}}
                        ]},"finishReason":"STOP"}],
                        "usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":3,
                         "toolUsePromptTokenCount":2,"thoughtsTokenCount":4}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var transport = new TextModelTransport(json, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta",
                    "gemini-key", 5, 64, TextModelTransport.Protocol.GEMINI);
            var usage = new ArrayList<Long>();
            var output = transport.complete(List.of(Map.of("role", "system", "content", "rules"),
                            Map.of("role", "assistant", "content", "previous"), Map.of("role", "user", "content", "read")),
                    "models/upstream-gemini", Map.of("tool_choice", "required", "tools", List.of(Map.of("function",
                            Map.of("name", "READ_FILE", "description", "read", "parameters", Map.of("type", "object"))))),
                    32, null, estimate -> assertTrue(estimate > 32),
                    (estimate, input, completion) -> usage.addAll(List.of(input, completion)));
            var request = requests.getFirst();
            assertEquals(List.of("gemini-key"), keys);
            assertEquals("rules", request.at("/systemInstruction/parts/0/text").asText());
            assertEquals("model", request.at("/contents/0/role").asText());
            assertEquals(32, request.at("/generationConfig/maxOutputTokens").asInt());
            assertEquals("object", request.at("/tools/0/functionDeclarations/0/parametersJsonSchema/type").asText());
            assertEquals("ANY", request.at("/toolConfig/functionCallingConfig/mode").asText());
            assertEquals("", output.path("content").asText());
            assertEquals("READ_FILE", output.at("/tool_calls/0/function/name").asText());
            assertEquals("index.html", json.readTree(output.at("/tool_calls/0/function/arguments").asText()).path("path").asText());
            assertEquals(List.of(14L, 7L), usage);
        } finally {
            server.stop(0);
        }
    }

    private ModelCatalog catalog() {
        var catalog = new ModelCatalog("primary", "fast,fallback", "chat", "", "image", "");
        ReflectionTestUtils.setField(catalog, "buildFast", "fast");
        ReflectionTestUtils.setField(catalog, "buildFallback", "fallback");
        catalog.validateRouting();
        return catalog;
    }
}
