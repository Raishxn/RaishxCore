package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raishxn.ufocore.api.amount.UfoAmount;
import com.raishxn.ufocore.api.crafting.planner.PlanningCancellation;
import com.raishxn.ufocore.neoforge.crafting.CooperativeGraphCapture.KeyDetails;
import com.raishxn.ufocore.neoforge.crafting.CooperativeGraphCapture.PatternDetails;
import com.raishxn.ufocore.neoforge.crafting.CooperativeGraphCapture.Slice;
import com.raishxn.ufocore.neoforge.crafting.CooperativeGraphCapture.Slot;
import com.raishxn.ufocore.neoforge.crafting.CooperativeGraphCapture.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The capture machine must stay deterministic and honest without a Minecraft world: one slice and
 * many slices must capture the same graph, a budget can never lose or half-publish state, and every
 * refusal path must discard everything it had already registered.
 */
class CooperativeGraphCaptureTest {
    private static final Slice UNBOUNDED = new Slice(Long.MAX_VALUE, Integer.MAX_VALUE);
    private static final Slice ONE_KEY = new Slice(2_000_000L, 1);
    private static final String TARGET = "assembled";

    @Test
    void capturesAWholeDagInOneUnboundedSlice() {
        var capture = capture(chain(), PlanningCancellation.NEVER);

        assertEquals(Status.COMPLETED, capture.advance(UNBOUNDED));

        var captured = capture.result();
        assertEquals(TARGET, captured.target());
        assertEquals(Set.of(TARGET, "frame", "plate", "ore"), captured.keys().keySet());
        assertEquals(3, captured.graph().patterns().size());
        assertEquals(3, captured.handles().size(), "every pattern keeps its native handle");
        assertEquals(1, captured.amountsPerByte().get("ore"));
        assertEquals(3, capture.patternsCaptured());
    }

    @Test
    void slicesYieldBetweenKeysAndCaptureTheSameGraph() {
        var whole = capture(chain(), PlanningCancellation.NEVER);
        assertEquals(Status.COMPLETED, whole.advance(UNBOUNDED));

        var sliced = capture(chain(), PlanningCancellation.NEVER);
        int slices = 0;
        while (sliced.advance(ONE_KEY) == Status.YIELDED) slices++;

        assertTrue(slices >= 3, "a one-edge slice needs one slice per key, got " + slices);
        assertEquals(whole.result().keys().keySet(), sliced.result().keys().keySet());
        assertEquals(whole.result().graph().patterns().stream().map(pattern -> pattern.id()).toList(),
                sliced.result().graph().patterns().stream().map(pattern -> pattern.id()).toList());
    }

    @Test
    void oneKeyPerSliceAlwaysMakesProgress() {
        var capture = capture(chain(), PlanningCancellation.NEVER);
        for (int attempt = 0; attempt < 50; attempt++) {
            if (capture.advance(ONE_KEY) == Status.COMPLETED) return;
        }
        throw new AssertionError("a bounded slice never finished the capture");
    }

    @Test
    void aSliceTooSmallForTheClockStillMakesProgress() {
        var capture = capture(chain(), PlanningCancellation.NEVER);
        for (int attempt = 0; attempt < 50; attempt++) {
            if (capture.advance(new Slice(1L, 1)) == Status.COMPLETED) return;
        }
        throw new AssertionError("a one-nanosecond slice never finished the capture");
    }

    @Test
    void cancellationBetweenSlicesDiscardsEverythingCapturedSoFar() {
        var cancellation = PlanningCancellation.source();
        var capture = capture(chain(), cancellation);
        assertEquals(Status.YIELDED, capture.advance(ONE_KEY));

        cancellation.cancel();

        assertEquals(Status.CANCELLED, capture.advance(ONE_KEY));
        assertEquals(Status.CANCELLED, capture.advance(UNBOUNDED));
        assertTrue(capture.cancelled());
        assertThrows(IllegalStateException.class, capture::result);
    }

    @Test
    void aDeclinedKeyDiscardsPartialStateAndPropagatesTheReason() {
        var grid = chain();
        grid.decline("plate");
        var capture = capture(grid, PlanningCancellation.NEVER);

        var declined = assertThrows(Ae2PlanningSnapshot.Declined.class, () -> capture.advance(UNBOUNDED));

        assertEquals("declined by the fake grid", declined.getMessage());
        assertEquals(Status.CANCELLED, capture.advance(UNBOUNDED));
        assertThrows(IllegalStateException.class, capture::result);
    }

    @Test
    void emitterKeysAreRefusedInsteadOfBeingPlanned() {
        var grid = chain();
        grid.emitter("ore");
        var capture = capture(grid, PlanningCancellation.NEVER);

        var declined = assertThrows(Ae2PlanningSnapshot.Declined.class, () -> capture.advance(UNBOUNDED));

        assertEquals("crafting emitter", declined.getMessage());
    }

