package dev.atoms.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "atoms.demo=false", "atoms.model.api-key=local-workflow-fixture",
        "spring.datasource.url=jdbc:h2:mem:workflow_model_features;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always"
})
class OpenAiWorkflowFeaturesHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    private static HttpServer provider;
    private static final List<com.fasterxml.jackson.databind.JsonNode> REQUESTS = new CopyOnWriteArrayList<>();
    private static final java.util.concurrent.atomic.AtomicInteger CANDIDATES = new java.util.concurrent.atomic.AtomicInteger();

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/chat/completions", exchange -> {
            var mapper = new ObjectMapper();
            try (exchange) {
                var request = mapper.readTree(exchange.getRequestBody());
                REQUESTS.add(request);
                var messages = request.path("messages");
                String system = messages.get(0).path("content").asText();
                String last = messages.get(messages.size() - 1).path("content").asText();
                var message = mapper.createObjectNode().put("role", "assistant");
                String finish = "stop";
                if (system.contains("依据工作流草稿") && !"tool".equals(messages.get(messages.size() - 1).path("role").asText())) {
                    message.putNull("content");
                    message.putArray("tool_calls").addObject().put("id", "call_workflow").put("type", "function")
                            .putObject("function").put("name", "lookup").put("arguments", "{}");
                    finish = "tool_calls";
                } else if (last.contains("把用户问题拆成")) {
                    message.put("content", "[{\"id\":\"summary\",\"query\":\"summarize\",\"depends_on\":[]}]");
                } else if (last.contains("candidate demo")) {
                    message.put("content", "choice-" + CANDIDATES.incrementAndGet());
                } else if (system.contains("依据工作流草稿")) {
                    message.put("content", "completed using client tool");
                } else {
                    message.put("content", "workflow draft");
                }
                var body = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                        "message", message, "finish_reason", finish)),
                        "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5)));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        provider.start();
        registry.add("atoms.model.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
    }

    @AfterAll static void stopProvider() { if (provider != null) provider.stop(0); }

    @Test void workflowModesReturnFunctionCallsAndConsumeClientToolResults() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            for (String model : List.of("shannon-quick-research", "shannon-complex")) {
                var request = json.createObjectNode().put("model", model).put("tool_choice", "auto");
                request.putArray("tools").addObject().put("type", "function").putObject("function")
                        .put("name", "lookup").putObject("parameters").put("type", "object");
                var messages = request.putArray("messages");
                messages.addObject().put("role", "user").put("content", "research workflow question");
                var opened = send(http, request);
                assertEquals(200, opened.statusCode(), opened.body());
                var choice = json.readTree(opened.body()).at("/choices/0");
                assertEquals("tool_calls", choice.path("finish_reason").asText());
                messages.add(choice.get("message"));
                messages.addObject().put("role", "tool").put("tool_call_id", "call_workflow").put("content", "client source");
                request.put("n", 2);
                var returned = send(http, request);
                assertEquals(200, returned.statusCode(), returned.body());
                var choices = json.readTree(returned.body()).path("choices");
                assertEquals(2, choices.size());
                assertEquals("completed using client tool", choices.at("/0/message/content").asText());
                assertEquals(1, choices.at("/1/index").asInt());
            }
        }
    }

    @Test void directChatReturnsDistinctMeteredCandidatesAndForwardsScopedCacheKey() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var request = json.createObjectNode().put("n", 2).put("prompt_cache_key", "raw-client-key");
            request.putArray("messages").addObject().put("role", "user").put("content", "candidate demo");
            var response = send(http, request);
            assertEquals(200, response.statusCode(), response.body());
            var result = json.readTree(response.body());
            assertEquals(2, result.path("choices").size());
            assertNotEquals(result.at("/choices/0/message/content"), result.at("/choices/1/message/content"));
            assertEquals(30, result.at("/usage/total_tokens").asInt());
            var forwarded = REQUESTS.stream().filter(body -> body.path("messages").toString().contains("candidate demo")).toList();
            assertEquals(2, forwarded.size());
            assertTrue(forwarded.getFirst().path("prompt_cache_key").asText().matches("[0-9a-f]{48}"));
            assertEquals(forwarded.getFirst().get("prompt_cache_key"), forwarded.getLast().get("prompt_cache_key"));
        }
    }

    @Test void streamsEachCandidateWithItsOwnIndex() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var request = json.createObjectNode().put("n", 2).put("stream", true);
            request.putObject("stream_options").put("include_usage", true);
            request.putArray("messages").addObject().put("role", "user").put("content", "streaming choices");
            var response = send(http, request);
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"index\":1"));
            assertTrue(response.body().contains("\"total_tokens\":30"));
            assertTrue(response.body().contains("data:[DONE]"));
        }
    }

    private HttpResponse<String> send(HttpClient http, com.fasterxml.jackson.databind.JsonNode input) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(input.toString()));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
