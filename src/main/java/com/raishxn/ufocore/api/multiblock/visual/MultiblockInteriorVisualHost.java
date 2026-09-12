package com.raishxn.ufocore.api.multiblock.visual;

import net.minecraft.world.phys.Vec3;

/** Implemented by controllers that opt into RaishxCore's procedural interior renderer. */
public interface MultiblockInteriorVisualHost {
    boolean isInteriorVisualFormed();
    boolean isInteriorVisualActive();
    /** Exact absolute world-space center of the effect. */
    Vec3 getInteriorVisualCenter();
    MultiblockInteriorVisual getInteriorVisual();
}
