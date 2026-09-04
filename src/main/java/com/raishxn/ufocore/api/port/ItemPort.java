package com.raishxn.ufocore.api.port;

/** Technology-neutral bidirectional port for a keyed item store. */
public interface ItemPort<K> {
    long extractItem(K item, long maxAmount, boolean simulate);

    long insertItem(K item, long maxAmount, boolean simulate);
}
