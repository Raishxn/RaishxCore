package com.raishxn.ufocore.gametest;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import com.mojang.logging.LogUtils;
import com.raishxn.ufocore.CoreConfig;
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
 * Cooperative capture against a real AE2 grid. A one-edge slice forces the capture to span ticks, so
 * these tests prove that a deferred request is still planned exactly, that a grid mutation during the
 * capture hands the request to AE2 instead of planning a stale graph, and that the per-grid capture
 * cap degrades to AE2 rather than queueing without bound.
 *
 * <p>Each scenario owns its own batch because a batch is the isolation unit for grid and config state.
 */
@GameTestHolder("raishxcore_tests")
@PrefixGameTestTemplate(false)
public final class PlannerCooperativeCaptureGameTests {
    private static final String SLICED = "planner_capture_sliced";
    private static final String MUTATION = "planner_capture_mutation";
    private static final String CAP = "planner_capture_cap";
    private static ModConfigSpec.IntValue sliceEdges;
    private static ModConfigSpec.IntValue pendingCaptures;
    private static int originalSliceEdges;
    private static int originalPendingCaptures;
    private static PlannerGameTests.Fixture fixture;

    @AfterBatch(batch = SLICED)
    public static void afterSliced(ServerLevel level) {
        cleanup();
    }

    @AfterBatch(batch = MUTATION)
    public static void afterMutation(ServerLevel level) {
        cleanup();
    }

    @AfterBatch(batch = CAP)
    public static void afterCap(ServerLevel level) {
        cleanup();
    }

    @GameTest(template = "empty", batch = SLICED, timeoutTicks = 600)
    public static void aCaptureThatSpansTicksStillProducesTheExactPlan(GameTestHelper helper) {
        fixture = new PlannerGameTests.Fixture(helper);
        fixture.addPattern(fixture.pattern(1));
        fixture.register();
        forceSlicedCapture(helper);

        var deferred = fixture.request(8, CalculationStrategy.REPORT_MISSING_ITEMS);

        helper.assertTrue(!deferred.isDone(), "a one-edge slice must defer a multi-key capture");
        helper.assertTrue(fixture.diagnostics().pendingCaptures() == 1, "the capture must be tracked as pending");
        helper.assertTrue(fixture.diagnostics().deferredRequests() == 1, "the deferral must be counted");
        helper.assertTrue(fixture.diagnostics().status().equals("capture deferred"),
                "diagnostics must say why the request is not done yet");
        await(helper, deferred, plan -> {
            helper.assertTrue(!plan.simulation() && plan.usedItems().get(fixture.raw) == 8,
                    "the deferred request must be planned exactly, got: " + plan.usedItems().get(fixture.raw));
            helper.assertTrue(plan.patternTimes().values().stream().mapToLong(Long::longValue).sum() == 8,
                    "the deferred request must keep its craft count");
            helper.assertTrue(fixture.diagnostics().pendingCaptures() == 0, "the capture must be released");
            helper.assertTrue(fixture.diagnostics().captureSlices() >= 2, "the capture must have been sliced");
            helper.assertTrue(fixture.diagnostics().status().equals("COMPLETE"),
                    "the plan must come from the Core planner, got: " + fixture.diagnostics().status());
            helper.assertTrue(fixture.diagnostics().cacheMisses() == 1 && fixture.diagnostics().capturedPatterns() == 1,
                    "the completed capture must be published once");
            LogUtils.getLogger().info("Cooperative capture: deferred across ticks, exact plan, core status");
            succeed(helper);
        });
    }

    @GameTest(template = "empty", batch = MUTATION, timeoutTicks = 600)
    public static void aGridMutationDuringCaptureHandsTheRequestToAe2(GameTestHelper helper) {
        fixture = new PlannerGameTests.Fixture(helper);
        fixture.addPattern(fixture.pattern(1));
        fixture.register();
        forceSlicedCapture(helper);

        var deferred = fixture.request(8, CalculationStrategy.REPORT_MISSING_ITEMS);
        helper.assertTrue(fixture.diagnostics().pendingCaptures() == 1, "the capture must still be open");

        // Mutate the grid while the capture is open, without starting a new request.
        fixture.replacePatterns(fixture.pattern(2));

        await(helper, deferred, plan -> {
            helper.assertTrue(!plan.simulation() && plan.usedItems().get(fixture.raw) == 16,
                    "the stale capture must be served by AE2 against the new grid, got: "
                            + plan.usedItems().get(fixture.raw));
            helper.assertTrue(fixture.diagnostics().status().startsWith("ae2 fallback"),
                    "diagnostics must say the attempt was discarded, got: " + fixture.diagnostics().status());
            helper.assertTrue(fixture.diagnostics().pendingCaptures() == 0, "the stale capture must be dropped");
            helper.assertTrue(fixture.diagnostics().cachedSnapshots() == 0,
                    "a capture discarded mid-flight must not be cached as a snapshot");
            LogUtils.getLogger().info("Cooperative capture: grid mutation cancelled the attempt, AE2 served it");
            succeed(helper);
        });
    }

    @GameTest(template = "empty", batch = CAP, timeoutTicks = 600)
    public static void aSecondTargetIsServedByAe2WhenTheCaptureCapIsReached(GameTestHelper helper) {
        var secondary = AEItemKey.of(Items.GLASS_PANE);
        fixture = new PlannerGameTests.Fixture(helper);
        fixture.addPattern(fixture.pattern(1));
        fixture.addPattern(fixture.pattern(1, secondary));
        fixture.register();
        forceSlicedCapture(helper);
        pendingCaptures = intSetting("planner.snapshot.maxPendingCaptures", helper);
        originalPendingCaptures = pendingCaptures.get();
        pendingCaptures.set(1);

        var deferred = fixture.request(8, CalculationStrategy.REPORT_MISSING_ITEMS);
        helper.assertTrue(fixture.diagnostics().pendingCaptures() == 1, "the first capture must be open");

        var fellBack = fixture.request(secondary, 8, CalculationStrategy.REPORT_MISSING_ITEMS);
        await(helper, fellBack, plan -> {
            helper.assertTrue(!plan.simulation() && plan.usedItems().get(fixture.raw) == 8,
                    "AE2 must serve the request that exceeded the capture cap");
            helper.assertTrue(fixture.diagnostics().backpressureRejections() >= 1,
                    "the cap must be recorded as backpressure, not as a silent success");
            await(helper, deferred, first -> {
                helper.assertTrue(!first.simulation() && first.usedItems().get(fixture.raw) == 8,
                        "the open capture must still finish on a later tick");
                helper.assertTrue(fixture.diagnostics().pendingCaptures() == 0, "the capture must be released");
                LogUtils.getLogger().info("Cooperative capture: second target served by AE2 at the cap");
                succeed(helper);
            });
        });
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

    /** Restores the loaded config and releases the grid, whether the test passed or failed. */
    private static void cleanup() {
        if (sliceEdges != null) {
            sliceEdges.set(originalSliceEdges);
            sliceEdges = null;
        }
        if (pendingCaptures != null) {
            pendingCaptures.set(originalPendingCaptures);
            pendingCaptures = null;
        }
        if (fixture != null) {
            fixture.close();
            fixture = null;
        }
    }

    private static void await(GameTestHelper helper, Future<ICraftingPlan> future, Consumer<ICraftingPlan> action) {
        helper.runAfterDelay(1, () -> {
            if (!future.isDone()) { await(helper, future, action); return; }
            try { action.accept(future.get()); }
            catch (Exception failure) { helper.fail("cooperative planning failed: " + failure); }
        });
    }
}
