package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static Ae2PlanningSnapshot.CaptureBudget budget(int edges, int keys, long bytes) {
        return new Ae2PlanningSnapshot.CaptureBudget(
                new Ae2PlanningSnapshot.CaptureLimits(Duration.ofMinutes(1), edges, keys, bytes));
    }
}
