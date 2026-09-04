package com.raishxn.ufocore.client.gui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Button backed by a fixed rectangle in a texture atlas, optionally scaled. */
public final class UfoAtlasButton extends Button {
    private final ResourceLocation texture;
    private final int textureWidth;
    private final int textureHeight;
    private int sourceX;
    private int sourceY;
    private final int sourceWidth;
    private final int sourceHeight;

    public UfoAtlasButton(int x, int y, int width, int height, ResourceLocation texture,
            int textureWidth, int textureHeight, int sourceX, int sourceY,
            Component narration, OnPress onPress) {
        this(x, y, width, height, texture, textureWidth, textureHeight, sourceX, sourceY,
                width, height, narration, onPress);
    }

    public UfoAtlasButton(int x, int y, int width, int height, ResourceLocation texture,
            int textureWidth, int textureHeight, int sourceX, int sourceY,
            int sourceWidth, int sourceHeight, Component narration, OnPress onPress) {
        super(x, y, width, height, narration, onPress, DEFAULT_NARRATION);
        this.texture = texture;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.sourceX = sourceX;
        this.sourceY = sourceY;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
    }

    public void setSource(int sourceX, int sourceY) {
        this.sourceX = sourceX;
        this.sourceY = sourceY;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        float alpha = this.active ? (this.isHovered() ? 1.0F : 0.9F) : 0.4F;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        if (this.width == this.sourceWidth && this.height == this.sourceHeight) {
            graphics.blit(this.texture, getX(), getY(), this.sourceX, this.sourceY,
                    this.width, this.height, this.textureWidth, this.textureHeight);
        } else {
            graphics.blit(this.texture, getX(), getY(), this.sourceX, this.sourceY,
                    this.width, this.height, this.sourceWidth, this.sourceHeight,
                    this.textureWidth, this.textureHeight);
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
