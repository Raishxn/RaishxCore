package com.raishxn.ufocore.api.crafting.planner.differential;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Common replay oracle for the differential corpus.
 *
 * <p>The oracle never trusts the plan's own bookkeeping. It re-executes the reported schedule over
 * the declared inventory, then compares the simulated residue with the declared one. Every
 * quantity is an exact {@link UfoAmount}; nothing is narrowed to {@code long} or {@code double}.
 */
public final class CapabilityPlanReplay {

    private CapabilityPlanReplay() {
    }

    /** Everything the oracle observed, including the reason for any failure. */
    public record Report(boolean valid,
                         List<String> failures,
                         List<String> findings,
                         Map<String, UfoAmount> stockUsed,
                         Map<String, UfoAmount> injectedUsed,
                         Map<String, UfoAmount> neededMissing,
                         Map<String, UfoAmount> leftover,
                         Map<String, UfoAmount> produced,
                         Map<String, UfoAmount> overproduction,
                         Map<String, UfoAmount> byproducts,
                         UfoAmount targetAvailable,
                         UfoAmount executedRuns) {

        public Report {
            failures = List.copyOf(failures);
            findings = List.copyOf(findings);
            Objects.requireNonNull(targetAvailable, "targetAvailable");
            Objects.requireNonNull(executedRuns, "executedRuns");
        }

        public String summary() {
            String base = valid ? "replay ok runs=" + executedRuns + " leftover=" + leftover
                    : "replay failed: " + failures;
            return findings.isEmpty() ? base : base + " findings=" + findings;
        }
    }

    /**
     * Replays {@code plan} against {@code graph}.
     *
     * @param supplyReportedMissing when true the reported shortage is injected, which proves the
     *                              report is sufficient; when false the plan must complete from the
     *                              declared inventory alone, which is the false-positive detector
     */
    public static Report replay(CapabilityGraph graph, CapabilityPlan plan, boolean supplyReportedMissing) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(plan, "plan");
        ArrayList<String> failures = new ArrayList<>();
        ArrayList<String> findings = new ArrayList<>();

        Map<String, CapabilityPattern> byId = new LinkedHashMap<>();
        for (CapabilityPattern pattern : graph.patterns()) {
            byId.put(pattern.id(), pattern);
        }
        if (byId.size() != graph.patterns().size()) {
            failures.add("graph declares duplicate pattern ids");
        }

        LinkedHashMap<String, UfoAmount> declaredExecutions = new LinkedHashMap<>();
        for (Map.Entry<String, UfoAmount> entry : plan.executions().entrySet()) {
            CapabilityPattern pattern = byId.get(entry.getKey());
            if (pattern == null) {
                failures.add("plan executes undeclared pattern " + entry.getKey());
                continue;
            }
            if (entry.getValue().isZero()) {
                failures.add("plan executes " + entry.getKey() + " zero times");
                continue;
            }
            if (!isReplayable(pattern)) {
                failures.add("plan executes " + entry.getKey() + " with semantics the oracle cannot replay");
            }
            declaredExecutions.merge(entry.getKey(), entry.getValue(), UfoAmount::add);
        }

        LinkedHashMap<String, UfoAmount> scheduledExecutions = new LinkedHashMap<>();
        for (CapabilityPlan.Step step : plan.schedule()) {
            CapabilityPattern pattern = byId.get(step.patternId());
            if (pattern == null) {
                failures.add("schedule references undeclared pattern " + step.patternId());
                continue;
            }
            scheduledExecutions.merge(step.patternId(), step.runs(), UfoAmount::add);
        }
        if (!scheduledExecutions.equals(declaredExecutions)) {
            failures.add("schedule does not match declared executions: " + scheduledExecutions
                    + " != " + declaredExecutions);
        }

        Map<String, UfoAmount> demand = new LinkedHashMap<>();
        Map<String, UfoAmount> produced = new LinkedHashMap<>();
        for (Map.Entry<String, UfoAmount> entry : plan.executions().entrySet()) {
            CapabilityPattern pattern = byId.get(entry.getKey());
            if (pattern == null) {
                continue;
            }
            for (CapabilityInput input : pattern.inputs()) {
                // A catalyst is never consumed, so it is not demand: the seed stays in remaining, and
                // that is what the balance below expects to find there.
                if (presenceOnly(input) || externallySatisfied(input)) continue;
                addInto(demand, input.key(), draw(input, entry.getValue()));
            }
            for (CapabilityOutput output : pattern.outputs()) {
                addInto(produced, output.key(), output.amount().multiply(entry.getValue().asBigInteger()));
            }
        }

