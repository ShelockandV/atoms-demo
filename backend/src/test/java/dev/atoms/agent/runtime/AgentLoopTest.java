package dev.atoms.agent.runtime;

import dev.atoms.agent.model.ModelClient;
import dev.atoms.agent.persistence.Store;

import dev.atoms.workspace.ProjectContext;

import dev.atoms.agent.pattern.PatternRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证动作执行、检查点和复核反馈驱动的修复。
 *
 * <p>示例：首次复核失败后生成第二个候选，再通过校验和复核。</p>
 */
class AgentLoopTest {
    final ObjectMapper json = new ObjectMapper();
    final String owner = "owner";
    Store store;
    ScriptedModel model;
    String pid, rid, token;
    final AgentSettings settings = new AgentSettings(12, 2, 6);

    @BeforeEach void setup() {
        var source = dev.atoms.MySqlTestDatabase.create();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        store = new Store(new JdbcTemplate(source), new TransactionTemplate(new DataSourceTransactionManager(source)), json);
        model = new ScriptedModel(json);
        pid = (String) store.create(owner, "Agent 测试").get("id");
        rid = (String) store.queue(pid, owner, "做一个页面", null, "build", "test").get("id");
        token = store.claim(rid, owner);
    }

    Map<String, String> execute(AgentSettings limits) throws Exception {
        return new AgentLoop(store, model, limits).execute(rid, owner, token, "做一个页面", "计划",
                new ProjectContext("Agent 测试", null, List.of(), Map.of()), "test");
    }
    void actions(String... actions) {
        for (String action : actions) model.responses.add("{\"action\":\"" + action + "\",\"summary\":\"操作说明\"}");
    }
    Map<String, String> validFiles(String text) { return Map.of("index.html", "<body>" + text + "</body>"); }

    @Test void realDecisionProtocolInspectsGeneratesValidatesAndFinishes() throws Exception {
        actions("LIST_FILES", "GENERATE");
        model.responses.add("{\"action\":\"READ_FILE\",\"path\":\"index.html\"}");
        actions("VALIDATE", "FINISH");
        model.outputs.add(validFiles("页面"));
        assertEquals(validFiles("页面"), execute(settings));
        assertEquals(1, model.generations);
        assertTrue(store.agentSnapshot(rid).finished());
        var steps = (List<Map<String, Object>>) store.run(rid, owner).get("steps");
        assertEquals(5, steps.size());
        assertEquals("READ_FILE", steps.get(2).get("action"));
        assertTrue(steps.stream().allMatch(s -> "SUCCEEDED".equals(s.get("outcome"))));
        assertNotNull(steps.getFirst().get("updatedAt"));
        assertTrue(model.messages.stream().anyMatch(m -> m.getLast().get("content").contains("<body>页面</body>")));
    }

    @Test void reactCanWriteAndReadSessionArtifact() throws Exception {
        model.responses.add("""
                {"action":"WRITE_WORKSPACE_FILE","arguments":{"path":"artifacts/report.md","content":"# Analysis"}}
                """);
        model.responses.add("""
                {"action":"READ_FILE","arguments":{"path":"artifacts/report.md"}}
                """);
        actions("GENERATE", "VALIDATE", "FINISH");
        model.outputs.add(validFiles("页面"));
        assertEquals(validFiles("页面"), execute(settings));
        assertEquals("# Analysis", store.workspaceFiles(pid, owner).get("artifacts/report.md"));
        assertTrue(model.messages.stream().anyMatch(messages -> messages.getLast().get("content").contains("# Analysis")));
    }

    @Test void prematureFinishAndUnknownToolCannotBypassValidation() throws Exception {
        actions("FINISH", "SHELL", "GENERATE", "FINISH", "VALIDATE", "FINISH");
        model.outputs.add(validFiles("安全工作区"));
        execute(settings);
        var steps = (List<Map<String, Object>>) store.run(rid, owner).get("steps");
        assertEquals("FAILED", steps.get(0).get("outcome"));
        assertEquals("INVALID", steps.get(1).get("action"));
        assertEquals("FAILED", steps.get(3).get("outcome"));
        assertTrue(store.agentSnapshot(rid).validated());
    }

