package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PlannerCircuitBreakerTest {
    private final AtomicLong clock = new AtomicLong(1_000);
    private final PlannerCircuitBreaker breaker = new PlannerCircuitBreaker(clock::get);
    private final Duration cooldown = Duration.ofNanos(100);

    @Test
    void consecutiveFailuresOpenCircuitAndSuccessResetsThem() {
        assertTrue(breaker.tryAcquire());
        breaker.recordFailure(3, cooldown);
        breaker.recordSuccess();
        breaker.recordFailure(3, cooldown);
        breaker.recordFailure(3, cooldown);
        assertEquals(new PlannerCircuitBreaker.Snapshot(PlannerCircuitBreaker.State.CLOSED, 2),
                breaker.snapshot());

        breaker.recordFailure(3, cooldown);
        assertFalse(breaker.tryAcquire());
        assertEquals(PlannerCircuitBreaker.State.OPEN, breaker.snapshot().state());
    }

    @Test
    void cooldownAllowsOnlyOneProbeUntilItsOutcomeIsKnown() {
        breaker.recordFailure(1, cooldown);
        assertFalse(breaker.tryAcquire());
        clock.addAndGet(100);
        assertTrue(breaker.tryAcquire());
        assertFalse(breaker.tryAcquire());

        breaker.recordSuccess();
        assertTrue(breaker.tryAcquire());
        assertEquals(new PlannerCircuitBreaker.Snapshot(PlannerCircuitBreaker.State.CLOSED, 0),
                breaker.snapshot());
    }

    @Test
    void abortedProbeStartsANewCooldownWithoutInventingFailure() {
        breaker.recordFailure(1, cooldown);
        clock.addAndGet(100);
        assertTrue(breaker.tryAcquire());
        breaker.abortProbe(cooldown);

        assertFalse(breaker.tryAcquire());
        assertEquals(1, breaker.snapshot().consecutiveFailures());
        clock.addAndGet(100);
        assertTrue(breaker.tryAcquire());
    }

    @Test
    void resetClearsOpenCircuitForChangedGridRevision() {
        breaker.recordFailure(1, cooldown);
        breaker.reset();
        assertTrue(breaker.tryAcquire());
        assertEquals(new PlannerCircuitBreaker.Snapshot(PlannerCircuitBreaker.State.CLOSED, 0),
                breaker.snapshot());
    }
}
