package com.raishxn.ufocore.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import com.raishxn.ufocore.neoforge.crafting.Ae2PlannerBridge;
import com.raishxn.ufocore.neoforge.crafting.PlannerGridService;
import com.raishxn.ufocore.neoforge.crafting.PlannerRevisionSource;
import java.util.concurrent.Future;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftingService.class, remap = false)
public abstract class CoreCraftingPlannerServiceMixin implements PlannerGridService {
    @Shadow @Final private IGrid grid;
    @Shadow @Final private NetworkCraftingProviders craftingProviders;
    @Unique private Ae2PlannerBridge raishxcore$planner;

    @Inject(method = "beginCraftingCalculation", at = @At("HEAD"), cancellable = true)
    private void raishxcore$planFromSnapshot(Level level, ICraftingSimulationRequester requester,
                                            AEKey target, long amount, CalculationStrategy strategy,
                                            CallbackInfoReturnable<Future<ICraftingPlan>> cir) {
        if (!com.raishxn.ufocore.CoreConfig.isPlannerEnabled()) {
            // Kill-switch: leave AE2's own planner in charge of this request entirely.
            if (raishxcore$planner == null) raishxcore$planner = new Ae2PlannerBridge();
            raishxcore$planner.recordDisabled();
            return;
        }
        if (raishxcore$planner == null) raishxcore$planner = new Ae2PlannerBridge();
        long revision = ((PlannerRevisionSource) craftingProviders).raishxcore$getPatternRevision();
        Future<ICraftingPlan> result = raishxcore$planner.begin(level, grid, requester, target, amount, strategy, revision);
        if (result != null) cir.setReturnValue(result);
    }

    @Inject(method = "removeNode", at = @At("HEAD"))
    private void raishxcore$cancelPlanningForGridChange(IGridNode node, CallbackInfo ci) {
        if (raishxcore$planner == null) return;
        if (grid.size() <= 1) raishxcore$planner.close();
        else raishxcore$planner.invalidate("grid node removed");
    }

    @Override public Ae2PlannerBridge.Diagnostics raishxcore$getPlannerDiagnostics() {
        if (raishxcore$planner == null) raishxcore$planner = new Ae2PlannerBridge();
        return raishxcore$planner.diagnostics();
    }
}
