package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Gate O needs a measured slice p95. The histogram must therefore never under-report: a percentile is
 * the upper edge of the bucket a sample landed in, an outlier cannot disappear, and an idle tick must
 * not be recorded as a fast one.
 */
class CaptureSliceMetricsTest {
    private static final long MILLIS = 1_000_000L;

    @Test
    void anEmptySnapshotReportsZeroInsteadOfGuessing() {
        var snapshot = new CaptureSliceMetrics().snapshot();

        assertEquals(0L, snapshot.slices());
        assertEquals(0L, snapshot.ticks());
        assertEquals(0L, snapshot.sliceP95Nanos());
        assertEquals(0L, snapshot.sliceMaxNanos());
        assertEquals(0L, snapshot.meanSliceNanos());
        assertEquals(0L, snapshot.meanSliceEdges());
    }

    @Test
    void aPercentileIsTheUpperEdgeOfTheBucketTheSampleLandedIn() {
        var metrics = new CaptureSliceMetrics();
        for (int sample = 0; sample < 20; sample++) metrics.recordSlice(30_000L, 4, 10_000L, 20_000L, 0L);
        metrics.recordSlice(3 * MILLIS, 9, 1 * MILLIS, 2 * MILLIS, 0L);

        var snapshot = metrics.snapshot();

        assertEquals(21L, snapshot.slices());
        assertEquals(50_000L, snapshot.sliceP95Nanos(), "one outlier in twenty must not move the p95");
        assertEquals(4 * MILLIS, snapshot.sliceP99Nanos(),
                "a percentile never reports less than the sample that caused it");
        assertEquals(3 * MILLIS, snapshot.sliceMaxNanos());
        assertEquals(20 * 30_000L + 3 * MILLIS, snapshot.sliceTotalNanos());
        assertEquals(89L, snapshot.edges());
        assertEquals(4L, snapshot.meanSliceEdges(), "integer mean, no rounding up");
    }

    @Test
    void aSampleBeyondTheLastBucketIsCountedAsOverflow() {
        var metrics = new CaptureSliceMetrics();
        metrics.recordSlice(2 * 1_000_000_000L, 1, 0L, 0L, 0L);

        var snapshot = metrics.snapshot();

        assertEquals(1L, snapshot.overflowSamples());
        assertEquals(2 * 1_000_000_000L, snapshot.sliceP99Nanos(), "the maximum must survive an overflow");
        assertEquals(2 * 1_000_000_000L, snapshot.sliceMaxNanos());
    }

    @Test
    void ticksAreMeasuredSeparatelyFromSlices() {
        var metrics = new CaptureSliceMetrics();
        metrics.recordSlice(MILLIS, 5, 0L, 0L, 0L);
        metrics.recordTick(4 * MILLIS);

        var snapshot = metrics.snapshot();

        assertEquals(1L, snapshot.slices());
        assertEquals(1L, snapshot.ticks());
        assertEquals(MILLIS, snapshot.sliceMaxNanos());
        assertEquals(4 * MILLIS, snapshot.tickMaxNanos());
        assertEquals(4 * MILLIS, snapshot.tickTotalNanos());
        assertEquals(4 * MILLIS, snapshot.tickP95Nanos());
    }

    @Test
    void phasesAccumulateSeparatelyFromTheSliceTotal() {
        var metrics = new CaptureSliceMetrics();
        metrics.recordSlice(10 * MILLIS, 2, 3 * MILLIS, 5 * MILLIS, 0L);
        metrics.recordSlice(20 * MILLIS, 6, 1 * MILLIS, 2 * MILLIS, 15 * MILLIS);

        var snapshot = metrics.snapshot();

        assertEquals(4 * MILLIS, snapshot.keyNanos());
        assertEquals(7 * MILLIS, snapshot.patternNanos());
        assertEquals(15 * MILLIS, snapshot.publishNanos());
        assertTrue(snapshot.keyNanos() + snapshot.patternNanos() + snapshot.publishNanos()
                        <= snapshot.sliceTotalNanos(),
                "phases are subsets of the slices they describe");
    }

    @Test
    void theSameSamplesAlwaysProduceTheSameSnapshot() {
        var first = new CaptureSliceMetrics();
        var second = new CaptureSliceMetrics();
        for (int sample = 0; sample < 64; sample++) {
            first.recordSlice(sample * 1_000L, sample % 7, 0L, 0L, 0L);
            first.recordTick(sample * 2_000L);
            second.recordSlice(sample * 1_000L, sample % 7, 0L, 0L, 0L);
            second.recordTick(sample * 2_000L);
        }

        assertEquals(first.snapshot(), second.snapshot());
    }

    @Test
    void impossibleSamplesAndPercentilesAreRefused() {
        var metrics = new CaptureSliceMetrics();

        assertThrows(IllegalArgumentException.class, () -> metrics.recordSlice(-1L, 1, 0L, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> metrics.recordSlice(1L, -1, 0L, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> metrics.recordSlice(1L, 1, -1L, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> metrics.recordTick(-1L));
        assertThrows(IllegalArgumentException.class, () -> new CaptureSliceMetrics.Histogram().percentile(0));
        assertThrows(IllegalArgumentException.class, () -> new CaptureSliceMetrics.Histogram().percentile(101));
    }
}
