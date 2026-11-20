package dev.atoms.agent.tool;

/**
 * 描述工具身份、用途、超时和访问要求。
 *
 * <p>示例：<code>new ToolMetadata("LIST_FILES", "1.0.0", "列出文件", "workspace", 30, false, false, true, Scope.VIRTUAL_WORKSPACE)</code>。</p>
 *
 * Execution constraints describe capabilities the executor actually enforces.
 */
public record ToolMetadata(String name, String version, String description, String category,
                           int timeoutSeconds, boolean requiresAuth, boolean dangerous,
                           boolean sessionAware, Scope scope) {
    /**
     * 标明工具需要访问的范围，供执行器判断是否允许执行。
     *
     * <p>示例：VIRTUAL_WORKSPACE 用于读取项目快照。</p>
     */
    public enum Scope { VIRTUAL_WORKSPACE, NETWORK, HOST }

    public ToolMetadata {
        if (name == null || !name.matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("工具名称须为大写字母、数字或下划线");
        if (version == null || version.isBlank() || description == null || description.isBlank()
                || category == null || category.isBlank() || scope == null)
            throw new IllegalArgumentException("工具元数据不完整");
        if (timeoutSeconds < 1 || timeoutSeconds > 1800)
            throw new IllegalArgumentException("工具超时须为1～1800秒");
    }
}