        // A key that only ever serves as a catalyst has no flow to balance: the seed is not consumed,
        // so supplying the reported shortage leaves it exactly where it was, and the declared balance
        // would have to charge the plan for a unit it never used. Its requirement is verified by the
        // presence check in the ordered replay instead, which is stricter than a balance.
        Set<String> catalystsOnly = new LinkedHashSet<>();
        Set<String> flowing = new LinkedHashSet<>();
        for (CapabilityPattern pattern : graph.patterns()) {
            for (CapabilityInput input : pattern.inputs()) {
                if (presenceOnly(input)) {
                    catalystsOnly.add(input.key());
                } else {
                    flowing.add(input.key());
                }
            }
            for (CapabilityOutput output : pattern.outputs()) {
                flowing.add(output.key());
            }
        }
        catalystsOnly.removeAll(flowing);

        // Balance of the declared numbers, independent of any execution order.
        for (String key : unionKeys(graph, plan, demand, produced)) {
            if (catalystsOnly.contains(key)) {
                continue;
            }
            UfoAmount expected = stock(graph).getOrDefault(key, UfoAmount.ZERO)
                    .add(produced.getOrDefault(key, UfoAmount.ZERO))
                    .add(plan.missing().getOrDefault(key, UfoAmount.ZERO));
            UfoAmount observed = plan.remaining().getOrDefault(key, UfoAmount.ZERO)
                    .add(demand.getOrDefault(key, UfoAmount.ZERO))
                    .add(request(plan, key));
            if (!expected.equals(observed)) {
                failures.add("declared balance broken for " + key + ": available=" + expected
                        + " accounted=" + observed);
            }
        }
        for (Map.Entry<String, UfoAmount> entry : plan.extractedFromInventory().entrySet()) {
            UfoAmount available = stock(graph).getOrDefault(entry.getKey(), UfoAmount.ZERO);
            if (entry.getValue().compareTo(available) > 0) {
                failures.add("plan extracts " + entry.getValue() + " " + entry.getKey()
                        + " above the declared stock " + available);
            }
        }

        // Independent execution of the reported order.
        Pool crafted = new Pool();
        Pool consumable = new Pool();
        stock(graph).forEach(consumable::add);
        Pool injected = new Pool();
        if (supplyReportedMissing) {
            plan.missing().forEach(injected::add);
        }
        Pool stockUsed = new Pool();
        Pool injectedUsed = new Pool();
        UfoAmount executedRuns = UfoAmount.ZERO;

        for (CapabilityPlan.Step step : plan.schedule()) {
            CapabilityPattern pattern = byId.get(step.patternId());
            if (pattern == null) {
                continue;
            }
            executedRuns = executedRuns.add(step.runs());
            for (CapabilityInput input : pattern.inputs()) {
                if (externallySatisfied(input)) continue;
                if (presenceOnly(input)) {
                    // Presence once, drawn never: one seed serves every execution and must still be
                    // in its pool after the step. A fuzzy slot is satisfied by any accepted variant,
                    // so presence is their total.
                    UfoAmount present = UfoAmount.ZERO;
                    for (String accepted : acceptedKeys(input)) {
                        present = present.add(crafted.get(accepted))
                                .add(consumable.get(accepted))
                                .add(injected.get(accepted));
                    }
                    if (present.compareTo(input.amount()) < 0) {
                        failures.add(supplyReportedMissing
                                ? "reported shortage is insufficient for catalyst " + input.key()
                                : "unfunded catalyst " + input.key() + " at " + pattern.id());
                    }
                    continue;
                }
                UfoAmount need = draw(input, step.runs());
                need = crafted.take(input.key(), need);
                UfoAmount fromStock = consumable.take(input.key(), need);
                UfoAmount stockDraw = need.subtract(fromStock);
                if (!stockDraw.isZero()) {
                    stockUsed.add(input.key(), stockDraw);
                }
                need = fromStock;
                UfoAmount notInjected = injected.take(input.key(), need);
                UfoAmount injectedDraw = need.subtract(notInjected);
                if (!injectedDraw.isZero()) {
                    injectedUsed.add(input.key(), injectedDraw);
                }
                if (!notInjected.isZero()) {
                    failures.add(supplyReportedMissing
                            ? "reported shortage is insufficient for " + input.key()
                            : "unfunded input " + input.key() + " at " + pattern.id());
                }
            }
            for (CapabilityOutput output : pattern.outputs()) {
                if (output.kind() == CapabilityOutput.Kind.PROBABILISTIC) {
                    failures.add("probabilistic output " + output.key() + " cannot be replayed");
                    continue;
                }
                crafted.add(output.key(), output.amount().multiply(step.runs().asBigInteger()));
            }
        }