    @Test
    void feedbackPatternsAreRefusedEvenWhenTheAdapterReportsThem() {
        var grid = chain();
        grid.selfFeeding("plate", "frame");
        var capture = capture(grid, PlanningCancellation.NEVER);

        var declined = assertThrows(Ae2PlanningSnapshot.Declined.class, () -> capture.advance(UNBOUNDED));

        assertEquals("feedback or catalyst pattern", declined.getMessage());
    }

    @Test
    void totalEdgeLimitStopsTheCaptureAndKeepsNoPartialState() {
        var grid = chain();
        var limits = new Ae2PlanningSnapshot.CaptureLimits(Duration.ofSeconds(30), 3, 100, 1_000_000L);
        var capture = new CooperativeGraphCapture<String>(grid, TARGET, 7L, limits, PlanningCancellation.NEVER);

        var declined = assertThrows(Ae2PlanningSnapshot.Declined.class, () -> capture.advance(UNBOUNDED));

        assertEquals("snapshot edge limit", declined.getMessage());
        assertThrows(IllegalStateException.class, capture::result);
    }

    @Test
    void outputKeysAreRegisteredWithoutBeingRouted() {
        var grid = new FakeGrid();
        grid.leaf("ore");
        grid.produces(TARGET, inputs("ore", 1L));
        var capture = capture(grid, PlanningCancellation.NEVER);
        assertEquals(Status.COMPLETED, capture.advance(UNBOUNDED));

        // "ore" is consumed but never routed, so it is registered without becoming a route.
        assertEquals(2, capture.result().keys().size());
        assertEquals(1, capture.result().graph().patternsFor(TARGET).size());
        assertTrue(capture.result().graph().patternsFor("ore").isEmpty());
    }

    @Test
    void aDiamondRegistersEveryKeyOnceInBreadthFirstOrder() {
        var grid = new FakeGrid();
        grid.leaf("ore");
        grid.leaf("gem");
        grid.produces("left", inputs("ore", 1L));
        grid.produces("right", inputs("gem", 1L));
        grid.produces(TARGET, inputs("left", 1L, "right", 1L));
        var capture = capture(grid, PlanningCancellation.NEVER);

        assertEquals(Status.COMPLETED, capture.advance(UNBOUNDED));

        assertEquals(5, capture.result().keys().size());
        assertEquals(List.of(TARGET, "left", "right", "ore", "gem"), List.copyOf(grid.described));
    }

    @Test
    void aPatternReportedForTwoKeysIsCapturedOnce() {
        var grid = new FakeGrid();
        grid.leaf("ore");
        grid.produces("plate", inputs("ore", 1L));
        grid.sharedPattern("gear", "shared", inputs("plate", 1L));
        grid.sharedPattern("cog", "shared", inputs("plate", 1L));
        grid.produces(TARGET, inputs("gear", 1L, "cog", 1L));
        var capture = capture(grid, PlanningCancellation.NEVER);

        assertEquals(Status.COMPLETED, capture.advance(UNBOUNDED));

        var ids = capture.result().graph().patterns().stream().map(pattern -> pattern.id()).toList();
        assertEquals(ids.size(), Set.copyOf(ids).size(), "duplicate pattern ids must not reach the graph");
        assertEquals(3, ids.size());
    }

    @Test
    void theResultDoesNotDependOnRegistrationOrder() {
        var forward = new FakeGrid();
        forward.leaf("ore");
        forward.produces(TARGET, inputs("ore", 1L));
        var reversed = new FakeGrid();
        reversed.produces(TARGET, inputs("ore", 1L));
        reversed.leaf("ore");

        var first = capture(forward, PlanningCancellation.NEVER);
        var second = capture(reversed, PlanningCancellation.NEVER);
        assertEquals(Status.COMPLETED, first.advance(UNBOUNDED));
        assertEquals(Status.COMPLETED, second.advance(UNBOUNDED));

        assertEquals(first.result().keys().keySet(), second.result().keys().keySet());
        assertEquals(first.result().graph().patterns().stream().map(pattern -> pattern.id()).toList(),
                second.result().graph().patterns().stream().map(pattern -> pattern.id()).toList());
        assertEquals(first.result().amountsPerByte(), second.result().amountsPerByte());
    }

    @Test
    void sliceMetricsReportSpentTimeAndConservativeBytes() {
        var capture = capture(chain(), PlanningCancellation.NEVER);
        assertEquals(0L, capture.lastSliceNanos());

        assertEquals(Status.YIELDED, capture.advance(ONE_KEY));

        assertTrue(capture.lastSliceNanos() > 0L, "a slice must report the time it spent");
        assertEquals(Status.COMPLETED, capture.advance(UNBOUNDED));
        assertTrue(capture.result().estimatedBytes() > 0L, "the snapshot must carry its byte weight");
    }

