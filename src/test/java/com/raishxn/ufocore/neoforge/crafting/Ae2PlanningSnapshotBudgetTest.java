package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class Ae2PlanningSnapshotBudgetTest {
    @Test
    void edgeLimitStopsCaptureAtTheConfiguredBoundary() {
        var budget = budget(1, 10, 10_000);
        budget.edge();

        var declined = assertThrows(Ae2PlanningSnapshot.Declined.class, budget::edge);

        assertEquals("snapshot edge limit", declined.getMessage());
    }

    @Test
    void keyLimitCountsOnlyNewSerializedKeys() {
        var budget = budget(10, 1, 10_000);
        budget.key("first");

        var declined = assertThrows(Ae2PlanningSnapshot.Declined.class, () -> budget.key("second"));

        assertEquals("snapshot key limit", declined.getMessage());
    }

    @Test
    void estimatedMemoryLimitCoversKeysPatternsAndEdges() {
        var budget = budget(10, 10, 400);
        budget.edge();
        budget.pattern("pattern");

        var declined = assertThrows(Ae2PlanningSnapshot.Declined.class, () -> budget.key("key"));

        assertEquals("snapshot memory limit", declined.getMessage());
    }

    @Test
    void captureLimitsRejectInvalidBudgets() {
        assertThrows(IllegalArgumentException.class,
                () -> new Ae2PlanningSnapshot.CaptureLimits(Duration.ZERO, 1, 1, 1));
    }

    @Test
    void aFreshSliceAlwaysGetsToStartItsFirstKey() {
        var budget = budget(10, 10, 10_000);
        budget.beginSlice(60_000_000L, 2);

        assertFalse(budget.sliceExhausted(), "a slice that has not worked yet must not report exhausted");
        budget.edge();
        assertFalse(budget.sliceExhausted());
        budget.edge();

        assertTrue(budget.sliceExhausted(), "the edge allowance must end the slice");
    }

    @Test
    void exhaustedSliceDoesNotNeedTheWallClockToNotice() {
        var budget = budget(10, 10, 10_000);
        budget.beginSlice(Long.MAX_VALUE, 1);
        budget.edge();

        assertTrue(budget.sliceExhausted(), "the deterministic edge allowance must end the slice");
    }

    @Test
    void totalLimitsStillApplyInsideASlice() {
        var budget = budget(1, 10, 10_000);
        budget.beginSlice(60_000_000L, 100);
        budget.edge();

        assertThrows(Ae2PlanningSnapshot.Declined.class, budget::edge);
    }

    @Test
    void estimatedBytesAccumulateForTheCacheCeiling() {
        var budget = budget(10, 10, 10_000);
        assertEquals(0L, budget.estimatedBytes());

        budget.key("key");
        budget.pattern("pattern");

        assertTrue(budget.estimatedBytes() > 256L,
                "keys and patterns must weigh in, not only edges: " + budget.estimatedBytes());
    }

    @Test
    void captureLimitsRejectInvalidSliceBudgets() {
        assertThrows(IllegalArgumentException.class,
                () -> new Ae2PlanningSnapshot.CaptureLimits(Duration.ofMinutes(1), 1, 1, 1, 0L, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Ae2PlanningSnapshot.CaptureLimits(Duration.ofMinutes(1), 1, 1, 1, 1L, 0));
    }

    private static Ae2PlanningSnapshot.CaptureBudget budget(int edges, int keys, long bytes) {
        return new Ae2PlanningSnapshot.CaptureBudget(
                new Ae2PlanningSnapshot.CaptureLimits(Duration.ofMinutes(1), edges, keys, bytes));
    }
}
