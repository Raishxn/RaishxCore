package com.raishxn.ufocore.gametest;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.MEStorage;
import com.raishxn.ufocore.neoforge.crafting.PlannerGridService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@Mod("raishxcore_tests")
@GameTestHolder("raishxcore_tests")
@PrefixGameTestTemplate(false)
public final class PlannerGameTests {
    @GameTest(template = "empty", timeoutTicks = 240)
    public static void realGridPlanningAndSameTickInvalidation(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        fixture.provider.patterns.add(fixture.pattern(1));
        fixture.grid.getCraftingService().addGlobalCraftingProvider(fixture.provider);
        var first = fixture.request(8, CalculationStrategy.REPORT_MISSING_ITEMS);
        await(helper, first, plan -> {
            helper.assertTrue(!plan.simulation(), "the Core must produce an executable AE2 plan");
            helper.assertTrue(plan.usedItems().get(fixture.raw) == 8, "incorrect raw extraction");
            helper.assertTrue(plan.patternTimes().values().stream().mapToLong(Long::longValue).sum() == 8,
                    "incorrect number of crafts");
            helper.assertTrue(fixture.diagnostics().lastPlan() != null, "Core entry point was bypassed");
            var second = fixture.request(8, CalculationStrategy.REPORT_MISSING_ITEMS);
            await(helper, second, cached -> {
                helper.assertTrue(fixture.diagnostics().cacheHits() >= 1, "revision cache did not hit");
                long revision = fixture.diagnostics().revision();
                // Two provider changes during this one server tick must both advance the revision.
                fixture.provider.patterns.clear();
                fixture.provider.patterns.add(fixture.pattern(2));
                fixture.grid.getCraftingService().refreshGlobalCraftingProvider(fixture.provider);
                fixture.grid.getCraftingService().refreshGlobalCraftingProvider(fixture.provider);
                var changed = fixture.request(8, CalculationStrategy.REPORT_MISSING_ITEMS);
                await(helper, changed, fresh -> {
                    helper.assertTrue(fixture.diagnostics().revision() >= revision + 4,
                            "same-tick updates reused a stale graph");
                    helper.assertTrue(fresh.usedItems().get(fixture.raw) == 16, "stale pattern quantities");
                    helper.assertTrue(fixture.diagnostics().status().equals("COMPLETE"),
                            "obsolete request overwrote current planner diagnostics");
                    fixture.close();
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void craftLessUsesOneBudgetAndPreservesShortageSemantics(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        fixture.provider.patterns.add(fixture.pattern(2));
        fixture.grid.getCraftingService().addGlobalCraftingProvider(fixture.provider);
        await(helper, fixture.request(100, CalculationStrategy.CRAFT_LESS), plan -> {
            helper.assertTrue(!plan.simulation(), "CRAFT_LESS should find a feasible amount");
            helper.assertTrue(plan.finalOutput().amount() == 32, "expected exactly 32 crafts from 64 raw");
            await(helper, fixture.request(100, CalculationStrategy.REPORT_MISSING_ITEMS), missing -> {
                helper.assertTrue(missing.simulation(), "shortage must remain a simulation");
                helper.assertTrue(missing.missingItems().get(fixture.raw) == 136, "incorrect missing count");
                fixture.close();
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void standardMenuRequesterWithoutGridNodeUsesCorePlanner(GameTestHelper helper) {
        var fixture = new Fixture(helper);
        fixture.provider.patterns.add(fixture.pattern(1));
        fixture.grid.getCraftingService().addGlobalCraftingProvider(fixture.provider);
        // CraftConfirmMenu creates this same shape of requester: it implements getActionSource(),
        // while ICraftingSimulationRequester's default getGridNode() returns null.
        ICraftingSimulationRequester menuRequester = () -> IActionSource.empty();
        var request = fixture.grid.getCraftingService().beginCraftingCalculation(
                helper.getLevel(), menuRequester, fixture.product, 8, CalculationStrategy.REPORT_MISSING_ITEMS);
        await(helper, request, plan -> {
            helper.assertTrue(!plan.simulation(), "menu request should produce an executable plan");
            helper.assertTrue(plan.usedItems().get(fixture.raw) == 8, "incorrect raw extraction");
            helper.assertTrue(fixture.diagnostics().lastPlan() != null,
                    "requester without a grid node bypassed the Core planner");
            fixture.close();
            helper.succeed();
        });
    }

    private static void await(GameTestHelper helper, Future<ICraftingPlan> future, Consumer<ICraftingPlan> action) {
        helper.runAfterDelay(1, () -> {
            if (!future.isDone()) { await(helper, future, action); return; }
            try { action.accept(future.get()); }
            catch (Exception failure) { helper.fail("planning failed: " + failure); }
        });
    }

    static final class Fixture {
        final GameTestHelper helper;
        final IManagedGridNode managed;
        final IGrid grid;
        final AEItemKey raw = AEItemKey.of(Items.COBBLESTONE);
        final AEItemKey product = AEItemKey.of(Items.GLASS);
        final Provider provider = new Provider();
        final ICraftingSimulationRequester requester;
        Fixture(GameTestHelper helper) {
            this.helper = helper;
            managed = GridHelper.createManagedNode(this, (owner, node) -> {}).setInWorldNode(false);
            managed.create(helper.getLevel(), helper.absolutePos(net.minecraft.core.BlockPos.ZERO));
            grid = managed.getNode().getGrid();
            grid.getStorageService().addGlobalStorageProvider(mounts -> mounts.mount(new MEStorage() {
                @Override public void getAvailableStacks(KeyCounter out) { out.add(raw, 64); }
                @Override public Component getDescription() { return Component.literal("Planner test stock"); }
            }, 0));
            requester = new ICraftingSimulationRequester() {
                @Override public IActionSource getActionSource() { return IActionSource.empty(); }
                @Override public IGridNode getGridNode() { return managed.getNode(); }
            };
        }
        IPatternDetails pattern(int input) {
            return pattern(input, product);
        }

        IPatternDetails pattern(int input, AEItemKey output) {
            return PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                    List.of(new GenericStack(raw, input)), List.of(new GenericStack(output, 1))), helper.getLevel());
        }
        void addPattern(IPatternDetails pattern) {
            provider.patterns.add(pattern);
        }

        void register() {
            grid.getCraftingService().addGlobalCraftingProvider(provider);
        }

        /** Replaces every pattern and tells the grid, which advances the pattern revision. */
        void replacePatterns(IPatternDetails... replacements) {
            provider.patterns.clear();
            provider.patterns.addAll(List.of(replacements));
            grid.getCraftingService().refreshGlobalCraftingProvider(provider);
        }

        Future<ICraftingPlan> request(long amount, CalculationStrategy strategy) {
            return request(product, amount, strategy);
        }

        Future<ICraftingPlan> request(AEItemKey what, long amount, CalculationStrategy strategy) {
            return grid.getCraftingService().beginCraftingCalculation(helper.getLevel(), requester, what, amount, strategy);
        }
        com.raishxn.ufocore.neoforge.crafting.Ae2PlannerBridge.Diagnostics diagnostics() {
            return ((PlannerGridService) grid.getCraftingService()).raishxcore$getPlannerDiagnostics();
        }
        void close() {
            grid.getCraftingService().removeGlobalCraftingProvider(provider);
            managed.destroy();
            helper.assertTrue(diagnostics().status().equals("grid closed"),
                    "destroying the final grid node did not close the planner bridge");
        }
    }
    private static final class Provider implements ICraftingProvider {
        final List<IPatternDetails> patterns = new ArrayList<>();
        @Override public List<IPatternDetails> getAvailablePatterns() { return List.copyOf(patterns); }
        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) { return false; }
        @Override public boolean isBusy() { return false; }
    }
}
