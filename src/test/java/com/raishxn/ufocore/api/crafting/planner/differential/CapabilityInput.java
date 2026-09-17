package com.raishxn.ufocore.api.crafting.planner.differential;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.List;
import java.util.Objects;

/**
 * Engine-neutral declaration of one input slot of a capability pattern.
 *
 * <p>The value is always an exact {@link UfoAmount}; nothing in the neutral model is narrowed to
 * {@code long}. {@code alternatives}, {@code uses} and {@code host} are only meaningful for the
 * matching {@link Kind}, which keeps an unsupported behaviour explicit instead of silently reduced
 * to an exact input.
 */
public record CapabilityInput(String key, UfoAmount amount, Kind kind,
                              List<String> alternatives, int uses, String host) {

    /** How the slot may be satisfied. */
    public enum Kind {
        /** Exactly one key with an exact consumed amount. */
        EXACT,
        /** A reusable seed returned unchanged after every execution. */
        REUSABLE,
        /** A carrier that loses one use per execution. */
        FINITE_USE,
        /** A logical key routed to a captured real variant of the same host. */
        FUZZY,
        /** A dependency satisfied by an authorized external source. */
        EMITTER
    }

    public CapabilityInput {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(kind, "kind");
        alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives"));
        if (key.isBlank()) throw new IllegalArgumentException("input key must not be blank");
        if (amount.isZero()) throw new IllegalArgumentException("input amount must be positive");
        boolean bounded = kind == Kind.REUSABLE || kind == Kind.FINITE_USE
                || kind == Kind.FUZZY || kind == Kind.EMITTER;
        if (bounded && (host == null || host.isBlank())) {
            throw new IllegalArgumentException(kind + " input requires a host identity");
        }
        if (kind != Kind.REUSABLE && kind != Kind.FINITE_USE && kind != Kind.FUZZY
                && kind != Kind.EMITTER && host != null) {
            throw new IllegalArgumentException(kind + " input must not declare a host");
        }
        if (kind == Kind.FINITE_USE && uses < 1) {
            throw new IllegalArgumentException("finite-use input requires at least one use");
        }
        if (kind != Kind.FINITE_USE && uses != 0) {
            throw new IllegalArgumentException(kind + " input must not declare finite uses");
        }
        if (kind == Kind.FUZZY) {
            if (alternatives.isEmpty() || !alternatives.contains(key)) {
                throw new IllegalArgumentException("fuzzy input must list its logical key plus variants");
            }
        } else if (!alternatives.isEmpty()) {
            throw new IllegalArgumentException(kind + " input must not declare alternatives");
        }
    }

    public static CapabilityInput exact(String key, long amount) {
        return new CapabilityInput(key, UfoAmount.of(amount), Kind.EXACT, List.of(), 0, null);
    }

    public static CapabilityInput exact(String key, UfoAmount amount) {
        return new CapabilityInput(key, amount, Kind.EXACT, List.of(), 0, null);
    }

    public static CapabilityInput reusable(String key, long amount, String host) {
        return new CapabilityInput(key, UfoAmount.of(amount), Kind.REUSABLE, List.of(), 0, host);
    }

    public static CapabilityInput finiteUse(String key, long amount, int uses) {
        return new CapabilityInput(key, UfoAmount.of(amount), Kind.FINITE_USE, List.of(), uses, key);
    }

    public static CapabilityInput fuzzy(String key, long amount, String host, List<String> variants) {
        return new CapabilityInput(key, UfoAmount.of(amount), Kind.FUZZY, variants, 0, host);
    }

    public static CapabilityInput emitter(String key, long amount, String source) {
        return new CapabilityInput(key, UfoAmount.of(amount), Kind.EMITTER, List.of(), 0, source);
    }
}
