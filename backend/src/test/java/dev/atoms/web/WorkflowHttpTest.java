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
        "atoms.research.models.planning=qwen-flash",
        "atoms.model.rates.test.input=1", "atoms.model.rates.test.output=2",
        "atoms.metrics.enabled=true", "atoms.metrics.token=test-metrics-reader",
        "spring.datasource.url=jdbc:h2:mem:workflow_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always"
})
class WorkflowHttpTest {
    @LocalServerPort int port;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired dev.atoms.agent.persistence.Store store;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test void openAiGatewayExposesModelsAndReturnsJsonAndSse() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            String base = "http://127.0.0.1:" + port + "/v1";
            var catalog = client.send(HttpRequest.newBuilder(URI.create(base + "/models")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, catalog.statusCode(), catalog.body());
            var model = json.readTree(catalog.body()).path("data").get(0);
            var detail = client.send(HttpRequest.newBuilder(URI.create(base + "/models/" + model.path("id").asText())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(model, json.readTree(detail.body()));

            var input = json.createObjectNode().put("model", model.path("id").asText());
            var messages = input.putArray("messages");
            messages.addObject().put("role", "developer").put("content", "Respond briefly.");
            messages.addObject().put("role", "user").put("content", "compat hello");
            var endpoint = URI.create(base + "/chat/completions");
            var request = HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10));
            var reply = client.send(request.copy().POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, reply.statusCode(), reply.body());
            var completion = json.readTree(reply.body());
            assertEquals("chat.completion", completion.path("object").asText());
            assertEquals("【演示回答】compat hello", completion.at("/choices/0/message/content").asText());
            String taskId = completion.path("id").asText().substring("chatcmpl-".length());
            assertEquals("TASK_STATUS_COMPLETED", call(client, "/v1/tasks/" + taskId, null).path("status").asText());

            input.remove("model");
            ((com.fasterxml.jackson.databind.node.ObjectNode) messages.get(1)).putArray("content")
                    .addObject().put("type", "text").put("text", "compat hello");
            input.put("stream", true).putObject("stream_options").put("include_usage", true);
            var stream = client.send(request.copy().POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, stream.statusCode(), stream.body());
            assertTrue(stream.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
            var frames = stream.body().lines().filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()).strip()).toList();
            var lifecycle = frames.stream().filter(frame -> frame.contains("\"shannon_events\"")).toList();
            assertEquals("WORKFLOW_STARTED", json.readTree(lifecycle.getFirst()).at("/shannon_events/0/type").asText());
            assertEquals("WORKFLOW_COMPLETED", json.readTree(lifecycle.getLast()).at("/shannon_events/0/type").asText());
            var events = frames.stream().filter(frame -> !lifecycle.contains(frame)).toList();
            assertEquals(5, events.size(), stream.body());
            assertEquals("assistant", json.readTree(events.get(0)).at("/choices/0/delta/role").asText());
            assertEquals("shannon-chat", json.readTree(events.get(0)).path("model").asText());
            assertEquals("【演示回答】compat hello", json.readTree(events.get(1)).at("/choices/0/delta/content").asText());
            assertEquals("stop", json.readTree(events.get(2)).at("/choices/0/finish_reason").asText());
            var usage = json.readTree(events.get(3));
            assertTrue(usage.path("choices").isArray() && usage.path("choices").isEmpty());
            assertEquals(0, usage.at("/usage/total_tokens").asInt(-1));
            assertEquals("[DONE]", events.get(4));

