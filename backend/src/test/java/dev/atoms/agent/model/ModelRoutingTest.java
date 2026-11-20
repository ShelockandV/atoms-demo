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

    private ModelCatalog catalog() {
        var catalog = new ModelCatalog("primary", "fast,fallback", "chat", "", "image", "");
        ReflectionTestUtils.setField(catalog, "buildFast", "fast");
        ReflectionTestUtils.setField(catalog, "buildFallback", "fallback");
        catalog.validateRouting();
        return catalog;
    }
}
