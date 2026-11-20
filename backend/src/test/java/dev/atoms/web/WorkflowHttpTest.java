package dev.atoms.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** 验证工作台所用的 HTTP 流程，例如从模板目录选择版本后提交并批准任务。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "atoms.demo=true", "atoms.agent.learning-router-enabled=true",
        "atoms.metrics.enabled=true", "atoms.metrics.token=test-metrics-reader",
        "spring.datasource.url=jdbc:h2:mem:workflow_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always"
})
class WorkflowHttpTest {
    @LocalServerPort int port;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired dev.atoms.agent.persistence.Store store;

    @Test void gatewayRoutesTemplateAndRunsItAfterReview() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/tasks"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("""
                            {"query":"创建任务看板","context":{"template":"approval","template_version":"1.0.0","disable_ai":true}}
                            """)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());
            var submitted = json.readTree(response.body());
            String path = "/v1/tasks/" + submitted.path("task_id").asText();
            assertTrue(call(client, path, null).path("awaiting_approval").asBoolean());
            call(client, path + "/review", "{\"action\":\"approve\"}");
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (true) {
                    var task = call(client, path, null);
                    assertNotEquals("TASK_STATUS_FAILED", task.path("status").asText(), task.toString());
                    if ("TASK_STATUS_COMPLETED".equals(task.path("status").asText())) {
                        assertEquals("EXECUTION_MODE_COMPLEX", task.path("mode").asText());
                        assertFalse(task.path("result").asText().isBlank());
                          var details = call(client, "/runs/" + submitted.path("task_id").asText(), null);
                          assertTrue(details.path("nodes").toString().contains("template.team.swarm.review"));
                          assertTrue(details.path("nodes").toString().contains("template.team.swarm.synthesis"));
                        return;
                    }
                    Thread.sleep(30);
                }
            });
        }
    }

    @Test void shannonTaskCanBePausedAndResumedThroughGateway() throws Exception {
        var cookies = new CookieManager();
        try (var client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            call(client, "/config", null);
            String owner = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "ATOMS_VISITOR".equals(cookie.getName())).findFirst().orElseThrow().getValue();
            String session = (String) store.create(owner, "暂停测试").get("id");
            String id = (String) store.queue(session, owner, "hello", null, "chat", "test").get("id");
            for (String action : java.util.List.of("pause", "resume")) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/api/v1/tasks/" + id + "/" + action))
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(202, response.statusCode(), response.body());
                assertTrue(json.readTree(response.body()).path("success").asBoolean());
                if ("pause".equals(action))
                    assertEquals("TASK_STATUS_PAUSED", call(client, "/v1/tasks/" + id, null).path("status").asText());
            }
            String second = (String) store.create(owner, "取消测试").get("id");
            String cancelled = (String) store.queue(second, owner, "cancel", null, "chat", "test").get("id");
            String token = store.claim(cancelled, owner);
            store.reserveModelCall(cancelled, owner, token, 10);
            store.recordModelUsage(cancelled, owner, token, 12, 3);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/api/v1/tasks/" + cancelled + "/cancel"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(202, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals("TASK_STATUS_CANCELLED", call(client, "/v1/tasks/" + cancelled, null).path("status").asText());
            String listing = "/v1/tasks?session_id=" + second + "&status=canceled&limit=1";
            var page = call(client, listing, null);
            assertEquals(1, page.path("total_count").asInt());
            assertEquals(cancelled, page.path("tasks").get(0).path("task_id").asText());
            assertEquals(15, page.path("tasks").get(0).path("total_token_usage").path("total_tokens").asLong());
            assertEquals(0, call(client, listing + "&offset=1", null).path("tasks").size());
            assertDoesNotThrow(() -> store.queue(second, owner, "new task", null, "chat", "test"));
        }
    }

    @Test void shannonTaskReturnsSessionAndResult() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/tasks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"hello\",\"mode\":\"simple\",\"context\":{\"locale\":\"zh\"}}")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());
            var submitted = json.readTree(response.body());
            String path = "/v1/tasks/" + submitted.path("task_id").asText();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (true) {
                    var task = call(client, path, null);
                    assertNotEquals("TASK_STATUS_FAILED", task.path("status").asText(), task.toString());
                    if ("TASK_STATUS_COMPLETED".equals(task.path("status").asText())) {
                        assertEquals(submitted.path("session_id"), task.path("session_id"));
                        assertTrue(task.path("result").asText().contains("hello"));
                        var listed = call(client, "/v1/tasks?status=completed&session_id="
                                + task.path("session_id").asText(), null).path("tasks").get(0);
                        assertEquals(task.path("updated_at"), listed.path("completed_at"));
                        var events = call(client, path + "/events", null).path("events");
                        assertEquals("WORKFLOW_STARTED", events.get(0).path("type").asText());
                        assertEquals("WORKFLOW_COMPLETED", events.get(1).path("type").asText());
                        var page = call(client, path + "/events?limit=1&offset=1", null);
                        assertEquals(1, page.path("count").asInt());
                        assertEquals(events.get(1), page.path("events").get(0));
                        String sessionId = task.path("session_id").asText();
                        assertEquals(1, call(client, "/v1/sessions", null).path("total_count").asInt());
                        assertEquals(sessionId, call(client, "/v1/sessions/" + sessionId, null).path("session_id").asText());
                        var history = call(client, "/v1/sessions/" + sessionId + "/history", null);
                        assertEquals(1, history.path("total").asInt());
                        assertEquals("zh", history.path("tasks").get(0).path("metadata").path("context").path("locale").asText());
                        assertTrue(history.path("tasks").get(0).path("result").asText().contains("hello"));
                        var stream = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path + "/stream"))
                                .header("Last-Event-ID", events.get(0).path("seq").asText())
                                .timeout(Duration.ofSeconds(3)).build();
                        var streamed = client.send(stream, HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, streamed.statusCode());
                        assertTrue(streamed.body().contains("WORKFLOW_COMPLETED"));
                        assertFalse(streamed.body().contains("WORKFLOW_STARTED"));
                        try (var outsider = HttpClient.newHttpClient()) {
                            var foreign = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                    + "/api/v1/sessions/" + sessionId)).build();
                            assertEquals(404, outsider.send(foreign, HttpResponse.BodyHandlers.discarding()).statusCode());
                        }
                        var sessionUrl = URI.create("http://127.0.0.1:" + port + "/api/v1/sessions/" + sessionId);
                        var rename = HttpRequest.newBuilder(sessionUrl).header("Content-Type", "application/json")
                                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"title\":\"renamed\"}")).build();
                        var renamed = client.send(rename, HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, renamed.statusCode(), renamed.body());
                        assertEquals("renamed", json.readTree(renamed.body()).path("title").asText());
                        assertEquals(204, client.send(HttpRequest.newBuilder(sessionUrl).DELETE().build(),
                                HttpResponse.BodyHandlers.discarding()).statusCode());
                        assertEquals(404, client.send(HttpRequest.newBuilder(sessionUrl).GET().build(),
                                HttpResponse.BodyHandlers.discarding()).statusCode());
                        return;
                    }
                    Thread.sleep(30);
                }
            });
        }
    }

    @Test void metricsRequiresAuthorizationAndExposesOnlyAggregates() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var endpoint = URI.create("http://127.0.0.1:" + port + "/metrics");
            var denied = client.send(HttpRequest.newBuilder(endpoint).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, denied.statusCode());
            var response = client.send(HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer test-metrics-reader").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("# TYPE atoms_runs_submitted_total counter"));
            assertTrue(response.body().contains("# TYPE atoms_runs gauge"));
            assertTrue(response.body().contains("atoms_model_input_tokens_total "));
            assertFalse(response.body().contains("test-metrics-reader"));
        }
    }

    @DynamicPropertySource
    static void templates(DynamicPropertyRegistry properties) throws IOException {
        var dir = Files.createTempDirectory("atoms-workflow-http-");
        dir.toFile().deleteOnExit();
        var file = Files.writeString(dir.resolve("approval.yml"), """
                name: approval
                version: 1.0.0
                defaults:
                  require_approval: true
                nodes:
                  - id: analyze
                    type: simple
                  - id: team
                    type: swarm
                    depends_on: [analyze]
                """);
        file.toFile().deleteOnExit();
        properties.add("atoms.agent.workflow-templates-directory", dir::toString);
    }

    @Test void learnedPatternUsesOnlyProjectHistoryForTheSameBuildContext() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var project = call(client, "/projects", "{\"title\":\"学习路由测试\"}");
            String projectPath = "/projects/" + project.path("id").asText();
            call(client, projectPath + "/versions",
                    "{\"files\":{\"index.html\":\"<body>基准版本</body>\",\"style.css\":\"body{color:black}\"}}");
            String base = call(client, projectPath, null).path("currentVersion").asText();
            String prompt = "把待办事项看板改成暗色模式";
            var manual = call(client, projectPath + "/runs", json.writeValueAsString(java.util.Map.of(
                    "prompt", prompt, "baseVersion", base, "mode", "build", "pattern", "reflection")));
            String manualPath = "/runs/" + manual.path("id").asText();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (true) {
                    var run = call(client, manualPath, null);
                    assertNotEquals("FAILED", run.path("status").asText(), run.path("error").asText());
                    if ("SUCCEEDED".equals(run.path("status").asText())) return;
                    Thread.sleep(30);
                }
            });
            String latest = call(client, projectPath, null).path("currentVersion").asText();
            var learned = call(client, projectPath + "/runs", json.writeValueAsString(java.util.Map.of(
                    "prompt", prompt, "baseVersion", latest, "mode", "build", "pattern", "auto")));
            assertEquals("reflection", learned.path("pattern").asText());
            var other = call(client, "/projects", "{\"title\":\"独立项目\"}");
            var otherRun = call(client, "/projects/" + other.path("id").asText() + "/runs",
                    json.writeValueAsString(java.util.Map.of("prompt", prompt, "mode", "build", "pattern", "auto")));
            assertEquals("react", otherRun.path("pattern").asText());
        }
    }

    @Test void catalogSelectionCanBeApprovedAndExecutedOverHttp() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var config = call(client, "/config", null);
            String template = config.path("templates").get(0).path("id").asText();
            assertEquals("approval@1.0.0", template);
            var project = call(client, "/projects", "{\"title\":\"模板测试\"}");
            var run = call(client, "/projects/" + project.path("id").asText() + "/runs",
                    json.writeValueAsString(java.util.Map.of("prompt", "创建任务看板", "mode", "build",
                            "template", template, "requireTemplate", true)));
            assertEquals("AWAITING_APPROVAL", run.path("status").asText());
            String path = "/runs/" + run.path("id").asText();
            call(client, path + "/approval?approved=true", "");
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (true) {
                    var current = call(client, path, null);
                    String status = current.path("status").asText();
                    assertNotEquals("FAILED", status, current.path("error").asText());
                    if ("SUCCEEDED".equals(status)) {
                        assertEquals("1.0.0", current.path("templateVersion").asText());
                        assertEquals("template.analyze", current.path("nodes").get(0).path("id").asText());
                        return;
                    }
                    Thread.sleep(30);
                }
            });
        }
    }

    private com.fasterxml.jackson.databind.JsonNode call(HttpClient client, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path));
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
}