            input.put("model", "missing-openai-model");
            var missing = client.send(request.copy().POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode(), missing.body());
            assertEquals("model_not_found", json.readTree(missing.body()).at("/error/code").asText());
        }
    }

    @Test void chatAcceptsToolDeclarationsAndHistoryForDirectAndWorkflowModels() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
            String payload = """
                    {"model":"%s","tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object"}}}],
                     "tool_choice":"auto","messages":[{"role":"user","content":"check status"},
                       {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function",
                         "function":{"name":"lookup","arguments":"{}"}}]},
                       {"role":"tool","tool_call_id":"call_1","content":"ready"}]}
                    """;
            var reply = client.send(request.copy().POST(HttpRequest.BodyPublishers.ofString(payload.formatted("shannon-chat"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, reply.statusCode(), reply.body());
            assertEquals("【演示回答】check status", json.readTree(reply.body()).at("/choices/0/message/content").asText());
            for (String model : java.util.List.of("shannon-complex", "shannon-quick-research")) {
                var accepted = client.send(request.copy().POST(HttpRequest.BodyPublishers.ofString(payload.formatted(model))).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, accepted.statusCode(), accepted.body());
            }
        }
    }

    @Test void legacyCompletionsUseShannonJsonAndSseResponseShapes() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var endpoint = URI.create("http://127.0.0.1:" + port + "/v1/completions");
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json");
            var input = json.createObjectNode().put("specific_model", "qwen-flash").put("max_tokens", 100);
            input.putArray("messages").addObject().put("role", "user").put("content", "legacy hello");
            var response = client.send(request.copy().POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            var body = json.readTree(response.body());
            assertEquals("qwen-flash", body.path("model").asText());
            assertEquals("【演示回答】legacy hello", body.path("output_text").asText());
            assertEquals(0, body.at("/usage/total_tokens").asInt(-1));
            input.put("stream", true);
            var streamed = client.send(request.copy().POST(HttpRequest.BodyPublishers.ofString(input.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, streamed.statusCode(), streamed.body());
            var events = streamed.body().lines().filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).strip()).toList();
            assertEquals(3, events.size(), streamed.body());
            assertEquals("content_delta", json.readTree(events.get(0)).path("type").asText());
            assertEquals("done", json.readTree(events.get(1)).path("type").asText());
            assertEquals("[DONE]", events.get(2));
        }
    }

    @Test void legacyCompletionsRequireToolResultsToMatchAssistantCallIds() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/completions"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
            String body = """
                    {"messages":[
                      {"role":"user","content":"check release"},
                      {"role":"assistant","content":null,"tool_calls":[
                        {"id":"call_status","name":"lookup_status","arguments":{"item":"release"}}
                      ]},
                      {"role":"tool","tool_call_id":"%s","content":"ready"}
                    ]}
                    """;
            var valid = client.send(request.copy().POST(HttpRequest.BodyPublishers.ofString(body.formatted("call_status"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, valid.statusCode(), valid.body());
            assertEquals("【演示回答】check release", json.readTree(valid.body()).path("output_text").asText());
            var invalid = client.send(request.copy().POST(HttpRequest.BodyPublishers.ofString(body.formatted("wrong_call"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode(), invalid.body());
            assertEquals("Unknown or duplicate tool_call_id", json.readTree(invalid.body()).at("/error/message").asText());
        }
    }

    @Test void openAiComplexAliasReturnsTextFromCheckpointedSupervisor() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            String endpoint = "http://127.0.0.1:" + port + "/v1/chat/completions";
            var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("""
                            {"model":"shannon-complex","messages":[{"role":"user","content":"比较两个方案"}]}
                            """)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            var completion = json.readTree(response.body());
            assertEquals("shannon-complex", completion.path("model").asText());
            assertEquals("【演示多角色回答】比较两个方案", completion.at("/choices/0/message/content").asText());
            String id = completion.path("id").asText().substring("chatcmpl-".length());
            var task = call(client, "/v1/tasks/" + id, null);
            assertEquals("EXECUTION_MODE_COMPLEX", task.path("mode").asText());
            var nodes = call(client, "/runs/" + id, null).path("nodes");
            var succeeded = new java.util.HashSet<String>();
            nodes.forEach(node -> {
                if ("SUCCEEDED".equals(node.path("status").asText())) succeeded.add(node.path("id").asText());
            });
            assertTrue(succeeded.containsAll(java.util.Set.of("template.answer.supervisor.plan", "template.answer.requirements",
                    "template.answer.review", "template.answer.supervisor.synthesis")), nodes.toString());
        }
    }

    @Test void openAiResearchAliasesRunTheResearchWorkflow() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            String base = "http://127.0.0.1:" + port + "/v1";
            var catalog = client.send(HttpRequest.newBuilder(URI.create(base + "/models")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, catalog.statusCode());
            var ids = new java.util.HashSet<String>();
            json.readTree(catalog.body()).path("data").forEach(model -> ids.add(model.path("id").asText()));
            for (String strategy : java.util.List.of("quick", "standard", "deep")) {
                String alias = "shannon-" + strategy + "-research";
                assertTrue(ids.contains(alias));
                var request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                        .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"model":"%s","messages":[{"role":"user","content":[
                                  {"type":"text","text":"验收条件"},
                                  {"type":"file","file":{"filename":"guide.md","file_data":"data:text/markdown;base64,%s"}}
                                ]}]}
                                """.formatted(alias, java.util.Base64.getEncoder().encodeToString(
                                "验收条件：周五完成。".getBytes(java.nio.charset.StandardCharsets.UTF_8))))).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                var completion = json.readTree(response.body());
                assertEquals(alias, completion.path("model").asText());
                String report = completion.at("/choices/0/message/content").asText();
                assertTrue(report.contains("周五完成") && report.contains("attachments/1-guide.md"), report);
                String id = completion.path("id").asText().substring("chatcmpl-".length());
                assertTrue(call(client, "/v1/tasks/" + id, null).path("research").asBoolean());
                assertEquals(strategy, json.readTree(store.taskContext(id)).path("research_strategy").asText());
                var route = store.dagNodes(id).stream()
                        .filter(node -> "research.model-routing".equals(node.get("id"))).findFirst().orElseThrow();
                assertEquals("qwen-flash", json.readTree((String) route.get("output")).path("planning").asText());
            }
        }
    }

    @Test void openAiResearchCitesTextExtractedFromPdf() throws Exception {
        var pdf = new java.io.ByteArrayOutputStream();
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 750);
                stream.showText("Release deadline Friday");
                stream.endText();
            }
            document.save(pdf);
        }
        String data = java.util.Base64.getEncoder().encodeToString(pdf.toByteArray());
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                    .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"model":"shannon-quick-research","messages":[{"role":"user","content":[
                              {"type":"text","text":"release deadline"},
                              {"type":"file","file":{"filename":"guide.pdf","file_data":"data:application/pdf;base64,%s"}}
                            ]}]}
                            """.formatted(data))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            String result = json.readTree(response.body()).at("/choices/0/message/content").asText();
            assertTrue(result.contains("Release deadline Friday") && result.contains("guide.pdf"), result);
        }
    }

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
                        var board = call(client, "/v1/swarm/" + submitted.path("task_id").asText() + "/board", null);
                        var team = board.path("teams").get(0);
                        assertEquals("SUCCEEDED", team.path("synthesis").asText());
                        assertEquals(2, team.path("tasks").size());
                        assertEquals("analyst", team.path("tasks").get(0).path("role").asText());
                        assertFalse(board.path("accepting_input").asBoolean());
                        return;
                    }
                    Thread.sleep(30);
                }
            });
        }
    }

    @Test void gatewayApprovalDecisionCanRejectPendingTask() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/tasks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"query":"创建任务看板","context":{"template":"approval","disable_ai":true}}
                            """)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());
            String id = json.readTree(response.body()).path("task_id").asText();
            String path = "/v1/tasks/" + id;
            var task = call(client, path, null);
            String approvalId = task.path("approval_id").asText();
            assertFalse(approvalId.isBlank());
            var decision = call(client, "/v1/approvals/decision", json.writeValueAsString(java.util.Map.of(
                    "workflow_id", id, "approval_id", approvalId, "approved", false)));
            assertTrue(decision.path("success").asBoolean());
            assertEquals("TASK_STATUS_FAILED", call(client, path, null).path("status").asText());
            assertEquals("WORKFLOW_FAILED", call(client, path + "/events", null)
                    .path("events").get(0).path("type").asText());
        }
    }

    @Test void shannonTaskCanBePausedAndResumedThroughGateway() throws Exception {
        var cookies = new CookieManager();
        try (var client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            call(client, "/config", null);
            String owner = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "ATOMS_VISITOR".equals(cookie.getName())).findFirst().orElseThrow().getValue();
            String session = (String) store.create(owner, "暂停测试").get("id");
            var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
            String id = transaction.execute(status -> {
                String runId = (String) store.queue(session, owner, "hello", null, "chat", "test").get("id");
                store.claim(runId, owner);
                return runId;
            });
            for (String action : java.util.List.of("pause", "resume")) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/api/v1/tasks/" + id + "/" + action))
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(202, response.statusCode(), response.body());
                assertTrue(json.readTree(response.body()).path("success").asBoolean());
                var control = call(client, "/v1/tasks/" + id + "/control-state", null);
                assertEquals("pause".equals(action), control.path("is_paused").asBoolean());
                assertEquals("resume".equals(action), control.path("paused_at").asText().isBlank());
            }
            String second = (String) store.create(owner, "取消测试").get("id");
            String cancelled = transaction.execute(status -> {
                String runId = (String) store.queue(second, owner, "cancel", null, "chat", "test").get("id");
                String token = store.claim(runId, owner);
                store.reserveModelCall(runId, owner, token, 10);
                store.recordModelUsage(runId, owner, token, 12, 3);
                return runId;
            });
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/api/v1/tasks/" + cancelled + "/cancel"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(202, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals("TASK_STATUS_CANCELLED", call(client, "/v1/tasks/" + cancelled, null).path("status").asText());
            assertTrue(call(client, "/v1/tasks/" + cancelled + "/control-state", null).path("is_cancelled").asBoolean());
            String listing = "/v1/tasks?session_id=" + second + "&status=canceled&limit=1";
            var page = call(client, listing, null);
            assertEquals(1, page.path("total_count").asInt());
            assertEquals(cancelled, page.path("tasks").get(0).path("task_id").asText());
            assertEquals(15, page.path("tasks").get(0).path("total_token_usage").path("total_tokens").asLong());
            assertEquals(0, call(client, listing + "&offset=1", null).path("tasks").size());
            assertDoesNotThrow(() -> store.queue(second, owner, "new task", null, "chat", "test"));
        }
    }

    @Test void gatewayResearchReturnsACitedReportWithoutChangingTheWorkspace() throws Exception {
        var cookies = new CookieManager();
        try (var client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            call(client, "/config", null);
            String owner = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "ATOMS_VISITOR".equals(cookie.getName())).findFirst().orElseThrow().getValue();
            String session = (String) store.createGatewaySession(owner).get("id");
            String version = (String) store.save(session, owner, null,
                    java.util.Map.of("guide.md", "采购约定：到货后验收。"), "save", null).get("id");
            String body = json.writeValueAsString(java.util.Map.of("query", "采购约定", "session_id", session,
                    "context", java.util.Map.of("force_research", true)));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/tasks"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var submitted = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, submitted.statusCode(), submitted.body());
            String id = json.readTree(submitted.body()).path("task_id").asText();
            assertTimeoutPreemptively(Duration.ofSeconds(6), () -> {
                while (true) {
                    var task = call(client, "/v1/tasks/" + id, null);
                    assertNotEquals("TASK_STATUS_FAILED", task.path("status").asText(), task.toString());
                    if ("TASK_STATUS_COMPLETED".equals(task.path("status").asText())) {
                        assertTrue(task.path("research").asBoolean());
                        assertTrue(task.path("result").asText().contains("[S1] guide.md"));
                        assertTrue(task.path("result").asText().contains("到货后验收"));
                        return;
                    }
                    Thread.sleep(35);
                }
            });
            assertTrue(call(client, "/v1/sessions/" + session, null).path("is_research_session").asBoolean());
            assertEquals(version, store.project(session, owner).get("current_version"));
            var run = call(client, "/runs/" + id, null);
            assertTrue(run.path("nodes").toString().contains("research.report"));
        }
    }

    @Test void recurringScheduleQueuesAndRecordsARealTask() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
             var outsider = HttpClient.newHttpClient()) {
            var create = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/schedules"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("""
                            {"name":"Hourly summary","cron_expression":"0 * * * *","timezone":"Asia/Shanghai",
                             "task_query":"scheduled hello","task_context":{"format":"markdown"},"timeout_seconds":45,"max_budget_per_run_usd":0.03}
                            """)).build();
            var response = client.send(create, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());
            var created = json.readTree(response.body());
            String id = created.path("schedule_id").asText();
            assertFalse(id.isBlank());
            String path = "/v1/schedules/" + id;
            String url = "http://127.0.0.1:" + port + "/api" + path;
            assertEquals(404, outsider.send(HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals("ACTIVE", call(client, path, null).path("status").asText());
            call(client, path + "/pause", "{}");
            assertEquals("PAUSED", call(client, path, null).path("status").asText());
            assertEquals(1, call(client, "/v1/schedules?status=PAUSED", null).path("total_count").asInt());
            var edit = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"description\":\"Updated\"}")).build();
            assertEquals(200, client.send(edit, HttpResponse.BodyHandlers.discarding()).statusCode());
            var updated = call(client, path, null);
            assertEquals("Updated", updated.path("description").asText());
            assertEquals(45, updated.path("timeout_seconds").asInt());
            assertEquals(0.03, updated.path("max_budget_per_run_usd").asDouble());
            call(client, path + "/resume", "{}");
            long occurrence = java.time.Instant.now().getEpochSecond();
            db.update("UPDATE scheduled_tasks SET next_run_epoch=? WHERE id=?", occurrence, id);

            assertTimeoutPreemptively(Duration.ofSeconds(12), () -> {
                while (true) {
                    var history = call(client, path + "/runs", null);
                    if (history.path("total_count").asInt() == 1) {
                        var run = history.path("runs").get(0);
                        assertNotEquals("FAILED", run.path("status").asText());
                        if ("COMPLETED".equals(run.path("status").asText())) {
                            assertTrue(run.path("result").asText().contains("scheduled hello"));
                            assertEquals(0.03, run.path("max_budget_per_run_usd").asDouble());
                            String sessionId = run.path("session_id").asText();
                            var task = call(client, "/v1/sessions/" + sessionId + "/history", null).path("tasks").get(0);
                            assertEquals(id, task.path("metadata").path("context").path("schedule_id").asText());
                            assertEquals("markdown", task.path("metadata").path("context").path("format").asText());
                            assertTrue(java.time.Instant.parse(call(client, path, null).path("next_run_at").asText())
                                    .isAfter(java.time.Instant.ofEpochSecond(occurrence)));
                            break;
                        }
                    }
                    Thread.sleep(100);
                }
            });
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(url)).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    @Test void scheduledBudgetReservesByModelAndSettlesAgainstReportedUsage() {
        String owner = "schedule-budget-test";
        String session = (String) store.createGatewaySession(owner).get("id");
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        var run = transaction.execute(status -> {
            String id = (String) store.queue(session, owner, "budget", null, "chat", "test").get("id");
            String token = store.claim(id, owner);
            db.update("INSERT INTO scheduled_run_budgets(run_id,limit_usd) VALUES(?,?)",
                    id, new java.math.BigDecimal("0.000250"));
            return java.util.Map.entry(id, token);
        });
        String id = run.getKey(), token = run.getValue();
        store.reserveModelCall(id, owner, token, 8, 0, 100, null, 0, "test");
        assertThrows(IllegalStateException.class,
                () -> store.reserveModelCall(id, owner, token, 8, 0, 100, null, 0, "test"));
        store.settleModelCall(id, owner, token, 100, 10, 10, null, "test");
        assertThrows(IllegalStateException.class,
                () -> store.reserveModelCall(id, owner, token, 8, 0, 100, null, 0, "unpriced"));
        store.reserveModelCall(id, owner, token, 8, 0, 100, null, 0, "test");
        var charged = db.queryForObject("SELECT charged_usd FROM scheduled_run_budgets WHERE run_id=?",
                java.math.BigDecimal.class, id);
        assertEquals(0, new java.math.BigDecimal("0.000230").compareTo(charged));
        store.cancel(id, owner);
    }

    @Test void expiredScheduleRunRejectsLateResultsAndReleasesItsSession() {
        String owner = "schedule-deadline-test";
        String session = (String) store.createGatewaySession(owner).get("id");
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            String run = (String) store.queue(session, owner, "deadline", null, "chat", "test").get("id");
            String token = store.claim(run, owner);
            long deadline = java.time.Instant.now().minusSeconds(1).getEpochSecond();
            db.update("INSERT INTO scheduled_run_deadlines(run_id,deadline_epoch) VALUES(?,?)", run, deadline);
            assertThrows(dev.atoms.agent.persistence.Store.StaleExecution.class,
                    () -> store.requireExecution(run, token));
            store.expireScheduledRuns(deadline);
            assertEquals("TASK_STATUS_TIMEOUT", store.simpleTask(run, owner).get("status"));
            assertFalse(store.hasExecutionToken(run, token));
            assertDoesNotThrow(() -> store.queue(session, owner, "new task", null, "chat", "test"));
        });
    }

    @Test void gatewayExposesSkillVersionsAndToolSchemas() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var catalog = call(client, "/v1/skills?category=development", null);
            assertEquals(1, catalog.path("count").asInt());
            assertEquals("2.0.0", catalog.path("skills").get(0).path("version").asText());
            assertEquals("development", catalog.path("categories").get(0).asText());
            var versions = call(client, "/v1/skills/review/versions", null);
            assertEquals(2, versions.path("count").asInt());
            assertEquals("1.0.0", versions.path("versions").get(1).path("version").asText());
            var skill = call(client, "/v1/skills/review@1.0.0", null).path("skill");
            assertEquals("Review version 1.0.0", skill.path("content").asText());
            var tools = call(client, "/v1/tools?category=workspace", null);
            assertTrue(tools.isArray());
            assertTrue(tools.toString().contains("READ_FILE"));
            var tool = call(client, "/v1/tools/READ_FILE", null);
            assertEquals("workspace", tool.path("category").asText());
            assertEquals("string", tool.path("parameters").path("properties").path("path").path("type").asText());
        }
    }

    @Test void gatewayDocumentSearchUsesVersionedUnicodeChunks() throws Exception {
        var cookies = new CookieManager();
        try (var client = HttpClient.newBuilder().cookieHandler(cookies).build();
             var outsider = HttpClient.newHttpClient()) {
            call(client, "/config", null);
            String owner = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "ATOMS_VISITOR".equals(cookie.getName())).findFirst().orElseThrow().getValue();
            String session = (String) store.createGatewaySession(owner).get("id");
            String keyword = "cross-boundary-phrase";
            String document = "😀".repeat(790) + keyword + "z".repeat(150);
            String oldVersion = (String) store.save(session, owner, null,
                    java.util.Map.of("notes/brief.md", document, "ignored.js", keyword), "first", null).get("id");
            String path = "/v1/sessions/" + session + "/documents/search?query=" + keyword;

            var first = call(client, path, null).path("hits").get(0);
            assertEquals("notes/brief.md", first.path("path").asText());
            assertEquals(1, first.path("chunk").asInt());
            assertEquals(680, first.path("start").asInt());
            assertTrue(first.path("text").asText().contains(keyword));
            assertEquals(first, call(client, path, null).path("hits").get(0));

            String oldRun = (String) store.queue(session, owner, "Use the old base", oldVersion, "build", "test").get("id");
            store.cancel(oldRun, owner);
            String newer = (String) store.save(session, owner, oldVersion, java.util.Map.of("notes/brief.md", "Different text"),
                    "second", null).get("id");
            assertTrue(call(client, path, null).path("hits").isEmpty());
            assertTrue(store.searchRunDocuments(oldRun, owner, keyword).getFirst().text().contains(keyword));
            var selected = call(client, "/projects/" + session + "/documents/search?query=" + keyword
                    + "&versionId=" + oldVersion, null);
            assertEquals(first, selected.path("hits").get(0));
            store.save(session, owner, newer, store.versionFiles(oldVersion), "restore", null);
            assertEquals(first, call(client, path, null).path("hits").get(0));

            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path)).build();
            assertEquals(404, outsider.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    @Test void gatewayWorkspaceListsAndDownloadsTheCurrentVersion() throws Exception {
        var cookies = new CookieManager();
        try (var client = HttpClient.newBuilder().cookieHandler(cookies).build();
             var outsider = HttpClient.newHttpClient()) {
            call(client, "/config", null);
            String owner = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "ATOMS_VISITOR".equals(cookie.getName())).findFirst().orElseThrow().getValue();
            String id = (String) store.createGatewaySession(owner).get("id");
            String path = "/v1/sessions/" + id + "/files";
            assertTrue(call(client, path, null).path("files").isEmpty());
            store.save(id, owner, null, java.util.Map.of("src/示例.txt", "你好", "README.md", "project"), "save", null);

            var entries = call(client, path, null).path("files");
            assertEquals(2, entries.size());
            assertEquals("README.md", entries.get(0).path("path").asText());
            assertTrue(entries.get(1).path("is_dir").asBoolean());
            var nested = call(client, path + "?path=src", null).path("files");
            assertEquals("src/示例.txt", nested.get(0).path("path").asText());
            assertEquals(6, nested.get(0).path("size_bytes").asInt());
            String file = path + "/src/%E7%A4%BA%E4%BE%8B.txt";
            assertEquals("你好", call(client, file, null).path("content").asText());

            String base = "http://127.0.0.1:" + port + "/api";
            var download = HttpRequest.newBuilder(URI.create(base + file)).build();
            assertEquals(404, outsider.send(download, HttpResponse.BodyHandlers.discarding()).statusCode());
            var invalid = HttpRequest.newBuilder(URI.create(base + path + "?path=..%2Fsrc")).build();
            assertEquals(400, client.send(invalid, HttpResponse.BodyHandlers.discarding()).statusCode());
            store.deleteGatewaySession(id, owner);
            assertEquals(404, client.send(download, HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    @Test void gatewaySubmitForStreamingReturnsUsableEventUrl() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            String base = "http://127.0.0.1:" + port;
            var request = HttpRequest.newBuilder(URI.create(base + "/api/v1/tasks/stream"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"hello\"}")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());
            var task = json.readTree(response.body());
            String workflow = task.path("workflow_id").asText();
            String streamUrl = task.path("stream_url").asText();
            assertEquals(task.path("task_id").asText(), workflow);
            assertEquals(workflow, response.headers().firstValue("X-Workflow-ID").orElseThrow());
            assertFalse(response.headers().firstValue("X-Session-ID").orElseThrow().isBlank());
            var stream = HttpRequest.newBuilder(URI.create(base + streamUrl)).timeout(Duration.ofSeconds(5)).build();
            var events = client.send(stream, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, events.statusCode());
            assertTrue(events.body().contains("WORKFLOW_COMPLETED"), events.body());
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
                        String turnsUrl = "/v1/sessions/" + sessionId + "/events";
                        var turns = call(client, turnsUrl, null);
                        assertEquals(1, turns.path("count").asInt());
                        var turn = turns.path("turns").get(0);
                        assertEquals(1, turn.path("turn").asInt());
                        assertEquals(task.path("task_id"), turn.path("task_id"));
                        assertEquals(task.path("result"), turn.path("final_output"));
                        assertEquals(events, turn.path("events"));
                        assertTrue(call(client, turnsUrl + "?offset=1", null).path("turns").isEmpty());
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
        for (String version : java.util.List.of("1.0.0", "2.0.0")) {
            var skill = Files.writeString(dir.resolve("review-" + version + ".md"), """
                    ---
                    name: review
                    category: development
                    version: %s
                    ---
                    Review version %s
                    """.formatted(version, version));
            skill.toFile().deleteOnExit();
        }
        properties.add("atoms.agent.skills-directory", dir::toString);
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

    @Test void newWorkbenchTaskRoutesByIntentAndSnapshotsModels() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var project = call(client, "/projects", "{\"title\":\"首次自动路由\"}");
            var run = call(client, "/projects/" + project.path("id").asText() + "/runs", """
                    {"mode":"build","prompt":"构建多模块的前后端预约系统","model":"auto","pattern":"auto"}
                    """);
            assertEquals("dag", run.path("pattern").asText());
            var stages = store.stageModels(run.path("id").asText());
            assertFalse(stages.getOrDefault("planning", "").isBlank());
            assertEquals(run.path("model").asText(), stages.get("review"));
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

    @Test void workbenchCanCancelItsOwnPendingRun() throws Exception {
        try (var owner = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
             var other = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var project = call(owner, "/projects", "{\"title\":\"任务终止验证\"}");
            var run = call(owner, "/projects/" + project.path("id").asText() + "/runs", """
                    {"mode":"build","prompt":"创建任务看板","template":"approval@1.0.0","requireTemplate":true}
                    """);
            assertEquals("AWAITING_APPROVAL", run.path("status").asText());
            String path = "/runs/" + run.path("id").asText();
            var denied = other.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path + "/cancel"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(404, denied.statusCode());
            assertEquals("CANCELLED", call(owner, path + "/cancel", "").path("status").asText());
            assertEquals("CANCELLED", call(owner, path, null).path("status").asText());
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
