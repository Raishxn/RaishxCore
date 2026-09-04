package com.raishxn.ufocore.api.port;

/** Technology-neutral source of machine energy. */
@FunctionalInterface
public interface EnergyInputPort {
    long extract(long maxAmount, boolean simulate);
}
