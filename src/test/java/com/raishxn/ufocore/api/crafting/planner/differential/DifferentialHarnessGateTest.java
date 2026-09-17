package com.raishxn.ufocore.api.crafting.planner.differential;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves the gate cannot hide a defect, cannot accept a stale defect entry and cannot turn a
 * limitation into support. The defect registry is injected so both branches are exercised even while
 * {@link DifferentialHarness#CONFIRMED_DEFECTS} is empty.
 */
class DifferentialHarnessGateTest {

    private static final CapabilityScenario SCENARIO = CapabilityCorpus.all().getFirst();

    @Test void anUnlistedDefectFailsTheGate() {
        DifferentialHarness.Report report = reportWith(CapabilityClassification.FALSE_POSITIVE);

        DifferentialHarness.Gate gate = report.gate(Map.of());

        assertFalse(gate.passed());
        assertTrue(gate.failures().stream().anyMatch(failure -> failure.contains("without an entry in CONFIRMED_DEFECTS")),
                () -> gate.failures().toString());
    }

    @Test void aConfirmedDefectThatNoLongerReproducesFailsTheGate() {
        DifferentialHarness.Report report = reportWith(CapabilityClassification.SUPPORTED);
        String label = label();
        Map<String, DifferentialHarness.ConfirmedDefect> defects = Map.of(label,
                new DifferentialHarness.ConfirmedDefect(label, CapabilityClassification.FALSE_NEGATIVE,
                        "synthetic"));

        DifferentialHarness.Gate gate = report.gate(defects);

        assertFalse(gate.passed());
        assertTrue(gate.failures().stream().anyMatch(failure -> failure.contains("stale")),
                () -> gate.failures().toString());
    }

    @Test void aReproducedConfirmedDefectDoesNotFailTheGate() {
        DifferentialHarness.Report report = reportWith(CapabilityClassification.FALSE_NEGATIVE);
        String label = label();
        Map<String, DifferentialHarness.ConfirmedDefect> defects = Map.of(label,
                new DifferentialHarness.ConfirmedDefect(label, CapabilityClassification.FALSE_NEGATIVE,
                        "synthetic"));

        DifferentialHarness.Gate gate = report.gate(defects);

        assertTrue(gate.passed(), () -> gate.failures().toString());
    }

    @Test void aConfirmedDefectWithADifferentClassificationFailsTheGate() {
        DifferentialHarness.Report report = reportWith(CapabilityClassification.FALSE_POSITIVE);
        String label = label();
        Map<String, DifferentialHarness.ConfirmedDefect> defects = Map.of(label,
                new DifferentialHarness.ConfirmedDefect(label, CapabilityClassification.FALSE_NEGATIVE,
                        "synthetic"));

        DifferentialHarness.Gate gate = report.gate(defects);

        assertFalse(gate.passed());
        assertTrue(gate.failures().stream().anyMatch(failure -> failure.contains("CONFIRMED_DEFECTS records")),
                () -> gate.failures().toString());
    }

    @Test void aNonDeterministicCaseFailsTheGate() {
        DifferentialHarness.Report report = reportWith(CapabilityClassification.SUPPORTED, false);

        DifferentialHarness.Gate gate = report.gate(Map.of());

        assertFalse(gate.passed());
        assertTrue(gate.failures().stream().anyMatch(failure -> failure.contains("non-deterministic")),
                () -> gate.failures().toString());
    }

    private static String label() {
        return SCENARIO.id() + "/" + SCENARIO.mode().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static DifferentialHarness.Report reportWith(CapabilityClassification classification) {
        return reportWith(classification, true);
    }

    private static DifferentialHarness.Report reportWith(CapabilityClassification classification,
                                                         boolean deterministic) {
        CapabilityRun run = new CapabilityRun(SCENARIO, classification, 0L, Double.NaN, null, null,
                null, null, null, "synthetic");
        DifferentialHarness.Entry entry = new DifferentialHarness.Entry(SCENARIO, run, deterministic,
                deterministic ? "" : "synthetic");
        return new DifferentialHarness.Report(new RaishxCoreCapabilityPlanner(Duration.ofSeconds(5)),
                List.of(entry), 0L);
    }
}
