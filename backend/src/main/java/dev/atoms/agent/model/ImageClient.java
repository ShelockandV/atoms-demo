package dev.atoms.agent.model;

import dev.atoms.agent.network.PublicHttps;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
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
    private final boolean demo;
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

    ImageClient(ObjectMapper json,
                @Value("${atoms.demo}") boolean demo, @Value("${atoms.model.base-url}") String textBase,
                @Value("${atoms.model.api-key}") String textKey,
                @Value("${atoms.model.image.endpoint:}") String endpoint, @Value("${atoms.model.image.api-key:}") String key,
                @Value("${atoms.model.image.size:1024*1024}") String size) {
        this.demo = demo;
        this.textBase = textBase;
        this.textKey = textKey;
        this.json = json;
        this.configuredEndpoint = endpoint;
        this.key = key;
        this.size = size;
    }

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

    public Image generate(String prompt, String selectedModel) throws Exception {
        return generate(prompt, selectedModel, null);
    }

    public Image generate(String prompt, String selectedModel, String traceId) throws Exception {
        if (demo) return demo();
        String apiKey = key.isBlank() ? textKey : key;
        if (apiKey.isBlank()) throw new IllegalArgumentException("请配置图像模型 API Key 或文本模型 API Key");
        var payload = Map.of("model", selectedModel, "input", Map.of("messages", List.of(Map.of("role", "user", "content", List.of(Map.of("text", prompt))))), "parameters", Map.of("size", size, "n", 1, "prompt_extend", true));
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
        var download = PublicHttps.get(URI.create(url), host -> host.endsWith(".aliyuncs.com"), 8_000_000);
        if (!Set.of("image/png", "image/jpeg", "image/webp").contains(download.mediaType()))
            throw new IllegalStateException("图片格式不受支持");
        return new Image(download.bytes(), download.mediaType());
    }

    Image demo() throws IOException {
        var image = new BufferedImage(768, 768, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            g.setPaint(new GradientPaint(0, 0, new Color(97, 86, 220), 768, 768, new Color(244, 178, 153)));
            g.fillRect(0, 0, 768, 768);
            g.setColor(new Color(255, 255, 255, 65));
            g.fillOval(190, 150, 460, 460);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 45));
            g.drawString("Atoms Image Demo", 125, 370);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
            g.drawString("Offline sample - not AI generated", 200, 420);
        } finally {
            g.dispose();
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new Image(out.toByteArray(), "image/png");
    }
}
