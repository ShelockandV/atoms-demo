package dev.atoms.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Service
final class GatewayRateLimits {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final int minuteLimit;
    private final int hourLimit;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    GatewayRateLimits(JdbcTemplate db, TransactionTemplate tx,
                      @Value("${atoms.gateway.rate-limit.requests-per-minute:0}") int minuteLimit,
                      @Value("${atoms.gateway.rate-limit.requests-per-hour:0}") int hourLimit) {
        this(db, tx, minuteLimit, hourLimit, Clock.systemUTC());
    }

    GatewayRateLimits(JdbcTemplate db, TransactionTemplate tx, int minuteLimit, int hourLimit, Clock clock) {
        if (minuteLimit < 0 || hourLimit < 0) throw new IllegalArgumentException("Gateway 限流配置不能为负数");
        this.db = db;
        this.tx = tx;
        this.minuteLimit = minuteLimit;
        this.hourLimit = hourLimit;
        this.clock = clock;
    }

    /** Returns zero when granted, or the number of seconds until both windows permit a request. */
    long retryAfter(String owner) {
        if (minuteLimit == 0 && hourLimit == 0) return 0;
        return tx.execute(status -> {
            db.update("""
                    INSERT INTO gateway_rate_windows(tenant_owner,minute_start,minute_used,hour_start,hour_used)
                    VALUES(?,0,0,0,0) ON DUPLICATE KEY UPDATE tenant_owner=tenant_owner
                    """, owner);
            var row = db.queryForMap("""
                    SELECT minute_start,minute_used,hour_start,hour_used
                    FROM gateway_rate_windows WHERE tenant_owner=? FOR UPDATE
                    """, owner);
            long now = clock.instant().getEpochSecond();
            var minute = window(row, "minute", now, 60);
            var hour = window(row, "hour", now, 3600);
            long retry = Math.max(minute.retryAfter(minuteLimit, now, 60), hour.retryAfter(hourLimit, now, 3600));
            if (retry == 0) {
                db.update("""
                        UPDATE gateway_rate_windows SET minute_start=?,minute_used=?,hour_start=?,hour_used=?
                        WHERE tenant_owner=?
                        """, minute.start(), minute.used() + 1, hour.start(), hour.used() + 1, owner);
            }
            return retry;
        });
    }

    private static Window window(java.util.Map<String, Object> row, String name, long now, long period) {
        long previous = ((Number) row.get(name + "_start")).longValue();
        long start = Math.max(previous, Math.floorDiv(now, period) * period);
        long used = previous == start ? ((Number) row.get(name + "_used")).longValue() : 0;
        return new Window(start, used);
    }

    private record Window(long start, long used) {
        long retryAfter(int limit, long now, long period) {
            return limit > 0 && used >= limit ? Math.max(1, start + period - now) : 0;
        }
    }
}
