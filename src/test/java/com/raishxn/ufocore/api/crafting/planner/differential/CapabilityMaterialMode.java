package com.raishxn.ufocore.api.crafting.planner.differential;

/** The three independently reported inventory modes of the capability standard. */
public enum CapabilityMaterialMode {
    /** Inventory is deliberately insufficient; the engine must report a usable shortage set. */
    MISSING,
    /** Exactly one known minimum witness is supplied; the engine must produce a valid plan. */
    MINIMUM,
    /** Every leaf material is effectively unlimited. */
    UNBOUNDED
}
