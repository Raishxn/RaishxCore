package com.raishxn.ufocore.api.crafting.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IterativeCraftingPlannerTest {
    @Test void createsAnExactPlanWithBatchingAndByproducts() {
        var plate = pattern("plate", Map.of("ingot", amount(3)), Map.of("plate", amount(2), "dust", amount(1)));
        var graph = graph(7, List.of(plate));

        var result = new IterativeCraftingPlanner<String>().plan(graph,
                new PlanningRequest<>("plate", amount(5), Map.of("ingot", amount(9))));

        assertEquals(PlanningResult.Status.COMPLETE, result.status());
        assertEquals(amount(3), result.plan().patternExecutions().get(plate));
        assertEquals(amount(9), result.plan().extractedFromInventory().get("ingot"));
        assertEquals(amount(1), result.plan().remaining().get("plate"));
        assertEquals(amount(3), result.plan().remaining().get("dust"));
        assertEquals(amount(4), result.plan().quality().overproducedUnits());
        assertEquals(7, result.diagnostics().graphRevision());
    }

    @Test void selectionIsStableAcrossInsertionOrdersAndPrefersCraftableRoutes() {
        var cyclic = pattern("a-cycle", Map.of("loop", amount(1)), Map.of("target", amount(1)));
        var reachable = pattern("z-reachable", Map.of("raw", amount(2)), Map.of("target", amount(1)));
        var closeLoop = pattern("loop", Map.of("target", amount(1)), Map.of("loop", amount(1)));
        var planner = new IterativeCraftingPlanner<String>();

        for (List<CraftingPattern<String>> order : List.of(
                List.of(cyclic, reachable, closeLoop), List.of(closeLoop, reachable, cyclic))) {
            var result = planner.plan(graph(1, order),
                    new PlanningRequest<>("target", amount(1), Map.of("raw", amount(2))));
            assertEquals(PlanningResult.Status.COMPLETE, result.status());
            assertEquals(Map.of(reachable, amount(1)), result.plan().patternExecutions());
        }
    }

    @Test void reportsMissingBaseIngredientsWithoutRecursing() {
        var graph = graph(2, List.of(
                pattern("wire", Map.of("copper", amount(1)), Map.of("wire", amount(4))),
                pattern("motor", Map.of("wire", amount(6)), Map.of("motor", amount(1)))));
        var result = new IterativeCraftingPlanner<String>().plan(graph,
                new PlanningRequest<>("motor", amount(2), Map.of()));

        assertEquals(PlanningResult.Status.MISSING_INGREDIENTS, result.status());
        assertEquals(amount(5), result.plan().patternExecutions().values().stream()
                .reduce(UfoAmount.ZERO, UfoAmount::add));
        assertEquals(amount(3), result.plan().missing().get("copper"));
    }

    @Test void handlesADeepGraphWithAnExplicitStack() {
        int depth = 20_000;
        ArrayList<CraftingPattern<String>> patterns = new ArrayList<>(depth);
        for (int i = 1; i <= depth; i++) {
            patterns.add(pattern("p" + i, Map.of("k" + (i - 1), amount(1)), Map.of("k" + i, amount(1))));
        }
        var limits = new PlanningLimits(2_000_000, depth + 1, Duration.ofSeconds(5), 128);
        var result = new IterativeCraftingPlanner<String>().plan(graph(3, patterns),
                new PlanningRequest<>("k" + depth, amount(1), Map.of("k0", amount(1)), limits,
                        PlanningCancellation.NEVER));

        assertEquals(PlanningResult.Status.COMPLETE, result.status());
        assertEquals(depth, result.plan().patternExecutions().size());
        assertTrue(result.diagnostics().maximumDepth() >= depth);
    }

    @Test void cancellationDeadlineAndLimitsAreDistinct() {
        var graph = graph(4, List.of(pattern("one", Map.of("raw", amount(1)), Map.of("done", amount(1)))));
        var cancelled = PlanningCancellation.source();
        cancelled.cancel();
        var limits = new PlanningLimits(100, 100, Duration.ofSeconds(1), 1);
        var cancelledResult = new IterativeCraftingPlanner<String>().plan(graph,
                new PlanningRequest<>("done", amount(1), Map.of("raw", amount(1)), limits, cancelled));
        assertEquals(PlanningResult.Status.CANCELLED, cancelledResult.status());

        var clock = new AtomicInteger();
        var timed = new IterativeCraftingPlanner<String>(() -> clock.getAndAdd(10));
        var tinyTimeout = new PlanningLimits(100, 100, Duration.ofNanos(1), 1);
        assertEquals(PlanningResult.Status.TIMED_OUT, timed.plan(graph,
                new PlanningRequest<>("done", amount(1), Map.of("raw", amount(1)), tinyTimeout,
                        PlanningCancellation.NEVER)).status());

        var operationLimit = new PlanningLimits(1, 100, Duration.ofSeconds(1), 1);
        assertEquals(PlanningResult.Status.OPERATION_LIMIT,
                new IterativeCraftingPlanner<String>().plan(graph,
                        new PlanningRequest<>("done", amount(1), Map.of("raw", amount(1)), operationLimit,
                                PlanningCancellation.NEVER)).status());
    }

    @Test void graphAndPlanSnapshotsCannotBeMutated() {
        var mutableInputs = new java.util.HashMap<String, UfoAmount>();
        mutableInputs.put("raw", amount(1));
        var pattern = new CraftingPattern<>("stable", mutableInputs, Map.of("done", amount(1)));
        mutableInputs.clear();
        var graph = graph(5, List.of(pattern));
        assertEquals(Map.of("raw", amount(1)), pattern.inputs());
        assertEquals(List.of(pattern), graph.patternsFor("done"));
        assertFalse(graph.patterns().isEmpty());
    }

    @Test void cacheReusesOnlyTheExactGridRevision() {
        var cache = new RevisionedCraftingGraphCache<String>();
        var builds = new AtomicInteger();
        java.util.function.LongFunction<ImmutableCraftingGraph<String>> factory = revision -> {
            builds.incrementAndGet();
            return graph(revision, List.of());
        };
        var first = cache.getOrBuild(10, factory);
        assertSame(first, cache.getOrBuild(10, factory));
        var second = cache.getOrBuild(11, factory);
        assertFalse(first == second);
        assertEquals(2, builds.get());
        assertEquals(new RevisionedCraftingGraphCache.CacheStats(1, 2), cache.stats());
    }

    private static CraftingPattern<String> pattern(String id, Map<String, UfoAmount> inputs,
                                                    Map<String, UfoAmount> outputs) {
        return new CraftingPattern<>(id, inputs, outputs);
    }
    private static ImmutableCraftingGraph<String> graph(long revision,
                                                         List<CraftingPattern<String>> patterns) {
        return ImmutableCraftingGraph.create(revision, Comparator.naturalOrder(), patterns);
    }
    private static UfoAmount amount(long value) { return UfoAmount.of(value); }
}
