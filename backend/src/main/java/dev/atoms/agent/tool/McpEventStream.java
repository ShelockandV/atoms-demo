package dev.atoms.agent.tool;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class McpEventStream {
    private final InputStream input;
    private final int limit;
    private final long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
    private int read;
    private boolean firstLine = true;
    private boolean afterCr;

    McpEventStream(InputStream body, int limit) {
        input = new BufferedInputStream(body);
        this.limit = limit;
    }

    static byte[] read(InputStream body, int limit, ObjectMapper json, String id) throws IOException {
        return new McpEventStream(body, limit).response(json, id);
    }

    byte[] response(ObjectMapper json, String id) throws IOException {
        var parser = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        while (true) {
            var frame = next();
            var data = frame.data().getBytes(StandardCharsets.UTF_8);
            var message = parser.readTree(data);
            if (message == null || !"2.0".equals(message.path("jsonrpc").asText()))
                throw new IOException("MCP 事件流包含无效的 JSON-RPC 消息");
            if (!message.has("method") && id.equals(message.path("id").asText())) return data;
            if (message.has("method") && message.hasNonNull("id"))
                throw new IOException("MCP 服务器请求需要当前不支持的交互");
        }
    }

    record Frame(String type, String data) {}

    Frame next() throws IOException {
        var data = new StringBuilder();
        String type = "message";
        while (true) {
            String line = line();
            if (line.isEmpty()) {
                if (!data.isEmpty() && !data.toString().isBlank())
                    return new Frame(type, data.substring(0, data.length() - 1));
                data.setLength(0);
                type = "message";
            } else if (line.startsWith("event:")) {
                type = field(line, 6);
            } else if ("data".equals(line)) {
                data.append('\n');
            } else if (line.startsWith("data:")) {
                data.append(field(line, 5)).append('\n');
            }
        }
    }

    private String line() throws IOException {
        var buffer = new ByteArrayOutputStream();
        while (read++ < limit) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
                throw new IOException("MCP 事件流读取超时");
            int value = input.read();
            if (value < 0) throw new IOException("MCP 事件流在返回结果之前结束");
            if (value == '\n' && afterCr) {
                afterCr = false;
                continue;
            }
            afterCr = value == '\r';
            if (value != '\n' && value != '\r') {
                buffer.write(value);
                continue;
            }
            String result = buffer.toString(StandardCharsets.UTF_8);
            if (firstLine && result.startsWith("\uFEFF")) result = result.substring(1);
            firstLine = false;
            return result;
        }
        throw new IOException("MCP 事件流超过大小限制");
    }

    private static String field(String line, int start) {
        return line.length() > start && line.charAt(start) == ' ' ? line.substring(start + 1) : line.substring(start);
    }
}
