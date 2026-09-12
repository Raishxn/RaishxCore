package com.raishxn.ufocore.benchmark;

import com.moakiee.thunderbolt.core.crafting.planner.*;
import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Test-only adapter to the unmodified local Thunderbolt V2 source checkout. */
public final class ThunderboltReference implements PlannerBenchmark.Reference {
    @Override public Supplier<PlannerBenchmark.Outcome> prepare(PlannerBenchmark.Scenario scenario, boolean cold) {
        CraftGraph<String> graph = graph(scenario);
        return () -> {
            CraftPlan<String> plan = CraftPlannerV2.plan(cold ? graph(scenario) : graph, scenario.target(),
                    scenario.amount().longValueExact());
            Map<String, UfoAmount> runs = new TreeMap<>();
            plan.firings().forEach((pattern, amount) -> runs.put((String) pattern.source(), UfoAmount.of(amount)));
            Map<String, UfoAmount> used = new TreeMap<>();
            plan.usedStock().forEach((key, amount) -> used.put(key, UfoAmount.of(amount)));
            Map<String, UfoAmount> missing = new TreeMap<>();
            plan.missing().forEach((key, amount) -> missing.put(key, UfoAmount.of(amount)));
            return new PlannerBenchmark.Outcome(plan.feasible() ? "COMPLETE" : "MISSING_INGREDIENTS",
                    plan.feasible(), runs, used, missing);
        };
    }
    private static CraftGraph<String> graph(PlannerBenchmark.Scenario scenario) {
        var builder = CraftGraph.<String>builder();
        for (var pattern : scenario.patterns()) {
            String primary = pattern.craftableOutputs().stream().sorted().findFirst().orElseThrow();
            var inputs = new ArrayList<CraftInput<String>>();
            new TreeMap<>(pattern.inputs()).forEach((key, amount) ->
                    inputs.add(CraftInput.of(key, amount.longValueExact())));
            var byproducts = new ArrayList<CraftOutput<String>>();
            new TreeMap<>(pattern.outputs()).forEach((key, amount) -> {
                if (!key.equals(primary)) byproducts.add(CraftOutput.of(key, amount.longValueExact()));
            });
            builder.pattern(new CraftPattern<>(primary, pattern.outputs().get(primary).longValueExact(),
                    inputs, byproducts, pattern.id()));
        }
        scenario.stock().forEach((key, amount) -> builder.stock(key, amount.longValueExact()));
        return builder.build();
    }
}