    @Test
    void batchAmountsAndHandlesSurviveTheCapture() {
        var grid = new FakeGrid();
        grid.leaf("ore");
        grid.produces(TARGET, inputs("ore", 4L), 2L);
        var capture = capture(grid, PlanningCancellation.NEVER);

        assertEquals(Status.COMPLETED, capture.advance(UNBOUNDED));

        var pattern = capture.result().graph().patternsFor(TARGET).getFirst();
        assertEquals(UfoAmount.of(4), pattern.inputs().get("ore"));
        assertEquals(UfoAmount.of(2), pattern.outputs().get(TARGET));
        assertInstanceOf(String.class, capture.result().handles().get(pattern.id()));
        assertNotNull(capture.result().amountsPerByte().get("ore"));
    }

    @Test
    void invalidConstructionIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new CooperativeGraphCapture<String>(new FakeGrid(), "", 0L, limits(), PlanningCancellation.NEVER));
        assertThrows(IllegalArgumentException.class,
                () -> new CooperativeGraphCapture<String>(chain(), TARGET, -1L, limits(), PlanningCancellation.NEVER));
        assertThrows(IllegalArgumentException.class, () -> new Slice(0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new Slice(1L, 0));
        assertThrows(IllegalArgumentException.class, () -> new Slot(UfoAmount.ZERO, 1));
    }

    private static CooperativeGraphCapture<String> capture(FakeGrid grid, PlanningCancellation cancellation) {
        return new CooperativeGraphCapture<>(grid, TARGET, 7L, limits(), cancellation);
    }

    private static Ae2PlanningSnapshot.CaptureLimits limits() {
        return new Ae2PlanningSnapshot.CaptureLimits(Duration.ofSeconds(30), 10_000, 1_000, 8L * 1024 * 1024);
    }

    private static Map<String, Long> inputs(Object... pairs) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], (Long) pairs[index + 1]);
        }
        return map;
    }

    private static FakeGrid chain() {
        var grid = new FakeGrid();
        grid.leaf("ore");
        grid.produces("plate", inputs("ore", 1L));
        grid.produces("frame", inputs("plate", 1L));
        grid.produces(TARGET, inputs("frame", 1L));
        return grid;
    }

    /** Minimal stand-in for the grid: exact patterns by id, without touching AE2 or Minecraft. */
    private static final class FakeGrid implements CooperativeGraphCapture.Source<String> {
        private final Map<String, List<PatternDetails<String>>> recipes = new LinkedHashMap<>();
        private final Map<String, KeyDetails> details = new LinkedHashMap<>();
        private final List<String> described = new ArrayList<>();
        private final java.util.Set<String> declined = new java.util.HashSet<>();
        private int generated;

        void leaf(String id) {
            details.put(id, new KeyDetails(id, 1, false, 0));
        }

        void produces(String output, Map<String, Long> inputs) {
            produces(output, inputs, 1L);
        }

        void produces(String output, Map<String, Long> inputs, long outputAmount) {
            add(output, "pattern-" + output + "-" + generated, inputs, outputAmount);
        }

        void sharedPattern(String output, String patternId, Map<String, Long> inputs) {
            add(output, patternId, inputs, 1L);
        }

        void emitter(String id) {
            details.put(id, new KeyDetails(id, 1, true, 0));
        }

        void decline(String id) {
            declined.add(id);
        }

        /** Reports a pattern whose input is also its own output, as a broken adapter would. */
        void selfFeeding(String output, String input) {
            Map<String, Slot> slots = new LinkedHashMap<>();
            slots.put(input, new Slot(UfoAmount.of(1), 1));
            slots.put(output, new Slot(UfoAmount.of(1), 1));
            recipes.computeIfAbsent(output, ignored -> new ArrayList<>()).add(new PatternDetails<>(
                    "handle-feedback", "pattern-feedback", 0, slots,
                    Map.of(output, new Slot(UfoAmount.of(1), 1)), Set.of(output)));
            details.put(output, new KeyDetails(output, 1, false, recipes.get(output).size()));
        }

        @Override
        public KeyDetails describe(String id) {
            described.add(id);
            KeyDetails known = details.get(id);
            if (known == null || declined.contains(id)) {
                throw new Ae2PlanningSnapshot.Declined("declined by the fake grid");
            }
            return known;
        }

        @Override
        public List<PatternDetails<String>> patternsFor(String id) {
            return recipes.getOrDefault(id, List.of());
        }

        private void add(String output, String patternId, Map<String, Long> inputs, long outputAmount) {
            generated++;
            Map<String, Slot> slots = new LinkedHashMap<>();
            inputs.forEach((input, amount) -> slots.put(input, new Slot(UfoAmount.of(amount), 1)));
            recipes.computeIfAbsent(output, ignored -> new ArrayList<>()).add(new PatternDetails<>(
                    "handle-" + patternId, patternId, 0, slots,
                    Map.of(output, new Slot(UfoAmount.of(outputAmount), 1)), Set.of(output)));
            details.put(output, new KeyDetails(output, 1, false, recipes.get(output).size()));
        }
    }
}
