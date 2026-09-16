package com.raishxn.ufocore.neoforge.network;

import com.raishxn.ufocore.api.network.MachineAction;
import com.raishxn.ufocore.internal.network.MachinePacketRateLimiter;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

/** Shared authorization for client-triggered machine actions. */
public final class UfoMachinePacketGuard {
    public static final double DEFAULT_MAX_DISTANCE_SQUARED = 64.0D;
    private static final MachinePacketRateLimiter RATE_LIMITER = new MachinePacketRateLimiter();

    private UfoMachinePacketGuard() {
    }

    /**
     * Applies the shared player/action rate limit to C2S actions that are not tied
     * to a block entity. The caller remains responsible for validating the held
     * item, open menu and action-specific state.
     */
    public static boolean allow(IPayloadContext context, MachineAction action) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(action, "action");
        if (!(context.player() instanceof ServerPlayer player)) return false;
        return RATE_LIMITER.tryAcquire(player.getUUID(), action, player.level().getGameTime());
    }

    public static <M extends AbstractContainerMenu, T extends BlockEntity> @Nullable T require(
            IPayloadContext context,
            BlockPos pos,
            Class<M> menuType,
            Function<M, T> machineGetter,
            MachineAction action) {
        return require(context, pos, menuType, machineGetter, action, DEFAULT_MAX_DISTANCE_SQUARED);
    }

    public static <M extends AbstractContainerMenu, T extends BlockEntity> @Nullable T require(
            IPayloadContext context,
            BlockPos pos,
            Class<M> menuType,
            Function<M, T> machineGetter,
            MachineAction action,
            double maxDistanceSquared) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(menuType, "menuType");
        Objects.requireNonNull(machineGetter, "machineGetter");
        Objects.requireNonNull(action, "action");
        if (maxDistanceSquared < 0.0D) throw new IllegalArgumentException("max distance must be non-negative");

        if (!(context.player() instanceof ServerPlayer player)
                || !menuType.isInstance(player.containerMenu)) {
            return null;
        }
        M menu = menuType.cast(player.containerMenu);
        T machine = machineGetter.apply(menu);
        if (machine == null
                || !machine.getBlockPos().equals(pos)
                || !player.level().isLoaded(pos)
                || player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                        > maxDistanceSquared
                || !menu.stillValid(player)
                || !RATE_LIMITER.tryAcquire(player.getUUID(), action, player.level().getGameTime())) {
            return null;
        }
        BlockEntity current = player.level().getBlockEntity(pos);
        return current == machine ? machine : null;
    }
}
