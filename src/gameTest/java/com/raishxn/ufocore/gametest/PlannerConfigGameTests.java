package com.raishxn.ufocore.gametest;

import com.mojang.logging.LogUtils;
import com.raishxn.ufocore.CoreConfig;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Isolated batch: changing a COMMON config must not race other grids' requests. */
@GameTestHolder("raishxcore_tests")
@PrefixGameTestTemplate(false)
public final class PlannerConfigGameTests {
    private static final String BATCH = "planner_config";
    private static ModConfigSpec.BooleanValue enabled;
    private static boolean original;
    private static PlannerGameTests.Fixture fixture;

    @AfterBatch(batch = BATCH)
    public static void restoreConfigAndGrid(ServerLevel level) {
        if (enabled != null) { enabled.set(original); enabled = null; }
        if (fixture != null) { fixture.close(); fixture = null; }
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 600)
    public static void loadedConfigDisablesPlannerThenReenablesOnSameGrid(GameTestHelper helper) {
        helper.assertTrue(CoreConfig.SPEC.isLoaded(), "registered COMMON config is not loaded");
        helper.assertTrue(CoreConfig.plannerEnabledTestOverride == null, "test override must not drive this scenario");
        var policy = CoreConfig.plannerPolicy();
        helper.assertTrue(policy.workers() >= 1 && policy.queueCapacity() >= 1
                        && policy.timeoutMillis() >= 10 && policy.maxOperations() >= 1_000
                        && policy.snapshotMaxEstimatedBytes() >= 1024L * 1024,
                "loaded planner policy is outside its declared safety bounds");
        Object setting = CoreConfig.SPEC.getValues().get("planner.enabled");
        helper.assertTrue(setting instanceof ModConfigSpec.BooleanValue, "planner.enabled is not a BooleanValue");
        enabled = (ModConfigSpec.BooleanValue) setting;
        original = enabled.get();
        fixture = new PlannerGameTests.Fixture(helper);
        fixture.addPattern(fixture.pattern(1));
        fixture.register();
        // Change the loaded spec, never the override or the user's on-disk config.
        enabled.set(false);
        helper.assertTrue(!CoreConfig.isPlannerEnabled(), "loaded config did not disable the planner");
        await(helper, fixture.request(8, CalculationStrategy.REPORT_MISSING_ITEMS), vanilla -> {
            helper.assertTrue(!vanilla.simulation() && vanilla.usedItems().get(fixture.raw) == 8,
                    "AE2 disabled path did not produce the expected executable plan");
            helper.assertTrue(fixture.diagnostics().status().equals("disabled"), "disabled diagnostic was lost");
            helper.assertTrue(fixture.diagnostics().lastPlan() == null && fixture.diagnostics().cacheMisses() == 0,
                    "Core calculated or captured a graph while disabled");
            enabled.set(true);
            helper.assertTrue(CoreConfig.isPlannerEnabled(), "loaded config did not reenable the planner");
            await(helper, fixture.request(8, CalculationStrategy.REPORT_MISSING_ITEMS), core -> {
                helper.assertTrue(!core.simulation() && core.usedItems().get(fixture.raw) == 8,
                        "Core did not recover on the same grid after reenabling");
                helper.assertTrue(fixture.diagnostics().status().equals("COMPLETE")
                                && fixture.diagnostics().lastPlan() != null && fixture.diagnostics().cacheMisses() == 1,
                        "reenabled request did not reach the Core planner");
                LogUtils.getLogger().info("Loaded COMMON planner config: AE2 disabled -> Core COMPLETE on same grid; no test override");
                restoreConfigAndGrid(helper.getLevel());
                helper.succeed();
            });
        });
    }

    private static void await(GameTestHelper helper, Future<ICraftingPlan> future, Consumer<ICraftingPlan> action) {
        helper.runAfterDelay(1, () -> {
            if (!future.isDone()) { await(helper, future, action); return; }
            try { action.accept(future.get()); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                helper.fail("config planning interrupted: " + interrupted);
            } catch (java.util.concurrent.ExecutionException failure) {
                helper.fail("config planning failed: " + failure.getCause());
            }
        });
    }
}
