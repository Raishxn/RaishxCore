package com.raishxn.ufocore.neoforge.crafting;

import appeng.api.networking.crafting.ICraftingPlan;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanningOriginTest {
    @Test
    void ordinaryFuturesHaveNoRaishxPlannerBadge() {
        assertEquals(PlanningOrigin.NONE, PlanningOrigin.of(new CompletableFuture<ICraftingPlan>()));
    }

    @Test
    void directCoreFutureKeepsItsOwnOrigin() {
        var future = new OriginTrackingFuture(new CompletableFuture<ICraftingPlan>(), PlanningOrigin.RAISHX);
        assertEquals(PlanningOrigin.RAISHX, PlanningOrigin.of(future));
    }

    @Test
    void deferredFutureReportsTheEngineThatActuallyReceivedTheRequest() {
        var core = new DeferredPlanFuture();
        core.complete(new CompletableFuture<ICraftingPlan>(), PlanningOrigin.RAISHX);
        assertEquals(PlanningOrigin.RAISHX, PlanningOrigin.of(core));

        var fallback = new DeferredPlanFuture();
        fallback.complete(new CompletableFuture<ICraftingPlan>(), PlanningOrigin.AE2);
        assertEquals(PlanningOrigin.AE2, PlanningOrigin.of(fallback));
    }
}
