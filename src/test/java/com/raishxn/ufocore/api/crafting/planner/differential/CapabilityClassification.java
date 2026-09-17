package com.raishxn.ufocore.api.crafting.planner.differential;

/** Observable production-path classification of exactly one capability case. */
public enum CapabilityClassification {
    /** Planning completed and the independently replayed plan is semantically valid. */
    SUPPORTED(true),
    /** A cheap admission check rejected a request the engine declares unsupported. */
    CHECK_REJECTED(false),
    /** Admission passed but planning actively declined the request. */
    ATTEMPT_DECLINED(false),
    /** The engine reported an unusable or incomplete result for a feasible scenario. */
    FALSE_NEGATIVE(false),
    /** The engine delivered a plan that fails replay. Defect: never a supported case. */
    FALSE_POSITIVE(false),
    /** Admission or planning threw. */
    ENGINE_ERROR(false),
    /** The shared deadline expired and the engine stopped responding to interruption. */
    ENGINE_TIMEOUT(false),
    /** The shared deadline expired and the engine was still running after the grace period. */
    NON_COOPERATIVE_TIMEOUT(false);

    private final boolean supported;

    CapabilityClassification(boolean supported) {
        this.supported = supported;
    }

    /** True only for {@link #SUPPORTED}; every other value must stay visible and distinct. */
    public boolean supported() {
        return supported;
    }

    /** True for early and late declines, which are safe but are not support. */
    public boolean safeDecline() {
        return this == CHECK_REJECTED || this == ATTEMPT_DECLINED;
    }

    /** True when the engine produced a wrong result rather than safely declining. */
    public boolean defect() {
        return this == FALSE_POSITIVE || this == FALSE_NEGATIVE
                || this == ENGINE_ERROR || this == NON_COOPERATIVE_TIMEOUT;
    }
}
