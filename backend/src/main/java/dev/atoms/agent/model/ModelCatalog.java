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

    @Value("${atoms.research.models.planning:}")
    private String researchPlanning = "";
    @Value("${atoms.research.models.refinement:}")
    private String researchRefinement = "";
    @Value("${atoms.research.models.report:}")
    private String researchReport = "";

    public Map<String, String> researchModels() {
        var stages = new LinkedHashMap<String, String>();
        Map.of("planning", researchPlanning, "refinement", researchRefinement, "report", researchReport)
                .forEach((stage, model) -> {
                    if (!model.isBlank()) stages.put(stage, model.strip());
                });
        return Map.copyOf(stages);
    }

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
        if (!"research".equals(value) && !models.containsKey(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的模式");
        return value;
    }

    public String select(String mode, String requested) {
        if ("research".equals(mode)) mode = "chat";
        String value = requested == null || requested.isBlank() ? defaults.get(mode) : requested;
        if (!models.get(mode).contains(value))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该模式未配置此模型，请在 application.yml 中添加");
        return value;
    }

    @jakarta.annotation.PostConstruct
    void validateRouting() {
        researchModels().forEach((stage, model) -> {
            if (!models.get("chat").contains(model))
                throw new IllegalArgumentException("研究阶段 " + stage + " 的模型不在 chat-models 白名单：" + model);
        });
        for (String mode : List.of("build", "chat")) {
            for (String model : List.of(fast(mode), "build".equals(mode) ? buildFallback : chatFallback)) {
                if (!model.isBlank() && !models.get(mode).contains(model))
                    throw new IllegalArgumentException("路由模型不在 " + mode + " 白名单：" + model);
            }
        }
    }

    private String fast(String mode) {
        String configured = switch (mode) {
            case "build" -> buildFast;
            case "chat", "research" -> chatFast;
            default -> "";
        };
        if (!configured.isBlank()) return configured;
        String catalogMode = "research".equals(mode) ? "chat" : mode;
        String builtIn = "build".equals(mode) ? "qwen3-coder-flash" : "qwen-flash";
        return models.getOrDefault(catalogMode, List.of()).contains(builtIn) ? builtIn : "";
    }

    public Map<String, String> stageModels(String mode, String requested) {
        if (!"auto".equals(requested)) return Map.of();
        if ("research".equals(mode)) {
            String planning = fast(mode);
            var routes = new LinkedHashMap<>(Map.of(
                    "planning", planning.isBlank() ? defaults.get("chat") : planning,
                    "refinement", planning.isBlank() ? defaults.get("chat") : planning,
                    "report", defaults.get("chat")));
            routes.putAll(researchModels());
            return Map.copyOf(routes);
        }
        if (!"build".equals(mode)) return Map.of();
        String planning = fast(mode);
        return Map.of("planning", planning.isBlank() ? defaults.get(mode) : planning,
                "review", defaults.get(mode));
    }

    public String route(String mode, String requested, String prompt, String baseVersion, String pattern) {
        if (!"auto".equals(requested) || "image".equals(mode)) return select(mode, requested);
        if ("research".equals(mode)) return stageModels(mode, requested).get("report");
        if (fast(mode).isBlank()) return select(mode, null);
        String catalogMode = "research".equals(mode) ? "chat" : mode;
        boolean simple = prompt.codePointCount(0, prompt.length()) <= 300
                && ("chat".equals(catalogMode) || (baseVersion == null && !"dag".equals(pattern)));
        return simple ? fast(mode) : defaults.get(catalogMode);
    }

    String fallback(String mode, String primary) {
        String configured = switch (mode) {
            case "build" -> buildFallback;
            case "chat", "research", "openai-chat", "openai-complex", "openai-completion" -> chatFallback;
            default -> "";
        };
        return configured.isBlank() || configured.equals(primary) ? null : configured;
    }

    public String completionModel(String tier, String prompt) {
        return route("chat", "small".equals(tier) && !chatFast.isBlank() ? "auto" : null, prompt, null, "react");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private TextProviders textProviders;

    public List<Map<String, Object>> providerModels(String tier) {
        var providers = new HashMap<String, String>();
        textProviders.getProviders().forEach((provider, config) ->
                config.models().keySet().forEach(alias -> providers.put(alias, provider)));
        var entries = new ArrayList<Map<String, Object>>();
        int priority = 0;
        for (String id : chatModels()) {
            priority++;
            String size = id.equals(chatFast) || id.equals(buildFast) ? "small" : "medium";
            if (tier.isEmpty() || tier.equals(size)) {
                entries.add(Map.of("id", id, "provider", providers.getOrDefault(id, "openai-compatible"),
                        "tier", size, "priority", priority));
            }
        }
        return entries;
    }

    public List<String> chatModels() {
        return models.get("chat");
    }

    public Map<String, Object> view() {
        var options = new LinkedHashMap<>(models);
        for (String mode : List.of("build", "chat")) {
            var choices = new ArrayList<>(models.get(mode));
            choices.add("auto");
            options.put(mode, List.copyOf(choices));
        }
        options.put("research", options.get("chat"));
        var viewDefaults = new LinkedHashMap<>(defaults);
        viewDefaults.put("research", defaults.get("chat"));
        return Map.of("models", options, "defaults", viewDefaults);
    }
}
