package com.raishxn.ufocore.api.crafting.planner;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable input snapshot for one plan. */
public record PlanningRequest<K>(K target, UfoAmount amount, Map<K, UfoAmount> inventory,
                                 PlanningLimits limits, PlanningCancellation cancellation) {
    public PlanningRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(amount, "amount");
        if (amount.isZero()) throw new IllegalArgumentException("requested amount must be positive");
        Objects.requireNonNull(inventory, "inventory");
        LinkedHashMap<K, UfoAmount> copy = new LinkedHashMap<>();
        inventory.forEach((key, value) -> {
            Objects.requireNonNull(key, "inventory key");
            Objects.requireNonNull(value, "inventory amount");
            if (!value.isZero()) copy.put(key, value);
        });
        inventory = Collections.unmodifiableMap(copy);
        limits = Objects.requireNonNull(limits, "limits");
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    public PlanningRequest(K target, UfoAmount amount, Map<K, UfoAmount> inventory) {
        this(target, amount, inventory, PlanningLimits.DEFAULT, PlanningCancellation.NEVER);
    }
}
