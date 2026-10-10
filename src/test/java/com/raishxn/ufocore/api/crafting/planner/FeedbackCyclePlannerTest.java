package com.raishxn.ufocore.api.crafting.planner;

import static org.junit.jupiter.api.Assertions.*;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeedbackCyclePlannerTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 3, 4, 8, 64})
    void depletedWorkingStockIsConsumedRatherThanRegisteredAsZeroCatalyst(int requested) {
        var first = new CraftingPattern<>("b-from-a", 0,
                Map.of("A", UfoAmount.of(2)), Map.of("B", UfoAmount.of(1)), Set.of("B"));
        var second = new CraftingPattern<>("d-from-b", 0,
                Map.of("B", UfoAmount.of(1)),
                Map.of("A", UfoAmount.of(1), "D", UfoAmount.of(1)), Set.of("D"));
        var graph = ImmutableCraftingGraph.create(1, Comparator.<String>naturalOrder(),
                List.of(first, second));
        // Starting with B makes the chosen turn return A before drawing it. Its A depth
        // equals its decay, so there is no reusable A remainder.
        var stock = Map.of("B", UfoAmount.of(1), "A", UfoAmount.of(requested));
        var loops = FeedbackCyclePlanner.analyse(graph, "D", UfoAmount.of(requested), stock);
        assertTrue(loops.applies());
        var result = new IterativeCraftingPlanner<String>().plan(loops.augmentedGraph(),
                new PlanningRequest<>("D", UfoAmount.of(requested), stock));
        assertEquals(PlanningResult.Status.COMPLETE, result.status());
        var expanded = loops.expand(result.plan());
        var remaining = new LinkedHashMap<>(stock);
        for (var step : expanded.schedule()) {
            step.pattern().inputs().forEach((key, amount) -> {
                var needed = amount.multiply(step.runs().asBigInteger());
                assertTrue(remaining.getOrDefault(key, UfoAmount.ZERO).compareTo(needed) >= 0);
                remaining.put(key, remaining.get(key).subtract(needed));
            });
            step.pattern().outputs().forEach((key, amount) -> remaining.merge(key,
                    amount.multiply(step.runs().asBigInteger()), UfoAmount::add));
        }
        assertEquals(UfoAmount.of(requested), remaining.get("D"));
        assertEquals(UfoAmount.of(1), remaining.get("B"));
        assertEquals(UfoAmount.ZERO, remaining.get("A"));
    }
}