        UfoAmount targetAvailable = crafted.get(plan.target())
                .add(consumable.get(plan.target()))
                .add(injected.get(plan.target()));
        UfoAmount need = plan.requested();
        need = crafted.take(plan.target(), need);
        UfoAmount fromStock = consumable.take(plan.target(), need);
        UfoAmount stockDraw = need.subtract(fromStock);
        if (!stockDraw.isZero()) {
            stockUsed.add(plan.target(), stockDraw);
        }
        UfoAmount notInjected = injected.take(plan.target(), fromStock);
        UfoAmount injectedDraw = fromStock.subtract(notInjected);
        if (!injectedDraw.isZero()) {
            injectedUsed.add(plan.target(), injectedDraw);
        }
        if (!notInjected.isZero()) {
            failures.add(supplyReportedMissing
                    ? "reported shortage cannot satisfy the requested " + plan.target()
                    : "requested target " + plan.target() + " is not produced");
        }

        // Under-declaring the stock withdrawal would fail an AE2 commit, so it is a hard failure.
        for (Map.Entry<String, UfoAmount> entry : stockUsed.snapshot().entrySet()) {
            UfoAmount declared = plan.extractedFromInventory()
                    .getOrDefault(entry.getKey(), UfoAmount.ZERO);
            if (entry.getValue().compareTo(declared) > 0) {
                failures.add("replay withdraws " + entry.getValue() + " " + entry.getKey()
                        + " from the declared stock but the plan declares only " + declared);
            }
        }

        LinkedHashMap<String, UfoAmount> leftover = new LinkedHashMap<>(crafted.snapshot());
        consumable.snapshot().forEach((key, amount) -> addInto(leftover, key, amount));
        if (plan.complete() && !leftover.equals(plan.remaining())) {
            failures.add("replayed residue " + leftover + " differs from declared " + plan.remaining());
        }

        LinkedHashMap<String, UfoAmount> neededMissing = new LinkedHashMap<>(injectedUsed.snapshot());
        if (supplyReportedMissing) {
            for (String key : neededMissing.keySet()) {
                if (!plan.missing().containsKey(key)) {
                    failures.add("reported shortage under-reports the consumed " + key);
                }
            }
            for (String key : plan.missing().keySet()) {
                // A catalyst seed is the one shortage that is correctly reported and correctly never
                // consumed, so it is not an unused-material finding.
                if (!neededMissing.containsKey(key) && !catalystsOnly.contains(key)) {
                    findings.add("reported shortage includes unused material " + key);
                }
            }
        }

        LinkedHashMap<String, UfoAmount> overproduction = new LinkedHashMap<>();
        UfoAmount overproducedUnits = UfoAmount.ZERO;
        for (Map.Entry<String, UfoAmount> entry : leftover.entrySet()) {
            overproduction.put(entry.getKey(), entry.getValue());
            overproducedUnits = overproducedUnits.add(entry.getValue());
        }
        LinkedHashMap<String, UfoAmount> byproducts = new LinkedHashMap<>();
        for (CapabilityPattern pattern : graph.patterns()) {
            for (CapabilityOutput output : pattern.outputs()) {
                if (output.kind() != CapabilityOutput.Kind.BYPRODUCT
                        || output.key().equals(plan.target())) {
                    continue;
                }
                UfoAmount amount = leftover.get(output.key());
                if (amount != null && !amount.isZero()) {
                    byproducts.put(output.key(), amount);
                }
            }
        }

