package com.raishxn.ufocore.api.crafting.planner.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Proves every required classification is reachable and stays distinct. */
class CapabilityRunnerTest {

    private static final Duration DEADLINE = Duration.ofSeconds(5);

    @Test void classifiesAValidPlanAsSupported() {
        CapabilityRun run = run(new StubPlanner(CapabilityPlanner.Check.accept(),
                scenario -> CapabilityPlanner.Outcome.planned(validPlan(scenario))), feasible());

        assertEquals(CapabilityClassification.SUPPORTED, run.classification());
        assertNotNull(run.plan());
        assertTrue(run.replay().valid());
    }

    @Test void classifiesAnAdmissionRejectionAsCheckRejected() {
        CapabilityRun run = run(new StubPlanner(CapabilityPlanner.Check.reject("no route"),
                scenario -> CapabilityPlanner.Outcome.declined("no route")), feasible());

        assertEquals(CapabilityClassification.CHECK_REJECTED, run.classification());
        assertTrue(run.diagnostic().contains("no route"), run.diagnostic());
    }

    @Test void promotesAForcedReplayToFalseNegativeButNeverToSupport() {
        // Admission is dishonest: the production path can actually complete the case.
        CapabilityRun run = run(new StubPlanner(CapabilityPlanner.Check.reject("unsupported"),
                scenario -> CapabilityPlanner.Outcome.planned(validPlan(scenario))), feasible());

        assertEquals(CapabilityClassification.FALSE_NEGATIVE, run.classification());
        assertFalse(run.classification().supported());
    }

    @Test void classifiesAnActiveDeclineAsAttemptDeclined() {
        CapabilityRun run = run(new StubPlanner(CapabilityPlanner.Check.accept(),
                scenario -> CapabilityPlanner.Outcome.declined("model cannot represent it")), feasible());

        assertEquals(CapabilityClassification.ATTEMPT_DECLINED, run.classification());
    }

    @Test void classifiesAShortageOnAFeasibleScenarioAsFalseNegative() {
        CapabilityRun run = run(new StubPlanner(CapabilityPlanner.Check.accept(),
                scenario -> CapabilityPlanner.Outcome.planned(shortagePlan(scenario))), feasible());

        assertEquals(CapabilityClassification.FALSE_NEGATIVE, run.classification());
        assertTrue(run.diagnostic().contains("feasible"));
    }

    @Test void classifiesAnInvalidCompletePlanAsFalsePositive() {
        CapabilityRun run = run(new StubPlanner(CapabilityPlanner.Check.accept(),
                scenario -> CapabilityPlanner.Outcome.planned(lyingPlan(scenario))), feasible());

        assertEquals(CapabilityClassification.FALSE_POSITIVE, run.classification());
        assertFalse(run.replay().valid());
    }

    @Test void classifiesAThrownFailureAsEngineError() {
        CapabilityRun run = run(new StubPlanner(CapabilityPlanner.Check.accept(), scenario -> {
            throw new IllegalStateException("boom");
        }), feasible());

        assertEquals(CapabilityClassification.ENGINE_ERROR, run.classification());
        assertTrue(run.diagnostic().contains("boom"));
    }

    @Test void classifiesAnEngineDeadlineAsEngineTimeout() {
        CapabilityRun run = run(new StubPlanner(CapabilityPlanner.Check.accept(),
                scenario -> CapabilityPlanner.Outcome.timedOut("planner deadline expired")), feasible());

        assertEquals(CapabilityClassification.ENGINE_TIMEOUT, run.classification());
    }

    @Test void classifiesACooperativeHardDeadlineAsEngineTimeout() {
        CapabilityRunner runner = new CapabilityRunner(Duration.ofMillis(50), Duration.ofMillis(500));
        CapabilityRun run = runner.run(new StubPlanner(CapabilityPlanner.Check.accept(), scenario -> {
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return CapabilityPlanner.Outcome.planned(validPlan(scenario));
        }), feasible());

        assertEquals(CapabilityClassification.ENGINE_TIMEOUT, run.classification());
    }

