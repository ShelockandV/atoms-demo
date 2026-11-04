package dev.atoms;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@Repository
public class Store {
 final JdbcTemplate db;
 final TransactionTemplate tx;
 final ObjectMapper json;
 Store(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json) { this.db=db; this.tx=tx; this.json=json; }
 void recoverJobs() {
  db.update("UPDATE generation_runs SET status='PAUSED',error=NULL WHERE status NOT IN ('SUCCEEDED','FAILED','PAUSED')");
  db.update("UPDATE run_checkpoints SET execution_token=NULL");
  db.update("UPDATE projects SET active_run=NULL WHERE active_run IS NOT NULL AND active_run NOT IN (SELECT id FROM generation_runs WHERE status='PAUSED')");
 }
 static String id() { return UUID.randomUUID().toString(); }
 static String now() { return Instant.now().toString(); }
 static ResponseStatusException error(int code, String message) { return new ResponseStatusException(HttpStatus.valueOf(code),message); }
 String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
 Map<String,String> files(String raw) { try { return json.readValue(raw, new TypeReference<>(){}); } catch(Exception e) { throw new IllegalStateException(e); } }
 Map<String,Object> project(String id, String owner) {
  var rows=db.queryForList("SELECT * FROM projects WHERE id=? AND owner_id=?",id,owner);
  if(rows.isEmpty()) throw error(404,"项目不存在");
  return rows.getFirst();
 }
 Map<String,Object> projectView(Map<String,Object> p) {
  var result=new LinkedHashMap<String,Object>();
  result.put("id",p.get("id")); result.put("title",p.get("title")); result.put("currentVersion",p.get("current_version")); result.put("updatedAt",p.get("updated_at")); return result;
 }
 List<Map<String,Object>> projects(String owner) { return db.queryForList("SELECT * FROM projects WHERE owner_id=? ORDER BY updated_at DESC",owner).stream().map(this::projectView).toList(); }
 Map<String,Object> create(String owner,String title) {
  String id=id(); db.update("INSERT INTO projects(id,owner_id,title,updated_at) VALUES(?,?,?,?)",id,owner,title,now()); return projectView(project(id,owner));
 }
 Map<String,Object> version(Map<String,Object> row) {
  var v=new LinkedHashMap<String,Object>(); v.put("id",row.get("id"));v.put("number",row.get("version_number"));v.put("summary",row.get("summary"));v.put("files",files((String)row.get("files")));v.put("createdAt",row.get("created_at"));return v;
 }
 List<Map<String,Object>> versions(String project) {return db.queryForList("SELECT * FROM project_versions WHERE project_id=? ORDER BY version_number DESC",project).stream().map(this::version).toList();}
 void requireVersion(String pid,String vid) {
  if(db.queryForObject("SELECT COUNT(*) FROM project_versions WHERE project_id=? AND id=?",Integer.class,pid,vid)!=1)throw error(404,"版本不存在");
 }
 Map<String,String> versionFiles(String version) {
  if(version==null) return Map.of();
  return files(db.queryForObject("SELECT files FROM project_versions WHERE id=?",String.class,version));
 }
 Map<String,Object> detail(String id,String owner) {
  var p=project(id,owner);var result=projectView(p);
  result.put("versions",versions(id));
  result.put("messages",db.queryForList("SELECT m.*,d.mode,d.model,d.asset_id FROM messages m LEFT JOIN message_details d ON d.message_id=m.id WHERE m.project_id=? ORDER BY m.created_at,m.id",id).stream().map(m -> {
   var v=new LinkedHashMap<String,Object>();for(String key:List.of("id","role","content"))v.put(key,m.get(key));v.put("createdAt",m.get("created_at"));v.put("mode",Objects.requireNonNullElse(m.get("mode"),"build"));v.put("model",Objects.requireNonNullElse(m.get("model"),""));v.put("imageUrl",m.get("asset_id")==null?null:"/api/assets/"+m.get("asset_id"));return v;
  }).toList());
  var runs=db.queryForList("SELECT * FROM generation_runs WHERE project_id=? ORDER BY created_at DESC LIMIT 1",id);
  result.put("run",runs.isEmpty()?null:runView(runs.getFirst()));return result;
 }
 void message(String project,String role,String content) {db.update("INSERT INTO messages VALUES(?,?,?,?,?)",id(),project,role,content,now());}
 Map<String,Object> runView(Map<String,Object> r) {
  var result=new LinkedHashMap<String,Object>();for(String key:List.of("id","status","error","plan")) result.put(key,r.get(key));
  var options=db.queryForList("SELECT mode,model FROM run_options WHERE run_id=?",r.get("id"));
  result.put("mode",options.isEmpty()?"build":options.getFirst().get("mode"));result.put("model",options.isEmpty()?"":options.getFirst().get("model"));
  result.put("projectId",r.get("project_id"));result.put("baseVersion",r.get("base_version"));result.put("createdAt",r.get("created_at"));return result;
 }
 Map<String,Object> run(String id,String owner) {
  var rows=db.queryForList("SELECT r.* FROM generation_runs r JOIN projects p ON p.id=r.project_id WHERE r.id=? AND p.owner_id=?",id,owner);
  if(rows.isEmpty()) throw error(404,"任务不存在");return runView(rows.getFirst());
 }
 Map<String,Object> queue(String pid,String owner,String prompt,String base,String mode,String model) {
  return tx.execute(status -> {
   var p=project(pid,owner);String rid=id();
   if(!Objects.equals(base,p.get("current_version"))) throw error(409,"版本已更新，请刷新后重试");
   if(db.update("UPDATE projects SET active_run=?,updated_at=? WHERE id=? AND active_run IS NULL",rid,now(),pid)!=1) throw error(409,"项目已有任务正在执行");
   db.update("INSERT INTO generation_runs(id,project_id,status,base_version,prompt,created_at) VALUES(?,?,'QUEUED',?,?,?)",rid,pid,base,prompt,now());
   db.update("INSERT INTO run_options VALUES(?,?,?)",rid,mode,model);
   db.update("INSERT INTO run_checkpoints(run_id,attempt) VALUES(?,0)",rid);
   richMessage(pid,"user",prompt,rid,mode,model,null);return run(rid,owner);
  });
 }
 void stage(String id,String status) {db.update("UPDATE generation_runs SET status=? WHERE id=?",status,id);}
 void plan(String id,String plan) {db.update("UPDATE generation_runs SET plan=? WHERE id=?",plan,id);}
 Map<String,Object> save(String pid,String owner,String base,Map<String,String> files,String summary,String runId) {
  return save(pid,owner,base,files,summary,runId,null);
 }
 Map<String,Object> save(String pid,String owner,String base,Map<String,String> files,String summary,String runId,String token) {
  return tx.execute(status -> {
   // Serialize edits/restores/generation commits on the project row.
   db.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE",pid);
   var p=project(pid,owner);
   if(token!=null)requireExecution(runId,token);
   if(!Objects.equals(base,p.get("current_version"))) throw error(409,"当前版本已变化，请刷新后重试");
   if(!Objects.equals(runId,p.get("active_run"))) throw error(409,"生成中暂不能保存或恢复版本");
   int number=db.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM project_versions WHERE project_id=?",Integer.class,pid);
   String vid=id();db.update("INSERT INTO project_versions VALUES(?,?,?,?,?,?)",vid,pid,number,summary,encode(files),now());
   db.update("UPDATE projects SET current_version=?,active_run=NULL,updated_at=? WHERE id=?",vid,now(),pid);
   if(runId!=null) {stage(runId,"SUCCEEDED");richMessage(pid,"assistant",summary,runId,"build",runModel(runId),null);}
   return version(db.queryForMap("SELECT * FROM project_versions WHERE id=?",vid));
  });
 }
 String runMode(String rid) {return db.queryForObject("SELECT mode FROM run_options WHERE run_id=?",String.class,rid);}
 String runModel(String rid) {return db.queryForObject("SELECT model FROM run_options WHERE run_id=?",String.class,rid);}
 void richMessage(String pid,String role,String content,String rid,String mode,String model,String assetId) {
  String mid=id();db.update("INSERT INTO messages VALUES(?,?,?,?,?)",mid,pid,role,content,now());
  db.update("INSERT INTO message_details VALUES(?,?,?,?,?)",mid,rid,mode,model,assetId);
 }
 ProjectContext context(String pid,String owner,String base,String currentRun) {
  var p=project(pid,owner);
  if(base!=null)requireVersion(pid,base);
  // Fetch complete turns so timestamp ties never reverse user/assistant order.
  var turns=db.queryForList("SELECT r.id,r.status,r.prompt,r.error,COALESCE(o.mode,'build') AS mode FROM generation_runs r LEFT JOIN run_options o ON o.run_id=r.id WHERE r.project_id=? AND r.id<>? AND r.status IN ('SUCCEEDED','FAILED') AND COALESCE(o.mode,'build') IN ('build','chat') ORDER BY r.created_at DESC,r.id DESC LIMIT 20",pid,currentRun);
  Collections.reverse(turns);
  var first=db.queryForList("SELECT r.id,r.status,r.prompt,r.error,COALESCE(o.mode,'build') AS mode FROM generation_runs r LEFT JOIN run_options o ON o.run_id=r.id WHERE r.project_id=? AND r.id<>? AND r.status IN ('SUCCEEDED','FAILED') AND COALESCE(o.mode,'build') IN ('build','chat') ORDER BY r.created_at,r.id LIMIT 1",pid,currentRun);
  if(!first.isEmpty() && turns.stream().noneMatch(t->t.get("id").equals(first.getFirst().get("id"))))turns.addFirst(first.getFirst());
  var history=new ArrayList<Map<String,String>>();
  for(var turn:turns) {
   history.add(Map.of("role","user","content",(String)turn.get("prompt")));
   if("FAILED".equals(turn.get("status"))) {
    history.add(Map.of("role","assistant","content","此请求未完成，源码未更新。失败原因："+Objects.requireNonNullElse(turn.get("error"),"未知错误")));
   } else {
    var replies=db.queryForList("SELECT m.content FROM messages m JOIN message_details d ON d.message_id=m.id WHERE d.run_id=? AND m.project_id=? AND m.role='assistant' ORDER BY m.created_at,m.id",turn.get("id"),pid);
    for(var reply:replies)history.add(Map.of("role","assistant","content",(String)reply.get("content")));
   }
  }
  return new ProjectContext((String)p.get("title"),base,boundedHistory(history),versionFiles(base));
 }
 static List<Map<String,String>> boundedHistory(List<Map<String,String>> history) {
  // Retain the initial brief as well as the latest changes, with a fixed budget.
  if(history.isEmpty())return List.of();
  final int budget=24000, perMessage=6000, maxMessages=40;
  var result=new ArrayList<Map<String,String>>();
  var first=history.getFirst();String brief=clipHistory(first.get("content"),perMessage);
  result.add(Map.of("role",first.get("role"),"content",brief));
  int remaining=budget-brief.length()-200;
  var recent=new ArrayList<Map<String,String>>();
  for(int i=history.size()-1;i>0 && recent.size()<maxMessages-1;i--) {
   var message=history.get(i);String content=clipHistory(message.get("content"),perMessage);
   if(content.length()>remaining)break;
   recent.addFirst(Map.of("role",message.get("role"),"content",content));remaining-=content.length();
  }
  if(recent.size()+1<history.size())result.add(Map.of("role","assistant","content","部分较早的项目对话已因长度限制省略；请以当前源码和本次需求为准。"));
  result.addAll(recent);return result;
 }
 static String clipHistory(String content,int limit) {
  String note="\n[此条较长，已截断]";
  return content.length()<=limit?content:content.substring(0,limit-note.length())+note;
 }
 static class StaleExecution extends RuntimeException {}
 Map<String,Object> lockRun(String rid,String owner) {
  var view=run(rid,owner);String pid=(String)view.get("projectId");
  db.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE",pid);
  return db.queryForMap("SELECT * FROM generation_runs WHERE id=?",rid);
 }
 void requireExecution(String rid,String token) {
  var tokens=db.queryForList("SELECT c.execution_token,r.status FROM run_checkpoints c JOIN generation_runs r ON r.id=c.run_id WHERE c.run_id=?",rid);
  if(tokens.isEmpty() || !Objects.equals(token,tokens.getFirst().get("execution_token")) || Set.of("PAUSED","SUCCEEDED","FAILED").contains(tokens.getFirst().get("status")))throw new StaleExecution();
 }
 String claim(String rid,String owner) {
  return tx.execute(status->{
   var r=lockRun(rid,owner);
   if(!"QUEUED".equals(r.get("status")))throw new StaleExecution();
   var rows=db.queryForList("SELECT execution_token FROM run_checkpoints WHERE run_id=?",rid);
   if(rows.isEmpty())db.update("INSERT INTO run_checkpoints(run_id,attempt) VALUES(?,0)",rid);
   else if(rows.getFirst().get("execution_token")!=null)throw new StaleExecution();
   String token=id();db.update("UPDATE run_checkpoints SET execution_token=? WHERE run_id=?",token,rid);return token;
  });
 }
 String pause(String rid,String owner) {return pause(rid,owner,null);}
 String pause(String rid,String owner,String expectedToken) {
  return tx.execute(status->{
   var r=lockRun(rid,owner);
   if(expectedToken!=null)requireExecution(rid,expectedToken);
   if(Set.of("SUCCEEDED","FAILED").contains(r.get("status")))throw error(409,"任务已结束，不能暂停");
   var rows=db.queryForList("SELECT execution_token FROM run_checkpoints WHERE run_id=?",rid);
   String token=rows.isEmpty()?null:(String)rows.getFirst().get("execution_token");
   db.update("UPDATE run_checkpoints SET execution_token=NULL WHERE run_id=?",rid);
   db.update("UPDATE generation_runs SET status='PAUSED',error=NULL WHERE id=?",rid);return token;
  });
 }
 Map<String,Object> resume(String rid,String owner) {
  return tx.execute(status->{
   var r=lockRun(rid,owner);String pid=(String)r.get("project_id");
   if(!"PAUSED".equals(r.get("status")))throw error(409,"只有已暂停的任务可以恢复");
   var p=project(pid,owner);
   if(!Objects.equals(p.get("active_run"),rid) || !Objects.equals(p.get("current_version"),r.get("base_version")))throw error(409,"项目版本已变化，无法恢复此任务");
   db.update("UPDATE generation_runs SET status='QUEUED',error=NULL WHERE id=?",rid);return r;
  });
 }
 Map<String,Object> checkpoint(String rid) {return db.queryForMap("SELECT * FROM run_checkpoints WHERE run_id=?",rid);}
 void stage(String rid,String owner,String token,String stage) {
  tx.executeWithoutResult(s->{lockRun(rid,owner);requireExecution(rid,token);stage(rid,stage);});
 }
 void plan(String rid,String owner,String token,String plan) {
  tx.executeWithoutResult(s->{lockRun(rid,owner);requireExecution(rid,token);plan(rid,plan);});
 }
 void checkpoint(String rid,String owner,String token,Map<String,String> files,String failure,int attempt) {
  tx.executeWithoutResult(s->{lockRun(rid,owner);requireExecution(rid,token);db.update("UPDATE run_checkpoints SET files=?,failure=?,attempt=? WHERE run_id=?",files==null?null:encode(files),failure,attempt,rid);});
 }
 void finishReply(String rid,String pid,String content,String mode,String model,byte[] image,String mediaType,String prompt) {
  finishReply(rid,pid,content,mode,model,image,mediaType,prompt,null);
 }
 void finishReply(String rid,String pid,String content,String mode,String model,byte[] image,String mediaType,String prompt,String token) {
  tx.executeWithoutResult(status -> {
   var rows=db.queryForList("SELECT active_run FROM projects WHERE id=? FOR UPDATE",pid);
   if(token!=null)requireExecution(rid,token);
   if(rows.isEmpty()||!Objects.equals(rows.getFirst().get("active_run"),rid))throw error(409,"任务已失效");
   String asset=null;if(image!=null){asset=id();db.update("INSERT INTO image_assets VALUES(?,?,?,?,?,?,?)",asset,pid,prompt,model,mediaType,image,now());}
   richMessage(pid,"assistant",content,rid,mode,model,asset);stage(rid,"SUCCEEDED");
   db.update("UPDATE projects SET active_run=NULL,updated_at=? WHERE id=?",now(),pid);
  });
 }
 Map<String,Object> asset(String id,String owner) {
  var rows=db.queryForList("SELECT a.media_type,a.image_data FROM image_assets a JOIN projects p ON p.id=a.project_id WHERE a.id=? AND p.owner_id=?",id,owner);
  if(rows.isEmpty())throw error(404,"图片不存在");return rows.getFirst();
 }
 void fail(String rid,String pid,String message) {fail(rid,pid,message,null);}
 void fail(String rid,String pid,String message,String token) {
  tx.executeWithoutResult(s -> {
   db.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE",pid);
   if(token!=null){try{requireExecution(rid,token);}catch(StaleExecution e){return;}}
   db.update("UPDATE generation_runs SET status='FAILED',error=? WHERE id=?",message,rid);db.update("UPDATE projects SET active_run=NULL WHERE id=? AND active_run=?",pid,rid);richMessage(pid,"assistant","任务未完成："+message+"。已有版本未被覆盖。",rid,runMode(rid),runModel(rid),null);});
 }
}
