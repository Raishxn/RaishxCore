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
    private static final long TIMEOUT_NANOS = Duration.ofSeconds(2).toNanos();
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                Thread worker = new Thread(runnable, "RaishxCore-planner");
                worker.setDaemon(true);
                return worker;
            });
    static { WORKERS.allowCoreThreadTimeOut(true); }
    private final Map<AEKey, Ae2PlanningSnapshot> snapshots = new LinkedHashMap<>(16, .75F, true);
    private long revision = -1;
    private long hits;
    private long misses;
    private volatile PlanningResult.Diagnostics lastDiagnostics;
    private volatile String lastStatus = "idle";

    @Nullable public Future<ICraftingPlan> begin(Level level, IGrid grid,
                                                  ICraftingSimulationRequester requester, AEKey target,
                                                  long amount, CalculationStrategy strategy, long gridRevision) {
        if (level == null || level.isClientSide || level.getServer() == null || !level.getServer().isSameThread()
                || requester == null || requester.getActionSource() == null || amount <= 0) return null;
        var node = requester.getGridNode();
        if (node == null || node.getGrid() != grid) return null;
        Ae2PlanningSnapshot snapshot;
        try {
            if (revision != gridRevision) { snapshots.clear(); revision = gridRevision; }
            snapshot = snapshots.get(target);
            if (snapshot == null) {
                snapshot = Ae2PlanningSnapshot.capture(level, grid.getCraftingService(), target, revision);
                if (snapshots.size() >= 16) snapshots.remove(snapshots.keySet().iterator().next());
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
        try {
            return WORKERS.submit(() -> {
                try {
                    return calculate(captured, capturedStock, amount, strategy);
                } catch (java.util.concurrent.CancellationException cancelled) {
                    lastStatus = "cancelled";
                    throw cancelled;
                } catch (TimeoutException deadline) {
                    lastStatus = "timeout";
                    throw new IllegalStateException("RaishxCore planning deadline exceeded", deadline);
                } catch (RuntimeException unexpected) {
                    // AE2 menus surface the thrown cause to the player; this log is the
                    // structured trace for the addon maintainers.
                    lastStatus = "failed: " + unexpected;
                    LOG.warn("RaishxCore planning failed unexpectedly.", unexpected);
                    throw unexpected;
                }
            });
        } catch (RejectedExecutionException busy) {
            lastStatus = "ae2: planner queue full";
            return null;
        }
    }

    private ICraftingPlan calculate(Ae2PlanningSnapshot snapshot, Map<String, UfoAmount> stock,
                                     long amount, CalculationStrategy strategy) throws TimeoutException {
        long started = System.nanoTime();
        var full = attempt(snapshot, stock, amount, started);
        if (full.status() == PlanningResult.Status.COMPLETE) return adapt(snapshot, full);
        if (strategy == CalculationStrategy.CRAFT_LESS) {
            long successful = 0;
            PlanningResult<String> best = null;
            for (long increment = Long.highestOneBit(amount); increment > 0; increment /= 2) {
                if (increment >= amount - successful) continue;
                long test = successful + increment;
                var candidate = attempt(snapshot, stock, test, started);
                if (candidate.status() == PlanningResult.Status.COMPLETE) { successful = test; best = candidate; }
            }
            if (best != null) return adapt(snapshot, best);
        }
        return adapt(snapshot, full);
    }

    private PlanningResult<String> attempt(Ae2PlanningSnapshot snapshot, Map<String, UfoAmount> stock,
                                           long amount, long started) throws TimeoutException {
        long remaining = TIMEOUT_NANOS - (System.nanoTime() - started);
        if (remaining <= 0) throw new TimeoutException("RaishxCore planning deadline");
        var limits = new PlanningLimits(10_000_000, 100_000, Duration.ofNanos(remaining), 128);
        var result = new IterativeCraftingPlanner<String>().plan(snapshot.graph(),
                new PlanningRequest<>(snapshot.target(), UfoAmount.of(amount), stock, limits,
                        PlanningCancellation.NEVER));
        lastDiagnostics = result.diagnostics();
        lastStatus = result.status().name();
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
        lastStatus = "disabled";
        lastDiagnostics = null;
    }

    public Diagnostics diagnostics() { return new Diagnostics(revision, hits, misses, lastStatus, lastDiagnostics); }
    public record Diagnostics(long revision, long cacheHits, long cacheMisses, String status,
                               @Nullable PlanningResult.Diagnostics lastPlan) {}
}
