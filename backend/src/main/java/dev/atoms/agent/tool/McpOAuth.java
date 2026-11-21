package dev.atoms.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.agent.network.PublicHttps;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

final class McpOAuth {
    private final URI endpoint;
    private final String authorization;
    private final String form;
    private final ObjectMapper json;
    private final Exchange exchange;

    @FunctionalInterface
    interface Exchange {
        PublicHttps.Download token(URI endpoint, String form, String authorization) throws IOException;
    }
    private String cached = "";
    private long refreshAt;

    McpOAuth(URI endpoint, String client, String secret, String scope, String resource, ObjectMapper json) {
        this(endpoint, client, secret, scope, resource, json, PublicHttps::oauthToken);
    }

    McpOAuth(URI endpoint, String client, String secret, String scope, String resource, ObjectMapper json, Exchange exchange) {
        this.exchange = exchange;
        PublicHttps.validateEndpoint(endpoint, host -> true);
        if (endpoint.getRawQuery() != null) throw new IllegalArgumentException("OAuth Token 端点不能包含查询串");
        this.endpoint = endpoint;
        this.json = json;
        String credentials = encode(client) + ":" + encode(secret);
        authorization = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        form = "grant_type=client_credentials" + (scope.isBlank() ? "" : "&scope=" + encode(scope))
                + (resource.isBlank() ? "" : "&resource=" + encode(resource));
    }

    synchronized String token() throws IOException {
        long now = Instant.now().getEpochSecond();
        if (now < refreshAt && !cached.isEmpty()) return cached;
        var response = exchange.token(endpoint, form, authorization);
        if (!"application/json".equals(response.mediaType())) throw new IOException("OAuth Token 响应不是 JSON");
        var document = json.readTree(response.bytes());
        String token = document == null ? "" : document.path("access_token").asText();
        if (!token.matches("[A-Za-z0-9._~+/-]{1,8192}={0,8}") || !"Bearer".equalsIgnoreCase(document.path("token_type").asText()))
            throw new IOException("OAuth Token 响应无效");
        long expires = document.path("expires_in").asLong(0);
        refreshAt = now + Math.max(0, Math.min(3600, expires) - 15);
        cached = token;
        return cached;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
