package dev.atoms.agent.tool;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 检查工具参数及访问范围，并在超时约束下执行工具。
 *
 * <p>示例：对 READ_FILE 传入数字 path 时返回参数类型错误。</p>
 *
 * Runs trusted Java tools with bounded waiting, session fencing and inherited model accounting.
 */
public final class ToolExecutor<C> {
    private final ToolRegistry<C> registry;

    public ToolExecutor(ToolRegistry<C> registry) { this.registry = registry; }

    /**
     * 允许调用方在执行前后验证任务是否仍有效。
     *
     * <p>示例：<code>invocation::checkActive</code>。</p>
     */
    @FunctionalInterface public interface ActiveCheck { void run() throws InterruptedException; }

    public ToolResult execute(String action, Map<String, Object> arguments, C context, ObjectMapper json,
                              String owner, ActiveCheck checkActive, Supplier<? extends AutoCloseable> openScope) throws Exception {
        long started = System.nanoTime();
        AgentTool<C> tool;
        try {
            tool = registry.require(action);
            registry.validate(tool, arguments, json);
            var metadata = tool.metadata();
            // No authenticated principal, approval ledger, or external execution runtime exists yet.
            if (metadata.requiresAuth() || metadata.dangerous())
                throw new IllegalArgumentException("当前运行环境未提供此工具要求的认证或审批：" + metadata.name());
            if (metadata.scope() != ToolMetadata.Scope.VIRTUAL_WORKSPACE
                    && !(tool instanceof HttpToolFactory.ReadTool<?> || tool instanceof McpToolFactory.ReadTool<?>))
                throw new IllegalArgumentException("当前工具没有获准的执行范围");
            if (metadata.sessionAware() && (owner == null || owner.isBlank()))
                throw new IllegalArgumentException("工具要求有效项目会话");
        } catch (IllegalArgumentException e) {
            return new ToolResult(false, "", e.getMessage(), elapsed(started));
        }
        checkActive.run();
        var task = new FutureTask<ToolResult>(() -> {
            try (var tracking = openScope.get()) {
                try {
                    checkActive.run();
                    String output = tool.execute(context, arguments);
                    checkActive.run();
                    return new ToolResult(true, output, "", elapsed(started));
                } catch (IllegalArgumentException e) {
                    checkActive.run();
                    return new ToolResult(false, "", e.getMessage() == null ? "工具执行失败" : e.getMessage(),
                            elapsed(started));
                }
            }
        });
        Thread.ofVirtual().name("atoms-tool-" + action).start(task);
        try {
            return task.get(tool.metadata().timeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            // End the run: a non-cooperating tool must not share a live execution token with a next step.
            throw new IllegalStateException("工具执行超时：" + action + "（" + tool.metadata().timeoutSeconds() + "秒）");
        } catch (InterruptedException e) {
            task.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            if (e.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("工具执行失败", e.getCause());
        }
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
}
