package com.raishxn.ufocore.api.crafting.planner.differential;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic differential harness for the capability corpus.
 *
 * <p>The harness owns nothing engine specific: a {@link CapabilityPlanner} is passed in, every case
 * is executed with a hard deadline, and every produced plan is replayed by the common oracle. It is
 * safe to run as a CI gate: results are reproducible, order-independent and never inferred from
 * timing.
 */
public final class DifferentialHarness {

    /** One observed defect that is retained explicitly instead of being hidden or downgraded. */
    public record ConfirmedDefect(String label, CapabilityClassification classification, String reason) {
        public ConfirmedDefect {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private static final String BYPRODUCT_ORDER_DEFECT =
            "a demanded byproduct is resolved before the sibling route that produces it, so a "
                    + "composite whose coproduct key sorts before its primary keys reports an "
                    + "impossible shortage instead of running the producing route first";

    /**
     * Defects already observed and deliberately not hidden. The gate fails when an unlisted defect
     * appears and also when a listed entry stops being a defect, so the list can never go stale and
     * a new regression can never hide behind it.
     *
     * <p>Every entry below is a false negative on a capability the corpus lists as required. The
     * cases stay classified as {@link CapabilityClassification#FALSE_NEGATIVE}; a limitation is never
     * counted as support, and no scenario is removed or weakened to make the corpus pass.
     */
    public static final List<ConfirmedDefect> CONFIRMED_DEFECTS = List.of(
            new ConfirmedDefect("byproduct/shared-coproduct/minimum",
                    CapabilityClassification.FALSE_NEGATIVE, BYPRODUCT_ORDER_DEFECT),
            new ConfirmedDefect("byproduct/shared-coproduct/unbounded",
                    CapabilityClassification.FALSE_NEGATIVE, BYPRODUCT_ORDER_DEFECT),
            new ConfirmedDefect("byproduct/feeds-later-stage/minimum",
                    CapabilityClassification.FALSE_NEGATIVE, BYPRODUCT_ORDER_DEFECT),
            new ConfirmedDefect("byproduct/feeds-later-stage/unbounded",
                    CapabilityClassification.FALSE_NEGATIVE, BYPRODUCT_ORDER_DEFECT));

    private static Map<String, ConfirmedDefect> defectsByLabel() {
        LinkedHashMap<String, ConfirmedDefect> byLabel = new LinkedHashMap<>();
        for (ConfirmedDefect defect : CONFIRMED_DEFECTS) {
            ConfirmedDefect previous = byLabel.put(defect.label(), defect);
            if (previous != null) {
                throw new IllegalStateException("duplicate confirmed defect " + defect.label());
            }
        }
        return byLabel;
    }

    private final CapabilityPlanner planner;
    private final CapabilityRunner runner;

    public DifferentialHarness(CapabilityPlanner planner, CapabilityRunner runner) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** One executed case plus its determinism verification. */
    public record Entry(CapabilityScenario scenario, CapabilityRun run, boolean deterministic,
                        String determinismNote) {
        public String id() {
            return scenario.id();
        }

        public CapabilityMaterialMode mode() {
            return scenario.mode();
        }

        public CapabilityClassification classification() {
            return run.classification();
        }
    }

    /** Full harness output, suitable for printing, uploading and gating. */
    public record Report(CapabilityPlanner planner, List<Entry> entries, long elapsedNanos) {
        public Report {
            entries = List.copyOf(entries);
        }

        public Map<CapabilityClassification, Long> counts() {
            EnumMap<CapabilityClassification, Long> counts =
                    new EnumMap<>(CapabilityClassification.class);
            for (CapabilityClassification classification : CapabilityClassification.values()) {
                counts.put(classification, 0L);
            }
            for (Entry entry : entries) {
                counts.merge(entry.classification(), 1L, Long::sum);
            }
            return counts;
        }

        public List<Entry> of(CapabilityExpectation expectation) {
            return entries.stream()
                    .filter(entry -> entry.scenario().expectation() == expectation)
                    .toList();
        }

        public String render() {
            StringBuilder text = new StringBuilder();
            text.append("== differential capability corpus ==\n");
            text.append("engine=").append(planner.name())
                    .append(" scenarios=").append(entries.size())
                    .append(" required=").append(of(CapabilityExpectation.REQUIRED).size())
                    .append(" limitations=").append(of(CapabilityExpectation.LIMITATION).size())
                    .append(" elapsed=").append(milliseconds(elapsedNanos)).append('\n');
            text.append("\n== per scenario ==\n");
            for (Entry entry : entries) {
                CapabilityRun run = entry.run();
                text.append(String.format(Locale.ROOT, "%-46s %-24s %-9s %-22s %8s %-28s %-10s %s%n",
                        entry.id() + "/" + entry.mode().name().toLowerCase(Locale.ROOT),
                        entry.scenario().family(),
                        entry.mode(),
                        entry.classification(),
                        milliseconds(run.elapsedNanos()),
                        "missing=" + run.reportedMissing(),
                        overhead(run.missingOverhead()),
                        entry.deterministic() ? run.diagnostic() : "NON DETERMINISTIC: " + entry.determinismNote()));
            }
            text.append("\n== classification summary ==\n");
            counts().forEach((classification, count) ->
                    text.append(String.format(Locale.ROOT, "%-24s %d%n", classification, count)));
            text.append("\n== declared limitations (never counted as support) ==\n");
            for (Entry entry : of(CapabilityExpectation.LIMITATION)) {
                text.append(String.format(Locale.ROOT, "%-46s %-22s %-22s %s%n",
                        entry.id() + "/" + entry.mode().name().toLowerCase(Locale.ROOT),
                        entry.scenario().family(), entry.classification(), entry.run().diagnostic()));
            }
            text.append("\n== required capability outcome ==\n");
            text.append("required supported=")
                    .append(supportedRequired()).append('/').append(of(CapabilityExpectation.REQUIRED).size())
                    .append(" confirmed defects=").append(CONFIRMED_DEFECTS.size())
                    .append(" limitation cases supported but not claimed=")
                    .append(supportedLimitations()).append('/')
                    .append(of(CapabilityExpectation.LIMITATION).size()).append('\n');
            CONFIRMED_DEFECTS.forEach(defect -> text.append("  open defect: ").append(defect.label())
                    .append(" ").append(defect.classification()).append(" — ")
                    .append(defect.reason()).append('\n'));
            text.append("\n== gate ==\n");
            Gate gate = gate();
            text.append(gate.passed() ? "PASSED" : "FAILED").append('\n');
            gate.failures().forEach(failure -> text.append("FAILURE: ").append(failure).append('\n'));
            gate.findings().forEach(finding -> text.append("finding: ").append(finding).append('\n'));
            return text.toString();
        }

        public void write(Path path) throws IOException {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, render(), StandardCharsets.UTF_8);
        }

        public long supportedRequired() {
            return of(CapabilityExpectation.REQUIRED).stream().filter(entry -> entry.run().supported()).count();
        }

        public long supportedLimitations() {
            return of(CapabilityExpectation.LIMITATION).stream()
                    .filter(entry -> entry.run().supported()).count();
        }

        /** Admission and classification gate applied by the CI harness. */
        public Gate gate() {
            ArrayList<String> failures = new ArrayList<>();
            ArrayList<String> findings = new ArrayList<>();
            Map<String, ConfirmedDefect> defects = defectsByLabel();
            java.util.HashSet<String> observed = new java.util.HashSet<>();
            for (Entry entry : entries) {
                String label = entry.id() + "/" + entry.mode().name().toLowerCase(Locale.ROOT);
                ConfirmedDefect confirmed = defects.get(label);
                if (!entry.deterministic()) {
                    failures.add("non-deterministic result for " + label + ": " + entry.determinismNote());
                }
                if (entry.classification().defect()) {
                    observed.add(label);
                    if (confirmed == null) {
                        failures.add(label + " classified " + entry.classification()
                                + " without an entry in CONFIRMED_DEFECTS: " + entry.run().diagnostic());
                    } else if (confirmed.classification() != entry.classification()) {
                        failures.add(label + " is now " + entry.classification()
                                + " but CONFIRMED_DEFECTS records " + confirmed.classification());
                    }
                } else if (confirmed != null) {
                    failures.add("stale CONFIRMED_DEFECTS entry for " + label
                            + ": it is now " + entry.classification() + ", remove the entry");
                }
                if (entry.scenario().expectation() == CapabilityExpectation.REQUIRED
                        && !entry.run().supported() && confirmed == null) {
                    failures.add(label + " is a required capability but classified "
                            + entry.classification() + ": " + entry.run().diagnostic());
                }
                if (entry.scenario().expectation() == CapabilityExpectation.LIMITATION
                        && entry.run().supported()) {
                    findings.add(label + " replayed successfully but is still a declared limitation");
                }
                double overhead = entry.run().missingOverhead();
                if (!Double.isNaN(overhead) && overhead > 1.0D
                        && entry.scenario().expectation() == CapabilityExpectation.REQUIRED) {
                    findings.add(label + " reported shortage overhead " + overhead);
                }
            }
            for (String label : defects.keySet()) {
                if (!observed.contains(label)) {
                    failures.add("CONFIRMED_DEFECTS entry " + label + " did not reproduce");
                }
            }
            return new Gate(failures.isEmpty(), failures, findings);
        }
    }

