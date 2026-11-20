package dev.atoms.agent.persistence;

import dev.atoms.agent.skill.Skill;

import dev.atoms.agent.AgentLoop;

import dev.atoms.workspace.ProjectContext;
import dev.atoms.workspace.PromptSimilarity;

import dev.atoms.agent.pattern.PatternRegistry;
import dev.atoms.agent.pattern.WorkflowTemplate;
import dev.atoms.workspace.ProjectRecords;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;

/**
 * 协调任务生命周期和跨表事务，并核验任务的执行令牌。
 *
 * <p>示例：生成成功时，save(...) 在同一事务中新增版本并标记任务完成。</p>
 */
@Repository
public class Store {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ProjectRecords projects;
    private final RunJournal journal;
    private final Map<String, ModelRate> modelRates;

    public Store(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json) {
        this(db, tx, json, Map.of());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public Store(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json, org.springframework.core.env.Environment environment) {
        this(db, tx, json, org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("atoms.model.rates", org.springframework.boot.context.properties.bind.Bindable.mapOf(String.class, ModelRate.class))
                .orElse(Map.of()));
    }

    private Store(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json, Map<String, ModelRate> modelRates) {
        this.db = db;
        this.tx = tx;
        this.json = json;
        this.projects = new ProjectRecords(db, json);
        this.journal = new RunJournal(db, json);
        this.modelRates = Map.copyOf(modelRates);
    }

    /** 设置每百万 token 的美元价格，例如输入 0.25、输出 1.00。 */
    public record ModelRate(BigDecimal input, BigDecimal output) {
        public ModelRate {
            if (input == null || output == null || input.signum() < 0 || output.signum() < 0)
                throw new IllegalArgumentException("模型输入和输出费率须为非负数");
        }
    }

    public void recoverJobs() {
        db.update("UPDATE generation_runs SET status='PAUSED',error=NULL WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED','PAUSED','AWAITING_APPROVAL')");
        db.update("UPDATE run_checkpoints SET execution_token=NULL");
        db.update("UPDATE projects SET active_run=NULL WHERE active_run IS NOT NULL AND active_run NOT IN (SELECT id FROM generation_runs WHERE status IN ('PAUSED','AWAITING_APPROVAL'))");
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }

    static String now() {
        return Instant.now().toString();
    }

    public static ResponseStatusException error(int code, String message) {
        return new ResponseStatusException(HttpStatus.valueOf(code), message);
    }

    public String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, String> files(String raw) {
        try {
            return json.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Object> project(String id, String owner) {
        return projects.project(id, owner);
    }

    public List<Map<String, Object>> projects(String owner) {
        return projects.projects(owner);
    }

    public Map<String, Object> create(String owner, String title) {
        return projects.create(owner, title);
    }

    public List<Map<String, Object>> versions(String project) {
        return projects.versions(project);
    }

    public void requireVersion(String pid, String vid) {
        projects.requireVersion(pid, vid);
    }

    public Map<String, String> versionFiles(String version) {
        return projects.versionFiles(version);
    }

    public Map<String, Object> detail(String id, String owner) {
        var result = projects.detailWithMessages(id, owner);
        var runs = db.queryForList("SELECT * FROM generation_runs WHERE project_id=? ORDER BY created_at DESC LIMIT 1", id);
        result.put("run", runs.isEmpty() ? null : runView(runs.getFirst()));
        return result;
    }

    void message(String project, String role, String content) {
        db.update("INSERT INTO messages VALUES(?,?,?,?,?)", id(), project, role, content, now());
    }

    Map<String, Object> runView(Map<String, Object> r) {
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("id", "status", "error", "plan")) result.put(key, r.get(key));
        var options = db.queryForList("SELECT mode,model FROM run_options WHERE run_id=?", r.get("id"));
        result.put("mode", options.isEmpty() ? "build" : options.getFirst().get("mode"));
        result.put("model", options.isEmpty() ? "" : options.getFirst().get("model"));
        result.put("pattern", runPattern((String) r.get("id")));
        result.put("nodes", dagNodes((String) r.get("id")));
        result.put("projectId", r.get("project_id"));
        result.put("baseVersion", r.get("base_version"));
        result.put("createdAt", r.get("created_at"));
        String rid = (String) r.get("id");
        var skill = runSkill(rid);
        if (skill != null) result.put("skill", skill.reference());
        var template = runTemplate(rid);
        if (template != null) {
            result.put("template", template.name());
            result.put("templateVersion", template.version());
        }
        result.put("steps", journal.steps(rid));
        var usage = journal.usage(rid);
        if (!usage.isEmpty()) result.put("usage", usage);
        return result;
    }

    public Map<String, Object> run(String id, String owner) {
        var rows = db.queryForList("SELECT r.* FROM generation_runs r JOIN projects p ON p.id=r.project_id WHERE r.id=? AND p.owner_id=?", id, owner);
        if (rows.isEmpty()) throw error(404, "任务不存在");
        return runView(rows.getFirst());
    }

    public Map<String, Object> simpleTask(String id, String owner) {
        var rows = db.queryForList("""
                SELECT r.id,r.project_id,r.prompt,r.status,r.error,r.created_at,o.mode
                FROM generation_runs r JOIN projects p ON p.id=r.project_id
                JOIN run_options o ON o.run_id=r.id
                WHERE r.id=? AND p.owner_id=? AND (o.mode='chat'
                    OR EXISTS (SELECT 1 FROM gateway_task_context c WHERE c.run_id=r.id))
                AND NOT EXISTS (SELECT 1 FROM gateway_deleted_sessions x WHERE x.id=p.id)
                """, id, owner);
        if (rows.isEmpty()) throw error(404, "任务不存在");
        var row = rows.getFirst();
        var result = new LinkedHashMap<String, Object>();
        result.put("task_id", id);
        result.put("workflow_id", id);
        result.put("session_id", row.get("project_id"));
        result.put("query", row.get("prompt"));
        result.put("created_at", row.get("created_at"));
        result.put("mode", gatewayMode(id, (String) row.get("mode")));
        if ("AWAITING_APPROVAL".equals(row.get("status"))) result.put("awaiting_approval", true);
        String status = shannonStatus((String) row.get("status"));
        result.put("status", "TASK_STATUS_" + status);
        if (row.get("error") != null) result.put("error", row.get("error"));
        if ("COMPLETED".equals(status)) {
            var replies = db.queryForList("""
                    SELECT m.content,m.created_at FROM messages m JOIN message_details d ON d.message_id=m.id
                    WHERE d.run_id=? AND m.role='assistant' ORDER BY m.created_at DESC,m.id DESC LIMIT 1
                    """, id);
            if (!replies.isEmpty()) {
                result.put("result", replies.getFirst().get("content"));
                result.put("updated_at", replies.getFirst().get("created_at"));
            }
        }
        return result;
    }

    public Map<String, Object> createGatewaySession(String owner) {
        return tx.execute(status -> {
            var project = create(owner, "Shannon session");
            db.update("INSERT INTO gateway_sessions(id,created_at) VALUES(?,?)", project.get("id"), now());
            return project;
        });
    }

    public Map<String, Object> gatewaySession(String id, String owner) {
        return gatewaySessions(owner, id).stream().findFirst().orElseThrow(() -> error(404, "会话不存在"));
    }

    public List<Map<String, Object>> gatewaySessions(String owner, String id) {
        return db.queryForList("""
                SELECT s.id AS session_id,p.owner_id AS user_id,p.title,s.created_at,p.updated_at,
                       COUNT(r.id) AS task_count,COALESCE(SUM(u.prompt_tokens+u.completion_tokens),0) AS tokens_used
                FROM gateway_sessions s JOIN projects p ON p.id=s.id
                LEFT JOIN generation_runs r ON r.project_id=s.id
                LEFT JOIN agent_usage u ON u.run_id=r.id
                WHERE p.owner_id=? AND (?='' OR s.id=?)
                AND NOT EXISTS (SELECT 1 FROM gateway_deleted_sessions x WHERE x.id=s.id)
                GROUP BY s.id,p.owner_id,p.title,s.created_at,p.updated_at
                ORDER BY p.updated_at DESC,s.id DESC
                """, owner, id, id).stream().map(row -> {
            row.put("is_research_session", false);
            return row;
        }).toList();
    }

    public Map<String, Object> updateGatewaySession(String id, String owner, String title) {
        return tx.execute(status -> {
            db.queryForList("SELECT id FROM projects WHERE id=? AND owner_id=? FOR UPDATE", id, owner);
            gatewaySession(id, owner);
            db.update("UPDATE projects SET title=?,updated_at=? WHERE id=?", title, now(), id);
            return gatewaySession(id, owner);
        });
    }

    public void deleteGatewaySession(String id, String owner) {
        tx.executeWithoutResult(status -> {
            var rows = db.queryForList("SELECT active_run FROM projects WHERE id=? AND owner_id=? FOR UPDATE", id, owner);
            gatewaySession(id, owner);
            if (rows.getFirst().get("active_run") != null) throw error(409, "会话仍有活动任务");
            db.update("INSERT INTO gateway_deleted_sessions(id) VALUES(?)", id);
        });
    }

    public Map<String, Object> listSimpleTasks(String owner, String session, String status, int limit, int offset) {
        String filter = switch (status.toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> " AND r.status='SUCCEEDED'";
            case "RUNNING" -> " AND r.status NOT IN ('SUCCEEDED','QUEUED','FAILED','PAUSED','CANCELLED','TIMEOUT')";
            case "CANCELLED", "CANCELED" -> " AND r.status='CANCELLED'";
            case "FAILED" -> " AND r.status='FAILED'";
            case "QUEUED" -> " AND r.status='QUEUED'";
            case "TIMEOUT" -> " AND r.status='TIMEOUT'";
            default -> "";
        };
        String source = """
                 FROM generation_runs r JOIN projects p ON r.project_id=p.id
                 JOIN run_options o ON o.run_id=r.id WHERE p.owner_id=? AND (o.mode='chat'
                    OR EXISTS (SELECT 1 FROM gateway_task_context c WHERE c.run_id=r.id))
                 AND NOT EXISTS (SELECT 1 FROM gateway_deleted_sessions x WHERE x.id=p.id)
                """ + filter;
        var args = new ArrayList<Object>();
        args.add(owner);
        if (!session.isEmpty()) {
            source += " AND r.project_id=?";
            args.add(session);
        }
        long total = db.queryForObject("SELECT COUNT(*)" + source, Long.class, args.toArray());
        args.add(limit);
        args.add(offset);
        var rows = db.queryForList("""
                SELECT r.id,r.prompt,r.status,r.created_at,o.mode,
                    COALESCE((SELECT MAX(m.created_at) FROM message_details d JOIN messages m ON m.id=d.message_id
                     WHERE d.run_id=r.id AND m.role='assistant' AND r.status IN ('SUCCEEDED','FAILED')),
                     (SELECT MAX(e.created_at) FROM task_events e WHERE e.run_id=r.id
                      AND e.event_type='WORKFLOW_CANCELLED')) AS completed_at,
                    (SELECT u.prompt_tokens FROM agent_usage u WHERE u.run_id=r.id AND u.metered_calls>0) AS prompt_tokens,
                    (SELECT u.completion_tokens FROM agent_usage u WHERE u.run_id=r.id AND u.metered_calls>0) AS completion_tokens,
                    (SELECT COALESCE(SUM(c.usd),0) FROM agent_model_cost c WHERE c.run_id=r.id) AS cost_usd
                """ + source
                + " ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?", args.toArray());
        var tasks = rows.stream().map(row -> {
            var task = new LinkedHashMap<String, Object>();
            task.put("task_id", row.get("id"));
            task.put("query", row.get("prompt"));
            task.put("status", "TASK_STATUS_" + shannonStatus((String) row.get("status")));
            task.put("mode", gatewayMode((String) row.get("id"), (String) row.get("mode")));
            task.put("created_at", row.get("created_at"));
            if (row.get("completed_at") != null) task.put("completed_at", row.get("completed_at"));
            if (row.get("prompt_tokens") instanceof Number input && row.get("completion_tokens") instanceof Number output) {
                task.put("total_token_usage", Map.of("prompt_tokens", input, "completion_tokens", output,
                        "total_tokens", input.longValue() + output.longValue(), "cost_usd", row.get("cost_usd")));
            }
            return task;
        }).toList();
        return Map.of("tasks", tasks, "total_count", total);
    }

    public Map<String, Object> queue(String pid, String owner, String prompt, String base, String mode, String model) {
        return queue(pid, owner, prompt, base, mode, model, "react");
    }

    public Map<String, Object> queue(String pid, String owner, String prompt, String base, String mode, String model, String pattern) {
        return queue(pid, owner, prompt, base, mode, model, pattern, null);
    }

    public Map<String, Object> queue(String pid, String owner, String prompt, String base, String mode, String model, String pattern,
                                     Skill skill) {
        return queue(pid, owner, prompt, base, mode, model, pattern, skill, null);
    }

    public Map<String, Object> queue(String pid, String owner, String prompt, String base, String mode, String model, String pattern,
                                     Skill skill, WorkflowTemplate template) {
        return queue(pid, owner, prompt, base, mode, model, pattern, skill, template, null);
    }

    public Map<String, Object> queue(String pid, String owner, String prompt, String base, String mode, String model, String pattern,
                                     Skill skill, WorkflowTemplate template, Map<String, Object> context) {
        String selectedPattern = PatternRegistry.require(pattern).name();
        return tx.execute(status -> {
            if (context != null) {
                db.queryForList("SELECT id FROM projects WHERE id=? AND owner_id=? FOR UPDATE", pid, owner);
                gatewaySession(pid, owner);
            }
            var p = project(pid, owner);
            String rid = id();
            if (!Objects.equals(base, p.get("current_version"))) throw error(409, "版本已更新，请刷新后重试");
            if (db.update("UPDATE projects SET active_run=?,updated_at=? WHERE id=? AND active_run IS NULL", rid, now(), pid) != 1)
                throw error(409, "项目已有任务正在执行");
            String initialStatus = template != null && template.requiresApproval() ? "AWAITING_APPROVAL" : "QUEUED";
            db.update("INSERT INTO generation_runs(id,project_id,status,base_version,prompt,created_at) VALUES(?,?,?,?,?,?)", rid, pid, initialStatus, base, prompt, now());
            db.update("INSERT INTO run_options VALUES(?,?,?)", rid, mode, model);
            if (context != null)
                db.update("INSERT INTO gateway_task_context VALUES(?,?)", rid, encode(context));
            db.update("INSERT INTO run_patterns(run_id,pattern) VALUES(?,?)", rid, selectedPattern);
            db.update("INSERT INTO run_checkpoints(run_id,attempt) VALUES(?,0)", rid);
            if (skill != null) db.update("INSERT INTO run_skills(run_id,snapshot) VALUES(?,?)", rid, encode(skill));
            if (template != null)
                db.update("INSERT INTO run_workflow_templates(run_id,snapshot) VALUES(?,?)", rid, encode(template));
            richMessage(pid, "user", prompt, rid, mode, model, null);
            return run(rid, owner);
        });
    }

    public String taskContext(String rid) {
        return db.queryForList("SELECT context_json FROM gateway_task_context WHERE run_id=?", String.class, rid)
                .stream().filter(value -> !"{}".equals(value)).findFirst().orElse("");
    }

    public Map<String, Object> gatewayHistory(String id, String owner) {
        gatewaySession(id, owner);
        var rows = db.queryForList("""
                SELECT r.id AS task_id,r.id AS workflow_id,r.prompt AS query,r.status,r.error AS error_message,
                    c.context_json,o.mode,
                    COALESCE(u.prompt_tokens+u.completion_tokens,0) AS total_tokens,
                    (SELECT COALESCE(SUM(k.usd),0) FROM agent_model_cost k WHERE k.run_id=r.id) AS total_cost_usd,
                    (SELECT COUNT(*) FROM agent_steps a WHERE a.run_id=r.id) AS tools_invoked,
                    COALESCE((SELECT MIN(e.created_at) FROM task_events e
                        WHERE e.run_id=r.id AND e.event_type='WORKFLOW_STARTED'),r.created_at) AS started_at,
                    COALESCE((SELECT MAX(e.created_at) FROM task_events e WHERE e.run_id=r.id AND
                        e.event_type IN ('WORKFLOW_COMPLETED','WORKFLOW_FAILED','WORKFLOW_CANCELLED')),
                        (SELECT MAX(m.created_at) FROM message_details d JOIN messages m ON m.id=d.message_id
                         WHERE d.run_id=r.id AND m.role='assistant' AND r.status IN ('SUCCEEDED','FAILED'))) AS completed_at,
                    (SELECT m.content FROM message_details d JOIN messages m ON m.id=d.message_id
                     WHERE d.run_id=r.id AND m.role='assistant' AND r.status='SUCCEEDED'
                     ORDER BY m.created_at DESC,m.id DESC LIMIT 1) AS result
                FROM generation_runs r JOIN projects p ON p.id=r.project_id
                JOIN run_options o ON o.run_id=r.id
                LEFT JOIN agent_usage u ON u.run_id=r.id
                LEFT JOIN gateway_task_context c ON c.run_id=r.id
                WHERE r.project_id=? AND p.owner_id=?
                    AND (o.mode='chat' OR c.run_id IS NOT NULL)
                    AND NOT EXISTS (SELECT 1 FROM gateway_deleted_sessions x WHERE x.id=p.id)
                ORDER BY started_at,r.id
                """, id, owner);
        for (var row : rows) {
            row.put("status", "TASK_STATUS_" + shannonStatus((String) row.get("status")));
            row.put("mode", gatewayMode((String) row.get("task_id"), (String) row.get("mode")));
            row.put("agents_used", 0);
            if (row.get("completed_at") instanceof String end) {
                long duration = java.time.Duration.between(Instant.parse((String) row.get("started_at")), Instant.parse(end)).toMillis();
                row.put("duration_ms", Math.max(0, duration));
            }
            if (row.remove("context_json") instanceof String raw) {
                try {
                    row.put("metadata", Map.of("context", json.readTree(raw)));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalStateException("任务上下文读取失败", e);
                }
            }
            row.values().removeIf(Objects::isNull);
        }
        return Map.of("session_id", id, "tasks", rows, "total", rows.size());
    }

    private String gatewayMode(String id, String mode) {
        if ("chat".equals(mode)) return "EXECUTION_MODE_SIMPLE";
        return "dag".equals(runPattern(id)) || runTemplate(id) != null
                ? "EXECUTION_MODE_COMPLEX" : "EXECUTION_MODE_STANDARD";
    }

    public WorkflowTemplate runTemplate(String rid) {
        var rows = db.queryForList("SELECT snapshot FROM run_workflow_templates WHERE run_id=?", rid);
        if (rows.isEmpty()) return null;
        try {
            return json.readValue((String) rows.getFirst().get("snapshot"), WorkflowTemplate.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("无法读取任务工作流模板快照", e);
        }
    }

    public Skill runSkill(String rid) {
        var rows = db.queryForList("SELECT snapshot FROM run_skills WHERE run_id=?", rid);
        if (rows.isEmpty()) return null;
        try {
            return json.readValue((String) rows.getFirst().get("snapshot"), Skill.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("无法读取任务技能快照", e);
        }
    }

    void stage(String id, String status) {
        db.update("UPDATE generation_runs SET status=? WHERE id=?", status, id);
        switch (status) {
            case "ANSWERING", "PLANNING" -> db.update("""
                    INSERT INTO task_events(run_id,event_type,created_at)
                    SELECT ?, 'WORKFLOW_STARTED', ? WHERE NOT EXISTS
                    (SELECT 1 FROM task_events WHERE run_id=? AND event_type='WORKFLOW_STARTED')
                    """, id, now(), id);
            case "SUCCEEDED" -> taskEvent(id, "WORKFLOW_COMPLETED");
            case "CANCELLED" -> taskEvent(id, "WORKFLOW_CANCELLED");
        }
    }

    private void taskEvent(String id, String type) {
        db.update("INSERT INTO task_events(run_id,event_type,created_at) VALUES(?,?,?)", id, type, now());
    }

    public Map<String, Object> simpleTaskEvents(String id, String owner, int limit, int offset) {
        simpleTask(id, owner);
        var rows = db.queryForList("""
                SELECT event_id,event_type,created_at FROM task_events
                WHERE run_id=? ORDER BY event_id LIMIT ? OFFSET ?
                """, id, limit, offset);
        var events = rows.stream().map(row -> Map.of("workflow_id", id, "seq", row.get("event_id"),
                "type", row.get("event_type"), "timestamp", row.get("created_at"))).toList();
        return Map.of("events", events, "count", events.size());
    }

    public List<Map<String, Object>> taskEventsAfter(String id, long cursor) {
        return db.queryForList("""
                SELECT run_id AS workflow_id,event_id AS seq,event_type AS type,created_at AS timestamp
                FROM task_events WHERE run_id=? AND event_id>? ORDER BY event_id LIMIT 200
                """, id, cursor);
    }

    void plan(String id, String plan) {
        db.update("UPDATE generation_runs SET plan=? WHERE id=?", plan, id);
    }

    public Map<String, Object> save(String pid, String owner, String base, Map<String, String> files, String summary, String runId) {
        return save(pid, owner, base, files, summary, runId, null);
    }

    public Map<String, Object> save(String pid, String owner, String base, Map<String, String> files, String summary, String runId, String token) {
        return tx.execute(status -> {
            // Serialize edits/restores/generation commits on the project row.
            db.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE", pid);
            var p = project(pid, owner);
            if (token != null) requireExecution(runId, token);
            if (!Objects.equals(base, p.get("current_version"))) throw error(409, "当前版本已变化，请刷新后重试");
            if (!Objects.equals(runId, p.get("active_run"))) throw error(409, "生成中暂不能保存或恢复版本");
            int number = db.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM project_versions WHERE project_id=?", Integer.class, pid);
            String vid = id();
            db.update("INSERT INTO project_versions VALUES(?,?,?,?,?,?)", vid, pid, number, summary, encode(files), now());
            db.update("UPDATE projects SET current_version=?,active_run=NULL,updated_at=? WHERE id=?", vid, now(), pid);
            if (runId != null) {
                stage(runId, "SUCCEEDED");
                richMessage(pid, "assistant", summary, runId, "build", runModel(runId), null);
            }
            return projects.version(db.queryForMap("SELECT * FROM project_versions WHERE id=?", vid));
        });
    }

    public String runMode(String rid) {
        return db.queryForObject("SELECT mode FROM run_options WHERE run_id=?", String.class, rid);
    }

    public List<Map<String, Object>> routingHistory(String pid, String owner, boolean hasBaseVersion) {
        return db.queryForList("""
                SELECT r.prompt,r.status,p.pattern FROM generation_runs r
                JOIN projects j ON j.id=r.project_id
                JOIN run_patterns p ON p.run_id=r.id
                JOIN run_options o ON o.run_id=r.id
                LEFT JOIN run_skills s ON s.run_id=r.id
                LEFT JOIN run_workflow_templates w ON w.run_id=r.id
                WHERE j.id=? AND j.owner_id=? AND o.mode='build' AND r.status IN ('SUCCEEDED','FAILED')
                  AND s.run_id IS NULL AND w.run_id IS NULL
                  AND p.pattern IN ('react','reflection','dag')
                  AND %s
                ORDER BY r.created_at DESC,r.id DESC LIMIT 50
                """.formatted(hasBaseVersion ? "r.base_version IS NOT NULL" : "r.base_version IS NULL"), pid, owner);
    }

    public String runPattern(String rid) {
        var rows = db.queryForList("SELECT pattern FROM run_patterns WHERE run_id=?", rid);
        return rows.isEmpty() ? "react" : (String) rows.getFirst().get("pattern");
    }

    public String runModel(String rid) {
        return db.queryForObject("SELECT model FROM run_options WHERE run_id=?", String.class, rid);
    }

    void richMessage(String pid, String role, String content, String rid, String mode, String model, String assetId) {
        String mid = id();
        db.update("INSERT INTO messages VALUES(?,?,?,?,?)", mid, pid, role, content, now());
        db.update("INSERT INTO message_details VALUES(?,?,?,?,?)", mid, rid, mode, model, assetId);
    }

    public ProjectContext context(String pid, String owner, String base, String currentRun) {
        var p = project(pid, owner);
        if (base != null) requireVersion(pid, base);
        // Fetch complete turns so timestamp ties never reverse user/assistant order.
        var turns = db.queryForList("SELECT r.id,r.status,r.prompt,r.error,COALESCE(o.mode,'build') AS mode FROM generation_runs r LEFT JOIN run_options o ON o.run_id=r.id WHERE r.project_id=? AND r.id<>? AND r.status IN ('SUCCEEDED','FAILED') AND COALESCE(o.mode,'build') IN ('build','chat') ORDER BY r.created_at DESC,r.id DESC LIMIT 20", pid, currentRun);
        Collections.reverse(turns);
        var first = db.queryForList("SELECT r.id,r.status,r.prompt,r.error,COALESCE(o.mode,'build') AS mode FROM generation_runs r LEFT JOIN run_options o ON o.run_id=r.id WHERE r.project_id=? AND r.id<>? AND r.status IN ('SUCCEEDED','FAILED') AND COALESCE(o.mode,'build') IN ('build','chat') ORDER BY r.created_at,r.id LIMIT 1", pid, currentRun);
        if (!first.isEmpty() && turns.stream().noneMatch(t -> t.get("id").equals(first.getFirst().get("id"))))
            turns.addFirst(first.getFirst());
        var history = new ArrayList<Map<String, String>>();
        for (var turn : turns) {
            history.add(Map.of("role", "user", "content", (String) turn.get("prompt")));
            if ("FAILED".equals(turn.get("status"))) {
                history.add(Map.of("role", "assistant", "content", "此请求未完成，源码未更新。失败原因：" + Objects.requireNonNullElse(turn.get("error"), "未知错误")));
            } else {
                var replies = db.queryForList("SELECT m.content FROM messages m JOIN message_details d ON d.message_id=m.id WHERE d.run_id=? AND m.project_id=? AND m.role='assistant' ORDER BY m.created_at,m.id", turn.get("id"), pid);
                for (var reply : replies)
                    history.add(Map.of("role", "assistant", "content", (String) reply.get("content")));
            }
        }
        return new ProjectContext((String) p.get("title"), base, boundedHistory(history), versionFiles(base), "",
                recallHistory(pid, currentRun, turns));
    }

    private String recallHistory(String pid, String currentRun, List<Map<String, Object>> recent) {
        if (recent.size() < 20) return "";
        var current = db.queryForList("SELECT prompt FROM generation_runs WHERE id=? AND project_id=?", String.class, currentRun, pid);
        if (current.isEmpty()) return "";
        var similarity = new PromptSimilarity(current.getFirst());
        var recentIds = recent.stream().map(turn -> turn.get("id")).collect(java.util.stream.Collectors.toSet());
        var prior = db.queryForList("""
                SELECT r.id,r.prompt,m.content FROM generation_runs r
                JOIN message_details d ON d.run_id=r.id
                JOIN messages m ON m.id=d.message_id AND m.project_id=r.project_id AND m.role='assistant'
                LEFT JOIN run_options o ON o.run_id=r.id
                WHERE r.project_id=? AND r.id<>? AND r.status='SUCCEEDED' AND COALESCE(o.mode,'build') IN ('build','chat')
                ORDER BY r.created_at DESC,r.id DESC,m.id DESC LIMIT 100
                """, pid, currentRun);
        /** 记录旧需求和结果，例如检索出此前约定的金额精度。 */
        record Match(String id, String prompt, String reply, double score) {}
        var matches = prior.stream().filter(row -> !recentIds.contains(row.get("id")))
                .map(row -> new Match((String) row.get("id"), (String) row.get("prompt"), (String) row.get("content"),
                        similarity.score((String) row.get("prompt"))))
                .filter(match -> match.score() >= 0.45)
                .sorted(Comparator.comparingDouble(Match::score).reversed()).toList();
        var recalled = new StringJoiner("\n\n");
        var used = new HashSet<String>();
        for (var match : matches) {
            if (!used.add(match.id())) continue;
            recalled.add("历史需求：" + clipHistory(match.prompt(), 350) + "\n历史结果：" + clipHistory(match.reply(), 600));
            if (used.size() == 3) break;
        }
        return recalled.toString();
    }

    public static List<Map<String, String>> boundedHistory(List<Map<String, String>> history) {
        // Retain the initial brief as well as the latest changes, with a fixed budget.
        if (history.isEmpty()) return List.of();
        final int budget = 24000, perMessage = 6000, maxMessages = 40;
        var result = new ArrayList<Map<String, String>>();
        var first = history.getFirst();
        String brief = clipHistory(first.get("content"), perMessage);
        result.add(Map.of("role", first.get("role"), "content", brief));
        int remaining = budget - brief.length() - 200;
        var recent = new ArrayList<Map<String, String>>();
        for (int i = history.size() - 1; i > 0 && recent.size() < maxMessages - 1; i--) {
            var message = history.get(i);
            String content = clipHistory(message.get("content"), perMessage);
            if (content.length() > remaining) break;
            recent.addFirst(Map.of("role", message.get("role"), "content", content));
            remaining -= content.length();
        }
        if (recent.size() + 1 < history.size())
            result.add(Map.of("role", "assistant", "content", "部分较早的项目对话已因长度限制省略；请以当前源码和本次需求为准。"));
        result.addAll(recent);
        return result;
    }

    static String clipHistory(String content, int limit) {
        String note = "\n[此条较长，已截断]";
        return content.length() <= limit ? content : content.substring(0, limit - note.length()) + note;
    }

    /**
     * 表示当前执行令牌已失效，调用方应结束旧执行。
     *
     * <p>示例：任务暂停后，旧线程尝试保存检查点会收到此异常。</p>
     */
    public static class StaleExecution extends RuntimeException {
    }

    Map<String, Object> lockRun(String rid, String owner) {
        var view = run(rid, owner);
        String pid = (String) view.get("projectId");
        db.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE", pid);
        return db.queryForMap("SELECT * FROM generation_runs WHERE id=? FOR UPDATE", rid);
    }

    public void requireExecution(String rid, String token) {
        // Locking reads see the current token even after a MySQL REPEATABLE READ snapshot was opened.
        // Keep run -> checkpoint lock order consistent with pause/claim.
        var runs = db.queryForList("SELECT status FROM generation_runs WHERE id=? FOR UPDATE", rid);
        var tokens = db.queryForList("SELECT execution_token FROM run_checkpoints WHERE run_id=? FOR UPDATE", rid);
        if (token == null || token.isBlank() || runs.isEmpty() || tokens.isEmpty() || !Objects.equals(token, tokens.getFirst().get("execution_token")) || Set.of("PAUSED", "SUCCEEDED", "FAILED", "CANCELLED").contains(runs.getFirst().get("status")))
            throw new StaleExecution();
    }

    public String claim(String rid, String owner) {
        return tx.execute(status -> {
            var r = lockRun(rid, owner);
            if (!"QUEUED".equals(r.get("status"))) throw new StaleExecution();
            var rows = db.queryForList("SELECT execution_token FROM run_checkpoints WHERE run_id=? FOR UPDATE", rid);
            if (rows.isEmpty()) db.update("INSERT INTO run_checkpoints(run_id,attempt) VALUES(?,0)", rid);
            else if (rows.getFirst().get("execution_token") != null) throw new StaleExecution();
            String token = id();
            db.update("UPDATE run_checkpoints SET execution_token=? WHERE run_id=?", token, rid);
            return token;
        });
    }

    public String pause(String rid, String owner) {
        return pause(rid, owner, null);
    }

    public String pause(String rid, String owner, String expectedToken) {
        return tx.execute(status -> {
            var r = lockRun(rid, owner);
            if (expectedToken != null) requireExecution(rid, expectedToken);
            if (Set.of("SUCCEEDED", "FAILED", "CANCELLED", "AWAITING_APPROVAL").contains(r.get("status")))
                throw error(409, "当前任务状态不能暂停");
            var rows = db.queryForList("SELECT execution_token FROM run_checkpoints WHERE run_id=? FOR UPDATE", rid);
            String token = rows.isEmpty() ? null : (String) rows.getFirst().get("execution_token");
            db.update("UPDATE run_checkpoints SET execution_token=NULL WHERE run_id=?", rid);
            db.update("UPDATE generation_runs SET status='PAUSED',error=NULL WHERE id=?", rid);
            if (!"PAUSED".equals(r.get("status"))) taskEvent(rid, "WORKFLOW_PAUSED");
            return token;
        });
    }

    public Map<String, Object> resume(String rid, String owner) {
        return tx.execute(status -> {
            var r = lockRun(rid, owner);
            String pid = (String) r.get("project_id");
            if (!"PAUSED".equals(r.get("status"))) throw error(409, "只有已暂停的任务可以恢复");
            var p = project(pid, owner);
            if (!Objects.equals(p.get("active_run"), rid) || !Objects.equals(p.get("current_version"), r.get("base_version")))
                throw error(409, "项目版本已变化，无法恢复此任务");
            db.update("UPDATE generation_runs SET status='QUEUED',error=NULL WHERE id=?", rid);
            taskEvent(rid, "WORKFLOW_RESUMED");
            return r;
        });
    }

    public String cancel(String rid, String owner) {
        return tx.execute(status -> {
            var run = lockRun(rid, owner);
            if ("CANCELLED".equals(run.get("status"))) return null;
            if (Set.of("SUCCEEDED", "FAILED").contains(run.get("status")))
                throw error(409, "已结束的任务不能取消");
            var rows = db.queryForList("SELECT execution_token FROM run_checkpoints WHERE run_id=? FOR UPDATE", rid);
            String token = rows.isEmpty() ? null : (String) rows.getFirst().get("execution_token");
            db.update("UPDATE run_checkpoints SET execution_token=NULL WHERE run_id=?", rid);
            stage(rid, "CANCELLED");
            db.update("UPDATE projects SET active_run=NULL,updated_at=? WHERE id=? AND active_run=?",
                    now(), run.get("project_id"), rid);
            return token;
        });
    }

    public Map<String, Object> decideApproval(String rid, String owner, boolean approved) {
        return tx.execute(status -> {
            var run = lockRun(rid, owner);
            if (!"AWAITING_APPROVAL".equals(run.get("status"))) throw error(409, "任务当前无需审批");
            if (approved) stage(rid, "QUEUED");
            else {
                db.update("UPDATE generation_runs SET status='FAILED',error=? WHERE id=?", "工作流模板任务未获批准", rid);
                db.update("UPDATE projects SET active_run=NULL,updated_at=? WHERE id=? AND active_run=?", now(), run.get("project_id"), rid);
            }
            return run;
        });
    }

    public Map<String, Object> checkpoint(String rid) {
        return db.queryForMap("SELECT * FROM run_checkpoints WHERE run_id=?", rid);
    }

    public AgentLoop.Snapshot agentSnapshot(String rid) {
        return journal.snapshot(rid, AgentLoop.Snapshot.class);
    }

    public List<Map<String, Object>> dagNodes(String rid) {
        return journal.dagNodes(rid);
    }

    public void dagNode(String rid, String owner, String token, String node, List<String> dependencies, String status, String output, long elapsedMs) {
        writeJournal(rid, owner, token, () -> journal.saveNode(rid, node, dependencies, status, output, elapsedMs));
    }

    /**
     * 表示本轮模型调用次数已达到任务上限。
     *
     * <p>示例：限额为 20 时，尝试预留第 21 次调用会触发此异常。</p>
     */
    public static class ModelCallLimit extends IllegalStateException {
        ModelCallLimit(int limit) {
            super("已达到本任务的模型调用上限（" + limit + "次），请缩小需求后重试");
        }
    }

    public void reserveModelCall(String rid, String owner, String token, int maxCalls) {
        writeJournal(rid, owner, token, () -> {
            if (!journal.reserveModelCall(rid, maxCalls)) throw new ModelCallLimit(maxCalls);
        });
    }

    public void reserveModelCall(String rid, String owner, String token, int maxCalls,
                                 long tokenLimit, long estimate) {
        reserveModelCall(rid, owner, token, maxCalls, tokenLimit, estimate, null, 0);
    }

    public void reserveModelCall(String rid, String owner, String token, int maxCalls,
                                 long tokenLimit, long estimate, String node, long nodeLimit) {
        writeJournal(rid, owner, token, () -> {
            if (!journal.reserveModelCall(rid, maxCalls)) throw new ModelCallLimit(maxCalls);
            if (tokenLimit > 0 && estimate > tokenLimit - journal.chargedTokens(rid))
                throw new IllegalStateException("任务剩余 token 预算不足，无法预留本次请求的输入及输出额度");
            if (node != null) {
                var charges = db.queryForList("SELECT charged_tokens FROM workflow_node_budget WHERE run_id=? AND node_id=?",
                        Long.class, rid, node);
                long charged = charges.isEmpty() ? 0 : charges.getFirst();
                if (estimate > nodeLimit - charged)
                    throw new IllegalStateException("模板节点剩余 token 预算不足：" + node);
                if (charges.isEmpty()) db.update("INSERT INTO workflow_node_budget VALUES(?,?,?)", rid, node, estimate);
                else addNodeCharge(rid, node, estimate);
            }
            journal.addTokenCharge(rid, estimate);
        });
    }

    public void settleModelCall(String rid, String owner, String token, long estimate,
                                long input, long output) {
        settleModelCall(rid, owner, token, estimate, input, output, null);
    }

    public void settleModelCall(String rid, String owner, String token, long estimate,
                                long input, long output, String node) {
        settleModelCall(rid, owner, token, estimate, input, output, node, null);
    }

    public void settleModelCall(String rid, String owner, String token, long estimate,
                                long input, long output, String node, String model) {
        writeJournal(rid, owner, token, () -> {
            long delta = Math.addExact(input, output) - estimate;
            journal.addTokenCharge(rid, delta);
            if (node != null) addNodeCharge(rid, node, delta);
            journal.recordModelUsage(rid, input, output);
            if (model != null && model.length() <= 255) {
                int changed = db.update("""
                        UPDATE agent_model_usage SET metered_calls=metered_calls+1,
                        input_tokens=input_tokens+?,output_tokens=output_tokens+? WHERE run_id=? AND model=?
                        """, input, output, rid, model);
                if (changed == 0)
                    db.update("INSERT INTO agent_model_usage VALUES(?,?,1,?,?)", rid, model, input, output);
                var rate = modelRates.get(model);
                if (rate != null) {
                    var cost = rate.input().multiply(BigDecimal.valueOf(input))
                            .add(rate.output().multiply(BigDecimal.valueOf(output))).movePointLeft(6)
                            .setScale(18, java.math.RoundingMode.HALF_UP);
                    if (db.update("UPDATE agent_model_cost SET usd=usd+? WHERE run_id=? AND model=?", cost, rid, model) == 0)
                        db.update("INSERT INTO agent_model_cost VALUES(?,?,?)", rid, model, cost);
                }
            }
        });
    }

    private void addNodeCharge(String rid, String node, long delta) {
        db.update("UPDATE workflow_node_budget SET charged_tokens=charged_tokens+? WHERE run_id=? AND node_id=?", delta, rid, node);
    }

    public void recordModelUsage(String rid, String owner, String token, long promptTokens, long completionTokens) {
        writeJournal(rid, owner, token, () -> journal.recordModelUsage(rid, promptTokens, completionTokens));
    }

    public void agentStep(String rid, String owner, String token, AgentLoop.Snapshot snapshot, String outcome, String observation) {
        agentStep(rid, owner, token, snapshot, outcome, observation, null);
    }

    public void agentStep(String rid, String owner, String token, AgentLoop.Snapshot snapshot, String outcome, String observation, Long elapsedMs) {
        writeJournal(rid, owner, token, () -> {
            var decision = snapshot.pending();
            journal.saveStep(rid, snapshot.step(), encode(snapshot), decision == null ? null : decision.action(),
                    decision == null ? null : decision.summary(), outcome, observation, elapsedMs);
        });
    }

    private void writeJournal(String rid, String owner, String token, Runnable write) {
        tx.executeWithoutResult(status -> {
            lockRun(rid, owner);
            requireExecution(rid, token);
            write.run();
        });
    }

    public void stage(String rid, String owner, String token, String stage) {
        tx.executeWithoutResult(s -> {
            lockRun(rid, owner);
            requireExecution(rid, token);
            stage(rid, stage);
        });
    }

    public void plan(String rid, String owner, String token, String plan) {
        tx.executeWithoutResult(s -> {
            lockRun(rid, owner);
            requireExecution(rid, token);
            plan(rid, plan);
        });
    }

    public void checkpoint(String rid, String owner, String token, Map<String, String> files, String failure, int attempt) {
        tx.executeWithoutResult(s -> {
            lockRun(rid, owner);
            requireExecution(rid, token);
            db.update("UPDATE run_checkpoints SET files=?,failure=?,attempt=? WHERE run_id=?", files == null ? null : encode(files), failure, attempt, rid);
        });
    }

    public void finishReply(String rid, String pid, String content, String mode, String model, byte[] image, String mediaType, String prompt) {
        finishReply(rid, pid, content, mode, model, image, mediaType, prompt, null);
    }

    public void finishReply(String rid, String pid, String content, String mode, String model, byte[] image, String mediaType, String prompt, String token) {
        tx.executeWithoutResult(status -> {
            var rows = db.queryForList("SELECT active_run FROM projects WHERE id=? FOR UPDATE", pid);
            if (token != null) requireExecution(rid, token);
            if (rows.isEmpty() || !Objects.equals(rows.getFirst().get("active_run"), rid))
                throw error(409, "任务已失效");
            String asset = null;
            if (image != null) {
                asset = id();
                db.update("INSERT INTO image_assets VALUES(?,?,?,?,?,?,?)", asset, pid, prompt, model, mediaType, image, now());
            }
            richMessage(pid, "assistant", content, rid, mode, model, asset);
            stage(rid, "SUCCEEDED");
            db.update("UPDATE projects SET active_run=NULL,updated_at=? WHERE id=?", now(), pid);
        });
    }

    public Map<String, Object> asset(String id, String owner) {
        return projects.asset(id, owner);
    }

    public void fail(String rid, String pid, String message) {
        fail(rid, pid, message, null);
    }

    public void fail(String rid, String pid, String message, String token) {
        tx.executeWithoutResult(s -> {
            db.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE", pid);
            if (token != null) {
                try {
                    requireExecution(rid, token);
                } catch (StaleExecution e) {
                    return;
                }
            }
            db.update("UPDATE generation_runs SET status='FAILED',error=? WHERE id=?", message, rid);
            taskEvent(rid, "WORKFLOW_FAILED");
            db.update("UPDATE projects SET active_run=NULL WHERE id=? AND active_run=?", pid, rid);
            richMessage(pid, "assistant", "任务未完成：" + message + "。已有版本未被覆盖。", rid, runMode(rid), runModel(rid), null);
        });
    }
    private static String shannonStatus(String status) {
        return switch (status) {
            case "QUEUED", "FAILED", "PAUSED", "CANCELLED", "TIMEOUT" -> status;
            case "SUCCEEDED" -> "COMPLETED";
            default -> "RUNNING";
        };
    }
}
