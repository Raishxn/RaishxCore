package com.raishxn.ufocore.api.crafting.planner.differential;

import com.raishxn.ufocore.api.amount.UfoAmount;
import com.raishxn.ufocore.api.crafting.planner.CraftingPlan;
import com.raishxn.ufocore.api.crafting.planner.IterativeCraftingPlanner;
import com.raishxn.ufocore.api.crafting.planner.PlanningCancellation;
import com.raishxn.ufocore.api.crafting.planner.PlanningLimits;
import com.raishxn.ufocore.api.crafting.planner.PlanningRequest;
import com.raishxn.ufocore.api.crafting.planner.PlanningResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Drives the differential corpus through the RaishxCore production path.
 *
 * <p>Both entry points use the same public API that {@code Ae2PlannerBridge} uses on its worker:
 * {@link RaishxCoreSemanticModel#lower} into {@link com.raishxn.ufocore.api.crafting.planner.ImmutableCraftingGraph}
 * and {@link IterativeCraftingPlanner#plan} with an explicit {@link PlanningLimits} budget. No test-only
 * shortcut, no access to planner internals and no AE2 fallback is treated as support.
 */
public final class RaishxCoreCapabilityPlanner implements CapabilityPlanner {

    /** Graph revision used for the offline corpus; production passes the captured grid revision. */
    public static final long CORPUS_REVISION = 1L;

    private final PlanningLimits limits;

    public RaishxCoreCapabilityPlanner(PlanningLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Corpus defaults that match the documented configuration ranges without being the IDE defaults. */
    public RaishxCoreCapabilityPlanner(Duration engineTimeout) {
        this(new PlanningLimits(10_000_000L, 100_000, engineTimeout, 128));
    }

    @Override
    public String name() {
        return "RaishxCore";
    }

    @Override
    public Check check(CapabilityScenario scenario) {
        Objects.requireNonNull(scenario, "scenario");
        try {
            RaishxCoreSemanticModel.lower(scenario, CORPUS_REVISION);
            return Check.accept();
        } catch (RaishxCoreSemanticModel.UnsupportedSemantics unsupported) {
            return Check.reject("model cannot represent the request: " + unsupported.getMessage());
        }
    }

    @Override
    public Outcome plan(CapabilityScenario scenario) {
        Objects.requireNonNull(scenario, "scenario");
        RaishxCoreSemanticModel.Lowered lowered;
        try {
            lowered = RaishxCoreSemanticModel.lower(scenario, CORPUS_REVISION);
        } catch (RaishxCoreSemanticModel.UnsupportedSemantics unsupported) {
            return Outcome.declined(unsupported.getMessage());
        }
        PlanningResult<String> result = new IterativeCraftingPlanner<String>().plan(lowered.graph(),
                new PlanningRequest<>(lowered.target(), lowered.amount(), lowered.stock(), limits,
                        PlanningCancellation.NEVER));
        return switch (result.status()) {
            case COMPLETE, MISSING_INGREDIENTS -> Outcome.planned(adapt(result.plan()));
            case TIMED_OUT -> Outcome.timedOut("planner deadline of " + limits.timeout() + " expired");
            case OPERATION_LIMIT -> Outcome.declined("operation budget exhausted");
            case DEPTH_LIMIT -> Outcome.declined("depth budget exhausted");
            case CANCELLED -> Outcome.declined("planning cancelled");
        };
    }

    /** Converts the public plan into the neutral corpus view without narrowing quantities. */
    static CapabilityPlan adapt(CraftingPlan<String> plan) {
        Map<String, UfoAmount> executions = new java.util.LinkedHashMap<>();
        plan.patternExecutions().forEach((pattern, runs) -> executions.put(pattern.id(), runs));
        List<CapabilityPlan.Step> schedule = new ArrayList<>(plan.schedule().size());
        for (CraftingPlan.Execution<String> step : plan.schedule()) {
            schedule.add(new CapabilityPlan.Step(step.pattern().id(), step.runs()));
        }
        return new CapabilityPlan(plan.target(), plan.requested(), executions,
                plan.extractedFromInventory(), plan.missing(), plan.remaining(), schedule,
                plan.complete());
    }
}
