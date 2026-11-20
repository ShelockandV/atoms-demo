package dev.atoms.agent.tool;

import java.util.*;

/**
 * 定义工具参数的类型、必填条件和可选限制，并用于生成参数 schema。
 *
 * <p>示例：<code>new ToolParameter("path", Type.STRING, "相对路径", true, 240, List.of())</code>。</p>
 */
public record ToolParameter(String name, Type type, String description, boolean required,
                            Integer maxLength, List<String> enumValues) {
    /**
     * 列出工具参数支持的 JSON 数据类型。
     *
     * <p>示例：Type.STRING 用于文件路径，Type.BOOLEAN 用于开关。</p>
     */
    public enum Type {STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT}

    public ToolParameter {
        if (name == null || !name.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}") || type == null
                || description == null || description.isBlank())
            throw new IllegalArgumentException("工具参数定义无效");
        if (maxLength != null && (type != Type.STRING || maxLength < 1))
            throw new IllegalArgumentException("maxLength 只能用于字符串参数且必须大于0");
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        if (!enumValues.isEmpty() && type != Type.STRING)
            throw new IllegalArgumentException("当前枚举参数只支持字符串");
    }

    Map<String, Object> schema() {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", type.name().toLowerCase(Locale.ROOT));
        schema.put("description", description);
        if (required && type == Type.STRING) schema.put("minLength", 1);
        if (maxLength != null) schema.put("maxLength", maxLength);
        if (!enumValues.isEmpty()) schema.put("enum", enumValues);
        return schema;
    }
}
