package dev.atoms.agent.tool.builtin;

import dev.atoms.agent.pattern.DagExecutor;

import dev.atoms.agent.tool.*;

import dev.atoms.workspace.SourceFiles;

import java.util.*;

/**
 * 定义构建流程可用的内置工具及其行为。
 *
 * <p>示例：BuiltinTools.create() 返回包含 GENERATE、VALIDATE 和 FINISH 的工具列表。</p>
 *
 * Built-in implementations are registered once; AgentLoop contains no tool dispatch branches.
 */
public final class BuiltinTools {
    private BuiltinTools() {}

    /**
     * 用函数表示一个内置工具的具体操作。
     *
     * <p>示例：<code>(context, args) -&gt; context.workspace().keySet().toString()</code>。</p>
     */
    private interface Handler {
        String execute(ToolContext context, Map<String, Object> args) throws Exception;
    }

    /**
     * 把工具元数据、参数定义和操作函数组合成一个可注册工具。
     *
     * <p>示例：LIST_FILES 把工作区的文件名作为操作结果。</p>
     */
    private record Builtin(ToolMetadata metadata, List<ToolParameter> parameters,
                           Handler handler) implements AgentTool<ToolContext> {
        @Override
        public String execute(ToolContext context, Map<String, Object> args) throws Exception {
            return handler.execute(context, args);
        }
    }

    private static AgentTool<ToolContext> tool(String name, String description, String category, int timeout,
                                               List<ToolParameter> parameters, Handler handler) {
        return new Builtin(new ToolMetadata(name, "1.0.0", description, category, timeout,
                false, false, true, ToolMetadata.Scope.VIRTUAL_WORKSPACE), parameters, handler);
    }

