package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raishxn.ufocore.neoforge.crafting.CaptureSliceHarness.Case;
import com.raishxn.ufocore.neoforge.crafting.CaptureSliceHarness.Fairness;
import com.raishxn.ufocore.neoforge.crafting.CaptureSliceHarness.Measurement;
import com.raishxn.ufocore.neoforge.crafting.CaptureSliceHarness.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The gate has to fail for the right reasons. A measurement table that always says PASSED would be
 * worse than no table, so every reason the gate can reject a capture is exercised directly, next to a
 * real run of the smoke corpus through the production machine.
 */
class CaptureSliceHarnessTest {
    private static final CaptureSliceHarness HARNESS = new CaptureSliceHarness();

    @Test
    void theSmokeCorpusPassesItsOwnGate() {
        var report = HARNESS.run(CaptureSliceHarness.smokeCases(), false);

        assertTrue(report.passed(), () -> report.render());
        assertEquals(3, report.measurements().size());
        assertNull(report.fairness());
        for (Measurement measurement : report.measurements()) {
            assertTrue(measurement.completed(), measurement.label() + " must complete");
            assertTrue(measurement.slices() > 1, measurement.label() + " must be sliced");
            assertTrue(measurement.widestSliceEdges() <= measurement.sliceEdges() + measurement.tailEdges(),
                    measurement.label() + " exceeded its allowance plus one atomic tail");
        }
    }

    @Test
    void anUnmeasuredCaseIsStillHeldToTheDeterministicBound() {
        var report = HARNESS.run(List.of(Case.of("only-shape/CHAIN", Shape.CHAIN, 64, 0L)), false);

        var measurement = report.measurements().getFirst();
        assertTrue(report.passed(), () -> report.render());
        assertEquals(63, measurement.patterns(), "a 64-key chain has one pattern per non-leaf key");
        assertEquals(64, measurement.keys());
    }

    @Test
    void theGateRejectsASliceThatGrewPastItsAllowance() {
        var failures = CaptureSliceHarness.check(measurement(builder -> builder.widestSliceEdges = 500));

        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().contains("past its allowance"), failures.toString());
    }

    @Test
    void theGateRejectsASlicedCaptureThatChangedTheGraph() {
        var failures = CaptureSliceHarness.check(measurement(builder -> builder.equivalentToUnbounded = false));

        assertTrue(failures.getFirst().contains("changed the captured graph"), failures.toString());
    }

    @Test
    void theGateRejectsACaptureLargerThanOneAllowanceThatWasNeverSliced() {
        var oversized = CaptureSliceHarness.check(measurement(
                builder -> builder.slices = 1,
                builder -> builder.edges = 500L));
        var fitsInOneSlice = CaptureSliceHarness.check(measurement(builder -> builder.slices = 1));

        assertTrue(oversized.getFirst().contains("never sliced"), oversized.toString());
        assertTrue(fitsInOneSlice.isEmpty(),
                "a capture that fits inside one allowance is allowed to finish in one slice");
    }

    @Test
    void theGateReportsTheTargetButOnlyRejectsACatastrophicSlice() {
        var atTarget = CaptureSliceHarness.check(measurement(
                builder -> builder.gridCallNanos = 1L,
                builder -> builder.p95Nanos = CaptureSliceHarness.SLICE_TARGET_NANOS));
        var justOver = CaptureSliceHarness.check(measurement(
                builder -> builder.gridCallNanos = 1L,
                builder -> builder.p95Nanos = CaptureSliceHarness.SLICE_TARGET_NANOS + 1L));
        var catastrophic = CaptureSliceHarness.check(measurement(
                builder -> builder.gridCallNanos = 1L,
                builder -> builder.p95Nanos = CaptureSliceHarness.SLICE_TARGET_NANOS * 11L));

        assertTrue(atTarget.isEmpty(), "reaching the target exactly must pass");
        // Wall-clock time measures the machine as much as the slicing, so a run just over the target
        // on a busy machine is reported rather than failed. The deterministic invariants carry the
        // gate; this ceiling only catches the slicing itself changing by an order of magnitude.
        assertTrue(justOver.isEmpty(),
                () -> "a busy machine must not fail the gate: " + justOver);
        assertTrue(catastrophic.getFirst().contains("10x the"), catastrophic.toString());
    }

    @Test
    void theGateRejectsACaptureThatNeverCompleted() {
        var failures = CaptureSliceHarness.check(measurement(builder -> builder.completed = false));

        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().contains("did not complete"), failures.toString());
    }

    @Test
    void theFairnessGateRejectsStarvationAndMissingCaptures() {
        assertTrue(CaptureSliceHarness.checkFairness(fairness(4, 4, 4)).isEmpty());

        var starved = CaptureSliceHarness.checkFairness(fairness(4, 4, 9));
        var missing = CaptureSliceHarness.checkFairness(fairness(4, 1, 2));

        assertTrue(starved.getFirst().contains("waited"), starved.toString());
        assertTrue(missing.getFirst().contains("only 1 of 4"), missing.toString());
    }

    @Test
    void theCorpusStressesBothWidthAndDepth() {
        var cases = CaptureSliceHarness.cases();

        assertTrue(cases.stream().anyMatch(test -> test.shape() == Shape.FAT_KEY && test.size() >= 10_000),
                "the corpus must include a key of ten thousand patterns");
        assertTrue(cases.stream().anyMatch(test -> test.shape() == Shape.CHAIN && test.size() >= 64),
                "the corpus must include a deep chain");
        assertEquals(3, Shape.CHAIN.tailEdges());
        assertEquals(4, Shape.FIBONACCI.tailEdges(), "a two-input pattern costs one more edge");
        assertEquals(3, Shape.FAT_KEY.tailEdges());
    }

    private static Measurement measurement(Consumer<MutableMeasurement> change) {
        return measurement(ignored -> { }, change);
    }

    private static Measurement measurement(Consumer<MutableMeasurement> first,
                                           Consumer<MutableMeasurement> second) {
        var builder = new MutableMeasurement();
        first.accept(builder);
        second.accept(builder);
        return builder.build();
    }

    private static Fairness fairness(int grids, int completed, long maxWaitTicks) {
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < grids; index++) labels.add("fair/" + index);
        return new Fairness(List.copyOf(labels), 10L, 40L, maxWaitTicks, completed, 2_000_000L);
    }

    /** A valid measurement that a test can corrupt in exactly one way. */
    private static final class MutableMeasurement {
        private boolean completed = true;
        private boolean equivalentToUnbounded = true;
        private long slices = 40L;
        private long edges = 3L;
        private long gridCallNanos;
        private int widestSliceEdges = 60;
        private long p95Nanos = 500_000L;

        Measurement build() {
            return new Measurement("unit/CHAIN", 64, 3, gridCallNanos, completed, 64, 63, slices,
                    widestSliceEdges, edges, equivalentToUnbounded, 100_000L, p95Nanos, 700_000L, 900_000L,
                    400_000L, 2_000_000L, 12L, 0L);
        }
    }
}
