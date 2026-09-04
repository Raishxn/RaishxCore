package com.raishxn.ufocore.api.port;

import java.util.List;

/** Immutable snapshot of energy input ports in deterministic structure order. */
public final class EnergyPortGroup {
    private static final EnergyPortGroup EMPTY = new EnergyPortGroup(List.of());
    private final List<EnergyInputPort> ports;

    public EnergyPortGroup(List<? extends EnergyInputPort> ports) {
        this.ports = List.copyOf(ports);
    }

    public static EnergyPortGroup empty() {
        return EMPTY;
    }

    public int size() {
        return ports.size();
    }

    public long extract(long requested, boolean simulate) {
        if (requested <= 0L) return 0L;
        long extracted = 0L;
        for (EnergyInputPort port : ports) {
            long remaining = requested - extracted;
            if (remaining <= 0L) break;
            long accepted = port.extract(remaining, simulate);
            if (accepted > 0L) extracted += Math.min(remaining, accepted);
        }
        return extracted;
    }
}
