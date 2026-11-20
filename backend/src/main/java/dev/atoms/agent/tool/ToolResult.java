package dev.atoms.agent.tool;

/**
 * 记录一次工具调用的成功状态、输出或错误以及耗时。
 *
 * <p>示例：<code>new ToolResult(true, "校验通过", "", 18)</code>。</p>
 */
public record ToolResult(boolean success, String output, String error, long executionTimeMs) {
    public String observation() { return success ? output : error; }
}
