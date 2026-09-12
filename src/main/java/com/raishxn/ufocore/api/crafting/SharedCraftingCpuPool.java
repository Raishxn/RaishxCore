package com.raishxn.ufocore.api.crafting;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import java.util.Collection;
import java.util.Set;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * One AE2-visible pool that reserves capacity per job and exposes only its temporary job CPUs.
 * Implementations remain content-mod owned; RaishxCore integrates them with AE2.
 */
public interface SharedCraftingCpuPool extends ICraftingCPU {
    boolean isActive();
    Collection<? extends ICraftingCPU> getActiveCpus();
    long tickCraftingLogic(IEnergyService energyService, ICraftingService craftingService);
    void addWaitingKeys(Set<AEKey> waitingKeys);
    long insert(AEKey what, long amount, Actionable mode);
    long getRequestedAmount(AEKey what);
    ICraftingSubmitResult submitJob(IGrid grid, ICraftingPlan plan, IActionSource source,
                                    @Nullable ICraftingRequester requester);

    default int getCpuPriority() {
        return 0;
    }

    default boolean canHandle(ICraftingPlan plan) {
        return !plan.simulation();
    }

    default boolean consumeCpuListChanged() {
        return false;
    }

    default void prepareForCraftingService() {
    }

    default void restoreCraftingLinks(Consumer<ICraftingLink> consumer) {
    }

    default boolean containsCpu(ICraftingCPU candidate) {
        if (candidate == this) return true;
        for (ICraftingCPU cpu : getActiveCpus()) if (cpu == candidate) return true;
        return false;
    }
}
