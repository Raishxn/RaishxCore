package com.raishxn.ufocore.api.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The consumer registration point and the operator policy that scales it.
 *
 * <p>These are the two halves {@code Ae2PlannerBridge} joins before it builds a
 * {@link PlanningRequest}: what a consumer declared, and what the pack configured. The tests also pin
 * the promise that an unregistered, unconfigured installation keeps the unweighted path exactly.
 */
class MissingWeightsTest {

    @BeforeEach
    @AfterEach
    void clearRegistry() {
        MissingWeights.clear();
    }

    @Test void registrationStoresWeightsAndDropsOne() {
        MissingWeights.register("gold", 100L);
        assertEquals(Map.of("gold", 100L), MissingWeights.registered());

        // One is the default, so it is removed from the snapshot instead of stored.
        MissingWeights.register("gold", 1L);
        assertEquals(Map.of(), MissingWeights.registered());
    }

    @Test void unregisterAndClearRestoreTheDefault() {
        MissingWeights.register("gold", 100L);
        MissingWeights.register("gem", 5L);
        MissingWeights.unregister("gold");
        assertEquals(Map.of("gem", 5L), MissingWeights.registered());

        MissingWeights.clear();
        assertEquals(Map.of(), MissingWeights.registered());
    }

    @Test void rejectsInvalidDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> MissingWeights.register("", 2L));
        assertThrows(IllegalArgumentException.class, () -> MissingWeights.register("gold", 0L));
        assertThrows(IllegalArgumentException.class, () -> MissingWeights.register("gold", -3L));
        assertThrows(NullPointerException.class, () -> MissingWeights.register(null, 2L));
    }

    @Test void policyScalesEveryRegisteredWeight() {
        MissingWeights.register("gold", 100L);
        Map<String, Long> effective = new MissingWeightPolicy(3L, Map.of()).effective();
        assertEquals(Map.of("gold", 300L), effective);
    }

    @Test void policyOverridesOneKeyAndScalesTheRest() {
        MissingWeights.register("gold", 100L);
        MissingWeights.register("gem", 2L);
        Map<String, Long> effective = new MissingWeightPolicy(3L, Map.of("gem", 5L)).effective();
        assertEquals(Map.of("gold", 300L, "gem", 10L), effective);
    }

    @Test void overrideWeightsAKeyNobodyRegistered() {
        Map<String, Long> effective = new MissingWeightPolicy(2L, Map.of("gem", 7L)).effective();
        assertEquals(Map.of("gem", 7L), effective);
    }

    @Test void aScalingThatEndsAtOneIsDropped() {
        MissingWeights.register("gold", 1L);
        assertEquals(Map.of(), new MissingWeightPolicy(4L, Map.of()).effective());
    }

    @Test void unconfiguredPolicyReturnsTheRegisteredMapUnchanged() {
        MissingWeights.register("gold", 100L);
        assertSame(MissingWeights.registered(), MissingWeightPolicy.NONE.effective());
    }

    @Test void parsesAndValidatesConfigOverrides() {
        assertEquals(Map.of("gold", 5L, "gem", 2L),
                MissingWeightPolicy.parseOverrides(List.of("gold=5", " gem = 2 ")));
        assertEquals(Map.of(), MissingWeightPolicy.parseOverrides(List.of()));

        assertTrue(MissingWeightPolicy.isValidOverride("gold=5"));
        assertFalse(MissingWeightPolicy.isValidOverride("gold"));
        assertFalse(MissingWeightPolicy.isValidOverride("gold=nope"));
        assertFalse(MissingWeightPolicy.isValidOverride("gold=0"));
        assertFalse(MissingWeightPolicy.isValidOverride("=5"));
        assertFalse(MissingWeightPolicy.isValidOverride(7));
        assertThrows(IllegalArgumentException.class, () -> MissingWeightPolicy.parseOverrides(List.of("gold")));
    }

    /**
     * The registration point has to reach the public planner API, not just a getter: with the valuable
     * route declared first, the unweighted plan leaves gold short, and the declared weight is what makes
     * the cheap route win.
     */
    @Test void registeredWeightChangesTheRouteChosenThroughThePublicApi() {
        var gold = pattern("a-gold", Map.of("gold", UfoAmount.ONE), Map.of("widget", UfoAmount.ONE));
        var cheap = pattern("b-cheap", Map.of("cheap", UfoAmount.ONE), Map.of("widget", UfoAmount.ONE));
        var graph = graph(List.of(gold, cheap));
        var base = new PlanningRequest<String>("widget", UfoAmount.ONE, Map.of());

        var unweighted = new IterativeCraftingPlanner<String>().plan(graph, base);
        assertEquals(PlanningResult.Status.MISSING_INGREDIENTS, unweighted.status());
        assertTrue(unweighted.plan().missing().containsKey("gold"),
                () -> "the identifier decides when nothing is weighted: " + unweighted.plan().missing());

        MissingWeights.register("gold", 100L);
        var weighted = new IterativeCraftingPlanner<String>().plan(graph,
                new PlanningRequest<>("widget", UfoAmount.ONE, Map.of(), base.limits(),
                        PlanningCancellation.NEVER, MissingWeightPolicy.NONE.effective()));

        assertEquals(PlanningResult.Status.MISSING_INGREDIENTS, weighted.status());
        assertEquals(UfoAmount.ONE, weighted.plan().missing().get("cheap"),
                () -> "the registered weight must make the cheap route win: " + weighted.plan().missing());
        assertFalse(weighted.plan().missing().containsKey("gold"));
    }

    private static CraftingPattern<String> pattern(String id, Map<String, UfoAmount> inputs,
                                                    Map<String, UfoAmount> outputs) {
        return new CraftingPattern<>(id, inputs, outputs);
    }

    private static ImmutableCraftingGraph<String> graph(List<CraftingPattern<String>> patterns) {
        return ImmutableCraftingGraph.create(1L, Comparator.naturalOrder(), patterns);
    }
}
