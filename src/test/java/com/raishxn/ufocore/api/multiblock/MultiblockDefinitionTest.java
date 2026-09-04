package com.raishxn.ufocore.api.multiblock;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MultiblockDefinitionTest {
    @Test
    void validatesBoundsAndDuplicateCells() {
        var controller = new MultiblockCell(1, 1, 0, "C", MultiblockRole.CONTROLLER);
        var definition = new MultiblockDefinition("test:machine", 1, 3, 3, 3, 1, 1, 0, List.of(controller));
        assertEquals(27L, definition.volume());
        assertThrows(IllegalArgumentException.class, () -> new MultiblockDefinition(
                "test:duplicate", 1, 3, 3, 3, 1, 1, 0, List.of(controller, controller)));
    }
}
