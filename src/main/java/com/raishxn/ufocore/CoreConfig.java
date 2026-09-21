package com.raishxn.ufocore;

import com.raishxn.ufocore.api.crafting.planner.MissingWeightPolicy;
import java.util.List;
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
            2, 32, 2_000, 8, 10_000_000L, 100_000, 128,
            50, 100_000, 25_000, 64L * 1024 * 1024, 16, 128L * 1024 * 1024, 2, 512, 4, 8,
            4, 3, 10_000);

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
    private static final ModConfigSpec.IntValue PLANNER_EXACT_SEARCH_CHOICE_LIMIT = BUILDER
            .comment("Producer alternatives beyond the first that switch RaishxCore from exact",
                    "reversible search to its deterministic fast planner. Both modes are internal;",
                    "Eight keeps real multi-route graphs out of exponential proof searches while",
                    "leaving small shared-stock conflicts to the exact planner.",
                    "this never delegates a request to AE2, Thunderbolt or Tianshu.")
            .defineInRange("planner.exactSearchChoiceLimit", DEFAULT_POLICY.exactSearchChoiceLimit(),
                    1, 1_000_000);
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
    private static final ModConfigSpec.LongValue SNAPSHOT_CACHE_BYTES = BUILDER
            .comment("Conservative estimated heap ceiling for the whole per-grid snapshot cache.",
                    "Snapshots are evicted least-recently-used until both the entry count and this ceiling fit.")
            .defineInRange("planner.snapshot.cacheBytes", DEFAULT_POLICY.snapshotCacheBytes(),
                    1024L * 1024, 1024L * 1024 * 1024);
    private static final ModConfigSpec.IntValue SNAPSHOT_SLICE_MILLIS = BUILDER
            .comment("Main-thread time one grid may spend on one capture slice before yielding the tick.",
                    "The capture continues on later ticks; it is never handed to AE2 just because a slice ended.")
            .defineInRange("planner.snapshot.sliceMillis", DEFAULT_POLICY.snapshotSliceMillis(), 1, 50);
    private static final ModConfigSpec.IntValue SNAPSHOT_SLICE_EDGES = BUILDER
            .comment("Pattern/input/output edges one capture slice may consume.",
                    "Bounds a slice deterministically, independent of wall-clock resolution.")
            .defineInRange("planner.snapshot.sliceEdges", DEFAULT_POLICY.snapshotSliceEdges(), 1, 1_000_000);
    private static final ModConfigSpec.IntValue SNAPSHOT_TICK_BUDGET_MILLIS = BUILDER
            .comment("Shared capture budget for every grid in one server tick.",
                    "Applied when the budget is first used; restart to change.")
            .defineInRange("planner.snapshot.tickBudgetMillis", DEFAULT_POLICY.snapshotTickBudgetMillis(), 1, 50);
    private static final ModConfigSpec.IntValue MAX_PENDING_CAPTURES = BUILDER
            .comment("Captures one grid may keep in progress across ticks before further requests wait for AE2.")
            .defineInRange("planner.snapshot.maxPendingCaptures", DEFAULT_POLICY.maxPendingCaptures(), 1, 256);
    private static final ModConfigSpec.IntValue MAX_IN_FLIGHT_PER_GRID = BUILDER
            .comment("Maximum distinct calculations in flight for one AE2 grid.",
                    "Equivalent requests still share an existing calculation at this limit.")
            .defineInRange("planner.maxInFlightPerGrid", DEFAULT_POLICY.maxInFlightPerGrid(), 1, 256);
    private static final ModConfigSpec.IntValue CIRCUIT_FAILURE_THRESHOLD = BUILDER
            .comment("Consecutive planner failures that open one grid's circuit breaker.")
            .defineInRange("planner.circuitBreaker.failureThreshold",
                    DEFAULT_POLICY.circuitFailureThreshold(), 1, 100);
    private static final ModConfigSpec.IntValue CIRCUIT_COOLDOWN_MILLIS = BUILDER
            .comment("Time an unhealthy grid delegates directly to AE2 before one recovery probe.")
            .defineInRange("planner.circuitBreaker.cooldownMillis",
                    DEFAULT_POLICY.circuitCooldownMillis(), 100, 300_000);
    private static final ModConfigSpec.IntValue MISSING_WEIGHT_MULTIPLIER = BUILDER
            .comment("Scales every missing weight a consumer registered through the Core planner API.",
                    "A value above one makes each declared shortage that many times more expensive, so a",
                    "pack can disagree with an addon without recompiling either. One leaves weights untouched.")
            .defineInRange("planner.missingWeights.multiplier", 1, 1, 1_000_000);
    private static final ModConfigSpec.ConfigValue<List<? extends String>> MISSING_WEIGHT_OVERRIDES = BUILDER
            .comment("Per-key missing-weight multipliers, written as \"<serialized-key>=<multiplier>\".",
                    "An entry replaces planner.missingWeights.multiplier for that key, and a key no consumer",
                    "registered can still be weighted this way.")
            .defineListAllowEmpty("planner.missingWeights.overrides", List.of(), () -> "example:key=2",
                    MissingWeightPolicy::isValidOverride);

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
                PLANNER_TIMEOUT_MILLIS.get(), PLANNER_EXACT_SEARCH_CHOICE_LIMIT.get(),
                PLANNER_MAX_OPERATIONS.get(), PLANNER_MAX_DEPTH.get(),
                PLANNER_CHECKPOINT_INTERVAL.get(), SNAPSHOT_TIMEOUT_MILLIS.get(), SNAPSHOT_MAX_EDGES.get(),
                SNAPSHOT_MAX_KEYS.get(), SNAPSHOT_MAX_ESTIMATED_BYTES.get(), SNAPSHOT_CACHE_ENTRIES.get(),
                SNAPSHOT_CACHE_BYTES.get(), SNAPSHOT_SLICE_MILLIS.get(), SNAPSHOT_SLICE_EDGES.get(),
                SNAPSHOT_TICK_BUDGET_MILLIS.get(), MAX_PENDING_CAPTURES.get(),
                MAX_IN_FLIGHT_PER_GRID.get(), CIRCUIT_FAILURE_THRESHOLD.get(), CIRCUIT_COOLDOWN_MILLIS.get());
    }

    /**
     * Missing-weight policy read from {@code core.toml}. Before the file loads (unit tests, early
     * startup) it is the unconfigured policy, which is exactly the path taken before weights existed.
     */
    public static MissingWeightPolicy missingWeightPolicy() {
        if (!SPEC.isLoaded()) return MissingWeightPolicy.NONE;
        return new MissingWeightPolicy(MISSING_WEIGHT_MULTIPLIER.get(),
                MissingWeightPolicy.parseOverrides(MISSING_WEIGHT_OVERRIDES.get()));
    }

    public record PlannerPolicy(int workers, int queueCapacity, int timeoutMillis, int exactSearchChoiceLimit,
                                long maxOperations,
                                int maxDepth, int checkpointInterval, int snapshotTimeoutMillis,
                                int snapshotMaxEdges, int snapshotMaxKeys, long snapshotMaxEstimatedBytes,
                                int snapshotCacheEntries, long snapshotCacheBytes, int snapshotSliceMillis,
                                int snapshotSliceEdges, int snapshotTickBudgetMillis, int maxPendingCaptures,
                                int maxInFlightPerGrid, int circuitFailureThreshold, int circuitCooldownMillis) {
        public PlannerPolicy {
            if (workers < 1 || queueCapacity < 1 || timeoutMillis < 1 || exactSearchChoiceLimit < 1
                    || maxOperations < 1
                    || maxDepth < 1 || checkpointInterval < 1 || snapshotTimeoutMillis < 1
                    || snapshotMaxEdges < 1 || snapshotMaxKeys < 1 || snapshotMaxEstimatedBytes < 1
                    || snapshotCacheEntries < 1 || snapshotCacheBytes < 1 || snapshotSliceMillis < 1
                    || snapshotSliceEdges < 1 || snapshotTickBudgetMillis < 1 || maxPendingCaptures < 1
                    || maxInFlightPerGrid < 1 || circuitFailureThreshold < 1
                    || circuitCooldownMillis < 1) {
                throw new IllegalArgumentException("planner policy limits must be positive");
            }
        }
    }
}
