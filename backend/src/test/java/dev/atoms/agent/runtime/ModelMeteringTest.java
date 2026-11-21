package dev.atoms.agent.runtime;



import dev.atoms.agent.model.ModelClient;
import dev.atoms.agent.persistence.Store;
import dev.atoms.agent.skill.Skill;

import dev.atoms.workspace.ProjectContext;

import dev.atoms.agent.pattern.DagExecutor;

import dev.atoms.agent.tool.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用本地 HTTP 桩验证原生工具协议和模型预算。
 *
 * <p>示例：两个并行调用共享一次额度时，仅有一个 HTTP 请求发出。</p>
 */
class ModelMeteringTest {
    @Test void workflowNodeBudgetsSettleIndependentlyAndSurviveResume() {
        var source = dev.atoms.MySqlTestDatabase.create();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        var db = new JdbcTemplate(source);
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("atoms.model.rates[fallback].input", "2")
                .withProperty("atoms.model.rates[fallback].output", "5");
        var store = new Store(db, new TransactionTemplate(new DataSourceTransactionManager(source)),
                new ObjectMapper(), environment);
        String pid = (String) store.create("owner", "测试").get("id");
        String rid = (String) store.queue(pid, "owner", "任务", null, "build", "model").get("id");
        String token = store.claim(rid, "owner");
        store.reserveModelCall(rid, "owner", token, 2, 100, 60, "summary", 70);
        store.settleModelCall(rid, "owner", token, 60, 20, 10, "summary", "fallback");
        assertEquals(0, new java.math.BigDecimal("0.00009").compareTo(db.queryForObject(
                "SELECT usd FROM agent_model_cost WHERE run_id=? AND model='fallback'", java.math.BigDecimal.class, rid)));
        store.pause(rid, "owner");
        store.resume(rid, "owner");
        String resumed = store.claim(rid, "owner");
        assertThrows(IllegalStateException.class,
                () -> store.reserveModelCall(rid, "owner", resumed, 2, 100, 41, "summary", 70));
        store.reserveModelCall(rid, "owner", resumed, 2, 100, 70, "other", 70);
        assertEquals(2, ((Number) ((Map<?, ?>) store.run(rid, "owner").get("usage")).get("modelCalls")).intValue());
    }

    @Test void queuedSkillSurvivesPauseAndResume() {
        var source = dev.atoms.MySqlTestDatabase.create();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        var store = new Store(new JdbcTemplate(source), new TransactionTemplate(new DataSourceTransactionManager(source)),
                new ObjectMapper());
        String pid = (String) store.create("owner", "测试").get("id");
        var skill = new Skill("review", "1.1.0", "", "development", "", List.of("READ_FILE"),
                "critic", 5000, false, true, Map.of(), "逐项检查需求。");
        String rid = (String) store.queue(pid, "owner", "任务", null, "build", "model", "react", skill).get("id");

        store.claim(rid, "owner");
        store.pause(rid, "owner");
        store.resume(rid, "owner");

        assertEquals(skill, store.runSkill(rid));
        assertEquals(skill.reference(), store.run(rid, "owner").get("skill"));
    }

