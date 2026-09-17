package com.raishxn.ufocore.api.crafting.planner.differential;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;
import com.moakiee.thunderbolt.core.crafting.planner.CraftGraph;
import com.moakiee.thunderbolt.core.crafting.planner.CraftInput;
import com.moakiee.thunderbolt.core.crafting.planner.CraftOutput;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPattern;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPlan;
import com.moakiee.thunderbolt.core.crafting.planner.CraftPlannerV2;
import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Drives the differential corpus through the unmodified Thunderbolt V2 reference planner.
 *
 * <p>This adapter is the mirror image of {@link RaishxCoreCapabilityPlanner}: it lowers the neutral
 * model into Thunderbolt's own public builder and never reaches into its internals. When the
 * adapter cannot express a corpus concept it says so through {@link AdapterGap} and reports a clean
 * refusal, because a wrong lowering would be worse than a missing measurement.
 */
public final class ThunderboltCapabilityPlanner implements CapabilityPlanner {

    /** Raised when this adapter cannot express a corpus concept in Thunderbolt's model. */
    public static final class AdapterGap extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public AdapterGap(String message) {
            super(message);
        }
    }

    @Override
    public String name() {
        return "ThunderboltV2";
    }

    @Override
    public Check check(CapabilityScenario scenario) {
        try {
            lower(scenario);
            return Check.accept();
        } catch (AdapterGap | ArithmeticException gap) {
            return Check.reject("adapter cannot express this case: " + gap.getMessage());
        }
    }

    @Override
    public Outcome plan(CapabilityScenario scenario) {
        CraftGraph<String> graph;
        try {
            graph = lower(scenario);
        } catch (AdapterGap | ArithmeticException gap) {
            return Outcome.declined("adapter cannot express this case: " + gap.getMessage());
        }
        CraftPlan<String> plan = CraftPlannerV2.plan(graph, scenario.target(),
                scenario.amount().longValueExact());
        if (!plan.supported()) {
            return Outcome.declined("Thunderbolt reported the request as unsupported");
        }
        try {
            return Outcome.planned(adapt(scenario, plan));
        } catch (AdapterGap gap) {
            return Outcome.declined("adapter cannot express this case: " + gap.getMessage());
        }
    }

    /** Lowers one neutral scenario into Thunderbolt's public graph model. */
    static CraftGraph<String> lower(CapabilityScenario scenario) {
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        for (CapabilityPattern pattern : scenario.graph().patterns()) {
            CapabilityOutput primary = primaryOutput(pattern);
            List<CraftInput<String>> inputs = new ArrayList<>(pattern.inputs().size());
            for (CapabilityInput input : pattern.inputs()) {
                inputs.add(lowerInput(pattern, input));
            }
            List<CraftOutput<String>> byproducts = new ArrayList<>();
            for (CapabilityOutput output : pattern.outputs()) {
                if (output.key().equals(primary.key())) continue;
                if (output.kind() == CapabilityOutput.Kind.PROBABILISTIC) {
                    throw new AdapterGap("probabilistic output " + output.key()
                            + " in pattern " + pattern.id());
                }
                byproducts.add(CraftOutput.of(output.key(), output.amount().longValueExact()));
            }
            builder.pattern(new CraftPattern<>(primary.key(), primary.amount().longValueExact(),
                    inputs, byproducts, pattern.id()));
        }

        for (Map.Entry<String, CapabilityGraph.CapabilityStock> entry : scenario.graph().stock().entrySet()) {
            String key = entry.getKey();
            CapabilityGraph.CapabilityStock stock = entry.getValue();
            long amount = stock.amount().longValueExact();
            switch (stock.kind()) {
                case CONSUMABLE -> builder.stock(key, amount);
                case REUSABLE -> builder.reusableStock(stock.host(), key, amount);
                case EMITTED -> throw new AdapterGap("emitted stock for " + key);
            }
        }
        return builder.build();
    }

    private static CraftInput<String> lowerInput(CapabilityPattern pattern, CapabilityInput input) {
        long amount = input.amount().longValueExact();
        return switch (input.kind()) {
            case EXACT -> CraftInput.of(input.key(), amount);
            case REUSABLE -> CraftInput.returnedFrom(input.key(), amount,
                    // The storage scope must match the scope the seed stock is declared under, or the
                    // planner never finds the seed and reports a shortage the corpus does not expect.
                    new ReusableStockSource(input.host(), input.host()));
            case FINITE_USE -> CraftInput.finiteUse(input.key(), amount, input.uses());
            case FUZZY -> throw new AdapterGap("fuzzy input " + input.key() + " in " + pattern.id());
            case EMITTER -> throw new AdapterGap("emitter input " + input.key() + " in " + pattern.id());
        };
    }

    /** The output a Thunderbolt pattern is keyed on: the first declared primary, in declaration order. */
    private static CapabilityOutput primaryOutput(CapabilityPattern pattern) {
        for (CapabilityOutput output : pattern.outputs()) {
            if (output.kind() == CapabilityOutput.Kind.PRIMARY) {
                return output;
            }
        }
        throw new AdapterGap("pattern " + pattern.id() + " declares no primary output");
    }

    /** Converts Thunderbolt's plan into the neutral corpus view without narrowing quantities. */
    static CapabilityPlan adapt(CapabilityScenario scenario, CraftPlan<String> plan) {
        Map<String, UfoAmount> executions = new TreeMap<>();
        plan.firings().forEach((pattern, runs) ->
                executions.merge(String.valueOf(pattern.source()), UfoAmount.of(runs), UfoAmount::add));
        Map<String, UfoAmount> used = new TreeMap<>();
        plan.usedStock().forEach((key, amount) -> {
            if (amount != null && amount != 0L) {
                used.merge(key, UfoAmount.of(amount), UfoAmount::add);
            }
        });
        Map<String, UfoAmount> missing = new TreeMap<>();
        plan.missing().forEach((key, amount) -> {
            if (amount != null && amount != 0L) {
                missing.merge(key, UfoAmount.of(amount), UfoAmount::add);
            }
        });
        // Thunderbolt's plan carries no ordered schedule and no leftover report. The shared replay
        // oracle re-executes the schedule in order, so the adapter derives a dependency order from
        // the declared firings. A cyclic graph has no such order, and inventing one would be a lie;
        // the schedule is left empty there, which keeps the claim fields - Thunderbolt's own
        // firings, used stock and shortage - readable while making a replay verdict impossible.
        List<CapabilityPlan.Step> schedule;
        try {
            schedule = synthesiseSchedule(scenario, executions);
        } catch (AdapterGap cyclic) {
            schedule = List.of();
        }
        return new CapabilityPlan(scenario.target(), scenario.amount(), executions, used, missing,
                Map.of(), schedule, missing.isEmpty());
    }

    /**
     * Orders the declared firings so every pattern runs after the patterns that feed it.
     *
     * @throws AdapterGap when the dependency graph has a cycle, because no execution order exists
     *     and inventing one would turn an adapter limitation into a false verdict on the reference
     */
    static List<CapabilityPlan.Step> synthesiseSchedule(CapabilityScenario scenario,
                                                        Map<String, UfoAmount> executions) {
        Map<String, Set<String>> producers = new LinkedHashMap<>();
        for (CapabilityPattern pattern : scenario.graph().patterns()) {
            for (String key : pattern.craftableOutputs()) {
                producers.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(pattern.id());
            }
        }

        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (CapabilityPattern pattern : scenario.graph().patterns()) {
            Set<String> dependsOn = new LinkedHashSet<>();
            for (CapabilityInput input : pattern.inputs()) {
                Set<String> candidates = producers.get(input.key());
                if (candidates != null) {
                    dependsOn.addAll(candidates);
                }
            }
            dependencies.put(pattern.id(), dependsOn);
        }

        List<String> order = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        Set<String> undecided = new LinkedHashSet<>(dependencies.keySet());
        while (!undecided.isEmpty()) {
            boolean progress = false;
            for (Iterator<String> iterator = undecided.iterator(); iterator.hasNext(); ) {
                String id = iterator.next();
                if (done.containsAll(dependencies.get(id))) {
                    order.add(id);
                    done.add(id);
                    iterator.remove();
                    progress = true;
                }
            }
            if (!progress) {
                throw new AdapterGap("Thunderbolt's plan exposes no execution order and this graph "
                        + "has a dependency cycle");
            }
        }

        List<CapabilityPlan.Step> steps = new ArrayList<>();
        for (String id : order) {
            UfoAmount runs = executions.get(id);
            if (runs != null && !runs.isZero()) {
                steps.add(new CapabilityPlan.Step(id, runs));
            }
        }
        return List.copyOf(steps);
    }
}
