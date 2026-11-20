package dev.atoms.agent.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.*;

/**
 * 按构建、问答和图像模式管理可选模型及默认项。
 *
 * <p>示例：catalog.select("build", null) 取得默认构建模型。</p>
 */
@Component
public class ModelCatalog {
    private final Map<String, List<String>> models;
    private final Map<String, String> defaults;
    @Value("${atoms.agent.routing.build-fast-model:}")
    private String buildFast = "";
    @Value("${atoms.agent.routing.chat-fast-model:}")
    private String chatFast = "";
    @Value("${atoms.agent.routing.build-fallback-model:}")
    private String buildFallback = "";
    @Value("${atoms.agent.routing.chat-fallback-model:}")
    private String chatFallback = "";

    ModelCatalog(@Value("${atoms.model.name}") String build,
                 @Value("${atoms.model.build-models:qwen-plus,qwen3-coder-plus,qwen3-coder-flash}") String builds,
                 @Value("${atoms.model.chat-default:qwen-plus}") String chat,
                 @Value("${atoms.model.chat-models:qwen-plus,qwen-flash}") String chats,
                 @Value("${atoms.model.image.default-model:qwen-image-2.0}") String image,
                 @Value("${atoms.model.image.models:qwen-image-2.0,qwen-image-2.0-pro}") String images) {
        defaults = Map.of("build", build, "chat", chat, "image", image);
        models = Map.of("build", list(builds, build), "chat", list(chats, chat), "image", list(images, image));
    }

    private List<String> list(String raw, String fallback) {
        var result = new LinkedHashSet<String>();
        result.add(fallback);
        for (String s : raw.split(",")) if (!s.isBlank()) result.add(s.trim());
        return List.copyOf(result);
    }

    public String mode(String mode) {
        String value = mode == null ? "build" : mode;
        if (!models.containsKey(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的模式");
        return value;
    }

    public String select(String mode, String requested) {
        String value = requested == null || requested.isBlank() ? defaults.get(mode) : requested;
        if (!models.get(mode).contains(value))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该模式未配置此模型，请在 application.yml 中添加");
        return value;
    }

    @jakarta.annotation.PostConstruct
    void validateRouting() {
        for (String mode : List.of("build", "chat")) {
            for (String model : List.of(fast(mode), "build".equals(mode) ? buildFallback : chatFallback)) {
                if (!model.isBlank() && !models.get(mode).contains(model))
                    throw new IllegalArgumentException("路由模型不在 " + mode + " 白名单：" + model);
            }
        }
    }

    private String fast(String mode) {
        return switch (mode) {
            case "build" -> buildFast;
            case "chat" -> chatFast;
            default -> "";
        };
    }

    public String route(String mode, String requested, String prompt, String baseVersion, String pattern) {
        if (!"auto".equals(requested) || fast(mode).isBlank()) return select(mode, requested);
        boolean simple = prompt.codePointCount(0, prompt.length()) <= 300
                && ("chat".equals(mode) || (baseVersion == null && !"dag".equals(pattern)));
        return simple ? fast(mode) : defaults.get(mode);
    }

    String fallback(String mode, String primary) {
        String configured = switch (mode) {
            case "build" -> buildFallback;
            case "chat" -> chatFallback;
            default -> "";
        };
        return configured.isBlank() || configured.equals(primary) ? null : configured;
    }

    public Map<String, Object> view() {
        var options = new LinkedHashMap<>(models);
        for (String mode : List.of("build", "chat")) {
            if (!fast(mode).isBlank()) {
                var choices = new ArrayList<>(models.get(mode));
                choices.add("auto");
                options.put(mode, List.copyOf(choices));
            }
        }
        return Map.of("models", options, "defaults", defaults);
    }
}
