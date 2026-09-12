package com.raishxn.ufocore.api.multiblock.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class MultiblockInteriorVisualTest {
    @Test void acceptsAReusableProceduralProfile() {
        var visual = new MultiblockInteriorVisual(MultiblockInteriorEffect.COMPUTATION_LATTICE,
                0x65E7FF, 0xA75CFF, 2.0F, 4.0D);
        assertEquals(MultiblockInteriorEffect.COMPUTATION_LATTICE, visual.effect());
        assertEquals(0x65E7FF, visual.primaryRgb());
    }

    @Test void rejectsInvalidColorsAndBounds() {
        assertThrows(IllegalArgumentException.class, () -> new MultiblockInteriorVisual(
                MultiblockInteriorEffect.PATTERN_ORBITS, 0xFF65E7FF, 0xA75CFF, 2.0F, 4.0D));
        assertThrows(IllegalArgumentException.class, () -> new MultiblockInteriorVisual(
                MultiblockInteriorEffect.PATTERN_ORBITS, 0x65E7FF, 0xA75CFF, 2.0F, 1.0D));
    }
}
