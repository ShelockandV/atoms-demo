package dev.atoms.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class ModelSseReader {
    record Result(JsonNode message, JsonNode usage) {}

    @FunctionalInterface
    interface Event {
        boolean accept(JsonNode frame) throws Exception;
    }

    static boolean read(ObjectMapper json, InputStream body, int seconds, Event event) throws Exception {
        var expired = new AtomicBoolean();
        var deadline = Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(java.time.Duration.ofSeconds(seconds));
                expired.set(true);
                body.close();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The caller may have closed the response already.
            }
        });
        try (var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            int size = 0;
            var frame = new StringBuilder();
            for (String line; (line = reader.readLine()) != null; ) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                size += line.length() + 1;
                if (size > 2_000_000) throw new IOException("模型流式响应过大");
                if (line.startsWith("data:")) frame.append(line.substring(5).stripLeading()).append('\n');
                if (line.isEmpty() && !frame.isEmpty()) {
                    if (dispatch(json, frame, event)) return true;
                    frame.setLength(0);
                }
            }
            if (expired.get()) throw new HttpTimeoutException("模型流式响应超时");
            return !frame.isEmpty() && dispatch(json, frame, event);
        } catch (IOException failure) {
            if (expired.get()) throw new HttpTimeoutException("模型流式响应超时");
            throw failure;
        } finally {
            deadline.interrupt();
        }
    }

    private static boolean dispatch(ObjectMapper json, StringBuilder frame, Event event) throws Exception {
        String data = frame.toString().strip();
        if (data.isEmpty()) return false;
        if ("[DONE]".equals(data)) return true;
        var root = json.readTree(data);
        if (root == null || root.hasNonNull("error") || "error".equals(root.path("type").asText()))
            throw new IOException("模型流式响应包含错误");
        return event.accept(root);
    }

    static final class Publisher {
        private final Consumer<String> publish;
        private final StringBuilder pending = new StringBuilder();
        private long last;

        Publisher(Consumer<String> publish) { this.publish = publish; }

        void append(String text) {
            pending.append(text);
            if (pending.length() >= 512 || System.nanoTime() - last >= 100_000_000L) flush();
        }

        void flush() {
            if (pending.isEmpty()) return;
            publish.accept(pending.toString());
            pending.setLength(0);
            last = System.nanoTime();
        }
    }
}
