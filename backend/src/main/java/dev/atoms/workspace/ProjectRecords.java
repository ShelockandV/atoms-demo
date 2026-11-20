package dev.atoms.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/**
 * 通过 JDBC 读取项目、版本、消息和图片记录。调用方应先验证项目归属。
 *
 * <p>示例：<code>records.detailWithMessages(projectId, owner)</code>。</p>
 */
public final class ProjectRecords {
 final JdbcTemplate db;
 final ObjectMapper json;

 public ProjectRecords(JdbcTemplate db, ObjectMapper json) {
  this.db = db;
  this.json = json;
 }

 private Map<String,String> files(String raw) {
  try { return json.readValue(raw, new TypeReference<>() {}); }
  catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
 }

 private static ResponseStatusException error(int code, String message) {
  return new ResponseStatusException(HttpStatus.valueOf(code), message);
 }

 public Map<String,Object> project(String id, String owner) {
  var rows=db.queryForList("SELECT * FROM projects WHERE id=? AND owner_id=?",id,owner);
  if(rows.isEmpty()) throw error(404,"项目不存在");
  return rows.getFirst();
 }
 private Map<String,Object> projectView(Map<String,Object> p) {
  var result=new LinkedHashMap<String,Object>();
  result.put("id",p.get("id")); result.put("title",p.get("title")); result.put("currentVersion",p.get("current_version")); result.put("updatedAt",p.get("updated_at")); return result;
 }
 public List<Map<String,Object>> projects(String owner) { return db.queryForList("SELECT * FROM projects WHERE owner_id=? ORDER BY updated_at DESC",owner).stream().map(this::projectView).toList(); }
 public Map<String,Object> create(String owner,String title) {
  String id=UUID.randomUUID().toString(); db.update("INSERT INTO projects(id,owner_id,title,updated_at) VALUES(?,?,?,?)",id,owner,title,Instant.now().toString()); return projectView(project(id,owner));
 }
 public Map<String,Object> version(Map<String,Object> row) {
  var v=new LinkedHashMap<String,Object>(); v.put("id",row.get("id"));v.put("number",row.get("version_number"));v.put("summary",row.get("summary"));v.put("files",files((String)row.get("files")));v.put("createdAt",row.get("created_at"));return v;
 }
 public List<Map<String,Object>> versions(String project) {return db.queryForList("SELECT * FROM project_versions WHERE project_id=? ORDER BY version_number DESC",project).stream().map(this::version).toList();}
 public void requireVersion(String pid,String vid) {
  if(db.queryForObject("SELECT COUNT(*) FROM project_versions WHERE project_id=? AND id=?",Integer.class,pid,vid)!=1)throw error(404,"版本不存在");
 }
 public Map<String,String> versionFiles(String version) {
  if(version==null) return Map.of();
  return files(db.queryForObject("SELECT files FROM project_versions WHERE id=?",String.class,version));
 }
 public Map<String,Object> detailWithMessages(String id,String owner) {
  var p=project(id,owner);var result=projectView(p);
  result.put("versions",versions(id));
  result.put("messages",db.queryForList("SELECT m.*,d.mode,d.model,d.asset_id FROM messages m LEFT JOIN message_details d ON d.message_id=m.id WHERE m.project_id=? ORDER BY m.created_at,m.id",id).stream().map(m -> {
   var v=new LinkedHashMap<String,Object>();for(String key:List.of("id","role","content"))v.put(key,m.get(key));v.put("createdAt",m.get("created_at"));v.put("mode",Objects.requireNonNullElse(m.get("mode"),"build"));v.put("model",Objects.requireNonNullElse(m.get("model"),""));v.put("imageUrl",m.get("asset_id")==null?null:"/api/assets/"+m.get("asset_id"));return v;
  }).toList());
  return result;
 }
 public Map<String,Object> asset(String id,String owner) {
  var rows=db.queryForList("SELECT a.media_type,a.image_data FROM image_assets a JOIN projects p ON p.id=a.project_id WHERE a.id=? AND p.owner_id=?",id,owner);
  if(rows.isEmpty())throw error(404,"图片不存在");return rows.getFirst();
 }
}
