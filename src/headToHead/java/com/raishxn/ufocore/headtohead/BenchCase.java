package com.raishxn.ufocore.headtohead;

import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityScenario;
import java.util.Objects;

/**
 * One head-to-head case: a neutral scenario both planners are lowered from, plus where it came from.
 *
 * <p>{@code optimumKnown} says whether {@link CapabilityScenario#missingOverhead} and
 * {@link CapabilityScenario#expectedFeasible()} are a real reference. The corpus and the regression
 * shapes carry one; the generated graphs do not, so for them the planners are only compared with
 * each other.
 */
record BenchCase(String id, String origin, CapabilityScenario scenario, boolean optimumKnown) {

    BenchCase {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(scenario, "scenario");
    }

    int patternCount() {
        return scenario.graph().patterns().size();
    }
}