    @Test void failedValidationFeedsBackIntoRepair() throws Exception {
        actions("GENERATE", "VALIDATE", "GENERATE", "VALIDATE", "FINISH");
        model.outputs.add(Map.of("../escape", "bad"));
        model.outputs.add(validFiles("已修复"));
        assertEquals(validFiles("已修复"), execute(settings));
        assertTrue(model.feedback.getLast().contains("源码路径不能包含"));
        assertEquals(2, store.agentSnapshot(rid).generations());
    }

    @Test void stalledAgentStopsAtActionLimitAcrossResume() throws Exception {
        actions("LIST_FILES", "LIST_FILES", "LIST_FILES");
        assertThrows(IllegalArgumentException.class, () -> execute(new AgentSettings(3, 2, 2)));
        assertEquals(3, store.agentSnapshot(rid).step());
        assertEquals(2, store.agentSnapshot(rid).observations().size());
        store.pause(rid, owner);
        store.resume(rid, owner);
        token = store.claim(rid, owner);
        assertThrows(IllegalArgumentException.class, () -> execute(new AgentSettings(3, 2, 2)));
        assertEquals(3, model.messages.size());
    }

    @Test void completedGenerationSurvivesRestartWithoutRegeneration() throws Exception {
        actions("GENERATE");
        model.outputs.add(validFiles("检查点"));
        assertThrows(InterruptedException.class, () -> execute(settings));
        assertEquals(validFiles("检查点"), store.agentSnapshot(rid).files());
        store.recoverJobs();
        token = store.claim(rid, owner);
        actions("VALIDATE", "FINISH");
        assertEquals(validFiles("检查点"), execute(settings));
        assertEquals(1, model.generations);
        assertEquals(3, store.agentSnapshot(rid).step());
    }

    @Test void pendingActionResumesAndStaleWorkerCannotCommitAnEvent() throws Exception {
        var pending = new AgentLoop.Snapshot(1, 0, Map.of(), false, false, List.of(),
                new AgentLoop.Decision("GENERATE", "", "生成源码"), false);
        store.agentStep(rid, owner, token, pending, "RUNNING", "");
        String stale = token;
        store.pause(rid, owner);
        store.resume(rid, owner);
        token = store.claim(rid, owner);
        assertThrows(Store.StaleExecution.class, () -> store.agentStep(rid, owner, stale, pending, "RUNNING", "旧结果"));
        model.outputs.add(validFiles("恢复动作"));
        actions("VALIDATE", "FINISH");
        execute(settings);
        assertEquals(3, ((List<?>) store.run(rid, owner).get("steps")).size());
        assertEquals(2, model.messages.size(), "恢复已选动作无需重新请求选择器");
    }

    @Test void readFileCannotReadHostFilesystem() throws Exception {
        model.responses.add("{\"action\":\"READ_FILE\",\"path\":\"C:/Windows/win.ini\"}");
        actions("GENERATE", "VALIDATE", "FINISH");
        model.outputs.add(validFiles("页面"));
        execute(settings);
        var first = ((List<Map<String, Object>>) store.run(rid, owner).get("steps")).getFirst();
        assertEquals("FAILED", first.get("outcome"));
        assertTrue(first.get("observation").toString().contains("工作区不存在该文件"));
    }

    @Test void requiredReviewRejectsIncompleteImplementationAndAcceptsRepair() throws Exception {
        actions("GENERATE", "VALIDATE", "FINISH", "REVIEW");
        model.responses.add("{\"passed\":false,\"feedback\":\"缺少新增按钮的点击行为\"}");
        actions("GENERATE", "VALIDATE", "REVIEW");
        model.responses.add("{\"passed\":true,\"feedback\":\"点击行为已实现\"}");
        actions("FINISH");
        model.outputs.add(validFiles("不完整"));
        model.outputs.add(validFiles("完整"));
        assertEquals(validFiles("完整"), execute(new AgentSettings(12, 2, 6, true)));
        assertTrue(model.feedback.getLast().contains("缺少新增按钮的点击行为"));
        assertTrue(store.agentSnapshot(rid).reviewed());
        var steps = (List<Map<String, Object>>) store.run(rid, owner).get("steps");
        assertEquals("FAILED", steps.get(2).get("outcome"), "不能跳过必需的需求复核");
        assertEquals("FAILED", steps.get(3).get("outcome"));
    }

