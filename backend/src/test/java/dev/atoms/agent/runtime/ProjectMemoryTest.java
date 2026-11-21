package dev.atoms.agent.runtime;

import dev.atoms.agent.pattern.ExecutionPattern;
import dev.atoms.agent.pattern.PatternRegistry;

import dev.atoms.agent.model.ModelClient;
import dev.atoms.agent.persistence.Store;

import dev.atoms.workspace.ProjectContext;

import dev.atoms.agent.model.ImageClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 验证项目历史和基准文件在多轮任务中的使用。
 *
 * <p>示例：重新加载任务服务后继续基于已保存的项目版本开发。</p>
 */
class ProjectMemoryTest {
 @TempDir Path directory;
 final ObjectMapper json=new ObjectMapper();
 final String owner="test-owner";
 Store store;
    DriverManagerDataSource source;
 RecordingModel model;
 GenerationService generation;

 @BeforeEach void setup() {
  source=dev.atoms.MySqlTestDatabase.create();
  new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
  store=new Store(new JdbcTemplate(source),new TransactionTemplate(new DataSourceTransactionManager(source)),json);
  model=new RecordingModel(json);
  generation=new GenerationService(store,model,mock(ImageClient.class));
 }
 String create(String title){return (String)store.create(owner,title).get("id");}
 String current(String pid){return (String)store.project(pid,owner).get("current_version");}
 String execute(String pid,String prompt,String mode) {
  String base=current(pid);
  String rid=(String)store.queue(pid,owner,prompt,base,mode,"test-model").get("id");
  generation.execute(rid,pid,owner,prompt,base,mode,"test-model");
  return rid;
 }
 String html(String marker){return "<html><body>"+marker+"</body></html>";}
 void answerBuild(String content)throws Exception {model.answers.add("修改现有代码，保留绿色样式");model.answers.add(json.writeValueAsString(Map.of("index.html",content)));}
 List<Map<String,String>> lastCall(){return model.calls.getLast();}
 String latestInput(){return lastCall().getLast().get("content");}

 @Test void relatedOlderSuccessfulTurnIsRecalledOutsideTheRecentWindow() {
  String pid = create("结算项目");
  String other = create("独立项目");
  model.answers.add("另一个项目的私有结算规则");
  execute(other, "发票金额如何处理尾数和进位？", "chat");
  model.answers.add("初始化项目");
  execute(pid, "项目启动", "chat");
  model.answers.add("旧约定：按四舍五入保留两位小数");
  execute(pid, "发票金额如何处理尾数和进位？", "chat");
  for (int i = 0; i < 20; i++) {
   model.answers.add("页面导航讨论");
   execute(pid, "讨论页面导航 " + i, "chat");
  }
  model.answers.add("引用旧约定");
  execute(pid, "发票金额如何处理尾数和进位", "chat");
  assertTrue(latestInput().contains("相关的旧项目记录"));
  assertTrue(latestInput().contains("旧约定：按四舍五入保留两位小数"));
  assertFalse(latestInput().contains("另一个项目的私有结算规则"));
  assertEquals(1, lastCall().stream().filter(message -> message.get("content").contains("旧约定：按四舍五入")).count());
 }

 @Test void followupRepairRemembersBriefChatAndCodeAfterReload()throws Exception {
  String pid=create("绿色计算器");
  String original=html("绿色主题；加法错误：a-b");
  answerBuild(original);
  execute(pid,"做一个绿色计算器，保留键盘操作","build");
  String firstVersion=current(pid);
  model.answers.add("刚才约定显示两位小数。");
  execute(pid,"金额保留两位小数","chat");
  assertEquals(firstVersion,current(pid),"讨论需求不应覆盖源码");
  assertTrue(latestInput().contains(original));
  // New service/store instances use only persisted project data.
  generation.stop();
  store=new Store(new JdbcTemplate(source),new TransactionTemplate(new DataSourceTransactionManager(source)),json);
  generation=new GenerationService(store,model,mock(ImageClient.class));
  String fixed=html("绿色主题；加法正确：a+b；两位小数");
  answerBuild(fixed);
  int before=model.calls.size();
  execute(pid,"刚才加法有bug，修复它，保持其他功能","build");
  assertNotEquals(firstVersion,current(pid));
  assertEquals(fixed,store.versionFiles(current(pid)).get("index.html"));
  assertEquals(original,store.versionFiles(firstVersion).get("index.html"));
  for(var call:model.calls.subList(before,model.calls.size())) {
   assertTrue(call.stream().anyMatch(m->m.get("content").equals("做一个绿色计算器，保留键盘操作")));
   assertTrue(call.stream().anyMatch(m->m.get("content").equals("金额保留两位小数")));
   assertTrue(call.stream().anyMatch(m->m.get("content").equals("刚才约定显示两位小数。")));
   assertTrue(call.getLast().get("content").contains(original));
   assertEquals(1,call.stream().filter(m->m.get("content").contains("刚才加法有bug，修复它，保持其他功能")).count(),"当前请求只发送一次");
  }
 }

