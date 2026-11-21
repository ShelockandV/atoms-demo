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

    @FunctionalInterface
    private interface Request {
        JsonNode execute(String method, Map<String, Object> arguments) throws IOException;
    }

    private McpToolFactory() {}

    public static <C> List<AgentTool<C>> load(ObjectMapper json, byte[] configuration) throws IOException {
        return load(json, configuration, null);
    }

    /** 发送一次受限的 MCP 请求，生产环境通过固定 DNS 的 HTTPS 客户端执行。 */
    @FunctionalInterface
    interface Transport {
        PublicHttps.Download call(URI uri, Predicate<String> allowedHost, byte[] body, String remoteName, int maxBytes,
                                  PublicHttps.EventStreamResponse sse) throws IOException;
    }

    static <C> List<AgentTool<C>> load(ObjectMapper json, byte[] configuration, Transport transport) throws IOException {
        if (configuration.length > 65_536) throw new IllegalArgumentException("MCP 工具配置超过 64 KiB");
        var parser = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode definitions = parser.readTree(configuration);
        if (definitions == null || !definitions.isArray() || definitions.size() > 20)
            throw new IllegalArgumentException("MCP 工具配置必须是最多 20 项的数组");
        var tools = new ArrayList<AgentTool<C>>();
        for (var definition : definitions) {
            only(definition, Set.of("name", "description", "endpoint", "remoteName", "readOnly", "parameters", "discover", "oauth", "transport", "stdioImage"));
            String name = requiredText(definition, "name", 64);
            if (!name.startsWith("MCP_") || !definition.path("readOnly").isBoolean()
                    || !definition.path("readOnly").booleanValue())
                throw new IllegalArgumentException("MCP 工具须以 MCP_ 开头并由运维显式确认只读");
            String remoteName = requiredText(definition, "remoteName", 128);
            if (!remoteName.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("远程 MCP 工具名无效");
            Request request = connection(json, definition, transport, remoteName);
            var discover = definition.get("discover");
            if (discover != null && !discover.isBoolean())
                throw new IllegalArgumentException("MCP discover 必须是布尔值");
            boolean autoDiscover = discover != null && discover.booleanValue();
            if (autoDiscover && definition.has("parameters"))
                throw new IllegalArgumentException("MCP discover 与手工 parameters 只能配置一个");
            var parameters = autoDiscover ? discover(request, remoteName) : configuredParameters(definition.path("parameters"));
            var metadata = new ToolMetadata(name, "1.0.0", requiredText(definition, "description", 500),
                    "remote-mcp", 35, false, false, true, ToolMetadata.Scope.NETWORK);
            tools.add(new ReadTool<>(metadata, remoteName, List.copyOf(parameters), request));
        }
        return List.copyOf(tools);
    }

    private static Request connection(ObjectMapper json, JsonNode definition, Transport transport, String remoteName) {
        String kind = definition.path("transport").asText("streamable-http");
        if ("stdio".equals(kind)) {
            if (definition.has("endpoint") || definition.has("oauth"))
                throw new IllegalArgumentException("MCP stdio 只使用受控 Docker 镜像");
            var stdio = new McpStdio(requiredText(definition, "stdioImage", 255), json);
            return stdio::request;
        }
        if (!Set.of("streamable-http", "legacy-sse").contains(kind) || definition.has("stdioImage"))
            throw new IllegalArgumentException("MCP transport 无效");
        var endpoint = URI.create(requiredText(definition, "endpoint", 2048));
        PublicHttps.validateEndpoint(endpoint, host -> true);
        if (endpoint.getRawQuery() != null) throw new IllegalArgumentException("MCP 端点不能包含查询串");
        var oauth = oauth(json, definition.get("oauth"));
        if ("legacy-sse".equals(kind)) return (method, params) ->
                PublicHttps.legacyMcp(endpoint, oauth == null ? "" : oauth.token(),
                        (input, post) -> legacyExchange(json, input, post, method, params));
        return (method, supplied) -> {
            String id = UUID.randomUUID().toString();
            var params = new LinkedHashMap<>(supplied);
            params.put("_meta", Map.of("io.modelcontextprotocol/protocolVersion", VERSION,
                    "io.modelcontextprotocol/clientInfo", McpLegacySession.CLIENT,
                    "io.modelcontextprotocol/clientCapabilities", Map.of()));
            var body = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
            PublicHttps.EventStreamResponse sse = (input, limit) -> McpEventStream.read(input, limit, json, id);
            var response = transport == null
                    ? PublicHttps.callMcp(endpoint, endpoint.getHost()::equalsIgnoreCase, body, method,
                            "tools/call".equals(method) ? remoteName : "", oauth == null ? "" : oauth.token(), 16_384, sse)
                    : transport.call(endpoint, endpoint.getHost()::equalsIgnoreCase, body, remoteName, 16_384, sse);
            if (!"application/json".equals(response.mediaType()))
                throw new IOException("MCP 响应须为 JSON 或 SSE");
            return McpLegacySession.result(json, response.bytes(), id);
        };
    }

    static JsonNode legacyExchange(ObjectMapper json, java.io.InputStream input, PublicHttps.LegacyPost post,
                                   String method, Map<String, Object> params) throws IOException {
        var events = new McpEventStream(input, 16_384);
        var address = events.next();
        if (!"endpoint".equals(address.type())) throw new IOException("旧版 MCP SSE 缺少 endpoint 事件");
        return McpLegacySession.request(json, (message, id) -> {
            post.send(address.data(), message);
            return id == null ? null : events.response(json, id);
        }, method, params);
    }

    private static McpOAuth oauth(ObjectMapper json, JsonNode config) {
        if (config == null) return null;
        only(config, Set.of("tokenEndpoint", "clientId", "clientSecret", "scope", "resource"));
        return new McpOAuth(URI.create(requiredText(config, "tokenEndpoint", 2048)),
                requiredText(config, "clientId", 256), requiredText(config, "clientSecret", 2048),
                config.has("scope") ? requiredText(config, "scope", 512) : "",
                config.has("resource") ? requiredText(config, "resource", 2048) : "", json);
    }

    private static List<ToolParameter> configuredParameters(JsonNode fields) {
        if (!fields.isArray() || fields.size() > 10)
            throw new IllegalArgumentException("MCP parameters 必须是最多 10 项的数组");
        var parameters = new ArrayList<ToolParameter>();
        for (var field : fields) {
            only(field, Set.of("name", "description", "required"));
            if (!field.path("required").isBoolean()) throw new IllegalArgumentException("required 必须是布尔值");
            parameters.add(new ToolParameter(requiredText(field, "name", 64), ToolParameter.Type.STRING,
                    requiredText(field, "description", 300), field.path("required").booleanValue(), 256, List.of()));
        }
        return parameters;
    }

    private static List<ToolParameter> discover(Request request, String remoteName) throws IOException {
        var cursors = new HashSet<String>();
        String cursor = "";
        for (int page = 0; page < 5; page++) {
            var result = request.execute("tools/list", cursor.isEmpty() ? Map.of() : Map.of("cursor", cursor));
            if (!result.path("tools").isArray()) throw new IOException("MCP 工具列表无效");
            for (var tool : result.path("tools")) {
                if (!remoteName.equals(tool.path("name").asText())) continue;
                var hints = tool.path("annotations");
                if (hints.path("destructiveHint").asBoolean(false)
                        || (hints.path("readOnlyHint").isBoolean() && !hints.path("readOnlyHint").booleanValue()))
                    throw new IOException("远端 MCP 工具声明了写入或破坏性行为");
                return discoveredParameters(tool.path("inputSchema"));
            }
            cursor = result.path("nextCursor").asText();
            if (cursor.isEmpty() || cursor.length() > 1024 || !cursors.add(cursor)) break;
        }
        throw new IOException("未在 MCP 工具列表中找到已批准的工具：" + remoteName);
    }

    private static List<ToolParameter> discoveredParameters(JsonNode schema) {
        var properties = schema.path("properties");
        if (!"object".equals(schema.path("type").asText()) || (schema.has("properties") && !properties.isObject())
                || properties.size() > 10)
            throw new IllegalArgumentException("MCP 发现的工具输入须是最多包含 10 个字段的对象");
        var required = new HashSet<String>();
        for (var field : schema.path("required")) {
            if (!field.isTextual() || !properties.has(field.asText())) throw new IllegalArgumentException("MCP required 字段无效");
            required.add(field.asText());
        }
        var parameters = new ArrayList<ToolParameter>();
        properties.fields().forEachRemaining(entry -> {
            var field = entry.getValue();
            var type = switch (field.path("type").asText()) {
                case "string" -> ToolParameter.Type.STRING;
                case "integer" -> ToolParameter.Type.INTEGER;
                case "number" -> ToolParameter.Type.NUMBER;
                case "boolean" -> ToolParameter.Type.BOOLEAN;
                default -> throw new IllegalArgumentException("MCP 自动发现暂只支持标量参数");
            };
            var choices = new ArrayList<String>();
            for (var choice : field.path("enum")) {
                if (type != ToolParameter.Type.STRING || !choice.isTextual() || choice.asText().length() > 256)
                    throw new IllegalArgumentException("MCP 自动发现仅支持字符串枚举");
                choices.add(choice.asText());
            }
            String description = field.path("description").asText(entry.getKey()).strip();
            if (description.isEmpty()) description = entry.getKey();
            int max = Math.max(1, Math.min(256, field.path("maxLength").asInt(256)));
            parameters.add(new ToolParameter(entry.getKey(), type, description.substring(0, Math.min(300, description.length())),
                    required.contains(entry.getKey()), type == ToolParameter.Type.STRING ? max : null, choices));
        });
        return parameters;
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
        private final String remoteName;
        private final List<ToolParameter> parameters;
        private final Request request;

        private ReadTool(ToolMetadata metadata, String remoteName, List<ToolParameter> parameters, Request request) {
            this.metadata = metadata;
            this.remoteName = remoteName;
            this.parameters = parameters;
            this.request = request;
        }

        public ToolMetadata metadata() { return metadata; }
        public List<ToolParameter> parameters() { return parameters; }

        public String execute(C context, Map<String, Object> arguments) {
            try {
                var result = request.execute("tools/call", Map.of("name", remoteName, "arguments", arguments));
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
                throw new IllegalArgumentException("MCP 读取失败、响应无效或超出大小限制");
            }
        }
    }
}
