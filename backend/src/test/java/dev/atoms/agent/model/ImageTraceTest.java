package dev.atoms.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 验证图像供应商收到任务追踪头，例如关联一次失败的生成请求。 */
class ImageTraceTest {
    @Test void sendsTraceparentToImageProvider() throws Exception {
        var header = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/generate", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                header.set(exchange.getRequestHeaders().getFirst("traceparent"));
                byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var client = new ImageClient(new ObjectMapper(), false, "", "",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/generate", "test", "1024*1024");
            String trace = "0123456789abcdef0123456789abcdef";
            var failure = assertThrows(IllegalStateException.class, () -> client.generate("示例", "test", trace));
            assertTrue(failure.getMessage().contains("未返回图片"));
            assertNotNull(header.get());
            assertTrue(header.get().matches("00-" + trace + "-[0-9a-f]{16}-00"));
        } finally {
            server.stop(0);
        }
    }
}
