package dev.atoms.agent.runtime;

import dev.atoms.agent.approval.TaskApprovalPolicy;

import dev.atoms.agent.pattern.ExecutionPattern;
import dev.atoms.agent.pattern.PatternRegistry;
import dev.atoms.agent.pattern.WorkflowTemplates;

import dev.atoms.agent.model.ModelClient;
import dev.atoms.agent.persistence.Store;

import dev.atoms.workspace.ProjectContext;

import dev.atoms.agent.model.ImageClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 验证暂停恢复和旧执行令牌的隔离。
 *
 * <p>示例：暂停后，旧请求迟到也不能覆盖恢复后的结果。</p>
 */
class PauseResumeTest {
 final ObjectMapper json=new ObjectMapper();
 final String owner="owner";
 Store store;
    DriverManagerDataSource source;
 GenerationService service;
 BlockingModel model;
 String pid,rid;

 @BeforeEach void setup() {
  source=dev.atoms.MySqlTestDatabase.create();
  new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
  store=new Store(new JdbcTemplate(source),new TransactionTemplate(new DataSourceTransactionManager(source)),json);
  pid=(String)store.create(owner,"暂停恢复测试").get("id");
  model=new BlockingModel(json);service=new GenerationService(store,model,mock(ImageClient.class));
  rid=(String)store.queue(pid,owner,"生成页面",null,"build","test-model").get("id");
 }
 @AfterEach void cleanup() {model.release.countDown();service.stop();}
 String status(){return (String)store.run(rid,owner).get("status");}
 void start(){service.submit(rid,pid,owner,"生成页面",null,"build","test-model");}
 void awaitSuccess() {
  assertTimeoutPreemptively(Duration.ofSeconds(5),()->{while(!Set.of("SUCCEEDED","FAILED").contains(status()))Thread.sleep(20);});
  assertEquals("SUCCEEDED",status());
 }

 @Test void sessionArtifactsRemainAvailableAfterCancellationAndAcrossRuns() {
  String token = store.claim(rid, owner);
  store.writeWorkspaceFile(rid, owner, token, "artifacts/report.md", "# Findings");
  assertEquals("# Findings", store.workspaceFiles(pid, owner).get("artifacts/report.md"));
  assertThrows(IllegalArgumentException.class,
          () -> store.writeWorkspaceFile(rid, owner, token, "../outside", "blocked"));
  service.cancel(rid, owner);
  assertThrows(Store.StaleExecution.class,
          () -> store.writeWorkspaceFile(rid, owner, token, "artifacts/report.md", "stale"));
  String next = (String) store.queue(pid, owner, "读取上次的报告", null, "build", "test-model").get("id");
  assertEquals("# Findings", store.workspaceArtifactsForRun(next, owner).get("artifacts/report.md"));
  assertTrue(store.workspaceArtifactsForRun(next, "another-owner").isEmpty());
 }

