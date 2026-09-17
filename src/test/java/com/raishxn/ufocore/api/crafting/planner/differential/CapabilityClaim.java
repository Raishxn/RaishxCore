package com.raishxn.ufocore.api.crafting.planner.differential;

import java.util.Locale;
import java.util.Objects;

/**
 * What an engine <em>claims</em> about one case, using only the fields every planner reports:
 * whether it produced a plan and what shortage that plan declares.
 *
 * <p>The strict replay oracle needs an ordered schedule and a reported residue, which a foreign
 * planner does not have to expose. This verdict is therefore the comparable level: it answers
 * "does the engine claim to solve this case, and how good is the shortage it reports", and it never
 * pretends to be proof. Proof stays with {@link CapabilityPlanReplay} and the RaishxCore gate.
 */
public record CapabilityClaim(Verdict verdict, String detail, double overhead) {

    /** Portable, engine-independent statement about one case. */
    public enum Verdict {
        /** Admission or planning declined the case; the engine does not claim it. */
        UNSUPPORTED,
        /** The engine failed, timed out or produced nothing usable. */
        ERROR,
        /** A plan with no declared shortage. */
        CLAIMS_COMPLETE,
        /** A plan that declares a shortage. */
        CLAIMS_SHORTAGE
    }

    public CapabilityClaim {
        Objects.requireNonNull(verdict, "verdict");
        detail = detail == null ? "" : detail;
    }

    public static CapabilityClaim of(CapabilityScenario scenario, CapabilityRun run) {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(run, "run");

        CapabilityPlan plan = run.plan();
        if (plan == null) {
            return run.classification().safeDecline()
                    ? new CapabilityClaim(Verdict.UNSUPPORTED, run.diagnostic(), Double.NaN)
                    : new CapabilityClaim(Verdict.ERROR,
                            run.classification() + ": " + run.diagnostic(), Double.NaN);
        }

        if (plan.missing().isEmpty()) {
            String agreement = scenario.expectedFeasible() ? "as expected" : "on a case known infeasible";
            return new CapabilityClaim(Verdict.CLAIMS_COMPLETE, agreement, Double.NaN);
        }

        double overhead = scenario.missingOverhead(plan.missing());
        String quality = Double.isNaN(overhead)
                ? "no known minimum to compare"
                : String.format(Locale.ROOT, "reported/known-minimum=%.3f", overhead);
        String disagreement = scenario.expectedFeasible()
                ? "; DISAGREES: the corpus declares this case feasible"
                : "";
        return new CapabilityClaim(Verdict.CLAIMS_SHORTAGE,
                "missing=" + plan.missing() + "; " + quality + disagreement, overhead);
    }

    /** True when the claim matches what the corpus says the case is. */
    public boolean agreesWithExpectation(CapabilityScenario scenario) {
        if (verdict == Verdict.UNSUPPORTED || verdict == Verdict.ERROR) {
            return false;
        }
        boolean claimsComplete = verdict == Verdict.CLAIMS_COMPLETE;
        return claimsComplete == scenario.expectedFeasible();
    }

    /** True when the engine claims a shortage on a case the corpus declares feasible. */
    public boolean isFalseNegative(CapabilityScenario scenario) {
        return verdict == Verdict.CLAIMS_SHORTAGE && scenario.expectedFeasible();
    }

    /** True when the engine claims completion on a case the corpus declares infeasible. */
    public boolean isFalsePositive(CapabilityScenario scenario) {
        return verdict == Verdict.CLAIMS_COMPLETE && !scenario.expectedFeasible();
    }
}
