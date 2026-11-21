package dev.atoms.agent.persistence;

import dev.atoms.workspace.SourceFiles;
import dev.atoms.agent.skill.Skill;

import dev.atoms.agent.runtime.AgentLoop;

import dev.atoms.workspace.ProjectContext;
import dev.atoms.workspace.DocumentIndex;
import dev.atoms.workspace.PromptSimilarity;
import dev.atoms.workspace.SemanticHistory;
import dev.atoms.workspace.SemanticDocuments;

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
    private final DocumentIndex documents;
    private final Map<String, ModelRate> modelRates;
    private SemanticHistory semanticHistory;
    private SemanticDocuments semanticDocuments;

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
        this.documents = new DocumentIndex(db, tx);
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
        tx.executeWithoutResult(status -> {
            db.update("""
                    UPDATE generation_runs SET status='QUEUED',error=NULL
                    WHERE id IN (SELECT active_run FROM projects WHERE active_run IS NOT NULL)
                      AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','TIMEOUT','PAUSED','AWAITING_APPROVAL')
                      AND id NOT IN (SELECT run_id FROM scheduled_daemon_runs)
                    """);
            db.update("UPDATE run_checkpoints SET execution_token=NULL WHERE execution_token IS NOT NULL");
            db.update("""
                    UPDATE projects SET active_run=NULL
                    WHERE active_run IS NOT NULL AND active_run NOT IN (
                        SELECT id FROM generation_runs WHERE status IN ('QUEUED','PAUSED','AWAITING_APPROVAL')
                        OR (status='ANSWERING' AND id IN (SELECT run_id FROM scheduled_daemon_runs)))
                    """);
        });
    }

    public record QueuedRun(String id, String projectId, String owner, String prompt,
                            String baseVersion, String mode, String model) {}

    public List<QueuedRun> pendingRuns(int limit) {
        return db.query("""
                SELECT r.id,r.project_id,p.owner_id,r.prompt,r.base_version,o.mode,o.model
                FROM projects p JOIN generation_runs r ON p.active_run=r.id AND p.id=r.project_id
                JOIN run_options o ON o.run_id=r.id
                LEFT JOIN run_checkpoints c ON c.run_id=r.id
                WHERE r.status='QUEUED' AND c.execution_token IS NULL
                  AND NOT EXISTS (SELECT 1 FROM scheduled_daemon_runs d WHERE d.run_id=r.id)
                ORDER BY r.created_at,r.id LIMIT ?
                """, (rs, index) -> new QueuedRun(rs.getString("id"), rs.getString("project_id"),
                rs.getString("owner_id"), rs.getString("prompt"), rs.getString("base_version"),
                rs.getString("mode"), rs.getString("model")), limit);
    }

    public List<String> undispatchedTemporalRuns(int limit) {
        return db.queryForList("""
                SELECT r.id FROM generation_runs r JOIN projects p ON p.id=r.project_id AND p.active_run=r.id
                LEFT JOIN temporal_run_dispatches t ON t.run_id=r.id
                WHERE t.run_id IS NULL AND NOT EXISTS (SELECT 1 FROM scheduled_daemon_runs d WHERE d.run_id=r.id)
                  AND r.status NOT IN ('PAUSED','AWAITING_APPROVAL','FAILED','SUCCEEDED','CANCELLED','TIMEOUT')
                ORDER BY r.created_at,r.id LIMIT ?
                """, String.class, limit);
    }

    public void markTemporalDispatched(String id) {
        try {
            db.update("INSERT INTO temporal_run_dispatches(run_id) VALUES(?)", id);
        } catch (org.springframework.dao.DuplicateKeyException alreadyRecorded) {
            // Another dispatcher recorded the same workflow.
        }
    }

    public QueuedRun activeRunForTemporal(String id) {
        return db.query("""
                SELECT r.id,r.project_id,p.owner_id,r.prompt,r.base_version,o.mode,o.model
                FROM generation_runs r JOIN projects p ON p.id=r.project_id AND p.active_run=r.id
                JOIN run_options o ON o.run_id=r.id
                WHERE r.id=? AND r.status NOT IN ('FAILED','SUCCEEDED','CANCELLED','TIMEOUT')
                """, (rs, index) -> new QueuedRun(rs.getString("id"), rs.getString("project_id"),
                rs.getString("owner_id"), rs.getString("prompt"), rs.getString("base_version"),
                rs.getString("mode"), rs.getString("model")), id).stream().findFirst().orElse(null);
    }

    public String claimForTemporal(String id, String owner) {
        return claimForTemporal(id, owner, true);
    }

    public String claimForTemporal(String id, String owner, boolean resetStatus) {
        return tx.execute(status -> {
            var run = lockRun(id, owner);
            if (Set.of("PAUSED", "AWAITING_APPROVAL", "FAILED", "SUCCEEDED", "CANCELLED", "TIMEOUT").contains(run.get("status")))
                throw new StaleExecution();
            var rows = db.queryForList("SELECT run_id FROM run_checkpoints WHERE run_id=? FOR UPDATE", id);
            if (rows.isEmpty()) db.update("INSERT INTO run_checkpoints(run_id,attempt) VALUES(?,0)", id);
            String token = id();
            db.update("UPDATE run_checkpoints SET execution_token=? WHERE run_id=?", token, id);
            if (resetStatus && !"QUEUED".equals(run.get("status"))) stage(id, "QUEUED");
            return token;
        });
    }

    public boolean hasExecutionToken(String id, String token) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM run_checkpoints WHERE run_id=? AND execution_token=?)
                """, Boolean.class, id, token));
    }

    public void requeue(String rid, String token) {
        tx.executeWithoutResult(status -> {
            requireExecution(rid, token);
            db.update("UPDATE run_checkpoints SET execution_token=NULL WHERE run_id=?", rid);
            db.update("UPDATE generation_runs SET status='QUEUED',error=NULL WHERE id=?", rid);
        });
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
        var nodes = dagNodes((String) r.get("id"));
        result.put("nodes", nodes);
        result.put("projectId", r.get("project_id"));
        result.put("baseVersion", r.get("base_version"));
        result.put("createdAt", r.get("created_at"));
        String rid = (String) r.get("id");
        result.put("timeline", db.queryForList("""
                SELECT event_id AS seq,event_type AS type,created_at AS timestamp
                FROM task_events WHERE run_id=? AND event_type IN (
                    'WORKFLOW_STARTED','ANSWERING','PLANNING','RESEARCHING','WORKFLOW_PAUSED','WORKFLOW_RESUMED',
                    'WORKFLOW_COMPLETED','WORKFLOW_CANCELLED','WORKFLOW_FAILED')
                ORDER BY event_id DESC LIMIT 100
                """, rid).reversed());
        if ("SUCCEEDED".equals(r.get("status"))) {
            db.queryForList("""
                    SELECT LEFT(m.content,800) FROM messages m JOIN message_details d ON d.message_id=m.id
                    WHERE d.run_id=? AND m.project_id=? AND m.role='assistant'
                    ORDER BY m.created_at DESC LIMIT 1
                    """, String.class, rid, r.get("project_id")).stream().findFirst()
                    .ifPresent(preview -> result.put("outputPreview", preview));
        }
        var planReview = reviewHead(rid);
        planReview.ifPresent(review -> result.put("planReviewVersion", review.get("version")));
        if ("AWAITING_APPROVAL".equals(r.get("status"))) {
            planReview.filter(review -> "PENDING".equals(review.get("decision"))).ifPresent(review -> {
                result.put("approvalId", review.get("approval_id"));
                if (supervisorReview(rid)) result.put("supervisorReview", true);
            });
            approvalReason(rid).ifPresent(reason -> result.put("approvalReason", reason));
            pendingToolApproval(rid).ifPresent(approval -> {
                result.put("approvalId", approval.get("approval_id"));
                result.put("approvalTool", approval.get("tool_name"));
                if (approval.get("node_label") != null) result.put("approvalNode", approval.get("node_label"));
            });
        }
        var skill = runSkill(rid);
        if (skill != null) result.put("skill", skill.reference());
        var template = runTemplate(rid);
        if (template != null) {
            result.put("template", template.name());
            result.put("templateVersion", template.version());
            if (template.nodes().stream().anyMatch(node -> "swarm".equals(node.type()))) {
                result.put("swarmAcceptingInput", acceptsSwarmInput(rid, (String) r.get("status"), false));
                result.put("swarmBoard", swarmBoard(template, nodes));
            }
        }
        result.put("steps", journal.steps(rid));
        var usage = journal.usage(rid);
        if (!usage.isEmpty()) result.put("usage", usage);
        return result;
    }

    private List<Map<String, Object>> swarmBoard(WorkflowTemplate template, List<Map<String, Object>> progress) {
        var stages = new HashMap<String, Map<String, Object>>();
        progress.forEach(stage -> stages.put((String) stage.get("id"), stage));
        var board = new ArrayList<Map<String, Object>>();
        for (var node : template.nodes()) {
            if (!"swarm".equals(node.type())) continue;
            String prefix = "template." + node.id();
            var plan = stages.get(prefix + ".swarm.plan");
            var tasks = new ArrayList<Map<String, Object>>();
            appendSwarmBoardTasks(stages, tasks, prefix, plan, 0);
            String replanning = "";
            for (int round = 1; round <= 4; round++) {
                String suffix = round == 1 ? "" : ".round-" + round;
                var replan = stages.get(prefix + ".swarm.replan" + suffix);
                if (replan == null) break;
                replanning = (String) replan.get("status");
                appendSwarmBoardTasks(stages, tasks, prefix, replan, round);
            }
            var review = stages.getOrDefault(prefix + ".swarm.review", Map.of());
            var synthesis = stages.getOrDefault(prefix + ".swarm.synthesis", Map.of());
            board.add(Map.of("id", node.id(), "planning", plan == null ? "PENDING" : plan.get("status"),
                    "review", review.getOrDefault("status", "PENDING"),
                    "replanning", replanning,
                    "synthesis", synthesis.getOrDefault("status", "PENDING"), "tasks", tasks));
        }
        return board;
    }

    private void appendSwarmBoardTasks(Map<String, Map<String, Object>> stages, List<Map<String, Object>> tasks,
                                       String prefix, Map<String, Object> plan, int round) {
        boolean followup = round > 0;
        String suffix = round <= 1 ? "" : ".round-" + round;
        if (plan == null || !"SUCCEEDED".equals(plan.get("status"))) return;
        try {
            var plans = json.readerForListOf(WorkflowTemplate.Task.class)
                    .<List<WorkflowTemplate.Task>>readValue((String) plan.get("output"));
            for (var task : plans) {
                String id = task.id();
                String stage = prefix + (followup ? ".swarm.followup" + suffix + "." : ".") + id;
                var current = stages.getOrDefault(stage, Map.of());
                var outbox = followup ? Map.of() : stages.getOrDefault(prefix + ".swarm.outbox." + id, Map.of());
                var inbox = followup ? Map.of() : stages.getOrDefault(prefix + ".swarm.inbox." + id, Map.of());
                tasks.add(Map.of("id", id, "role", Objects.toString(task.role(), ""), "query", task.goal(),
                        "dependencies", task.dependsOn(), "status", current.getOrDefault("status", "PENDING"),
                        "outboxStatus", outbox.getOrDefault("status", ""),
                        "inboxStatus", inbox.getOrDefault("status", ""), "followup", followup,
                        "peerRounds", followup ? List.of() : swarmPeerRounds(stages, prefix, id), "round", round));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Swarm 计划快照不可读取", invalid);
        }
    }

    private static List<Map<String, Object>> swarmPeerRounds(Map<String, Map<String, Object>> stages,
                                                              String prefix, String id) {
        var rounds = new ArrayList<Map<String, Object>>();
        for (int round = 1; ; round++) {
            String path = round == 1 ? id : "round-" + round + "." + id;
            var outbox = stages.getOrDefault(prefix + ".swarm.outbox." + path, Map.of());
            var inbox = stages.getOrDefault(prefix + ".swarm.inbox." + path, Map.of());
            if (outbox.isEmpty() && inbox.isEmpty()) return rounds;
            rounds.add(Map.of("round", round, "outboxStatus", outbox.getOrDefault("status", ""),
                    "inboxStatus", inbox.getOrDefault("status", "")));
        }
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
        if ("research".equals(row.get("mode"))) result.put("research", true);
        if ("AWAITING_APPROVAL".equals(row.get("status"))) {
            result.put("awaiting_approval", true);
            result.put("approval_id", pendingToolApproval(id).map(item -> item.get("approval_id")).orElseGet(() -> reviewHead(id)
                    .filter(review -> "PENDING".equals(review.get("decision"))).map(review -> review.get("approval_id")).orElse(id)));
            pendingToolApproval(id).ifPresent(item -> result.put("approval_tool", item.get("tool_name")));
            approvalReason(id).ifPresent(reason -> result.put("approval_reason", reason));
        }
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

    public Map<String, Object> gatewayControlState(String id, String owner) {
        var task = simpleTask(id, owner);
        boolean paused = "TASK_STATUS_PAUSED".equals(task.get("status"));
        var pauses = paused ? db.queryForList("""
                SELECT created_at FROM task_events WHERE run_id=? AND event_type='WORKFLOW_PAUSED'
                ORDER BY event_id DESC LIMIT 1
                """, String.class, id) : List.<String>of();
        return Map.of("is_paused", paused,
                "is_cancelled", "TASK_STATUS_CANCELLED".equals(task.get("status")),
                "paused_at", pauses.isEmpty() ? "" : pauses.getFirst(),
                "pause_reason", "", "paused_by", "", "cancel_reason", "", "cancelled_by", "");
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
                       COUNT(r.id) AS task_count,COALESCE(SUM(u.prompt_tokens+u.completion_tokens),0) AS tokens_used,
                       MAX(CASE WHEN o.mode='research' THEN 1 ELSE 0 END) AS research_flag
                FROM gateway_sessions s JOIN projects p ON p.id=s.id
                LEFT JOIN generation_runs r ON r.project_id=s.id
                LEFT JOIN run_options o ON o.run_id=r.id
                LEFT JOIN agent_usage u ON u.run_id=r.id
                WHERE p.owner_id=? AND (?='' OR s.id=?)
                AND NOT EXISTS (SELECT 1 FROM gateway_deleted_sessions x WHERE x.id=s.id)
                GROUP BY s.id,p.owner_id,p.title,s.created_at,p.updated_at
                ORDER BY p.updated_at DESC,s.id DESC
                """, owner, id, id).stream().map(row -> {
            row.put("is_research_session", ((Number) row.remove("research_flag")).intValue() != 0);
            return row;
        }).toList();
    }

    public Map<String, String> gatewayWorkspaceFiles(String id, String owner) {
        gatewayWorkspaceVersion(id, owner);
        return workspaceFiles(id, owner);
    }

    public Map<String, String> workspaceFiles(String id, String owner) {
        var project = project(id, owner);
        var files = new TreeMap<>(versionFiles((String) project.get("current_version")));
        files.putAll(workspaceArtifacts(id, owner));
        return files;
    }

    public Map<String, String> workspaceArtifacts(String project, String owner) {
        var files = new TreeMap<String, String>();
        db.query("""
                SELECT f.file_path,f.content FROM session_workspace_files f JOIN projects p ON p.id=f.project_id
                WHERE f.project_id=? AND p.owner_id=?
                """, rs -> { files.put(rs.getString(1), rs.getString(2)); }, project, owner);
        return files;
    }

    public Map<String, String> workspaceArtifactsForRun(String run, String owner) {
        var artifacts = new TreeMap<String, String>();
        db.query("""
                SELECT f.file_path,f.content FROM generation_runs r
                JOIN projects p ON p.id=r.project_id JOIN session_workspace_files f ON f.project_id=p.id
                WHERE r.id=? AND p.owner_id=?
                """, rs -> { artifacts.put(rs.getString(1), rs.getString(2)); }, run, owner);
        return artifacts;
    }

    public void writeWorkspaceFile(String run, String owner, String token, String path, String content) {
        SourceFiles.validatePath(path);
        if (!path.startsWith("artifacts/") || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64_000)
            throw new IllegalArgumentException("会话产物须放在 artifacts/ 下，每个文件最多 64 KB");
        writeJournal(run, owner, token, () -> {
            String project = db.queryForObject("SELECT project_id FROM generation_runs WHERE id=?", String.class, run);
            var files = workspaceArtifacts(project, owner);
            if (files.keySet().stream().anyMatch(existing ->
                    existing.startsWith(path + "/") || path.startsWith(existing + "/")))
                throw new IllegalArgumentException("会话文件和目录名称冲突");
            files.put(path, content);
            long bytes = files.values().stream().mapToLong(value -> value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
            if (files.size() > 80 || bytes > 1_000_000)
                throw new IllegalArgumentException("单个会话最多保存 80 个产物，总大小不得超过 1 MB");
            db.update("""
                    INSERT INTO session_workspace_files(project_id,file_path,content) VALUES(?,?,?)
                    ON DUPLICATE KEY UPDATE content=?
                    """, project, path, content, content);
        });
    }

    public List<DocumentIndex.Hit> searchGatewayDocuments(String id, String owner, String query) {
        return searchDocuments(gatewayWorkspaceVersion(id, owner), query);
    }

    public List<DocumentIndex.Hit> searchProjectDocuments(String id, String owner, String versionId, String query) {
        var project = project(id, owner);
        String version = versionId == null ? (String) project.get("current_version") : versionId;
        if (version != null) requireVersion(id, version);
        return searchDocuments(version, query);
    }

    public List<DocumentIndex.Hit> searchRunDocuments(String id, String owner, String query) {
        var run = run(id, owner);
        return searchDocuments((String) run.get("baseVersion"), query);
    }

    private List<DocumentIndex.Hit> searchDocuments(String version, String query) {
        String term = query.strip();
        if (term.isEmpty() || term.length() > 200) throw error(400, "文档检索词须为 1～200 字符");
        return documents.search(version, term, () -> versionFiles(version), semanticDocuments);
    }

    private String gatewayWorkspaceVersion(String id, String owner) {
        var sessions = db.queryForList("""
                SELECT p.current_version FROM gateway_sessions s JOIN projects p ON p.id=s.id
                WHERE s.id=? AND p.owner_id=?
                AND NOT EXISTS (SELECT 1 FROM gateway_deleted_sessions x WHERE x.id=s.id)
                """, id, owner);
        if (sessions.isEmpty()) throw error(404, "会话不存在");
        return (String) sessions.getFirst().get("current_version");
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
        return queue(pid, owner, prompt, base, mode, model, pattern, skill, template, context, null);
    }

    public Map<String, Object> queue(String pid, String owner, String prompt, String base, String mode, String model,
                                     String pattern, Skill skill, WorkflowTemplate template, Map<String, Object> context,
                                     String policyApproval) {
        return queue(pid, owner, prompt, base, mode, model, pattern, skill, template, context, policyApproval, "", null);
    }

    public Map<String, Object> queue(String pid, String owner, String prompt, String base, String mode, String model,
                                     String pattern, Skill skill, WorkflowTemplate template, Map<String, Object> context,
                                     String policyApproval, String recalledMemory, String captureScope) {
        return queue(pid, owner, prompt, base, mode, model, pattern, skill, template, context,
                policyApproval, recalledMemory, captureScope, Map.of());
    }

    public Map<String, Object> queue(String pid, String owner, String prompt, String base, String mode, String model,
                                     String pattern, Skill skill, WorkflowTemplate template, Map<String, Object> context,
                                     String policyApproval, String recalledMemory, String captureScope,
                                     Map<String, String> stageModels) {
        return queue(pid, owner, prompt, base, mode, model, pattern, skill, template, context,
                policyApproval, recalledMemory, captureScope, stageModels, false);
    }

    public Map<String, Object> queue(String pid, String owner, String prompt, String base, String mode, String model,
                                     String pattern, Skill skill, WorkflowTemplate template, Map<String, Object> context,
                                     String policyApproval, String recalledMemory, String captureScope,
                                     Map<String, String> stageModels, boolean editPreviousImage) {
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
            String initialStatus = policyApproval != null || (template != null && template.requiresApproval())
                    ? "AWAITING_APPROVAL" : "QUEUED";
            db.update("INSERT INTO generation_runs(id,project_id,status,base_version,prompt,created_at) VALUES(?,?,?,?,?,?)", rid, pid, initialStatus, base, prompt, now());
            db.update("INSERT INTO run_options VALUES(?,?,?)", rid, mode, model);
            if (editPreviousImage) {
                String source = db.queryForList("""
                        SELECT id FROM image_assets WHERE project_id=? ORDER BY created_at DESC,id DESC LIMIT 1
                        """, String.class, pid).stream().findFirst()
                        .orElseThrow(() -> error(400, "当前会话还没有可编辑的图片，请先生成一张"));
                db.update("INSERT INTO run_image_sources(run_id,asset_id) VALUES(?,?)", rid, source);
            }
            stageModels.forEach((stage, stageModel) -> db.update(
                    "INSERT INTO run_stage_models(run_id,stage,model) VALUES(?,?,?)", rid, stage, stageModel));
            if (captureScope != null) db.update("INSERT INTO run_gateway_memory_capture(run_id,memory_scope) VALUES(?,?)", rid, captureScope);
            if (!recalledMemory.isBlank()) db.update("INSERT INTO run_gateway_memory(run_id,memory_text) VALUES(?,?)", rid, recalledMemory);
            if (policyApproval != null) db.update("INSERT INTO run_approval_requirements(run_id,reason) VALUES(?,?)", rid, policyApproval);
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

    public String stageModel(String run, String stage, String primary) {
        return db.queryForList("SELECT model FROM run_stage_models WHERE run_id=? AND stage=?",
                String.class, run, stage).stream().findFirst().orElse(primary);
    }

    public Map<String, String> stageModels(String run) {
        var result = new LinkedHashMap<String, String>();
        db.query("SELECT stage,model FROM run_stage_models WHERE run_id=?",
                rs -> { result.put(rs.getString("stage"), rs.getString("model")); }, run);
        return Map.copyOf(result);
    }

    public void appendCompletionDelta(String run, String owner, String token, String content) {
        writeJournal(run, owner, token, () -> db.update(
                "INSERT INTO completion_text_deltas(run_id,execution_token,content) VALUES(?,?,?)", run, token, content));
    }

    public record CompletionDelta(long seq, String token, String content) {}

    public List<CompletionDelta> completionDeltasAfter(String run, long cursor) {
        return db.query("""
                SELECT seq,execution_token,content FROM completion_text_deltas
                WHERE run_id=? AND seq>? ORDER BY seq LIMIT 200
                """, (rs, index) -> new CompletionDelta(rs.getLong("seq"), rs.getString("execution_token"),
                rs.getString("content")), run, cursor);
    }

    public BigDecimal modelCost(String run) {
        return db.queryForObject("SELECT COALESCE(SUM(usd),0) FROM agent_model_cost WHERE run_id=?", BigDecimal.class, run);
    }

    public String taskContext(String rid) {
        return db.queryForList("SELECT context_json FROM gateway_task_context WHERE run_id=?", String.class, rid)
                .stream().filter(value -> !"{}".equals(value)).findFirst().orElse("");
    }

    public Map<String, Object> gatewayHistory(String id, String owner) {
        var rows = gatewayHistoryRows(id, owner, null, 0);
        return Map.of("session_id", id, "tasks", rows, "total", rows.size());
    }

    public Map<String, Object> gatewaySessionEvents(String id, String owner, int limit, int offset) {
        var rows = gatewayHistoryRows(id, owner, limit, offset);
        var total = db.queryForObject("SELECT COUNT(*)" + GATEWAY_HISTORY_SOURCE, Long.class, id, owner);
        var turns = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            String taskId = (String) row.get("task_id");
            var events = gatewayEvents(GATEWAY_EVENT_COLUMNS
                    + " WHERE e.run_id=? ORDER BY e.event_id", taskId);
            turns.add(Map.of("turn", (long) offset + turns.size() + 1, "task_id", taskId,
                    "user_query", row.get("query"), "final_output", row.getOrDefault("result", ""),
                    "timestamp", row.get("started_at"), "events", events,
                    "metadata", Map.of("tokens_used", row.get("total_tokens"),
                            "execution_time_ms", row.getOrDefault("duration_ms", 0),
                            "agents_involved", List.of())));
        }
        return Map.of("session_id", id, "count", total, "turns", turns);
    }

    private static final String GATEWAY_HISTORY_SOURCE = """
            FROM generation_runs r JOIN projects p ON p.id=r.project_id
            JOIN run_options o ON o.run_id=r.id
            LEFT JOIN agent_usage u ON u.run_id=r.id
            LEFT JOIN gateway_task_context c ON c.run_id=r.id
            WHERE r.project_id=? AND p.owner_id=?
                AND (o.mode='chat' OR c.run_id IS NOT NULL)
                AND NOT EXISTS (SELECT 1 FROM gateway_deleted_sessions x WHERE x.id=p.id)
            """;

    private List<Map<String, Object>> gatewayHistoryRows(String id, String owner, Integer limit, int offset) {
        gatewaySession(id, owner);
        String sql = """
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
                """ + GATEWAY_HISTORY_SOURCE + " ORDER BY started_at,r.id";
        var rows = limit == null ? db.queryForList(sql, id, owner)
                : db.queryForList(sql + " LIMIT ? OFFSET ?", id, owner, limit, offset);
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
        return rows;
    }

    private String gatewayMode(String id, String mode) {
        if (Set.of("chat", "openai-chat", "openai-completion").contains(mode)) return "EXECUTION_MODE_SIMPLE";
        return "research".equals(mode) || "dag".equals(runPattern(id)) || runTemplate(id) != null
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
            case "ANSWERING", "PLANNING", "RESEARCHING" -> {
                db.update("""
                        INSERT INTO task_events(run_id,event_type,created_at)
                        SELECT ?, 'WORKFLOW_STARTED', ? WHERE NOT EXISTS
                        (SELECT 1 FROM task_events WHERE run_id=? AND event_type='WORKFLOW_STARTED')
                        """, id, now(), id);
                taskEvent(id, status);
            }
            case "SUCCEEDED" -> taskEvent(id, "WORKFLOW_COMPLETED");
            case "CANCELLED" -> taskEvent(id, "WORKFLOW_CANCELLED");
        }
    }

    private void taskEvent(String id, String type) {
        db.update("INSERT INTO task_events(run_id,event_type,created_at) VALUES(?,?,?)", id, type, now());
    }

    private static final String GATEWAY_EVENT_COLUMNS = """
            SELECT e.run_id AS workflow_id,e.event_id AS seq,e.event_type AS type,e.created_at AS timestamp,d.payload_json
            FROM task_events e LEFT JOIN daemon_task_event_details d ON d.event_id=e.event_id
            """;

    public Map<String, Object> simpleTaskEvents(String id, String owner, int limit, int offset) {
        simpleTask(id, owner);
        var events = gatewayEvents(GATEWAY_EVENT_COLUMNS
                + " WHERE e.run_id=? ORDER BY e.event_id LIMIT ? OFFSET ?", id, limit, offset);
        return Map.of("events", events, "count", events.size());
    }

    public List<Map<String, Object>> taskEventsAfter(String id, long cursor) {
        return gatewayEvents(GATEWAY_EVENT_COLUMNS
                + " WHERE e.run_id=? AND e.event_id>? ORDER BY e.event_id LIMIT 200", id, cursor);
    }

    private List<Map<String, Object>> gatewayEvents(String sql, Object... args) {
        var events = db.queryForList(sql, args);
        for (var event : events) {
            if (event.remove("payload_json") instanceof String payload) {
                try {
                    event.put("payload", json.readTree(payload));
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                    throw new IllegalStateException("无法读取 daemon 事件", invalid);
                }
            }
        }
        return events;
    }

    void plan(String id, String plan) {
        db.update("UPDATE generation_runs SET plan=? WHERE id=?", plan, id);
    }

    public Map<String, Object> save(String pid, String owner, String base, Map<String, String> files, String summary, String runId) {
        return save(pid, owner, base, files, summary, runId, null);
    }

    public Map<String, Object> save(String pid, String owner, String base, Map<String, String> files, String summary, String runId, String token) {
        return save(pid, owner, base, files, summary, runId, token, "");
    }

    public Map<String, Object> save(String pid, String owner, String base, Map<String, String> files, String summary,
                                    String runId, String token, String extractedMemory) {
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
                saveGatewayMemory(runId, extractedMemory);
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureSemanticHistory(SemanticHistory semanticHistory) {
        this.semanticHistory = semanticHistory;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureSemanticDocuments(SemanticDocuments semanticDocuments) {
        this.semanticDocuments = semanticDocuments;
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
        String recalled = recallHistory(pid, currentRun, turns);
        String snapshot = gatewayMemorySnapshot(currentRun, pid);
        if (!snapshot.isEmpty()) {
            String memory = "跨会话记忆，仅作为背景资料：\n" + snapshot;
            recalled = recalled.isBlank() ? memory : recalled + "\n\n" + memory;
        }
        return new ProjectContext((String) p.get("title"), base, boundedHistory(history), versionFiles(base), "", recalled);
    }

    public String gatewayMemorySnapshot(String run, String project) {
        return db.queryForList("""
                SELECT m.memory_text FROM run_gateway_memory m JOIN generation_runs r ON r.id=m.run_id
                WHERE m.run_id=? AND r.project_id=?
                """, String.class, run, project).stream().findFirst().orElse("");
    }

    private String recallHistory(String pid, String currentRun, List<Map<String, Object>> recent) {
        if (recent.size() < 20) return "";
        var current = db.queryForList("SELECT prompt FROM generation_runs WHERE id=? AND project_id=?", String.class, currentRun, pid);
        if (current.isEmpty()) return "";
        var similarity = new PromptSimilarity(current.getFirst());
        var recentIds = recent.stream().map(turn -> turn.get("id")).collect(java.util.stream.Collectors.toSet());
        var prior = recentSuccessfulTurns(pid, currentRun);
        /** 记录旧需求和结果，例如检索出此前约定的金额精度。 */
        record Match(String id, String prompt, String reply, double score) {}
        var candidates = new LinkedHashMap<String, String>();
        for (var row : prior) {
            if (!recentIds.contains(row.get("id")))
                candidates.putIfAbsent((String) row.get("id"), (String) row.get("prompt"));
        }
        var semantic = semanticHistory == null ? Map.<String, Double>of()
                : semanticHistory.rank(pid, currentRun, current.getFirst(), candidates);
        var matches = prior.stream().filter(row -> candidates.containsKey(row.get("id")))
                .map(row -> new Match((String) row.get("id"), (String) row.get("prompt"), (String) row.get("content"),
                        semantic.containsKey(row.get("id")) ? 1 + semantic.get(row.get("id"))
                                : similarity.score((String) row.get("prompt"))))
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

    private List<Map<String, Object>> recentSuccessfulTurns(String pid, String currentRun) {
        return db.queryForList("""
                SELECT r.id,r.prompt,m.content FROM generation_runs r
                JOIN message_details d ON d.run_id=r.id
                JOIN messages m ON m.id=d.message_id AND m.project_id=r.project_id AND m.role='assistant'
                LEFT JOIN run_options o ON o.run_id=r.id
                WHERE r.project_id=? AND r.id<>? AND r.status='SUCCEEDED' AND COALESCE(o.mode,'build') IN ('build','chat')
                ORDER BY r.created_at DESC,r.id DESC,m.id DESC LIMIT 100
                """, pid, currentRun);
    }

    public List<Map<String, String>> compressionHistory(String pid, String owner, String currentRun) {
        project(pid, owner);
        var prior = recentSuccessfulTurns(pid, currentRun);
        if (prior.size() <= 8) return List.of();
        int historySize = prior.stream().limit(20).mapToInt(row ->
                ((String) row.get("prompt")).length() + ((String) row.get("content")).length()).sum();
        if (prior.size() <= 20 && historySize < 24000) return List.of();
        var input = new ArrayList<Map<String, String>>();
        for (var row : prior.subList(8, Math.min(prior.size(), 28))) {
            input.add(Map.of("request", clipHistory((String) row.get("prompt"), 350),
                    "result", clipHistory((String) row.get("content"), 550)));
        }
        Collections.reverse(input);
        return input;
    }

    public Optional<String> contextSummary(String id) {
        return db.queryForList("SELECT summary FROM run_context_summaries WHERE run_id=?", String.class, id)
                .stream().findFirst();
    }

    public void saveContextSummary(String id, String token, String summary) {
        tx.executeWithoutResult(status -> {
            requireExecution(id, token);
            db.update("INSERT INTO run_context_summaries(run_id,summary) VALUES(?,?)", id, summary);
        });
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

    private static final long DAEMON_CLAIM_SECONDS = 60;

    public void expireDaemonClaims(long nowEpoch) {
        var due = db.queryForList("""
                SELECT l.run_id,r.project_id FROM daemon_claim_leases l
                JOIN generation_runs r ON r.id=l.run_id
                WHERE l.expires_epoch<=? ORDER BY l.expires_epoch LIMIT 32
                """, nowEpoch);
        for (var candidate : due) {
            String id = (String) candidate.get("run_id");
            String project = (String) candidate.get("project_id");
            tx.executeWithoutResult(status -> {
                db.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE", project);
                if (db.update("DELETE FROM daemon_claim_leases WHERE run_id=? AND expires_epoch<=?", id, nowEpoch) != 1)
                    return;
                db.update("UPDATE scheduled_daemon_runs SET connection_id=NULL WHERE run_id=?", id);
                String current = db.queryForObject("SELECT status FROM generation_runs WHERE id=?", String.class, id);
                if ("ANSWERING".equals(current)) fail(id, project, "daemon 领取已过期，未收到进度心跳");
            });
        }
    }

    public void expireScheduledRuns(long nowEpoch) {
        var due = db.queryForList("""
                SELECT d.run_id,r.project_id FROM scheduled_run_deadlines d JOIN generation_runs r ON r.id=d.run_id
                WHERE d.deadline_epoch<=? ORDER BY d.deadline_epoch LIMIT 32
                """, nowEpoch);
        for (var candidate : due) {
            String id = (String) candidate.get("run_id");
            String project = (String) candidate.get("project_id");
            tx.executeWithoutResult(status -> {
                db.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE", project);
                String current = db.queryForObject("SELECT status FROM generation_runs WHERE id=? FOR UPDATE", String.class, id);
                if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMEOUT").contains(current)) {
                    db.update("UPDATE run_checkpoints SET execution_token=NULL WHERE run_id=?", id);
                    db.update("UPDATE generation_runs SET status='TIMEOUT',error=? WHERE id=?", "定时任务已超过执行时限", id);
                    taskEvent(id, "WORKFLOW_FAILED");
                    db.update("UPDATE projects SET active_run=NULL,updated_at=? WHERE id=? AND active_run=?", now(), project, id);
                }
                db.update("DELETE FROM scheduled_run_deadlines WHERE run_id=?", id);
            });
        }
    }

    public void requireExecution(String rid, String token) {
        // Locking reads see the current token even after a MySQL REPEATABLE READ snapshot was opened.
        // Keep run -> checkpoint lock order consistent with pause/claim.
        var runs = db.queryForList("SELECT status FROM generation_runs WHERE id=? FOR UPDATE", rid);
        var tokens = db.queryForList("SELECT execution_token FROM run_checkpoints WHERE run_id=? FOR UPDATE", rid);
        if (token == null || token.isBlank() || runs.isEmpty() || tokens.isEmpty() || !Objects.equals(token, tokens.getFirst().get("execution_token")) || Set.of("PAUSED", "SUCCEEDED", "FAILED", "CANCELLED", "TIMEOUT").contains(runs.getFirst().get("status")))
            throw new StaleExecution();
        if (!db.queryForList("""
                SELECT run_id FROM scheduled_run_deadlines WHERE run_id=? AND deadline_epoch<=?
                """, rid, Instant.now().getEpochSecond()).isEmpty()) throw new StaleExecution();
    }

    private boolean daemonRun(String id) {
        return !db.queryForList("SELECT run_id FROM scheduled_daemon_runs WHERE run_id=?", id).isEmpty();
    }

    private static final long DAEMON_CONNECTION_SECONDS = 65;

    public long connectedDaemons(String owner, String requester) {
        return db.queryForObject("""
                SELECT COUNT(*) FROM daemon_gateway_connections WHERE tenant_owner=? AND requester=? AND expires_epoch>?
                """, Long.class, owner, requester, Instant.now().getEpochSecond());
    }

    public void connectDaemon(String owner, String requester, String connection) {
        db.update("""
                INSERT INTO daemon_gateway_connections(connection_id,tenant_owner,requester,expires_epoch) VALUES(?,?,?,?)
                """, connection, owner, requester, Instant.now().getEpochSecond() + DAEMON_CONNECTION_SECONDS);
    }

    public void renewDaemonConnection(String owner, String requester, String connection) {
        db.update("""
                INSERT INTO daemon_gateway_connections(connection_id,tenant_owner,requester,expires_epoch) VALUES(?,?,?,?)
                ON DUPLICATE KEY UPDATE expires_epoch=VALUES(expires_epoch)
                """, connection, owner, requester, Instant.now().getEpochSecond() + DAEMON_CONNECTION_SECONDS);
    }

    public void expireDaemonConnections(long nowEpoch) {
        db.update("DELETE FROM daemon_gateway_connections WHERE expires_epoch<=?", nowEpoch);
    }

    public List<Map<String, Object>> daemonOffers(String owner, String requester) {
        return db.queryForList("""
                SELECT r.id AS run_id,r.prompt,r.created_at,d.agent_name
                FROM scheduled_daemon_runs d JOIN generation_runs r ON r.id=d.run_id
                JOIN projects p ON p.id=r.project_id AND p.active_run=r.id
                JOIN scheduled_run_deadlines t ON t.run_id=r.id
                WHERE p.owner_id=? AND d.requester=? AND d.connection_id IS NULL
                  AND r.status='QUEUED' AND t.deadline_epoch>?
                ORDER BY r.created_at,r.id LIMIT 16
                """, owner, requester, Instant.now().getEpochSecond());
    }

    public boolean claimDaemon(String id, String owner, String requester, String connection) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (!daemonAssignment(id, owner, requester)) return false;
            var run = lockRun(id, owner);
            if (!"QUEUED".equals(run.get("status")) || !daemonDeadlineActive(id)) return false;
            if (db.update("""
                    UPDATE scheduled_daemon_runs SET connection_id=?
                    WHERE run_id=? AND requester=? AND connection_id IS NULL
                    """, connection, id, requester) != 1) return false;
            db.update("INSERT INTO daemon_claim_leases(run_id,expires_epoch) VALUES(?,?)",
                    id, Instant.now().getEpochSecond() + DAEMON_CLAIM_SECONDS);
            stage(id, "ANSWERING");
            return true;
        }));
    }

    public void disconnectDaemon(String owner, String requester, String connection) {
        db.update("DELETE FROM daemon_gateway_connections WHERE connection_id=? AND tenant_owner=? AND requester=?",
                connection, owner, requester);
        var runs = db.queryForList("""
                SELECT d.run_id,r.project_id FROM scheduled_daemon_runs d
                JOIN generation_runs r ON r.id=d.run_id
                JOIN projects p ON p.id=r.project_id
                WHERE p.owner_id=? AND d.requester=? AND d.connection_id=? AND r.status='ANSWERING'
                """, owner, requester, connection);
        for (var candidate : runs) {
            String run = (String) candidate.get("run_id");
            String project = (String) candidate.get("project_id");
            tx.executeWithoutResult(status -> {
                db.queryForList("SELECT id FROM projects WHERE id=? FOR UPDATE", project);
                int released = db.update("""
                        UPDATE scheduled_daemon_runs SET connection_id=NULL
                        WHERE run_id=? AND connection_id=? AND run_id IN (
                            SELECT id FROM generation_runs WHERE id=? AND status='ANSWERING'
                        )
                        """, run, connection, run);
                if (released == 1) {
                    db.update("DELETE FROM daemon_claim_leases WHERE run_id=?", run);
                    fail(run, project, "daemon 连接中断，未收到执行结果");
                }
            });
        }
    }

    public void replyDaemon(String id, String owner, String requester, String connection, String reply) {
        tx.executeWithoutResult(status -> {
            var run = requireDaemonClaim(id, owner, requester, connection);
            finishReply(id, (String) run.get("project_id"), reply, "chat", "daemon", null, null, null, null);
            db.update("DELETE FROM daemon_claim_leases WHERE run_id=?", id);
            db.update("UPDATE scheduled_daemon_runs SET connection_id=NULL WHERE run_id=?", id);
        });
    }

    private static final String BLOB_PREFIX = "shannon:blob:";
    private static final long BLOB_TTL_SECONDS = 7 * 86400L;
    private static final Set<String> DAEMON_BLOB_FIELDS = Set.of(
            "screenshot_b64", "popup_screenshot_b64", "data_base64", "image_base64", "screenshot");

    public String gatewayBlob(String owner, String key) {
        if (key.length() > 180 || !key.startsWith(BLOB_PREFIX))
            throw error(400, "无效 Blob 键");
        String[] parts = key.substring(BLOB_PREFIX.length()).split(":", -1);
        if (parts.length != 2 || !parts[0].matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                || !parts[1].matches("[A-Za-z0-9_.-]{1,128}"))
            throw error(400, "无效 Blob 键");
        return tx.execute(status -> {
            simpleTask(parts[0], owner);
            long now = Instant.now().getEpochSecond();
            db.update("""
                    UPDATE gateway_blobs SET expires_epoch=?
                    WHERE run_id=? AND field_path=? AND expires_epoch>?
                    """, now + BLOB_TTL_SECONDS, parts[0], parts[1], now);
            // An update in the same second can leave the stored expiry unchanged.
            var blobs = db.queryForList("""
                    SELECT content FROM gateway_blobs WHERE run_id=? AND field_path=? AND expires_epoch>?
                    """, String.class, parts[0], parts[1], now);
            if (blobs.isEmpty()) throw error(404, "Blob 不存在或已过期");
            return blobs.getFirst();
        });
    }

    public void expireGatewayBlobs(long now) {
        db.update("DELETE FROM gateway_blobs WHERE expires_epoch<=?", now);
    }

    private Map<String, Object> storeGatewayBlob(String id, String field, String content) {
        db.update("""
                INSERT INTO gateway_blobs(run_id,field_path,content,expires_epoch) VALUES(?,?,?,?)
                """, id, field, content, Instant.now().getEpochSecond() + BLOB_TTL_SECONDS);
        return Map.of("redis_key", BLOB_PREFIX + id + ":" + field, "size",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, "ttl_days", 7);
    }

    private String encodeDaemonEvent(String id, long sequence, com.fasterxml.jackson.databind.JsonNode payload) {
        var safePayload = payload.deepCopy();
        if (safePayload.path("data") instanceof com.fasterxml.jackson.databind.node.ObjectNode data) {
            long count = db.queryForObject("SELECT COUNT(*) FROM gateway_blobs WHERE run_id=?", Long.class, id);
            for (String name : DAEMON_BLOB_FIELDS) {
                var value = data.path(name);
                if (count >= 32 || !value.isTextual() || value.asText().length() <= 8192) continue;
                String field = "event." + sequence + ".data." + name;
                data.set(name + "_ref", json.valueToTree(storeGatewayBlob(id, field, value.asText())));
                data.putNull(name);
                count++;
            }
        }
        String encoded = encode(safePayload);
        if (encoded.length() > 16000) throw error(400, "daemon 事件超过长度限制");
        return encoded;
    }

    public void recordDaemonEvent(String id, String owner, String requester, String connection,
                                  String type, long sequence, com.fasterxml.jackson.databind.JsonNode payload) {
        tx.executeWithoutResult(status -> {
            requireDaemonClaim(id, owner, requester, connection);
            if ("DAEMON_PROGRESS".equals(type)) {
                db.update("UPDATE daemon_claim_leases SET expires_epoch=? WHERE run_id=?",
                        Instant.now().getEpochSecond() + DAEMON_CLAIM_SECONDS, id);
            }
            if (!db.queryForList("""
                    SELECT event_id FROM daemon_task_event_details WHERE run_id=? AND daemon_seq=?
                    """, id, sequence).isEmpty()) return;
            long count = db.queryForObject("SELECT COUNT(*) FROM daemon_task_event_details WHERE run_id=?", Long.class, id);
            if (count >= 1000) return;
            String encoded = encodeDaemonEvent(id, sequence, payload);
            var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
            db.update(connectionHandle -> {
                var statement = connectionHandle.prepareStatement(
                        "INSERT INTO task_events(run_id,event_type,created_at) VALUES(?,?,?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, id);
                statement.setString(2, type);
                statement.setString(3, now());
                return statement;
            }, key);
            db.update("INSERT INTO daemon_task_event_details(event_id,run_id,daemon_seq,payload_json) VALUES(?,?,?,?)",
                    Objects.requireNonNull(key.getKey()).longValue(), id, sequence, encoded);
        });
    }

    private Map<String, Object> requireDaemonClaim(String id, String owner, String requester, String connection) {
        if (!daemonAssignment(id, owner, requester)) throw error(404, "daemon 任务不存在");
        var run = lockRun(id, owner);
        if (!"ANSWERING".equals(run.get("status")) || !daemonDeadlineActive(id)
                || !db.queryForList("SELECT run_id FROM daemon_claim_leases WHERE run_id=? AND expires_epoch<=?",
                        id, Instant.now().getEpochSecond()).isEmpty()
                || db.queryForList("""
                        SELECT run_id FROM scheduled_daemon_runs
                        WHERE run_id=? AND requester=? AND connection_id=?
                        """, id, requester, connection).isEmpty())
            throw error(409, "daemon 任务已失效");
        return run;
    }

    private boolean daemonAssignment(String id, String owner, String requester) {
        return !db.queryForList("""
                SELECT d.run_id FROM scheduled_daemon_runs d JOIN generation_runs r ON r.id=d.run_id
                JOIN projects p ON p.id=r.project_id WHERE d.run_id=? AND p.owner_id=? AND d.requester=?
                """, id, owner, requester).isEmpty();
    }

    private boolean daemonDeadlineActive(String id) {
        return !db.queryForList("SELECT run_id FROM scheduled_run_deadlines WHERE run_id=? AND deadline_epoch>?",
                id, Instant.now().getEpochSecond()).isEmpty();
    }

    public String claim(String rid, String owner) {
        return tx.execute(status -> {
            var r = lockRun(rid, owner);
            if (!"QUEUED".equals(r.get("status")) || daemonRun(rid)) throw new StaleExecution();
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
            if (daemonRun(rid)) throw error(409, "daemon 任务支持取消");
            if (expectedToken != null) requireExecution(rid, expectedToken);
            if (Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMEOUT", "AWAITING_APPROVAL").contains(r.get("status")))
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
            if (Set.of("SUCCEEDED", "FAILED", "TIMEOUT").contains(run.get("status")))
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

    private Optional<Map<String, Object>> pendingToolApproval(String id) {
        return db.queryForList("""
                SELECT approval_id,tool_name,reason,NULL AS node_label FROM run_tool_approvals WHERE run_id=? AND decision='PENDING'
                UNION ALL
                SELECT approval_id,tool_name,reason,node_label FROM run_node_tool_approvals WHERE run_id=? AND decision='PENDING'
                """, id, id).stream().findFirst();
    }

    public Optional<Map<String, Object>> nodeToolApproval(String run, String key) {
        return db.queryForList("SELECT action_json,decision FROM run_node_tool_approvals WHERE run_id=? AND node_key=?",
                run, key).stream().findFirst();
    }

    public void awaitNodeToolApproval(String run, String owner, String token, String key, String label,
                                      AgentLoop.Decision action, String reason) {
        tx.executeWithoutResult(status -> {
            lockRun(run, owner);
            requireExecution(run, token);
            db.update("""
                    INSERT INTO run_node_tool_approvals(run_id,node_key,node_label,approval_id,tool_name,reason,action_json,decision)
                    VALUES(?,?,?,?,?,?,?,'PENDING')
                    """, run, key, label, id(), action.action(), reason, encode(action));
            db.update("UPDATE run_checkpoints SET execution_token=NULL WHERE run_id=?", run);
            stage(run, "AWAITING_APPROVAL");
        });
    }

    public boolean toolApprovalGranted(String id, int step, String tool) {
        return !db.queryForList("""
                SELECT approval_id FROM run_tool_approvals WHERE run_id=? AND step_number=? AND tool_name=? AND decision='APPROVED'
                """, id, step, tool).isEmpty();
    }

    public void awaitToolApproval(String id, String owner, String token, int step, String tool, String reason) {
        tx.executeWithoutResult(status -> {
            lockRun(id, owner);
            requireExecution(id, token);
            db.update("""
                    INSERT INTO run_tool_approvals(run_id,step_number,approval_id,tool_name,reason,decision)
                    VALUES(?,?,?,?,?,'PENDING')
                    """, id, step, id(), tool, reason);
            db.update("UPDATE run_checkpoints SET execution_token=NULL WHERE run_id=?", id);
            stage(id, "AWAITING_APPROVAL");
        });
    }

    private Optional<Map<String, Object>> reviewHead(String id) {
        return db.queryForList("SELECT * FROM run_plan_reviews WHERE run_id=?", id).stream().findFirst();
    }

    private Optional<Map<String, Object>> lockReviewHead(String id) {
        return db.queryForList("SELECT * FROM run_plan_reviews WHERE run_id=? FOR UPDATE", id).stream().findFirst();
    }

    public void awaitPlanReview(String run, String owner, String token, String plan, boolean supervisor) {
        tx.executeWithoutResult(status -> {
            lockRun(run, owner);
            requireExecution(run, token);
            db.update("INSERT INTO run_plan_reviews(run_id,version,approval_id) VALUES(?,1,?)", run, id());
            if (supervisor) db.update("INSERT INTO supervisor_plan_reviews(run_id) VALUES(?)", run);
            insertPlanRevision(run, 1, plan, "");
            db.update("UPDATE run_checkpoints SET execution_token=NULL WHERE run_id=?", run);
            stage(run, "AWAITING_APPROVAL");
        });
    }

    private void insertPlanRevision(String run, int version, String plan, String feedback) {
        db.update("INSERT INTO run_plan_revisions(run_id,version,plan_text,feedback,created_at) VALUES(?,?,?,?,?)",
                run, version, plan, feedback, now());
        plan(run, plan);
    }

    public Map<String, Object> planReview(String run, String owner) {
        return tx.execute(status -> {
            lockRun(run, owner);
            var head = reviewHead(run).orElseThrow(() -> error(404, "任务没有计划审核记录"));
            var versions = db.queryForList("""
                    SELECT version,plan_text,feedback,created_at FROM run_plan_revisions
                    WHERE run_id=? ORDER BY version DESC
                    """, run);
            return Map.of("version", head.get("version"), "decision", head.get("decision"),
                    "approvalId", head.get("approval_id"), "revisions", versions);
        });
    }

    public record PlanRevisionWork(String token, String prompt, String plan) {}

    public PlanRevisionWork claimPlanRevision(String id, String owner, int version) {
        return tx.execute(status -> {
            var run = lockRun(id, owner);
            var head = lockReviewHead(id).orElseThrow(() -> error(404, "任务没有计划审核记录"));
            long now = Instant.now().getEpochSecond();
            if (!"AWAITING_APPROVAL".equals(run.get("status")) || !"PENDING".equals(head.get("decision"))
                    || ((Number) head.get("version")).intValue() != version
                    || ((Number) head.get("revision_until_epoch")).longValue() > now)
                throw error(409, "计划已更新或正在修订，请重新查看");
            if (version >= 10) throw error(409, "已达到计划修订次数上限");
            String token = id();
            db.update("UPDATE run_plan_reviews SET revision_until_epoch=? WHERE run_id=?", now + 90, id);
            db.update("UPDATE run_checkpoints SET execution_token=? WHERE run_id=?", token, id);
            return new PlanRevisionWork(token, (String) run.get("prompt"), (String) run.get("plan"));
        });
    }

    public void completePlanRevision(String id, String owner, String token, int version, String feedback, String revised) {
        tx.executeWithoutResult(status -> {
            var run = lockRun(id, owner);
            requireExecution(id, token);
            if (!"AWAITING_APPROVAL".equals(run.get("status")) || db.update("""
                    UPDATE run_plan_reviews SET version=?,approval_id=?,revision_until_epoch=0
                    WHERE run_id=? AND version=? AND decision='PENDING' AND revision_until_epoch>?
                    """, version + 1, id(), id, version, Instant.now().getEpochSecond()) != 1)
                throw error(409, "计划已更新或修订超时，请重新查看");
            insertPlanRevision(id, version + 1, revised, feedback);
            db.update("UPDATE run_checkpoints SET execution_token=NULL WHERE run_id=?", id);
        });
    }

    public void releasePlanRevision(String id, String owner, String token) {
        tx.executeWithoutResult(status -> {
            lockRun(id, owner);
            if (db.update("UPDATE run_checkpoints SET execution_token=NULL WHERE run_id=? AND execution_token=?", id, token) == 1)
                db.update("UPDATE run_plan_reviews SET revision_until_epoch=0 WHERE run_id=?", id);
        });
    }

    private Optional<String> approvalReason(String id) {
        var tool = pendingToolApproval(id);
        if (tool.isPresent()) return Optional.of((String) tool.get().get("reason"));
        return db.queryForList("SELECT reason FROM run_approval_requirements WHERE run_id=?", String.class, id).stream().findFirst();
    }

    public Map<String, Object> decideApproval(String rid, String owner, boolean approved) {
        return decideApproval(rid, owner, approved, null);
    }

    public List<Map<String, Object>> pendingSupervisorReviews() {
        return db.queryForList("""
                SELECT r.id AS run_id,r.prompt,r.plan,h.version,h.approval_id,h.revision_until_epoch
                FROM supervisor_plan_reviews s JOIN run_plan_reviews h ON h.run_id=s.run_id
                JOIN generation_runs r ON r.id=s.run_id
                WHERE h.decision='PENDING' AND r.status='AWAITING_APPROVAL'
                ORDER BY r.created_at,r.id LIMIT 100
                """);
    }

    public String supervisorReviewOwner(String id) {
        return db.queryForList("""
                SELECT p.owner_id FROM supervisor_plan_reviews s JOIN generation_runs r ON r.id=s.run_id
                JOIN projects p ON p.id=r.project_id WHERE s.run_id=?
                """, String.class, id).stream().findFirst().orElseThrow(() -> error(404, "Supervisor 审核不存在"));
    }

    private boolean supervisorReview(String id) {
        return !db.queryForList("SELECT run_id FROM supervisor_plan_reviews WHERE run_id=?", id).isEmpty();
    }

    public Map<String, Object> decideApproval(String rid, String owner, boolean approved, String approvalId) {
        return decideApproval(rid, owner, approved, approvalId, false);
    }

    public Map<String, Object> decideApproval(String rid, String owner, boolean approved, String approvalId, boolean supervisor) {
        return tx.execute(status -> {
            var run = lockRun(rid, owner);
            if (!"AWAITING_APPROVAL".equals(run.get("status"))) throw error(409, "任务当前无需审批");
            var tool = pendingToolApproval(rid);
            if (tool.isPresent()) {
                if (supervisor) throw error(409, "该审核已结束");
                String expected = (String) tool.get().get("approval_id");
                if (!expected.equals(approvalId)) throw error(409, "工具审批请求已改变，请重新查看");
                if (tool.get().get("node_label") != null) {
                    db.update("UPDATE run_node_tool_approvals SET decision=? WHERE approval_id=?",
                            approved ? "APPROVED" : "DENIED", expected);
                    stage(rid, "QUEUED");
                    return run;
                }
                db.update("UPDATE run_tool_approvals SET decision=? WHERE approval_id=?",
                        approved ? "APPROVED" : "DENIED", expected);
            } else {
                var review = lockReviewHead(rid).filter(head -> "PENDING".equals(head.get("decision")));
                if (review.isPresent()) {
                    if (supervisorReview(rid) != supervisor) throw error(403, "该计划需要指定审批人决定");
                    var head = review.get();
                    if (!head.get("approval_id").equals(approvalId)
                            || ((Number) head.get("revision_until_epoch")).longValue() > Instant.now().getEpochSecond())
                        throw error(409, "计划已更新或正在修订，请重新查看");
                    db.update("UPDATE run_plan_reviews SET decision=?,revision_until_epoch=0 WHERE run_id=?",
                            approved ? "APPROVED" : "DENIED", rid);
                    if (supervisor) db.update("UPDATE supervisor_plan_reviews SET decided_at=? WHERE run_id=?", now(), rid);
                    db.update("UPDATE run_checkpoints SET execution_token=NULL WHERE run_id=?", rid);
                } else if (supervisor) throw error(409, "该审核已结束");
                else if (approvalId != null && !rid.equals(approvalId)) throw error(404, "审批请求不存在");
            }
            if (approved) stage(rid, "QUEUED");
            else {
                db.update("UPDATE generation_runs SET status='FAILED',error=? WHERE id=?", "任务未获批准", rid);
                taskEvent(rid, "WORKFLOW_FAILED");
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

    public Map<String, Object> addSwarmHumanMessage(String rid, String owner, String message) {
        if (message == null || message.isBlank() || message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768)
            throw error(400, "Swarm 消息须为非空文本且不超过 32 KiB");
        return tx.execute(status -> {
            var run = lockRun(rid, owner);
            var template = runTemplate(rid);
            if (template == null || template.nodes().stream().noneMatch(node -> "swarm".equals(node.type())))
                throw error(400, "此任务没有 Swarm 节点");
            if (!acceptsSwarmInput(rid, (String) run.get("status"), true))
                throw error(409, "Swarm 已进入最终汇总或结束，无法接收新消息");
            var accepted = db.queryForList("SELECT message_id FROM swarm_human_messages WHERE run_id=? FOR UPDATE", Long.class, rid);
            if (accepted.size() >= 20) throw error(429, "此任务的人工输入已达到 20 条");
            db.update("INSERT INTO swarm_human_messages(run_id,message_text,created_at) VALUES(?,?,?)",
                    rid, message.strip(), now());
            taskEvent(rid, "SWARM_HUMAN_INPUT");
            return Map.of("success", true, "message", "Human input recorded");
        });
    }

    private boolean acceptsSwarmInput(String rid, String status, boolean lock) {
        if (Set.of("FAILED", "SUCCEEDED", "CANCELLED", "TIMEOUT").contains(status)) return false;
        String query = "SELECT stage FROM swarm_input_snapshots WHERE run_id=? AND closes_input=TRUE LIMIT 1";
        return db.queryForList(query + (lock ? " FOR UPDATE" : ""), String.class, rid).isEmpty();
    }

    public String swarmInputSnapshot(String rid, String owner, String token, String stage, boolean closeInput) {
        return tx.execute(status -> {
            lockRun(rid, owner);
            requireExecution(rid, token);
            var snapshots = db.queryForList("""
                    SELECT payload FROM swarm_input_snapshots WHERE run_id=? AND stage=? FOR UPDATE
                    """, String.class, rid, stage);
            if (!snapshots.isEmpty()) return snapshots.getFirst();
            var messages = db.queryForList("""
                    SELECT message_text FROM swarm_human_messages WHERE run_id=? ORDER BY message_id DESC LIMIT 6 FOR UPDATE
                    """, String.class, rid);
            Collections.reverse(messages);
            String payload = messages.isEmpty() ? "" : encode(messages.stream().map(message -> clipHistory(message, 1000)).toList());
            db.update("INSERT INTO swarm_input_snapshots(run_id,stage,closes_input,payload) VALUES(?,?,?,?)",
                    rid, stage, closeInput, payload);
            return payload;
        });
    }

    public void rememberAgentRun(String rid, String owner, String token, String stage, String scope,
                                 String query, String result) {
        String key = memoryKey(stage);
        String scopeKey = memoryKey(scope);
        writeJournal(rid, owner, token, () -> {
            String prompt = clipHistory(query, 800);
            String output = clipHistory(result, 1800);
            if (db.queryForObject("SELECT COUNT(*) FROM agent_run_memories WHERE run_id=? AND memory_key=?",
                    Integer.class, rid, key) == 0) {
                db.update("INSERT INTO agent_run_memories VALUES(?,?,?,?,?)", rid, key, scopeKey, prompt, output);
            } else {
                db.update("""
                        UPDATE agent_run_memories SET memory_scope=?,query_text=?,result_text=?
                        WHERE run_id=? AND memory_key=?
                        """, scopeKey, prompt, output, rid, key);
            }
        });
    }

    public List<Map<String, String>> agentRunExamples(String rid, String owner, String scope, String query) {
        var rows = db.queryForList("""
                SELECT m.query_text,m.result_text FROM generation_runs current_run
                JOIN projects p ON p.id=current_run.project_id
                JOIN generation_runs past ON past.project_id=current_run.project_id
                JOIN agent_run_memories m ON m.run_id=past.id
                WHERE current_run.id=? AND p.owner_id=? AND past.id<>current_run.id
                  AND past.status='SUCCEEDED' AND m.memory_scope=?
                ORDER BY past.created_at DESC,past.id DESC,m.memory_key LIMIT 50
                """, rid, owner, memoryKey(scope));
        var similarity = new PromptSimilarity(query);
        return rows.stream().map(row -> Map.of("query", (String) row.get("query_text"),
                        "result", (String) row.get("result_text")))
                .filter(example -> similarity.score(example.get("query")) >= 0.35)
                .sorted(Comparator.comparingDouble((Map<String, String> example) ->
                        similarity.score(example.get("query"))).reversed())
                .distinct().limit(2).toList();
    }

    private static String memoryKey(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
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
        reserveModelCall(rid, owner, token, maxCalls, tokenLimit, estimate, node, nodeLimit, null);
    }

    public void reserveModelCall(String rid, String owner, String token, int maxCalls,
                                 long tokenLimit, long estimate, String node, long nodeLimit, String model) {
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
            reserveScheduledCost(rid, model, estimate);
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
                    db.update("UPDATE scheduled_run_budgets SET charged_usd=charged_usd+? WHERE run_id=?",
                            cost.subtract(estimatedCost(rate, estimate)), rid);
                    if (db.update("UPDATE agent_model_cost SET usd=usd+? WHERE run_id=? AND model=?", cost, rid, model) == 0)
                        db.update("INSERT INTO agent_model_cost VALUES(?,?,?)", rid, model, cost);
                }
            }
        });
    }

    private void reserveScheduledCost(String run, String model, long estimate) {
        var rows = db.queryForList("SELECT limit_usd,charged_usd FROM scheduled_run_budgets WHERE run_id=?", run);
        if (rows.isEmpty()) return;
        var rate = model == null ? null : modelRates.get(model);
        if (rate == null) throw new IllegalStateException("定时任务金额预算需要为所选模型配置费率");
        var budget = rows.getFirst();
        var cost = estimatedCost(rate, estimate);
        var total = ((BigDecimal) budget.get("charged_usd")).add(cost);
        if (total.compareTo((BigDecimal) budget.get("limit_usd")) > 0)
            throw new IllegalStateException("定时任务剩余金额预算不足，无法预留本次模型调用");
        db.update("UPDATE scheduled_run_budgets SET charged_usd=? WHERE run_id=?", total, run);
    }

    private static BigDecimal estimatedCost(ModelRate rate, long tokens) {
        return rate.input().max(rate.output()).multiply(BigDecimal.valueOf(tokens)).movePointLeft(6)
                .setScale(18, java.math.RoundingMode.CEILING);
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

    public boolean capturesGatewayMemory(String id) {
        return !db.queryForList("SELECT run_id FROM run_gateway_memory_capture WHERE run_id=?", id).isEmpty();
    }

    public void finishReply(String rid, String pid, String content, String mode, String model, byte[] image, String mediaType, String prompt) {
        finishReply(rid, pid, content, mode, model, image, mediaType, prompt, null);
    }

    public void finishReply(String rid, String pid, String content, String mode, String model, byte[] image, String mediaType, String prompt, String token) {
        finishReply(rid, pid, content, mode, model, image, mediaType, prompt, token, "");
    }

    public void finishReply(String rid, String pid, String content, String mode, String model, byte[] image,
                            String mediaType, String prompt, String token, String extractedMemory) {
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
            String reply = content;
            if ("gateway-agent".equals(mode) && content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1500 * 1024) {
                var ref = new LinkedHashMap<>(storeGatewayBlob(rid, "full_output", content));
                ref.put("_stored_in_redis", true);
                ref.put("size_bytes", ref.remove("size"));
                reply = encode(ref);
            }
            richMessage(pid, "assistant", reply, rid, mode, model, asset);
            saveGatewayMemory(rid, extractedMemory);
            stage(rid, "SUCCEEDED");
            db.update("UPDATE projects SET active_run=NULL,updated_at=? WHERE id=?", now(), pid);
        });
    }

    private void saveGatewayMemory(String run, String extracted) {
        if (extracted.isBlank()) return;
        db.update("""
                INSERT INTO gateway_memory_entries(run_id,memory_scope,content,created_at)
                SELECT c.run_id,c.memory_scope,?,? FROM run_gateway_memory_capture c
                WHERE c.run_id=? AND NOT EXISTS (
                    SELECT 1 FROM gateway_memory_entries m WHERE m.memory_scope=c.memory_scope AND m.content=?
                )
                """, extracted, now(), run, extracted);
    }

    public Optional<Map<String, Object>> imageSource(String run, String project) {
        return db.queryForList("""
                SELECT a.image_data,a.media_type FROM run_image_sources s JOIN image_assets a ON a.id=s.asset_id
                WHERE s.run_id=? AND a.project_id=?
                """, run, project).stream().findFirst();
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
