package dev.atoms.web;

import dev.atoms.agent.runtime.TemporalTaskRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通过一次数据库查询报告服务健康状态。
 *
 * <p>示例：GET /api/health 查询数据库是否可用。</p>
 */
@RestController
public class HealthController {
    private final JdbcTemplate db;
    private final ObjectProvider<TemporalTaskRuntime> temporal;

    public HealthController(JdbcTemplate db, ObjectProvider<TemporalTaskRuntime> temporal) {
        this.db = db;
        this.temporal = temporal;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        try {
            db.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (org.springframework.dao.DataAccessException e) {
            return ResponseEntity.status(503).body(Map.of("status", "DOWN"));
        }
    }
    @GetMapping("/health")
    public Map<String, Object> gatewayHealth() {
        return probe("healthy", Map.of("gateway", "ok"));
    }

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> gatewayReadiness() {
        var checks = new LinkedHashMap<String, String>();
        try {
            db.queryForObject("SELECT 1", Integer.class);
            checks.put("database", "ok");
        } catch (org.springframework.dao.DataAccessException unavailable) {
            checks.put("database", "failed");
        }
        var runtime = temporal.getIfAvailable();
        if (runtime != null) checks.put("temporal", runtime.ready() ? "ok" : "failed");
        boolean ready = !checks.containsValue("failed");
        return ResponseEntity.status(ready ? 200 : 503).body(probe(ready ? "ready" : "not ready", checks));
    }

    private static Map<String, Object> probe(String status, Map<String, String> checks) {
        return Map.of("status", status, "version", "0.1.0", "time", Instant.now(), "checks", checks);
    }

}