 @Test void restoredVersionIsTheBaseAndProjectsStayIsolated()throws Exception {
  String pid=create("项目A"),other=create("项目B");
  answerBuild(html("A最初版本"));execute(pid,"记住A使用绿色","build");
  String first=current(pid);
  answerBuild(html("A较新版本"));execute(pid,"添加按钮","build");
  answerBuild(html("B的私有源码"));execute(other,"B的独立需求","build");
  store.save(pid,owner,current(pid),store.versionFiles(first),"恢复第一版",null);
  String restored=current(pid);
  model.answers.add("解释恢复后的代码");execute(pid,"解释当前代码","chat");
  assertTrue(latestInput().contains(restored));
  assertTrue(latestInput().contains("A最初版本"));
  assertFalse(latestInput().contains("A较新版本"));
  assertTrue(lastCall().stream().noneMatch(m->m.get("content").contains("B的独立需求")||m.get("content").contains("B的私有源码")));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.context(pid,"different-owner",restored,"none"));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.context(pid,owner,current(other),"none"));
 }

 @Test void repairAttemptsShareContextAndFailedRequestsRemainClearlyMarked()throws Exception {
  String pid=create("修复测试");answerBuild(html("原始源码"));execute(pid,"保留原功能","build");
  String originalVersion=current(pid);
  String failed=(String)store.queue(pid,owner,"修复除零问题",originalVersion,"build","test-model").get("id");
  store.fail(failed,pid,"模拟模型超时");
  model.answers.add("继续修复");model.answers.add("invalid JSON");model.answers.add(json.writeValueAsString(Map.of("index.html",html("修复后源码"))));
  int start=model.calls.size();execute(pid,"继续刚才的修复","build");
  var calls=model.calls.subList(start,model.calls.size());
  assertEquals(3,calls.size());
  for(var call:calls) {
   assertTrue(call.getLast().get("content").contains("原始源码"));
   assertTrue(call.stream().anyMatch(m->m.get("content").equals("修复除零问题")));
   assertTrue(call.stream().anyMatch(m->m.get("content").contains("此请求未完成，源码未更新")));
  }
  assertTrue(calls.getLast().getLast().get("content").contains("模型未返回合法的源码文件JSON"));
 }

 @Test void semanticMemoryFindsParaphrasesAndFallsBackToLexicalWithinProject() throws Exception {
  var db = new JdbcTemplate(source);
  String pid = create("Semantic project");
  String foreign = create("Other project");
  var index = new java.util.concurrent.atomic.AtomicInteger();
  java.util.function.BiConsumer<String, String> seed = (project, prompt) -> {
   String rid = (String) store.queue(project, owner, prompt, null, "chat", "test-model").get("id");
   store.finishReply(rid, project, "Result for " + prompt, "chat", "test-model", null, null, prompt);
   db.update("UPDATE generation_runs SET created_at=? WHERE id=?",
           java.time.Instant.EPOCH.plusSeconds(index.incrementAndGet()).toString(), rid);
  };
  seed.accept(pid, "Original project brief");
  seed.accept(pid, "专案蓝鸢");
  seed.accept(pid, "帮我看一下发票");
  seed.accept(foreign, "another-project private text");
  for (int i = 0; i < 20; i++) seed.accept(pid, "Recent exchange " + i);
  String query = "帮我看一下发票";
  String run = (String) store.queue(pid, owner, query, null, "chat", "test-model").get("id");
  var requests = new java.util.concurrent.CopyOnWriteArrayList<List<String>>();
  var outage = new java.util.concurrent.atomic.AtomicBoolean(true);
  var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
  server.createContext("/embeddings", exchange -> {
   try (exchange) {
    var body = json.readTree(exchange.getRequestBody());
    List<String> input = json.convertValue(body.path("input"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    requests.add(input);
    if (outage.get()) {
     exchange.sendResponseHeaders(503, -1);
     return;
    }
    var data = new ArrayList<Map<String, Object>>();
    for (int item = input.size() - 1; item >= 0; item--) {
     data.add(Map.of("index", item, "embedding", item == 0 || input.get(item).equals("专案蓝鸢")
             ? List.of(2, 0) : List.of(0, 5)));
    }
    byte[] response = json.writeValueAsBytes(Map.of("data", data));
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
   }
  });
  server.start();
  try {
   var uri = java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings");
   var semantic = new dev.atoms.workspace.SemanticHistory(db, json, uri, "", "fake-embedding", 0.65);
   org.springframework.test.util.ReflectionTestUtils.invokeMethod(store, "configureSemanticHistory", semantic);
   String lexical = store.context(pid, owner, null, run).recalledHistory();
   assertTrue(lexical.contains("Result for 帮我看一下发票"));
   assertFalse(lexical.contains("专案蓝鸢"));
   outage.set(false);
   String recalled = store.context(pid, owner, null, run).recalledHistory();
   assertTrue(recalled.startsWith("历史需求：专案蓝鸢"), recalled);
   assertEquals(recalled, store.context(pid, owner, null, run).recalledHistory());
   assertEquals(2, requests.size());
   assertTrue(requests.stream().flatMap(List::stream).noneMatch(text -> text.contains("another-project")));
  } finally {
   server.stop(0);
  }
 }

 @Test void researchRefinementRecoversMissingEvidenceAndCheckpointsFollowup() {
  String pid = create("Research refinement");
  store.save(pid, owner, null, Map.of("notes.md", "金丝雀约定：周五完成验收"), "save", null);
  org.springframework.test.util.ReflectionTestUtils.setField(generation, "researchRefinementEnabled", true);
  model.answers.add("[\"首轮无关词\"]");
  model.answers.add("[\"首轮无关词\",\"金丝雀约定\"]");
  model.answers.add("验收安排在周五 [S1]");
  String run = execute(pid, "找验收约定", "research");
  assertEquals("SUCCEEDED", store.run(run, owner).get("status"));
  assertEquals(3, model.calls.size());
  assertTrue(model.calls.getLast().getLast().get("content").contains("周五完成验收"));
  assertTrue(store.dagNodes(run).stream().anyMatch(node -> "research.refine".equals(node.get("id"))
          && "SUCCEEDED".equals(node.get("status"))));
  assertTrue(store.dagNodes(run).stream().anyMatch(node -> "research.followup.1".equals(node.get("id"))
          && "SUCCEEDED".equals(node.get("status"))));
  assertFalse(store.dagNodes(run).stream().anyMatch(node -> "research.followup.2".equals(node.get("id"))));
 }

 @Test void deepResearchCarriesEvidenceForwardAndStopsWhenCoverageIsSufficient() {
  String pid = (String) store.createGatewaySession(owner).get("id");
  store.save(pid, owner, null, Map.of("notes.md", "金丝雀约定：周五完成验收"), "save", null);
  model.answers.add("[\"首轮无关词\"]");
  model.answers.add("[\"金丝雀约定\"]");
  model.answers.add("[\"金丝雀约定\"]");
  model.answers.add("验收安排在周五 [S1]");
  String base = current(pid);
  String prompt = "找验收约定";
  String run = (String) store.queue(pid, owner, prompt, base, "research", "test-model", "react",
          null, null, Map.of("research_strategy", "deep")).get("id");
  generation.execute(run, pid, owner, prompt, base, "research", "test-model");
  assertEquals("SUCCEEDED", store.run(run, owner).get("status"));
  assertEquals(4, model.calls.size());
  assertTrue(model.calls.get(2).getLast().get("content").contains("周五完成验收"));
  var nodes = store.dagNodes(run);
  assertTrue(nodes.stream().anyMatch(node -> "research.refine.round-2".equals(node.get("id"))));
  assertFalse(nodes.stream().anyMatch(node -> "research.refine.round-3".equals(node.get("id"))));
 }

 @Test void researchParsesStructuredSourcesWithoutPublishingUnsafeUrls() {
  var evidence = ResearchWorkflow.externalEvidence(json, "REFERENCE", """
          {"results":[
            {"url":"javascript:alert(1)","title":"invalid","snippet":"skip"},
            {"url":"https://user@example.com/doc","title":"invalid","snippet":"skip"},
            {"url":"https://example.com/topics/../policy#section","title":"Policy","snippet":"Public policy summary"}
          ]}
          """);
  assertEquals(1, evidence.size());
  assertEquals("https://example.com/policy", evidence.getFirst().url());
  assertEquals("Policy", evidence.getFirst().origin());
  var plain = ResearchWorkflow.externalEvidence(json, "REFERENCE", "Unstructured notes");
  assertEquals("", plain.getFirst().url());
  assertEquals("REFERENCE", plain.getFirst().origin());
 }

 @Test void researchFallsBackWhenCitationsDoNotExist() {
  String pid = create("Research citations");
  String version = (String) store.save(pid, owner, null, Map.of("notes.md", "合同交付条款：两个工作日"),
          "save", null).get("id");
  model.answers.add("[\"合同交付条款\"]");
  model.answers.add("这一结论有来源 [S9]");
  String run = execute(pid, "找一下合同交付条款", "research");
  var result = store.run(run, owner);
  assertEquals("SUCCEEDED", result.get("status"));
  String report = store.dagNodes(run).stream()
          .filter(node -> "research.report".equals(node.get("id"))).findFirst().orElseThrow().get("output").toString();
  assertTrue(report.contains("综合报告暂不可用"));
  assertFalse(report.contains("[S9]"));
  assertEquals(version, store.project(pid, owner).get("current_version"));
  assertTrue(model.calls.getLast().getLast().get("content").contains("两个工作日"));
 }

 @Test void documentEmbeddingsFindSynonymsAndKeepVersionsIsolated() throws Exception {
  var db = new JdbcTemplate(source);
  String project = create("Document vectors");
  String another = create("Different project");
  String old = (String) store.save(project, owner, null,
          Map.of("guide.md", "采购履约规范", "literal.txt", "发票流程"),
          "save", null).get("id");
  String foreign = (String) store.save(another, owner, null,
          Map.of("notes.md", "其他项目的内部信息"), "save", null).get("id");
  var requests = new java.util.concurrent.CopyOnWriteArrayList<List<String>>();
  var outage = new java.util.concurrent.atomic.AtomicBoolean(false);
  var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
  server.createContext("/embeddings", exchange -> {
   try (exchange) {
    List<String> input = json.convertValue(json.readTree(exchange.getRequestBody()).path("input"),
            new com.fasterxml.jackson.core.type.TypeReference<>() {});
    requests.add(input);
    if (outage.get()) {
     exchange.sendResponseHeaders(503, -1);
     return;
    }
    var data = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < input.size(); i++) {
     data.add(Map.of("index", i, "embedding", i == 0 || input.get(i).equals("采购履约规范")
             ? List.of(3, 0) : List.of(0, 4)));
    }
    byte[] response = json.writeValueAsBytes(Map.of("data", data));
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
   }
  });
  server.start();
  try {
   var uri = java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings");
   var provider = new dev.atoms.workspace.SemanticHistory(db, json, uri, "", "doc-embedding", 0.65);
   org.springframework.test.util.ReflectionTestUtils.invokeMethod(store, "configureSemanticDocuments",
           new dev.atoms.workspace.SemanticDocuments(db, json, provider));
   var semantic = store.searchProjectDocuments(project, owner, old, "供应商约定");
   assertEquals("guide.md", semantic.getFirst().path());
   assertEquals(semantic, store.searchProjectDocuments(project, owner, old, "供应商约定"));
   assertEquals(List.of("供应商约定"), requests.get(1));
   assertThrows(org.springframework.web.server.ResponseStatusException.class,
           () -> store.searchProjectDocuments(project, owner, foreign, "供应商约定"));
   String updated = (String) store.save(project, owner, old,
           Map.of("guide.md", "预算清单"), "change", null).get("id");
   assertTrue(store.searchProjectDocuments(project, owner, updated, "供应商约定").isEmpty());
   assertTrue(requests.stream().flatMap(List::stream).noneMatch(text -> text.contains("其他项目")));
   outage.set(true);
   var lexical = store.searchProjectDocuments(project, owner, old, "发票流程");
   assertEquals("literal.txt", lexical.getFirst().path());
  } finally {
   server.stop(0);
  }
 }

 @Test void compressedHistoryIsUsedAndReusedAfterPause() throws Exception {
  var db = new JdbcTemplate(source);
  String pid = create("Compression");
  for (int i = 0; i < 23; i++) {
   String prompt = "Past exchange " + i;
   String task = (String) store.queue(pid, owner, prompt, null, "chat", "test").get("id");
   store.finishReply(task, pid, "Past answer " + i, "chat", "test", null, null, prompt);
   db.update("UPDATE generation_runs SET created_at=? WHERE id=?",
           java.time.Instant.EPOCH.plusSeconds(i).toString(), task);
  }
  String run = (String) store.queue(pid, owner, "Continue project", null, "chat", "test").get("id");
  var shouldPause = new java.util.concurrent.atomic.AtomicBoolean(true);
  var bodies = new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
  var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
  server.createContext("/chat/completions", exchange -> {
   try (exchange) {
    bodies.add(json.readTree(exchange.getRequestBody()));
    byte[] data = """
            {"choices":[{"message":{"content":"Keep confirmed project decisions"}}],
             "usage":{"prompt_tokens":30,"completion_tokens":8}}
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, data.length);
    exchange.getResponseBody().write(data);
   }
  });
  server.start();
  var calls = new ArrayList<ProjectContext>();
  var client = new ModelClient(json, false, "http://127.0.0.1:" + server.getAddress().getPort(),
          "local-test", "test", 5, 1000) {
   @Override public String chat(String prompt, ProjectContext context, String selectedModel) {
    calls.add(context);
    if (shouldPause.getAndSet(false)) store.pause(run, owner);
    return "Done";
   }
  };
  generation.stop();
  generation = new GenerationService(store, client, mock(ImageClient.class));
  org.springframework.test.util.ReflectionTestUtils.setField(generation, "compressionEnabled", true);
  try {
   generation.execute(run, pid, owner, "Continue project", null, "chat", "test");
   assertEquals("PAUSED", store.run(run, owner).get("status"));
   assertEquals("Keep confirmed project decisions", store.contextSummary(run).orElseThrow());
   store.resume(run, owner);
   generation.execute(run, pid, owner, "Continue project", null, "chat", "test");
   assertEquals("SUCCEEDED", store.run(run, owner).get("status"));
   assertEquals(1, bodies.size());
   assertEquals(500, bodies.getFirst().path("max_tokens").asInt());
   assertFalse(bodies.getFirst().toString().contains("Past exchange 22"));
   var usage = (Map<?, ?>) store.run(run, owner).get("usage");
   assertEquals(1, ((Number) usage.get("modelCalls")).intValue());
   assertEquals(calls.getFirst(), calls.getLast());
   var context = calls.getLast();
   assertTrue(context.recalledHistory().contains("Keep confirmed project decisions"));
   assertTrue(context.history().stream().anyMatch(message -> message.get("content").equals("Past exchange 0")));
   assertTrue(context.history().stream().anyMatch(message -> message.get("content").equals("Past exchange 22")));
   assertFalse(context.history().stream().anyMatch(message -> message.get("content").equals("Past exchange 8")));
  } finally {
   server.stop(0);
  }
 }

 @Test void longHistoryKeepsInitialBriefAndLatestRequestWithinBudget() {
  var messages=new ArrayList<Map<String,String>>();
  messages.add(Map.of("role","user","content","初始约定：绿色主题"));
  for(int i=0;i<100;i++)messages.add(Map.of("role",i%2==0?"assistant":"user","content","第"+i+"轮"+"x".repeat(2000)));
  var bounded=Store.boundedHistory(messages);
  assertEquals("初始约定：绿色主题",bounded.getFirst().get("content"));
  assertEquals(messages.getLast(),bounded.getLast());
  assertTrue(bounded.stream().mapToInt(m->m.get("content").length()).sum()<=24000);
  assertTrue(bounded.size()<=41);
 }

 @Test void imageTurnsAreNotAddedToCodeMemory()throws Exception {
  String pid=create("图片隔离");
  String rid=(String)store.queue(pid,owner,"图片描述不应作为代码需求",null,"image","image-model").get("id");
  store.finishReply(rid,pid,"图片回复","image","image-model",null,null,"");
  assertTrue(store.context(pid,owner,null,"next").history().isEmpty());
 }

 @Test void unchangedRepairRetriesWithFeedbackInsteadOfSavingDuplicateVersion()throws Exception {
  String pid=create("2048");String original=html("缺少新游戏按钮");
  answerBuild(original);execute(pid,"创建2048","build");String base=current(pid);
  model.answers.add("添加始终可见的新游戏按钮");
  model.answers.add(json.writeValueAsString(Map.of("index.html",original)));
  String changed=html("<button>新游戏</button>");
  model.answers.add(json.writeValueAsString(Map.of("index.html",changed)));
  String rid=execute(pid,"添加新游戏按钮","build");
  assertEquals("SUCCEEDED",store.run(rid,owner).get("status"),()->store.encode(store.agentSnapshot(rid)));
  assertEquals(2,store.versions(pid).size());
  assertNotEquals(base,current(pid));
  assertEquals(changed,store.versionFiles(current(pid)).get("index.html"));
  assertTrue(latestInput().contains("源码与当前版本完全相同"));
 }

 @Test void repeatedlyUnchangedRepairFailsWithoutCreatingVersion()throws Exception {
  String pid=create("2048");String original=html("缺少新游戏按钮");
  answerBuild(original);execute(pid,"创建2048","build");String base=current(pid);
  model.answers.add("添加按钮");
  for(int i=0;i<2;i++)model.answers.add(json.writeValueAsString(Map.of("index.html",original)));
  String rid=execute(pid,"添加新游戏按钮","build");
  assertEquals("FAILED",store.run(rid,owner).get("status"));
  assertEquals(base,current(pid));
  assertEquals(1,store.versions(pid).size());
  assertTrue(((String)store.run(rid,owner).get("error")).contains("源码与当前版本完全相同"));
 }

 /**
  * 记录收到的模型消息并返回预设响应，供项目记忆测试断言。
  *
  * <p>示例：观察后续请求是否包含此前的项目历史。</p>
  */
 static class RecordingModel extends ModelClient {
  final List<List<Map<String,String>>> calls=new ArrayList<>();
  final Deque<String> answers=new ArrayDeque<>();
  RecordingModel(ObjectMapper json){super(json,false,"http://unused","test-key","test-model",300,16000);}
  @Override public ModelClient.Decision decide(String prompt, String plan, ProjectContext context, ModelClient.ActionInput input, String selectedModel) {
   var choice = PatternRegistry.offlineDecision(new ExecutionPattern.Progress(input.generations(),
           input.hasFiles(), input.validated(), input.reviewed(), input.observations()));
   return new ModelClient.Decision(choice.action(), choice.path(), choice.summary(), choice.arguments());
  }
  @Override public com.fasterxml.jackson.databind.JsonNode completeMessage(
          List<Map<String, String>> messages, String selectedModel, Map<String, Object> options) {
   return json().createObjectNode().put("content", completion(messages, selectedModel));
  }

  @Override public String completion(List<Map<String,String>> messages,String selectedModel) {
   calls.add(List.copyOf(messages));
   if(answers.isEmpty())throw new AssertionError("Unexpected model request");
   return answers.removeFirst();
  }
 }
}
