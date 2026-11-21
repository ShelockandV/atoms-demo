package dev.atoms.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps stable Atoms model aliases to provider-specific model IDs and independent credentials. */
@Component
@ConfigurationProperties("atoms.model")
public class TextProviders {
    private Map<String, Provider> providers = Map.of();

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers;
    }

    public record Provider(String protocol, String baseUrl, String apiKey, Map<String, String> models,
                           boolean promptCache, String cachedContent, boolean includeThinking) {}

    record Route(TextModelTransport transport, String upstreamModel) {}

    Map<String, Route> routes(ObjectMapper json, int timeoutSeconds, int maxOutputTokens) {
        var routes = new LinkedHashMap<String, Route>();
        providers.forEach((id, provider) -> {
            var protocol = switch (provider.protocol() == null ? "openai-compatible" : provider.protocol()) {
                case "openai-compatible" -> TextModelTransport.Protocol.OPENAI;
                case "anthropic" -> TextModelTransport.Protocol.ANTHROPIC;
                case "gemini" -> TextModelTransport.Protocol.GEMINI;
                default -> throw new IllegalArgumentException("不支持的文本供应商协议：" + id);
            };
            if (provider.apiKey() == null || provider.apiKey().isBlank()
                    || provider.models() == null || provider.models().isEmpty())
                throw new IllegalArgumentException("文本供应商须配置 api-key 和 models：" + id);
            var transport = new TextModelTransport(json, provider.baseUrl(), provider.apiKey(),
                    timeoutSeconds, maxOutputTokens, protocol, provider.promptCache(), provider.cachedContent(), provider.includeThinking());
            provider.models().forEach((alias, upstream) -> {
                if (alias.isBlank() || alias.length() > 100 || "auto".equals(alias) || upstream.isBlank())
                    throw new IllegalArgumentException("文本供应商模型别名或上游模型 ID 无效：" + id);
                transport.endpoint(upstream);
                if (routes.putIfAbsent(alias, new Route(transport, upstream)) != null)
                    throw new IllegalArgumentException("文本供应商重复配置模型别名：" + alias);
            });
        });
        return Map.copyOf(routes);
    }
}
