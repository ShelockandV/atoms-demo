package dev.atoms.agent.tool;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.atoms.agent.network.PublicHttps;

import java.io.IOException;
import java.net.URI;
import java.util.*;

/** 将运维显式选择的 OpenAPI GET 操作映射为受控读取工具。例如将 searchDocs 注册为 HTTP_DOCS。 */
public final class OpenApiToolImport {
    private OpenApiToolImport() {}

    public static <C> List<AgentTool<C>> load(ObjectMapper json, byte[] config) throws IOException {
        if (config.length > 262_144) throw new IllegalArgumentException("OpenAPI 导入配置超过 256 KiB");
        var root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(config);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("OpenAPI 导入配置必须是对象");
        URI base = URI.create(root.path("baseUrl").asText());
        PublicHttps.validateEndpoint(base, host -> true);
        if (base.getRawQuery() != null || !validPath(base.getRawPath()))
            throw new IllegalArgumentException("OpenAPI 基础地址必须使用固定路径，且不能包含查询串");
        var selected = root.path("operations");
        var spec = root.path("spec");
        if (!selected.isObject() || selected.isEmpty() || selected.size() > 20
                || !spec.path("openapi").asText().matches("3\\.[01]\\.[0-9]+") || !spec.path("paths").isObject())
            throw new IllegalArgumentException("需要 OpenAPI 3.0/3.1 JSON 和 1～20 个显式选择的 operationId");
        var definitions = json.createArrayNode();
        var seen = new HashSet<String>();
        var paths = spec.path("paths").fields();
        while (paths.hasNext()) {
            var path = paths.next();
            var item = path.getValue();
            var get = item.path("get");
            String id = get.path("operationId").asText();
            if (!selected.has(id)) continue;
            var name = selected.get(id);
            var security = get.has("security") ? get.get("security") : spec.path("security");
            if (!seen.add(id) || !name.isTextual() || !path.getKey().startsWith("/") || !validPath(path.getKey())
                    || get.has("requestBody") || get.has("$ref") || item.has("$ref")
                    || (!security.isMissingNode() && (!security.isArray() || !security.isEmpty())))
                throw new IllegalArgumentException("OpenAPI 操作必须是无认证、无请求体的固定 GET：" + id);
            var tool = definitions.addObject();
            tool.put("name", name.asText());
            tool.put("description", get.path("summary").asText(id));
            tool.put("url", base.toString().replaceAll("/+$", "") + path.getKey());
            var query = tool.putArray("query");
            addQuery(item.path("parameters"), query);
            addQuery(get.path("parameters"), query);
        }
        if (seen.size() != selected.size())
            throw new IllegalArgumentException("OpenAPI 导入项包含不存在或非 GET 的 operationId");
        return HttpToolFactory.load(json, json.writeValueAsBytes(definitions));
    }

    private static boolean validPath(String path) {
        return path.matches("(?:/[A-Za-z0-9_.~-]+)*/?")
                && Arrays.stream(path.split("/")).noneMatch(part -> part.equals(".") || part.equals(".."));
    }

    private static void addQuery(JsonNode parameters, ArrayNode query) {
        if (parameters.isMissingNode()) return;
        if (!parameters.isArray()) throw new IllegalArgumentException("OpenAPI parameters 必须是数组");
        for (var parameter : parameters) {
            var schema = parameter.path("schema");
            if (parameter.has("$ref") || parameter.has("content") || !"query".equals(parameter.path("in").asText())
                    || !"form".equals(parameter.path("style").asText("form"))
                    || parameter.path("allowReserved").asBoolean(false)
                    || schema.size() != 1 || !"string".equals(schema.path("type").asText()))
                throw new IllegalArgumentException("OpenAPI 导入只支持内联、无约束的字符串查询参数");
            var field = query.addObject();
            String name = parameter.path("name").asText();
            field.put("name", name);
            field.put("description", parameter.path("description").asText(name));
            field.put("required", parameter.path("required").asBoolean(false));
        }
    }
}
