package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** A byte ceiling must be enforced, not only an entry count: one huge snapshot can outweigh the rest. */
class CaptureSnapshotCacheTest {
    @Test
    void evictsTheLeastRecentlyUsedEntryWhenTheCountIsReached() {
        var cache = new CaptureSnapshotCache<String, Long>(2, 1_000L, value -> value);

        cache.put("a", 10L);
        cache.put("b", 20L);
        cache.get("a");
        cache.put("c", 30L);

        assertNull(cache.get("b"), "the least recently used entry must go first");
        assertEquals(10L, cache.get("a"));
        assertEquals(30L, cache.get("c"));
        assertEquals(1L, cache.evictions());
    }

    @Test
    void evictsOnBytesEvenWhenTheEntryCountStillFits() {
        var cache = new CaptureSnapshotCache<String, Long>(10, 100L, value -> value);

        cache.put("small", 40L);
        cache.put("huge", 90L);

        assertNull(cache.get("small"), "the byte ceiling must evict before the entry ceiling");
        assertEquals(90L, cache.bytes());
        assertEquals(1, cache.size());
    }

    @Test
    void replacingAnEntryRecomputesTheByteTotal() {
        var cache = new CaptureSnapshotCache<String, Long>(4, 1_000L, value -> value);

        cache.put("a", 100L);
        cache.put("a", 25L);

        assertEquals(25L, cache.bytes());
        assertEquals(1, cache.size());
    }

    @Test
    void shrinkingThePolicyTrimsImmediately() {
        var cache = new CaptureSnapshotCache<String, Long>(8, 1_000L, value -> value);
        cache.put("a", 40L);
        cache.put("b", 40L);
        cache.put("c", 40L);

        cache.configure(2, 1_000L);

        assertEquals(2, cache.size());
        assertNull(cache.get("a"));
    }

    @Test
    void clearingReleasesTheByteTotal() {
        var cache = new CaptureSnapshotCache<String, Long>(4, 1_000L, value -> value);
        cache.put("a", 40L);

        cache.clear();

        assertEquals(0, cache.size());
        assertEquals(0L, cache.bytes());
    }

    @Test
    void invalidLimitsAndEntriesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CaptureSnapshotCache<String, Long>(0, 10L, v -> v));
        assertThrows(IllegalArgumentException.class, () -> new CaptureSnapshotCache<String, Long>(1, 0L, v -> v));
        var cache = new CaptureSnapshotCache<String, Long>(1, 10L, v -> v);
        assertThrows(NullPointerException.class, () -> cache.put(null, 1L));
        assertThrows(NullPointerException.class, () -> cache.put("a", null));
    }
}
