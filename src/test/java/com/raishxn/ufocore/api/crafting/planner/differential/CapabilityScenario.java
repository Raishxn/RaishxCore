package com.raishxn.ufocore.api.crafting.planner.differential;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * One behavioural capability case: a graph, an inventory mode and the expected result.
 *
 * <p>This record is the independent specification recreated from the published reference standard.
 * It never references another planner's production classes; the neutral model is the contract.
 */
public record CapabilityScenario(String id,
                                 CapabilityFamily family,
                                 CapabilityMaterialMode mode,
                                 int scale,
                                 CapabilityGraph graph,
                                 String target,
                                 UfoAmount amount,
                                 boolean expectedFeasible,
                                 List<Map<String, UfoAmount>> minimalMissing,
                                 Map<String, Double> missingWeights,
                                 boolean uniqueMinimalMissing,
                                 Set<CapabilitySemantics> requiredSemantics,
                                 CapabilityExpectation expectation,
                                 Function<Map<String, UfoAmount>, CapabilityGraph> refill) {

    public CapabilityScenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(minimalMissing, "minimalMissing");
        Objects.requireNonNull(requiredSemantics, "requiredSemantics");
        Objects.requireNonNull(expectation, "expectation");
        Objects.requireNonNull(refill, "refill");
        if (id.isBlank()) throw new IllegalArgumentException("scenario id must not be blank");
        if (target.isBlank()) throw new IllegalArgumentException("scenario target must not be blank");
        if (scale < 0) throw new IllegalArgumentException("scale must be non-negative");
        if (amount.isZero()) throw new IllegalArgumentException("requested amount must be positive");
        ArrayList<Map<String, UfoAmount>> normalized = new ArrayList<>();
        for (Map<String, UfoAmount> candidate : minimalMissing) {
            LinkedHashMap<String, UfoAmount> clean = new LinkedHashMap<>();
            candidate.forEach((key, value) -> {
                if (value != null && !value.isZero()) {
                    clean.put(key, value);
                }
            });
            if (!clean.isEmpty()) {
                normalized.add(Collections.unmodifiableMap(clean));
            }
        }
        minimalMissing = List.copyOf(normalized);
        missingWeights = Map.copyOf(Objects.requireNonNullElse(missingWeights, Map.of()));
        requiredSemantics = Set.copyOf(requiredSemantics);
        if (expectedFeasible && !minimalMissing.isEmpty()) {
            throw new IllegalArgumentException("feasible scenarios cannot declare a shortage baseline");
        }
        if (!expectedFeasible && minimalMissing.isEmpty()) {
            throw new IllegalArgumentException("infeasible scenarios need a shortage baseline");
        }
        if (uniqueMinimalMissing != (minimalMissing.size() == 1) && uniqueMinimalMissing) {
            throw new IllegalArgumentException("a unique minimum needs exactly one witness");
        }
    }

    public static CapabilityScenario of(String id, CapabilityFamily family, CapabilityMaterialMode mode,
                                        int scale, CapabilityGraph graph, String target, UfoAmount amount,
                                        boolean expectedFeasible, List<Map<String, UfoAmount>> minimalMissing,
                                        Map<String, Double> weights, boolean uniqueMinimalMissing,
                                        Set<CapabilitySemantics> requiredSemantics,
                                        CapabilityExpectation expectation,
                                        Function<Map<String, UfoAmount>, CapabilityGraph> refill) {
        return new CapabilityScenario(id, family, mode, scale, graph, target, amount, expectedFeasible,
                minimalMissing, weights, uniqueMinimalMissing, requiredSemantics, expectation, refill);
    }

    /** The same graph plus exactly the reported shortage, used to prove a report is sufficient. */
    public CapabilityScenario refilled(Map<String, UfoAmount> reported) {
        LinkedHashMap<String, UfoAmount> supplied = new LinkedHashMap<>();
        reported.forEach((key, value) -> {
            if (value != null && !value.isZero()) {
                supplied.put(key, value);
            }
        });
        return new CapabilityScenario(id + "/refill", family, mode, scale, refill.apply(supplied),
                target, amount, true, List.of(), missingWeights, false, requiredSemantics,
                expectation, refill);
    }

    /** The same graph with the pattern declaration order reversed, to prove determinism. */
    public CapabilityScenario reversedPatternOrder() {
        CapabilityScenario reversed = new CapabilityScenario(id, family, mode, scale,
                graph.reversedPatternOrder(), target, amount, expectedFeasible, minimalMissing,
                missingWeights, uniqueMinimalMissing, requiredSemantics, expectation, refill);
        return reversed;
    }

    /** Lowest-weighted known shortage witness, or an empty map when none is known. */
    public Map<String, UfoAmount> minimumWitness() {
        return minimalMissing.stream()
                .min((left, right) -> weightedCost(left).compareTo(weightedCost(right)))
                .orElse(Map.of());
    }

    /**
     * {@code reportedCost / minimumCost} for the reported shortage set, or {@code NaN} when no
     * optimum is known. A value of exactly one means the report matches a known minimum.
     */
    public double missingOverhead(Map<String, UfoAmount> reported) {
        if (minimalMissing.isEmpty()) {
            return Double.NaN;
        }
        BigDecimal minimum = null;
        for (Map<String, UfoAmount> candidate : minimalMissing) {
            BigDecimal cost = weightedCost(candidate);
            if (minimum == null || cost.compareTo(minimum) < 0) {
                minimum = cost;
            }
        }
        if (minimum == null || minimum.signum() == 0) {
            return Double.NaN;
        }
        return weightedCost(reported).divide(minimum, 6, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Exact weighted cost of one shortage set, in whole-number weights. The oracle check needs the same
     * arithmetic the ratio uses, but as an exact integer so two computed minima can be compared without
     * a rounding step deciding the result.
     */
    public BigInteger exactWeightedCost(Map<String, UfoAmount> missing) {
        return weightedCost(missing).toBigIntegerExact();
    }

    private BigDecimal weightedCost(Map<String, UfoAmount> missing) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, UfoAmount> entry : missing.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isZero()) {
                continue;
            }
            BigDecimal weight = BigDecimal.valueOf(missingWeights.getOrDefault(entry.getKey(), 1.0D));
            total = total.add(new BigDecimal(entry.getValue().asBigInteger()).multiply(weight));
        }
        return total;
    }

    /** Human-readable line used by the harness summary. */
    public String label() {
        return id + "/" + mode.name().toLowerCase(java.util.Locale.ROOT);
    }
}