    public static List<AgentTool<ToolContext>> create() {
        return List.of(
                tool("PLAN_DAG", "并行分析本轮需求和架构，依赖节点完成后汇总开发约束；已完成节点可恢复复用。",
                        "planning", 1800, List.of(), (c, args) -> {
                            c.stage("DECOMPOSING");
                            boolean legacy = c.nodes().stream().anyMatch(node -> node.get("id").toString().startsWith("v1."));
                            boolean dynamicStarted = c.nodes().stream().anyMatch(node -> DynamicDag.PLAN.equals(node.get("id")));
                            if (!legacy && (dynamicStarted || c.dynamicDagEnabled())) {
                                try {
                                    return "DAG 分析完成，开发约束与验收清单：\n" + DynamicDag.execute(c);
                                } catch (IllegalArgumentException e) {
                                    throw new IllegalStateException("动态 DAG 未完成：" + e.getMessage(), e);
                                }
                            }
                            var outputs = new DagExecutor().execute(List.of(
                                    new DagExecutor.Node("v1.requirements", List.of(), dependencies ->
                                            c.analyze("requirements", "列出本轮必须落实的需求、验收条件及需保留的原有行为。")),
                                    new DagExecutor.Node("v1.architecture", List.of(), dependencies ->
                                            c.analyze("architecture", "分析源码结构、受影响文件、技术栈约束和实现风险。")),
                                    new DagExecutor.Node("v1.synthesis", List.of("v1.requirements", "v1.architecture"), dependencies ->
                                            c.analyze("synthesis", "根据以下分析汇总具体实现步骤和验收清单：\n" + c.encode(dependencies)))
                            ), c, 2);
                            return "DAG 分析完成，开发约束与验收清单：\n" + outputs.get("v1.synthesis");
                        }),
                tool("LIST_FILES", "列出虚拟源码工作区中的全部文件路径。", "workspace", 30, List.of(), (c, args) -> {
                    c.stage("INSPECTING");
                    return "当前工作区文件：" + new TreeSet<>(c.workspace().keySet());
                }),
                tool("READ_FILE", "读取虚拟工作区内一个文件的源码，最多返回8000字符。", "workspace", 30,
                        List.of(new ToolParameter("path", ToolParameter.Type.STRING, "文件完整相对路径，例如src/main/java/App.java",
                                true, 240, List.of())), (c, args) -> {
                            c.stage("INSPECTING");
                            String path = (String) args.get("path");
                            String content = c.workspace().get(path);
                            if (content == null) throw new IllegalArgumentException("工作区不存在该文件：" + path);
                            return "文件 " + path + "：\n" + clip(content, 8000);
                        }),
                tool("GENERATE", "根据需求生成完整源码快照，或根据候选源码和工具反馈修复。生成后必须重新校验和复核。",
                        "development", 1800, List.of(), (c, args) -> {
                            if (c.generations() >= c.maxGenerations())
                                throw new IllegalArgumentException("已达到源码生成次数上限，请校验现有源码或结束任务");
                            c.generations(c.generations() + 1);
                            c.validated(false);
                            c.reviewed(false);
                            c.stage(c.generations() == 1 ? "GENERATING" : "REPAIRING");
                            String candidate = c.files().isEmpty() ? "" : "\n上轮候选源码（需要在此基础上修复）：" + c.encode(c.files());
                            String constraints = c.nodes().stream()
                                    .filter(node -> Set.of("v1.synthesis", DynamicDag.SUMMARY).contains(node.get("id"))
                                            && "SUCCEEDED".equals(node.get("status")))
                                    .map(node -> "\nDAG 开发约束：" + node.get("output")).findFirst().orElse("");
                            c.files(c.generate(constraints + candidate));
                            return "源码生成完成，共 " + c.files().size() + " 个文件；尚未校验，请执行 VALIDATE。";
                        }),
                tool("VALIDATE", "检查候选源码，按配置运行隔离 Maven 测试或浏览器冒烟检查。", "quality", 205,
                        List.of(), (c, args) -> {
                            c.stage("VALIDATING");
                            c.validated(false);
                            SourceFiles.validate(c.files());
                            if (!c.baseline().isEmpty() && c.files().equals(c.baseline()))
                                throw new IllegalArgumentException("模型返回的源码与当前版本完全相同，尚未落实本次修改需求。请根据本次需求实际修改相关文件，不能原样复制旧源码");
                            String acceptance = c.verifyCandidate();
                            c.validated(true);
                            return "静态校验通过。" + (acceptance.isEmpty() ? "未执行隔离验收。" : acceptance)
                                    + (c.requiresReview() ? "请执行 REVIEW 复核需求。" : "可执行 FINISH。");
                        }),
                tool("REVIEW", "校验通过后，单独请求模型复核候选源码是否落实需求；失败反馈用于下一轮修复。", "quality", 1800,
                        List.of(), (c, args) -> {
                            if (!c.validated()) throw new IllegalArgumentException("必须先通过 VALIDATE，再复核需求");
                            c.stage("REVIEWING");
                            c.reviewed(false);
                            var review = c.review(c.files());
                            if (!review.passed())
                                throw new IllegalArgumentException("需求复核未通过：" + review.feedback());
                            c.reviewed(true);
                            return "需求复核通过：" + review.feedback() + "。模型复核不能替代实际编译运行和功能验收。";
                        }),
                tool("FINISH", "所有必需检查通过后结束开发循环，交由版本模块保存；不能跳过校验或必需的需求复核。",
                        "workflow", 30, List.of(), (c, args) -> {
                            if (!c.validated() || c.files().isEmpty())
                                throw new IllegalArgumentException("必须先生成源码并通过 VALIDATE，才能完成任务");
                            if (c.requiresReview() && !c.reviewed())
                                throw new IllegalArgumentException("必须先通过 REVIEW 需求复核，才能完成任务");
                            c.finished(true);
                            return "源码已通过静态校验，准备保存新版本。";
                        })
        );
    }
    private static String clip(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "\n[内容已截断]";
    }
}
