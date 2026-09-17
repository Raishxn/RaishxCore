package com.raishxn.ufocore.api.crafting.planner.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Proves the adapter admits only representable semantics and never narrows quantities. */
class RaishxCoreCapabilityPlannerTest {

    private final CapabilityPlanner planner =
            new RaishxCoreCapabilityPlanner(Duration.ofSeconds(5));

    @Test void rejectsEveryDeclaredLimitationBeforePlanning() {
        List<CapabilityScenario> limitations = CapabilityCorpus.limitations();
        assertFalse(limitations.isEmpty());
        for (CapabilityScenario scenario : limitations) {
            CapabilityPlanner.Check check = planner.check(scenario);
            assertFalse(check.accepted(), () -> scenario.label() + " must be refused");
            assertTrue(check.reason().contains("cannot represent"), check.reason());
            assertEquals(CapabilityPlanner.Outcome.Kind.DECLINED,
                    planner.plan(scenario).kind(), scenario.label());
        }
    }

    @Test void acceptsEveryRepresentableScenario() {
        List<CapabilityScenario> required = CapabilityCorpus.required();
        // 39 before self-growth was activated; the family is representable now.
        assertEquals(42, required.size());
        for (CapabilityScenario scenario : required) {
            assertTrue(planner.check(scenario).accepted(),
                    () -> scenario.label() + " must be admitted: " + planner.check(scenario).reason());
        }
    }

    @Test void plansThroughThePublicApiWithoutNarrowingQuantities() {
        BigInteger amount = BigInteger.TWO.pow(70);
        CapabilityGraph graph = new CapabilityGraph(List.of(
                CapabilityPattern.of("k1", List.of(CapabilityInput.exact("k0", 1)),
                        List.of(CapabilityOutput.primary("k1", 1))),
                CapabilityPattern.of("k2", List.of(CapabilityInput.exact("k1", 1)),
                        List.of(CapabilityOutput.primary("k2", 1)))),
                Map.of("k0", CapabilityGraph.CapabilityStock.consumable(UfoAmount.of(amount))));
        CapabilityScenario scenario = new CapabilityScenario("stub/bigint", CapabilityFamily.SINGLE_DAG,
                CapabilityMaterialMode.MINIMUM, 2, graph, "k2", UfoAmount.of(amount), true, List.of(),
                Map.of(), false, Set.of(CapabilitySemantics.DETERMINISTIC_EXACT_DAG),
                CapabilityExpectation.REQUIRED, additions -> graph.withAdditionalStock(additions));

        CapabilityPlanner.Outcome outcome = planner.plan(scenario);

        assertEquals(CapabilityPlanner.Outcome.Kind.PLANNED, outcome.kind());
        CapabilityPlan plan = outcome.plan();
        assertEquals(UfoAmount.of(amount), plan.requested());
        assertEquals(UfoAmount.of(amount), plan.executions().get("k1"));
        assertEquals(amount, plan.executions().get("k2").asBigInteger());
        assertEquals(amount, plan.extractedFromInventory().get("k0").asBigInteger());
        assertTrue(plan.extractedFromInventory().get("k0").bitLength() > 63);
        assertTrue(new CapabilityRunner(Duration.ofSeconds(5)).run(planner, scenario).supported());
    }

    @Test void isDeterministicAcrossPatternDeclarationOrder() {
        CapabilityScenario scenario = CapabilityCorpus.all().stream()
                .filter(candidate -> candidate.id().equals("multi-dag/greedy-trap")
                        && candidate.mode() == CapabilityMaterialMode.MINIMUM)
                .findFirst()
                .orElseThrow();
        CapabilityPlan first = planner.plan(scenario).plan();
        CapabilityPlan reversed = planner.plan(scenario.reversedPatternOrder()).plan();

        assertTrue(first.sameSemantics(reversed),
                () -> "pattern order changed the plan: " + first + " vs " + reversed);
    }

    @Test void refusesAScenarioWithNoRouteAndNoStock() {
        CapabilityGraph graph = CapabilityGraph.of(List.of(CapabilityPattern.of("p",
                List.of(CapabilityInput.exact("raw", 1)), List.of(CapabilityOutput.primary("out", 1)))),
                Map.of());
        CapabilityScenario scenario = new CapabilityScenario("stub/no-route", CapabilityFamily.SINGLE_DAG,
                CapabilityMaterialMode.MISSING, 1, graph, "unreachable", UfoAmount.ONE, false,
                List.of(Map.of("unreachable", UfoAmount.ONE)), Map.of(), true,
                Set.of(CapabilitySemantics.DETERMINISTIC_EXACT_DAG), CapabilityExpectation.REQUIRED,
                additions -> graph.withAdditionalStock(additions));

        CapabilityPlanner.Check check = planner.check(scenario);
        assertFalse(check.accepted());
        assertTrue(check.reason().contains("no route"), check.reason());
    }
}
