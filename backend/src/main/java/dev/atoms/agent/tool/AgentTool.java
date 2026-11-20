package dev.atoms.agent.tool;

import java.util.List;
import java.util.Map;

/**
 * 定义工具的元数据、参数和执行入口。
 *
 * <p>示例：READ_FILE 声明 path 参数，再由 execute 根据路径读取源码。</p>
 *
 * Tool implementations operate on an invocation-local workspace; only the loop commits results.
 */
public interface AgentTool<C> {
    ToolMetadata metadata();

    List<ToolParameter> parameters();

    String execute(C context, Map<String, Object> arguments) throws Exception;
}
