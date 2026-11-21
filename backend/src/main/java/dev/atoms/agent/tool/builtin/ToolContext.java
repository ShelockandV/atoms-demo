package dev.atoms.agent.tool.builtin;

import dev.atoms.agent.pattern.DagExecutor;

import java.util.Map;

/**
 * 声明内置工具可使用的工作区状态、模型操作及 DAG 节点能力。
 *
 * <p>示例：工具调用 context.workspace() 获取文件集合。</p>
 */
public interface ToolContext extends DagExecutor.Runtime {
    void stage(String stage) throws InterruptedException;
    Map<String, String> workspace();
    Map<String, String> baseline();
    default void writeWorkspaceFile(String path, String content) {
        throw new UnsupportedOperationException("当前工具上下文不允许写会话文件");
    }
    String searchDocuments(String query);
    Map<String, String> files();
    void files(Map<String, String> files);
    int generations();
    void generations(int count);
    int maxGenerations();
    boolean validated();
    void validated(boolean value);
    boolean reviewed();
    void reviewed(boolean value);
    void finished(boolean value);
    boolean requiresReview();
    String encode(Object value);
    String analyze(String aspect, String instructions) throws Exception;
    String proposeDag() throws Exception;
    boolean dynamicDagEnabled();
    default boolean dynamicDagEditsEnabled() { return false; }
    default String proposeDagEdit(String aspect, String instructions) throws Exception {
        throw new UnsupportedOperationException("动态 DAG 源码节点不可用");
    }
    String verifyCandidate() throws Exception;
    Map<String, String> generate(String planDetails) throws Exception;
    Review review(Map<String, String> files) throws Exception;

    /**
     * 向内置工具提供复核结论与反馈。
     *
     * <p>示例：<code>new Review(false, "新增按钮缺少点击事件")</code>。</p>
     */
    record Review(boolean passed, String feedback) {}
}
