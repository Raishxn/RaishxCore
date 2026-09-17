package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The pool is the only place where several grids compete for one tick, so its maths must be exact. */
class CaptureBudgetPoolTest {
    private static final long MILLIS = 1_000_000L;

    @Test
    void oneTickAllowanceIsSharedAcrossReservations() {
        var pool = new CaptureBudgetPool(Duration.ofMillis(4));

        assertEquals(3 * MILLIS, pool.reserve(3 * MILLIS));
        assertEquals(MILLIS, pool.remainingNanos());
        assertEquals(MILLIS, pool.reserve(3 * MILLIS), "a reservation is capped by what is left");
        assertTrue(pool.exhausted());
        assertEquals(0L, pool.reserve(MILLIS));
    }

    @Test
    void settleRefundsThePartOfAReservationTheSliceDidNotUse() {
        var pool = new CaptureBudgetPool(Duration.ofMillis(4));

        long reservation = pool.reserve(2 * MILLIS);
        pool.settle(reservation, MILLIS);

        assertEquals(3 * MILLIS, pool.remainingNanos(), "unused slice time must return to the tick");
        assertFalse(pool.exhausted());
    }

    @Test
    void beginTickRestoresTheWholeAllowance() {
        var pool = new CaptureBudgetPool(Duration.ofMillis(2));
        pool.reserve(2 * MILLIS);
        assertTrue(pool.exhausted());

        pool.beginTick();

        assertEquals(2 * MILLIS, pool.remainingNanos());
        assertEquals(2 * MILLIS, pool.reserve(10 * MILLIS), "one grid may still use a whole tick");
    }

    @Test
    void exhaustingThePoolIsCountedForDiagnostics() {
        var pool = new CaptureBudgetPool(Duration.ofMillis(1));
        pool.beginTick();
        pool.reserve(MILLIS);
        pool.reserve(MILLIS);

        var stats = pool.stats();

        assertEquals(1L, stats.ticks());
        assertEquals(1L, stats.reservations());
        assertEquals(MILLIS, stats.grantedNanos());
        assertEquals(0L, stats.spentNanos(), "no slice has settled yet");
        assertEquals(1L, stats.exhaustedTicks());
    }

    @Test
    void aSliceThatOverrunsIsChargedInFull() {
        var pool = new CaptureBudgetPool(Duration.ofMillis(2));

        long reservation = pool.reserve(MILLIS);
        pool.settle(reservation, 3 * MILLIS);

        assertEquals(-MILLIS, pool.remainingNanos(), "a slice that overran must spend the next tick's share");
        assertTrue(pool.exhausted());
        assertEquals(0L, pool.reserve(MILLIS), "no later grid may spend a budget that is already gone");
        assertEquals(3 * MILLIS, pool.stats().spentNanos());
    }

    @Test
    void invalidArgumentsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CaptureBudgetPool(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new CaptureBudgetPool(Duration.ofMillis(-1)));
        var pool = new CaptureBudgetPool(Duration.ofMillis(1));
        assertThrows(IllegalArgumentException.class, () -> pool.reserve(-1L));
        assertThrows(IllegalArgumentException.class, () -> pool.settle(-1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> pool.settle(1L, -1L));
    }
}
