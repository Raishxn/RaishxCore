package com.raishxn.ufocore.api.crafting.planner.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Proves the common oracle accepts valid plans and rejects hand-built invalid ones. */
class CapabilityPlanReplayTest {

    private static final CapabilityPattern CHAIN_A =
            CapabilityPattern.of("p", List.of(CapabilityInput.exact("raw", 2)),
                    List.of(CapabilityOutput.primary("A", 1)));
    private static final CapabilityPattern CHAIN_B =
            CapabilityPattern.of("q", List.of(CapabilityInput.exact("A", 1)),
                    List.of(CapabilityOutput.primary("B", 1)));

    @Test void acceptsAValidPlanAndReportsConservation() {
        CapabilityPlanReplay.Report report =
                CapabilityPlanReplay.replay(chainGraph(Map.of("raw", 4L)), plan(true,
                        Map.of("p", 2L, "q", 2L), Map.of("raw", 4L), Map.of(), Map.of(),
                        List.of(step("p", 2), step("q", 2))), false);

        assertTrue(report.valid(), report.summary());
        assertEquals(Map.of("raw", UfoAmount.of(4)), report.stockUsed());
        assertEquals(UfoAmount.of(2), report.targetAvailable());
        assertEquals(UfoAmount.of(4), report.executedRuns());
        assertTrue(report.leftover().isEmpty());
    }

    @Test void countsOverproductionAndByproducts() {
        CapabilityPattern plate = CapabilityPattern.of("plate", List.of(CapabilityInput.exact("ingot", 3)),
                List.of(CapabilityOutput.primary("plate", 2), CapabilityOutput.byproduct("dust", 1)));
        CapabilityGraph graph = new CapabilityGraph(List.of(plate), Map.of());
        CapabilityPlan plan = new CapabilityPlan("plate", UfoAmount.of(5),
                Map.of("plate", UfoAmount.of(3)), Map.of("ingot", UfoAmount.of(9)), Map.of(),
                Map.of("plate", UfoAmount.ONE, "dust", UfoAmount.of(3)),
                List.of(step("plate", 3)), true);

        CapabilityPlanReplay.Report report =
                CapabilityPlanReplay.replay(graph.withAdditionalStock(Map.of("ingot", UfoAmount.of(9))),
                        plan, false);

        assertTrue(report.valid(), report.summary());
        assertEquals(Map.of("plate", UfoAmount.ONE, "dust", UfoAmount.of(3)), report.overproduction());
        assertEquals(Map.of("dust", UfoAmount.of(3)), report.byproducts());
    }

    /** Requirement: the corpus must be able to detect a deliberately invalid plan. */
    @Test void rejectsAPlanThatSpendsAnInputItNeverProduced() {
        CapabilityPlanReplay.Report report =
                CapabilityPlanReplay.replay(chainGraph(Map.of()), plan(true,
                        Map.of("q", 2L), Map.of(), Map.of(), Map.of(), List.of(step("q", 2))), false);

        assertFalse(report.valid());
        assertTrue(report.failures().stream()
                        .anyMatch(failure -> failure.contains("unfunded input A")),
                () -> "expected an unfunded-input failure, got " + report.failures());
    }

    @Test void rejectsABalanceThatDoesNotHold() {
        // The plan invents leftover A that the declared executions cannot produce.
        CapabilityPlanReplay.Report report =
                CapabilityPlanReplay.replay(chainGraph(Map.of("raw", 4L)), plan(true,
                        Map.of("p", 2L, "q", 2L), Map.of("raw", 4L), Map.of(),
                        Map.of("A", UfoAmount.ONE), List.of(step("p", 2), step("q", 2))), false);

        assertFalse(report.valid());
        assertTrue(report.failures().stream()
                        .anyMatch(failure -> failure.contains("declared balance broken for A")),
                () -> "expected a balance failure, got " + report.failures());
    }

    @Test void rejectsAScheduleThatDoesNotMatchDeclaredExecutions() {
        CapabilityPlanReplay.Report report =
                CapabilityPlanReplay.replay(chainGraph(Map.of("raw", 4L)), plan(true,
                        Map.of("p", 2L, "q", 2L), Map.of("raw", 4L), Map.of(), Map.of(),
                        List.of(step("p", 2))), false);

        assertFalse(report.valid());
        assertTrue(report.failures().stream()
                        .anyMatch(failure -> failure.contains("schedule does not match")),
                () -> "expected a schedule mismatch, got " + report.failures());
    }

