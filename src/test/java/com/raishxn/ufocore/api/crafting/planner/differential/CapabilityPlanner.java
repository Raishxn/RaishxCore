package com.raishxn.ufocore.api.crafting.planner.differential;

/**
 * Production-shaped contract an engine author implements to run the differential corpus.
 *
 * <p>Both entry points route through the engine's own admission and planning path. A diagnostic
 * forced execution used by the runner never changes the production classification.
 */
public interface CapabilityPlanner {

    /** Stable engine name reported in the harness output. */
    String name();

    /** Cheap, bounded admission probe that may safely reject unsupported semantics. */
    Check check(CapabilityScenario scenario);

    /** Full planning attempt through the engine's production path. */
    Outcome plan(CapabilityScenario scenario);

    /** Result of the admission probe. */
    record Check(boolean accepted, String reason) {
        public Check {
            reason = reason == null ? "" : reason;
        }

        public static Check accept() {
            return new Check(true, "admitted");
        }

        public static Check reject(String reason) {
            return new Check(false, reason);
        }
    }

    /** Outcome of one planning attempt. */
    record Outcome(CapabilityPlan plan, Kind kind, String reason) {
        public Outcome {
            kind = kind == null ? Kind.PLANNED : kind;
            reason = reason == null ? "" : reason;
        }

        public enum Kind {
            /** The engine produced a plan, complete or with a reported shortage. */
            PLANNED,
            /** The engine actively declined the request. */
            DECLINED,
            /** The engine stopped on its own configured deadline or budget. */
            TIMED_OUT
        }

        public static Outcome planned(CapabilityPlan plan) {
            return new Outcome(plan, Kind.PLANNED, "");
        }

        public static Outcome declined(String reason) {
            return new Outcome(null, Kind.DECLINED, reason);
        }

        public static Outcome timedOut(String reason) {
            return new Outcome(null, Kind.TIMED_OUT, reason);
        }
    }
}
