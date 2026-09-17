package com.raishxn.ufocore.gametest;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.mojang.logging.LogUtils;
import com.raishxn.ufocore.CoreConfig;
import com.raishxn.ufocore.neoforge.crafting.Ae2PlannerBridge;
import com.raishxn.ufocore.neoforge.crafting.CaptureSliceMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Soak of the cooperative capture with several real AE2 grids competing for one shared tick budget,
 * one of them served by a deliberately slow provider.
 *
 * <p>The slow grid is registered first, so a pump that stopped at the first exhausted budget would
 * starve the other two forever: the rotation is what lets every grid finish. The slow provider burns
 * a full tick budget inside a single uninterruptible adapter call, which is exactly the case the
 * accounting charges in full instead of hiding.
 *
 * <p>The second scenario measures the slice histograms against a real grid, so the numbers Gate O
 * needs are not only produced by the synthetic harness. It asserts a wide smoke ceiling rather than
 * the 2 ms target: a two-key game grid says nothing about what a large grid costs in game.
 */
@GameTestHolder("raishxcore_tests")
@PrefixGameTestTemplate(false)
public final class PlannerCaptureSoakGameTests {
    private static final String SOAK = "planner_soak_multi_grid";
    private static final String METRICS = "planner_soak_slice_metrics";
    /** Milliseconds one slow provider call burns: more than the whole shared tick budget. */
    private static final long SLOW_CALL_MILLIS = 4L;
    /** Smoke ceiling for a game grid this small; the 2 ms target needs a real session. */
    private static final long SMOKE_CEILING_NANOS = 50_000_000L;
    private static ModConfigSpec.IntValue sliceEdges;
    private static int originalSliceEdges;
    private static final List<PlannerGameTests.Fixture> FIXTURES = new ArrayList<>();
    private static final List<SlowProvider> PROVIDERS = new ArrayList<>();

    @AfterBatch(batch = SOAK)
    public static void afterSoak(ServerLevel level) {
        cleanup();
    }

    @AfterBatch(batch = METRICS)
    public static void afterMetrics(ServerLevel level) {
        cleanup();
    }

    @GameTest(template = "empty", batch = SOAK, timeoutTicks = 600)
    public static void threeGridsWithOneSlowProviderAllFinish(GameTestHelper helper) {
        forceSlicedCapture(helper);
        var slow = grid(helper, SLOW_CALL_MILLIS, pattern(helper, product(), 1), pattern(helper, product(), 2),
                pattern(helper, product(), 3), pattern(helper, product(), 4));
        var fastA = grid(helper, 0L, pattern(helper, product(), 1));
        var fastB = grid(helper, 0L, pattern(helper, product(), 1));
        List<Future<ICraftingPlan>> futures = List.of(
                slow.request(8, CalculationStrategy.REPORT_MISSING_ITEMS),
                fastA.request(8, CalculationStrategy.REPORT_MISSING_ITEMS),
                fastB.request(8, CalculationStrategy.REPORT_MISSING_ITEMS));

        awaitAll(helper, futures, plans -> {
            for (int index = 0; index < plans.size(); index++) {
                ICraftingPlan plan = plans.get(index);
                String label = "grid " + index;
                helper.assertTrue(!plan.simulation() && plan.usedItems().get(FIXTURES.get(index).raw) == 8,
                        label + " must be planned exactly by the Core, got: "
                                + plan.usedItems().get(FIXTURES.get(index).raw));
            }
            for (PlannerGameTests.Fixture fixture : FIXTURES) {
                Ae2PlannerBridge.Diagnostics diagnostics = fixture.diagnostics();
                helper.assertTrue(diagnostics.status().equals("COMPLETE"),
                        "every grid must be served by the Core, got: " + diagnostics.status());
                helper.assertTrue(diagnostics.pendingCaptures() == 0, "every capture must be released");
                helper.assertTrue(diagnostics.captureSlices() >= 2,
                        "every grid must be fed across ticks, got: " + diagnostics.captureSlices());
                helper.assertTrue(diagnostics.backpressureRejections() == 0,
                        "a slow neighbour must never push a grid into backpressure");
                helper.assertTrue(diagnostics.circuitRejections() == 0,
                        "a slow neighbour must never trip another grid's circuit breaker");
            }
            var slowProvider = PROVIDERS.getFirst();
            helper.assertTrue(slowProvider.calls >= 4,
                    "the slow provider must really have been asked once per pattern, got: "
                            + slowProvider.calls);
            var metrics = FIXTURES.getFirst().diagnostics().captureMetrics();
            helper.assertTrue(metrics.slices() >= 6,
                    "the shared metrics must see every grid's slices, got: " + metrics.slices());
            LogUtils.getLogger().info("R2.2 soak: {} slices over {} ticks, {} slow calls of {}ms,"
                            + " no starvation (slice p95 {}ms, tick p95 {}ms)", metrics.slices(),
                    metrics.ticks(), slowProvider.calls, SLOW_CALL_MILLIS, millis(metrics.sliceP95Nanos()),
                    millis(metrics.tickP95Nanos()));
            succeed(helper);
        });
    }

