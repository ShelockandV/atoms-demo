package dev.atoms;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;

@RestController
@RequestMapping("/api")
public class ApiController {
 final Store store; final GenerationService generation;final ModelClient model;final ModelCatalog catalog;
 final ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();
 ApiController(Store store,GenerationService generation,ModelClient model,ModelCatalog catalog){this.catalog=catalog;this.store=store;this.generation=generation;this.model=model;scheduler.setPoolSize(2);scheduler.initialize();}
 @PreDestroy void close(){scheduler.shutdown();}
 String owner(HttpSession session){synchronized(session){if(session.getAttribute("owner")==null)session.setAttribute("owner",Store.id());return (String)session.getAttribute("owner");}}
 record ProjectInput(String title){}
 record RunInput(String prompt,String baseVersion,String mode,String model){}
 record SaveInput(Map<String,String> files,String baseVersion){}
 record RestoreInput(String versionId,String baseVersion){}
 @GetMapping("/config") Map<String,Object> config(HttpSession s){owner(s);var config=new LinkedHashMap<String,Object>(catalog.view());config.put("demo",model.demo);config.put("model",model.demo?"内置演示模板":model.model);config.put("configured",model.demo||(!model.key.isBlank()&&!model.model.isBlank()));return config;}
 @GetMapping("/assets/{id}") ResponseEntity<byte[]> asset(@PathVariable String id,HttpSession s){
  var asset=store.asset(id,owner(s));return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)asset.get("media_type"))).header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=atoms-image."+(((String)asset.get("media_type")).equals("image/jpeg")?"jpg":((String)asset.get("media_type")).equals("image/webp")?"webp":"png")).body((byte[])asset.get("image_data"));
 }

 @GetMapping("/projects") Object list(HttpSession s){return store.projects(owner(s));}
 @PostMapping("/projects") Object create(@RequestBody ProjectInput input,HttpSession s){String title=input.title()==null?"未命名项目":input.title().trim();if(title.isBlank()||title.length()>100)throw Store.error(400,"项目名称须为1～100字");return store.create(owner(s),title);}
 @GetMapping("/projects/{id}") Object detail(@PathVariable String id,HttpSession s){return store.detail(id,owner(s));}
 @GetMapping("/projects/{id}/versions") Object versions(@PathVariable String id,HttpSession s){store.project(id,owner(s));return store.versions(id);}
 @PostMapping("/projects/{id}/runs") Object run(@PathVariable String id,@RequestBody RunInput input,HttpSession s){
  if(input.prompt()==null||input.prompt().isBlank()||input.prompt().length()>10000)throw Store.error(400,"需求须为1～10000字");
  String owner=owner(s);String mode=catalog.mode(input.mode());String selectedModel=catalog.select(mode,input.model());
  synchronized(s){
   Long start=(Long)s.getAttribute("quotaStart");Integer count=(Integer)s.getAttribute("quotaCount");long now=System.currentTimeMillis();
   if(start==null||now-start>3600000){start=now;count=0;}
   if(count>=20)throw Store.error(429,"每个会话每小时最多生成20次，请稍后再试");
   var run=store.queue(id,owner,input.prompt(),input.baseVersion(),mode,selectedModel);s.setAttribute("quotaStart",start);s.setAttribute("quotaCount",count+1);
   generation.submit((String)run.get("id"),id,owner,input.prompt(),input.baseVersion(),mode,selectedModel);return run;
  }
 }
 @GetMapping("/runs/{id}") Object status(@PathVariable String id,HttpSession s){return store.run(id,owner(s));}
 @GetMapping(value="/runs/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) SseEmitter events(@PathVariable String id,HttpSession s){
  String owner=owner(s);store.run(id,owner);var emitter=new SseEmitter(180000L);var done=new AtomicBoolean(false);
  emitter.onCompletion(()->done.set(true));emitter.onTimeout(()->{done.set(true);emitter.complete();});emitter.onError(e->done.set(true));
  final java.util.concurrent.ScheduledFuture<?>[] future=new java.util.concurrent.ScheduledFuture<?>[1];
  future[0]=scheduler.scheduleAtFixedRate(()->{
   if(done.get()){if(future[0]!=null)future[0].cancel(false);return;}
   try{var run=store.run(id,owner);emitter.send(SseEmitter.event().name("status").data(run));if(Set.of("SUCCEEDED","FAILED").contains(run.get("status"))){done.set(true);emitter.complete();}}
   catch(Exception e){done.set(true);emitter.completeWithError(e);}
  },Duration.ofSeconds(1));return emitter;
 }
 @PostMapping("/projects/{id}/versions") Object save(@PathVariable String id,@RequestBody SaveInput input,HttpSession s){GenerationService.validate(input.files());return store.save(id,owner(s),input.baseVersion(),input.files(),"手动编辑保存",null);}
 @PostMapping("/projects/{id}/restore") Object restore(@PathVariable String id,@RequestBody RestoreInput input,HttpSession s){
  String owner=owner(s);store.project(id,owner);
  var target=store.versions(id).stream().filter(v->v.get("id").equals(input.versionId())).findFirst().orElseThrow(()->Store.error(404,"版本不存在"));
  return store.save(id,owner,input.baseVersion(),store.versionFiles((String)target.get("id")),"恢复自版本 v"+target.get("number"),null);
 }
 @GetMapping("/projects/{id}/export") ResponseEntity<byte[]> export(@PathVariable String id,@RequestParam(required=false) String versionId,HttpSession s)throws Exception{
  var project=store.project(id,owner(s));String vid=versionId==null?(String)project.get("current_version"):versionId;
  if(vid==null)throw Store.error(400,"暂无可导出的版本");
  store.requireVersion(id,vid);
  return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=atoms-project.zip").contentType(MediaType.APPLICATION_OCTET_STREAM).body(SourceFiles.zip(store.versionFiles(vid)));
 }
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> error(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",Objects.requireNonNullElse(e.getReason(),"请求失败")));}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",Objects.requireNonNullElse(e.getMessage(),"参数错误")));}
 @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e){return ResponseEntity.internalServerError().body(Map.of("error","服务暂不可用，请检查后端日志"));}
}
