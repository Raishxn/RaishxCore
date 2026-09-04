package com.raishxn.ufocore.api.port;

import java.util.List;

/** Immutable snapshot of keyed item ports with simulate/commit helpers. */
public final class ItemPortGroup<K> {
    private final List<ItemPort<K>> ports;

    public ItemPortGroup(List<? extends ItemPort<K>> ports) {
        this.ports = List.copyOf(ports);
    }

    public static <K> ItemPortGroup<K> empty() {
        return new ItemPortGroup<>(List.of());
    }

    public int size() {
        return ports.size();
    }

    public long extract(K item, long requested, boolean simulate) {
        return transfer(item, requested, simulate, true);
    }

    public long insert(K item, long requested, boolean simulate) {
        return transfer(item, requested, simulate, false);
    }

    public long extractTransactional(K item, long requested) {
        long planned = extract(item, requested, true);
        return extract(item, Math.min(requested, planned), false);
    }

    public long insertTransactional(K item, long requested) {
        long planned = insert(item, requested, true);
        return insert(item, Math.min(requested, planned), false);
    }

    private long transfer(K item, long requested, boolean simulate, boolean extract) {
        if (item == null || requested <= 0L) return 0L;
        long transferred = 0L;
        for (ItemPort<K> port : ports) {
            long remaining = requested - transferred;
            if (remaining <= 0L) break;
            long accepted = extract ? port.extractItem(item, remaining, simulate)
                    : port.insertItem(item, remaining, simulate);
            if (accepted > 0L) transferred += Math.min(remaining, accepted);
        }
        return transferred;
    }
}
