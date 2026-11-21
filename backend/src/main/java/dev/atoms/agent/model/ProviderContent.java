package dev.atoms.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;

final class ProviderContent {
    private ProviderContent() {}

    static JsonNode replay(ObjectMapper json, Map<String, String> message, String provider)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        if (!"assistant".equals(message.get("role")) || !message.containsKey("provider_content")) return null;
        var value = json.readTree(message.get("provider_content"));
        if (!provider.equals(value.path("provider").asText())) return null;
        var blocks = value.path("blocks");
        if (!blocks.isArray() || blocks.isEmpty() || blocks.size() > 128)
            throw new IllegalArgumentException("供应商内容块无效");
        for (var block : blocks) {
            boolean valid = switch (provider) {
                case "anthropic" -> Set.of("thinking", "redacted_thinking", "text", "tool_use")
                        .contains(block.path("type").asText());
                case "gemini" -> block.isObject() && (block.path("text").isTextual()
                        || block.path("functionCall").isObject() || block.path("thoughtSignature").isTextual())
                        && !block.has("functionResponse") && !block.has("inlineData") && !block.has("fileData");
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("供应商内容块类型不受支持");
        }
        return blocks;
    }

    static void retain(ObjectMapper json, com.fasterxml.jackson.databind.node.ObjectNode message,
                       String provider, JsonNode blocks) {
        if (!blocks.isArray() || blocks.isEmpty()) return;
        var content = json.createObjectNode().put("provider", provider);
        content.set("blocks", blocks);
        message.set("provider_content", content);
    }
}
