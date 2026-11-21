package dev.atoms.agent.model;

import dev.atoms.agent.network.PublicHttps;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.List;

/**
 * 调用图像模型并返回生成图片及其媒体类型。
 *
 * <p>示例：<code>images.generate("极简蓝色图标", modelId)</code>。</p>
 */
@Component
public class ImageClient {
    private final String textBase, textKey;
    final ObjectMapper json;
    final String configuredEndpoint, key, size;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /**
     * 承载生成图片的字节和媒体类型。
     *
     * <p>示例：<code>new Image(pngBytes, "image/png")</code>。</p>
     */
    public record Image(byte[] bytes, String mediaType) {
    }

    public ImageClient(ObjectMapper json,
                @Value("${atoms.model.base-url}") String textBase,
                @Value("${atoms.model.api-key}") String textKey,
                @Value("${atoms.model.image.endpoint:}") String endpoint, @Value("${atoms.model.image.api-key:}") String key,
                @Value("${atoms.model.image.size:1024*1024}") String size) {
        this.textBase = textBase;
        this.textKey = textKey;
        this.json = json;
        this.configuredEndpoint = endpoint;
        this.key = key;
        this.size = size;
    }

    @Value("${atoms.model.image.download-fallback-dns:}")
    private String downloadFallbackDns = "";

    URI endpoint() {
        if (!configuredEndpoint.isBlank()) {
            ModelHttp.endpoint(configuredEndpoint);
            URI uri = URI.create(configuredEndpoint.trim());
            if (uri.getPath().contains("/compatible-mode") || uri.getPath().endsWith("/chat/completions")) {
                throw new IllegalArgumentException("图像接口配置错误：atoms.model.image.endpoint 不能填写文本接口地址，请填写完整的 /api/v1/services/aigc/multimodal-generation/generation 地址，或留空自动推导");
            }
            return uri;
        }
        URI base = ModelHttp.endpoint(textBase);
        String host = base.getHost();
        if (!host.endsWith(".aliyuncs.com"))
            throw new IllegalArgumentException("请配置 atoms.model.image.endpoint 为阿里云原生图像生成完整接口地址");
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + "/api/v1/services/aigc/multimodal-generation/generation");
    }

    private static final java.util.regex.Pattern IMAGE_REFERENCE = java.util.regex.Pattern.compile(
            "上一张|上张|前一张|刚才.{0,8}(图|照片)|(?:这|那)(?:张|个)(?:图|照片)|原图|(?:在|给|把)(?:图片|图像|图|照片)(?:上|里|中)|(?:previous|last|this|that|the) (?:image|picture|photo)",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern IMAGE_CHANGE = java.util.regex.Pattern.compile(
            "加|改|换|删|移|裁|调整|编辑|保留|补|add|edit|change|remove|replace|crop|keep",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    public static boolean editsPreviousImage(String prompt) {
        return IMAGE_REFERENCE.matcher(prompt).find() && IMAGE_CHANGE.matcher(prompt).find();
    }

    public Image generate(String prompt, String selectedModel) throws Exception {
        return generate(prompt, selectedModel, null);
    }

    public Image generate(String prompt, String selectedModel, String traceId) throws Exception {
        return requestImage(prompt, selectedModel, traceId, null);
    }

    public Image edit(String prompt, String selectedModel, String traceId, Image source) throws Exception {
        return requestImage(prompt, selectedModel, traceId, source);
    }

    private Image requestImage(String prompt, String selectedModel, String traceId, Image source) throws Exception {
        String apiKey = key.isBlank() ? textKey : key;
        if (apiKey.isBlank()) throw new IllegalArgumentException("请配置图像模型 API Key 或文本模型 API Key");
        var content = new java.util.ArrayList<Map<String, String>>();
        if (source != null) {
            content.add(Map.of("image", "data:" + source.mediaType() + ";base64,"
                    + java.util.Base64.getEncoder().encodeToString(source.bytes())));
        }
        content.add(Map.of("text", prompt));
        var parameters = source == null ? Map.of("size", size, "n", 1, "prompt_extend", true)
                : Map.of("n", 1, "prompt_extend", false);
        var payload = Map.of("model", selectedModel, "input", Map.of("messages",
                List.of(Map.of("role", "user", "content", content))), "parameters", parameters);
        var req = HttpRequest.newBuilder(endpoint()).timeout(Duration.ofSeconds(240))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        ModelHttp.addTraceparent(req, traceId);
        var response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2)
            throw new IllegalStateException(ModelHttp.providerError(json, response.statusCode(), response.body()));
        if (response.body().length() > 1_000_000) throw new IllegalStateException("图像服务响应过大");
        var root = json.readTree(response.body());
        String url = "";
        for (var item : root.path("output").path("choices").path(0).path("message").path("content"))
            if (item.path("image").isTextual()) {
                url = item.get("image").asText();
                break;
            }
        if (url.isBlank()) throw new IllegalStateException("图像服务未返回图片，请检查图像模型和接口类型是否匹配");
        // Fetch only provider-owned OSS results. Never forward the API key to storage.
        var download = PublicHttps.getProviderImage(URI.create(url), 8_000_000, downloadFallbackDns);
        if (!Set.of("image/png", "image/jpeg", "image/webp").contains(download.mediaType()))
            throw new IllegalStateException("图片格式不受支持");
        return new Image(download.bytes(), download.mediaType());
    }
}
