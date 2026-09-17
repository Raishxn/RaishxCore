package com.raishxn.ufocore.api.crafting.planner.differential;

import java.util.Objects;

/** Auditable outcome of one executed capability case. */
public record CapabilityRun(CapabilityScenario scenario,
                            CapabilityClassification classification,
                            long elapsedNanos,
                            double missingOverhead,
                            CapabilityPlan plan,
                            CapabilityPlan refillPlan,
                            CapabilityPlanReplay.Report replay,
                            CapabilityPlanReplay.Report refillReplay,
                            Throwable failure,
                            String diagnostic) {

    public CapabilityRun {
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(classification, "classification");
        diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public boolean supported() {
        return classification == CapabilityClassification.SUPPORTED;
    }

    /** Reported shortage of the primary attempt, empty when no plan or no shortage exists. */
    public java.util.Map<String, com.raishxn.ufocore.api.amount.UfoAmount> reportedMissing() {
        return plan == null ? java.util.Map.of() : plan.missing();
    }
}
