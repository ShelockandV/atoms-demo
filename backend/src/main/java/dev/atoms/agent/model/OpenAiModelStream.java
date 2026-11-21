package dev.atoms.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Accumulates OpenAI-compatible SSE deltas into the message persisted by the task. */
final class OpenAiModelStream {
    private record ToolCall(StringBuilder id, StringBuilder name, StringBuilder arguments) {
        ToolCall() { this(new StringBuilder(), new StringBuilder(), new StringBuilder()); }
    }

    static ModelSseReader.Result read(ObjectMapper json, InputStream body, int timeoutSeconds, Consumer<String> publish) throws Exception {
        var text = new StringBuilder();
        var reasoning = new StringBuilder();
        var publisher = new ModelSseReader.Publisher(publish);
        var tools = new TreeMap<Integer, ToolCall>();
        var usage = json.createObjectNode();
        var message = json.createObjectNode();
        boolean done = ModelSseReader.read(json, body, timeoutSeconds, event -> {
            if (event.path("usage").isObject()) usage.set("usage", event.path("usage"));
            var choice = event.path("choices").path(0);
            if (choice.path("finish_reason").isTextual()) message.set("finish_reason", choice.path("finish_reason"));
            var delta = choice.path("delta");
            if (delta.path("reasoning_content").isTextual()) reasoning.append(delta.path("reasoning_content").asText());
            if (delta.path("content").isTextual()) {
                String fragment = delta.path("content").asText();
                text.append(fragment);
                publisher.append(fragment);
            }
            for (var call : delta.path("tool_calls")) {
                int index = call.path("index").asInt(-1);
                if (index < 0 || index >= 32) throw new IOException("模型返回了无效的函数调用序号");
                var tool = tools.computeIfAbsent(index, ignored -> new ToolCall());
                var function = call.path("function");
                tool.id().append(call.path("id").asText(""));
                tool.name().append(function.path("name").asText(""));
                tool.arguments().append(function.path("arguments").asText(""));
            }
            return false;
        });
        if (!done && !message.has("finish_reason")) throw new IOException("模型流式响应提前断开");
        publisher.flush();
        message.put("content", text.toString());
        if (!reasoning.isEmpty()) message.put("reasoning_content", reasoning.toString());
        if (!tools.isEmpty()) {
            var calls = message.putArray("tool_calls");
            for (var tool : tools.values()) {
                calls.addObject().put("id", tool.id().toString()).put("type", "function")
                        .putObject("function").put("name", tool.name().toString())
                        .put("arguments", tool.arguments().toString());
            }
        }
        return new ModelSseReader.Result(message, usage);
    }
}
