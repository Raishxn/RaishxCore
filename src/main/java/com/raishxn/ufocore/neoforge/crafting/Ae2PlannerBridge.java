package com.raishxn.ufocore.neoforge.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import com.mojang.logging.LogUtils;
import com.raishxn.ufocore.CoreConfig;
import com.raishxn.ufocore.api.amount.UfoAmount;
import com.raishxn.ufocore.api.crafting.planner.IterativeCraftingPlanner;
import com.raishxn.ufocore.api.crafting.planner.PlanningCancellation;
import com.raishxn.ufocore.api.crafting.planner.PlanningLimits;
import com.raishxn.ufocore.api.crafting.planner.PlanningRequest;
import com.raishxn.ufocore.api.crafting.planner.PlanningResult;
import java.math.BigInteger;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/** Per-grid revision cache and bounded worker queue. No world/storage calls run on a worker. */
public final class Ae2PlannerBridge {
    private static final Logger LOG = LogUtils.getLogger();
    private static final java.util.Set<Ae2PlannerBridge> ACTIVE = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers;
    private final InFlightRequestCoordinator<RequestKey, ICraftingPlan> requests;
    private final Map<AEKey, Ae2PlanningSnapshot> snapshots = new LinkedHashMap<>(16, .75F, true);
    private volatile long revision = -1;
    private volatile long statusGeneration;
    private long hits;
    private long misses;
    private volatile PlanningResult.Diagnostics lastDiagnostics;
    private volatile String lastStatus = "idle";

    public Ae2PlannerBridge() {
        workers = WorkerPool.INSTANCE;
        requests = new InFlightRequestCoordinator<>(workers);
        ACTIVE.add(this);
    }

    @Nullable public Future<ICraftingPlan> begin(Level level, IGrid grid,
                                                  ICraftingSimulationRequester requester, AEKey target,
                                                  long amount, CalculationStrategy strategy, long gridRevision) {
        if (level == null || level.isClientSide || level.getServer() == null || !level.getServer().isSameThread()
                || requester == null || requester.getActionSource() == null || amount <= 0) return null;
        var node = requester.getGridNode();
        if (node == null || node.getGrid() != grid) return null;
        CoreConfig.PlannerPolicy policy = CoreConfig.plannerPolicy();
        Ae2PlanningSnapshot snapshot;
        try {
            if (revision != gridRevision) {
                revision = gridRevision;
                statusGeneration++;
                snapshots.clear();
                requests.cancelAll();
            }
            snapshot = snapshots.get(target);
            if (snapshot == null) {
                var captureLimits = new Ae2PlanningSnapshot.CaptureLimits(
                        Duration.ofMillis(policy.snapshotTimeoutMillis()), policy.snapshotMaxEdges(),
                        policy.snapshotMaxKeys(), policy.snapshotMaxEstimatedBytes());
                snapshot = Ae2PlanningSnapshot.capture(
                        level, grid.getCraftingService(), target, revision, captureLimits);
                if (snapshots.size() >= policy.snapshotCacheEntries()) {
                    snapshots.remove(snapshots.keySet().iterator().next());
                }
                snapshots.put(target, snapshot);
                misses++;
            } else hits++;
        } catch (Ae2PlanningSnapshot.Declined declined) {
            lastStatus = "ae2: " + declined.getMessage();
            return null;
        }

        // AE2 also captures fresh inventory for player requests and cached inventory for machines.
        KeyCounter inventory = requester.getActionSource().player().isPresent()
                ? grid.getStorageService().getInventory().getAvailableStacks()
                : grid.getStorageService().getCachedInventory();
        Map<String, UfoAmount> stock = new HashMap<>();
        for (var entry : inventory) {
            String key = snapshot.keyIds().get(entry.getKey());
            if (key != null && !key.equals(snapshot.target()) && entry.getLongValue() > 0) {
                stock.put(key, UfoAmount.of(entry.getLongValue()));
            }
        }
        var captured = snapshot;
        var capturedStock = Map.copyOf(stock);
        long generation = statusGeneration;
        Object owner = requester.getActionSource().player()
                .<Object>map(player -> player.getUUID())
                .orElse(requester);
        try {
            var requestKey = new RequestKey(revision, snapshot.target(), amount, strategy, capturedStock, policy);
            return requests.submit(requestKey, owner, PlanningTask.classified(
                    () -> calculate(captured, capturedStock, amount, strategy, generation, policy),
                    status -> recordStatus(generation, status),
                    unexpected -> LOG.warn("RaishxCore planning failed unexpectedly.", unexpected)));
        } catch (RejectedExecutionException busy) {
            lastStatus = "ae2: planner queue full";
            return null;
        }
    }

