package dev.atoms;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class GenerationService {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GenerationService.class);
 final Store store;final ModelClient model;final ImageClient images;
 final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20));
 GenerationService(Store store,ModelClient model,ImageClient images){this.store=store;this.model=model;this.images=images;}
 @PostConstruct void recover() {
  store.recoverJobs();
 }
 final ConcurrentMap<String,FutureTask<Void>> workers=new ConcurrentHashMap<>();
 @PreDestroy void stop(){executor.shutdownNow();}
 void submit(String rid,String pid,String owner,String prompt,String base,String mode,String selectedModel) {
  final String token;
  try{token=store.claim(rid,owner);}catch(Store.StaleExecution e){return;}
  var task=new FutureTask<Void>(()->{execute(rid,pid,owner,prompt,base,mode,selectedModel,token);return null;}) {
   @Override protected void done(){workers.remove(token);executor.remove(this);}
  };
  workers.put(token,task);
  try{executor.execute(task);}catch(RejectedExecutionException e){workers.remove(token);store.fail(rid,pid,"任务队列已满，请稍后重试",token);}
 }
 Map<String,Object> pause(String rid,String owner) {
  String token=store.pause(rid,owner);
  if(token!=null){var task=workers.get(token);if(task!=null)task.cancel(true);}
  return store.run(rid,owner);
 }
 Map<String,Object> resume(String rid,String owner) {
  var r=store.resume(rid,owner);String pid=(String)r.get("project_id");
  submit(rid,pid,owner,(String)r.get("prompt"),(String)r.get("base_version"),store.runMode(rid),store.runModel(rid));
  return store.run(rid,owner);
 }
 void execute(String rid,String pid,String owner,String prompt,String base,String mode,String selectedModel) {
  try{execute(rid,pid,owner,prompt,base,mode,selectedModel,store.claim(rid,owner));}catch(Store.StaleExecution ignored){}
 }
 void execute(String rid,String pid,String owner,String prompt,String base,String mode,String selectedModel,String token){
  try {
   store.requireExecution(rid,token);
   var context="image".equals(mode)?null:store.context(pid,owner,base,rid);
   if("chat".equals(mode)) {
    store.stage(rid,owner,token,"ANSWERING");String reply=model.chat(prompt,context,selectedModel);
    store.finishReply(rid,pid,reply,mode,selectedModel,null,null,prompt,token);return;
   }
   if("image".equals(mode)) {
    store.stage(rid,owner,token,"IMAGING");var result=images.generate(prompt,selectedModel);
    store.finishReply(rid,pid,model.demo?"【演示图片】此图为本地示例，不代表真实模型生成。":"图片已生成并保存，可预览或下载原图。",mode,selectedModel,result.bytes(),result.mediaType(),prompt,token);return;
   }
   String plan=(String)store.run(rid,owner).get("plan");
   if(plan==null){store.stage(rid,owner,token,"PLANNING");plan=model.plan(prompt,context,selectedModel);store.plan(rid,owner,token,plan);}
   var checkpoint=store.checkpoint(rid);
   Map<String,String> files=checkpoint.get("files")==null?null:store.files((String)checkpoint.get("files"));
   String failure=(String)Objects.requireNonNullElse(checkpoint.get("failure"),"");int attempt=((Number)checkpoint.get("attempt")).intValue();
   if(attempt>=2)throw new IllegalArgumentException(failure);
   while(attempt<2) {
    try {
     if(files==null) {
      store.stage(rid,owner,token,attempt==0?"GENERATING":"REPAIRING");
      files=model.generate(prompt,plan,context,failure,selectedModel);
      store.checkpoint(rid,owner,token,files,failure,attempt);
     }
     store.stage(rid,owner,token,"VALIDATING");validate(files);
     if(!context.files().isEmpty() && files.equals(context.files()))throw new IllegalArgumentException("模型返回的源码与当前版本完全相同，尚未落实本次修改需求。请根据本次需求实际修改相关文件，不能原样复制旧源码");
     break;
    } catch(IllegalArgumentException e) {
     failure=e.getMessage();files=null;attempt++;
     store.checkpoint(rid,owner,token,null,failure,attempt);
     if(attempt>=2)throw e;
    }
   }
   store.save(pid,owner,base,files,model.demo?"演示源码已保存。静态页面可预览，后端工程请导出后在本地运行；任意需求修改请接入真实模型。":"已生成并保存 "+files.size()+" 个源码文件。"+(SourceFiles.canPreview(files)?"请在右侧预览并手动验收交互。":"请在代码面板查看文件，导出后在本地编译运行；工作台不会执行后端代码。"),rid,token);
  } catch(Store.StaleExecution ignored) {
   // A pause/resume invalidates this execution, even if a provider returns late.
  } catch(Exception e){
   if(executor.isShutdown()){try{store.pause(rid,owner,token);}catch(Store.StaleExecution ignored){}return;}
   log.warn("Generation task failed: runId={}, mode={}, model={}, exception={}",rid,mode,selectedModel,e.getClass().getName());
   String message="image".equals(mode)&&e instanceof java.net.http.HttpTimeoutException?"图像生成或下载请求超时，请稍后重试":describeFailure(e,model.timeoutSeconds);
   store.fail(rid,pid,message==null?"生成失败":message,token);
  }
 }
 static String describeFailure(Exception error,int timeoutSeconds) {
  Throwable cause=error;while(cause.getCause()!=null&&cause.getCause()!=cause)cause=cause.getCause();
  if(error instanceof java.net.http.HttpTimeoutException||cause instanceof java.net.SocketTimeoutException)return "模型请求超过 "+timeoutSeconds+" 秒仍未完成。请缩小本次需求，或在后端私有配置中调整 atoms.model.timeout-seconds 后重试";
  if(error instanceof InterruptedException){Thread.currentThread().interrupt();return "生成任务被中断，请检查后端是否重启后再试";}
  if(cause instanceof java.net.UnknownHostException||cause instanceof java.nio.channels.UnresolvedAddressException)return "无法解析模型服务域名，请检查模型地址、DNS与网络";
  if(error instanceof java.net.ConnectException||cause instanceof java.net.ConnectException)return "无法连接模型服务，请检查网络、代理和服务地址";
  if(cause instanceof javax.net.ssl.SSLException||error instanceof javax.net.ssl.SSLException)return "模型服务TLS连接失败，请检查网络代理和证书配置";
  if(error instanceof java.io.IOException)return "与模型服务的网络连接中断，请重试；若反复出现，请检查代理与网络稳定性";
  if(error instanceof IllegalArgumentException||error instanceof IllegalStateException)return error.getMessage();
  return "任务处理失败，请根据后端日志中的任务ID检查原因";
 }
 static void validate(Map<String,String> files){SourceFiles.validate(files);}
}
