package com.raishxn.ufocore.api.port;

import java.util.List;

/** Immutable snapshot of keyed chemical ports with simulate/commit helpers. */
public final class ChemicalPortGroup<K> {
    private final List<ChemicalPort<K>> ports;

    public ChemicalPortGroup(List<? extends ChemicalPort<K>> ports) {
        this.ports = List.copyOf(ports);
    }

    public static <K> ChemicalPortGroup<K> empty() {
        return new ChemicalPortGroup<>(List.of());
    }

    public int size() {
        return ports.size();
    }

    public long extract(K chemical, long requested, boolean simulate) {
        return transfer(chemical, requested, simulate, true);
    }

    public long insert(K chemical, long requested, boolean simulate) {
        return transfer(chemical, requested, simulate, false);
    }

    public long extractTransactional(K chemical, long requested) {
        long planned = extract(chemical, requested, true);
        return extract(chemical, Math.min(requested, planned), false);
    }

    public long insertTransactional(K chemical, long requested) {
        long planned = insert(chemical, requested, true);
        return insert(chemical, Math.min(requested, planned), false);
    }

    private long transfer(K chemical, long requested, boolean simulate, boolean extract) {
        if (chemical == null || requested <= 0L) return 0L;
        long transferred = 0L;
        for (ChemicalPort<K> port : ports) {
            long remaining = requested - transferred;
            if (remaining <= 0L) break;
            long accepted = extract ? port.extractChemical(chemical, remaining, simulate)
                    : port.insertChemical(chemical, remaining, simulate);
            if (accepted > 0L) transferred += Math.min(remaining, accepted);
        }
        return transferred;
    }
}