    @Test void structuredArgumentsAreValidatedBeforeReadFileExecution() throws Exception {
        model.responses.add("{\"action\":\"READ_FILE\",\"arguments\":{\"path\":3}}");
        model.responses.add("{\"action\":\"READ_FILE\",\"arguments\":{}}");
        actions("GENERATE", "VALIDATE", "FINISH");
        model.outputs.add(validFiles("页面"));
        execute(settings);
        var steps = (List<Map<String, Object>>) store.run(rid, owner).get("steps");
        assertEquals("FAILED", steps.get(0).get("outcome"));
        assertTrue(steps.get(0).get("observation").toString().contains("参数类型错误"));
        assertTrue(steps.get(1).get("observation").toString().contains("缺少必需参数"));
        assertNotNull(steps.get(2).get("executionTimeMs"));
    }

    @Test void reflectionPatternRepairsWithoutCallingTheActionSelector() throws Exception {
        model.outputs.add(validFiles("遗漏按钮"));
        model.outputs.add(validFiles("完整按钮"));
        model.responses.add("{\"passed\":false,\"feedback\":\"需要绑定点击事件\"}");
        model.responses.add("{\"passed\":true,\"feedback\":\"交互已实现\"}");
        var files = new AgentLoop(store, model, settings, PatternRegistry.require("reflection")).execute(rid, owner, token,
                "做页面", "计划", new ProjectContext("项目", null, List.of(), Map.of()), "test");
        assertEquals(validFiles("完整按钮"), files);
        assertEquals(2, model.messages.size(), "只发生两次复核请求，没有动作选择请求");
        assertTrue(model.feedback.getLast().contains("需要绑定点击事件"));
        assertTrue(store.agentSnapshot(rid).reviewed());
    }

    @Test void dagPatternAnalyzesBeforeGenerationAndPersistsAllNodes() throws Exception {
        model.outputs.add(validFiles("DAG页面"));
        model.responses.add("{\"passed\":true,\"feedback\":\"完成\"}");
        var files = new AgentLoop(store, model, settings, PatternRegistry.require("dag")).execute(rid, owner, token,
                "做页面", "计划", new ProjectContext("项目", null, List.of(), Map.of()), "test");
        assertEquals(validFiles("DAG页面"), files);
        assertTrue(model.feedback.getFirst().contains("DAG 分析完成"));
        assertEquals(3, store.dagNodes(rid).size());
        assertTrue(store.dagNodes(rid).stream().allMatch(n -> n.get("status").equals("SUCCEEDED")));
    }

    @Test void isolationFailureBecomesRepairFeedbackBeforeCompletion() throws Exception {
        model.outputs.add(validFiles("第一次"));
        model.outputs.add(validFiles("已修复"));
        model.responses.add("{\"passed\":true,\"feedback\":\"完成\"}");
        var checks = new ArrayList<Map<String, String>>();
        var verifier = new dev.atoms.workspace.IsolatedAcceptance("", "") {
            @Override public String verify(Map<String, String> files) {
                checks.add(files);
                if (checks.size() == 1) throw new IllegalArgumentException("隔离测试失败：断言未通过");
                return "隔离测试通过";
            }
        };
        var loop = new AgentLoop(store, model, settings, PatternRegistry.require("reflection"), verifier);
        var project = new ProjectContext("项目", null, List.of(), Map.of());
        assertEquals(validFiles("已修复"), loop.execute(rid, owner, token, "做页面", "计划", project, "test"));
        assertEquals(List.of(validFiles("第一次"), validFiles("已修复")), checks);
        assertTrue(model.feedback.getLast().contains("隔离测试失败：断言未通过"));
    }

