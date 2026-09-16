package com.raishxn.ufocore;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Runtime switches for the platform. The planner kill-switch is the field escape
 * hatch: with it off, new crafting requests delegate to AE2's built-in planner
 * before graph capture. Already submitted calculations are cancelled when the
 * planner is disabled or their source grid revision becomes obsolete.
 */
public final class CoreConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final PlannerPolicy DEFAULT_POLICY = new PlannerPolicy(
            2, 32, 2_000, 10_000_000L, 100_000, 128,
            50, 100_000, 25_000, 64L * 1024 * 1024, 16);

    private static final ModConfigSpec.BooleanValue PLANNER_ENABLED = BUILDER
            .comment("Replace AE2's crafting planner with the RaishxCore iterative planner.",
                    "When false, every crafting calculation is served by AE2's built-in planner.")
            .define("planner.enabled", true);

    private static final ModConfigSpec.IntValue PLANNER_WORKERS = BUILDER
            .comment("Planner worker threads. Applied when the planner pool is first created; restart to change.")
            .defineInRange("planner.workers", DEFAULT_POLICY.workers(), 1, 16);
    private static final ModConfigSpec.IntValue PLANNER_QUEUE_CAPACITY = BUILDER
            .comment("Global pending planner queue capacity. Restart required after the pool is created.")
            .defineInRange("planner.queueCapacity", DEFAULT_POLICY.queueCapacity(), 1, 4_096);
    private static final ModConfigSpec.IntValue PLANNER_TIMEOUT_MILLIS = BUILDER
            .comment("Wall-clock deadline shared by all attempts for one crafting request.")
            .defineInRange("planner.timeoutMillis", DEFAULT_POLICY.timeoutMillis(), 10, 60_000);
    private static final ModConfigSpec.LongValue PLANNER_MAX_OPERATIONS = BUILDER
            .comment("Maximum deterministic planner operations per attempt.")
            .defineInRange("planner.maxOperations", DEFAULT_POLICY.maxOperations(), 1_000L, 1_000_000_000L);
    private static final ModConfigSpec.IntValue PLANNER_MAX_DEPTH = BUILDER
            .comment("Maximum dependency depth accepted by the iterative planner.")
            .defineInRange("planner.maxDepth", DEFAULT_POLICY.maxDepth(), 16, 1_000_000);
    private static final ModConfigSpec.IntValue PLANNER_CHECKPOINT_INTERVAL = BUILDER
            .comment("Operations between timeout, interrupt and cancellation checks.")
            .defineInRange("planner.checkpointInterval", DEFAULT_POLICY.checkpointInterval(), 1, 8_192);
    private static final ModConfigSpec.IntValue SNAPSHOT_TIMEOUT_MILLIS = BUILDER
            .comment("Maximum main-thread time for one AE2 graph snapshot before falling back to AE2.")
            .defineInRange("planner.snapshot.timeoutMillis", DEFAULT_POLICY.snapshotTimeoutMillis(), 1, 1_000);
    private static final ModConfigSpec.IntValue SNAPSHOT_MAX_EDGES = BUILDER
            .comment("Maximum pattern/input/output edges captured in one graph snapshot.")
            .defineInRange("planner.snapshot.maxEdges", DEFAULT_POLICY.snapshotMaxEdges(), 100, 10_000_000);
    private static final ModConfigSpec.IntValue SNAPSHOT_MAX_KEYS = BUILDER
            .comment("Maximum distinct AE keys captured in one graph snapshot.")
            .defineInRange("planner.snapshot.maxKeys", DEFAULT_POLICY.snapshotMaxKeys(), 100, 1_000_000);
    private static final ModConfigSpec.LongValue SNAPSHOT_MAX_ESTIMATED_BYTES = BUILDER
            .comment("Conservative estimated heap ceiling for one cached graph snapshot.")
            .defineInRange("planner.snapshot.maxEstimatedBytes", DEFAULT_POLICY.snapshotMaxEstimatedBytes(),
                    1024L * 1024, 1024L * 1024 * 1024);
    private static final ModConfigSpec.IntValue SNAPSHOT_CACHE_ENTRIES = BUILDER
            .comment("Maximum target-specific snapshots cached per AE2 grid revision.")
            .defineInRange("planner.snapshot.cacheEntries", DEFAULT_POLICY.snapshotCacheEntries(), 1, 256);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * Test-only override for {@link #isPlannerEnabled()}; never set outside automated
     * tests because the COMMON switch is an instance-wide decision.
     */
    public static volatile Boolean plannerEnabledTestOverride;

    private CoreConfig() {
    }

    /**
     * Read on every planning request. Defaults to enabled while the config file has
     * not loaded (unit tests, early startup), so the bridge never blocks startup.
     */
    public static boolean isPlannerEnabled() {
        Boolean override = plannerEnabledTestOverride;
        if (override != null) return override;
        return !SPEC.isLoaded() || PLANNER_ENABLED.get();
    }

    /** Runtime limits; startup-only pool fields are documented in their config comments. */
    public static PlannerPolicy plannerPolicy() {
        if (!SPEC.isLoaded()) return DEFAULT_POLICY;
        return new PlannerPolicy(PLANNER_WORKERS.get(), PLANNER_QUEUE_CAPACITY.get(),
                PLANNER_TIMEOUT_MILLIS.get(), PLANNER_MAX_OPERATIONS.get(), PLANNER_MAX_DEPTH.get(),
                PLANNER_CHECKPOINT_INTERVAL.get(), SNAPSHOT_TIMEOUT_MILLIS.get(), SNAPSHOT_MAX_EDGES.get(),
                SNAPSHOT_MAX_KEYS.get(), SNAPSHOT_MAX_ESTIMATED_BYTES.get(), SNAPSHOT_CACHE_ENTRIES.get());
    }

    public record PlannerPolicy(int workers, int queueCapacity, int timeoutMillis, long maxOperations,
                                int maxDepth, int checkpointInterval, int snapshotTimeoutMillis,
                                int snapshotMaxEdges, int snapshotMaxKeys, long snapshotMaxEstimatedBytes,
                                int snapshotCacheEntries) {
        public PlannerPolicy {
            if (workers < 1 || queueCapacity < 1 || timeoutMillis < 1 || maxOperations < 1
                    || maxDepth < 1 || checkpointInterval < 1 || snapshotTimeoutMillis < 1
                    || snapshotMaxEdges < 1 || snapshotMaxKeys < 1 || snapshotMaxEstimatedBytes < 1
                    || snapshotCacheEntries < 1) {
                throw new IllegalArgumentException("planner policy limits must be positive");
            }
        }
    }
}
