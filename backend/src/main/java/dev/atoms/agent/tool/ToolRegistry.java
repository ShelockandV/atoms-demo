package dev.atoms.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 注册工具并复用同一份定义生成目录、模型描述和参数校验规则。
 *
 * <p>示例：<code>registry.require("READ_FILE")</code>。</p>
 *
 * One immutable catalog drives discovery, model descriptions and runtime argument validation.
 */
public final class ToolRegistry<C> {
    private final Map<String, AgentTool<C>> tools;


    public ToolRegistry(List<AgentTool<C>> implementations) {
        var registered = new LinkedHashMap<String, AgentTool<C>>();
        for (var tool : implementations) {
            if (registered.putIfAbsent(tool.metadata().name(), tool) != null)
                throw new IllegalArgumentException("重复工具：" + tool.metadata().name());
            var names = new HashSet<String>();
            for (var parameter : tool.parameters())
                if (!names.add(parameter.name()))
                    throw new IllegalArgumentException("重复工具参数：" + parameter.name());
        }
        tools = Collections.unmodifiableMap(registered);
    }

    public AgentTool<C> require(String name) {
        var tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("未注册工具：" + name);
        return tool;
    }

    public List<Map<String, Object>> catalog() {
        return tools.values().stream().map(tool -> Map.<String, Object>of(
                "metadata", tool.metadata(), "parameters", parameterSchema(tool))).toList();
    }

    public List<Map<String, Object>> modelDefinitions() {
        return tools.values().stream().map(tool -> Map.<String, Object>of("type", "function", "function",
                Map.of("name", tool.metadata().name(), "description", tool.metadata().description(),
                        "parameters", parameterSchema(tool)))).toList();
    }

    private Map<String, Object> parameterSchema(AgentTool<C> tool) {
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (var parameter : tool.parameters()) {
            properties.put(parameter.name(), parameter.schema());
            if (parameter.required()) required.add(parameter.name());
        }
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    public void validate(AgentTool<C> tool, Map<String, Object> arguments, ObjectMapper json) {
        var values = json.valueToTree(arguments);
        if (values.toString().length() > 16000) throw new IllegalArgumentException("工具参数总长不能超过16000字符");
        var known = new HashSet<String>();
        for (var parameter : tool.parameters()) {
            known.add(parameter.name());
            var value = values.get(parameter.name());
            if (value == null) {
                if (parameter.required()) throw new IllegalArgumentException("缺少必需参数：" + parameter.name());
                continue;
            }
            boolean matches = switch (parameter.type()) {
                case STRING -> value.isTextual();
                case INTEGER -> value.isIntegralNumber();
                case NUMBER -> value.isNumber();
                case BOOLEAN -> value.isBoolean();
                case ARRAY -> value.isArray();
                case OBJECT -> value.isObject();
            };
            if (!matches) throw new IllegalArgumentException("参数类型错误：" + parameter.name());
            if (value.isTextual()) {
                if (parameter.required() && value.asText().isBlank())
                    throw new IllegalArgumentException("必需参数不能为空：" + parameter.name());
                if (parameter.maxLength() != null && value.asText().length() > parameter.maxLength())
                    throw new IllegalArgumentException("参数过长：" + parameter.name());
                if (!parameter.enumValues().isEmpty() && !parameter.enumValues().contains(value.asText()))
                    throw new IllegalArgumentException("参数不在枚举范围内：" + parameter.name());
            }
        }
        arguments.keySet().forEach(name -> {
            if (!known.contains(name)) throw new IllegalArgumentException("未知工具参数：" + name);
        });
    }
}
