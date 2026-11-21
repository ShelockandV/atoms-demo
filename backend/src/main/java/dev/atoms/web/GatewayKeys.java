package dev.atoms.web;

import dev.atoms.agent.persistence.Store;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
final class GatewayKeys {
    record Credential(String id, String tenant) {}

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private static final SecureRandom RANDOM = new SecureRandom();

    GatewayKeys(JdbcTemplate db, TransactionTemplate tx) {
        this.db = db;
        this.tx = tx;
    }

    Credential authenticate(String token) {
        if (!token.matches("atoms_[A-Za-z0-9_-]{43}")) return null;
        var found = db.query("SELECT id,tenant FROM gateway_api_keys WHERE key_hash=? AND revoked_at IS NULL",
                (row, ignored) -> new Credential(row.getString("id"), row.getString("tenant")), hash(token));
        if (found.isEmpty()) return null;
        var key = found.getFirst();
        int updated = db.update("UPDATE gateway_api_keys SET last_used_at=? WHERE id=? AND revoked_at IS NULL",
                Instant.now().toString(), key.id());
        return updated == 1 ? key : null;
    }

    Map<String, Object> list(String tenant) {
        var keys = db.queryForList("""
                SELECT id,name,description,key_prefix,created_at,last_used_at,revoked_at
                FROM gateway_api_keys WHERE tenant_scope=? ORDER BY created_at DESC,id
                """, hash(tenant));
        var entries = keys.stream().map(row -> {
            var data = new LinkedHashMap<>(row);
            data.put("is_active", data.remove("revoked_at") == null);
            return data;
        }).toList();
        return Map.of("keys", entries, "total", entries.size());
    }

    Map<String, Object> create(String tenant, String name, String description) {
        if (name.isBlank() || name.length() > 100 || description.length() > 1000)
            throw Store.error(400, "API Key 名称须为 1 至 100 字符，描述最多 1000 字符");
        String key = randomToken("atoms_");
        String id = Store.id();
        String time = Instant.now().toString();
        String prefix = key.substring(0, 12);
        db.update("""
                INSERT INTO gateway_api_keys(id,tenant,tenant_scope,key_hash,key_prefix,name,description,created_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, id, tenant, hash(tenant), hash(key), prefix, name, description, time);
        return Map.of("id", id, "name", name, "api_key", key, "key_prefix", prefix, "created_at", time);
    }

    void revoke(String tenant, String id) {
        int changed = db.update("UPDATE gateway_api_keys SET revoked_at=? WHERE tenant_scope=? AND id=? AND revoked_at IS NULL",
                Instant.now().toString(), hash(tenant), id);
        if (changed == 0) throw Store.error(404, "API Key 不存在或已撤销");
    }

    Map<String, Object> rotate(Credential key) {
        return tx.execute(status -> {
            var rows = db.queryForList("SELECT name,description FROM gateway_api_keys WHERE tenant_scope=? AND id=? FOR UPDATE",
                    hash(key.tenant()), key.id());
            if (rows.isEmpty()) throw Store.error(404, "API Key 不存在");
            revoke(key.tenant(), key.id());
            var previous = rows.getFirst();
            var created = new LinkedHashMap<>(create(key.tenant(), (String) previous.get("name"), (String) previous.get("description")));
            created.put("previous_key_revoked", true);
            return created;
        });
    }

    static String randomToken(String prefix) {
        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    static String hash(String key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
