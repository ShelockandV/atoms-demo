package dev.atoms.agent.runtime;

import dev.atoms.agent.model.ModelClient;
import dev.atoms.agent.persistence.Store;

import dev.atoms.workspace.ProjectContext;

import dev.atoms.agent.pattern.DagExecutor;

import dev.atoms.agent.tool.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 DAG 的并行、依赖、恢复及错误阻断。
 *
 * <p>示例：两个独立节点完成后才进入汇总节点。</p>
 */
class DagExecutorTest {
    Store store;
    DriverManagerDataSource source;
    ModelClient model;
    String pid, rid, token;
    @BeforeEach void setup() {
        source = dev.atoms.MySqlTestDatabase.create();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        var json = new ObjectMapper();
        store = new Store(new JdbcTemplate(source), new TransactionTemplate(new DataSourceTransactionManager(source)), json);
        model = new ModelClient(json, true, "http://unused", "test", "test", 5, 100);
        pid = (String) store.create("owner", "DAG").get("id");
        rid = (String) store.queue(pid, "owner", "目标", null, "build", "test", "dag").get("id");
        token = store.claim(rid, "owner");
    }
    ToolInvocation context() {
        return new ToolInvocation(store, model, new AgentSettings(12, 2, 6), rid, "owner", token, "目标", "计划",
                new ProjectContext("DAG", null, List.of(), Map.of()), "test",
                new AgentLoop.Snapshot(1, 0, Map.of(), false, false, List.of(), new AgentLoop.Decision("PLAN_DAG", "", "分析"), false));
    }

    @Test void independentNodesRunConcurrentlyAndJoinReceivesOnlyDependencyOutputs() throws Exception {
        var bothStarted = new CountDownLatch(2);
        var live = new AtomicInteger();
        var peak = new AtomicInteger();
        DagExecutor.Work independent = dependencies -> {
            assertTrue(dependencies.isEmpty());
            peak.accumulateAndGet(live.incrementAndGet(), Math::max);
            bothStarted.countDown();
            assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "独立节点应并行启动");
            live.decrementAndGet();
            return "分析";
        };
        var results = new DagExecutor().execute(List.of(
                new DagExecutor.Node("requirements", List.of(), independent),
                new DagExecutor.Node("architecture", List.of(), independent),
                new DagExecutor.Node("join", List.of("requirements", "architecture"), dependencies -> {
                    assertEquals(Map.of("requirements", "分析", "architecture", "分析"), dependencies);
                    return "实现方案";
                })), context(), 2);
        assertEquals(2, peak.get());
        assertEquals("实现方案", results.get("join"));
        assertTrue(store.dagNodes(rid).stream().allMatch(n -> n.get("status").equals("SUCCEEDED")));
    }

    @Test void resumeReusesCompletedNodesAndFencesThePreviousExecution() throws Exception {
        store.dagNode(rid, "owner", token, "first", List.of(), "SUCCEEDED", "检查点结果", 10);
        String stale = token;
        store.recoverJobs();
        token = store.claim(rid, "owner");
        var results = new DagExecutor().execute(List.of(
                new DagExecutor.Node("first", List.of(), dependencies -> { throw new AssertionError("不应重新执行"); }),
                new DagExecutor.Node("next", List.of("first"), dependencies -> dependencies.get("first") + "继续")), context(), 2);
        assertEquals("检查点结果继续", results.get("next"));
        assertEquals("dag", store.runPattern(rid));
        assertThrows(Store.StaleExecution.class, () -> store.dagNode(rid, "owner", stale, "next", List.of(), "SUCCEEDED", "旧结果", 1));
    }

    @Test void cyclesAndMissingDependenciesAreRejectedBeforeAnyWork() {
        DagExecutor.Work forbidden = dependencies -> { throw new AssertionError("非法图不能执行"); };
        assertThrows(IllegalArgumentException.class, () -> new DagExecutor().execute(List.of(
                new DagExecutor.Node("a", List.of("b"), forbidden), new DagExecutor.Node("b", List.of("a"), forbidden)), context(), 2));
        assertThrows(IllegalArgumentException.class, () -> new DagExecutor().execute(List.of(
                new DagExecutor.Node("a", List.of("missing"), forbidden)), context(), 2));
        assertTrue(store.dagNodes(rid).isEmpty());
    }

    @Test void unknownPatternCannotCreateRunAndLegacyRunsDefaultToReact() {
        assertThrows(IllegalArgumentException.class, () -> store.queue(pid, "owner", "非法策略", null, "build", "test", "swarm"));
        assertEquals(rid, store.project(pid, "owner").get("active_run"));
        new JdbcTemplate(source).update("DELETE FROM run_patterns WHERE run_id=?", rid);
        assertEquals("react", store.runPattern(rid));
    }

    @Test void failedDependencyNeverStartsItsDependent() {
        var started = new AtomicBoolean();
        assertThrows(IllegalStateException.class, () -> new DagExecutor().execute(List.of(
                new DagExecutor.Node("fail", List.of(), dependencies -> { throw new IllegalStateException("节点失败"); }),
                new DagExecutor.Node("dependent", List.of("fail"), dependencies -> { started.set(true); return "不应执行"; })), context(), 2));
        assertFalse(started.get());
        assertEquals("FAILED", store.dagNodes(rid).getFirst().get("status"));
    }

    @Test void timeoutBoundsWaitingAndDiscardsLateToolResult() throws Exception {
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        AgentTool<ToolInvocation> slow = new AgentTool<>() {
            public ToolMetadata metadata() { return new ToolMetadata("SLOW", "1", "超时测试", "test", 1, false, false, true, ToolMetadata.Scope.VIRTUAL_WORKSPACE); }
            public List<ToolParameter> parameters() { return List.of(); }
            public String execute(ToolInvocation context, Map<String, Object> args) {
                boolean done = false;
                while (!done) { try { release.await(); done = true; } catch (InterruptedException ignored) {} }
                context.files = Map.of("index.html", "迟到结果");
                returned.countDown();
                return "迟到结果";
            }
        };
        try {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(4), () ->
                assertThrows(IllegalStateException.class, () -> context().execute(new ToolExecutor<>(new ToolRegistry<>(List.of(slow))), new AgentLoop.Decision("SLOW", "", "测试"))));
            assertNull(store.agentSnapshot(rid), "超时工具不能直接写入循环检查点");
        } finally { release.countDown(); }
        assertTrue(returned.await(5, TimeUnit.SECONDS));
        assertNull(store.agentSnapshot(rid));
    }

    @Test void unsupportedAuthenticationApprovalAndHostScopeAreDeniedBeforeExecution() throws Exception {
        for (var metadata : List.of(
                new ToolMetadata("AUTH", "1", "需认证", "test", 5, true, false, true, ToolMetadata.Scope.VIRTUAL_WORKSPACE),
                new ToolMetadata("DANGEROUS", "1", "需审批", "test", 5, false, true, true, ToolMetadata.Scope.VIRTUAL_WORKSPACE),
                new ToolMetadata("HOST", "1", "宿主机访问", "test", 5, false, false, true, ToolMetadata.Scope.HOST))) {
            AgentTool<ToolInvocation> denied = new AgentTool<>() {
                public ToolMetadata metadata() { return metadata; }
                public List<ToolParameter> parameters() { return List.of(); }
                public String execute(ToolInvocation context, Map<String, Object> arguments) { throw new AssertionError("不应执行"); }
            };
            assertFalse(context().execute(new ToolExecutor<>(new ToolRegistry<>(List.of(denied))), new AgentLoop.Decision(metadata.name(), "", "拒绝测试")).success());
        }
    }
}
