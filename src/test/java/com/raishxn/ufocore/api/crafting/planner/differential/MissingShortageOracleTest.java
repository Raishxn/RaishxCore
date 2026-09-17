package com.raishxn.ufocore.api.crafting.planner.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Checks the independent exact oracle against every hand-declared witness of the MISSING material
 * mode. The oracle computes the minimum; the corpus declares one. Where the two differ the test
 * fails and prints the exact scenario, the declared witness and cost, and the cheaper witness the
 * oracle found, so a wrong declaration is a visible finding rather than a silent agreement.
 */
class MissingShortageOracleTest {

    @Test
    void computesTheDeclaredMinimumOrReportsTheExactDisagreement() {
        List<CapabilityScenario> scenarios = CapabilityCorpus.all().stream()
                .filter(scenario -> scenario.mode() == CapabilityMaterialMode.MISSING)
                .toList();
        assertEquals(27, scenarios.size(), "the corpus must have 27 MISSING-mode scenarios");

        List<String> agreements = new ArrayList<>();
        List<String> disagreements = new ArrayList<>();
        for (CapabilityScenario scenario : scenarios) {
            MissingShortageOracle.Result result = MissingShortageOracle.minimum(scenario);
            BigInteger declared = declaredCost(scenario);
            if (result.cost().equals(declared)) {
                agreements.add(label(scenario) + " declared=" + declared + " oracle=" + result.cost());
                continue;
            }
            disagreements.add(label(scenario)
                    + "\n    declared witness=" + scenario.minimumWitness()
                    + "  declaredCost=" + declared
                    + "\n    oracle   witness=" + result.shortage()
                    + "  oracleCost=" + result.cost());
            // The oracle computes a minimum, so it can never be more expensive than a witness that
            // is known to be feasible. An overshoot is an oracle bug, not a corpus finding.
            assertTrue(result.cost().compareTo(declared) < 0,
                    () -> label(scenario) + " oracle cost " + result.cost()
                            + " must not exceed the declared witness cost " + declared);
        }

        String report = "agreements=" + agreements.size() + "/" + scenarios.size() + "\n"
                + "AGREEMENTS:\n" + String.join("\n", agreements) + "\n"
                + "DISAGREEMENTS (" + disagreements.size() + "):\n" + String.join("\n", disagreements);
        assertTrue(disagreements.isEmpty(), () -> "declared witnesses the oracle rejects:\n" + report);
    }

    /**
     * A negative control: the oracle must reject a declared witness that is not the minimum. The
     * synthetic case has a valuable route and a cheap route, and declares only the valuable one, so
     * a test that merely echoed the declaration would report 100 instead of 1.
     */
    @Test
    void rejectsAWrongDeclaredWitnessInsteadOfEchoingIt() {
        CapabilityGraph graph = new CapabilityGraph(List.of(
                CapabilityPattern.of("widget-from-gold", List.of(CapabilityInput.exact("gold", 1)),
                        List.of(CapabilityOutput.primary("widget", 1))),
                CapabilityPattern.of("widget-from-cheap", List.of(CapabilityInput.exact("cheap", 1)),
                        List.of(CapabilityOutput.primary("widget", 1)))),
                Map.of());
        CapabilityScenario wrong = new CapabilityScenario("synthetic/wrong-witness",
                CapabilityFamily.MULTI_DAG, CapabilityMaterialMode.MISSING, 1, graph, "widget",
                UfoAmount.ONE, false, List.of(Map.of("gold", UfoAmount.ONE)),
                Map.of("gold", 100.0D, "cheap", 1.0D), true,
                Set.of(CapabilitySemantics.DETERMINISTIC_EXACT_DAG), CapabilityExpectation.REQUIRED,
                additions -> graph.withAdditionalStock(additions));

        MissingShortageOracle.Result result = MissingShortageOracle.minimum(wrong);

        assertEquals(BigInteger.ONE, result.cost(),
                () -> "the cheap route is the minimum, not the declared valuable one: " + result);
        assertEquals(Map.of("cheap", BigInteger.ONE), result.shortage());
    }

    private static String label(CapabilityScenario scenario) {
        return scenario.id() + "/" + scenario.mode().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Exact weighted cost of the cheapest declared witness, in whole-number weights. */
    private static BigInteger declaredCost(CapabilityScenario scenario) {
        BigInteger best = null;
        for (Map<String, UfoAmount> witness : scenario.minimalMissing()) {
            BigInteger cost = BigInteger.ZERO;
            for (Map.Entry<String, UfoAmount> entry : witness.entrySet()) {
                BigDecimal weight = BigDecimal.valueOf(
                        scenario.missingWeights().getOrDefault(entry.getKey(), 1.0D));
                cost = cost.add(new BigDecimal(entry.getValue().asBigInteger()).multiply(weight)
                        .toBigIntegerExact());
            }
            if (best == null || cost.compareTo(best) < 0) best = cost;
        }
        if (best == null) throw new IllegalStateException("no declared witness for " + label(scenario));
        return best;
    }
}
