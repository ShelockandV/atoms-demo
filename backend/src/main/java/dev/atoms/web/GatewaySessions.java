package dev.atoms.web;

import dev.atoms.agent.persistence.Store;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
final class GatewaySessions {
    private static final int ACCESS_SECONDS = 900;
    private static final long REFRESH_SECONDS = 30 * 86400L;
    private final GoogleGatewayIdentity google;
    private final GatewayJwt jwt;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Map<String, String> members;
    private final Map<String, String> gmailMembers;

    GatewaySessions(GoogleGatewayIdentity google, GatewayJwt jwt, JdbcTemplate db,
                    TransactionTemplate tx, Environment environment) {
        this.google = google;
        this.jwt = jwt;
        this.db = db;
        this.tx = tx;
        var binder = Binder.get(environment);
        members = Map.copyOf(binder.bind("atoms.gateway.google.members", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of()));
        gmailMembers = Map.copyOf(binder.bind("atoms.gateway.google.gmail-members", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of()));
        var tenants = binder.bind("atoms.gateway.tenant-tokens", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        if (enabled() && (!jwt.enabled() || !google.enabled() || members.entrySet().stream().anyMatch(entry ->
                !entry.getKey().matches("[A-Za-z0-9_-]{1,128}") || !tenants.containsKey(entry.getValue()))
                || gmailMembers.entrySet().stream().anyMatch(entry ->
                !entry.getKey().matches("[a-z0-9.]+@gmail\\.com") || !tenants.containsKey(entry.getValue()))))
            throw new IllegalArgumentException("Google 登录须配置客户端 ID、JWT 签名密钥和有效的租户成员映射");
    }

    boolean enabled() { return !members.isEmpty() || !gmailMembers.isEmpty(); }

    String googleClientId() { return enabled() ? google.clientId() : ""; }

    boolean allows(String subject, String tenant) {
        if (tenant.equals(members.get(subject))) return true;
        if (gmailMembers.isEmpty()) return false;
        return db.queryForList("""
                SELECT email_hash FROM gateway_google_email_bindings WHERE subject_hash=?
                """, String.class, GatewayKeys.hash(subject)).stream().anyMatch(emailHash ->
                gmailMembers.entrySet().stream().anyMatch(entry ->
                        tenant.equals(entry.getValue()) && emailHash.equals(GatewayKeys.hash(entry.getKey()))));
    }

    private void bindGmail(String email, String subject) {
        String emailHash = GatewayKeys.hash(email);
        String subjectHash = GatewayKeys.hash(subject);
        try {
            db.update("INSERT INTO gateway_google_email_bindings(email_hash,subject_hash) VALUES(?,?)",
                    emailHash, subjectHash);
        } catch (DuplicateKeyException existing) {
            if (!db.queryForList("""
                    SELECT subject_hash FROM gateway_google_email_bindings WHERE email_hash=? FOR UPDATE
                    """, String.class, emailHash).contains(subjectHash)) throw invalid();
        }
    }

    Map<String, Object> register(String provider, String token) {
        requireEnabled();
        if (!"google".equals(provider)) throw Store.error(400, "登录仅支持 Google ID token");
        var identity = google.verify(token);
        if (identity == null) throw invalid();
        String subject = identity.subject();
        String email = identity.verifiedGmail();
        String explicitTenant = members.get(subject);
        String tenant = explicitTenant != null ? explicitTenant : email == null ? null : gmailMembers.get(email);
        if (tenant == null) throw invalid();
        return tx.execute(status -> {
            if (explicitTenant == null) bindGmail(email, subject);
            boolean newUser;
            try {
                db.update("INSERT INTO gateway_google_accounts(subject_hash,tenant_hash) VALUES(?,?)",
                        GatewayKeys.hash(subject), GatewayKeys.hash(tenant));
                newUser = true;
            } catch (DuplicateKeyException existing) {
                newUser = false;
            }
            var result = new LinkedHashMap<>(issue(subject, tenant));
            result.put("user_id", subject);
            result.put("tenant_id", tenant);
            result.put("is_new_user", newUser);
            return result;
        });
    }

    Map<String, Object> refresh(String token) {
        requireEnabled();
        if (token == null || !token.matches("atoms_refresh_[A-Za-z0-9_-]{43}")) throw invalid();
        return tx.execute(status -> {
            String hash = GatewayKeys.hash(token);
            var rows = db.queryForList("""
                    SELECT subject,tenant,expires_epoch FROM gateway_refresh_tokens WHERE token_hash=? FOR UPDATE
                    """, hash);
            if (rows.isEmpty()) throw invalid();
            var session = rows.getFirst();
            String subject = (String) session.get("subject");
            String tenant = (String) session.get("tenant");
            if (((Number) session.get("expires_epoch")).longValue() <= Instant.now().getEpochSecond()
                    || !allows(subject, tenant)) throw invalid();
            db.update("DELETE FROM gateway_refresh_tokens WHERE token_hash=?", hash);
            return issue(subject, tenant);
        });
    }

    private Map<String, Object> issue(String subject, String tenant) {
        Instant now = Instant.now();
        String refresh = GatewayKeys.randomToken("atoms_refresh_");
        db.update("INSERT INTO gateway_refresh_tokens(token_hash,subject,tenant,expires_epoch) VALUES(?,?,?,?)",
                GatewayKeys.hash(refresh), subject, tenant, now.getEpochSecond() + REFRESH_SECONDS);
        return Map.of("access_token", jwt.issueGoogleSession(tenant, subject, now.plusSeconds(ACCESS_SECONDS)),
                "refresh_token", refresh, "expires_in", ACCESS_SECONDS);
    }

    private void requireEnabled() {
        if (!enabled()) throw Store.error(404, "Google 登录未配置");
    }

    private static org.springframework.web.server.ResponseStatusException invalid() {
        return Store.error(401, "登录或刷新凭据无效");
    }
}
