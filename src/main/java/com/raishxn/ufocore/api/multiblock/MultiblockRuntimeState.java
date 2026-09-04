package com.raishxn.ufocore.api.multiblock;

public enum MultiblockRuntimeState {
    UNFORMED,
    FORMING,
    IDLE,
    RESERVING,
    RUNNING,
    PAUSED_MANUAL,
    PAUSED_NO_GRID,
    PAUSED_NO_ENERGY,
    PAUSED_NO_COOLANT,
    OUTPUT_BLOCKED,
    INVALID_RECIPE,
    OVERHEATED,
    ERROR_RECOVERABLE
}
