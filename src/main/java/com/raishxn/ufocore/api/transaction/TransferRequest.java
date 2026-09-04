package com.raishxn.ufocore.api.transaction;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.Objects;

public record TransferRequest<K>(K key, UfoAmount amount) {
    public TransferRequest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(amount, "amount");
        if (amount.isZero()) throw new IllegalArgumentException("transfer amount must be positive");
    }
}
