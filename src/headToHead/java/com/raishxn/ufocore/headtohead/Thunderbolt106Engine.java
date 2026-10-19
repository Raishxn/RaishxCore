package com.raishxn.ufocore.headtohead;

import com.moakiee.thunderbolt.core.planner.CraftGraph;
import com.moakiee.thunderbolt.core.planner.CraftInput;
import com.moakiee.thunderbolt.core.planner.CraftOutput;
import com.moakiee.thunderbolt.core.planner.CraftPattern;
import com.moakiee.thunderbolt.core.planner.CraftPlan;
import com.moakiee.thunderbolt.core.planner.CraftPlannerV2;
import com.moakiee.thunderbolt.core.planner.ReusableStockSource;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityGraph;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityInput;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityOutput;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityPattern;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityScenario;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The unmodified Thunderbolt 1.0.6 {@code CraftPlannerV2}, called through its public
 * {@link CraftPlannerV2#plan(CraftGraph, Object, long)} entry point with its default visit cap and
 * scaled search budget, exactly as the AE2 integration calls it.
 *
 * <p>The lowering mirrors {@code ThunderboltCapabilityPlanner} (the corpus adapter for Thunderbolt
 * 2.0), retargeted at the 1.0.6 package. One deliberate difference: a probabilistic output is dropped
 * rather than refused, which is the same reading the RaishxCore lowering takes, so a chance output
 * never decides support on its own.
 */
final class Thunderbolt106Engine implements Engine {

    @Override
    public String name() {
        return "Thunderbolt-1.0.6";
    }

    @Override
    public Prepared prepare(BenchCase benchCase) {
        CapabilityScenario scenario = benchCase.scenario();
        CraftGraph<String> graph = lower(scenario);
        String target = scenario.target();
        long amount;
        try {
            amount = scenario.amount().longValueExact();
        } catch (ArithmeticException tooLarge) {
            throw new Unsupported("request amount does not fit a long");
        }
        return () -> adapt(CraftPlannerV2.plan(graph, target, amount));
    }

    private static Result adapt(CraftPlan<String> plan) {
        if (!plan.supported()) {
            return Result.failed(Status.UNSUPPORTED, "planner reported the request as unsupported");
        }
        Map<String, BigInteger> firings = new TreeMap<>();
        plan.firings().forEach((pattern, runs) -> {
            if (runs != null && runs != 0L) {
                firings.merge(String.valueOf(pattern.source()), BigInteger.valueOf(runs), BigInteger::add);
            }
        });
        Map<String, BigInteger> missing = new TreeMap<>();
        plan.missing().forEach((key, amount) -> {
            if (amount != null && amount != 0L) missing.merge(key, BigInteger.valueOf(amount), BigInteger::add);
        });
        boolean complete = plan.feasible() && missing.isEmpty();
        return new Result(complete ? Status.COMPLETE : Status.SHORTAGE, firings, missing,
                plan.budgetExhausted(), "items=" + plan.itemsProcessed());
    }

    static CraftGraph<String> lower(CapabilityScenario scenario) {
        CraftGraph.Builder<String> builder = CraftGraph.builder();
        for (CapabilityPattern pattern : scenario.graph().patterns()) {
            CapabilityOutput primary = primaryOutput(pattern);
            List<CraftInput<String>> inputs = new ArrayList<>(pattern.inputs().size());
            for (CapabilityInput input : pattern.inputs()) {
                CraftInput<String> lowered = lowerInput(builder, input);
                if (lowered != null) inputs.add(lowered);
            }
            List<CraftOutput<String>> byproducts = new ArrayList<>();
            for (CapabilityOutput output : pattern.outputs()) {
                if (output.key().equals(primary.key())) continue;
                if (output.kind() == CapabilityOutput.Kind.PROBABILISTIC) continue;
                byproducts.add(CraftOutput.of(output.key(), longOf(output.amount().asBigInteger())));
            }
            builder.pattern(new CraftPattern<>(primary.key(), longOf(primary.amount().asBigInteger()),
                    inputs, byproducts, pattern.id()));
        }
        for (Map.Entry<String, CapabilityGraph.CapabilityStock> entry : scenario.graph().stock().entrySet()) {
            CapabilityGraph.CapabilityStock stock = entry.getValue();
            long amount = longOf(stock.amount().asBigInteger());
            switch (stock.kind()) {
                case CONSUMABLE -> builder.stock(entry.getKey(), amount);
                case REUSABLE -> builder.reusableStock(stock.host(), entry.getKey(), amount);
                case EMITTED -> throw new Unsupported("emitted stock for " + entry.getKey());
            }
        }
        return builder.build();
    }

    /** Emitter inputs are satisfied by an external source, so they lower to no requirement at all. */
    private static CraftInput<String> lowerInput(CraftGraph.Builder<String> builder, CapabilityInput input) {
        long amount = longOf(input.amount().asBigInteger());
        return switch (input.kind()) {
            case EXACT -> CraftInput.of(input.key(), amount);
            case REUSABLE -> CraftInput.returnedFrom(input.key(), amount, source(input.host()));
            case FINITE_USE -> CraftInput.finiteUse(input.key(), amount, input.uses());
            case FUZZY -> {
                ReusableStockSource source = source(input.host());
                builder.reusableStockRoute(source, input.key(), input.alternatives());
                yield CraftInput.returnedFrom(input.key(), amount, source);
            }
            case EMITTER -> null;
        };
    }

    private static ReusableStockSource source(String host) {
        return new ReusableStockSource(host, host, host);
    }

    private static CapabilityOutput primaryOutput(CapabilityPattern pattern) {
        for (CapabilityOutput output : pattern.outputs()) {
            if (output.kind() == CapabilityOutput.Kind.PRIMARY) return output;
        }
        throw new Unsupported("pattern " + pattern.id() + " declares no primary output");
    }

    private static long longOf(BigInteger value) {
        if (value.bitLength() > 63) throw new Unsupported("amount " + value + " does not fit a long");
        return value.longValueExact();
    }
}
