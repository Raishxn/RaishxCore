package com.raishxn.ufocore.api.multiblock;

import java.util.List;
import java.util.Objects;

public record MultiblockScanResult(boolean valid, List<MultiblockScanIssue> issues) {
    public MultiblockScanResult {
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (valid && !issues.isEmpty()) throw new IllegalArgumentException("valid scan cannot contain issues");
    }

    public static MultiblockScanResult success() {
        return new MultiblockScanResult(true, List.of());
    }

    public static MultiblockScanResult failure(List<MultiblockScanIssue> issues) {
        if (issues.isEmpty()) throw new IllegalArgumentException("failed scan must contain an issue");
        return new MultiblockScanResult(false, issues);
    }
}
