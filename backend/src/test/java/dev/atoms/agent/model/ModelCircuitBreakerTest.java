package dev.atoms.agent.model;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** 验证模型分别熔断，并在冷却后仅允许一个探测请求。 */
class ModelCircuitBreakerTest {
    @Test void opensAfterFailuresAndIgnoresResultsFromBeforeOpening() {
        var clock = new AtomicLong();
        var breaker = new ModelCircuitBreaker(2, 30, clock::get);
        var late = breaker.acquire("primary");
        breaker.complete(breaker.acquire("primary"), false);
        breaker.complete(breaker.acquire("primary"), false);
        breaker.complete(late, true);
        assertThrows(ModelCircuitBreaker.Open.class, () -> breaker.acquire("primary"));
        assertNotNull(breaker.acquire("fallback"));
        clock.addAndGet(30);
        var probe = breaker.acquire("primary");
        assertTrue(probe.probe());
        assertThrows(ModelCircuitBreaker.Open.class, () -> breaker.acquire("primary"));
        breaker.complete(probe, false);
        assertThrows(ModelCircuitBreaker.Open.class, () -> breaker.acquire("primary"));
        clock.addAndGet(30);
        breaker.complete(breaker.acquire("primary"), true);
        assertFalse(breaker.acquire("primary").probe());
    }
}
