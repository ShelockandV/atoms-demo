package dev.atoms.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Converts the internal text and function-call representation to and from Claude Messages. */
final class AnthropicMessages {
    private AnthropicMessages() {}

    static Map<String, Object> request(ObjectMapper json, List<Map<String, String>> messages,
                                       Map<String, Object> options) throws com.fasterxml.jackson.core.JsonProcessingException {
        var body = new LinkedHashMap<String, Object>();
        String system = messages.stream().filter(message -> "system".equals(message.get("role")))
                .map(message -> message.get("content")).collect(Collectors.joining("\n\n"));
        if (!system.isEmpty()) body.put("system", system);
        var turns = json.createArrayNode();
        com.fasterxml.jackson.databind.node.ArrayNode toolResults = null;
        for (var message : messages) {
            String role = message.get("role");
            if ("system".equals(role)) continue;
            if ("tool".equals(role)) {
                if (toolResults == null) toolResults = turns.addObject().put("role", "user").putArray("content");
                toolResults.addObject().put("type", "tool_result").put("tool_use_id", message.get("tool_call_id"))
                        .put("content", message.get("content"));
                continue;
            }
            toolResults = null;
            var turn = turns.addObject().put("role", role);
            var replay = ProviderContent.replay(json, message, "anthropic");
            if (replay != null) {
                turn.set("content", replay);
            } else if (message.containsKey("tool_calls")) {
                var parts = turn.putArray("content");
                if (!message.get("content").isEmpty()) parts.addObject().put("type", "text").put("text", message.get("content"));
                for (var call : json.readTree(message.get("tool_calls"))) {
                    var part = parts.addObject().put("type", "tool_use").put("id", call.path("id").asText())
                            .put("name", call.path("function").path("name").asText());
                    part.set("input", TextModelTransport.functionArguments(json, call));
                }
            } else {
                turn.put("content", message.get("content"));
            }
        }
        body.put("messages", turns);
        if (options.containsKey("temperature")) body.put("temperature", options.get("temperature"));
        if (options.containsKey("tools")) {
            JsonNode definitions = json.valueToTree(options.get("tools"));
            var tools = json.createArrayNode();
            for (var definition : definitions) {
                var function = definition.path("function");
                tools.addObject().put("name", function.path("name").asText())
                        .put("description", function.path("description").asText())
                        .set("input_schema", function.path("parameters"));
            }
            body.put("tools", tools);
            if ("required".equals(options.get("tool_choice")))
                body.put("tool_choice", Map.of("type", "any", "disable_parallel_tool_use", true));
            else if ("none".equals(options.get("tool_choice")))
                body.put("tool_choice", Map.of("type", "none"));
        }
        return body;
    }

    static JsonNode message(ObjectMapper json, JsonNode root) {
        var message = json.createObjectNode();
        var calls = json.createArrayNode();
        var content = new StringBuilder();
        boolean reasoning = false;
        for (var block : root.path("content")) {
            switch (block.path("type").asText()) {
                case "thinking", "redacted_thinking" -> reasoning = true;
                case "text" -> content.append(block.path("text").asText());
                case "tool_use" -> calls.addObject().put("type", "function").put("id", block.path("id").asText())
                        .putObject("function").put("name", block.path("name").asText())
                        .put("arguments", block.path("input").toString());
                default -> { }
            }
        }
        message.put("content", content.toString());
        if (!calls.isEmpty()) message.set("tool_calls", calls);
        if (reasoning) ProviderContent.retain(json, message, "anthropic", root.path("content"));
        return message;
    }
}
