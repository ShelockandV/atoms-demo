package dev.atoms.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts text context and function calls for the Gemini Developer API. */
final class GeminiContent {
    private GeminiContent() {}

    static Map<String, Object> request(ObjectMapper json, List<Map<String, String>> messages,
                                       Map<String, Object> options, int outputTokens) throws com.fasterxml.jackson.core.JsonProcessingException {
        var body = new LinkedHashMap<String, Object>();
        var instructions = new ArrayList<Map<String, String>>();
        var contents = json.createArrayNode();
        var pending = new LinkedHashMap<String, JsonNode>();
        var responses = new LinkedHashMap<String, String>();
        for (var message : messages) {
            String role = message.get("role");
            String content = message.get("content");
            if ("system".equals(role)) {
                instructions.add(Map.of("text", content));
                continue;
            }
            if ("tool".equals(role)) {
                responses.put(message.get("tool_call_id"), content);
                if (responses.size() == pending.size()) {
                    var parts = contents.addObject().put("role", "user").putArray("parts");
                    for (var call : pending.entrySet()) {
                        var response = parts.addObject().putObject("functionResponse");
                        response.put("name", call.getValue().path("function").path("name").asText());
                        if (call.getValue().path("provider_call_id").isTextual())
                            response.put("id", call.getValue().path("provider_call_id").asText());
                        response.putObject("response").put("output", responses.get(call.getKey()));
                    }
                    pending.clear();
                    responses.clear();
                }
                continue;
            }
            var parts = contents.addObject().put("role", "assistant".equals(role) ? "model" : "user").putArray("parts");
            var replay = ProviderContent.replay(json, message, "gemini");
            if (replay != null) replay.forEach(parts::add);
            else if (!content.isEmpty() || !message.containsKey("tool_calls")) parts.addObject().put("text", content);
            if (message.containsKey("tool_calls")) {
                for (var call : json.readTree(message.get("tool_calls"))) {
                    pending.put(call.path("id").asText(), call);
                    if (replay != null) continue;
                    var part = parts.addObject();
                    var function = part.putObject("functionCall").put("name", call.path("function").path("name").asText());
                    function.set("args", TextModelTransport.functionArguments(json, call));
                    if (call.path("provider_call_id").isTextual()) function.put("id", call.path("provider_call_id").asText());
                    if (call.path("thought_signature").isTextual()) part.put("thoughtSignature", call.path("thought_signature").asText());
                }
            }
        }
        if (!instructions.isEmpty()) body.put("systemInstruction", Map.of("parts", instructions));
        body.put("contents", contents);
        var generation = new LinkedHashMap<String, Object>();
        generation.put("maxOutputTokens", outputTokens);
        if (options.containsKey("temperature")) generation.put("temperature", options.get("temperature"));
        body.put("generationConfig", generation);
        if (options.containsKey("tools")) {
            JsonNode definitions = json.valueToTree(options.get("tools"));
            var declarations = json.createArrayNode();
            for (var definition : definitions) {
                var function = definition.path("function");
                declarations.addObject().put("name", function.path("name").asText())
                        .put("description", function.path("description").asText())
                        .set("parametersJsonSchema", function.path("parameters"));
            }
            body.put("tools", List.of(Map.of("functionDeclarations", declarations)));
            if ("required".equals(options.get("tool_choice")))
                body.put("toolConfig", Map.of("functionCallingConfig", Map.of("mode", "ANY")));
            else if ("none".equals(options.get("tool_choice")))
                body.put("toolConfig", Map.of("functionCallingConfig", Map.of("mode", "NONE")));
        }
        return body;
    }

    static JsonNode message(ObjectMapper json, JsonNode candidate) {
        var message = json.createObjectNode();
        var calls = json.createArrayNode();
        var text = new StringBuilder();
        boolean reasoning = false;
        for (var part : candidate.path("content").path("parts")) {
            if (part.path("thought").asBoolean()) {
                reasoning = true;
                continue;
            }
            if (part.path("thoughtSignature").isTextual()) reasoning = true;
            if (part.path("text").isTextual()) text.append(part.path("text").asText());
            if (part.path("functionCall").isObject()) {
                var call = part.path("functionCall");
                var normalized = calls.addObject().put("type", "function")
                        .put("id", call.path("id").asText("gemini-" + java.util.UUID.randomUUID()));
                if (call.path("id").isTextual()) normalized.put("provider_call_id", call.path("id").asText());
                if (part.path("thoughtSignature").isTextual()) normalized.put("thought_signature", part.path("thoughtSignature").asText());
                normalized.putObject("function")
                        .put("name", call.path("name").asText())
                        .put("arguments", call.has("args") ? call.path("args").toString() : "{}");
            }
        }
        message.put("content", text.toString());
        if (!calls.isEmpty()) message.set("tool_calls", calls);
        if (reasoning) ProviderContent.retain(json, message, "gemini", candidate.path("content").path("parts"));
        return message;
    }
}