        return new Report(failures.isEmpty(), failures, findings, stockUsed.snapshot(),
                injectedUsed.snapshot(), neededMissing, leftover, produced, overproduction, byproducts,
                targetAvailable, executedRuns);
    }

    /**
     * True when every pattern the plan can execute has a shape this oracle can re-execute: exact and
     * reusable inputs only, and no probabilistic output. A catalyst is replayable because the oracle
     * checks that it is present and hands it back rather than drawing it.
     */
    public static boolean isReplayable(CapabilityGraph graph) {
        return graph.patterns().stream().allMatch(CapabilityPlanReplay::isReplayable);
    }

    private static boolean isReplayable(CapabilityPattern pattern) {
        return pattern.inputs().stream().allMatch(input ->
                        input.kind() == CapabilityInput.Kind.EXACT
                                || input.kind() == CapabilityInput.Kind.REUSABLE
                                || input.kind() == CapabilityInput.Kind.FINITE_USE
                                || input.kind() == CapabilityInput.Kind.FUZZY
                                || input.kind() == CapabilityInput.Kind.EMITTER)
                && pattern.outputs().stream().noneMatch(output ->
                        output.kind() == CapabilityOutput.Kind.PROBABILISTIC);
    }

    /** The keys that may satisfy a slot: any accepted variant for a fuzzy one, else just its key. */
    private static List<String> acceptedKeys(CapabilityInput input) {
        return input.kind() == CapabilityInput.Kind.FUZZY ? input.alternatives() : List.of(input.key());
    }

    /** True when a slot is required to be present and is never drawn. */
    private static boolean presenceOnly(CapabilityInput input) {
        return input.kind() == CapabilityInput.Kind.REUSABLE
                || input.kind() == CapabilityInput.Kind.FUZZY;
    }

    /** True when an authorized external source satisfies the slot, so the plan owes nothing for it. */
    private static boolean externallySatisfied(CapabilityInput input) {
        return input.kind() == CapabilityInput.Kind.EMITTER;
    }

    /**
     * Units one batch of {@code runs} firings draws of an input. A durable carrier is consumed, but
     * one unit survives several firings, so a batch needs one unit per {@code uses} firings rather
     * than one per firing.
     */
    private static UfoAmount draw(CapabilityInput input, UfoAmount runs) {
        if (input.kind() != CapabilityInput.Kind.FINITE_USE) {
            return input.amount().multiply(runs.asBigInteger());
        }
        BigInteger[] quotient = runs.asBigInteger().divideAndRemainder(BigInteger.valueOf(input.uses()));
        BigInteger carriers = quotient[1].signum() == 0 ? quotient[0] : quotient[0].add(BigInteger.ONE);
        return input.amount().multiply(carriers);
    }

    private static UfoAmount request(CapabilityPlan plan, String key) {
        return key.equals(plan.target()) ? plan.requested() : UfoAmount.ZERO;
    }

    private static Map<String, UfoAmount> stock(CapabilityGraph graph) {
        LinkedHashMap<String, UfoAmount> consumable = new LinkedHashMap<>();
        graph.stock().forEach((key, entry) -> {
            // A host-owned reusable seed is inventory the plan may rely on. It is never drawn, which
            // is what keeps the declared balance intact for a catalyst.
            if (entry.kind() == CapabilityGraph.CapabilityStock.Kind.CONSUMABLE
                    || entry.kind() == CapabilityGraph.CapabilityStock.Kind.REUSABLE) {
                consumable.put(key, entry.amount());
            }
        });
        return consumable;
    }

    private static List<String> unionKeys(CapabilityGraph graph, CapabilityPlan plan,
                                          Map<String, UfoAmount> demand, Map<String, UfoAmount> produced) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(stock(graph).keySet());
        keys.addAll(plan.remaining().keySet());
        keys.addAll(plan.missing().keySet());
        keys.addAll(plan.extractedFromInventory().keySet());
        keys.addAll(demand.keySet());
        keys.addAll(produced.keySet());
        keys.add(plan.target());
        ArrayList<String> ordered = new ArrayList<>(keys);
        Collections.sort(ordered);
        return ordered;
    }

    private static void addInto(Map<String, UfoAmount> map, String key, UfoAmount amount) {
        if (amount.isZero()) {
            return;
        }
        map.merge(key, amount, UfoAmount::add);
    }

    /** Small consumable bucket: crafted, stock or injected. */
    private static final class Pool {
        private final Map<String, UfoAmount> amounts = new LinkedHashMap<>();

        void add(String key, UfoAmount amount) {
            addInto(amounts, key, amount);
        }

        UfoAmount get(String key) {
            return amounts.getOrDefault(key, UfoAmount.ZERO);
        }

        /** Removes and returns the part of {@code requested} this pool could not cover. */
        UfoAmount take(String key, UfoAmount requested) {
            UfoAmount stored = get(key);
            UfoAmount taken = stored.min(requested);
            if (!taken.isZero()) {
                UfoAmount rest = stored.subtract(taken);
                if (rest.isZero()) {
                    amounts.remove(key);
                } else {
                    amounts.put(key, rest);
                }
            }
            return requested.subtract(taken);
        }

        Map<String, UfoAmount> snapshot() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(amounts));
        }
    }
}
