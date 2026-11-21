package dev.atoms.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "atoms.model.api-key=local-memory-fixture",
        "atoms.gateway.tenant-tokens.memory=test-tenant-root",
        "atoms.gateway.jwt.signing-key=private-memory-test-signing-secret",
        "atoms.memory.user-files.private-recall-enabled=true",
        "atoms.memory.user-files.recall-enabled=true", "atoms.memory.user-files.auto-write-enabled=true",
        "spring.sql.init.mode=always"
})
class GatewayPrivateMemoryHttpTest extends dev.atoms.MySqlTestDatabase {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    private static HttpServer provider;
    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static final String NOTE = "用户约定所有示例使用 UTC。";

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) throws Exception {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/chat/completions", exchange -> {
            var mapper = new ObjectMapper();
            try (exchange) {
                var messages = mapper.readTree(exchange.getRequestBody()).path("messages");
                boolean extract = messages.get(0).path("content").asText().contains("提取可供之后任务参考");
                if (!extract) REQUESTS.add(messages.toString());
                String content = extract ? NOTE : "已确认使用 UTC。".repeat(70);
                var body = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                        "message", Map.of("content", content), "finish_reason", "stop")),
                        "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5)));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        provider.start();
        registry.add("atoms.model.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
    }

    @AfterAll static void stopProvider() { if (provider != null) provider.stop(0); }

    @Test void successfulTasksWritePrivateNotesAndOnlyTheSameIdentityRecallsThem() throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            String firstUser = token("account-a");
            String otherUser = token("account-b");
            String firstTask = submit(http, firstUser, "initial request");
            complete(http, firstUser, firstTask);
            String notePath = "/api/v1/memory/files/extracted/" + firstTask + ".md";
            var note = call(http, firstUser, notePath, null);
            assertEquals(200, note.statusCode(), note.body());
            assertEquals(NOTE, json.readTree(note.body()).path("content").asText());
            var list = call(http, firstUser, "/api/v1/memory/files?path=extracted", null);
            assertEquals(200, list.statusCode(), list.body());
            assertEquals(firstTask + ".md", json.readTree(list.body()).at("/files/0/name").asText());
            assertEquals(404, call(http, otherUser, notePath, null).statusCode());
            assertEquals(404, call(http, "test-tenant-root", "/api/v1/tasks/" + firstTask, null).statusCode());

            complete(http, firstUser, submit(http, firstUser, "second request"));
            complete(http, otherUser, submit(http, otherUser, "unrelated request"));
            var compatible = call(http, firstUser, "/v1/chat/completions", """
                    {"model":"shannon-chat","messages":[{"role":"user","content":"compatible request"}]}
                    """);
            assertEquals(200, compatible.statusCode(), compatible.body());
            assertTrue(REQUESTS.stream().anyMatch(message -> message.contains("compatible request") && message.contains(NOTE)));
            assertTrue(REQUESTS.stream().anyMatch(message -> message.contains("second request") && message.contains(NOTE)));
            assertTrue(REQUESTS.stream().anyMatch(message -> message.contains("unrelated request") && !message.contains(NOTE)));
        }
    }

    private String submit(HttpClient http, String bearer, String query) throws Exception {
        var response = call(http, bearer, "/api/v1/tasks", json.writeValueAsString(Map.of("query", query)));
        assertEquals(201, response.statusCode(), response.body());
        return json.readTree(response.body()).path("task_id").asText();
    }

    private void complete(HttpClient http, String bearer, String id) {
        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            while (true) {
                var body = json.readTree(call(http, bearer, "/api/v1/tasks/" + id, null).body());
                String status = body.path("status").asText();
                if ("TASK_STATUS_COMPLETED".equals(status)) return;
                assertNotEquals("TASK_STATUS_FAILED", status, body.toString());
                Thread.sleep(100);
            }
        });
    }

    private HttpResponse<String> call(HttpClient http, String bearer, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + bearer).timeout(Duration.ofSeconds(5));
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String subject) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("shannon-platform").subject(subject)
                .claim("tenant_id", "memory").expirationTime(Date.from(Instant.now().plusSeconds(120))).build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        token.sign(new MACSigner("private-memory-test-signing-secret"));
        return token.serialize();
    }
}
