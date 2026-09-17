package com.raishxn.ufocore.api.crafting.planner.differential;

/**
 * Author-facing capability families of the frozen differential corpus.
 *
 * <p>The first group is representable by the current RaishxCore semantic model. The second group is
 * declared as an explicit limitation until the roadmap phases that implement it land. Every family
 * is described here as behaviour, never by depending on another planner's production classes.
 */
public enum CapabilityFamily {
    // Representable by the current model.
    SINGLE_DAG,
    MULTI_DAG,
    BATCHING,
    BYPRODUCT,
    DEEP_CHAIN,

    // Declared limitations: modelled as behaviour, not yet implemented.
    CONVERSION_CYCLE,
    POSITIVE_FEEDBACK,
    CONSERVATIVE_FEEDBACK,
    LOSSY_FEEDBACK,
    REUSABLE_CATALYST,
    FINITE_DURABILITY,
    FUZZY_VARIANT,
    EMITTER,
    PROBABILISTIC_OUTPUT
}
