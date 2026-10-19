package com.raishxn.ufocore.headtohead;

import com.raishxn.ufocore.api.crafting.planner.CraftingPlan;
import com.raishxn.ufocore.api.crafting.planner.FeedbackCyclePlanner;
import com.raishxn.ufocore.api.crafting.planner.IterativeCraftingPlanner;
import com.raishxn.ufocore.api.crafting.planner.PlanningCancellation;
import com.raishxn.ufocore.api.crafting.planner.PlanningLimits;
import com.raishxn.ufocore.api.crafting.planner.PlanningRequest;
import com.raishxn.ufocore.api.crafting.planner.PlanningResult;
import com.raishxn.ufocore.api.crafting.planner.differential.RaishxCoreSemanticModel;
import java.math.BigInteger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * RaishxCore through the same path the differential corpus and the AE2 bridge use: the semantic
 * lowering, the feedback-cycle adapter and {@link IterativeCraftingPlanner#plan} or
 * {@link IterativeCraftingPlanner#planFast}. Limits are the shipped {@code CoreConfig} defaults.
 */
final class RaishxCoreEngine implements Engine {

    /** CoreConfig defaults: 10M operations, depth 100k, 2 s deadline, checkpoint every 128 steps. */
    static final PlanningLimits PRODUCTION_LIMITS =
            new PlanningLimits(10_000_000L, 100_000, Duration.ofMillis(2_000), 128);

    private final boolean exact;

    RaishxCoreEngine(boolean exact) {
        this.exact = exact;
    }

    @Override
    public String name() {
        return exact ? "RaishxCore.plan" : "RaishxCore.planFast";
    }

    @Override
    public Prepared prepare(BenchCase benchCase) {
        RaishxCoreSemanticModel.Lowered lowered;
        try {
            lowered = RaishxCoreSemanticModel.lower(benchCase.scenario(), 1L);
        } catch (RaishxCoreSemanticModel.UnsupportedSemantics unsupported) {
            throw new Unsupported(unsupported.getMessage());
        }
        Map<String, Long> weights = weights(benchCase.scenario().missingWeights());
        IterativeCraftingPlanner<String> planner = new IterativeCraftingPlanner<>();
        return () -> {
            FeedbackCyclePlanner<String> loops = FeedbackCyclePlanner.analyse(lowered.graph(),
                    lowered.target(), lowered.amount(), lowered.stock());
            PlanningRequest<String> request = new PlanningRequest<>(lowered.target(), lowered.amount(),
                    lowered.stock(), PRODUCTION_LIMITS, PlanningCancellation.NEVER, weights);
            PlanningResult<String> result = exact
                    ? planner.plan(loops.augmentedGraph(), request)
                    : planner.planFast(loops.augmentedGraph(), request);
            return switch (result.status()) {
                case COMPLETE, MISSING_INGREDIENTS -> adapt(loops.expand(result.plan()),
                        result.diagnostics().operations());
                default -> Result.failed(Status.LIMIT, result.status().name());
            };
        };
    }

    /**
     * Which entry point {@code Ae2PlannerBridge} would pick: the fast planner once the graph has at
     * least {@code exactSearchChoiceLimit} (default 8) route-choice alternatives, else the exact one.
     */
    static boolean productionUsesFast(BenchCase benchCase) {
        try {
            return RaishxCoreSemanticModel.lower(benchCase.scenario(), 1L).graph().routeChoiceAlternatives()
                    >= PRODUCTION_EXACT_CHOICE_LIMIT;
        } catch (RaishxCoreSemanticModel.UnsupportedSemantics unsupported) {
            return false;
        }
    }

    static final int PRODUCTION_EXACT_CHOICE_LIMIT = 8;

    private static Result adapt(CraftingPlan<String> plan, long operations) {
        Map<String, BigInteger> firings = new TreeMap<>();
        plan.patternExecutions().forEach((pattern, runs) ->
                firings.merge(pattern.id(), runs.asBigInteger(), BigInteger::add));
        Map<String, BigInteger> missing = new TreeMap<>();
        plan.missing().forEach((key, amount) -> {
            if (!amount.isZero()) missing.put(key, amount.asBigInteger());
        });
        return new Result(missing.isEmpty() ? Status.COMPLETE : Status.SHORTAGE, firings, missing, false,
                "ops=" + operations);
    }

    private static Map<String, Long> weights(Map<String, Double> declared) {
        Map<String, Long> weights = new LinkedHashMap<>();
        declared.forEach((key, weight) -> {
            long exact = Math.round(weight);
            if (exact > 1L) weights.put(key, exact);
        });
        return weights;
    }
}