    /**
     * Two captures on one real grid, logged separately: the first one pays class loading and JIT for the
     * whole adapter path, so only the second says anything about the cost of a warm slice. The test
     * asserts a wide smoke ceiling and reports both readings; the 2 ms target needs a real session on a
     * large grid, which a two-key game grid cannot stand in for.
     */
    @GameTest(template = "empty", batch = METRICS, timeoutTicks = 600)
    public static void aRealGridReportsItsSlicePercentiles(GameTestHelper helper) {
        forceSlicedCapture(helper);
        var secondary = AEItemKey.of(Items.GLASS_PANE);
        var fixture = grid(helper, 0L, pattern(helper, product(), 1), pattern(helper, secondary, 1));

        awaitAll(helper, List.of(fixture.request(8, CalculationStrategy.REPORT_MISSING_ITEMS)), first -> {
            report(fixture, "cold capture");
            awaitAll(helper, List.of(fixture.request(secondary, 8, CalculationStrategy.REPORT_MISSING_ITEMS)),
                    warm -> {
                        var metrics = report(fixture, "warm capture");
                        helper.assertTrue(metrics.slices() >= 4,
                                "two real captures must record their slices, got: " + metrics.slices());
                        helper.assertTrue(metrics.ticks() >= 2, "a real grid must record its ticks");
                        helper.assertTrue(metrics.sliceP95Nanos() <= SMOKE_CEILING_NANOS,
                                "game slice p95 is pathological: " + millis(metrics.sliceP95Nanos()) + "ms");
                        helper.assertTrue(metrics.tickP95Nanos() <= SMOKE_CEILING_NANOS,
                                "game tick p95 is pathological: " + millis(metrics.tickP95Nanos()) + "ms");
                        helper.assertTrue(metrics.keyNanos() + metrics.patternNanos()
                                        <= metrics.sliceTotalNanos(),
                                "the phases must stay inside the slices they describe");
                        succeed(helper);
                    });
        });
    }

    private static CaptureSliceMetrics.Snapshot report(PlannerGameTests.Fixture fixture, String label) {
        var metrics = fixture.diagnostics().captureMetrics();
        LogUtils.getLogger().info("R2.2 metrics {}: {} slices, {} ticks, slice p50/p95/p99 = {}/{}/{}ms,"
                        + " tick p95 {}ms, phases key/pattern/publish = {}/{}/{}us, mean slice {}ms",
                label, metrics.slices(), metrics.ticks(), millis(metrics.sliceP50Nanos()),
                millis(metrics.sliceP95Nanos()), millis(metrics.sliceP99Nanos()),
                millis(metrics.tickP95Nanos()), micros(metrics.keyNanos()), micros(metrics.patternNanos()),
                micros(metrics.publishNanos()), millis(metrics.meanSliceNanos()));
        return metrics;
    }

