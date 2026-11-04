package dev.atoms;

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

class PauseResumeTest {
 final ObjectMapper json=new ObjectMapper();
 final String owner="owner";
 Store store;
 GenerationService service;
 BlockingModel model;
 String pid,rid;

 @BeforeEach void setup() {
  var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
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
  store=new Store(store.db,store.tx,json);
  service=new GenerationService(store,model,mock(ImageClient.class));
  service.recover();
  assertEquals("PAUSED",status());
  service.resume(rid,owner);awaitSuccess();
  assertEquals(0,model.plans.get());assertEquals(0,model.generations.get());
  assertTrue(store.versionFiles((String)store.project(pid,owner).get("current_version")).get("index.html").contains("checkpoint result"));
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

 static class BlockingModel extends ModelClient {
  final AtomicInteger plans=new AtomicInteger(),generations=new AtomicInteger();
  final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);
  BlockingModel(ObjectMapper json){super(json,false,"http://unused","test-key","test-model",300,16000);}
  @Override String plan(String prompt,ProjectContext context,String model){plans.incrementAndGet();return "计划";}
  @Override Map<String,String> generate(String prompt,String plan,ProjectContext context,String failure,String model) {
   if(generations.incrementAndGet()==1) {
    entered.countDown();boolean done=false;
    while(!done){try{release.await();done=true;}catch(InterruptedException ignored){}}
    returned.countDown();return Map.of("index.html","<body>old late result</body>");
   }
   return Map.of("index.html","<body>new result</body>");
  }
 }
}
