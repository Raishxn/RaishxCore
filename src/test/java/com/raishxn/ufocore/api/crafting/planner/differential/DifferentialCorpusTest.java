package com.raishxn.ufocore.api.crafting.planner.differential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Gate for the differential corpus, executed through the RaishxCore production path.
 *
 * <p>The corpus is the R2.1 deliverable: it records what the planner can really do and refuses to
 * count an unproven capability as support. Confirmed defects stay visible in
 * {@link DifferentialHarness#CONFIRMED_DEFECTS}; they never become successes.
 */
class DifferentialCorpusTest {

    /**
     * Canonical cases whose known minimum must be reported exactly.
     *
     * <p>The multi-route Fibonacci frontier used to be absent because only one witness is retained,
     * so its ratio was a denominator rather than a claim about the true frontier. It belongs here
     * now: the planner selects routes by a bottom-up leaf-demand computation and the corpus computes
     * its witness by an independent bottom-up sum of the same objective, so agreement between the
     * two is a real cross-check and not a restatement. Before that change the planner reported 6.857
     * times the witness, so this assertion is exactly what fails if the choice regresses.
     */
    private static final List<String> MINIMAL_SHORTAGE_CASES = List.of(
            "single-dag/dispersed",
            "single-dag/fibonacci-depth32",
            "multi-dag/fibonacci-depth12",
            "multi-dag/sibling-routes",
            "multi-dag/greedy-trap",
            "batching/multi-output",
            "byproduct/shared-coproduct",
            "byproduct/feeds-later-stage",
            "deep-chain/linear-20000");

    private static DifferentialHarness.Report report;

    @BeforeAll
    static void runCorpusOnce() {
        CapabilityPlanner planner = new RaishxCoreCapabilityPlanner(Duration.ofSeconds(10));
        CapabilityRunner runner = new CapabilityRunner(Duration.ofSeconds(30), Duration.ofMillis(500));
        report = new DifferentialHarness(planner, runner).run();
    }

    @Test void gatePasses() {
        assertTrue(report.gate().passed(), () -> report.render());
    }

    @Test void everyScenarioRunsInAllThreeMaterialModes() {
        Map<String, Set<CapabilityMaterialMode>> modes = new java.util.TreeMap<>();
        for (DifferentialHarness.Entry entry : report.entries()) {
            modes.computeIfAbsent(entry.id(), ignored -> new LinkedHashSet<>()).add(entry.mode());
        }
        assertEquals(17, modes.size());
        assertEquals(51, report.entries().size());
        modes.forEach((id, present) -> assertEquals(Set.of(CapabilityMaterialMode.values()), present,
                () -> id + " must run in every material mode"));
    }

    @Test void everyRequiredCapabilityIsSupportedOrAConfirmedDefect() {
        List<String> unresolved = new ArrayList<>();
        for (DifferentialHarness.Entry entry : report.of(CapabilityExpectation.REQUIRED)) {
            if (entry.run().supported()) {
                continue;
            }
            String label = entry.id() + "/" + entry.mode().name().toLowerCase(java.util.Locale.ROOT);
            boolean confirmed = DifferentialHarness.CONFIRMED_DEFECTS.stream()
                    .anyMatch(defect -> defect.label().equals(label));
            if (!confirmed) {
                unresolved.add(label + " -> " + entry.classification());
            }
        }
        assertTrue(unresolved.isEmpty(), () -> "unresolved required capabilities: " + unresolved);
        assertEquals(39, report.supportedRequired());
    }

    @Test void noFalsePositiveAndNoEngineErrorAnywhereInTheCorpus() {
        EnumMap<CapabilityClassification, Long> counts = new EnumMap<>(report.counts());
        assertEquals(0L, counts.get(CapabilityClassification.FALSE_POSITIVE), report::render);
        assertEquals(0L, counts.get(CapabilityClassification.ENGINE_ERROR), report::render);
        assertEquals(0L, counts.get(CapabilityClassification.NON_COOPERATIVE_TIMEOUT), report::render);
        assertEquals(0L, counts.get(CapabilityClassification.ENGINE_TIMEOUT), report::render);
        assertEquals(51L, counts.values().stream().mapToLong(Long::longValue).sum());
    }

    @Test void declaredLimitationsAreNeverCountedAsSupport() {
        for (DifferentialHarness.Entry entry : report.of(CapabilityExpectation.LIMITATION)) {
            assertFalse(entry.run().supported(),
                    () -> entry.id() + "/" + entry.mode() + " must not be claimed as support");
            assertTrue(entry.classification().safeDecline(),
                    () -> entry.id() + "/" + entry.mode() + " must decline safely, was "
                            + entry.classification());
        }
        assertEquals(0, report.supportedLimitations());
        assertEquals(12, report.of(CapabilityExpectation.LIMITATION).size());
    }

    @Test void everyResultIsDeterministic() {
        for (DifferentialHarness.Entry entry : report.entries()) {
            assertTrue(entry.deterministic(), () -> entry.id() + "/" + entry.mode()
                    + " is not deterministic: " + entry.determinismNote());
        }
    }

    @Test void canonicalShortagesAreMinimal() {
        for (DifferentialHarness.Entry entry : report.of(CapabilityExpectation.REQUIRED)) {
            if (!MINIMAL_SHORTAGE_CASES.contains(entry.id())
                    || entry.mode() != CapabilityMaterialMode.MISSING) {
                continue;
            }
            assertEquals(1.0D, entry.run().missingOverhead(),
                    () -> entry.id() + " reported " + entry.run().reportedMissing());
        }
    }

    @Test void corpusCoversEveryFamilyItDeclares() {
        Set<CapabilityFamily> required = new LinkedHashSet<>();
        Set<CapabilityFamily> limitations = new LinkedHashSet<>();
        report.of(CapabilityExpectation.REQUIRED).forEach(entry -> required.add(entry.scenario().family()));
        report.of(CapabilityExpectation.LIMITATION).forEach(entry -> limitations.add(entry.scenario().family()));
        assertEquals(Set.of(CapabilityFamily.SINGLE_DAG, CapabilityFamily.MULTI_DAG,
                CapabilityFamily.BATCHING, CapabilityFamily.BYPRODUCT, CapabilityFamily.DEEP_CHAIN,
                CapabilityFamily.REUSABLE_CATALYST, CapabilityFamily.FINITE_DURABILITY,
                CapabilityFamily.FUZZY_VARIANT, CapabilityFamily.EMITTER),
                required);
        assertEquals(Set.of(CapabilityFamily.CONVERSION_CYCLE, CapabilityFamily.POSITIVE_FEEDBACK,
                CapabilityFamily.CONSERVATIVE_FEEDBACK, CapabilityFamily.LOSSY_FEEDBACK),
                limitations);
    }

    @Test void reportContainsEveryFieldTheStandardRequires() {
        String rendered = report.render();
        assertTrue(rendered.contains("engine=RaishxCore"));
        assertTrue(rendered.contains("required="));
        for (CapabilityClassification classification : CapabilityClassification.values()) {
            assertTrue(rendered.contains(classification.name()),
                    () -> "report must list " + classification);
        }
        for (DifferentialHarness.Entry entry : report.entries()) {
            String label = entry.id() + "/" + entry.mode().name().toLowerCase(java.util.Locale.ROOT);
            assertTrue(rendered.contains(label), () -> "report must contain " + label);
            assertTrue(rendered.contains("missing="), "report must contain the reported shortage");
            assertTrue(rendered.contains("overhead="), "report must contain the shortage overhead");
        }
    }

    @Test void repeatedRunsAgreeOnEveryClassification() {
        CapabilityPlanner planner = new RaishxCoreCapabilityPlanner(Duration.ofSeconds(10));
        CapabilityRunner runner = new CapabilityRunner(Duration.ofSeconds(30), Duration.ofMillis(500));
        DifferentialHarness.Report second = new DifferentialHarness(planner, runner).run();
        assertEquals(report.entries().size(), second.entries().size());
        for (int index = 0; index < report.entries().size(); index++) {
            DifferentialHarness.Entry first = report.entries().get(index);
            DifferentialHarness.Entry other = second.entries().get(index);
            assertEquals(first.classification(), other.classification(), first.id() + "/" + first.mode());
            assertEquals(first.run().reportedMissing(), other.run().reportedMissing(),
                    first.id() + "/" + first.mode());
        }
    }
}
