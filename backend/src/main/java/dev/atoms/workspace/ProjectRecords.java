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
 public List<Map<String,Object>> projects(String owner) {
  return db.queryForList("""
          SELECT p.*, o.mode AS latest_mode, o.model AS latest_model, rp.pattern AS latest_pattern,
                 (SELECT COUNT(*) FROM generation_runs r WHERE r.project_id=p.id) AS run_count,
                 (SELECT SUM(c.usd) FROM generation_runs r JOIN agent_model_cost c ON c.run_id=r.id
                   WHERE r.project_id=p.id) AS cost_usd,
                 (SELECT SUM(u.input_tokens + u.output_tokens) FROM generation_runs r
                   JOIN agent_model_usage u ON u.run_id=r.id WHERE r.project_id=p.id) AS total_tokens
          FROM projects p
          LEFT JOIN generation_runs latest ON latest.id=(
              SELECT r.id FROM generation_runs r WHERE r.project_id=p.id
              ORDER BY r.created_at DESC, r.id DESC LIMIT 1)
          LEFT JOIN run_options o ON o.run_id=latest.id
          LEFT JOIN run_patterns rp ON rp.run_id=latest.id
          WHERE p.owner_id=? ORDER BY p.updated_at DESC, p.id
          """, owner).stream().map(row -> {
   var result = projectView(row);
   result.put("latestMode", row.get("latest_mode"));
   result.put("latestModel", row.get("latest_model"));
   result.put("latestPattern", row.get("latest_pattern"));
   result.put("runCount", row.get("run_count"));
   result.put("costUsd", row.get("cost_usd"));
   result.put("totalTokens", row.get("total_tokens"));
   return result;
  }).toList();
 }
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
