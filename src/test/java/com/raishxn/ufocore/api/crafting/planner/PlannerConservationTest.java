package com.raishxn.ufocore.api.crafting.planner;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlannerConservationTest {
    @Test void rollsBackAnEarlierChoiceWhenALaterSiblingNeedsItsMaterial() {
        var a = p("a-from-c", "a", Map.of("c", 1L));
        var alternative = p("a-from-d", "a", Map.of("d", 1L));
        var b = p("b-from-c", "b", Map.of("c", 1L));
        var target = p("target", "target", Map.of("a", 1L, "b", 1L));
        var result = run(List.of(a, alternative, b, target), "target", 1, Map.of("c", 1L, "d", 1L));
        assertEquals(PlanningResult.Status.COMPLETE, result.status());
        assertFalse(result.plan().patternExecutions().containsKey(a));
        assertEquals(UfoAmount.ONE, result.plan().patternExecutions().get(alternative));
        verify(result.plan());
    }

    @Test void combinesTwoPatternsWhenNeitherAloneHasEnoughStock() {
        var result = run(List.of(p("a", "target", Map.of("raw-a", 1L)),
                p("b", "target", Map.of("raw-b", 1L))), "target", 7, Map.of("raw-a", 3L, "raw-b", 4L));
        assertEquals(PlanningResult.Status.COMPLETE, result.status());
        assertEquals(2, result.plan().patternExecutions().size());
        verify(result.plan());
    }

    @Test void batchesAnExponentialFibonacciDagAndQuantitiesPastLong() {
        List<CraftingPattern<String>> patterns = new ArrayList<>();
        patterns.add(p("p1", "k1", Map.of("k0", 1L)));
        for (int i = 2; i <= 100; i++) {
            patterns.add(p("p" + i, "k" + i, Map.of("k" + (i - 1), 1L, "k" + (i - 2), 1L)));
        }
        var graph = ImmutableCraftingGraph.create(1, Comparator.<String>naturalOrder(), patterns);
        var amount = UfoAmount.of(BigInteger.TEN.pow(30));
        var result = new IterativeCraftingPlanner<String>().plan(graph,
                new PlanningRequest<>("k100", amount, Map.of("k0", UfoAmount.of(BigInteger.TEN.pow(70)))));
        assertEquals(PlanningResult.Status.COMPLETE, result.status());
        assertEquals(100, result.plan().patternExecutions().size());
        assertTrue(result.diagnostics().operations() < 1000);
        assertTrue(result.plan().extractedFromInventory().get("k0").bitLength() > 63);
        verify(result.plan());
    }

    @Test void shuffledGraphsProduceIdenticalSchedulesAndConserveAllResources() {
        Random random = new Random(82173);
        for (int example = 0; example < 60; example++) {
            List<CraftingPattern<String>> patterns = new ArrayList<>();
            for (int i = 1; i < 25; i++) {
                patterns.add(p("p" + i, "k" + i, Map.of("k" + random.nextInt(i), 1L + random.nextInt(4))));
            }
            var first = run(patterns, "k24", 3, Map.of("k0", 1000000000L));
            Collections.shuffle(patterns, random);
            var second = run(patterns, "k24", 3, Map.of("k0", 1000000000L));
            assertEquals(first.plan(), second.plan());
            verify(first.plan());
        }
    }

    @Test void cancelledOrLimitedPlansCannotBeSubmittedAsComplete() {
        var graph = ImmutableCraftingGraph.create(1, Comparator.<String>naturalOrder(),
                List.of(p("p", "a", Map.of("b", 1L)), p("q", "b", Map.of("c", 1L))));
        for (var limits : List.of(new PlanningLimits(1, 100, Duration.ofSeconds(5), 1),
                new PlanningLimits(1000, 1, Duration.ofSeconds(5), 1))) {
            var result = new IterativeCraftingPlanner<String>().plan(graph,
                    new PlanningRequest<>("a", UfoAmount.ONE, Map.of("c", UfoAmount.ONE), limits,
                            PlanningCancellation.NEVER));
            assertNotEquals(PlanningResult.Status.COMPLETE, result.status());
            assertFalse(result.plan().complete());
            assertFalse(result.plan().quality().complete());
            assertTrue(result.plan().patternExecutions().isEmpty());
            assertTrue(result.plan().schedule().isEmpty());
        }
    }

    @Test void collectsACoproductFromItsSiblingBranchBeforeDemandingIt() {
        // "coupler" sorts before "part", so raw key order demanded the coproduct before the route
        // that produces it and reported an impossible shortage instead of collecting it.
        var makePart = new CraftingPattern<>("make-part", 0, amounts(Map.of("raw", 1L)),
                amounts(Map.of("part", 1L, "coupler", 1L)), java.util.Set.of("part"));
        var assemble = new CraftingPattern<>("assemble", 0, amounts(Map.of("coupler", 1L, "part", 1L)),
                Map.of("target", UfoAmount.ONE));
        var result = run(List.of(makePart, assemble), "target", 1, Map.of("raw", 1L));

        assertEquals(PlanningResult.Status.COMPLETE, result.status());
        assertEquals(UfoAmount.ONE, result.plan().patternExecutions().get(makePart));
        verify(result.plan());
    }

    @Test void byproductsStayAvailableAndCannotBeSelectedAsAe2PrimaryOutputs() {
        var both = new CraftingPattern<>("a-and-b", 0, amounts(Map.of("raw", 1L)),
                amounts(Map.of("a", 1L, "b", 1L)), java.util.Set.of("a"));
        var result = run(List.of(both, p("done", "done", Map.of("a", 1L, "b", 1L))),
                "done", 1, Map.of("raw", 1L));
        assertEquals(PlanningResult.Status.COMPLETE, result.status());
        verify(result.plan());
        var missing = run(List.of(both), "b", 1, Map.of("raw", 1L));
        assertEquals(PlanningResult.Status.MISSING_INGREDIENTS, missing.status());
    }

    @Test void cachePublishesOneGraphToConcurrentReadersAndRejectsWrongRevisions() throws Exception {
        var cache = new RevisionedCraftingGraphCache<String>();
        var count = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(6)) {
            var tasks = java.util.stream.IntStream.range(0, 30).<java.util.concurrent.Callable<ImmutableCraftingGraph<String>>>mapToObj(
                    ignored -> () -> cache.getOrBuild(9, revision -> {
                        count.incrementAndGet();
                        return ImmutableCraftingGraph.create(revision, Comparator.naturalOrder(), List.of());
                    })).toList();
            var results = executor.invokeAll(tasks);
            var graph = results.getFirst().get();
            for (var result : results) assertSame(graph, result.get());
            assertEquals(1, count.get());
            cache.getOrBuild(8, revision -> ImmutableCraftingGraph.create(revision, Comparator.naturalOrder(), List.of()));
            assertSame(graph, cache.getOrBuild(9, revision -> { throw new AssertionError("stale eviction"); }));
        }
        assertThrows(IllegalArgumentException.class, () -> cache.getOrBuild(10,
                ignored -> ImmutableCraftingGraph.create(11, Comparator.naturalOrder(), List.of())));
    }

    /** Execute the actual ordered plan and prove no batch spends a resource before it exists. */
    static <K> void verify(CraftingPlan<K> plan) {
        Map<K, UfoAmount> available = new HashMap<>(plan.extractedFromInventory());
        plan.missing().forEach((key, value) -> available.merge(key, value, UfoAmount::add));
        Map<CraftingPattern<K>, UfoAmount> firings = new HashMap<>();
        for (var step : plan.schedule()) {
            firings.merge(step.pattern(), step.runs(), UfoAmount::add);
            step.pattern().inputs().forEach((key, amount) -> {
                var required = amount.multiply(step.runs().asBigInteger());
                var stored = available.getOrDefault(key, UfoAmount.ZERO);
                assertTrue(stored.compareTo(required) >= 0, () -> "unfunded input " + key + " for " + step.pattern().id());
                available.put(key, stored.subtract(required));
            });
            step.pattern().outputs().forEach((key, amount) -> available.merge(key,
                    amount.multiply(step.runs().asBigInteger()), UfoAmount::add));
        }
        assertEquals(plan.patternExecutions(), firings);
        assertTrue(available.getOrDefault(plan.target(), UfoAmount.ZERO).compareTo(plan.requested()) >= 0);
    }

    private static CraftingPattern<String> p(String id, String output, Map<String, Long> inputs) {
        return new CraftingPattern<>(id, amounts(inputs), Map.of(output, UfoAmount.ONE));
    }
    private static Map<String, UfoAmount> amounts(Map<String, Long> values) {
        Map<String, UfoAmount> result = new HashMap<>();
        values.forEach((key, value) -> result.put(key, UfoAmount.of(value)));
        return result;
    }
    private static PlanningResult<String> run(List<CraftingPattern<String>> patterns, String target,
                                               long count, Map<String, Long> inventory) {
        return new IterativeCraftingPlanner<String>().plan(
                ImmutableCraftingGraph.create(1, Comparator.naturalOrder(), patterns),
                new PlanningRequest<>(target, UfoAmount.of(count), amounts(inventory)));
    }
}
