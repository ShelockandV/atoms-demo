package dev.atoms;

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

class ProjectMemoryTest {
 @TempDir Path directory;
 final ObjectMapper json=new ObjectMapper();
 final String owner="test-owner";
 Store store;
 RecordingModel model;
 GenerationService generation;

 @BeforeEach void setup() {
  var source=new DriverManagerDataSource("jdbc:h2:file:"+directory.resolve("memory")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","");
  new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
  store=new Store(new JdbcTemplate(source),new TransactionTemplate(new DataSourceTransactionManager(source)),json);
  model=new RecordingModel(json);
  generation=new GenerationService(store,model,mock(ImageClient.class));
 }
 @AfterEach void cleanup(){generation.stop();}
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
  store=new Store(store.db,store.tx,json);
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

 static class RecordingModel extends ModelClient {
  final List<List<Map<String,String>>> calls=new ArrayList<>();
  final Deque<String> answers=new ArrayDeque<>();
  RecordingModel(ObjectMapper json){super(json,false,"http://unused","test-key","test-model",300,16000);}
  @Override String completion(List<Map<String,String>> messages,String selectedModel) {
   calls.add(List.copyOf(messages));
   if(answers.isEmpty())throw new AssertionError("Unexpected model request");
   return answers.removeFirst();
  }
 }
}
