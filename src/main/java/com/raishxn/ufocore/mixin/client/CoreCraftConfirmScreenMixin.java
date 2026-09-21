package com.raishxn.ufocore.mixin.client;

import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.menu.me.crafting.CraftConfirmMenu;
import com.raishxn.ufocore.neoforge.crafting.CraftConfirmPlannerOriginAccess;
import com.raishxn.ufocore.neoforge.crafting.PlanningOrigin;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Adds a small, truthful planner badge to AE2's standard confirmation heading. */
@Mixin(value = CraftConfirmScreen.class, remap = false)
public abstract class CoreCraftConfirmScreenMixin {
    @ModifyArg(method = "updateBeforeRender", at = @At(value = "INVOKE",
            // The JVM records this inherited-method invocation against the concrete screen
            // class, not AEBaseScreen. Targeting the base class therefore finds no call site
            // and makes Mixin abort client startup.
            target = "Lappeng/client/gui/me/crafting/CraftConfirmScreen;setTextContent(Ljava/lang/String;Lnet/minecraft/network/chat/Component;)V",
            ordinal = 0), index = 1)
    private Component raishxcore$showPlannerBadge(Component original) {
        CraftConfirmMenu menu = ((CraftConfirmScreen) (Object) this).getMenu();
        PlanningOrigin origin = ((CraftConfirmPlannerOriginAccess) menu).raishxcore$getPlanningOrigin();
        return origin == PlanningOrigin.RAISHX
                ? Component.translatable("gui.raishxcore.craft_confirm.raishx_planner", original)
                : original;
    }
}
