package com.raishxn.ufocore.gametest;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Degradation path of the AE2 planner integration: when the Core declines a graph
 * it cannot represent, AE2's own planner must still serve the request and the
 * diagnostics must say so instead of leaving a silent gap.
 */
@GameTestHolder("raishxcore_tests")
@PrefixGameTestTemplate(false)
public final class PlannerDegradationGameTests {
    @GameTest(template = "empty", timeoutTicks = 240)
    @SuppressWarnings("unchecked")
    public static void declinedGraphFallsBackToVanillaAe2Planner(GameTestHelper helper) {
        try {
            runDeclineScenario(helper);
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().error("decline scenario setup failure", t);
            helper.fail("setup failed: " + t);
        }
    }

    private static void runDeclineScenario(GameTestHelper helper) {
        var fixture = new PlannerGameTests.Fixture(helper);
        // Crafting patterns with substitution are outside the Core graph contract (it
        // only accepts graphs of exact inputs); AE2's built-in planner handles them natively.
        RecipeHolder<CraftingRecipe> stoneBricks = (RecipeHolder<CraftingRecipe>) helper.getLevel()
                .getServer().getRecipeManager()
                .byKey(ResourceLocation.withDefaultNamespace("stone_bricks"))
                .orElseThrow(() -> new IllegalStateException("vanilla stone_bricks recipe missing"));
        var stone = new ItemStack(Items.STONE);
        var empty = ItemStack.EMPTY;
        IPatternDetails substituted = PatternDetailsHelper.decodePattern(
                PatternDetailsHelper.encodeCraftingPattern(
                        stoneBricks,
                        // vanilla stone bricks layout: a 2x2 block inside the 3x3 grid
                        new ItemStack[]{stone, stone, empty, stone, stone, empty, empty, empty, empty},
                        new ItemStack(Items.STONE_BRICKS),
                        true, false),
                helper.getLevel());
        if (substituted == null) {
            helper.fail("vanilla tag pattern failed to decode");
            return;
        }
        fixture.addPattern(substituted);
        fixture.register();
        var future = fixture.request(AEItemKey.of(Items.STONE_BRICKS), 8, CalculationStrategy.REPORT_MISSING_ITEMS);
        await(helper, future, plan -> {
            helper.assertTrue(plan != null, "AE2's vanilla planner should still produce a plan");
            var status = fixture.diagnostics().status();
            helper.assertTrue(status.startsWith("ae2: "),
                    "diagnostics should record the decline, got: " + status);
            helper.assertTrue(status.contains("substitution"),
                    "decline reason should name the substitution pattern, got: " + status);
            fixture.close();
            helper.succeed();
        });
    }

    private static void await(GameTestHelper helper, Future<ICraftingPlan> future,
            java.util.function.Consumer<ICraftingPlan> action) {
        helper.runAfterDelay(1, () -> {
            if (!future.isDone()) { await(helper, future, action); return; }
            try { action.accept(future.get()); }
            catch (ExecutionException | InterruptedException failure) {
                helper.fail("vanilla fallback planning failed: " + failure);
            }
        });
    }
}
