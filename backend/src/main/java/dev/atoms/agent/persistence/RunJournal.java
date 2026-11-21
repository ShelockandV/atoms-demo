package dev.atoms.agent.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.*;

/**
 * 读写 Agent 检查点、步骤、DAG 节点及模型用量。写入由 Store 放在任务事务内。
 *
 * <p>示例：journal.steps(runId) 按步骤读取执行记录。</p>
 *
 * Journal writes are called inside the run transaction after checking the execution token.
 */
public final class RunJournal {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public RunJournal(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String now() {
        return Instant.now().toString();
    }

    public <T> T snapshot(String rid, Class<T> type) {
        var rows = db.queryForList("SELECT snapshot FROM agent_checkpoints WHERE run_id=?", rid);
        if (rows.isEmpty()) return null;
        try {
            return json.readValue((String) rows.getFirst().get("snapshot"), type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Agent 检查点无法读取", e);
        }
    }

    public List<Map<String, Object>> dagNodes(String rid) {
        return db.queryForList("SELECT * FROM agent_dag_nodes WHERE run_id=? ORDER BY node_id", rid).stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("node_id"));
            item.put("status", row.get("status"));
            item.put("output", row.get("output"));
            item.put("executionTimeMs", row.get("execution_time_ms"));
            try {
                item.put("dependencies", json.readValue((String) row.get("dependencies"), new TypeReference<List<String>>() {
                }));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("DAG 依赖记录无法读取", e);
            }
            return item;
        }).toList();
    }

    public void saveNode(String rid, String node, List<String> dependencies, String status, String output, long elapsedMs) {
        if (db.update("UPDATE agent_dag_nodes SET status=?,output=?,execution_time_ms=? WHERE run_id=? AND node_id=?", status, output, elapsedMs, rid, node) == 0)
            db.update("INSERT INTO agent_dag_nodes(run_id,node_id,dependencies,status,output,execution_time_ms) VALUES(?,?,?,?,?,?)", rid, node, encode(dependencies), status, output, elapsedMs);
    }

    public boolean reserveModelCall(String rid, int maxCalls) {
        var rows = db.queryForList("SELECT model_calls FROM agent_usage WHERE run_id=? FOR UPDATE", rid);
        if (rows.isEmpty())
            db.update("INSERT INTO agent_usage(run_id,model_calls,metered_calls,prompt_tokens,completion_tokens) VALUES(?,1,0,0,0)", rid);
        else {
            if (((Number) rows.getFirst().get("model_calls")).intValue() >= maxCalls) return false;
            db.update("UPDATE agent_usage SET model_calls=model_calls+1 WHERE run_id=?", rid);
        }
        return true;
    }

    public void recordModelUsage(String rid, long promptTokens, long completionTokens) {
        db.update("UPDATE agent_usage SET metered_calls=metered_calls+1,prompt_tokens=prompt_tokens+?,completion_tokens=completion_tokens+? WHERE run_id=?", promptTokens, completionTokens, rid);
    }

    public long chargedTokens(String rid) {
        var rows = db.queryForList("SELECT charged_tokens FROM agent_token_budget WHERE run_id=? FOR UPDATE", rid);
        if (!rows.isEmpty()) return ((Number) rows.getFirst().get("charged_tokens")).longValue();
        var totals = usage(rid);
        if (totals.isEmpty()) return 0;
        return ((Number) totals.get("promptTokens")).longValue() + ((Number) totals.get("completionTokens")).longValue();
    }

    public void addTokenCharge(String rid, long delta) {
        if (delta == 0) return;
        if (db.update("UPDATE agent_token_budget SET charged_tokens=charged_tokens+? WHERE run_id=?", delta, rid) == 0)
            db.update("INSERT INTO agent_token_budget(run_id,charged_tokens) VALUES(?,?)", rid, chargedTokens(rid) + delta);
    }

    public void saveStep(String rid, int step, String encoded, String action, String summary,
                         String outcome, String observation, Long elapsedMs) {
        if (db.update("UPDATE agent_checkpoints SET snapshot=? WHERE run_id=?", encoded, rid) == 0)
            db.update("INSERT INTO agent_checkpoints(run_id,snapshot) VALUES(?,?)", rid, encoded);
        if (action != null) {
            db.update("INSERT INTO agent_steps(run_id,step_number,action,summary,outcome,observation,updated_at) VALUES(?,?,?,?,?,?,?)",
                    rid, step, action, summary, outcome, "", now());
            return;
        }
        db.update("UPDATE agent_steps SET outcome=?,observation=?,updated_at=? WHERE run_id=? AND step_number=?",
                outcome, observation, now(), rid, step);
        if (elapsedMs != null && db.update("UPDATE agent_step_metrics SET execution_time_ms=? WHERE run_id=? AND step_number=?",
                elapsedMs, rid, step) == 0)
            db.update("INSERT INTO agent_step_metrics(run_id,step_number,execution_time_ms) VALUES(?,?,?)", rid, step, elapsedMs);
    }

    public List<Map<String, Object>> steps(String rid) {
        return db.queryForList("SELECT s.*,m.execution_time_ms FROM agent_steps s LEFT JOIN agent_step_metrics m ON m.run_id=s.run_id AND m.step_number=s.step_number WHERE s.run_id=? ORDER BY s.step_number", rid).stream().map(step -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("step", step.get("step_number"));
            for (String key : List.of("action", "summary", "outcome", "observation")) item.put(key, step.get(key));
            item.put("updatedAt", step.get("updated_at"));
            item.put("executionTimeMs", step.get("execution_time_ms"));
            return item;
        }).toList();
    }

    public Map<String, Object> usage(String rid) {
        var usage = db.queryForList("SELECT * FROM agent_usage WHERE run_id=?", rid);
        if (!usage.isEmpty()) {
            var u = usage.getFirst();
            var result = new LinkedHashMap<String, Object>(Map.of("modelCalls", u.get("model_calls"),
                    "meteredCalls", u.get("metered_calls"), "promptTokens", u.get("prompt_tokens"),
                    "completionTokens", u.get("completion_tokens")));
            var budget = db.queryForList("SELECT charged_tokens FROM agent_token_budget WHERE run_id=?", rid);
            if (!budget.isEmpty()) result.put("chargedTokens", budget.getFirst().get("charged_tokens"));
            var models = db.queryForList("""
                    SELECT model,metered_calls,input_tokens,output_tokens
                    FROM agent_model_usage WHERE run_id=? ORDER BY model
                    """, rid);
            if (!models.isEmpty()) result.put("models", models.stream().map(model -> Map.of(
                    "model", model.get("model"), "meteredCalls", model.get("metered_calls"),
                    "promptTokens", model.get("input_tokens"), "completionTokens", model.get("output_tokens"))).toList());
            return result;
        }
        return Map.of();
    }
}
