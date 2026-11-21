package dev.atoms.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.io.IOException;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.LongConsumer;

/**
 * 发送文本模型 HTTP 请求，解析响应并将供应商报告的 token 数交给计量回调。
 *
 * <p>示例：<code>transport.complete(messages, modelId, options, 0, traceId, reserve, recordUsage)</code>。</p>
 */
public final class TextModelTransport {
    /** 收到供应商用量时结算。例如用实际输入和输出替代先前的预留。 */
    @FunctionalInterface
    public interface Settlement { void accept(long reserved, long input, long output); }

    /** 保留 HTTP 状态供备用模型路由判断。 */
    public static final class ProviderFailure extends IllegalStateException {
        private final int status;
        ProviderFailure(int status, String message) { super(message); this.status = status; }
        public boolean retryable() { return Set.of(429, 502, 503, 504, 529).contains(status); }
    }
    enum Protocol { OPENAI, ANTHROPIC, GEMINI }

    private final ObjectMapper json;
    private final Protocol protocol;
    private final String base, key;
    private final int timeoutSeconds, maxOutputTokens;
    private final boolean promptCache, includeThinking;
    private final String cachedContent;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public TextModelTransport(ObjectMapper json, String base, String key, int timeoutSeconds, int maxOutputTokens) {
        this(json, base, key, timeoutSeconds, maxOutputTokens, Protocol.OPENAI);
    }

    TextModelTransport(ObjectMapper json, String base, String key, int timeoutSeconds, int maxOutputTokens, Protocol protocol) {
        this(json, base, key, timeoutSeconds, maxOutputTokens, protocol, false, null, false);
    }

    TextModelTransport(ObjectMapper json, String base, String key, int timeoutSeconds, int maxOutputTokens,
                       Protocol protocol, boolean promptCache, String cachedContent, boolean includeThinking) {
        if (cachedContent != null && !cachedContent.isBlank()
                && (protocol != Protocol.GEMINI || !cachedContent.matches("cachedContents/[A-Za-z0-9_-]{1,120}")))
            throw new IllegalArgumentException("Gemini 缓存资源名无效");
        this.promptCache = promptCache;
        this.includeThinking = includeThinking;
        this.cachedContent = cachedContent;
        this.json = json;
        this.protocol = protocol;
        this.base = base;
        this.key = key;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputTokens = maxOutputTokens;
    }

    URI endpoint(String upstreamModel) {
        return endpoint(upstreamModel, false);
    }

    private URI endpoint(String upstreamModel, boolean stream) {
        String suffix = switch (protocol) {
            case OPENAI -> "/chat/completions";
            case ANTHROPIC -> "/messages";
            case GEMINI -> {
                if (!upstreamModel.matches("(?:models/)?[a-zA-Z0-9][a-zA-Z0-9._-]{0,119}"))
                    throw new IllegalArgumentException("Gemini 上游模型 ID 无效");
                yield "/models/" + upstreamModel.replaceFirst("^models/", "") + (stream ? ":streamGenerateContent?alt=sse" : ":generateContent");
            }
        };
        return ModelHttp.endpoint(base, suffix);
    }

    public JsonNode complete(
            List<Map<String, String>> messages, String selectedModel, Map<String, Object> options,
            int outputLimit, String traceId, LongConsumer reserve, Settlement recordUsage) throws Exception {
        return complete(messages, selectedModel, options, outputLimit, traceId, reserve, recordUsage, null);
    }

