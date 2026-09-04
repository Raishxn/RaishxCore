package com.raishxn.ufocore.api.transaction;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.Objects;

public record TransferAllocation<K>(K key, UfoAmount requested, UfoAmount accepted) {
    public TransferAllocation {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(accepted, "accepted");
        if (accepted.compareTo(requested) > 0) throw new IllegalArgumentException("accepted exceeds requested");
    }

    public UfoAmount remaining() {
        return requested.subtract(accepted);
    }
}
