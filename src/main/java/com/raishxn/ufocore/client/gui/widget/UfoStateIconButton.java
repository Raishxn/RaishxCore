package com.raishxn.ufocore.client.gui.widget;

import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.IconButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** AE2-style state button backed by an AE2 icon or arbitrary atlas sprite. */
public final class UfoStateIconButton extends IconButton {
    private Icon icon;
    private ResourceLocation texture;
    private int textureWidth;
    private int textureHeight;
    private int sourceX;
    private int sourceY;
    private int spriteWidth;
    private int spriteHeight;

    public UfoStateIconButton(Component tooltip, OnPress onPress) {
        super(onPress);
        setMessage(tooltip);
    }

    public void setAe2Icon(Icon icon) {
        this.icon = icon;
        this.texture = null;
    }

    public void setAtlasSprite(ResourceLocation texture, int textureWidth, int textureHeight,
            int sourceX, int sourceY, int spriteWidth, int spriteHeight) {
        this.icon = null;
        this.texture = texture;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.sourceX = sourceX;
        this.sourceY = sourceY;
        this.spriteWidth = spriteWidth;
        this.spriteHeight = spriteHeight;
    }

    @Override
    protected Icon getIcon() {
        return this.icon;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.texture == null) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            return;
        }
        if (!this.visible) return;

        int yOffset = isHovered() ? 1 : 0;
        Icon background = isHovered() ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER
                : isFocused() ? Icon.TOOLBAR_BUTTON_BACKGROUND_FOCUS : Icon.TOOLBAR_BUTTON_BACKGROUND;
        background.getBlitter().dest(getX() - 1, getY() + yOffset, 18, 20).zOffset(2).blit(graphics);

        int iconX = getX() + (16 - this.spriteWidth) / 2;
        int iconY = getY() + 1 + yOffset + (16 - this.spriteHeight) / 2;
        Blitter blitter = Blitter.texture(this.texture, this.textureWidth, this.textureHeight)
                .src(this.sourceX, this.sourceY, this.spriteWidth, this.spriteHeight);
        if (!this.active) blitter.opacity(0.5F);
        blitter.dest(iconX, iconY).zOffset(3).blit(graphics);
    }
}
