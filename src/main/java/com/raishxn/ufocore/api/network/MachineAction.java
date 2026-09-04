package com.raishxn.ufocore.api.network;

import java.util.Objects;

/** Stable action identity and server-side minimum interval. */
public record MachineAction(String id, long minimumIntervalTicks) {
    public MachineAction {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("action id must not be blank");
        if (minimumIntervalTicks < 0L) throw new IllegalArgumentException("minimum interval must be non-negative");
    }
}
