package com.raishxn.ufocore.api.port;

import java.util.List;

/** Immutable snapshot of keyed fluid input ports. */
public final class FluidPortGroup<K> {
    private final List<FluidInputPort<K>> ports;

    public FluidPortGroup(List<? extends FluidInputPort<K>> ports) {
        this.ports = List.copyOf(ports);
    }

    public static <K> FluidPortGroup<K> empty() {
        return new FluidPortGroup<>(List.of());
    }

    public int size() {
        return ports.size();
    }

    public long extract(K fluid, long requested, boolean simulate) {
        if (fluid == null || requested <= 0L) return 0L;
        long extracted = 0L;
        for (FluidInputPort<K> port : ports) {
            long remaining = requested - extracted;
            if (remaining <= 0L) break;
            long accepted = port.extract(fluid, remaining, simulate);
            if (accepted > 0L) extracted += Math.min(remaining, accepted);
        }
        return extracted;
    }
}
