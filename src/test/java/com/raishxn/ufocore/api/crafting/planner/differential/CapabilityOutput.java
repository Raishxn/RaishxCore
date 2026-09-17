package com.raishxn.ufocore.api.crafting.planner.differential;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.Objects;

/** Engine-neutral declaration of one output slot of a capability pattern. */
public record CapabilityOutput(String key, UfoAmount amount, Kind kind) {

    /** The role this output plays in the plan. */
    public enum Kind {
        /** Deterministic output selectable as a crafting route. */
        PRIMARY,
        /** Deterministic coproduct that supports demand but is not a route. */
        BYPRODUCT,
        /** Output whose guarantee is below one; never promised to a deterministic request. */
        PROBABILISTIC
    }

    public CapabilityOutput {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(kind, "kind");
        if (key.isBlank()) throw new IllegalArgumentException("output key must not be blank");
        if (amount.isZero()) throw new IllegalArgumentException("output amount must be positive");
    }

    public static CapabilityOutput primary(String key, long amount) {
        return new CapabilityOutput(key, UfoAmount.of(amount), Kind.PRIMARY);
    }

    public static CapabilityOutput byproduct(String key, long amount) {
        return new CapabilityOutput(key, UfoAmount.of(amount), Kind.BYPRODUCT);
    }

    public static CapabilityOutput probabilistic(String key, long amount) {
        return new CapabilityOutput(key, UfoAmount.of(amount), Kind.PROBABILISTIC);
    }

    public static CapabilityOutput primary(String key, UfoAmount amount) {
        return new CapabilityOutput(key, amount, Kind.PRIMARY);
    }
}
