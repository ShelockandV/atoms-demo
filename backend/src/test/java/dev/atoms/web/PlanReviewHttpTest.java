package dev.atoms.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@org.springframework.context.annotation.Import(dev.atoms.OfflineModelTestConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "atoms.approval.plan-review-enabled=true", "atoms.approval.supervisor-review-token=test-review-secret",
        "spring.sql.init.mode=always"
})
class PlanReviewHttpTest extends dev.atoms.MySqlTestDatabase {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    @Test void aUserCanReviseReviewAndApproveOnlyTheCurrentPlan() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
             var stranger = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var project = json.readTree(send(client, "/projects", "{\"title\":\"Review project\"}").body());
            var created = send(client, "/projects/" + project.path("id").asText() + "/runs", """
                    {"prompt":"创建待办列表","mode":"build"}
                    """);
            assertEquals(200, created.statusCode(), created.body());
            String run = "/runs/" + json.readTree(created.body()).path("id").asText();
            JsonNode first = waitFor(client, run, "AWAITING_APPROVAL");
            assertEquals(1, first.path("planReviewVersion").asInt());
            assertFalse(first.path("plan").asText().isBlank());
            String staleId = first.path("approvalId").asText();
            assertEquals(404, send(stranger, run + "/plan-review", null).statusCode());

            var revised = send(client, run + "/plan-review", """
                    {"version":1,"feedback":"放在右上角"}
                    """);
            assertEquals(200, revised.statusCode(), revised.body());
            var review = json.readTree(revised.body());
            assertEquals(2, review.path("version").asInt());
            assertEquals("放在右上角", review.at("/revisions/0/feedback").asText());
            assertEquals(first.path("plan").asText(), review.at("/revisions/1/plan_text").asText());
            assertEquals(409, send(client, run + "/plan-review", """
                    {"version":1,"feedback":"重复提交"}
                    """).statusCode());
            assertEquals(409, send(client, run + "/approval?approved=true&approvalId=" + staleId, "").statusCode());
            String latest = review.path("approvalId").asText();
            assertNotEquals(staleId, latest);
            assertEquals(200, send(client, run + "/approval?approved=true&approvalId=" + latest, "").statusCode());
            var completed = waitFor(client, run, "SUCCEEDED");
            assertTrue(completed.path("plan").asText().contains("放在右上角"));
            var history = json.readTree(send(client, run + "/plan-review", null).body());
            assertEquals("APPROVED", history.path("decision").asText());
            assertEquals(2, history.path("revisions").size());
        }
    }

    @org.springframework.test.context.DynamicPropertySource
    static void workflow(org.springframework.test.context.DynamicPropertyRegistry registry) throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("atoms-supervisor-review");
        dir.toFile().deleteOnExit();
        var template = java.nio.file.Files.writeString(dir.resolve("team.yml"), """
                name: team
                version: 1.0.0
                nodes:
                  - id: lead
                    type: supervisor
                """);
        template.toFile().deleteOnExit();
        registry.add("atoms.agent.workflow-templates-directory", dir::toString);
    }

    @Test void onlyIndependentReviewerCanDecideCurrentSupervisorVersion() throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
             var reviewer = HttpClient.newHttpClient()) {
            var project = json.readTree(send(client, "/projects", "{\"title\":\"Supervisor project\"}").body());
            var created = send(client, "/projects/" + project.path("id").asText() + "/runs", """
                    {"prompt":"创建待办列表","mode":"build","template":"team"}
                    """);
            assertEquals(200, created.statusCode(), created.body());
            String id = json.readTree(created.body()).path("id").asText();
            String run = "/runs/" + id;
            var first = waitFor(client, run, "AWAITING_APPROVAL");
            assertTrue(first.path("supervisorReview").asBoolean());
            String original = first.path("approvalId").asText();
            assertEquals(403, send(client, run + "/approval?approved=true&approvalId=" + original, "").statusCode());
            assertEquals(401, send(reviewer, "/supervisor-reviews", null).statusCode());
            var revised = send(client, run + "/plan-review", "{\"version\":1,\"feedback\":\"保留右上角按钮\"}");
            assertEquals(200, revised.statusCode(), revised.body());
            String current = json.readTree(revised.body()).path("approvalId").asText();
            assertEquals(409, decide(reviewer, id, original).statusCode());
            assertEquals(200, decide(reviewer, id, current).statusCode());
            assertTrue(waitFor(client, run, "SUCCEEDED").path("plan").asText().contains("保留右上角按钮"));
        }
    }

    private HttpResponse<String> decide(HttpClient reviewer, String id, String approvalId) throws Exception {
        return reviewer.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/supervisor-reviews/" + id))
                .header("Authorization", "Bearer test-review-secret").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(java.util.Map.of(
                        "approvalId", approvalId, "approved", true)))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode waitFor(HttpClient client, String path, String status) {
        return assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
            while (true) {
                var response = send(client, path, null);
                assertEquals(200, response.statusCode(), response.body());
                var run = json.readTree(response.body());
                if (status.equals(run.path("status").asText())) return run;
                assertNotEquals("FAILED", run.path("status").asText(), response.body());
                Thread.sleep(50);
            }
        });
    }

    private HttpResponse<String> send(HttpClient client, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(10));
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
