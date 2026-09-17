package com.raishxn.ufocore.api.crafting.planner.differential;

/**
 * A semantic feature a scenario needs from an engine.
 *
 * <p>Scenarios declare these instead of relying on pattern shapes, so an engine can reject an
 * unrepresented behaviour before it produces a plausible but wrong plan. The roadmap phases
 * R2.3-R2.5 add features here; the RaishxCore model only claims the first group today.
 */
public enum CapabilitySemantics {
    /** Exact inputs, deterministic outputs, acyclic dependencies. */
    DETERMINISTIC_EXACT_DAG,
    /** Deterministic coproducts that are not selectable as a route. */
    DETERMINISTIC_BYPRODUCT,
    /** Output counts above one, requiring whole-batch rounding. */
    BATCHING,
    /** More than one selectable route for the same key. */
    MULTI_ROUTE,
    /** Reversible conversion cycles must be oriented and proven. */
    CONVERSION_CYCLE,
    /** A key feeds back into itself with net gain; unsafe unless proven otherwise. */
    POSITIVE_FEEDBACK,
    /** Feedback that conserves every internal state except a startup seed. */
    CONSERVATIVE_FEEDBACK,
    /** Feedback that loses a fixed amount per turn and needs a retained seed. */
    LOSSY_FEEDBACK,
    /** An input returned unchanged after every execution. */
    REUSABLE_INPUT,
    /** A carrier consumed by finite durability uses. */
    FINITE_DURABILITY,
    /** A logical key routed to a captured real variant under a declared policy. */
    FUZZY_ALTERNATIVES,
    /** A dependency satisfied by an authorized external source. */
    EMITTER,
    /** An output whose guarantee is below one. */
    PROBABILISTIC_OUTPUT
}
