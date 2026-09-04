package com.raishxn.ufocore.api.multiblock;

import java.util.Objects;

public record MultiblockCell(int x, int y, int z, String symbol, MultiblockRole role) {
    public MultiblockCell {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(role, "role");
        if (symbol.isBlank()) throw new IllegalArgumentException("symbol must not be blank");
    }
}
