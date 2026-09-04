package com.raishxn.ufocore.client.gui.widget;

import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Generic 16x16 texture action rendered in an AE2 vertical toolbar frame. */
public class UfoToolbarTextureButton extends Button {
    private final ResourceLocation texture;

    public UfoToolbarTextureButton(ResourceLocation texture, Component narration, OnPress onPress) {
        super(0, 0, 16, 16, narration, onPress, DEFAULT_NARRATION);
        this.texture = Objects.requireNonNull(texture, "texture");
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!this.visible) return;
        int yOffset = isHovered() ? 1 : 0;
        Icon background = isHovered() ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER
                : isFocused() ? Icon.TOOLBAR_BUTTON_BACKGROUND_FOCUS : Icon.TOOLBAR_BUTTON_BACKGROUND;
        background.getBlitter().dest(getX() - 1, getY() + yOffset, 18, 20).zOffset(2).blit(graphics);

        Blitter icon = Blitter.texture(this.texture, 16, 16).src(0, 0, 16, 16);
        if (!this.active) icon.opacity(0.5F);
        icon.dest(getX(), getY() + 1 + yOffset).zOffset(3).blit(graphics);
    }
}
