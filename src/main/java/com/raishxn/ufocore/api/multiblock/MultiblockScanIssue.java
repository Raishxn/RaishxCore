package com.raishxn.ufocore.api.multiblock;

import java.util.Objects;

public record MultiblockScanIssue(int x, int y, int z, String expected, String actual) {
    public MultiblockScanIssue {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
    }
}
