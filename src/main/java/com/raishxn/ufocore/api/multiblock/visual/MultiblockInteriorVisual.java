package com.raishxn.ufocore.api.multiblock.visual;

import java.util.Objects;

/** Immutable visual profile shared by a multiblock controller and the generic client renderer. */
public record MultiblockInteriorVisual(
        MultiblockInteriorEffect effect,
        int primaryRgb,
        int accentRgb,
        float scale,
        double renderRadius) {

    public MultiblockInteriorVisual {
        Objects.requireNonNull(effect, "effect");
        if ((primaryRgb & 0xFF000000) != 0 || (accentRgb & 0xFF000000) != 0) {
            throw new IllegalArgumentException("colors must be 24-bit RGB values");
        }
        if (!Float.isFinite(scale) || scale <= 0.0F) {
            throw new IllegalArgumentException("scale must be finite and positive");
        }
        if (!Double.isFinite(renderRadius) || renderRadius < scale) {
            throw new IllegalArgumentException("renderRadius must be finite and cover the effect scale");
        }
    }
}
