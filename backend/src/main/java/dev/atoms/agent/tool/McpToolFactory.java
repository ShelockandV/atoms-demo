package dev.atoms.agent.tool;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.agent.network.PublicHttps;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.function.Predicate;

/** 将运维批准的远程 MCP 读取工具注册为 Agent 工具。例如把远程 search 映射为 MCP_DOC_SEARCH。 */
public final class McpToolFactory {
    private static final String VERSION = "2026-07-28";

    private McpToolFactory() {}

    public static <C> List<AgentTool<C>> load(ObjectMapper json, byte[] configuration) throws IOException {
        return load(json, configuration, PublicHttps::callMcpTool);
    }

    /** 发送一次受限的 MCP 请求，生产环境通过固定 DNS 的 HTTPS 客户端执行。 */
    @FunctionalInterface
    interface Transport {
        PublicHttps.Download call(URI uri, Predicate<String> allowedHost, byte[] body, String remoteName, int maxBytes)
                throws IOException;
    }

    static <C> List<AgentTool<C>> load(ObjectMapper json, byte[] configuration, Transport transport) throws IOException {
        if (configuration.length > 65_536) throw new IllegalArgumentException("MCP 工具配置超过 64 KiB");
        var parser = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode definitions = parser.readTree(configuration);
        if (definitions == null || !definitions.isArray() || definitions.size() > 20)
            throw new IllegalArgumentException("MCP 工具配置必须是最多 20 项的数组");
        var tools = new ArrayList<AgentTool<C>>();
        for (var definition : definitions) {
            only(definition, Set.of("name", "description", "endpoint", "remoteName", "readOnly", "parameters"));
            String name = requiredText(definition, "name", 64);
            if (!name.startsWith("MCP_") || !definition.path("readOnly").isBoolean()
                    || !definition.path("readOnly").booleanValue())
                throw new IllegalArgumentException("MCP 工具须以 MCP_ 开头并由运维显式确认只读");
            String remoteName = requiredText(definition, "remoteName", 128);
            if (!remoteName.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("远程 MCP 工具名无效");
            var endpoint = URI.create(requiredText(definition, "endpoint", 2048));
            PublicHttps.validateEndpoint(endpoint, host -> true);
            if (endpoint.getRawQuery() != null) throw new IllegalArgumentException("MCP 端点不能包含查询串");
            var fields = definition.path("parameters");
            if (!fields.isArray() || fields.size() > 10)
                throw new IllegalArgumentException("MCP parameters 必须是最多 10 项的数组");
            var parameters = new ArrayList<ToolParameter>();
            for (var field : fields) {
                only(field, Set.of("name", "description", "required"));
                if (!field.path("required").isBoolean()) throw new IllegalArgumentException("required 必须是布尔值");
                parameters.add(new ToolParameter(requiredText(field, "name", 64), ToolParameter.Type.STRING,
                        requiredText(field, "description", 300), field.path("required").booleanValue(), 256, List.of()));
            }
            var metadata = new ToolMetadata(name, "1.0.0", requiredText(definition, "description", 500),
                    "remote-mcp", 35, false, false, true, ToolMetadata.Scope.NETWORK);
            tools.add(new ReadTool<>(metadata, endpoint, remoteName, List.copyOf(parameters), json, transport));
        }
        return List.copyOf(tools);
    }

    private static String requiredText(JsonNode node, String name, int maxLength) {
        var value = node.path(name);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maxLength)
            throw new IllegalArgumentException("MCP 工具配置字段无效：" + name);
        return value.textValue();
    }

    private static void only(JsonNode node, Set<String> fields) {
        if (!node.isObject()) throw new IllegalArgumentException("MCP 工具定义必须是对象");
        node.fieldNames().forEachRemaining(key -> {
            if (!fields.contains(key)) throw new IllegalArgumentException("MCP 工具配置不支持字段：" + key);
        });
    }

    /** 将本地工具别名映射到一个固定的端点与远程名称，仅返回文本或结构化内容。 */
    static final class ReadTool<C> implements AgentTool<C> {
        private final ToolMetadata metadata;
        private final URI endpoint;
        private final String remoteName;
        private final List<ToolParameter> parameters;
        private final ObjectMapper json;
        private final Transport transport;

        private ReadTool(ToolMetadata metadata, URI endpoint, String remoteName, List<ToolParameter> parameters,
                         ObjectMapper json, Transport transport) {
            this.metadata = metadata;
            this.endpoint = endpoint;
            this.remoteName = remoteName;
            this.parameters = parameters;
            this.json = json;
            this.transport = transport;
        }

        public ToolMetadata metadata() { return metadata; }
        public List<ToolParameter> parameters() { return parameters; }

        public String execute(C context, Map<String, Object> arguments) {
            String id = UUID.randomUUID().toString();
            var meta = Map.of("io.modelcontextprotocol/protocolVersion", VERSION,
                    "io.modelcontextprotocol/clientInfo", Map.of("name", "atoms", "version", "0.1.0"),
                    "io.modelcontextprotocol/clientCapabilities", Map.of());
            var envelope = Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                    "params", Map.of("name", remoteName, "arguments", arguments, "_meta", meta));
            try {
                var response = transport.call(endpoint, endpoint.getHost()::equalsIgnoreCase,
                        json.writeValueAsBytes(envelope), remoteName, 16_384);
                if (!"application/json".equals(response.mediaType()))
                    throw new IllegalArgumentException("当前 MCP 工具仅支持单条 JSON 响应");
                var node = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(response.bytes());
                if (node == null || !"2.0".equals(node.path("jsonrpc").asText())
                        || !id.equals(node.path("id").asText()) || node.has("error"))
                    throw new IllegalArgumentException("MCP 返回协议错误或响应 ID 不匹配");
                var result = node.path("result");
                if (!result.isObject() || result.path("isError").asBoolean(false)
                        || result.has("taskId") || result.has("resultType") && !"complete".equals(result.path("resultType").asText()))
                    throw new IllegalArgumentException("MCP 工具失败或需要当前不支持的后续交互");
                var outputs = new ArrayList<String>();
                if (result.path("structuredContent").isObject()) outputs.add(result.get("structuredContent").toString());
                for (var content : result.path("content")) {
                    if ("text".equals(content.path("type").asText()) && content.path("text").isTextual())
                        outputs.add(content.get("text").asText());
                }
                if (outputs.isEmpty()) throw new IllegalArgumentException("MCP 工具未返回文本或结构化内容");
                return String.join("\n", outputs);
            } catch (IOException e) {
                throw new IllegalArgumentException("MCP 读取失败、响应不是合法 JSON 或超出大小限制");
            }
        }
    }
}