 @Test void lateProviderResultCannotOverwriteResumedRun()throws Exception {
  start();assertTrue(model.entered.await(5,TimeUnit.SECONDS));
  assertEquals("PAUSED",service.pause(rid,owner).get("status"));
  assertNull(store.project(pid,owner).get("current_version"));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.queue(pid,owner,"另一个需求",null,"build","test-model"));
  // Resume while the original provider deliberately ignores cancellation.
  service.resume(rid,owner);awaitSuccess();
  String version=(String)store.project(pid,owner).get("current_version");
  assertTrue(store.versionFiles(version).get("index.html").contains("new result"));
  assertEquals(1,model.plans.get(),"恢复复用已保存计划");
  model.release.countDown();assertTrue(model.returned.await(5,TimeUnit.SECONDS));
  service.executor.shutdown();assertTrue(service.executor.awaitTermination(5,TimeUnit.SECONDS));
  assertEquals("SUCCEEDED",status());
  assertEquals(1,store.versions(pid).size(),"旧请求返回后不能多写一个版本");
  assertEquals(version,store.project(pid,owner).get("current_version"));
 }

 @Test void checkpointedFilesResumeAfterServiceRestartWithoutModelCalls()throws Exception {
  String token=store.claim(rid,owner);
  store.plan(rid,owner,token,"已完成的计划");
  store.checkpoint(rid,owner,token,Map.of("index.html","<body>checkpoint result</body>"),"",0);
  service.pause(rid,owner);
  service.stop();
  store=new Store(new JdbcTemplate(source),new TransactionTemplate(new DataSourceTransactionManager(source)),json);
  service=new GenerationService(store,model,mock(ImageClient.class));
  service.recover();
  assertEquals("PAUSED",status());
  service.resume(rid,owner);awaitSuccess();
  assertEquals(0,model.plans.get());assertEquals(0,model.generations.get());
  assertTrue(store.versionFiles((String)store.project(pid,owner).get("current_version")).get("index.html").contains("checkpoint result"));
 }

 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
 void templateRunResumesFromCompletedNodeWithoutReplanning(boolean approved,
         @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
         throws Exception {
  java.nio.file.Files.writeString(directory.resolve("review.yaml"), """
          name: review
          defaults:
            budget_agent_max: 6000
            require_approval: true
          nodes:
            - id: requirements
              type: simple
            - id: summary
              type: simple
              metadata:
                query: 汇总
          edges:
            - from: requirements
              to: summary
          """);
  java.nio.file.Files.writeString(directory.resolve("child.yml"), """
          name: child
          version: 2.0.0
          extends: [review]
          nodes:
            - id: summary
              type: dag
              budget_max: 3000
              tools_allowlist: [READ_FILE, LIST_FILES]
              on_fail:
                retry: 1
              metadata:
                query: 汇总子模板
                execution: sequential
                role_tools:
                  reviewer: [LIST_FILES]
                tasks:
                  - id: collect
                    query: 收集验收条件
                  - id: combine
                    role: reviewer
                    query: 汇总验收条件
          """);
  var catalog = new WorkflowTemplates(directory.toString());
  var template = catalog.route("child", "2.0.0", true);
  assertEquals(template, catalog.route("child@2.0.0", null, false));
  assertNull(catalog.route("child", "9.0.0", false));
  assertThrows(IllegalArgumentException.class, () -> catalog.route("child", "9.0.0", true));
  assertEquals(6000, template.budgetFor(template.nodes().getFirst()));
  assertEquals(3000, template.budgetFor(template.nodes().getLast()));
  service.cancel(rid, owner);
  pid = (String) store.create(owner, "模板恢复测试").get("id");
  rid = (String) store.queue(pid, owner, "生成页面", null, "build", "test-model",
          "reflection", null, template).get("id");
  assertEquals("AWAITING_APPROVAL", status());
  assertThrows(Store.StaleExecution.class, () -> store.claim(rid, owner));
  assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> store.resume(rid, owner));
  store.recoverJobs();
  assertEquals("AWAITING_APPROVAL", status());
  assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> store.decideApproval(rid, "other", true));
  store.decideApproval(rid, owner, true);
  String token = store.claim(rid, owner);
  store.dagNode(rid, owner, token, "template.requirements", List.of(), "SUCCEEDED", "已保存的需求", 1);
  store.dagNode(rid, owner, token, "template.summary.collect", List.of(), "SUCCEEDED", "已保存的验收条件", 1);
  store.pause(rid, owner);
  service.stop();
  java.nio.file.Files.delete(directory.resolve("review.yaml"));
  java.nio.file.Files.delete(directory.resolve("child.yml"));
  var calls = new CopyOnWriteArrayList<String>();
  model = new BlockingModel(json) {
   @Override public String analyze(String prompt, ProjectContext context, String aspect, String instructions,
                                  String selectedModel) throws java.io.IOException {
    calls.add(aspect);
    if (aspect.startsWith("role.tools.")) {
     assertTrue(instructions.contains("LIST_FILES"));
     assertFalse(instructions.contains("READ_FILE"));
     return "{\"action\":\"LIST_FILES\",\"arguments\":{}}";
    }
    if (calls.size() == 2) throw new java.io.IOException("模拟暂时网络故障");
    return instructions.contains("已保存的需求") && instructions.contains("汇总子模板")
            && instructions.contains("已保存的验收条件") && instructions.contains("分析角色：reviewer")
            && instructions.contains("当前工作区文件")
            ? "已汇总" : "缺少依赖或覆盖内容";
   }
   @Override public ModelClient.Review review(String prompt, ProjectContext context, Map<String, String> candidate,
                                              String selectedModel) {
    return new ModelClient.Review(true, "通过");
   }
  };
  model.generations.set(1);
  service = new GenerationService(store, model, mock(ImageClient.class));
  org.springframework.test.util.ReflectionTestUtils.setField(service, "toolApprovalPolicy",
          new TaskApprovalPolicy(json, "", "dev", 0, "", "LIST_FILES"));
  service.recover();
  service.resume(rid, owner);
  assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
   while (!"AWAITING_APPROVAL".equals(status())) Thread.sleep(20);
  });
  var pending = store.run(rid, owner);
  assertEquals("summary.combine", pending.get("approvalNode"));
  assertEquals("LIST_FILES", pending.get("approvalTool"));
  assertEquals(1, calls.size(), "工具选择完成后应等待审批");
  service.approve(rid, owner, approved, (String) pending.get("approvalId"));
  if (!approved) {
   assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
    while (!"FAILED".equals(status())) Thread.sleep(20);
   });
   assertEquals("节点工具调用未获批准", store.run(rid, owner).get("error"));
   assertEquals(1, calls.size(), "拒绝后应停止该节点");
   return;
  }
  awaitSuccess();
  assertTrue(calls.getFirst().startsWith("role.tools."));
  assertEquals(List.of("summary.combine", "summary.combine"), calls.subList(1, calls.size()));
  assertEquals(0, model.plans.get());
  assertTrue(store.run(rid, owner).get("plan").toString().contains("已汇总"));
  String version = (String) store.project(pid, owner).get("current_version");
  String rejected = (String) store.queue(pid, owner, "另一项任务", version, "build", "test-model",
          "reflection", null, template).get("id");
  assertEquals("FAILED", service.approve(rejected, owner, false).get("status"));
  assertDoesNotThrow(() -> store.queue(pid, owner, "可以提交新任务", version, "build", "test-model"));
 }

 @Test void swarmReplansUsingLateInputAndResumesNewRoleWithoutRepeatingLead(
         @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
  java.nio.file.Files.writeString(directory.resolve("replan.yml"), """
          name: replan-swarm
          version: 1.0.0
          nodes:
            - id: team
              type: swarm
          """);
  var template = new WorkflowTemplates(directory.toString()).route("replan-swarm", null, true);
  service.cancel(rid, owner);
  service.stop();
  String prompt = "构建验收看板";
  rid = (String) store.queue(pid, owner, prompt, null, "build", "test-model",
          "reflection", null, template).get("id");
  var calls = new CopyOnWriteArrayList<String>();
  var pauseOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
  model = new BlockingModel(json) {
   @Override public String analyze(String prompt, ProjectContext context, String aspect,
                                   String instructions, String selectedModel) {
    calls.add(aspect);
    if (aspect.endsWith(".swarm.plan")) return "[{\"id\":\"initial\",\"query\":\"分析接口\",\"role\":\"architect\"}]";
    if (aspect.endsWith(".swarm.initial")) return "首轮接口结果";
    if (aspect.endsWith(".swarm.review")) {
     store.addSwarmHumanMessage(rid, owner, "加入键盘验证");
     return "缺少可访问性证据";
    }
    if (aspect.endsWith(".swarm.replan")) {
     assertTrue(instructions.contains("加入键盘验证"));
     assertTrue(instructions.contains("缺少可访问性证据"));
     return """
             [{"id":"a11y","query":"核对键盘","depends_on":["initial"],"role":"reviewer"},
              {"id":"initial","query":"独立复核接口","depends_on":["initial"],"role":"auditor"}]
             """;
    }
    if (aspect.endsWith(".swarm.followup.a11y")) {
     assertTrue(instructions.contains("首轮接口结果"));
     assertTrue(instructions.contains("分析角色：reviewer"));
     if (pauseOnce.getAndSet(false)) store.pause(rid, owner);
     return "补充验收：支持键盘访问";
    }
    if (aspect.endsWith(".swarm.followup.initial")) {
     assertTrue(instructions.contains("首轮接口结果"));
     assertTrue(instructions.contains("分析角色：auditor"));
     return "重派复核确认接口";
    }
    if (aspect.endsWith(".swarm.replan.round-2")) {
     assertTrue(instructions.contains("重派复核确认接口"));
     return "[{\"id\":\"initial\",\"query\":\"复检修改后的方案\",\"depends_on\":[\"initial\"],\"role\":\"tester\"}]";
    }
    if (aspect.endsWith(".swarm.followup.round-2.initial")) {
     assertTrue(instructions.contains("重派复核确认接口"));
     return "第二轮复检通过";
    }
    if (aspect.endsWith(".swarm.replan.round-3")) return "[]";
    if (aspect.endsWith(".swarm.synthesis")) {
     assertTrue(instructions.contains("补充验收：支持键盘访问"));
     assertTrue(instructions.contains("第二轮复检通过"));
     assertFalse(instructions.contains("重派复核确认接口"));
    }
    return "完成汇总";
   }

   @Override public ModelClient.Review review(String prompt, ProjectContext context, Map<String, String> files, String model) {
    return new ModelClient.Review(true, "通过");
   }
  };
  model.generations.set(1);
  service = new GenerationService(store, model, mock(ImageClient.class));
  org.springframework.test.util.ReflectionTestUtils.setField(service, "swarmReplanning", true);
  org.springframework.test.util.ReflectionTestUtils.setField(service, "swarmReplanRounds", 4);
  service.execute(rid, pid, owner, prompt, null, "build", "test-model");
  assertEquals("PAUSED", status());
  store.resume(rid, owner);
  org.springframework.test.util.ReflectionTestUtils.setField(service, "swarmReplanning", false);
  org.springframework.test.util.ReflectionTestUtils.setField(service, "swarmReplanRounds", 1);
  service.execute(rid, pid, owner, prompt, null, "build", "test-model");
  assertEquals("SUCCEEDED", status());
  assertEquals(1, calls.stream().filter(aspect -> aspect.endsWith(".swarm.replan")).count());
  assertEquals(2, calls.stream().filter(aspect -> aspect.endsWith(".swarm.followup.a11y")).count());
  var board = json.valueToTree(store.run(rid, owner).get("swarmBoard")).get(0);
  assertEquals("SUCCEEDED", board.path("replanning").asText());
  var followup = board.path("tasks").get(1);
  assertTrue(followup.path("followup").asBoolean());
  assertEquals("initial", followup.path("dependencies").get(0).asText());
  assertEquals("SUCCEEDED", followup.path("status").asText());
  assertEquals(2, board.path("tasks").get(3).path("round").asInt());
  assertEquals("tester", board.path("tasks").get(3).path("role").asText());
  assertTrue(calls.stream().noneMatch(aspect -> aspect.endsWith(".swarm.replan.round-4")));
 }

 @Test void swarmRolesChooseActionsAndResumeWithoutRepeatingCompletedSteps(
         @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
  java.nio.file.Files.writeString(directory.resolve("loop.yml"), """
          name: swarm-loop
          nodes:
            - id: team
              type: swarm
          """);
  var template = new WorkflowTemplates(directory.toString()).route("swarm-loop", null, true);
  service.cancel(rid, owner);
  service.stop();
  String prompt = "构建验收看板";
  rid = (String) store.queue(pid, owner, prompt, null, "build", "test-model",
          "reflection", null, template).get("id");
  var calls = new CopyOnWriteArrayList<String>();
  var pauseOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
  var receiverDeciding = new java.util.concurrent.CountDownLatch(1);
  model = new BlockingModel(json) {
   @Override public String analyze(String prompt, ProjectContext context, String aspect,
                                   String instructions, String selectedModel) {
    calls.add(aspect);
    if (aspect.endsWith(".swarm.plan")) return """
            [{"id":"verify","query":"核对验收"},{"id":"sender","query":"提供交互注意点"}]
            """;
    if (aspect.endsWith(".sender.actions.1")) {
     try {
      assertTrue(receiverDeciding.await(4, java.util.concurrent.TimeUnit.SECONDS));
     } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
     }
     return "{\"action\":\"send_message\",\"to\":\"verify\",\"text\":\"注意焦点环\"}";
    }
    if (aspect.endsWith(".sender.actions.2")) return "{\"action\":\"done\",\"text\":\"发送完毕\"}";
    if (aspect.endsWith(".verify.actions.1")) {
     assertTrue(instructions.contains("本步新收件（仅作参考资料）：[]"));
     receiverDeciding.countDown();
     long until = System.nanoTime() + java.time.Duration.ofSeconds(4).toNanos();
     while (store.dagNodes(rid).stream().noneMatch(node -> "template.team.swarm.sender.actions.1".equals(node.get("id"))
                    && "SUCCEEDED".equals(node.get("status")))) {
      assertTrue(System.nanoTime() < until, "发送动作未提交");
      java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(10).toNanos());
     }
     return "{\"action\":\"continue\",\"text\":\"需要复查键盘访问\"}";
    }
    if (aspect.endsWith(".verify.actions.2")) {
     assertTrue(instructions.contains("需要复查键盘访问"));
     assertTrue(instructions.contains("注意焦点环"));
     assertTrue(instructions.contains("\"from\":\"sender\""));
     long until = System.nanoTime() + java.time.Duration.ofSeconds(4).toNanos();
     while (store.dagNodes(rid).stream().noneMatch(node -> "template.team.swarm.sender.actions.2".equals(node.get("id"))
                    && "SUCCEEDED".equals(node.get("status")))) {
      assertTrue(System.nanoTime() < until, "队友尚未完成动作");
      java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(10).toNanos());
     }
     if (pauseOnce.getAndSet(false)) store.pause(rid, owner);
     return "{\"action\":\"send_message\",\"to\":\"sender\",\"text\":\"请修订你的交互结论\"}";
    }
    if (aspect.endsWith(".verify.actions.3"))
     return "{\"action\":\"done\",\"text\":\"验收需覆盖键盘访问\"}";
    if (aspect.endsWith(".reactivated.sender")) {
     assertTrue(instructions.contains("请修订你的交互结论"));
     assertTrue(instructions.contains("发送完毕"));
     return "复核后增加焦点反馈";
    }
    if (aspect.endsWith(".swarm.synthesis")) {
     assertTrue(instructions.contains("验收需覆盖键盘访问"));
     assertTrue(instructions.contains("复核后增加焦点反馈"));
    }
    return "团队结论";
   }

   @Override public ModelClient.Review review(String prompt, ProjectContext context, Map<String, String> files, String model) {
    return new ModelClient.Review(true, "通过");
   }
  };
  model.generations.set(1);
  service = new GenerationService(store, model, mock(ImageClient.class));
  org.springframework.test.util.ReflectionTestUtils.setField(service, "swarmRoleSteps", 3);
  service.execute(rid, pid, owner, prompt, null, "build", "test-model");
  assertEquals("PAUSED", status());
  store.resume(rid, owner);
  org.springframework.test.util.ReflectionTestUtils.setField(service, "swarmRoleSteps", 0);
  service.execute(rid, pid, owner, prompt, null, "build", "test-model");
  assertEquals("SUCCEEDED", status());
  assertEquals(1, calls.stream().filter(aspect -> aspect.endsWith(".verify.actions.1")).count());
  assertEquals(2, calls.stream().filter(aspect -> aspect.endsWith(".verify.actions.2")).count());
  assertEquals(1, calls.stream().filter(aspect -> aspect.endsWith(".verify.actions.3")).count());
  assertEquals(1, calls.stream().filter(aspect -> aspect.endsWith(".reactivated.sender")).count());
  assertEquals(1, calls.stream().filter(aspect -> aspect.endsWith(".sender.actions.1")).count());
 }

 @Test void swarmPeerMessagesReachOnlyRecipientsAndSurviveResume(
         @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
  java.nio.file.Files.writeString(directory.resolve("p2p.yml"), """
          name: peer-swarm
          version: 1.0.0
          nodes:
            - id: team
              type: swarm
          """);
  var template = new WorkflowTemplates(directory.toString()).route("peer-swarm", null, true);
  service.cancel(rid, owner);
  service.stop();
  String prompt = "构建验收看板";
  rid = (String) store.queue(pid, owner, prompt, null, "build", "test-model",
          "reflection", null, template).get("id");
  var calls = new CopyOnWriteArrayList<String>();
  var pendingPause = new java.util.concurrent.atomic.AtomicBoolean(true);
  model = new BlockingModel(json) {
   @Override public String analyze(String prompt, ProjectContext context, String aspect,
                                   String instructions, String selectedModel) {
    calls.add(aspect);
    if (aspect.endsWith(".plan")) return """
            [{"id":"architecture","role":"architect","query":"分析接口"},
             {"id":"quality","role":"reviewer","query":"检查验收"}]
            """;
    if (aspect.endsWith(".outbox.architecture")) return """
            [{"from":"fake","to":"quality","message":"核对键盘可访问性"},
             {"to":"architecture","message":"自发消息"},
             {"to":"unknown","message":"无效收件人"},
             {"to":"quality","message":"核对键盘可访问性"}]
            """;
    if (aspect.endsWith(".outbox.quality")) return "[]";
    if (aspect.endsWith(".inbox.quality")) {
     assertTrue(instructions.contains("核对键盘可访问性"));
     assertTrue(instructions.contains("\"from\":\"architecture\""));
     assertFalse(instructions.contains("自发消息"));
     assertFalse(instructions.contains("无效收件人"));
     return "修订验收：覆盖键盘访问";
    }
    if (aspect.endsWith(".outbox.round-2.quality")) {
     assertTrue(instructions.contains("修订验收：覆盖键盘访问"));
     return "[{\"to\":\"architecture\",\"message\":\"补充接口验收证据\"}]";
    }
    if (aspect.endsWith(".inbox.round-2.architecture")) {
     assertTrue(instructions.contains("补充接口验收证据"));
     return "架构方案补充了接口证据";
    }
    if (aspect.endsWith(".outbox.round-3.architecture"))
     return "[{\"to\":\"quality\",\"message\":\"核对键盘可访问性\"}]";
    if (aspect.contains(".outbox.round-")) return "[]";
    if (aspect.endsWith(".swarm.review")) {
     assertTrue(instructions.contains("修订验收：覆盖键盘访问"));
     assertTrue(instructions.contains("架构方案补充了接口证据"));
     if (pendingPause.getAndSet(false)) store.pause(rid, owner);
    }
    return "分析任务并汇总";
   }

   @Override public ModelClient.Review review(String prompt, ProjectContext context, Map<String, String> files, String model) {
    return new ModelClient.Review(true, "通过");
   }
  };
  model.generations.set(1);
  service = new GenerationService(store, model, mock(ImageClient.class));
  org.springframework.test.util.ReflectionTestUtils.setField(service, "swarmPeerMessaging", true);
  org.springframework.test.util.ReflectionTestUtils.setField(service, "swarmPeerRounds", 4);
  service.execute(rid, pid, owner, prompt, null, "build", "test-model");
  assertEquals("PAUSED", status());
  store.resume(rid, owner);
  org.springframework.test.util.ReflectionTestUtils.setField(service, "swarmPeerMessaging", false);
  org.springframework.test.util.ReflectionTestUtils.setField(service, "swarmPeerRounds", 1);
  service.execute(rid, pid, owner, prompt, null, "build", "test-model");
  assertEquals("SUCCEEDED", status());
  assertEquals(6, calls.stream().filter(aspect -> aspect.contains(".outbox.")).count());
  assertEquals(1, calls.stream().filter(aspect -> aspect.endsWith(".inbox.round-2.architecture")).count());
  assertTrue(calls.stream().noneMatch(aspect -> aspect.contains(".inbox.round-3.")));
  var tasks = json.valueToTree(store.run(rid, owner).get("swarmBoard")).get(0).path("tasks");
  assertEquals(3, tasks.get(0).path("peerRounds").size());
  assertEquals(1, calls.stream().filter(aspect -> aspect.endsWith(".inbox.quality")).count());
  assertTrue(calls.stream().noneMatch(aspect -> aspect.endsWith(".inbox.architecture")));
 }

 @Test void swarmLeadReadsLateInputBeforeSynthesisAndRejectsFurtherMessages(
         @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
  java.nio.file.Files.writeString(directory.resolve("swarm.yml"), """
          name: swarm-test
          version: 1.0.0
          nodes:
            - id: team
              type: swarm
          """);
  var template = new WorkflowTemplates(directory.toString()).route("swarm-test", null, true);
  service.cancel(rid, owner);
  service.stop();
  rid = (String) store.queue(pid, owner, "构建接口看板", null, "build", "test-model",
          "reflection", null, template).get("id");
  store.addSwarmHumanMessage(rid, owner, "先核对接口约束");
  assertThrows(org.springframework.web.server.ResponseStatusException.class,
          () -> store.addSwarmHumanMessage(rid, "outsider", "foreign"));
  var calls = new CopyOnWriteArrayList<String>();
  model = new BlockingModel(json) {
   @Override public String analyze(String prompt, ProjectContext context, String aspect,
                                   String instructions, String selectedModel) {
    calls.add(aspect);
    if (aspect.endsWith(".plan")) {
     assertTrue(instructions.contains("先核对接口约束"));
     return "[{\"id\":\"worker\",\"query\":\"分析接口\",\"role\":\"architect\"}]";
    }
    if (aspect.endsWith(".worker")) {
     store.addSwarmHumanMessage(rid, owner, "另外考虑无障碍");
     return "接口分析";
    }
    if (aspect.endsWith(".review")) assertTrue(instructions.contains("另外考虑无障碍"));
    if (aspect.endsWith(".synthesis")) {
     assertTrue(instructions.contains("另外考虑无障碍"));
     var exception = assertThrows(org.springframework.web.server.ResponseStatusException.class,
             () -> store.addSwarmHumanMessage(rid, owner, "太迟的补充"));
     assertEquals(409, exception.getStatusCode().value());
    }
    return "复核汇总";
   }

   @Override public ModelClient.Review review(String prompt, ProjectContext context, Map<String, String> files, String model) {
    return new ModelClient.Review(true, "通过");
   }
  };
  model.generations.set(1);
  service = new GenerationService(store, model, mock(ImageClient.class));
  service.execute(rid, pid, owner, "构建接口看板", null, "build", "test-model");
  assertEquals("SUCCEEDED", status());
  assertEquals(false, store.run(rid, owner).get("swarmAcceptingInput"));
  assertTrue(calls.stream().anyMatch(aspect -> aspect.endsWith(".synthesis")));
 }

 @Test void supervisorAndRoleRecallOnlyTheirSuccessfulProjectExamples(
         @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
  java.nio.file.Files.writeString(directory.resolve("team.yml"), """
          name: team
          version: 1.0.0
          nodes:
            - id: lead
              type: supervisor
          """);
  var template = new WorkflowTemplates(directory.toString()).route("team", null, true);
  service.cancel(rid, owner);
  service.stop();
  var seen = new CopyOnWriteArrayList<java.util.Map.Entry<String, String>>();
  model = new BlockingModel(json) {
   @Override public String analyze(String prompt, ProjectContext context, String aspect, String instructions, String selectedModel) {
    seen.add(java.util.Map.entry(aspect, instructions));
    if (aspect.endsWith(".plan")) return """
            [{"id":"architecture","role":"architect","query":"分析接口约束"},
             {"id":"quality","role":"reviewer","query":"分析接口约束"}]
            """;
    if (aspect.endsWith(".architecture")) return "架构缓存经验";
    if (aspect.endsWith(".quality")) return "验收缓存经验";
    return "汇总";
   }

   @Override public Map<String, String> generate(String prompt, String plan, ProjectContext context, String failure, String model) {
    return Map.of("index.html", "<body>version " + generations.incrementAndGet() + "</body>");
   }

   @Override public ModelClient.Review review(String prompt, ProjectContext context, Map<String, String> files, String model) {
    return new ModelClient.Review(true, "通过");
   }
  };
  service = new GenerationService(store, model, mock(ImageClient.class));
  org.springframework.test.util.ReflectionTestUtils.setField(service, "roleMemoryEnabled", true);
  String prompt = "生成接口约束看板";
  String first = (String) store.queue(pid, owner, prompt, null, "build", "test-model",
          "reflection", null, template).get("id");
  service.execute(first, pid, owner, prompt, null, "build", "test-model");
  assertEquals("SUCCEEDED", store.run(first, owner).get("status"));
  String current = (String) store.project(pid, owner).get("current_version");
  String failed = (String) store.queue(pid, owner, prompt, current, "build", "test-model").get("id");
  String token = store.claim(failed, owner);
  store.rememberAgentRun(failed, owner, token, "failed", "agent:architect", "分析接口约束", "失败结果不能复用");
  store.fail(failed, pid, "test", token);
  String foreignProject = (String) store.create(owner, "另一项目").get("id");
  String foreign = (String) store.queue(foreignProject, owner, "foreign", null, "chat", "test-model").get("id");
  String foreignToken = store.claim(foreign, owner);
  store.rememberAgentRun(foreign, owner, foreignToken, "foreign", "agent:architect", "分析接口约束", "跨项目不能复用");
  store.finishReply(foreign, foreignProject, "done", "chat", "test-model", null, null, "foreign", foreignToken);

  seen.clear();
  String next = (String) store.queue(pid, owner, prompt, current, "build", "test-model",
          "reflection", null, template).get("id");
  service.execute(next, pid, owner, prompt, current, "build", "test-model");
  assertEquals("SUCCEEDED", store.run(next, owner).get("status"));
  String plan = seen.stream().filter(call -> call.getKey().endsWith(".plan")).findFirst().orElseThrow().getValue();
  String architect = seen.stream().filter(call -> call.getKey().endsWith(".architecture")).findFirst().orElseThrow().getValue();
  String reviewer = seen.stream().filter(call -> call.getKey().endsWith(".quality")).findFirst().orElseThrow().getValue();
  assertTrue(plan.contains("相似历史经验") && plan.contains("architect"));
  assertTrue(architect.contains("架构缓存经验"));
  assertFalse(architect.contains("验收缓存经验"));
  assertFalse(architect.contains("失败结果不能复用"));
  assertFalse(architect.contains("跨项目不能复用"));
  assertTrue(reviewer.contains("验收缓存经验"));
  assertFalse(reviewer.contains("架构缓存经验"));
 }

 @Test void cognitiveStrategiesUseSeparateStagesAndDegradeBelowThreshold(
         @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
  java.nio.file.Files.writeString(directory.resolve("reflection.yaml"), """
          name: review
          nodes:
            - id: thorough
              type: cognitive
              strategy: debate
              budget_max: 5999
            - id: brief
              type: cognitive
              strategy: debate
              budget_max: 2999
              depends_on: [thorough]
            - id: full
              type: cognitive
              strategy: debate
              budget_max: 6000
              depends_on: [brief]
            - id: steps
              type: cognitive
              strategy: chain_of_thought
              budget_max: 4000
              depends_on: [full]
            - id: tree
              type: cognitive
              strategy: tree_of_thoughts
              depends_on: [steps]
            - id: fallback
              type: cognitive
              strategy: tree_of_thoughts
              depends_on: [tree]
              on_fail:
                degrade_to: react
            - id: coordinate
              type: cognitive
              strategy: react
              depends_on: [fallback]
              tools_allowlist: [LIST_FILES]
              on_fail:
                escalate_to: supervisor
          """);
  var template = new WorkflowTemplates(directory.toString()).require("review");
  pid = (String) store.create(owner, "认知模板测试").get("id");
  rid = (String) store.queue(pid, owner, "生成页面", null, "build", "test-model", "reflection", null, template).get("id");
  service.stop();
  var calls = new CopyOnWriteArrayList<String>();
  model = new BlockingModel(json) {
   @Override public String analyze(String prompt, ProjectContext context, String aspect, String instructions,
                                  String selectedModel) throws java.io.IOException {
    calls.add(aspect);
    if (aspect.equals("template.tree.tree.expand.0")) return """
            [{"proposal":"应被剪除","score":0.1,"terminal":false},
             {"proposal":"继续细化","score":0.8,"terminal":false}]
            """;
    if (aspect.equals("template.tree.tree.expand.2")) return """
            [{"proposal":"最终候选","score":0.95,"terminal":true}]
            """;
    if (aspect.equals("template.tree.tree.synthesis"))
     return instructions.contains("最终候选") && !instructions.contains("应被剪除") ? "剪枝和汇总完成" : "剪枝失败";
    if (aspect.equals("template.fallback.tree.expand.0")) throw new java.io.IOException("暂时网络错误");
    if (aspect.equals("template.coordinate.tools"))
     return instructions.contains("LIST_FILES") && !instructions.contains("READ_FILE")
             ? "{\"action\":\"LIST_FILES\",\"arguments\":{}}" : "{\"action\":\"GENERATE\",\"arguments\":{}}";
    if (aspect.equals("coordinate")) throw new java.io.IOException("暂时网络错误");
    if (aspect.equals("template.coordinate.supervisor.plan") && instructions.contains("当前工作区文件")) return """
            [{"id":"requirements","query":"识别需求"},
             {"id":"verify","query":"制定验收","depends_on":["requirements"]}]
            """;
    if (aspect.equals("template.coordinate.supervisor.verify"))
     return instructions.contains("template.coordinate.supervisor.requirements") ? "子任务拿到依赖" : "依赖缺失";
    return aspect;
   }
   @Override public ModelClient.Review review(String prompt, ProjectContext context, Map<String, String> candidate,
                                              String selectedModel) {
    return new ModelClient.Review(true, "通过");
   }
  };
  model.generations.set(1);
  service = new GenerationService(store, model, mock(ImageClient.class));
  start();
  awaitSuccess();
  assertEquals(List.of("template.thorough.draft", "template.thorough.critique", "template.thorough.revision", "brief"),
          calls.subList(0, 4));
  for (int round = 0; round <= 3; round++)
   for (int perspective = 0; perspective < 3; perspective++)
    assertEquals("template.full.debate." + round + "." + perspective, calls.get(4 + round * 3 + perspective));
  assertEquals(List.of("template.full.debate.synthesis", "steps", "template.tree.tree.expand.0",
          "template.tree.tree.expand.2", "template.tree.tree.synthesis", "template.fallback.tree.expand.0",
          "fallback", "template.coordinate.tools", "coordinate", "template.coordinate.supervisor.plan", "template.coordinate.supervisor.requirements",
          "template.coordinate.supervisor.verify", "template.coordinate.supervisor.synthesis"),
          calls.subList(16, calls.size()));
  assertTrue(store.run(rid, owner).get("plan").toString().contains("剪枝和汇总完成"));
  assertTrue(store.dagNodes(rid).stream().anyMatch(node -> "template.fallback.degraded".equals(node.get("id"))
          && "react".equals(node.get("output"))));
  assertTrue(store.dagNodes(rid).stream().anyMatch(node -> "template.coordinate.verify".equals(node.get("id"))
          && "子任务拿到依赖".equals(node.get("output"))));
  assertTrue(store.dagNodes(rid).stream().anyMatch(node -> "template.coordinate.escalated".equals(node.get("id"))
          && "supervisor".equals(node.get("output"))));
 }

 @Test void queuedPausePersistsAndResumeDoesNotDuplicateUserMessage()throws Exception {
  service.pause(rid,owner);assertEquals("PAUSED",status());
  assertEquals(1,((List<?>)store.detail(pid,owner).get("messages")).size());
  model.generations.set(1); // This run should return immediately.
  service.resume(rid,owner);awaitSuccess();
  var messages=(List<Map<String,Object>>)store.detail(pid,owner).get("messages");
  assertEquals(1,messages.stream().filter(m->"user".equals(m.get("role"))).count());
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.pause(rid,owner));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.resume(rid,owner));
 }

 @Test void staleExecutionCannotFailOrCommitAfterPause() {
  String token=store.claim(rid,owner);service.pause(rid,owner);
  store.fail(rid,pid,"旧请求中断",token);
  assertEquals("PAUSED",status());
  store.resume(rid,owner);
  String resumedToken=store.claim(rid,owner);
  assertThrows(Store.StaleExecution.class,()->store.pause(rid,owner,token));
  assertDoesNotThrow(()->store.requireExecution(rid,resumedToken));
  service.pause(rid,owner);
  assertThrows(Store.StaleExecution.class,()->store.save(pid,owner,null,Map.of("index.html","<body>late</body>"),"late",rid,token));
  assertThrows(Store.StaleExecution.class,()->store.stage(rid,owner,token,"GENERATING"));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.resume(rid,"another-owner"));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.pause(rid,"another-owner"));
 }

 @Test void restartAutomaticallyDispatchesActiveRunFromItsCheckpoint() {
  String token = store.claim(rid, owner);
  store.stage(rid, owner, token, "GENERATING");
  store.plan(rid, owner, token, "已完成的计划");
  store.checkpoint(rid, owner, token, Map.of("index.html", "<body>recovered</body>"), "", 0);
  store.reserveModelCall(rid, owner, token, 10);
  store.recoverJobs();
  assertEquals("QUEUED", status());
  assertEquals(rid, store.project(pid, owner).get("active_run"));
  assertThrows(Store.StaleExecution.class, () -> store.requireExecution(rid, token));

  service.recover();
  awaitSuccess();
  assertEquals(0, model.plans.get());
  assertEquals(0, model.generations.get());
  var usage = (Map<?, ?>) store.run(rid, owner).get("usage");
  assertEquals(1, ((Number) usage.get("modelCalls")).intValue());
  String version = (String) store.project(pid, owner).get("current_version");
  assertEquals("<body>recovered</body>", store.versionFiles(version).get("index.html"));
 }

 @Test void fullWorkerQueueLeavesRunAvailableForDispatch() throws Exception {
  var release = new CountDownLatch(1);
  try {
   for (int i = 0; i < 22; i++) {
    service.executor.submit(() -> {
     release.await();
     return null;
    });
   }
   model.generations.set(1);
   start();
   assertEquals("QUEUED", status());
   assertEquals(rid, store.pendingRuns(1).getFirst().id());
   service.recover();
   release.countDown();
   awaitSuccess();
  } finally {
   release.countDown();
  }
 }

 @Test void pausedChatCannotAppendALateReply() {
  store.fail(rid,pid,"switch to chat");
  rid=(String)store.queue(pid,owner,"解释当前代码",null,"chat","test-model").get("id");
  String token=store.claim(rid,owner);service.pause(rid,owner);
  assertThrows(Store.StaleExecution.class,()->store.finishReply(rid,pid,"late reply","chat","test-model",null,null,"",token));
  assertEquals("PAUSED",status());
 }

 /**
  * 用阻塞请求模拟暂停时仍在运行的模型调用。
  *
  * <p>示例：等待测试释放请求，再验证旧执行结果被拒绝。</p>
  */
 static class BlockingModel extends ModelClient {
  final AtomicInteger plans=new AtomicInteger(),generations=new AtomicInteger();
  final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);
  BlockingModel(ObjectMapper json){super(json,false,"http://unused","test-key","test-model",300,16000);}
  @Override public ModelClient.Decision decide(String prompt, String plan, ProjectContext context, ModelClient.ActionInput input, String selectedModel) {
   var choice = PatternRegistry.offlineDecision(new ExecutionPattern.Progress(input.generations(),
           input.hasFiles(), input.validated(), input.reviewed(), input.observations()));
   return new ModelClient.Decision(choice.action(), choice.path(), choice.summary(), choice.arguments());
  }
  @Override public String plan(String prompt,ProjectContext context,String model){plans.incrementAndGet();return "计划";}
  @Override public Map<String,String> generate(String prompt,String plan,ProjectContext context,String failure,String model) {
   if(generations.incrementAndGet()==1) {
    entered.countDown();boolean done=false;
    while(!done){try{release.await();done=true;}catch(InterruptedException ignored){}}
    returned.countDown();return Map.of("index.html","<body>old late result</body>");
   }
   return Map.of("index.html","<body>new result</body>");
  }
 }
}
