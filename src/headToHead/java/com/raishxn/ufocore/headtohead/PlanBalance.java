package com.raishxn.ufocore.headtohead;

import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityGraph;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityInput;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityOutput;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityPattern;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityScenario;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * Order-free material balance check that works on any engine's plan.
 *
 * <p>Thunderbolt reports firings without a schedule, so the replay oracle of the differential corpus
 * cannot judge it. This check is weaker but symmetric: given the declared firings, stock plus the
 * reported shortage plus everything produced must cover everything consumed plus the request. A
 * plan that fails it is not executable whatever its order; a plan that passes it may still need a
 * bootstrap order on a cyclic graph, which this check does not prove.
 */
final class PlanBalance {

    enum Verdict { OK, FAIL, NOT_CHECKED }

    record Check(Verdict verdict, String detail) {
        @Override
        public String toString() {
            return verdict == Verdict.FAIL ? "FAIL(" + detail + ")" : verdict == Verdict.OK ? "ok" : "n/a";
        }
    }

    static Check check(CapabilityScenario scenario, Engine.Result result) {
        if (!result.planned()) return new Check(Verdict.NOT_CHECKED, "");
        CapabilityGraph graph = scenario.graph();
        Map<String, CapabilityPattern> byId = new HashMap<>();
        for (CapabilityPattern pattern : graph.patterns()) byId.put(pattern.id(), pattern);

        Map<String, BigInteger> net = new HashMap<>();
        Map<String, BigInteger> supplied = new HashMap<>();
        Map<String, BigInteger> presence = new HashMap<>();
        graph.stock().forEach((key, stock) -> {
            if (stock.kind() != CapabilityGraph.CapabilityStock.Kind.EMITTED) {
                add(net, key, stock.amount().asBigInteger());
                add(supplied, key, stock.amount().asBigInteger());
            }
        });
        result.missing().forEach((key, amount) -> {
            add(net, key, amount);
            add(supplied, key, amount);
        });
        for (Map.Entry<String, BigInteger> firing : result.firings().entrySet()) {
            CapabilityPattern pattern = byId.get(firing.getKey());
            if (pattern == null) return new Check(Verdict.FAIL, "unknown pattern " + firing.getKey());
            BigInteger runs = firing.getValue();
            for (CapabilityInput input : pattern.inputs()) {
                BigInteger amount = input.amount().asBigInteger();
                switch (input.kind()) {
                    case EXACT -> add(net, input.key(), amount.multiply(runs).negate());
                    case REUSABLE -> presence.merge(input.key(), amount, BigInteger::max);
                    // Durability, variant substitution and emitters change what is drawn in ways this
                    // simple ledger cannot follow, so a plan that uses them is not judged here.
                    case FINITE_USE, FUZZY, EMITTER -> {
                        return new Check(Verdict.NOT_CHECKED, "");
                    }
                }
            }
            for (CapabilityOutput output : pattern.outputs()) {
                if (output.kind() == CapabilityOutput.Kind.PROBABILISTIC) continue;
                BigInteger produced = output.amount().asBigInteger().multiply(runs);
                add(net, output.key(), produced);
                add(supplied, output.key(), produced);
            }
        }
        add(net, scenario.target(), scenario.amount().asBigInteger().negate());
        for (Map.Entry<String, BigInteger> entry : net.entrySet()) {
            if (entry.getValue().signum() < 0) {
                return new Check(Verdict.FAIL, entry.getKey() + " short by " + entry.getValue().negate());
            }
        }
        for (Map.Entry<String, BigInteger> entry : presence.entrySet()) {
            BigInteger available = supplied.getOrDefault(entry.getKey(), BigInteger.ZERO);
            if (available.compareTo(entry.getValue()) < 0) {
                return new Check(Verdict.FAIL, "catalyst " + entry.getKey() + " never present");
            }
        }
        return new Check(Verdict.OK, "");
    }

    private static void add(Map<String, BigInteger> map, String key, BigInteger amount) {
        map.merge(key, amount, BigInteger::add);
    }

    private PlanBalance() {
    }
}