    private ICraftingPlan calculate(Ae2PlanningSnapshot snapshot, Map<String, UfoAmount> stock,
                                     long amount, CalculationStrategy strategy,
                                     long generation, CoreConfig.PlannerPolicy policy) throws TimeoutException {
        long started = System.nanoTime();
        var full = attempt(snapshot, stock, amount, started, generation, policy);
        if (full.status() == PlanningResult.Status.COMPLETE) return adapt(snapshot, full);
        if (strategy == CalculationStrategy.CRAFT_LESS) {
            long successful = 0;
            PlanningResult<String> best = null;
            for (long increment = Long.highestOneBit(amount); increment > 0; increment /= 2) {
                if (increment >= amount - successful) continue;
                long test = successful + increment;
                var candidate = attempt(snapshot, stock, test, started, generation, policy);
                if (candidate.status() == PlanningResult.Status.COMPLETE) { successful = test; best = candidate; }
            }
            if (best != null) return adapt(snapshot, best);
        }
        return adapt(snapshot, full);
    }

    private PlanningResult<String> attempt(Ae2PlanningSnapshot snapshot, Map<String, UfoAmount> stock,
                                           long amount, long started, long generation,
                                           CoreConfig.PlannerPolicy policy) throws TimeoutException {
        long timeoutNanos = Duration.ofMillis(policy.timeoutMillis()).toNanos();
        long remaining = timeoutNanos - (System.nanoTime() - started);
        if (remaining <= 0) throw new TimeoutException("RaishxCore planning deadline");
        var limits = new PlanningLimits(policy.maxOperations(), policy.maxDepth(),
                Duration.ofNanos(remaining), policy.checkpointInterval());
        var result = new IterativeCraftingPlanner<String>().plan(snapshot.graph(),
                new PlanningRequest<>(snapshot.target(), UfoAmount.of(amount), stock, limits,
                        PlanningCancellation.NEVER));
        if (statusGeneration == generation) {
            lastDiagnostics = result.diagnostics();
            lastStatus = result.status().name();
        }
        switch (result.status()) {
            case COMPLETE, MISSING_INGREDIENTS -> { return result; }
            case CANCELLED -> throw new java.util.concurrent.CancellationException("RaishxCore planning cancelled");
            default -> throw new TimeoutException("RaishxCore planning stopped: " + result.status());
        }
    }

