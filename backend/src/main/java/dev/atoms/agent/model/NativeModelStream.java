package dev.atoms.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

final class NativeModelStream {
    private record ClaudeBlock(ObjectNode part, StringBuilder text, StringBuilder arguments, StringBuilder signature) {
        ClaudeBlock(ObjectNode part) {
            this(part, new StringBuilder(part.path("thinking").asText()), new StringBuilder(),
                    new StringBuilder(part.path("signature").asText()));
        }
    }

    static ModelSseReader.Result claude(ObjectMapper json, InputStream body, int seconds,
                                           Consumer<String> publish) throws Exception {
        var publisher = new ModelSseReader.Publisher(publish);
        var blocks = new TreeMap<Integer, ClaudeBlock>();
        var usage = json.createObjectNode();
        var finish = new StringBuilder();
        boolean complete = ModelSseReader.read(json, body, seconds, event -> {
            int index = event.path("index").asInt(-1);
            switch (event.path("type").asText()) {
                case "message_start" -> copyFields(usage, event.path("message").path("usage"));
                case "content_block_start" -> {
                    if (index < 0 || index >= 256 || !event.path("content_block").isObject())
                        throw new IOException("Anthropic 返回了无效的流式内容块");
                    var part = (ObjectNode) event.path("content_block").deepCopy();
                    var block = new ClaudeBlock(part);
                    if ("text".equals(part.path("type").asText())) {
                        String text = part.path("text").asText();
                        block.text().append(text);
                        publisher.append(text);
                    }
                    blocks.put(index, block);
                }
                case "content_block_delta" -> {
                    var block = blocks.get(index);
                    if (block == null) throw new IOException("Anthropic 流式内容块未初始化");
                    var delta = event.path("delta");
                    if ("text_delta".equals(delta.path("type").asText()) && "text".equals(block.part().path("type").asText())) {
                        var text = delta.path("text").asText();
                        block.text().append(text);
                        publisher.append(text);
                    }
                    if ("thinking".equals(block.part().path("type").asText())) {
                        if ("thinking_delta".equals(delta.path("type").asText()))
                            block.text().append(delta.path("thinking").asText());
                        if ("signature_delta".equals(delta.path("type").asText()))
                            block.signature().append(delta.path("signature").asText());
                    }
                    if ("input_json_delta".equals(delta.path("type").asText()))
                        block.arguments().append(delta.path("partial_json").asText());
                }
                case "message_delta" -> {
                    copyFields(usage, event.path("usage"));
                    if (event.path("delta").path("stop_reason").isTextual()) {
                        finish.setLength(0);
                        finish.append(event.path("delta").path("stop_reason").asText());
                    }
                }
                case "message_stop" -> { return true; }
                default -> { }
            }
            return false;
        });
        if (!complete) throw new IOException("Anthropic 模型流式响应提前断开");
        var root = json.createObjectNode();
        var parts = root.putArray("content");
        for (var block : blocks.values()) {
            switch (block.part().path("type").asText()) {
                case "text" -> parts.add(block.part().put("text", block.text().toString()));
                case "thinking" -> parts.add(block.part().put("thinking", block.text().toString())
                        .put("signature", block.signature().toString()));
                case "redacted_thinking" -> parts.add(block.part());
                case "tool_use" -> {
                    if (!block.arguments().isEmpty()) block.part().set("input", json.readTree(block.arguments().toString()));
                    parts.add(block.part());
                }
                default -> { }
            }
        }
        root.set("usage", usage);
        var message = (ObjectNode) AnthropicMessages.message(json, root);
        message.put("finish_reason", Map.of("tool_use", "tool_calls", "max_tokens", "length",
                "end_turn", "stop", "stop_sequence", "stop").getOrDefault(finish.toString(), "stop"));
        publisher.flush();
        return new ModelSseReader.Result(message, root);
    }

    static ModelSseReader.Result gemini(ObjectMapper json, InputStream body, int seconds,
                                           Consumer<String> publish) throws Exception {
        var publisher = new ModelSseReader.Publisher(publish);
        var candidate = json.createObjectNode();
        var parts = candidate.putObject("content").putArray("parts");
        var root = json.createObjectNode();
        ModelSseReader.read(json, body, seconds, event -> {
            if (event.path("usageMetadata").isObject()) root.set("usageMetadata", event.path("usageMetadata"));
            var chunk = event.path("candidates").path(0);
            if (chunk.path("finishReason").isTextual()) candidate.set("finishReason", chunk.path("finishReason"));
            for (var part : chunk.path("content").path("parts")) {
                parts.add(part);
                if (!part.path("thought").asBoolean() && part.path("text").isTextual()) publisher.append(part.path("text").asText());
            }
            return false;
        });
        if (!candidate.has("finishReason")) throw new IOException("Gemini 模型流式响应提前断开");
        var message = (ObjectNode) GeminiContent.message(json, candidate);
        String finish = candidate.path("finishReason").asText();
        message.put("finish_reason", switch (finish) {
            case "STOP" -> message.has("tool_calls") ? "tool_calls" : "stop";
            case "MAX_TOKENS" -> "length";
            default -> finish.toLowerCase(java.util.Locale.ROOT);
        });
        publisher.flush();
        return new ModelSseReader.Result(message, root);
    }

    private static void copyFields(ObjectNode target, JsonNode source) {
        source.fields().forEachRemaining(field -> target.set(field.getKey(), field.getValue()));
    }
}
