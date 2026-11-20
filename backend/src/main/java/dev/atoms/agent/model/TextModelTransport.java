package dev.atoms.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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

    /** 保留 HTTP 状态供路由判断。例如 503 可换用同一供应商的备用模型。 */
    public static final class ProviderFailure extends IllegalStateException {
        private final int status;
        ProviderFailure(int status, String message) { super(message); this.status = status; }
        public boolean retryable() { return Set.of(429, 502, 503, 504).contains(status); }
    }
    private final ObjectMapper json;
    private final String base, key;
    private final int timeoutSeconds, maxOutputTokens;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public TextModelTransport(ObjectMapper json, String base, String key, int timeoutSeconds, int maxOutputTokens) {
        this.json = json;
        this.base = base;
        this.key = key;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputTokens = maxOutputTokens;
    }

    public com.fasterxml.jackson.databind.JsonNode complete(
            List<Map<String, String>> messages, String selectedModel, Map<String, Object> options,
            int outputLimit, String traceId, LongConsumer reserve, Settlement recordUsage) throws Exception {
        if (key.isBlank())
            throw new IllegalArgumentException("请在后端私有配置中配置 atoms.model.api-key 和 atoms.model.name，或设置 atoms.demo=true");
        URI endpoint = ModelHttp.endpoint(base);
        var payload = new LinkedHashMap<String, Object>(options);
        payload.put("model", selectedModel);
        payload.put("messages", messages);
        int outputTokens = outputLimit == 0 ? maxOutputTokens : Math.min(maxOutputTokens, outputLimit);
        payload.put("max_tokens", outputTokens);
        String body = json.writeValueAsString(payload);
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        ModelHttp.addTraceparent(request, traceId);
        
        long estimated = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 256L + outputTokens;
        reserve.accept(estimated);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2)
            throw new ProviderFailure(response.statusCode(), ModelHttp.providerError(json, response.statusCode(), response.body()));
        if (response.body().length() > 2_000_000) throw new IllegalArgumentException("模型响应过大");
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = json.readTree(response.body());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("模型服务响应不是合法JSON，请检查接口是否返回网关错误页面");
        }
        if (root == null) throw new IllegalStateException("模型服务返回空响应");
        if (root.path("usage").path("prompt_tokens").canConvertToLong()
                && root.path("usage").path("completion_tokens").canConvertToLong()) {
            recordUsage.accept(estimated,
                    Math.max(0, root.path("usage").path("prompt_tokens").asLong()),
                    Math.max(0, root.path("usage").path("completion_tokens").asLong()));
        }
        var choice = root.path("choices").path(0);
        if ("length".equals(choice.path("finish_reason").asText()))
            throw new IllegalStateException("模型输出达到长度上限，源码可能不完整。请缩小本次生成范围，或调整 atoms.model.max-output-tokens 后重试");
        return choice.path("message");
    }
}
