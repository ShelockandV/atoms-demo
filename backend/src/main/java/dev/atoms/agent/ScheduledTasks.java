package dev.atoms.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.atoms.agent.model.ModelCatalog;
import dev.atoms.agent.persistence.Store;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Persists recurring task definitions and enqueues each due occurrence with its execution record. */
@Service
@DependsOnDatabaseInitialization
public class ScheduledTasks {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(ScheduledTasks.class);
    private static final long MIN_INTERVAL_SECONDS = 3600;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Store store;
    private final ModelCatalog models;
    private final TaskApprovalPolicy approvalPolicy;
    private TemporalTaskRuntime temporal;
    private final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("atoms-schedules").daemon().factory());

    public ScheduledTasks(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json, Store store, ModelCatalog models,
                          TaskApprovalPolicy approvalPolicy) {
        this.db = db;
        this.tx = tx;
        this.json = json;
        this.store = store;
        this.models = models;
        this.approvalPolicy = approvalPolicy;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureTemporal(TemporalTaskRuntime temporal) {
        this.temporal = temporal;
    }

    @PostConstruct
    void start() {
        timer.scheduleWithFixedDelay(() -> {
            try {
                Instant now = Instant.now();
                store.expireScheduledRuns(now.getEpochSecond());
                store.expireDaemonClaims(now.getEpochSecond());
                store.expireDaemonConnections(now.getEpochSecond());
                store.expireGatewayBlobs(now.getEpochSecond());
                if (temporal == null) dispatchDue(now);
                else reconcileTemporal();
            } catch (RuntimeException error) {
                log.warn("Could not scan schedules", error);
            }
        }, 1, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    public record Input(String name, String description,
                        @JsonProperty("cron_expression") String cron,
                        String timezone,
                        @JsonProperty("task_query") String query,
                        @JsonProperty("task_context") Map<String, Object> context,
                        @JsonProperty("timeout_seconds") Integer timeoutSeconds,
                        @JsonProperty("max_budget_per_run_usd") BigDecimal maxBudgetPerRunUsd,
                        @JsonProperty("execution_target") String executionTarget,
                        @JsonProperty("agent_name") String agentName) {}

    private record Definition(String name, String description, String cron, String zone, String query,
                              String context, long nextEpoch) {}

    public Map<String, Object> create(String owner, Input input, String requester) {
        Instant now = Instant.now();
        var definition = definition(input, Map.of(), now);
        String id = Store.id();
        tx.executeWithoutResult(status -> {
            db.update("""
                INSERT INTO scheduled_tasks(id,owner_id,name,description,cron_expression,timezone,
                    task_query,task_context,status,next_run_epoch,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,'ACTIVE',?,?,?)
                """, id, owner, definition.name(), definition.description(), definition.cron(), definition.zone(),
                definition.query(), definition.context(), definition.nextEpoch(), now.toString(), now.toString());
            saveTimeout(id, input.timeoutSeconds());
            saveBudget(id, input.maxBudgetPerRunUsd());
            saveDaemonTarget(id, input, requester);
        });
        return Map.of("schedule_id", id, "message", "Schedule created", "next_run_at", iso(definition.nextEpoch()));
    }

    public Map<String, Object> get(String id, String owner) {
        return view(require(id, owner, false));
    }

    public Map<String, Object> list(String owner, int page, int size, String status) {
        String filter = status.toUpperCase(Locale.ROOT);
        if (!filter.isEmpty() && !Set.of("ACTIVE", "PAUSED").contains(filter))
            throw Store.error(400, "计划状态须为 ACTIVE 或 PAUSED");
        String where = " WHERE s.owner_id=? AND s.status<>'DELETED' AND (?='' OR s.status=?)";
        var rows = db.queryForList("""
                SELECT s.*,t.applied_version FROM scheduled_tasks s
                LEFT JOIN temporal_schedule_sync t ON t.schedule_id=s.id
                """ + where + " ORDER BY s.created_at DESC,s.id DESC LIMIT ? OFFSET ?",
                owner, filter, filter, size, (long) (page - 1) * size);
        long count = db.queryForObject("SELECT COUNT(*) FROM scheduled_tasks s" + where, Long.class, owner, filter, filter);
        return Map.of("schedules", rows.stream().map(this::view).toList(), "total_count", count,
                "page", page, "page_size", size);
    }

    public Map<String, Object> update(String id, String owner, Input input, String requester) {
        return tx.execute(status -> {
            var row = require(id, owner, true);
            Instant now = Instant.now();
            var definition = definition(input, row, now);
            Object next = row.get("next_run_epoch");
            if ("ACTIVE".equals(row.get("status"))
                    && (!definition.cron().equals(row.get("cron_expression")) || !definition.zone().equals(row.get("timezone"))))
                next = definition.nextEpoch();
            db.update("""
                    UPDATE scheduled_tasks SET name=?,description=?,cron_expression=?,timezone=?,task_query=?,
                        task_context=?,next_run_epoch=?,updated_at=? WHERE id=?
                    """, definition.name(), definition.description(), definition.cron(), definition.zone(),
                    definition.query(), definition.context(), next, now.toString(), id);
            saveTimeout(id, input.timeoutSeconds());
            saveBudget(id, input.maxBudgetPerRunUsd());
            saveDaemonTarget(id, input, requester);
            return Map.of("success", true, "message", "Schedule updated", "schedule_id", id);
        });
    }

    public Map<String, Object> changeStatus(String id, String owner, String target) {
        return tx.execute(status -> {
            var row = require(id, owner, true);
            if (!target.equals(row.get("status"))) {
                Instant now = Instant.now();
                Long next = "ACTIVE".equals(target) ? nextRun((String) row.get("cron_expression"),
                        (String) row.get("timezone"), now) : null;
                db.update("UPDATE scheduled_tasks SET status=?,next_run_epoch=?,updated_at=? WHERE id=?",
                        target, next, now.toString(), id);
            }
            return Map.of("success", true, "message", "Schedule " + target.toLowerCase(Locale.ROOT), "schedule_id", id);
        });
    }

    public Map<String, Object> runs(String id, String owner, int page, int size) {
        require(id, owner, false);
        var rows = db.queryForList("""
                SELECT e.run_id,e.scheduled_epoch,r.project_id,r.status,r.prompt,r.error,o.model,a.reason AS approval_reason,
                       (SELECT m.content FROM messages m JOIN message_details d ON d.message_id=m.id
                        WHERE d.run_id=r.id AND m.role='assistant' AND r.status='SUCCEEDED'
                        ORDER BY m.created_at DESC,m.id DESC LIMIT 1) AS result,
                       COALESCE(u.prompt_tokens+u.completion_tokens,0) AS total_tokens,b.limit_usd,
                       (SELECT COALESCE(SUM(c.usd),0) FROM agent_model_cost c WHERE c.run_id=r.id) AS total_cost_usd
                FROM scheduled_task_executions e JOIN generation_runs r ON r.id=e.run_id
                JOIN run_options o ON o.run_id=e.run_id
                LEFT JOIN run_approval_requirements a ON a.run_id=e.run_id
                LEFT JOIN agent_usage u ON u.run_id=e.run_id
                LEFT JOIN scheduled_run_budgets b ON b.run_id=e.run_id WHERE e.schedule_id=?
                ORDER BY e.scheduled_epoch DESC LIMIT ? OFFSET ?
                """, id, size, (long) (page - 1) * size);
        var runs = rows.stream().map(row -> {
            // Schedule history remains visible after a generated session is deleted.
            var result = new LinkedHashMap<String, Object>();
            result.put("workflow_id", row.get("run_id"));
            result.put("session_id", row.get("project_id"));
            result.put("triggered_at", iso(row.get("scheduled_epoch")));
            result.put("status", "SUCCEEDED".equals(row.get("status")) ? "COMPLETED" : row.get("status"));
            result.put("query", row.get("prompt"));
            result.put("model_used", row.get("model"));
            if (row.get("result") != null) result.put("result", row.get("result"));
            if (row.get("error") != null) result.put("error_message", row.get("error"));
            if ("AWAITING_APPROVAL".equals(row.get("status")) && row.get("approval_reason") != null)
                result.put("approval_reason", row.get("approval_reason"));
            result.put("total_tokens", row.get("total_tokens"));
            result.put("total_cost_usd", row.get("total_cost_usd"));
            if (row.get("limit_usd") != null) result.put("max_budget_per_run_usd", row.get("limit_usd"));
            return result;
        }).toList();
        long count = db.queryForObject("SELECT COUNT(*) FROM scheduled_task_executions WHERE schedule_id=?", Long.class, id);
        return Map.of("runs", runs, "total_count", count, "page", page, "page_size", size);
    }

    private void reconcileTemporal() {
        var pending = db.queryForList("""
                SELECT s.id FROM scheduled_tasks s LEFT JOIN temporal_schedule_sync t ON t.schedule_id=s.id
                WHERE t.schedule_id IS NULL OR t.applied_version<>s.updated_at
                ORDER BY s.updated_at,s.id LIMIT 32
                """, String.class);
        for (String id : pending) {
            try {
                tx.executeWithoutResult(status -> {
                    var rows = db.queryForList("SELECT * FROM scheduled_tasks WHERE id=? FOR UPDATE", id);
                    if (rows.isEmpty()) return;
                    var row = rows.getFirst();
                    String version = (String) row.get("updated_at");
                    var applied = db.queryForList("SELECT applied_version FROM temporal_schedule_sync WHERE schedule_id=?",
                            String.class, id);
                    if (applied.contains(version)) return;
                    Instant next = temporal.syncSchedule(id, version, (String) row.get("cron_expression"),
                            (String) row.get("timezone"), (String) row.get("status"));
                    db.update("UPDATE scheduled_tasks SET next_run_epoch=? WHERE id=?",
                            next == null ? null : next.getEpochSecond(), id);
                    if (applied.isEmpty())
                        db.update("INSERT INTO temporal_schedule_sync(schedule_id,applied_version) VALUES(?,?)", id, version);
                    else db.update("UPDATE temporal_schedule_sync SET applied_version=? WHERE schedule_id=?", version, id);
                });
            } catch (RuntimeException error) {
                log.warn("Could not synchronize Temporal schedule {}", id, error);
            }
        }
    }

    void enqueueTemporal(String id, String version, long occurrence) {
        tx.executeWithoutResult(status -> {
            var rows = db.queryForList("SELECT * FROM scheduled_tasks WHERE id=? FOR UPDATE", id);
            if (rows.isEmpty()) return;
            var row = rows.getFirst();
            if (!"ACTIVE".equals(row.get("status")) || !version.equals(row.get("updated_at"))) return;
            var recent = db.queryForObject("SELECT MAX(scheduled_epoch) FROM scheduled_task_executions WHERE schedule_id=?",
                    Long.class, id);
            if (recent != null && occurrence - recent < MIN_INTERVAL_SECONDS) return;
            enqueueOccurrence(id, row, occurrence, Instant.now());
        });
    }

    void dispatchDue(Instant now) {
        var due = db.queryForList("""
                SELECT id FROM scheduled_tasks WHERE status='ACTIVE' AND next_run_epoch<=?
                ORDER BY next_run_epoch,id LIMIT 32
                """, String.class, now.getEpochSecond());
        for (String id : due) {
            try {
                tx.executeWithoutResult(status -> enqueueDue(id, now));
            } catch (RuntimeException error) {
                log.warn("Could not enqueue schedule {}", id, error);
            }
        }
    }

    private void enqueueDue(String id, Instant now) {
        var rows = db.queryForList("SELECT * FROM scheduled_tasks WHERE id=? FOR UPDATE", id);
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        if (!"ACTIVE".equals(row.get("status")) || !(row.get("next_run_epoch") instanceof Number next)
                || next.longValue() > now.getEpochSecond()) return;
        enqueueOccurrence(id, row, next.longValue(), now);
    }

    private void saveDaemonTarget(String id, Input input, String requester) {
        if (input.executionTarget() == null && input.agentName() == null) return;
        var previous = daemonTarget(id);
        String target = Objects.toString(input.executionTarget(), previous.isEmpty() ? "cloud" : "daemon");
        if (!Set.of("cloud", "daemon").contains(target)) throw Store.error(400, "execution_target 须为 cloud 或 daemon");
        if ("cloud".equals(target)) {
            db.update("DELETE FROM scheduled_daemon_targets WHERE schedule_id=?", id);
            return;
        }
        if (requester == null) throw Store.error(401, "daemon 计划须使用 Gateway 身份凭据");
        String agent = Objects.toString(input.agentName(), Objects.toString(previous.get("agent_name"), "")).strip();
        if (agent.length() > 100) throw Store.error(400, "agent_name 超过长度限制");
        db.update("DELETE FROM scheduled_daemon_targets WHERE schedule_id=?", id);
        db.update("INSERT INTO scheduled_daemon_targets(schedule_id,requester,agent_name) VALUES(?,?,?)", id, requester, agent);
    }

    private Map<String, Object> daemonTarget(String id) {
        var rows = db.queryForList("SELECT requester,agent_name FROM scheduled_daemon_targets WHERE schedule_id=?", id);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private void enqueueOccurrence(String id, Map<String, Object> row, long scheduledEpoch, Instant now) {
        String owner = (String) row.get("owner_id");
        String query = (String) row.get("task_query");
        var context = new LinkedHashMap<>(parseContext((String) row.get("task_context")));
        context.put("trigger_type", "schedule");
        context.put("schedule_id", id);
        var daemon = daemonTarget(id);
        String model = daemon.isEmpty() ? models.route("chat", null, query, null, "react") : "daemon";
        String approval = null;
        String failure = null;
        try {
            approval = approvalPolicy.requirement(owner, query, "chat", model, "react", null, null);
        } catch (ResponseStatusException rejected) {
            int status = rejected.getStatusCode().value();
            if (status != 403 && status != 503) throw rejected;
            failure = rejected.getReason();
        }
        String project = (String) store.createGatewaySession(owner).get("id");
        String run = (String) store.queue(project, owner, query, null, "chat", model,
                "react", null, null, context, approval).get("id");
        // Both callers hold the schedule transaction, so a rejected run is never visible as queued.
        if (failure != null) store.fail(run, project, failure);
        db.update("INSERT INTO scheduled_task_executions(schedule_id,run_id,scheduled_epoch) VALUES(?,?,?)",
                id, run, scheduledEpoch);
        if (!daemon.isEmpty() && failure == null)
            db.update("INSERT INTO scheduled_daemon_runs(run_id,requester,agent_name) VALUES(?,?,?)",
                    run, daemon.get("requester"), daemon.get("agent_name"));
        int timeout = timeout(id);
        if (!daemon.isEmpty()) timeout = timeout == 0 ? 600 : Math.min(timeout, 600);
        if (failure == null && timeout > 0)
            db.update("INSERT INTO scheduled_run_deadlines(run_id,deadline_epoch) VALUES(?,?)", run, now.getEpochSecond() + timeout);
        var budget = budget(id);
        if (budget.signum() > 0)
            db.update("INSERT INTO scheduled_run_budgets(run_id,limit_usd) VALUES(?,?)", run, budget);
        long following = nextRun((String) row.get("cron_expression"), (String) row.get("timezone"),
                Instant.ofEpochSecond(Math.max(now.getEpochSecond(), scheduledEpoch + MIN_INTERVAL_SECONDS - 1)));
        db.update("UPDATE scheduled_tasks SET next_run_epoch=?,last_run_epoch=? WHERE id=?",
                following, now.getEpochSecond(), id);
    }

    private Map<String, Object> require(String id, String owner, boolean lock) {
        var rows = lock ? db.queryForList("""
                SELECT * FROM scheduled_tasks WHERE id=? AND owner_id=? AND status<>'DELETED' FOR UPDATE
                """, id, owner) : db.queryForList("""
                SELECT s.*,t.applied_version FROM scheduled_tasks s
                LEFT JOIN temporal_schedule_sync t ON t.schedule_id=s.id
                WHERE s.id=? AND s.owner_id=? AND s.status<>'DELETED'
                """, id, owner);
        if (rows.isEmpty()) throw Store.error(404, "计划不存在");
        return rows.getFirst();
    }

    private Map<String, Object> view(Map<String, Object> row) {
        var result = new LinkedHashMap<>(row);
        result.remove("owner_id");
        Object applied = result.remove("applied_version");
        if (temporal != null) result.put("sync_pending", !Objects.equals(applied, row.get("updated_at")));
        result.put("next_run_at", iso(result.remove("next_run_epoch")));
        result.put("last_run_at", iso(result.remove("last_run_epoch")));
        result.put("task_context", parseContext((String) result.get("task_context")));
        var daemon = daemonTarget((String) row.get("id"));
        result.put("execution_target", daemon.isEmpty() ? "cloud" : "daemon");
        if (!daemon.isEmpty()) result.put("agent_name", daemon.get("agent_name"));
        result.put("timeout_seconds", timeout((String) row.get("id")));
        result.put("max_budget_per_run_usd", budget((String) row.get("id")));
        return result;
    }

    private Definition definition(Input input, Map<String, Object> existing, Instant now) {
        if (input.timeoutSeconds() != null && (input.timeoutSeconds() < 0 || input.timeoutSeconds() > 86400))
            throw Store.error(400, "timeout_seconds 须在 0 至 86400 之间");
        if (input.maxBudgetPerRunUsd() != null && (input.maxBudgetPerRunUsd().signum() < 0
                || input.maxBudgetPerRunUsd().compareTo(new BigDecimal("1000000")) > 0
                || input.maxBudgetPerRunUsd().stripTrailingZeros().scale() > 12))
            throw Store.error(400, "max_budget_per_run_usd 须在 0 至 1000000 之间，最多 12 位小数");
        String name = field(input.name(), existing, "name", "", 200, true);
        String description = field(input.description(), existing, "description", "", 1000, false);
        String cron = field(input.cron(), existing, "cron_expression", "", 128, true);
        String zone = field(input.timezone(), existing, "timezone", "UTC", 128, true);
        if (temporal != null && !ZoneId.getAvailableZoneIds().contains(zone))
            throw Store.error(400, "Temporal 计划须使用 IANA 时区");
        String query = field(input.query(), existing, "task_query", "", 10000, true);
        String context = input.context() == null ? (String) existing.getOrDefault("task_context", "{}") : store.encode(input.context());
        if (context.length() > 15000) throw Store.error(400, "计划任务 context 超过长度限制");
        long next = nextRun(cron, zone, now);
        long after = nextRun(cron, zone, Instant.ofEpochSecond(next));
        if (after - next < MIN_INTERVAL_SECONDS) throw Store.error(400, "相邻计划任务至少间隔一小时");
        return new Definition(name, description, cron, zone, query, context, next);
    }

    private int timeout(String id) {
        return db.queryForList("SELECT timeout_seconds FROM scheduled_task_timeouts WHERE schedule_id=?", Integer.class, id)
                .stream().findFirst().orElse(0);
    }

    private void saveTimeout(String id, Integer timeout) {
        if (timeout == null) return;
        db.update("DELETE FROM scheduled_task_timeouts WHERE schedule_id=?", id);
        if (timeout > 0)
            db.update("INSERT INTO scheduled_task_timeouts(schedule_id,timeout_seconds) VALUES(?,?)", id, timeout);
    }

    private BigDecimal budget(String id) {
        return db.queryForList("SELECT limit_usd FROM scheduled_task_budgets WHERE schedule_id=?", BigDecimal.class, id)
                .stream().findFirst().orElse(BigDecimal.ZERO);
    }

    private void saveBudget(String id, BigDecimal value) {
        if (value == null) return;
        db.update("DELETE FROM scheduled_task_budgets WHERE schedule_id=?", id);
        if (value.signum() > 0)
            db.update("INSERT INTO scheduled_task_budgets(schedule_id,limit_usd) VALUES(?,?)", id, value);
    }

    private static String field(String provided, Map<String, Object> existing, String key, String fallback,
                                int max, boolean required) {
        String value = Objects.requireNonNullElse(provided, (String) existing.getOrDefault(key, fallback)).strip();
        if (value.length() > max || required && value.isEmpty()) throw Store.error(400, key + " 无效");
        return value;
    }

    private static long nextRun(String cron, String zone, Instant after) {
        try {
            if (cron.split("\\s+").length != 5) throw new IllegalArgumentException("expected five fields");
            var next = CronExpression.parse("0 " + cron).next(after.atZone(ZoneId.of(zone)));
            if (next != null) return next.toEpochSecond();
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw Store.error(400, "Cron 表达式或时区无效");
        }
        throw Store.error(400, "Cron 表达式没有可用的执行时间");
    }

    private Map<String, Object> parseContext(String raw) {
        try {
            return json.readValue(raw, new TypeReference<>() {});
        } catch (JsonProcessingException problem) {
            throw new IllegalStateException("无法读取计划任务的上下文", problem);
        }
    }

    private static String iso(Object epoch) {
        return epoch instanceof Number number ? Instant.ofEpochSecond(number.longValue()).toString() : null;
    }
}
