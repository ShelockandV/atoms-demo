package dev.atoms.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "atoms.demo=true", "atoms.gateway.tenant-tokens.alpha=configured-root",
        "atoms.gateway.jwt.signing-key=google-session-test-signing-key-with-enough-bytes",
        "atoms.gateway.google.gmail-members.[allowed@gmail.com]=alpha",
        "atoms.gateway.google.client-id=local-test", "atoms.gateway.google.members.10001=alpha",
        "spring.datasource.url=jdbc:h2:mem:gateway_google;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always"
})
class GatewayGoogleHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockitoSpyBean GoogleGatewayIdentity google;

    @Test void googleRegistrationAuthenticatesAndRefreshTokensRotateOnce() throws Exception {
        doReturn(new GoogleGatewayIdentity.Identity("10001", null)).when(google).verify("valid");
        doReturn(new GoogleGatewayIdentity.Identity("20002", null)).when(google).verify("nonmember");
        try (var http = HttpClient.newHttpClient()) {
            assertEquals(401, send(http, "/register", "{\"provider\":\"google\",\"token\":\"nonmember\"}", null).statusCode());
            var registered = send(http, "/register", "{\"provider\":\"google\",\"token\":\"valid\"}", null);
            assertEquals(200, registered.statusCode(), registered.body());
            var session = json.readTree(registered.body());
            assertEquals("alpha", session.path("tenant_id").asText());
            assertTrue(session.path("is_new_user").asBoolean());
            String access = session.path("access_token").asText();
            String refresh = session.path("refresh_token").asText();
            assertFalse(refresh.isBlank());
            var me = send(http, "/me", null, access);
            assertEquals(200, me.statusCode(), me.body());
            assertEquals("10001", json.readTree(me.body()).path("user_id").asText());
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM gateway_refresh_tokens WHERE token_hash=?",
                    Integer.class, refresh));
            String body = "{\"refresh_token\":\"" + refresh + "\"}";
            var refreshed = send(http, "/refresh", body, null);
            assertEquals(200, refreshed.statusCode(), refreshed.body());
            var next = json.readTree(refreshed.body());
            assertNotEquals(refresh, next.path("refresh_token").asText());
            assertEquals(200, send(http, "/me", null, next.path("access_token").asText()).statusCode());
            assertEquals(401, send(http, "/refresh", body, null).statusCode());
            var login = send(http, "/register", "{\"provider\":\"google\",\"token\":\"valid\"}", null);
            assertEquals(200, login.statusCode());
            assertFalse(json.readTree(login.body()).path("is_new_user").asBoolean(true));
        }
    }

    @Test void verifiedGmailBindsOnceToGoogleSubject() throws Exception {
        doReturn(new GoogleGatewayIdentity.Identity("30001", "allowed@gmail.com")).when(google).verify("allowed");
        doReturn(new GoogleGatewayIdentity.Identity("30002", "allowed@gmail.com")).when(google).verify("different-subject");
        doReturn(new GoogleGatewayIdentity.Identity("30003", null)).when(google).verify("unverified");
        try (var http = HttpClient.newHttpClient()) {
            String body = "{\"provider\":\"google\",\"token\":\"%s\"}";
            assertEquals(401, send(http, "/register", body.formatted("unverified"), null).statusCode());
            var registered = send(http, "/register", body.formatted("allowed"), null);
            assertEquals(200, registered.statusCode(), registered.body());
            var session = json.readTree(registered.body());
            assertEquals(200, send(http, "/me", null, session.path("access_token").asText()).statusCode());
            assertEquals(401, send(http, "/register", body.formatted("different-subject"), null).statusCode());
        }
    }

    private HttpResponse<String> send(HttpClient http, String path, String body, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth" + path));
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
