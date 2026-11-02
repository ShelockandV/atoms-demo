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
 @PreDestroy void stop(){executor.shutdownNow();}
 void submit(String rid,String pid,String owner,String prompt,String base,String mode,String selectedModel) {
  try{executor.execute(() -> execute(rid,pid,owner,prompt,base,mode,selectedModel));}catch(RejectedExecutionException e){store.fail(rid,pid,"任务队列已满，请稍后重试");}
 }
 void execute(String rid,String pid,String owner,String prompt,String base,String mode,String selectedModel){
  try {
   if("chat".equals(mode)) {
    store.stage(rid,"ANSWERING");String reply=model.chat(prompt,store.chatHistory(pid),selectedModel);
    store.finishReply(rid,pid,reply,mode,selectedModel,null,null,prompt);return;
   }
   if("image".equals(mode)) {
    store.stage(rid,"IMAGING");var result=images.generate(prompt,selectedModel);
    store.finishReply(rid,pid,model.demo?"【演示图片】此图为本地示例，不代表真实模型生成。":"图片已生成并保存，可预览或下载原图。",mode,selectedModel,result.bytes(),result.mediaType(),prompt);return;
   }
   store.stage(rid,"PLANNING");String plan=model.plan(prompt,selectedModel);store.plan(rid,plan);
   var current=store.versionFiles(base);Map<String,String> files=null;String failure="";
   for(int attempt=0;attempt<2;attempt++) {
    store.stage(rid,attempt==0?"GENERATING":"REPAIRING");
    try {files=model.generate(prompt,plan,current,failure,selectedModel);store.stage(rid,"VALIDATING");validate(files);break;}
    catch(IllegalArgumentException e){failure=e.getMessage();if(attempt==1)throw e;}
   }
   store.save(pid,owner,base,files,model.demo?"演示源码已保存。静态页面可预览，后端工程请导出后在本地运行；任意需求修改请接入真实模型。":"已生成并保存 "+files.size()+" 个源码文件。"+(SourceFiles.canPreview(files)?"请在右侧预览并手动验收交互。":"请在代码面板查看文件，导出后在本地编译运行；工作台不会执行后端代码。"),rid);
  } catch(Exception e){
   log.warn("Generation task failed: runId={}, mode={}, model={}, exception={}",rid,mode,selectedModel,e.getClass().getName());
   String message="image".equals(mode)&&e instanceof java.net.http.HttpTimeoutException?"图像生成或下载请求超时，请稍后重试":describeFailure(e,model.timeoutSeconds);
   store.fail(rid,pid,message==null?"生成失败":message);
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
