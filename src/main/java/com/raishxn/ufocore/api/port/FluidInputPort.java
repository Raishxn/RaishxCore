package com.raishxn.ufocore.api.port;

/** Technology-neutral source for a keyed fluid. */
@FunctionalInterface
public interface FluidInputPort<K> {
    long extract(K fluid, long maxAmount, boolean simulate);
}
