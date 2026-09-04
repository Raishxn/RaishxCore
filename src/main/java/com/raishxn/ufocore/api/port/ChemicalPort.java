package com.raishxn.ufocore.api.port;

/** Technology-neutral bidirectional port for a keyed chemical store. */
public interface ChemicalPort<K> {
    long extractChemical(K chemical, long maxAmount, boolean simulate);

    long insertChemical(K chemical, long maxAmount, boolean simulate);
}
