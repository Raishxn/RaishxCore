package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raishxn.ufocore.api.amount.UfoAmount;
import com.raishxn.ufocore.api.crafting.planner.CraftingPattern;
import com.raishxn.ufocore.api.crafting.planner.ImmutableCraftingGraph;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Ae2PlannerBridgeAmountTest {
    @Test
    void exactMissingAmountRemainsExactInsideAe2Range() {
        assertEquals(5_700_000_000_000L,
                Ae2PlannerBridge.toAe2MissingDisplayAmount(UfoAmount.of(5_700_000_000_000L)));
    }

    @Test
    void oversizedMissingAmountIsCappedOnlyAtTheAe2DisplayBoundary() {
        UfoAmount amount = UfoAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        assertEquals(Long.MAX_VALUE, Ae2PlannerBridge.toAe2MissingDisplayAmount(amount));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), amount.asBigInteger());
    }

    @Test
    void simulatedPatternCountIsCappedOnlyAtTheAe2DisplayBoundary() {
        UfoAmount amount = UfoAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        assertEquals(Long.MAX_VALUE, Ae2PlannerBridge.toAe2PatternDisplayAmount(amount, false));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), amount.asBigInteger());
    }

    @Test
    void executablePatternCountMustRemainExactlyRepresentableByAe2() {
        UfoAmount amount = UfoAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        assertThrows(ArithmeticException.class,
                () -> Ae2PlannerBridge.toAe2PatternDisplayAmount(amount, true));
    }

    @Test
    void cosmicStringChoiceWidthUsesTheFastPlannerAtTheDefaultBoundary() {
        var routes = new ArrayList<CraftingPattern<String>>();
        for (int route = 0; route < 12; route++) {
            routes.add(new CraftingPattern<>("route-" + route,
                    Map.of("raw-" + route, UfoAmount.ONE), Map.of("target", UfoAmount.ONE)));
        }
        var graph = ImmutableCraftingGraph.create(1, Comparator.naturalOrder(), routes);

        assertEquals(11, graph.routeChoiceAlternatives(),
                "matches the producer-choice width observed for the real Cosmic String graph");
        assertTrue(Ae2PlannerBridge.usesFastPlanner(graph, 8),
                "the real graph must not enter the exponential exact proof search");
    }
}