    @Test void tokenReservationsAreAtomicAndSettledCapacitySurvivesResume() {
        var source = dev.atoms.MySqlTestDatabase.create();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        var store = new Store(new JdbcTemplate(source), new TransactionTemplate(new DataSourceTransactionManager(source)),
                new ObjectMapper());
        String pid = (String) store.create("owner", "测试").get("id");
        String rid = (String) store.queue(pid, "owner", "任务", null, "build", "model").get("id");
        String token = store.claim(rid, "owner");
        store.reserveModelCall(rid, "owner", token, 5, 100, 70);
        assertThrows(IllegalStateException.class, () -> store.reserveModelCall(rid, "owner", token, 5, 100, 31));
        store.settleModelCall(rid, "owner", token, 70, 15, 5);
        store.pause(rid, "owner");
        store.resume(rid, "owner");
        String resumed = store.claim(rid, "owner");
        store.reserveModelCall(rid, "owner", resumed, 5, 100, 80);
        var usage = (Map<?, ?>) store.run(rid, "owner").get("usage");
        assertEquals(2, ((Number) usage.get("modelCalls")).intValue());
        assertEquals(100L, ((Number) usage.get("chargedTokens")).longValue());
    }
    @Test void nativeSelectionSendsRegisteredToolsAndParsesFunctionArguments() throws Exception {
        var json = new ObjectMapper();
        var request = new CompletableFuture<com.fasterxml.jackson.databind.JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                request.complete(json.readTree(exchange.getRequestBody()));
                byte[] response = """
                        {"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[
                        {"id":"call_1","type":"function","function":{"name":"READ_FILE","arguments":"{\\"path\\":\\"index.html\\"}"}}
                        ]}}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var model = new ModelClient(json, false, "http://127.0.0.1:" + server.getAddress().getPort(), "fake", "test", 5, 100);
            org.springframework.test.util.ReflectionTestUtils.setField(model, "nativeToolCalls", true);
            var context = new ProjectContext("项目", null, List.of(), Map.of("index.html", "<body>页面</body>"), "逐项检查需求。");
            var state = new AgentLoop.Snapshot(0, 0, Map.of(), false, false, List.of(), null, false);
            var decision = AgentLoop.chooseAction(model, "修改页面", "计划", context, state, new AgentSettings(12, 2, 6), "test");
            assertEquals("READ_FILE", decision.action());
            assertEquals(Map.of("path", "index.html"), decision.arguments());
            var body = request.get(5, TimeUnit.SECONDS);
            assertTrue(body.path("messages").get(0).path("content").asText().contains("逐项检查需求。"));
            assertEquals("required", body.path("tool_choice").asText());
            assertTrue(body.path("tools").findValuesAsText("name").contains("READ_FILE"));
        } finally {
            server.stop(0);
        }
    }

    @Test void toolWorkersShareOneCallLimitAndDagWorkersInheritAccounting() throws Exception {
        var source = dev.atoms.MySqlTestDatabase.create();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        var json = new ObjectMapper();
        var store = new Store(new JdbcTemplate(source), new TransactionTemplate(new DataSourceTransactionManager(source)), json);
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var model = new ModelClient(json, false, "http://127.0.0.1:" + server.getAddress().getPort(), "fake", "test", 5, 100);
            String pid = (String) store.create("owner", "并发预算").get("id");
            String rid = (String) store.queue(pid, "owner", "目标", null, "build", "test").get("id");
            String token = store.claim(rid, "owner");
            var state = new AgentLoop.Snapshot(1, 0, Map.of(), false, false, List.of(), null, false);
            var project = new ProjectContext("预算", null, List.of(), Map.of());
            var messages = List.of(Map.of("role", "user", "content", "test"));
            AgentTool<ToolInvocation> query = new AgentTool<>() {
                public ToolMetadata metadata() { return new ToolMetadata("QUERY", "1", "测试模型调用", "test", 5, false, false, true, ToolMetadata.Scope.VIRTUAL_WORKSPACE); }
                public List<ToolParameter> parameters() { return List.of(); }
                public String execute(ToolInvocation context, Map<String, Object> args) throws Exception { return context.model.completion(messages, "test"); }
            };
            var executor = new ToolExecutor<>(new ToolRegistry<>(List.of(query)));
            var ready = new CountDownLatch(2);
            Callable<Object> call = () -> {
                ready.countDown();
                if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("并发任务未启动");
                try {
                    return
                            new ToolInvocation(store, model, new AgentSettings(12, 2, 6, false, 1), rid, "owner", token, "目标", "计划", project, "test", state).execute(executor, new AgentLoop.Decision("QUERY", "", "查询"));
                } catch (Store.ModelCallLimit e) { return e; }
            };
            var first = pool.submit(call);
            var second = pool.submit(call);
            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(result -> result instanceof ToolResult).count());
            assertEquals(1, results.stream().filter(result -> result instanceof Store.ModelCallLimit).count());
            assertEquals(1, requests.get());
            assertEquals(1, ((Number) ((Map<?, ?>) store.run(rid, "owner").get("usage")).get("modelCalls")).intValue());

            String dagPid = (String) store.create("owner", "DAG预算").get("id");
            String dagRid = (String) store.queue(dagPid, "owner", "目标", null, "build", "test", "dag").get("id");
            String dagToken = store.claim(dagRid, "owner");
            var context = new ToolInvocation(store, model, new AgentSettings(12, 2, 6, false, 2), dagRid, "owner", dagToken,
                    "目标", "计划", project, "test", state);
            new DagExecutor().execute(List.of(
                    new DagExecutor.Node("a", List.of(), dependencies -> model.completion(messages, "test")),
                    new DagExecutor.Node("b", List.of(), dependencies -> model.completion(messages, "test"))), context, 2);
            var usage = (Map<?, ?>) store.run(dagRid, "owner").get("usage");
            assertEquals(2, ((Number) usage.get("modelCalls")).intValue());
            assertEquals(2, ((Number) usage.get("meteredCalls")).intValue());
            assertEquals(16, ((Number) usage.get("promptTokens")).intValue());
            assertEquals(3, requests.get());
        } finally {
            pool.shutdownNow();
            server.stop(0);
        }
    }

    @Test void httpCallsAreReservedBeforeSendAndBudgetSurvivesResume() throws Exception {
        var source = dev.atoms.MySqlTestDatabase.create();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        var json = new ObjectMapper();
        var store = new Store(new JdbcTemplate(source), new TransactionTemplate(new DataSourceTransactionManager(source)), json);
        String pid = (String) store.create("owner", "用量测试").get("id");
        String rid = (String) store.queue(pid, "owner", "test", null, "build", "test").get("id");
        String token = store.claim(rid, "owner");
        var requests = new AtomicInteger();
        var traces = new ConcurrentLinkedQueue<Optional<String>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            traces.add(Optional.ofNullable(exchange.getRequestHeaders().getFirst("traceparent")));
            int request = requests.incrementAndGet();
            String body = request == 1
                    ? "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}"
                    : "{\"error\":{\"code\":\"limited\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(request == 1 ? 200 : 429, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var model = new ModelClient(json, false, "http://127.0.0.1:" + server.getAddress().getPort(), "fake", "test", 5, 100);
            var messages = List.of(Map.of("role", "user", "content", "test"));
            try (var tracking = ModelBudget.track(model, store, rid, "owner", token, 2)) {
                assertEquals("ok", model.completion(messages, "test"));
                assertThrows(IllegalStateException.class, () -> model.completion(messages, "test"));
                assertThrows(Store.ModelCallLimit.class, () -> model.completion(messages, "test"));
            }
            assertEquals(2, requests.get(), "达到限额后不能发出HTTP请求");
            String firstTrace = traces.remove().orElseThrow();
            String secondTrace = traces.remove().orElseThrow();
            String tracePattern = "00-" + rid.replace("-", "") + "-[0-9a-f]{16}-00";
            assertTrue(firstTrace.matches(tracePattern));
            assertTrue(secondTrace.matches(tracePattern));
            assertNotEquals(firstTrace, secondTrace);
            var usage = (Map<String, Object>) store.run(rid, "owner").get("usage");
            assertEquals(2, ((Number) usage.get("modelCalls")).intValue());
            assertEquals(1, ((Number) usage.get("meteredCalls")).intValue());
            assertEquals(12, ((Number) usage.get("promptTokens")).intValue());
            assertEquals(3, ((Number) usage.get("completionTokens")).intValue());
            var models = (List<?>) usage.get("models");
            assertEquals(1, models.size());
            var metered = (Map<?, ?>) models.getFirst();
            assertEquals("test", metered.get("model"));
            assertEquals(12L, ((Number) metered.get("promptTokens")).longValue());
            assertEquals(3L, ((Number) metered.get("completionTokens")).longValue());
            store.pause(rid, "owner");
            store.resume(rid, "owner");
            String resumed = store.claim(rid, "owner");
            try (var tracking = ModelBudget.track(model, store, rid, "owner", resumed, 2)) {
                assertThrows(Store.ModelCallLimit.class, () -> model.completion(messages, "test"));
            }
            assertThrows(Store.StaleExecution.class, () -> store.reserveModelCall(rid, "owner", token, 20));
            assertThrows(Store.StaleExecution.class, () -> store.recordModelUsage(rid, "owner", token, 100, 100));
            // A pooled worker without a run scope must not inherit a previous run's budget.
            assertThrows(IllegalStateException.class, () -> model.completion(messages, "test"));
            assertEquals(3, requests.get());
            assertEquals(Optional.empty(), traces.remove());
        } finally {
            server.stop(0);
        }
    }
}