    JsonNode complete(List<Map<String, String>> messages, String selectedModel, Map<String, Object> options,
                      int outputLimit, String traceId, LongConsumer reserve, Settlement recordUsage,
                      java.util.function.Consumer<String> publish) throws Exception {
        boolean stream = publish != null;
        boolean synthesis = !stream && Boolean.TRUE.equals(options.get("atoms_synthesis"));
        if (key.isBlank())
            throw new IllegalArgumentException("请在后端私有配置中配置 atoms.model.api-key 和 atoms.model.name");
        int outputTokens = outputLimit == 0 ? maxOutputTokens : Math.min(maxOutputTokens, outputLimit);
        Object imageInput = options.get("atoms_images");
        if (imageInput != null && protocol != Protocol.OPENAI)
            throw new IllegalArgumentException("Image attachments require an OpenAI-compatible vision model");
        boolean toolHistory = messages.stream().anyMatch(message ->
                message.containsKey("tool_calls") || message.containsKey("tool_call_id")
                        || message.containsKey("provider_content") || message.containsKey("reasoning_content"));
        var payload = switch (protocol) {
            case OPENAI -> new LinkedHashMap<String, Object>(options);
            case ANTHROPIC -> AnthropicMessages.request(json, messages, options);
            case GEMINI -> GeminiContent.request(json, messages, options, outputTokens);
        };
        if (protocol == Protocol.ANTHROPIC) {
            if (promptCache) payload.put("cache_control", Map.of("type", "ephemeral"));
            if (includeThinking) payload.put("thinking", Map.of("type", "adaptive", "display", "summarized"));
        }
        if (protocol == Protocol.GEMINI) {
            if (cachedContent != null && !cachedContent.isBlank()
                    && !payload.containsKey("systemInstruction") && !payload.containsKey("tools"))
                payload.put("cachedContent", cachedContent);
            if (includeThinking) {
                @SuppressWarnings("unchecked")
                var generation = (Map<String, Object>) payload.get("generationConfig");
                generation.put("thinkingConfig", Map.of("includeThoughts", true));
            }
        }
        if (protocol != Protocol.GEMINI) {
            payload.put("model", selectedModel);
            payload.put("max_tokens", outputTokens);
        }
        if (protocol == Protocol.OPENAI) {
            payload.remove("atoms_images");
            payload.remove("atoms_synthesis");
            if (imageInput != null || toolHistory) {
                var images = imageInput instanceof Map<?, ?> sources ? sources : Map.of();
                var inputs = json.createArrayNode();
                for (int index = 0; index < messages.size(); index++) {
                    var message = messages.get(index);
                    var entry = inputs.addObject().put("role", message.get("role"));
                    if (message.containsKey("reasoning_content")) entry.put("reasoning_content", message.get("reasoning_content"));
                    if (message.containsKey("tool_call_id")) entry.put("tool_call_id", message.get("tool_call_id"));
                    if (message.containsKey("tool_calls")) {
                        var calls = entry.putArray("tool_calls");
                        for (var call : json.readTree(message.get("tool_calls"))) {
                            var wire = calls.addObject().put("id", call.path("id").asText()).put("type", "function");
                            wire.set("function", call.path("function"));
                        }
                    }
                    if (images.get(index) instanceof List<?> urls) {
                        var content = entry.putArray("content");
                        content.addObject().put("type", "text").put("text", message.get("content"));
                        for (Object url : urls)
                            content.addObject().put("type", "image_url").putObject("image_url").put("url", (String) url);
                    } else if (message.containsKey("tool_calls") && message.get("content").isEmpty()) {
                        entry.putNull("content");
                    } else {
                        entry.put("content", message.get("content"));
                    }
                }
                payload.put("messages", inputs);
            } else {
                payload.put("messages", messages);
            }
        }
        if (stream && protocol != Protocol.GEMINI) {
            payload.put("stream", true);
            if (protocol == Protocol.OPENAI) payload.put("stream_options", Map.of("include_usage", true));
        }
        String body = json.writeValueAsString(payload);
        var request = HttpRequest.newBuilder(endpoint(selectedModel, stream)).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        switch (protocol) {
            case OPENAI -> request.header("Authorization", "Bearer " + key);
            case ANTHROPIC -> request.header("x-api-key", key).header("anthropic-version", "2023-06-01");
            case GEMINI -> request.header("x-goog-api-key", key);
        }
        ModelHttp.addTraceparent(request, traceId);

        long estimated = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 256L + outputTokens;
        reserve.accept(estimated);
        if (stream) {
            var response = http.send(request.header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                if (response.statusCode() / 100 != 2)
                    throw new ProviderFailure(response.statusCode(), "上游模型返回 HTTP " + response.statusCode());
                if (response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
                    var result = switch (protocol) {
                        case OPENAI -> OpenAiModelStream.read(json, input, timeoutSeconds, publish);
                        case ANTHROPIC -> NativeModelStream.claude(json, input, timeoutSeconds, publish);
                        case GEMINI -> NativeModelStream.gemini(json, input, timeoutSeconds, publish);
                    };
                    settleUsage(result.usage(), estimated, recordUsage);
                    return result.message();
                }
                return decode(new String(input.readNBytes(2_000_001), java.nio.charset.StandardCharsets.UTF_8),
                        estimated, recordUsage, 0);
            }
        }
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2)
            throw new ProviderFailure(response.statusCode(), ModelHttp.providerError(json, response.statusCode(), response.body()));
        return decode(response.body(), estimated, recordUsage, synthesis ? outputTokens : 0);
    }

    private JsonNode decode(String body, long estimated, Settlement recordUsage, int synthesisLimit) {
        if (body.length() > 2_000_000) throw new IllegalArgumentException("模型响应过大");
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("模型服务响应不是合法JSON，请检查接口是否返回网关错误页面");
        }
        if (root == null) throw new IllegalStateException("模型服务返回空响应");
        settleUsage(root, estimated, recordUsage);
        var choice = protocol == Protocol.GEMINI ? root.path("candidates").path(0) : root.path("choices").path(0);
        boolean truncated = switch (protocol) {
            case OPENAI -> "length".equals(choice.path("finish_reason").asText());
            case ANTHROPIC -> Set.of("max_tokens", "model_context_window_exceeded").contains(root.path("stop_reason").asText());
            case GEMINI -> "MAX_TOKENS".equals(choice.path("finishReason").asText());
        };
        if (truncated && synthesisLimit == 0)
            throw new IllegalStateException("模型输出达到长度上限，源码可能不完整。请缩小本次生成范围，或调整 atoms.model.max-output-tokens 后重试");
        var message = switch (protocol) {
            case OPENAI -> choice.path("message");
            case ANTHROPIC -> AnthropicMessages.message(json, root);
            case GEMINI -> GeminiContent.message(json, choice);
        };
        if (synthesisLimit > 0 && message instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            var usage = root.path(protocol == Protocol.GEMINI ? "usageMetadata" : "usage");
            object.put("atoms_truncated", truncated);
            object.put("atoms_output_limit", synthesisLimit);
            object.put("atoms_output_tokens", usage.path(switch (protocol) {
                case OPENAI -> "completion_tokens";
                case ANTHROPIC -> "output_tokens";
                case GEMINI -> "candidatesTokenCount";
            }).asInt(-1));
        }
        return message;
    }

    static JsonNode functionArguments(ObjectMapper json, JsonNode call) throws com.fasterxml.jackson.core.JsonProcessingException {
        var args = json.readTree(call.path("function").path("arguments").asText());
        if (args == null || !args.isObject()) throw new IllegalArgumentException("Historical function arguments must be a JSON object");
        return args;
    }

    private void settleUsage(JsonNode root, long estimate, Settlement recordUsage) {
        var usage = root.path(protocol == Protocol.GEMINI ? "usageMetadata" : "usage");
        String inputField = switch (protocol) {
            case OPENAI -> "prompt_tokens";
            case ANTHROPIC -> "input_tokens";
            case GEMINI -> "promptTokenCount";
        };
        String outputField = switch (protocol) {
            case OPENAI -> "completion_tokens";
            case ANTHROPIC -> "output_tokens";
            case GEMINI -> "candidatesTokenCount";
        };
        var input = usage.path(inputField);
        var output = usage.path(outputField);
        if (!input.canConvertToLong() || !output.canConvertToLong()) return;
        long inputTokens = Math.max(0, input.asLong());
        long outputTokens = Math.max(0, output.asLong());
        if (protocol == Protocol.ANTHROPIC) {
            inputTokens = Math.addExact(inputTokens, nonnegative(usage, "cache_read_input_tokens"));
            inputTokens = Math.addExact(inputTokens, nonnegative(usage, "cache_creation_input_tokens"));
        } else if (protocol == Protocol.GEMINI) {
            inputTokens = Math.addExact(inputTokens, nonnegative(usage, "toolUsePromptTokenCount"));
            outputTokens = Math.addExact(outputTokens, nonnegative(usage, "thoughtsTokenCount"));
        }
        recordUsage.accept(estimate, inputTokens, outputTokens);
    }

    private static long nonnegative(JsonNode usage, String field) {
        return Math.max(0, usage.path(field).asLong());
    }
}
