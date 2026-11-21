package dev.atoms.web;

import dev.atoms.agent.persistence.Store;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
final class DaemonApprovals {
    private static final Duration TTL = Duration.ofMinutes(10);
    private final JdbcTemplate db;
    private final NamedParameterJdbcTemplate named;

    DaemonApprovals(JdbcTemplate db) {
        this.db = db;
        this.named = new NamedParameterJdbcTemplate(db);
    }

    record Request(String id, String agent, String tool, String arguments, String threadId) {}

    void submit(String tenant, String requester, Request request) {
        if (!request.id().matches("[A-Za-z0-9_.:-]{1,100}") || request.agent().length() > 128
                || request.tool().isBlank() || request.tool().length() > 128
                || request.arguments().length() > 16000 || request.threadId().length() > 200)
            throw Store.error(400, "无效的 daemon 审批请求");
        var now = Instant.now();
        db.update("""
                INSERT INTO daemon_approvals(tenant_owner,requester,request_id,agent,tool,request_args,thread_id,created_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id
                """, tenant, requester, request.id(), request.agent(), request.tool(), request.arguments(),
                request.threadId(), now.toString(), now.plus(TTL).getEpochSecond());
        var rows = db.queryForList("""
                SELECT request_id,agent,tool,request_args,thread_id FROM daemon_approvals WHERE tenant_owner=? AND requester=? AND request_id=?
                """, tenant, requester, request.id());
        if (rows.isEmpty()) throw Store.error(409, "审批编号已被使用");
        var row = rows.getFirst();
        if (!Objects.equals(row.get("request_id"), request.id()) || !Objects.equals(row.get("agent"), request.agent()) || !Objects.equals(row.get("tool"), request.tool())
                || !Objects.equals(row.get("request_args"), request.arguments()) || !Objects.equals(row.get("thread_id"), request.threadId()))
            throw Store.error(409, "审批编号已绑定不同的请求");
    }

    List<Map<String, Object>> pending(String tenant) {
        return db.queryForList("""
                SELECT request_id,agent,tool,request_args AS args,thread_id,created_at FROM daemon_approvals
                WHERE tenant_owner=? AND decision IS NULL AND expires_at>? ORDER BY created_at,request_id LIMIT 200
                """, tenant, Instant.now().getEpochSecond());
    }

    void decide(String tenant, String id, String decision) {
        if (!Set.of("allow", "deny").contains(decision))
            throw Store.error(400, "daemon 审批决定必须为 allow 或 deny");
        int changed = db.update("""
                UPDATE daemon_approvals SET decision=?,resolved_by='gateway'
                WHERE tenant_owner=? AND request_id=? AND decision IS NULL AND expires_at>?
                """, decision, tenant, id, Instant.now().getEpochSecond());
        if (changed == 0) throw Store.error(404, "审批请求不存在、已过期或已处理");
    }

    void closeLocally(String tenant, String requester, String id) {
        db.update("""
                UPDATE daemon_approvals SET decision='closed',resolved_by='daemon'
                WHERE tenant_owner=? AND requester=? AND request_id=? AND decision IS NULL
                """, tenant, requester, id);
    }

    List<Map<String, Object>> ready(String tenant, String requester, Set<String> subscriptions) {
        if (subscriptions.isEmpty()) return List.of();
        long now = Instant.now().getEpochSecond();
        return named.queryForList("""
                SELECT request_id,CASE WHEN decision IS NULL AND expires_at<=:now THEN 'deny' ELSE decision END AS decision,
                       CASE WHEN decision IS NULL THEN 'expired' ELSE resolved_by END AS resolved_by
                FROM daemon_approvals WHERE tenant_owner=:tenant AND requester=:requester AND request_id IN (:ids)
                  AND (decision IS NOT NULL OR expires_at<=:now) ORDER BY created_at,request_id
                """, Map.of("tenant", tenant, "requester", requester, "ids", subscriptions, "now", now));
    }
}