    static CraftingPlan adapt(Ae2PlanningSnapshot snapshot, PlanningResult<String> result) {
        if (result.status() != PlanningResult.Status.COMPLETE
                && result.status() != PlanningResult.Status.MISSING_INGREDIENTS) {
            throw new IllegalArgumentException("cannot adapt interrupted planning");
        }
        var plan = result.plan();
        KeyCounter used = new KeyCounter();
        plan.extractedFromInventory().forEach((id, amount) ->
                used.add(snapshot.keys().get(id), amount.longValueExact()));
        KeyCounter missing = new KeyCounter();
        plan.missing().forEach((id, amount) -> missing.add(snapshot.keys().get(id), amount.longValueExact()));
        Map<IPatternDetails, Long> times = new HashMap<>();
        Map<String, UfoAmount> demand = new HashMap<>();
        Map<String, UfoAmount> produced = new HashMap<>();
        demand.put(plan.target(), plan.requested());
        BigInteger bytes = BigInteger.valueOf(8);
        for (var entry : plan.patternExecutions().entrySet()) {
            times.merge(snapshot.patterns().get(entry.getKey().id()), entry.getValue().longValueExact(), Math::addExact);
            bytes = bytes.add(entry.getValue().asBigInteger())
                    .add(BigInteger.valueOf(8L * (1 + entry.getKey().inputs().size())));
            entry.getKey().inputs().forEach((key, value) ->
                    demand.merge(key, value.multiply(entry.getValue().asBigInteger()), UfoAmount::add));
            entry.getKey().outputs().forEach((key, value) ->
                    produced.merge(key, value.multiply(entry.getValue().asBigInteger()), UfoAmount::add));
        }
        // Keep all AE2 counters representable; no silent saturation of required resources or jobs.
        for (var amount : produced.values()) amount.longValueExact();
        for (var entry : demand.entrySet()) {
            entry.getValue().longValueExact();
            BigInteger divisor = BigInteger.valueOf(snapshot.amountsPerByte().get(entry.getKey()));
            BigInteger numerator = entry.getValue().asBigInteger().multiply(BigInteger.valueOf(8));
            bytes = bytes.add(numerator.add(divisor).subtract(BigInteger.ONE).divide(divisor));
        }
        return new CraftingPlan(new GenericStack(snapshot.keys().get(plan.target()), plan.requested().longValueExact()),
                bytes.longValueExact(), !plan.complete(), snapshot.multiplePaths(), used, new KeyCounter(), missing,
                Map.copyOf(times));
    }

    /** Records that this request was declined by the config kill-switch. */
    public void recordDisabled() {
        invalidate("disabled");
    }

    public void invalidate(String status) {
        statusGeneration++;
        requests.cancelAll();
        snapshots.clear();
        lastStatus = status;
        lastDiagnostics = null;
    }

    public void close() {
        invalidate("grid closed");
        ACTIVE.remove(this);
    }

    public static void cancelForPlayer(java.util.UUID playerId) {
        for (Ae2PlannerBridge bridge : ACTIVE) bridge.requests.cancelOwner(playerId);
    }

    public static void cancelForServerStop() {
        for (Ae2PlannerBridge bridge : ACTIVE) bridge.invalidate("server stopping");
        ACTIVE.clear();
    }

    private void recordStatus(long generation, String status) {
        if (statusGeneration == generation) lastStatus = status;
    }

    public Diagnostics diagnostics() {
        var requestStats = requests.stats();
        return new Diagnostics(revision, hits, misses, lastStatus, lastDiagnostics,
                requestStats.inFlight(), requestStats.submitted(), requestStats.deduplicated(),
                requestStats.cancelled(), workers.getActiveCount(), workers.getQueue().size());
    }
    public record Diagnostics(long revision, long cacheHits, long cacheMisses, String status,
                              @Nullable PlanningResult.Diagnostics lastPlan, int inFlightRequests,
                              long submittedRequests, long deduplicatedRequests, long cancelledRequests,
                              int activeWorkers, int queuedRequests) {}

    private record RequestKey(long revision, String target, long amount, CalculationStrategy strategy,
                              Map<String, UfoAmount> inventory, CoreConfig.PlannerPolicy policy) {}

    private static final class WorkerPool {
        private static final ThreadPoolExecutor INSTANCE = create();

        private static ThreadPoolExecutor create() {
            CoreConfig.PlannerPolicy policy = CoreConfig.plannerPolicy();
            var executor = new ThreadPoolExecutor(policy.workers(), policy.workers(), 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(policy.queueCapacity()), runnable -> {
                        Thread worker = new Thread(runnable, "RaishxCore-planner");
                        worker.setDaemon(true);
                        return worker;
                    });
            executor.allowCoreThreadTimeOut(true);
            return executor;
        }
    }
}