    /** Builds one grid whose provider burns the given time inside a single adapter call. */
    private static PlannerGameTests.Fixture grid(GameTestHelper helper, long burnMillis,
                                                 IPatternDetails... patterns) {
        var fixture = new PlannerGameTests.Fixture(helper);
        var provider = new SlowProvider(burnMillis);
        provider.patterns.addAll(List.of(patterns));
        fixture.grid.getCraftingService().addGlobalCraftingProvider(provider);
        FIXTURES.add(fixture);
        PROVIDERS.add(provider);
        return fixture;
    }

    private static AEItemKey product() {
        return AEItemKey.of(Items.GLASS);
    }

    /** One exact processing pattern, built without a fixture so a grid can be created in one step. */
    private static IPatternDetails pattern(GameTestHelper helper, AEItemKey output, int rawAmount) {
        return PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                List.of(new GenericStack(AEItemKey.of(Items.COBBLESTONE), rawAmount)),
                List.of(new GenericStack(output, 1))), helper.getLevel());
    }

    private static void forceSlicedCapture(GameTestHelper helper) {
        sliceEdges = intSetting("planner.snapshot.sliceEdges", helper);
        originalSliceEdges = sliceEdges.get();
        sliceEdges.set(1);
        helper.assertTrue(CoreConfig.plannerPolicy().snapshotSliceEdges() == 1,
                "the slice edge limit is still read from the loaded config");
    }

    private static ModConfigSpec.IntValue intSetting(String path, GameTestHelper helper) {
        Object setting = CoreConfig.SPEC.getValues().get(path);
        if (!(setting instanceof ModConfigSpec.IntValue value)) {
            helper.fail("config " + path + " is not an IntValue");
            throw new IllegalStateException("missing config " + path);
        }
        return value;
    }

    private static void succeed(GameTestHelper helper) {
        cleanup();
        helper.succeed();
    }

    /** Releases every grid and restores the loaded config, whether the test passed or failed. */
    private static void cleanup() {
        if (sliceEdges != null) {
            sliceEdges.set(originalSliceEdges);
            sliceEdges = null;
        }
        for (int index = 0; index < FIXTURES.size(); index++) {
            FIXTURES.get(index).grid.getCraftingService().removeGlobalCraftingProvider(PROVIDERS.get(index));
            FIXTURES.get(index).close();
        }
        FIXTURES.clear();
        PROVIDERS.clear();
    }

    private static void awaitAll(GameTestHelper helper, List<Future<ICraftingPlan>> futures,
                                 Consumer<List<ICraftingPlan>> action) {
        helper.runAfterDelay(1, () -> {
            if (futures.stream().anyMatch(future -> !future.isDone())) {
                awaitAll(helper, futures, action);
                return;
            }
            try {
                List<ICraftingPlan> plans = new ArrayList<>(futures.size());
                for (Future<ICraftingPlan> future : futures) plans.add(future.get());
                action.accept(plans);
            } catch (Exception failure) {
                helper.fail("soak planning failed: " + failure);
            }
        });
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0D);
    }

    private static String micros(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000.0D);
    }

    /** A provider whose priority lookup blocks, standing in for a grid that answers slowly. */
    private static final class SlowProvider implements ICraftingProvider {
        private final List<IPatternDetails> patterns = new ArrayList<>();
        private final long burnMillis;
        private int calls;

        SlowProvider(long burnMillis) {
            this.burnMillis = burnMillis;
        }

        @Override
        public List<IPatternDetails> getAvailablePatterns() {
            return List.copyOf(patterns);
        }

        @Override
        public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
            return false;
        }

        @Override
        public boolean isBusy() {
            return false;
        }

        @Override
        public int getPatternPriority() {
            calls++;
            if (burnMillis > 0L) {
                try {
                    Thread.sleep(burnMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return 0;
        }
    }
}