    @Test void dynamicDagReusesPlanAndCompletedNodeAfterResume() throws Exception {
        model.proposal = """
                [{"id":"data","task":"分析数据结构","dependsOn":[]},
                 {"id":"ui","task":"设计界面验收","dependsOn":["data"]}]
                """;
        model.interruptAnalysis = "v2.analysis.ui";
        var loop = new AgentLoop(store, model, settings, PatternRegistry.require("dag"));
        var project = new ProjectContext("项目", null, List.of(), Map.of());
        assertThrows(InterruptedException.class, () ->
                loop.execute(rid, owner, token, "做页面", "计划", project, "test"));
        store.pause(rid, owner);
        store.resume(rid, owner);
        String resumed = store.claim(rid, owner);
        model.interruptAnalysis = null;
        model.outputs.add(validFiles("动态 DAG"));
        model.responses.add("{\"passed\":true,\"feedback\":\"完成\"}");
        assertEquals(validFiles("动态 DAG"), loop.execute(rid, owner, resumed, "做页面", "计划", project, "test"));
        assertEquals(1, model.proposals);
        assertEquals(1, Collections.frequency(model.analyses, "v2.analysis.data"));
        assertEquals(2, Collections.frequency(model.analyses, "v2.analysis.ui"));
        assertTrue(model.generationPlan.contains("DAG 开发约束：分析结论：synthesis"));
    }

    @Test void dynamicDagRejectsCyclesBeforeAnalysisOrGeneration() {
        model.proposal = """
                [{"id":"data","task":"分析数据结构","dependsOn":["ui"]},
                 {"id":"ui","task":"设计界面验收","dependsOn":["data"]}]
                """;
        var loop = new AgentLoop(store, model, settings, PatternRegistry.require("dag"));
        var project = new ProjectContext("项目", null, List.of(), Map.of());
        var failure = assertThrows(IllegalStateException.class, () ->
                loop.execute(rid, owner, token, "做页面", "计划", project, "test"));
        assertTrue(failure.getMessage().contains("循环依赖"));
        assertTrue(model.analyses.isEmpty());
        assertEquals(0, model.generations);
    }

    /**
     * 按顺序返回预设的动作、评审反馈或源码快照。
     *
     * <p>示例：先返回 GENERATE，再返回 VALIDATE 和 FINISH。</p>
     */
    static class ScriptedModel extends ModelClient {
        final Deque<String> responses = new ArrayDeque<>();
        final Deque<Map<String, String>> outputs = new ArrayDeque<>();
        final List<List<Map<String, String>>> messages = new ArrayList<>();
        final List<String> feedback = new ArrayList<>();
        int generations;
        String proposal, interruptAnalysis, generationPlan;
        int proposals;
        final List<String> analyses = Collections.synchronizedList(new ArrayList<>());
        ScriptedModel(ObjectMapper json) { super(json, false, "http://unused", "test", "test", 30, 1000); }
        @Override public boolean dynamicDagEnabled() { return proposal != null; }
        @Override public String proposeDag(String prompt, String plan, ProjectContext context, String model) {
            proposals++;
            return proposal;
        }
        @Override public String analyze(String prompt, ProjectContext context, String aspect, String instructions, String model)
                throws InterruptedException {
            analyses.add(aspect);
            if (aspect.equals(interruptAnalysis)) throw new InterruptedException("模拟暂停");
            return "分析结论：" + aspect;
        }
        @Override public String completion(List<Map<String, String>> messages, String selectedModel) throws Exception {
            if (responses.isEmpty()) throw new InterruptedException("模拟请求被暂停");
            this.messages.add(List.copyOf(messages));
            return responses.removeFirst();
        }
        @Override public Map<String, String> generate(String prompt, String plan, ProjectContext context, String failure, String selectedModel) {
            generations++;
            generationPlan = plan;
            feedback.add(failure);
            return outputs.removeFirst();
        }
    }
}
