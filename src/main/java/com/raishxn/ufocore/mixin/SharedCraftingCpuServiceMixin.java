package com.raishxn.ufocore.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import com.raishxn.ufocore.api.crafting.SharedCraftingCpuPool;
import com.raishxn.ufocore.api.crafting.SharedCraftingCpuPoolProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** AE2 bridge for RaishxCore shared CPU pools. */
@Mixin(value = CraftingService.class, remap = false)
public abstract class SharedCraftingCpuServiceMixin {
    @Shadow @Final private IGrid grid;
    @Shadow @Final private IEnergyService energyGrid;
    @Shadow @Final private Set<AEKey> currentlyCrafting;
    @Shadow private boolean updateList;
    @Shadow private long lastProcessedCraftingLogicChangeTick;
    @Shadow public abstract void addLink(CraftingLink link);

    /*
     * Mixin does not copy instance field initializers into the target constructor. These collections must be
     * created explicitly before CraftingService can receive its first node.
     */
    @Unique private Map<IGridNode, SharedCraftingCpuPool> raishxcore$pools;
    @Unique private Set<IGridNode> raishxcore$providerNodes;
    @Unique private Set<SharedCraftingCpuPool> raishxcore$prepared;
    /*
     * Distinct pools, rebuilt only when raishxcore$pools changes. Every grid reads it twice per tick
     * and again on each network insert, request query and link check, so building a fresh identity
     * set per call allocated on paths that run for every grid on the server.
     */
    @Unique private @Nullable List<SharedCraftingCpuPool> raishxcore$uniquePoolsCache;
    /*
     * Last pool state that made AE2 rebuild currentlyCrafting: the newest change tick of any pool
     * job and the number of pool job CPUs. AE2 does the same with its own CPUs' change ticks.
     */
    @Unique private long raishxcore$lastPoolChange = Long.MIN_VALUE;
    @Unique private int raishxcore$lastPoolCpuCount;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void raishxcore$initializePoolState(IGrid grid, IStorageService storageGrid,
                                                IEnergyService energyGrid, CallbackInfo ci) {
        raishxcore$pools = new IdentityHashMap<>();
        raishxcore$providerNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        raishxcore$prepared = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    @Inject(method = "addNode", at = @At("TAIL"))
    private void raishxcore$addPoolNode(IGridNode node, net.minecraft.nbt.CompoundTag savedData, CallbackInfo ci) {
        raishxcore$refreshNode(node);
    }

    @Inject(method = "removeNode", at = @At("TAIL"))
    private void raishxcore$removePoolNode(IGridNode node, CallbackInfo ci) {
        SharedCraftingCpuPool removed = raishxcore$pools.remove(node);
        raishxcore$providerNodes.remove(node);
        if (removed != null) {
            raishxcore$uniquePoolsCache = null;
            raishxcore$prepared.remove(removed);
            updateList = true;
        }
    }

    @Inject(method = "updateCPUClusters", at = @At("TAIL"))
    private void raishxcore$rescanPools(CallbackInfo ci) {
        var nodes = new ArrayList<IGridNode>();
        for (Class<?> machineClass : grid.getMachineClasses()) {
            for (IGridNode node : grid.getMachineNodes(machineClass)) nodes.add(node);
        }
        // Identity membership, as before, without a linear list search per tracked node.
        Set<IGridNode> present = Collections.newSetFromMap(new IdentityHashMap<>(nodes.size()));
        present.addAll(nodes);
        if (raishxcore$pools.keySet().removeIf(node -> !present.contains(node))) raishxcore$uniquePoolsCache = null;
        raishxcore$providerNodes.removeIf(node -> !present.contains(node));
        for (IGridNode node : nodes) raishxcore$refreshNode(node);
    }

    @Inject(method = "onServerEndTick", at = @At("HEAD"))
    private void raishxcore$refreshDynamicPools(CallbackInfo ci) {
        for (IGridNode node : ListCopy.copyOf(raishxcore$providerNodes)) raishxcore$refreshNode(node);
    }

    @Inject(method = "onServerEndTick", at = @At(
            value = "FIELD",
            target = "Lappeng/me/service/CraftingService;lastProcessedCraftingLogicChangeTick:J",
            opcode = Opcodes.GETFIELD,
            ordinal = 0))
    private void raishxcore$tickPools(CallbackInfo ci) {
        List<SharedCraftingCpuPool> pools = raishxcore$uniquePools();
        if (pools.isEmpty() && raishxcore$lastPoolCpuCount == 0 && raishxcore$lastPoolChange == Long.MIN_VALUE) return;
        long latest = Long.MIN_VALUE;
        for (SharedCraftingCpuPool pool : pools) {
            latest = Math.max(latest, pool.tickCraftingLogic(energyGrid, (CraftingService) (Object) this));
            if (pool.consumeCpuListChanged()) updateList = true;
        }
        // A pool may tick only part of its jobs; the others still change when outputs arrive.
        int cpuCount = 0;
        for (SharedCraftingCpuPool pool : pools) {
            for (ICraftingCPU cpu : pool.getActiveCpus()) {
                cpuCount++;
                if (cpu instanceof CraftingCPUCluster cluster) {
                    latest = Math.max(latest, cluster.craftingLogic.getLastModifiedOnTick());
                }
            }
        }
        // Rebuild AE2's waiting-key set only when a pool job changed, as AE2 does for its own CPUs.
        // Forcing it every tick made each grid with a busy pool copy, refill and diff
        // currentlyCrafting (and notify its watchers' diff) on every server tick.
        if (latest != raishxcore$lastPoolChange || cpuCount != raishxcore$lastPoolCpuCount) {
            raishxcore$lastPoolChange = latest;
            raishxcore$lastPoolCpuCount = cpuCount;
            lastProcessedCraftingLogicChangeTick = -1L;
        }
    }

    @Inject(method = "onServerEndTick", at = @At(
            value = "FIELD",
            target = "Lappeng/me/service/CraftingService;interests:Lcom/google/common/collect/Multimap;",
            opcode = Opcodes.GETFIELD,
            ordinal = 0))
    private void raishxcore$addWaitingKeys(CallbackInfo ci) {
        for (SharedCraftingCpuPool pool : raishxcore$uniquePools()) pool.addWaitingKeys(currentlyCrafting);
    }

    /*
     * The three CPU-facing reads below must never replace AE2's return value from a cancellable
     * RETURN injection. Other CPU add-ons (AdvancedAE's Quantum Computer, and the same pattern in
     * NeoECO) hook these methods the same way, and a cancellable @Inject aborts the method the
     * moment it sets a value, silently skipping every later injector from every other mod. That made
     * the Quantum Computer disappear from the CPU list and never receive crafted items while
     * RaishxCore was installed, even with the planner disabled. Mutating AE2's own accumulator, or
     * the builder right before it is built, composes with any number of other add-ons instead: our
     * contribution is already in the value they return.
     */

    @Inject(method = "getCpus", at = @At(
            value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableSet$Builder;build()Lcom/google/common/collect/ImmutableSet;"))
    private void raishxcore$appendPoolCpus(CallbackInfoReturnable<ImmutableSet<ICraftingCPU>> cir,
                                           @Local(ordinal = 0) ImmutableSet.Builder<ICraftingCPU> cpus) {
        for (SharedCraftingCpuPool pool : raishxcore$uniquePools()) {
            if (!pool.isActive()) continue;
            for (ICraftingCPU cpu : pool.getActiveCpus()) cpus.add(cpu);
            if (pool.getAvailableStorage() > 0L) cpus.add(pool);
        }
    }

    @Inject(method = "insertIntoCpus", at = @At(value = "RETURN", shift = At.Shift.BY, by = -1))
    private void raishxcore$insertIntoPools(AEKey what, long amount, Actionable mode,
                                            CallbackInfoReturnable<Long> cir,
                                            @Local(ordinal = 1) LocalLongRef insertedRef) {
        long inserted = insertedRef.get();
        for (SharedCraftingCpuPool pool : raishxcore$uniquePools()) {
            if (inserted >= amount) break;
            inserted += pool.insert(what, amount - inserted, mode);
        }
        insertedRef.set(inserted);
    }

    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true)
    private void raishxcore$submitToPool(ICraftingPlan plan, @Nullable ICraftingRequester requester,
                                         @Nullable ICraftingCPU target, boolean prioritizePower,
                                         IActionSource source, CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        if (plan.simulation()) return;
        if (target instanceof SharedCraftingCpuPool pool) {
            cir.setReturnValue(pool.canHandle(plan)
                    ? pool.submitJob(grid, plan, source, requester)
                    : CraftingSubmitResult.CPU_OFFLINE);
            return;
        }
        if (target != null) {
            for (SharedCraftingCpuPool pool : raishxcore$uniquePools()) {
                if (pool.containsCpu(target)) {
                    cir.setReturnValue(CraftingSubmitResult.CPU_BUSY);
                    return;
                }
            }
            return;
        }

        SharedCraftingCpuPool selected = null;
        for (SharedCraftingCpuPool pool : raishxcore$uniquePools()) {
            if (!pool.isActive() || !pool.canHandle(plan) || pool.getAvailableStorage() < plan.bytes()) continue;
            if (selected == null
                    || pool.getCpuPriority() > selected.getCpuPriority()
                    || pool.getCpuPriority() == selected.getCpuPriority()
                    && pool.getCoProcessors() > selected.getCoProcessors()) {
                selected = pool;
            }
        }
        if (selected != null) cir.setReturnValue(selected.submitJob(grid, plan, source, requester));
    }

    @Inject(method = "getRequestedAmount", at = @At(value = "RETURN", shift = At.Shift.BY, by = -1))
    private void raishxcore$requested(AEKey key, CallbackInfoReturnable<Long> cir,
                                      @Local(ordinal = 0) LocalLongRef requestedRef) {
        long requested = requestedRef.get();
        for (SharedCraftingCpuPool pool : raishxcore$uniquePools()) {
            long addition = pool.getRequestedAmount(key);
            requested = requested >= Long.MAX_VALUE - addition ? Long.MAX_VALUE : requested + addition;
        }
        requestedRef.set(requested);
    }

    @Inject(method = "hasCpu", at = @At("HEAD"), cancellable = true)
    private void raishxcore$hasCpu(ICraftingCPU cpu, CallbackInfoReturnable<Boolean> cir) {
        for (SharedCraftingCpuPool pool : raishxcore$uniquePools()) {
            if (pool.containsCpu(cpu)) {
                cir.setReturnValue(true);
                return;
            }
        }
    }

    @Unique
    private void raishxcore$refreshNode(IGridNode node) {
        SharedCraftingCpuPoolProvider provider = node.getService(SharedCraftingCpuPoolProvider.class);
        if (provider == null && node.getOwner() instanceof SharedCraftingCpuPoolProvider owner) provider = owner;
        if (provider == null) {
            raishxcore$providerNodes.remove(node);
        } else {
            raishxcore$providerNodes.add(node);
        }
        SharedCraftingCpuPool resolved = provider == null ? null : provider.getSharedCraftingCpuPool();
        SharedCraftingCpuPool previous = resolved == null
                ? raishxcore$pools.remove(node) : raishxcore$pools.put(node, resolved);
        if (previous != resolved) {
            updateList = true;
            raishxcore$uniquePoolsCache = null;
        }
        if (resolved != null && raishxcore$prepared.add(resolved)) {
            resolved.prepareForCraftingService();
            resolved.restoreCraftingLinks(link -> {
                if (link instanceof CraftingLink concrete) addLink(concrete);
            });
        }
    }

    @Unique
    private List<SharedCraftingCpuPool> raishxcore$uniquePools() {
        List<SharedCraftingCpuPool> cached = raishxcore$uniquePoolsCache;
        if (cached != null) return cached;
        if (raishxcore$pools.isEmpty()) {
            cached = List.of();
        } else {
            Set<SharedCraftingCpuPool> result = Collections.newSetFromMap(new IdentityHashMap<>());
            result.addAll(raishxcore$pools.values());
            cached = ListCopy.copyOf(result);
        }
        raishxcore$uniquePoolsCache = cached;
        return cached;
    }

    /** Avoids a dependency on SequencedCollection APIs in mixin-generated bytecode. */
    @Unique
    private static final class ListCopy {
        private static <T> java.util.List<T> copyOf(java.util.Collection<T> values) {
            return java.util.List.copyOf(values);
        }
    }
}
