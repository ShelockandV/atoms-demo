package dev.atoms.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.atoms.agent.ScheduledTasks;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "atoms.demo=true", "atoms.approval.environment=test",
        "spring.datasource.url=jdbc:h2:mem:opa_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always"
})
class TaskApprovalHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired ScheduledTasks schedules;
    private static HttpServer opa;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        opa = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        opa.createContext("/v1/data/atoms/task/decision", exchange -> {
            try (exchange) {
                var policy = new ObjectMapper().readTree(exchange.getRequestBody()).path("input");
                String query = policy.path("query").asText();
                int status = "unavailable".equals(query) ? 503 : 200;
                String body = switch (query) {
                    case "deny" -> "{\"result\":{\"allow\":false}}";
                    case "hold" -> "{\"result\":{\"allow\":true,\"require_approval\":true,\"reason\":\"review required\"}}";
                    case "undefined" -> "{}";
                    default -> "{\"result\":{\"allow\":true}}";
                };
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        opa.createContext("/v1/data/atoms/tool/decision", exchange -> {
            try (exchange) {
                String tool = new ObjectMapper().readTree(exchange.getRequestBody()).at("/input/tool").asText();
                String response = "GENERATE".equals(tool)
                        ? "{\"result\":{\"allow\":true,\"require_approval\":true,\"reason\":\"review source generation\"}}"
                        : "{\"result\":{\"allow\":true}}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        opa.start();
        registry.add("atoms.approval.tool-opa-url", () ->
                "http://127.0.0.1:" + opa.getAddress().getPort() + "/v1/data/atoms/tool/decision");
        registry.add("atoms.approval.opa-url", () ->
                "http://127.0.0.1:" + opa.getAddress().getPort() + "/v1/data/atoms/task/decision");
    }

    @AfterAll static void stop() { if (opa != null) opa.stop(0); }

    @Test void policyGatesTasksAndFailsClosed() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            String endpoint = "/api/v1/tasks";
            for (String query : java.util.List.of("deny", "unavailable", "undefined")) {
                var rejected = send(client, endpoint, "{\"query\":\"" + query + "\"}");
                assertEquals("deny".equals(query) ? 403 : 503, rejected.statusCode());
            }
            var allowed = send(client, endpoint, "{\"query\":\"allow\"}");
            assertEquals(201, allowed.statusCode(), allowed.body());
            var held = send(client, endpoint, "{\"query\":\"hold\"}");
            assertEquals(201, held.statusCode(), held.body());
            var task = json.readTree(held.body());
            assertTrue(task.path("awaiting_approval").asBoolean());
            assertEquals("review required", task.path("approval_reason").asText());
            String id = task.path("task_id").asText();
            assertTrue(json.readTree(send(client, endpoint + "/" + id, null).body()).path("awaiting_approval").asBoolean());
            var review = send(client, endpoint + "/" + id + "/review", null);
            assertEquals(200, review.statusCode(), review.body());
            assertEquals("\"1\"", review.headers().firstValue("ETag").orElseThrow());
            assertEquals("review required", json.readTree(review.body()).path("approval_reason").asText());
            var approved = send(client, "/api/v1/approvals/decision", """
                    {"workflow_id":"%s","approval_id":"%s","approved":true}
                    """.formatted(id, id));
            assertEquals(200, approved.statusCode(), approved.body());
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (!"TASK_STATUS_COMPLETED".equals(
                        json.readTree(send(client, endpoint + "/" + id, null).body()).path("status").asText()))
                    Thread.sleep(50);
            });

            var chat = send(client, "/v1/chat/completions", """
                    {"messages":[{"role":"user","content":"hold"}]}
                    """);
            assertEquals(409, chat.statusCode(), chat.body());
            var response = json.readTree(chat.body());
            assertFalse(response.path("task_id").asText().isBlank());
            assertEquals("approval_required", response.at("/error/code").asText());
        }
    }

    @Test void scheduledOccurrencesHonorPolicyAndAdvance() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var failures = Map.of("deny", "审批策略拒绝该任务", "unavailable", "审批策略服务不可用");
            for (String query : java.util.List.of("hold", "allow", "deny", "unavailable")) {
                var created = send(client, "/api/v1/schedules", """
                        {"name":"%s","cron_expression":"0 * * * *","timezone":"UTC","task_query":"%s"}
                        """.formatted(query, query));
                assertEquals(201, created.statusCode(), created.body());
                String schedule = json.readTree(created.body()).path("schedule_id").asText();
                assertFalse(schedule.isBlank(), created.body());
                Instant now = Instant.now();
                db.update("UPDATE scheduled_tasks SET next_run_epoch=? WHERE id=?", now.getEpochSecond(), schedule);
                ReflectionTestUtils.invokeMethod(schedules, "dispatchDue", now);
                String history = "/api/v1/schedules/" + schedule + "/runs";
                var run = json.readTree(send(client, history, null).body()).at("/runs/0");
                assertTrue(db.queryForObject("SELECT next_run_epoch FROM scheduled_tasks WHERE id=?", Long.class, schedule)
                        > now.getEpochSecond());
                String id = run.path("workflow_id").asText();
                assertFalse(id.isBlank());
                if (failures.containsKey(query)) {
                    assertEquals("FAILED", run.path("status").asText());
                    assertEquals(failures.get(query), run.path("error_message").asText());
                    assertEquals(0, run.path("total_tokens").asInt());
                    continue;
                }
                if ("hold".equals(query)) {
                    assertEquals("AWAITING_APPROVAL", run.path("status").asText());
                    assertEquals("review required", run.path("approval_reason").asText());
                    var approved = send(client, "/api/v1/approvals/decision", """
                            {"workflow_id":"%s","approval_id":"%s","approved":true}
                            """.formatted(id, id));
                    assertEquals(200, approved.statusCode(), approved.body());
                }
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (!"COMPLETED".equals(json.readTree(send(client, history, null).body()).at("/runs/0/status").asText()))
                        Thread.sleep(50);
                });
            }
        }
    }

    @Test void toolApprovalWaitsForTheExactActionAndResumesIt() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var created = send(client, "/api/v1/tasks", """
                    {"mode":"standard","query":"创建待办事项"}
                    """);
            assertEquals(201, created.statusCode(), created.body());
            String id = json.readTree(created.body()).path("task_id").asText();
            var pending = assertTimeoutPreemptively(Duration.ofSeconds(6), () -> {
                while (true) {
                    var task = json.readTree(send(client, "/api/v1/tasks/" + id, null).body());
                    if (task.path("awaiting_approval").asBoolean()) return task;
                    Thread.sleep(50);
                }
            });
            assertEquals("GENERATE", pending.path("approval_tool").asText());
            String approval = pending.path("approval_id").asText();
            assertNotEquals(id, approval);
            assertEquals("review source generation", pending.path("approval_reason").asText());
            assertEquals(409, send(client, "/api/v1/approvals/decision", """
                    {"workflow_id":"%s","approval_id":"%s","approved":true}
                    """.formatted(id, id)).statusCode());
            var approved = send(client, "/api/v1/approvals/decision", """
                    {"workflow_id":"%s","approval_id":"%s","approved":true}
                    """.formatted(id, approval));
            assertEquals(200, approved.statusCode(), approved.body());
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                while (!"TASK_STATUS_COMPLETED".equals(json.readTree(
                        send(client, "/api/v1/tasks/" + id, null).body()).path("status").asText())) Thread.sleep(50);
            });
        }
    }

    private HttpResponse<String> send(HttpClient client, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(8));
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
