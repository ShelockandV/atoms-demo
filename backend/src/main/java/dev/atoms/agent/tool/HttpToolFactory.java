package dev.atoms.agent.tool;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.agent.network.PublicHttps;
import org.apache.hc.core5.net.URIBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 从运维提供的 JSON 配置创建公网只读工具。例如把固定文档搜索地址注册成 HTTP_DOCS。 */
public final class HttpToolFactory {
    private HttpToolFactory() {}

    public static <C> List<AgentTool<C>> load(ObjectMapper json, byte[] configuration) throws IOException {
        if (configuration.length > 65_536) throw new IllegalArgumentException("HTTP 工具配置超过 64 KiB");
        var definitions = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(configuration);
        if (definitions == null || !definitions.isArray() || definitions.size() > 20)
            throw new IllegalArgumentException("HTTP 工具配置必须是最多 20 项的 JSON 数组");
        var tools = new ArrayList<AgentTool<C>>();
        for (var definition : definitions) {
            only(definition, Set.of("name", "description", "url", "query"));
            var name = field(definition, "name", 64);
            if (!name.startsWith("HTTP_")) throw new IllegalArgumentException("公网工具名称必须以 HTTP_ 开头");
            var endpoint = URI.create(field(definition, "url", 2048));
            PublicHttps.validateEndpoint(endpoint, host -> true);
            if (endpoint.getRawQuery() != null) throw new IllegalArgumentException("工具地址不能包含固定查询串");
            var query = definition.path("query");
            if (!query.isArray() || query.size() > 10)
                throw new IllegalArgumentException("query 必须是最多 10 项的数组");
            var parameters = new ArrayList<ToolParameter>();
            for (var parameter : query) {
                only(parameter, Set.of("name", "description", "required"));
                var required = parameter.path("required");
                if (!required.isBoolean()) throw new IllegalArgumentException("required 必须是布尔值");
                parameters.add(new ToolParameter(field(parameter, "name", 64), ToolParameter.Type.STRING,
                        field(parameter, "description", 300), required.booleanValue(), 256, List.of()));
            }
            var metadata = new ToolMetadata(name, "1.0.0", field(definition, "description", 500),
                    "public-https", 35, false, false, true, ToolMetadata.Scope.NETWORK);
            tools.add(new ReadTool<>(metadata, endpoint, parameters));
        }
        return List.copyOf(tools);
    }

    private static String field(JsonNode node, String key, int maxLength) {
        var value = node.path(key);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maxLength)
            throw new IllegalArgumentException("HTTP 工具配置字段无效：" + key);
        return value.textValue();
    }

    private static void only(JsonNode node, Set<String> valid) {
        if (!node.isObject()) throw new IllegalArgumentException("HTTP 工具定义必须是对象");
        node.fieldNames().forEachRemaining(key -> {
            if (!valid.contains(key)) throw new IllegalArgumentException("HTTP 工具配置不支持字段：" + key);
        });
    }

    /** 只调用配置中的固定 HTTPS 路径，运行参数只能作为编码后的查询值。 */
    static final class ReadTool<C> implements AgentTool<C> {
        private final ToolMetadata metadata;
        private final URI endpoint;
        private final List<ToolParameter> parameters;

        private ReadTool(ToolMetadata metadata, URI endpoint, List<ToolParameter> parameters) {
            this.metadata = metadata;
            this.endpoint = endpoint;
            this.parameters = List.copyOf(parameters);
        }

        public ToolMetadata metadata() { return metadata; }
        public List<ToolParameter> parameters() { return parameters; }

        URI requestUri(Map<String, Object> arguments) throws java.net.URISyntaxException {
            var builder = new URIBuilder(endpoint);
            for (var parameter : parameters) {
                if (arguments.containsKey(parameter.name()))
                    builder.addParameter(parameter.name(), (String) arguments.get(parameter.name()));
            }
            return builder.build();
        }

        public String execute(C context, Map<String, Object> arguments) throws Exception {
            try {
                var result = PublicHttps.get(requestUri(arguments), endpoint.getHost()::equalsIgnoreCase, 16_384);
                if (!Set.of("application/json", "text/plain").contains(result.mediaType()))
                    throw new IllegalArgumentException("公网工具只接收 JSON 或纯文本");
                return new String(result.bytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalArgumentException("公网读取失败或响应超过限制");
            }
        }
    }
}