    /** Gate outcome with every failure kept separate from advisory findings. */
    public record Gate(boolean passed, List<String> failures, List<String> findings) {
        public Gate {
            failures = List.copyOf(failures);
            findings = List.copyOf(findings);
        }
    }

    /** Runs the whole corpus, verifying determinism by re-running with reversed pattern order. */
    public Report run() {
        return run(CapabilityCorpus.all());
    }

    public Report run(List<CapabilityScenario> scenarios) {
        long started = System.nanoTime();
        ArrayList<Entry> entries = new ArrayList<>(scenarios.size());
        for (CapabilityScenario scenario : scenarios) {
            CapabilityRun primary = runner.run(planner, scenario);
            CapabilityRun repeated = runner.run(planner, scenario.reversedPatternOrder());
            boolean deterministic = primary.classification() == repeated.classification()
                    && samePlan(primary.plan(), repeated.plan())
                    && samePlan(primary.refillPlan(), repeated.refillPlan());
            entries.add(new Entry(scenario, primary, deterministic,
                    deterministic ? "" : "primary=" + primary.classification()
                            + " reversed=" + repeated.classification()));
        }
        return new Report(planner, entries, Math.max(0L, System.nanoTime() - started));
    }

    private static boolean samePlan(CapabilityPlan left, CapabilityPlan right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.sameSemantics(right);
    }

    private static String milliseconds(long nanos) {
        return String.format(Locale.ROOT, "%.3fms", nanos / 1_000_000.0D);
    }

    private static String overhead(double value) {
        return Double.isNaN(value) ? "overhead=n/a" : String.format(Locale.ROOT, "overhead=%.3f", value);
    }

    /** Direct executable entry point used by the {@code plannerDifferential} Gradle task. */
    public static void main(String[] args) throws IOException {
        Locale.setDefault(Locale.ROOT);
        CapabilityPlanner planner = new RaishxCoreCapabilityPlanner(Duration.ofSeconds(10));
        CapabilityRunner runner = new CapabilityRunner(Duration.ofSeconds(30), Duration.ofMillis(500));
        DifferentialHarness harness = new DifferentialHarness(planner, runner);
        Report report = harness.run();
        System.out.println(report.render());
        Path output = args.length > 0 ? Path.of(args[0])
                : Path.of("build", "reports", "planner", "differential.txt");
        report.write(output);
        System.out.println("REPORT=" + output.toAbsolutePath());
        if (!report.gate().passed()) {
            System.err.println("Differential corpus gate failed with "
                    + report.gate().failures().size() + " failure(s).");
            System.exit(1);
        }
    }
}