    @Test void classifiesAWorkerIgnoringInterruptionAsNonCooperativeTimeout() {
        CapabilityRunner runner = new CapabilityRunner(Duration.ofMillis(50), Duration.ofMillis(200));
        CapabilityRun run = runner.run(new StubPlanner(CapabilityPlanner.Check.accept(), scenario -> {
            long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < until) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException ignored) {
                    // Deliberately non-cooperative: keeps running after the hard deadline.
                }
            }
            return CapabilityPlanner.Outcome.planned(validPlan(scenario));
        }), feasible());

        assertEquals(CapabilityClassification.NON_COOPERATIVE_TIMEOUT, run.classification());
    }

    @Test void validatesAShortageByRefillingIt() {
        CapabilityRun run = run(new StubPlanner(CapabilityPlanner.Check.accept(),
                scenario -> CapabilityPlanner.Outcome.planned(scenario.graph().consumableStock("raw").isZero()
                        ? shortagePlan(scenario) : validPlan(scenario))), infeasible());

        assertEquals(CapabilityClassification.SUPPORTED, run.classification());
        assertEquals(1.0D, run.missingOverhead());
        assertNotNull(run.refillPlan());
        assertEquals(Map.of("raw", UfoAmount.ONE), run.reportedMissing());
    }

    @Test void declinesWhenTheReportedShortageDoesNotUnlockTheCase() {
        CapabilityRun run = run(new StubPlanner(CapabilityPlanner.Check.accept(),
                scenario -> CapabilityPlanner.Outcome.planned(shortagePlan(scenario))), infeasible());

        assertEquals(CapabilityClassification.ATTEMPT_DECLINED, run.classification());
        assertTrue(run.diagnostic().contains("still declines"), run.diagnostic());
    }

    private static CapabilityRun run(CapabilityPlanner planner, CapabilityScenario scenario) {
        return new CapabilityRunner(DEADLINE).run(planner, scenario);
    }

    private static CapabilityScenario feasible() {
        return scenario("stub/feasible", true, Map.of("raw", UfoAmount.ONE), List.of());
    }

    private static CapabilityScenario infeasible() {
        return scenario("stub/infeasible", false, Map.of(), List.of(Map.of("raw", UfoAmount.ONE)));
    }

    private static CapabilityScenario scenario(String id, boolean feasible, Map<String, UfoAmount> stock,
                                               List<Map<String, UfoAmount>> minimalMissing) {
        CapabilityGraph graph = CapabilityGraph.of(List.of(CapabilityPattern.of("make",
                List.of(CapabilityInput.exact("raw", 1)), List.of(CapabilityOutput.primary("out", 1)))),
                Map.of());
        CapabilityGraph stocked = graph.withAdditionalStock(stock);
        return new CapabilityScenario(id, CapabilityFamily.SINGLE_DAG, feasible
                ? CapabilityMaterialMode.MINIMUM : CapabilityMaterialMode.MISSING, 1, stocked, "out",
                UfoAmount.ONE, feasible, minimalMissing, Map.of(), minimalMissing.size() == 1,
                Set.of(CapabilitySemantics.DETERMINISTIC_EXACT_DAG), CapabilityExpectation.REQUIRED,
                additions -> graph.withAdditionalStock(additions));
    }

    private static CapabilityPlan validPlan(CapabilityScenario scenario) {
        return plan(scenario, Map.of("make", UfoAmount.ONE), Map.of("raw", UfoAmount.ONE),
                Map.of(), Map.of(), List.of(new CapabilityPlan.Step("make", UfoAmount.ONE)));
    }

    private static CapabilityPlan shortagePlan(CapabilityScenario scenario) {
        return plan(scenario, Map.of("make", UfoAmount.ONE), Map.of(),
                Map.of("raw", UfoAmount.ONE), Map.of(),
                List.of(new CapabilityPlan.Step("make", UfoAmount.ONE)));
    }

    /** Claims to be complete while leaving the requested output unproduced. */
    private static CapabilityPlan lyingPlan(CapabilityScenario scenario) {
        return plan(scenario, Map.of(), Map.of(), Map.of(),
                Map.of(scenario.target(), scenario.amount()), List.of());
    }

    private static CapabilityPlan plan(CapabilityScenario scenario, Map<String, UfoAmount> executions,
                                       Map<String, UfoAmount> extracted, Map<String, UfoAmount> missing,
                                       Map<String, UfoAmount> remaining, List<CapabilityPlan.Step> schedule) {
        return new CapabilityPlan(scenario.target(), scenario.amount(), executions, extracted, missing,
                remaining, schedule, missing.isEmpty());
    }

    private static final class StubPlanner implements CapabilityPlanner {
        private final Check check;
        private final Function<CapabilityScenario, Outcome> planning;

        StubPlanner(Check check, Function<CapabilityScenario, Outcome> planning) {
            this.check = check;
            this.planning = planning;
        }

        @Override public String name() {
            return "stub";
        }

        @Override public Check check(CapabilityScenario scenario) {
            return check;
        }

        @Override public Outcome plan(CapabilityScenario scenario) {
            return planning.apply(scenario);
        }
    }
}
