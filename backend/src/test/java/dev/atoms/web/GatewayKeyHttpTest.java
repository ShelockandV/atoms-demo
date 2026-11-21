package dev.atoms.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "atoms.demo=true",
        "atoms.gateway.tenant-tokens.memory=memory-root", "atoms.memory.user-files.recall-enabled=true",
        "atoms.gateway.agents.files.name=Files", "atoms.gateway.agents.files.description=List workspace files",
        "atoms.gateway.agents.files.category=workspace", "atoms.gateway.agents.files.tool=LIST_FILES", "atoms.gateway.tenant-tokens.epsilon=epsilon-root", "atoms.gateway.tenant-tokens.alpha=alpha-root", "atoms.gateway.tenant-tokens.beta=beta-root",
        "atoms.gateway.tenant-tokens.gamma=gamma-root", "atoms.gateway.tenant-tokens.delta=delta-root", "atoms.gateway.rate-limit.requests-per-hour=24",
        "atoms.gateway.jwt.signing-key=jwt-signature-for-integration-tests-only", "atoms.gateway.jwt.audience=atoms-tests",
        "spring.datasource.url=jdbc:h2:mem:gateway_keys;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always"
})
class GatewayKeyHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired dev.atoms.agent.ScheduledTasks schedules;
    @Autowired dev.atoms.agent.persistence.Store store;
    @org.junit.jupiter.api.io.TempDir static Path memoryRoot;

    @org.springframework.test.context.DynamicPropertySource
    static void memoryDirectory(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("atoms.memory.user-files.directory", () -> memoryRoot.toString());
    }

    @Test void gatewayMemorySnapshotsAreSharedAcrossSessionsAndPinnedAtSubmission() throws Exception {
        var uuid = java.util.UUID.nameUUIDFromBytes("memory".getBytes(StandardCharsets.UTF_8)).toString();
        String owner = "g-" + uuid.substring(2);
        Path directory = Files.createDirectories(memoryRoot.resolve(owner).resolve("memory"));
        var note = directory.resolve("MEMORY.md");
        Files.writeString(note, "旧版约定");
        try (var client = HttpClient.newHttpClient()) {
            var first = submitMemoryTask(client, "memory-root");
            Files.writeString(note, "更新后的约定");
            var second = submitMemoryTask(client, "memory-root");
            assertNotEquals(first.path("session_id").asText(), second.path("session_id").asText());
            var firstContext = store.context(first.path("session_id").asText(), owner, null, first.path("task_id").asText());
            var secondContext = store.context(second.path("session_id").asText(), owner, null, second.path("task_id").asText());
            assertTrue(firstContext.recalledHistory().contains("旧版约定"));
            assertTrue(secondContext.recalledHistory().contains("更新后的约定"));
            assertFalse(firstContext.recalledHistory().contains("更新后的约定"));

            String jwt = token("memory", "shannon-platform", "atoms-tests", java.time.Instant.now().plusSeconds(60));
            var personal = submitMemoryTask(client, jwt);
            assertTrue(store.context(personal.path("session_id").asText(), owner, null, personal.path("task_id").asText())
                    .recalledHistory().isEmpty());
        }
    }

    private com.fasterxml.jackson.databind.JsonNode submitMemoryTask(HttpClient client, String credential) throws Exception {
        var response = send(client, "POST", "/api/v1/tasks", credential, "{\"query\":\"summarize test data\"}");
        assertEquals(201, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    @Test void memoryFilesAreIsolatedAndEncodeBinaryContent() throws Exception {
        var id = java.util.UUID.nameUUIDFromBytes("alpha".getBytes(StandardCharsets.UTF_8)).toString();
        String owner = "g-" + id.substring(2);
        Path memory = Files.createDirectories(memoryRoot.resolve(owner).resolve("memory"));
        Files.writeString(memory.resolve("notes.md"), "共享记忆");
        Path nested = Files.createDirectory(memory.resolve("archive"));
        Files.write(nested.resolve("sample.bin"), new byte[]{0, 1, 2});
        Files.createSymbolicLink(memory.resolve("escape"), memoryRoot);
        try (var client = HttpClient.newHttpClient()) {
            String endpoint = "/api/v1/memory/files";
            assertEquals(401, send(client, "GET", endpoint, null, null).statusCode());
            var listing = send(client, "GET", endpoint, "alpha-root", null);
            assertEquals(200, listing.statusCode(), listing.body());
            var entries = json.readTree(listing.body()).path("files");
            assertEquals(2, entries.size());
            assertTrue(entries.get(0).path("is_dir").asBoolean());
            var text = send(client, "GET", endpoint + "/notes.md", "alpha-root", null);
            assertEquals("共享记忆", json.readTree(text.body()).path("content").asText());
            var binary = send(client, "GET", endpoint + "/archive/sample.bin", "alpha-root", null);
            assertEquals("AAEC", json.readTree(binary.body()).path("content").asText());
            assertEquals(404, send(client, "GET", endpoint + "/escape/notes.md", "alpha-root", null).statusCode());
            assertEquals(404, send(client, "GET", endpoint + "/notes.md", "beta-root", null).statusCode());

            String jwt = token("alpha", "shannon-platform", "atoms-tests", java.time.Instant.now().plusSeconds(60));
            assertEquals(404, send(client, "GET", endpoint + "/notes.md", jwt, null).statusCode());
            Path personal = Files.createDirectories(memoryRoot.resolve("u-" + GatewayKeys.hash(owner + ":external-user")).resolve("memory"));
            Files.writeString(personal.resolve("notes.md"), "personal");
            var isolated = send(client, "GET", endpoint + "/notes.md", jwt, null);
            assertEquals("personal", json.readTree(isolated.body()).path("content").asText());
        }
    }


    @Test void tenantCanCreateRotateAndRevokeManagedKey() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            String keys = "/api/v1/auth/api-keys";
            assertEquals(401, send(client, "GET", keys, null, null).statusCode());
            var create = send(client, "POST", keys, "alpha-root", "{\"name\":\"workspace\"}");
            assertEquals(201, create.statusCode(), create.body());
            var issued = json.readTree(create.body());
            String token = issued.path("api_key").asText();
            String id = issued.path("id").asText();
            assertTrue(token.startsWith("atoms_"));
            assertNotEquals(token, db.queryForObject("SELECT key_hash FROM gateway_api_keys WHERE id=?", String.class, id));
            assertEquals(200, send(client, "GET", "/v1/models", token, null).statusCode());
            var me = send(client, "GET", "/api/v1/auth/me", token, null);
            assertEquals("alpha", json.readTree(me.body()).path("tenant_id").asText());
            assertEquals("api_key", json.readTree(me.body()).path("credential_type").asText());
            var list = send(client, "GET", keys, "alpha-root", null);
            assertFalse(list.body().contains(token));
            assertTrue(json.readTree(list.body()).at("/keys/0/last_used_at").isTextual());
            assertEquals(403, send(client, "GET", keys, token, null).statusCode());
            assertEquals(404, send(client, "DELETE", keys + "/" + id, "beta-root", null).statusCode());

            var rotate = send(client, "POST", "/api/v1/auth/refresh-key", token, null);
            assertEquals(200, rotate.statusCode(), rotate.body());
            var replacement = json.readTree(rotate.body());
            String next = replacement.path("api_key").asText();
            assertNotEquals(token, next);
            assertEquals(401, send(client, "GET", "/v1/models", token, null).statusCode());
            assertEquals(200, send(client, "GET", "/v1/models", next, null).statusCode());
            assertEquals(200, send(client, "DELETE", keys + "/" + replacement.path("id").asText(), "alpha-root", null).statusCode());
            assertEquals(401, send(client, "GET", "/v1/models", next, null).statusCode());
        }
    }

    @Test void gatewayDirectToolReturnsOutputAndRejectsMutatingTools() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            String endpoint = "/api/v1/tools/";
            assertEquals(401, send(client, "POST", endpoint + "LIST_FILES/execute", null, "{}").statusCode());
            assertEquals(404, send(client, "POST", endpoint + "UNKNOWN_TOOL/execute", "beta-root", "{}").statusCode());
            assertEquals(403, send(client, "POST", endpoint + "FINISH/execute", "beta-root", "{}").statusCode());
            assertEquals(400, send(client, "POST", endpoint + "READ_FILE/execute", "beta-root", "{}").statusCode());
            var response = send(client, "POST", endpoint + "LIST_FILES/execute", "beta-root", "{}");
            assertEquals(200, response.statusCode(), response.body());
            var body = json.readTree(response.body());
            assertTrue(body.path("success").asBoolean());
            assertTrue(body.path("output").asText().endsWith("[]"));
            assertEquals(body.path("output"), body.path("text"));
            String run = response.headers().firstValue("X-Workflow-ID").orElseThrow();
            var status = send(client, "GET", "/api/v1/tasks/" + run, "beta-root", null);
            assertEquals("TASK_STATUS_COMPLETED", json.readTree(status.body()).path("status").asText());
        }
    }

    @Test void gatewayAgentUsesToolSchemaAndQueuesExecution() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            String token = "alpha-root";
            String endpoint = "/api/v1/agents";
            assertEquals(401, send(client, "GET", endpoint, null, null).statusCode());
            var list = send(client, "GET", endpoint, token, null);
            assertEquals(200, list.statusCode(), list.body());
            assertEquals("files", json.readTree(list.body()).at("/agents/0/id").asText());
            var agent = send(client, "GET", endpoint + "/files", token, null);
            assertEquals("LIST_FILES", json.readTree(agent.body()).path("tool").asText());
            assertEquals(400, send(client, "POST", endpoint + "/files", token, """
                    {"input":{"unknown":true}}
                    """).statusCode());
            var submitted = send(client, "POST", endpoint + "/files", token, "{\"input\":{}}");
            assertEquals(202, submitted.statusCode(), submitted.body());
            var accepted = json.readTree(submitted.body());
            String id = accepted.path("task_id").asText();
            assertEquals("files", accepted.path("agent_id").asText());
            assertEquals(id, submitted.headers().firstValue("X-Workflow-ID").orElseThrow());
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (!"SUCCEEDED".equals(db.queryForObject(
                        "SELECT status FROM generation_runs WHERE id=?", String.class, id))) Thread.sleep(50);
            });
            var result = send(client, "GET", "/api/v1/tasks/" + id, token, null);
            assertEquals("TASK_STATUS_COMPLETED", json.readTree(result.body()).path("status").asText());
            assertTrue(json.readTree(result.body()).has("result"));
        }
    }

    @Test void openApiAdvertisesRegisteredGatewayRoutes() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = send(client, "GET", "/openapi.json", null, null);
            assertEquals(200, response.statusCode(), response.body());
            var paths = json.readTree(response.body()).path("paths");
            assertTrue(paths.path("/api/v1/tasks").path("post").path("requestBody").path("required").asBoolean());
            assertTrue(paths.path("/api/v1/tasks").path("post").path("responses").has("201"));
            assertEquals("path", paths.path("/api/v1/tasks/{id}/review").at("/get/parameters/0/in").asText());
            assertEquals("tier", paths.path("/v1/llm-models").at("/get/parameters/0/name").asText());
            assertTrue(paths.path("/api/v1/sessions/{id}/files/{path}").has("get"));
            assertTrue(paths.path("/readiness").has("get"));
            var health = send(client, "GET", "/health", null, null);
            assertEquals(200, health.statusCode(), health.body());
            assertEquals("healthy", json.readTree(health.body()).path("status").asText());
            var readiness = send(client, "GET", "/readiness", null, null);
            assertEquals(200, readiness.statusCode(), readiness.body());
            assertEquals("ok", json.readTree(readiness.body()).at("/checks/database").asText());
            assertFalse(paths.has("/error"));
        }
    }

    @Test void gatewayListsConfiguredProviderModelsAndFiltersTier() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var denied = send(client, "GET", "/v1/llm-models", null, null);
            assertEquals(401, denied.statusCode());
            var response = send(client, "GET", "/v1/llm-models?tier=medium", "alpha-root", null);
            assertEquals(200, response.statusCode(), response.body());
            var models = json.readTree(response.body()).path("models");
            assertFalse(models.isEmpty());
            assertEquals("medium", models.get(0).path("tier").asText());
            assertFalse(models.get(0).path("provider").asText().isBlank());
        }
    }

    @Test void gatewaySharesQuotaAcrossCredentialsAndApiFamilies() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var create = send(client, "POST", "/api/v1/auth/api-keys", "gamma-root", "{\"name\":\"rate test\"}");
            assertEquals(201, create.statusCode(), create.body());
            String token = json.readTree(create.body()).path("api_key").asText();
            String jwt = token("gamma", "shannon-platform", "atoms-tests", java.time.Instant.now().plusSeconds(60));
            for (int index = 0; index < 23; index++) {
                var path = index % 2 == 0 ? "/v1/models" : "/api/v1/auth/me";
                assertEquals(200, send(client, "GET", path, index % 2 == 0 ? token : jwt, null).statusCode());
            }
            var limited = send(client, "GET", "/v1/models", "gamma-root", null);
            assertEquals(429, limited.statusCode(), limited.body());
            assertTrue(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow()) > 0);
            assertEquals("rate_limit_exceeded", json.readTree(limited.body()).at("/error/code").asText());
            assertEquals(200, send(client, "GET", "/v1/models", "beta-root", null).statusCode());
        }
    }

    @Test void jwtChecksClaimsAndRetainsConfiguredTokenPrivileges() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var expiry = java.time.Instant.now().plusSeconds(60);
            String valid = token("beta", "shannon-platform", "atoms-tests", expiry);
            var me = send(client, "GET", "/api/v1/auth/me", valid, null);
            assertEquals(200, me.statusCode(), me.body());
            var identity = json.readTree(me.body());
            assertEquals("beta", identity.path("tenant_id").asText());
            assertEquals("external-user", identity.path("user_id").asText());
            assertEquals("jwt", identity.path("credential_type").asText());
            assertEquals(403, send(client, "GET", "/api/v1/auth/api-keys", valid, null).statusCode());
            assertEquals(403, send(client, "POST", "/api/v1/auth/refresh-key", valid, null).statusCode());
            for (String invalid : java.util.List.of(token("unknown", "shannon-platform", "atoms-tests", expiry),
                    token("beta", "wrong", "atoms-tests", expiry),
                    token("beta", "shannon-platform", "wrong", expiry),
                    token("beta", "shannon-platform", null, expiry),
                    token("beta", "shannon-platform", "atoms-tests", java.time.Instant.now().minusSeconds(30)),
                    valid.substring(0, valid.lastIndexOf('.') + 1) + "invalid")) {
                var response = send(client, "GET", "/v1/models", invalid, null);
                assertEquals(401, response.statusCode(), response.body());
            }
        }
    }

    private static String token(String tenant, String issuer, String audience, java.time.Instant expiry) throws Exception {
        var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().subject("external-user").issuer(issuer)
                .claim("tenant_id", tenant).expirationTime(java.util.Date.from(expiry));
        if (audience != null) claims.audience(audience);
        var token = new com.nimbusds.jwt.SignedJWT(
                new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), claims.build());
        token.sign(new com.nimbusds.jose.crypto.MACSigner("jwt-signature-for-integration-tests-only"));
        return token.serialize();
    }

    @Test void websocketResumesAuthorizedTaskAndFiltersPersistedEvents() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var created = send(client, "POST", "/api/v1/tasks", "alpha-root", "{\"query\":\"ws hello\"}");
            assertEquals(201, created.statusCode(), created.body());
            String id = json.readTree(created.body()).path("task_id").asText();
            var history = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (true) {
                    var response = send(client, "GET", "/api/v1/tasks/" + id + "/events", "alpha-root", null);
                    var events = json.readTree(response.body()).path("events");
                    if (events.size() >= 2) return events;
                    Thread.sleep(25);
                }
            });
            long cursor = history.get(0).path("seq").asLong();
            var uri = URI.create("ws://127.0.0.1:" + port + "/api/v1/stream/ws?workflow_id=" + id
                    + "&last_event_id=" + cursor + "&types=WORKFLOW_COMPLETED");

            var unauthorized = assertThrows(java.util.concurrent.ExecutionException.class, () -> client.newWebSocketBuilder()
                    .buildAsync(uri, new java.net.http.WebSocket.Listener() {}).get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(401, ((java.net.http.WebSocketHandshakeException) unauthorized.getCause()).getResponse().statusCode());
            var outsider = assertThrows(java.util.concurrent.ExecutionException.class, () -> client.newWebSocketBuilder()
                    .header("Authorization", "Bearer beta-root").buildAsync(uri, new java.net.http.WebSocket.Listener() {})
                    .get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(404, ((java.net.http.WebSocketHandshakeException) outsider.getCause()).getResponse().statusCode());

            var message = new java.util.concurrent.CompletableFuture<String>();
            var closed = new java.util.concurrent.CompletableFuture<Integer>();
            var socket = client.newWebSocketBuilder().header("Authorization", "Bearer alpha-root")
                    .buildAsync(uri, new java.net.http.WebSocket.Listener() {
                        private final StringBuilder text = new StringBuilder();
                        @Override public java.util.concurrent.CompletionStage<?> onText(
                                java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                            text.append(data);
                            if (last) message.complete(text.toString());
                            webSocket.request(1);
                            return null;
                        }
                        @Override public java.util.concurrent.CompletionStage<?> onClose(
                                java.net.http.WebSocket webSocket, int statusCode, String reason) {
                            closed.complete(statusCode);
                            return null;
                        }
                    }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            try {
                var event = json.readTree(message.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("WORKFLOW_COMPLETED", event.path("type").asText());
                assertEquals(id, event.path("workflow_id").asText());
                assertTrue(event.path("seq").asLong() > cursor);
                assertEquals(java.net.http.WebSocket.NORMAL_CLOSURE, closed.get(5, java.util.concurrent.TimeUnit.SECONDS));
            } finally {
                socket.abort();
            }
        }
    }

    @Test void daemonApprovalSurvivesReconnectAndRequiresTenantAdministrator() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var issued = send(client, "POST", "/api/v1/auth/api-keys", "delta-root", "{\"name\":\"daemon\"}");
            assertEquals(201, issued.statusCode(), issued.body());
            String key = json.readTree(issued.body()).path("api_key").asText();
            String path = "/api/v1/daemon/approvals";
            String id = "request-release";
            String wire = """
                    {"type":"approval_request","payload":{"request_id":"request-release",
                     "agent":"reviewer","tool":"publish_report","args":"summary","thread_id":"release"}}
                    """;
            var first = new DaemonListener();
            var socket = openDaemon(client, key, first);
            try {
                assertEquals("connected", first.next(json).path("type").asText());
                socket.sendText(wire, true).get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
                    while (true) {
                        var pending = send(client, "GET", path, "delta-root", null);
                        var items = json.readTree(pending.body()).path("approvals");
                        if (!items.isEmpty()) {
                            assertEquals(id, items.get(0).path("request_id").asText());
                            return;
                        }
                        Thread.sleep(100);
                    }
                });
                assertEquals(403, send(client, "POST", path + "/" + id + "/decision", key, "{\"decision\":\"allow\"}").statusCode());
                assertEquals(404, send(client, "POST", path + "/" + id + "/decision", "beta-root", "{\"decision\":\"allow\"}").statusCode());
            } finally {
                socket.abort();
            }
            assertEquals(200, send(client, "POST", path + "/" + id + "/decision", "delta-root", "{\"decision\":\"deny\"}").statusCode());
            var resumed = new DaemonListener();
            var replacement = openDaemon(client, key, resumed);
            try {
                assertEquals("connected", resumed.next(json).path("type").asText());
                replacement.sendText(wire, true).get(5, java.util.concurrent.TimeUnit.SECONDS);
                var decision = resumed.next(json);
                assertEquals("approval_response", decision.path("type").asText());
                assertEquals(id, decision.at("/payload/request_id").asText());
                assertEquals("deny", decision.at("/payload/decision").asText());
                assertEquals(404, send(client, "POST", path + "/" + id + "/decision", "delta-root", "{\"decision\":\"allow\"}").statusCode());
            } finally {
                replacement.abort();
            }
        }
    }

    @Test void daemonStatusCountsOnlyConnectionsForAuthenticatedIdentity() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            String path = "/api/v1/daemon/status";
            var listener = new DaemonListener();
            var socket = openDaemon(client, "delta-root", listener);
            try {
                assertEquals("connected", listener.next(json).path("type").asText());
                for (String token : java.util.List.of("delta-root", "beta-root")) {
                    var status = send(client, "GET", path, token, null);
                    assertEquals(200, status.statusCode(), status.body());
                    assertEquals("delta-root".equals(token) ? 1 : 0,
                            json.readTree(status.body()).path("connected_daemons").asInt(-1));
                }
                socket.sendText("{\"type\":\"disconnect\"}", true).get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (json.readTree(send(client, "GET", path, "delta-root", null).body())
                            .path("connected_daemons").asInt(-1) != 0) Thread.sleep(50);
                });
            } finally {
                socket.abort();
            }
        }
    }

    @Test void scheduleDispatchesToDaemonAndRecordsOnlyClaimantsReply() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            String token = "epsilon-root";
            var created = send(client, "POST", "/api/v1/schedules", token, """
                    {"name":"daemon schedule","cron_expression":"0 * * * *","timezone":"UTC",
                     "task_query":"summarize test data","execution_target":"daemon","agent_name":"reporter"}
                    """);
            assertEquals(201, created.statusCode(), created.body());
            String schedule = json.readTree(created.body()).path("schedule_id").asText();
            var first = new DaemonListener();
            var second = new DaemonListener();
            var primary = openDaemon(client, token, first);
            var contender = openDaemon(client, token, second);
            try {
                assertEquals("connected", first.next(json).path("type").asText());
                assertEquals("connected", second.next(json).path("type").asText());
                var now = java.time.Instant.now();
                db.update("UPDATE scheduled_tasks SET next_run_epoch=? WHERE id=?", now.getEpochSecond(), schedule);
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(schedules, "dispatchDue", now);
                var task = first.next(json);
                assertEquals("message", task.path("type").asText());
                assertEquals("reporter", task.at("/payload/agent_name").asText());
                assertEquals("summarize test data", task.at("/payload/text").asText());
                String id = task.path("message_id").asText();
                assertEquals(id, second.next(json).path("message_id").asText());

                String claim = """
                        {"type":"claim","message_id":"%s"}
                        """.formatted(id);
                primary.sendText(claim, true).join();
                var ack = first.next(json);
                assertEquals("claim_ack", ack.path("type").asText());
                assertTrue(ack.at("/payload/granted").asBoolean());
                contender.sendText(claim, true).join();
                assertFalse(second.next(json).at("/payload/granted").asBoolean());

                String progress = """
                        {"type":"progress","message_id":"%s"}
                        """.formatted(id);
                String event = """
                        {"type":"event","message_id":"%s","payload":{"event_type":"LLM_OUTPUT",
                         "seq":0,"message":"draft ready","data":{"phase":"summary"}}}
                        """.formatted(id);
                contender.sendText(event, true).join();
                assertEquals("error", second.next(json).path("type").asText());
                long initialLease = java.time.Instant.now().plusSeconds(10).getEpochSecond();
                db.update("UPDATE daemon_claim_leases SET expires_epoch=? WHERE run_id=?", initialLease, id);
                primary.sendText(progress, true).join();
                primary.sendText(event, true).join();
                primary.sendText(event, true).join();

                assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
                    while (db.queryForObject("SELECT expires_epoch FROM daemon_claim_leases WHERE run_id=?",
                            Long.class, id) <= initialLease) Thread.sleep(25);
                });

                String screenshot = "YWJj".repeat(5000);
                primary.sendText("""
                        {"type":"event","message_id":"%s","payload":{"event_type":"TOOL_COMPLETED",
                         "seq":1,"data":{"screenshot_b64":"%s"}}}
                        """.formatted(id, screenshot), true).join();

                String reply = """
                        {"type":"reply","message_id":"%s","payload":{"channel":"schedule","thread_id":"%s","text":"daemon result"}}
                        """.formatted(id, id);
                contender.sendText(reply, true).join();
                assertEquals("error", second.next(json).path("type").asText());
                primary.sendText(reply, true).join();
                assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
                    while (!"SUCCEEDED".equals(db.queryForObject(
                            "SELECT status FROM generation_runs WHERE id=?", String.class, id))) Thread.sleep(50);
                });
                var history = send(client, "GET", "/api/v1/schedules/" + schedule + "/runs", token, null);
                assertEquals(200, history.statusCode(), history.body());
                var run = json.readTree(history.body()).at("/runs/0");
                assertEquals("COMPLETED", run.path("status").asText());
                assertEquals("daemon", run.path("model_used").asText());
                assertEquals("daemon result", run.path("result").asText());
                var response = send(client, "GET", "/api/v1/tasks/" + id + "/events", token, null);
                assertEquals(200, response.statusCode(), response.body());
                var events = json.readTree(response.body()).path("events");
                assertEquals(java.util.List.of("WORKFLOW_STARTED", "DAEMON_PROGRESS", "LLM_OUTPUT", "TOOL_COMPLETED", "WORKFLOW_COMPLETED"),
                        java.util.stream.StreamSupport.stream(events.spliterator(), false)
                                .map(item -> item.path("type").asText()).toList());
                assertEquals("draft ready", events.at("/2/payload/message").asText());
                assertEquals("summary", events.at("/2/payload/data/phase").asText());
                var data = events.at("/3/payload/data");
                assertTrue(data.path("screenshot_b64").isNull());
                String key = data.at("/screenshot_b64_ref/redis_key").asText();
                String blobPath = "/api/v1/blob/" + key;
                assertEquals(404, send(client, "GET", blobPath, "beta-root", null).statusCode());
                var downloaded = send(client, "GET", blobPath, token, null);
                assertEquals(200, downloaded.statusCode());
                assertTrue(downloaded.headers().firstValue("Content-Type").orElseThrow().startsWith("text/plain"));
                assertEquals(screenshot, downloaded.body());
                db.update("UPDATE gateway_blobs SET expires_epoch=0 WHERE run_id=?", id);
                assertEquals(404, send(client, "GET", blobPath, token, null).statusCode());
            } finally {
                primary.abort();
                contender.abort();
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void daemonDisconnectOrExpiredLeaseReleasesClaim(boolean expire) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            String token = "epsilon-root";
            var created = send(client, "POST", "/api/v1/schedules", token, """
                    {"name":"disconnect schedule","cron_expression":"0 * * * *","timezone":"UTC",
                     "task_query":"produce summary","execution_target":"daemon"}
                    """);
            assertEquals(201, created.statusCode(), created.body());
            String schedule = json.readTree(created.body()).path("schedule_id").asText();
            var listener = new DaemonListener();
            var socket = openDaemon(client, token, listener);
            String id;
            try {
                assertEquals("connected", listener.next(json).path("type").asText());
                var now = java.time.Instant.now();
                db.update("UPDATE scheduled_tasks SET next_run_epoch=? WHERE id=?", now.getEpochSecond(), schedule);
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(schedules, "dispatchDue", now);
                id = listener.next(json).path("message_id").asText();
                socket.sendText("""
                        {"type":"claim","message_id":"%s"}
                        """.formatted(id), true).join();
                assertTrue(listener.next(json).at("/payload/granted").asBoolean());
                if (expire) {
                    db.update("UPDATE daemon_claim_leases SET expires_epoch=? WHERE run_id=?", now.getEpochSecond(), id);
                    store.expireDaemonClaims(now.getEpochSecond());
                } else {
                    socket.sendText("{\"type\":\"disconnect\"}", true).join();
                }
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (!"FAILED".equals(db.queryForObject(
                            "SELECT status FROM generation_runs WHERE id=?", String.class, id))) Thread.sleep(50);
                });
            } finally {
                socket.abort();
            }
            assertNull(db.queryForObject("SELECT connection_id FROM scheduled_daemon_runs WHERE run_id=?", String.class, id));
            var history = send(client, "GET", "/api/v1/schedules/" + schedule + "/runs", token, null);
            assertEquals(200, history.statusCode(), history.body());
            assertTrue(json.readTree(history.body()).at("/runs/0/error_message").asText().contains(expire ? "领取已过期" : "连接中断"));
        }
    }

    private java.net.http.WebSocket openDaemon(HttpClient client, String key, DaemonListener listener) throws Exception {
        return client.newWebSocketBuilder().header("Authorization", "Bearer " + key)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/v1/ws/messages"), listener)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static final class DaemonListener implements java.net.http.WebSocket.Listener {
        private final java.util.concurrent.BlockingQueue<String> queue = new java.util.concurrent.LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override public java.util.concurrent.CompletionStage<?> onText(
                java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                queue.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        com.fasterxml.jackson.databind.JsonNode next(ObjectMapper json) throws Exception {
            String wire = queue.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(wire, "没有收到 daemon WebSocket 消息");
            return json.readTree(wire);
        }
    }

    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;

    @Test void twoInstancesAtomicallyShareMinuteAndHourWindows() throws Exception {
        var start = java.time.Instant.parse("2026-11-20T12:00:10Z");
        var clock = java.time.Clock.fixed(start, java.time.ZoneOffset.UTC);
        var first = new GatewayRateLimits(db, transaction, 3, 4, clock);
        var second = new GatewayRateLimits(db, transaction, 3, 4, clock);
        String tenant = java.util.UUID.randomUUID().toString();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 12; i++) {
                var instance = i % 2 == 0 ? first : second;
                requests.add(executor.submit(() -> instance.retryAfter(tenant)));
            }
            int allowed = 0;
            for (var request : requests) {
                long retry = request.get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (retry == 0) allowed++;
                else assertEquals(50, retry);
            }
            assertEquals(3, allowed);
        }
        var nextMinute = new GatewayRateLimits(db, transaction, 3, 4,
                java.time.Clock.fixed(start.plusSeconds(60), java.time.ZoneOffset.UTC));
        assertEquals(0, nextMinute.retryAfter(tenant));
        assertEquals(3530, nextMinute.retryAfter(tenant));
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String bearer, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
