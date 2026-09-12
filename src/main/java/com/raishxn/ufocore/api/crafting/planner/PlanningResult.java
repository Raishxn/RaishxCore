package com.raishxn.ufocore.api.crafting.planner;

import java.util.Objects;

/** Outcome and bounded diagnostics of one planning attempt. */
public record PlanningResult<K>(Status status, CraftingPlan<K> plan, Diagnostics diagnostics) {
    public PlanningResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public enum Status { COMPLETE, MISSING_INGREDIENTS, CANCELLED, TIMED_OUT, OPERATION_LIMIT, DEPTH_LIMIT }

    public record Diagnostics(long graphRevision, long operations, int maximumDepth, long elapsedNanos) {
        public Diagnostics {
            if (graphRevision < 0L || operations < 0L || maximumDepth < 0 || elapsedNanos < 0L) {
                throw new IllegalArgumentException("diagnostics must be non-negative");
            }
        }
    }
}
