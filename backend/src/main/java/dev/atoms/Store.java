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
  db.update("UPDATE generation_runs SET status='FAILED',error='服务重启中断了任务，请重试' WHERE status NOT IN ('SUCCEEDED','FAILED')");
  db.update("UPDATE projects SET active_run=NULL WHERE active_run IS NOT NULL");
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
   richMessage(pid,"user",prompt,rid,mode,model,null);return run(rid,owner);
  });
 }
 void stage(String id,String status) {db.update("UPDATE generation_runs SET status=? WHERE id=?",status,id);}
 void plan(String id,String plan) {db.update("UPDATE generation_runs SET plan=? WHERE id=?",plan,id);}
 Map<String,Object> save(String pid,String owner,String base,Map<String,String> files,String summary,String runId) {
  return tx.execute(status -> {
   // Serialize edits/restores/generation commits on the project row.
   db.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE",pid);
   var p=project(pid,owner);
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
 List<Map<String,String>> chatHistory(String pid) {
  var rows=db.queryForList("SELECT m.role,m.content FROM messages m JOIN message_details d ON d.message_id=m.id JOIN generation_runs r ON r.id=d.run_id WHERE m.project_id=? AND d.mode='chat' AND r.status='SUCCEEDED' ORDER BY m.created_at DESC,m.id DESC LIMIT 20",pid);
  Collections.reverse(rows);var history=new ArrayList<Map<String,String>>();int size=0;
  for(int i=rows.size()-1;i>=0;i--){var m=rows.get(i);String content=(String)m.get("content");if(size+content.length()>24000)break;size+=content.length();history.addFirst(Map.of("role",(String)m.get("role"),"content",content));}return history;
 }
 void finishReply(String rid,String pid,String content,String mode,String model,byte[] image,String mediaType,String prompt) {
  tx.executeWithoutResult(status -> {
   var rows=db.queryForList("SELECT active_run FROM projects WHERE id=? FOR UPDATE",pid);
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
 void fail(String rid,String pid,String message) {
  tx.executeWithoutResult(s -> {db.update("UPDATE generation_runs SET status='FAILED',error=? WHERE id=?",message,rid);db.update("UPDATE projects SET active_run=NULL WHERE id=? AND active_run=?",pid,rid);richMessage(pid,"assistant","任务未完成："+message+"。已有版本未被覆盖。",rid,runMode(rid),runModel(rid),null);});
 }
}
