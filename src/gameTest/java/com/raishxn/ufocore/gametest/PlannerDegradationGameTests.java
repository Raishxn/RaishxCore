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
 * Boundaries of the AE2 planner integration: common substitution patterns stay inside the Core,
 * while separate cooperative-capture tests exercise the deliberate fallback paths.
 */
@GameTestHolder("raishxcore_tests")
@PrefixGameTestTemplate(false)
public final class PlannerDegradationGameTests {
    @GameTest(template = "empty", timeoutTicks = 240)
    public static void substitutionAlternativesStayInsideCorePlanner(GameTestHelper helper) {
        try {
            runDeclineScenario(helper);
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().error("decline scenario setup failure", t);
            helper.fail("setup failed: " + t);
        }
    }

    private static void runDeclineScenario(GameTestHelper helper) {
        var fixture = new PlannerGameTests.Fixture(helper);
        // The crafting-table ingredient is tag-backed and exposes several possible planks. The Core
        // pins the slot to the encoded oak option, which remains valid for native AE2 execution.
        var recipe = helper.getLevel().getServer().getRecipeManager()
                .byKey(ResourceLocation.withDefaultNamespace("crafting_table"))
                .orElseThrow(() -> new IllegalStateException("vanilla crafting table recipe missing"));
        if (!(recipe.value() instanceof CraftingRecipe crafting)) {
            throw new IllegalStateException("crafting table is not a crafting recipe");
        }
        RecipeHolder<CraftingRecipe> craftingTable = new RecipeHolder<>(recipe.id(), crafting);
        var plank = new ItemStack(Items.OAK_PLANKS);
        var empty = ItemStack.EMPTY;
        IPatternDetails substituted = PatternDetailsHelper.decodePattern(
                PatternDetailsHelper.encodeCraftingPattern(
                        craftingTable,
                        new ItemStack[]{plank, plank, empty, plank, plank, empty, empty, empty, empty},
                        new ItemStack(Items.CRAFTING_TABLE),
                        true, false),
                helper.getLevel());
        if (substituted == null) {
            helper.fail("vanilla tag pattern failed to decode");
            return;
        }
        fixture.addPattern(substituted);
        fixture.register();
        var future = fixture.request(AEItemKey.of(Items.CRAFTING_TABLE), 8,
                CalculationStrategy.REPORT_MISSING_ITEMS);
        await(helper, future, plan -> {
            helper.assertTrue(plan != null, "Core should produce a plan for substitution alternatives");
            helper.assertTrue(fixture.diagnostics().lastPlan() != null,
                    "tag-backed substitution was delegated to AE2");
            fixture.close();
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void exactSubstitutionPatternStaysInsideCorePlanner(GameTestHelper helper) {
        var fixture = new PlannerGameTests.Fixture(helper);
        var recipe = helper.getLevel().getServer().getRecipeManager()
                .byKey(ResourceLocation.withDefaultNamespace("stone_bricks"))
                .orElseThrow(() -> new IllegalStateException("vanilla stone bricks recipe missing"));
        if (!(recipe.value() instanceof CraftingRecipe crafting)) {
            throw new IllegalStateException("stone bricks is not a crafting recipe");
        }
        var stone = new ItemStack(Items.STONE);
        var empty = ItemStack.EMPTY;
        IPatternDetails exact = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeCraftingPattern(
                new RecipeHolder<>(recipe.id(), crafting),
                new ItemStack[]{stone, stone, empty, stone, stone, empty, empty, empty, empty},
                new ItemStack(Items.STONE_BRICKS), true, false), helper.getLevel());
        helper.assertTrue(exact != null, "exact substitution pattern failed to decode");
        fixture.addPattern(exact);
        fixture.register();
        await(helper, fixture.request(AEItemKey.of(Items.STONE_BRICKS), 8,
                CalculationStrategy.REPORT_MISSING_ITEMS), plan -> {
            helper.assertTrue(plan != null, "Core should produce a plan for exact substitution");
            helper.assertTrue(fixture.diagnostics().lastPlan() != null,
                    "exact substitution was delegated to AE2");
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
