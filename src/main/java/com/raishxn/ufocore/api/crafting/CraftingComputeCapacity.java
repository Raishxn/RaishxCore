package com.raishxn.ufocore.api.crafting;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.Objects;

/** Exact crafting-CPU capacity independent of AE2's {@code int} co-processor counter. */
public record CraftingComputeCapacity(UfoAmount storageBytes, UfoAmount parallelLanes) {
    public static final CraftingComputeCapacity ZERO = new CraftingComputeCapacity(UfoAmount.ZERO, UfoAmount.ZERO);

    public CraftingComputeCapacity {
        Objects.requireNonNull(storageBytes, "storageBytes");
        Objects.requireNonNull(parallelLanes, "parallelLanes");
    }

    public static CraftingComputeCapacity storage(long bytes) {
        return new CraftingComputeCapacity(UfoAmount.of(bytes), UfoAmount.ZERO);
    }

    public static CraftingComputeCapacity lanes(long lanes) {
        return new CraftingComputeCapacity(UfoAmount.ZERO, UfoAmount.of(lanes));
    }

    public CraftingComputeCapacity add(CraftingComputeCapacity other) {
        Objects.requireNonNull(other, "other");
        return new CraftingComputeCapacity(storageBytes.add(other.storageBytes), parallelLanes.add(other.parallelLanes));
    }
}
