package dev.atoms.workspace;

import java.util.List;
import java.util.Map;

/**
 * 保存本轮使用的基准版本、项目历史和源码。构造时复制集合。
 *
 * <p>示例：<code>new ProjectContext("记账本", null, List.of(), Map.of())</code>。</p>
 *
 * One immutable snapshot shared by planning, generation and repair attempts.
 */
public record ProjectContext(String title, String baseVersion, List<Map<String, String>> history, Map<String, String> files,
                             String skillGuidance, String recalledHistory) {
    public ProjectContext(String title, String baseVersion, List<Map<String, String>> history,
                          Map<String, String> files) {
        this(title, baseVersion, history, files, "");
    }
    public ProjectContext(String title, String baseVersion, List<Map<String, String>> history,
                          Map<String, String> files, String skillGuidance) {
        this(title, baseVersion, history, files, skillGuidance, "");
    }
    public ProjectContext {
        history = history.stream().map(Map::copyOf).toList();
        files = Map.copyOf(files);
    }
}