    @Test void rejectsAShortageThatIsNotEnoughToFinish() {
        CapabilityPlanReplay.Report report =
                CapabilityPlanReplay.replay(chainGraph(Map.of()), plan(false,
                        Map.of("p", 1L, "q", 1L), Map.of(), Map.of("raw", UfoAmount.ONE),
                        Map.of(), List.of(step("p", 1), step("q", 1))), true);

        assertFalse(report.valid());
        assertTrue(report.failures().stream()
                        .anyMatch(failure -> failure.contains("insufficient")),
                () -> "expected an insufficient-shortage failure, got " + report.failures());
    }

    @Test void flagsAReportedShortageKeyThatIsNeverConsumed() {
        // Internally consistent, but it asks the player for material no route ever consumes.
        CapabilityPlan plan = new CapabilityPlan("B", UfoAmount.of(2),
                Map.of("p", UfoAmount.of(2), "q", UfoAmount.of(2)), Map.of(),
                Map.of("raw", UfoAmount.of(4), "bonus", UfoAmount.of(5)),
                Map.of("bonus", UfoAmount.of(5)),
                List.of(step("p", 2), step("q", 2)), false);
        CapabilityPlanReplay.Report report =
                CapabilityPlanReplay.replay(chainGraph(Map.of()), plan, true);

        assertTrue(report.valid(), report.summary());
        assertEquals(Map.of("raw", UfoAmount.of(4)), report.injectedUsed());
        assertEquals(Map.of("raw", UfoAmount.of(4)), report.neededMissing());
        assertTrue(report.findings().stream()
                        .anyMatch(finding -> finding.contains("unused material bonus")),
                () -> "expected an unused-material finding, got " + report.findings());
    }

    @Test void replaysCatalystsAndFuzzySlotsButRefusesWhatItCannotReExecute() {
        CapabilityGraph catalyst = new CapabilityGraph(List.of(CapabilityPattern.of("catalyst",
                List.of(CapabilityInput.reusable("seed", 1, "host")),
                List.of(CapabilityOutput.primary("out", 1)))), Map.of());
        CapabilityGraph fuzzy = new CapabilityGraph(List.of(CapabilityPattern.of("fuzzy",
                List.of(CapabilityInput.fuzzy("logical", 1, "host", List.of("logical", "variant"))),
                List.of(CapabilityOutput.primary("out", 1)))), Map.of());
        CapabilityGraph emitter = new CapabilityGraph(List.of(CapabilityPattern.of("emitted",
                List.of(CapabilityInput.emitter("flux", 10, "source")),
                List.of(CapabilityOutput.primary("out", 1)))), Map.of());
        CapabilityGraph probabilistic = new CapabilityGraph(List.of(CapabilityPattern.of("chance",
                List.of(CapabilityInput.exact("ore", 1)),
                List.of(CapabilityOutput.primary("out", 1),
                        CapabilityOutput.probabilistic("extra", 1)))), Map.of());

        // A catalyst and a fuzzy slot are both replayable: the oracle checks the accepted keys are
        // present and hands them back rather than drawing them, so a plan using either can be
        // verified like any other.
        assertTrue(CapabilityPlanReplay.isReplayable(catalyst));
        assertTrue(CapabilityPlanReplay.isReplayable(fuzzy));
        assertFalse(CapabilityPlanReplay.isReplayable(emitter));
        assertFalse(CapabilityPlanReplay.isReplayable(probabilistic));
    }

    private static CapabilityGraph chainGraph(Map<String, Long> stock) {
        return CapabilityGraph.of(List.of(CHAIN_A, CHAIN_B), stock);
    }

    private static CapabilityPlan.Step step(String patternId, long runs) {
        return new CapabilityPlan.Step(patternId, UfoAmount.of(runs));
    }

    private static CapabilityPlan plan(boolean complete, Map<String, Long> executions,
                                       Map<String, Long> extracted, Map<String, UfoAmount> missing,
                                       Map<String, UfoAmount> remaining, List<CapabilityPlan.Step> schedule) {
        return new CapabilityPlan("B", UfoAmount.of(2), amounts(executions), amounts(extracted),
                missing, remaining, schedule, complete);
    }

    private static Map<String, UfoAmount> amounts(Map<String, Long> values) {
        java.util.LinkedHashMap<String, UfoAmount> result = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, UfoAmount.of(value)));
        return result;
    }
}
