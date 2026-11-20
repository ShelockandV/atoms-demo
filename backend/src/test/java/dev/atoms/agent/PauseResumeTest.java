package dev.atoms.agent;

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
  source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
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

 @Test void templateRunResumesFromCompletedNodeWithoutReplanning(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
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
  service.recover();
  service.resume(rid, owner);
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

 @Test void restartPausesActiveRequestsAndRetainsReservation() {
  store.claim(rid,owner);store.recoverJobs();
  assertEquals("PAUSED",status());
  assertEquals(rid,store.project(pid,owner).get("active_run"));
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
