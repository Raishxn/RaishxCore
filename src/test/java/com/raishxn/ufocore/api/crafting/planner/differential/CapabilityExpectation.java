package com.raishxn.ufocore.api.crafting.planner.differential;

/** What the corpus claims about a scenario, so no unproven behaviour is counted as support. */
public enum CapabilityExpectation {
    /** The current model claims this capability; anything other than SUPPORTED is a finding. */
    REQUIRED,
    /**
     * The capability is not implemented yet. The engine must decline safely or prove the plan, and
     * the classification is reported as an explicit limitation instead of a success.
     */
    LIMITATION
}
