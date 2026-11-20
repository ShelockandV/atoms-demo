package dev.atoms.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 提供持久化运行指标，例如 Prometheus 使用 Bearer 凭据读取 /metrics。 */
@RestController
@ConditionalOnProperty(name = "atoms.metrics.enabled", havingValue = "true")
public final class MetricsController {
    private final JdbcTemplate db;
    private final byte[] expectedAuthorization;

    public MetricsController(JdbcTemplate db, @Value("${atoms.metrics.token:}") String token) {
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("启用指标时须配置有效的 atoms.metrics.token");
        this.db = db;
        expectedAuthorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/metrics", produces = "text/plain;version=0.0.4;charset=UTF-8")
    ResponseEntity<String> metrics(@RequestHeader(value = "Authorization", defaultValue = "") String authorization) {
        if (!MessageDigest.isEqual(expectedAuthorization, authorization.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(401).header("WWW-Authenticate", "Bearer").build();

        var body = new StringBuilder();
        var runs = db.queryForList("SELECT status,COUNT(*) AS n FROM generation_runs GROUP BY status ORDER BY status");
        metric(body, "atoms_runs_submitted_total", "counter",
                runs.stream().mapToLong(row -> ((Number) row.get("n")).longValue()).sum());
        body.append("# TYPE atoms_runs gauge\n");
        for (var run : runs) {
            String status = (String) run.get("status");
            if (status.matches("[A-Z_]{1,30}"))
                body.append("atoms_runs{status=\"").append(status).append("\"} ").append(run.get("n")).append('\n');
        }
        var usage = db.queryForMap("""
                SELECT COALESCE(SUM(model_calls),0) AS calls, COALESCE(SUM(metered_calls),0) AS metered,
                       COALESCE(SUM(prompt_tokens),0) AS input_tokens,
                       COALESCE(SUM(completion_tokens),0) AS output_tokens
                FROM agent_usage
                """);
        metric(body, "atoms_model_calls_total", "counter", usage.get("calls"));
        metric(body, "atoms_model_metered_calls_total", "counter", usage.get("metered"));
        metric(body, "atoms_model_input_tokens_total", "counter", usage.get("input_tokens"));
        metric(body, "atoms_model_output_tokens_total", "counter", usage.get("output_tokens"));
        metric(body, "atoms_model_estimated_cost_usd_total", "counter",
                db.queryForObject("SELECT COALESCE(SUM(usd),0) FROM agent_model_cost", java.math.BigDecimal.class));
        var models = db.queryForList("""
                SELECT model,SUM(metered_calls) AS calls,SUM(input_tokens) AS input_tokens,
                SUM(output_tokens) AS output_tokens FROM agent_model_usage GROUP BY model ORDER BY model
                """);
        for (String measure : java.util.List.of("calls", "input_tokens", "output_tokens")) {
            String name = "atoms_metered_model_" + measure + "_total";
            body.append("# TYPE ").append(name).append(" counter\n");
            for (var model : models) {
                String label = ((String) model.get("model")).replace("\\", "\\\\")
                        .replace("\"", "\\\"").replace("\n", "\\n");
                body.append(name).append("{model=\"").append(label).append("\"} ")
                        .append(model.get(measure)).append('\n');
            }
        }
        var steps = db.queryForMap("""
                SELECT COUNT(*) AS n, COALESCE(SUM(execution_time_ms),0) AS elapsed_ms
                FROM agent_step_metrics
                """);
        metric(body, "atoms_agent_steps_total", "counter", steps.get("n"));
        metric(body, "atoms_agent_step_duration_milliseconds_total", "counter", steps.get("elapsed_ms"));
        return ResponseEntity.ok(body.toString());
    }

    private static void metric(StringBuilder body, String name, String type, Object value) {
        body.append("# TYPE ").append(name).append(' ').append(type).append('\n')
                .append(name).append(' ').append(value).append('\n');
    }
}
