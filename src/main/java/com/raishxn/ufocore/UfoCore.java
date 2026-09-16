package com.raishxn.ufocore;

import com.raishxn.ufocore.neoforge.crafting.Ae2PlannerBridge;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Entrypoint for the reusable UFO platform. Content belongs to consuming addons. */
@Mod(UfoCore.MOD_ID)
public final class UfoCore {
    public static final String MOD_ID = "raishxcore";
    public static final String API_VERSION = "0.1";

    public UfoCore(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, CoreConfig.SPEC, "raishxcore/core.toml");
        NeoForge.EVENT_BUS.addListener(UfoCore::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(UfoCore::onServerStopping);
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Ae2PlannerBridge.cancelForPlayer(event.getEntity().getUUID());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        Ae2PlannerBridge.cancelForServerStop();
    }
}
