package com.raishxn.ufocore.api.crafting.planner.differential;

import com.raishxn.ufocore.api.crafting.planner.differential.DifferentialHarness.Entry;
import com.raishxn.ufocore.api.crafting.planner.differential.DifferentialHarness.Report;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Runs the differential corpus through RaishxCore and the unmodified Thunderbolt V2 reference and
 * prints the capability matrix.
 *
 * <p>The point is to replace "their classes exist, so they probably solve it" with a measured
 * answer per case. A case only counts as supported when the engine's own production path produced a
 * plan that the shared replay oracle accepted.
 */
public final class CapabilityMatrix {

    public static void main(String[] args) throws IOException {
        Locale.setDefault(Locale.ROOT);
        CapabilityRunner runner = new CapabilityRunner(Duration.ofSeconds(30), Duration.ofMillis(500));
        Report core = new DifferentialHarness(
                new RaishxCoreCapabilityPlanner(Duration.ofSeconds(10)), runner).run();
        Report reference = new DifferentialHarness(new ThunderboltCapabilityPlanner(), runner).run();
        String rendered = render(core, reference);
        System.out.println(rendered);
        Path output = args.length > 0 ? Path.of(args[0])
                : Path.of("build", "reports", "planner", "capability-matrix.txt");
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, rendered);
        System.out.println("REPORT=" + output.toAbsolutePath());
    }

    static String render(Report core, Report reference) {
        StringBuilder text = new StringBuilder();
        text.append("== differential capability matrix ==\n");
        text.append("A=").append(core.planner().name())
                .append(" B=").append(reference.planner().name())
                .append(" scenarios=").append(core.entries().size()).append('\n');
        text.append("a case counts as supported only when the shared replay oracle accepted a plan\n\n");

        Map<String, Entry> byA = index(core);
        Map<String, Entry> byB = index(reference);
        TreeSet<String> keys = new TreeSet<>(byA.keySet());
        keys.addAll(byB.keySet());

        text.append(String.format(Locale.ROOT, "%-52s %-26s %-18s %-18s%n",
                "case", "semantics", "A:" + shortName(core), "B:" + shortName(reference)));
        for (String key : keys) {
            Entry left = byA.get(key);
            Entry right = byB.get(key);
            CapabilityScenario scenario = left != null ? left.scenario() : right.scenario();
            text.append(String.format(Locale.ROOT, "%-52s %-26s %-18s %-18s%n",
                    key, semantics(scenario),
                    left == null ? "-" : left.classification(),
                    right == null ? "-" : right.classification()));
        }

        text.append("\n== classification counts ==\n");
        Map<CapabilityClassification, Long> countsA = core.counts();
        Map<CapabilityClassification, Long> countsB = reference.counts();
        text.append(String.format(Locale.ROOT, "%-24s %12s %12s%n", "classification",
                core.planner().name(), reference.planner().name()));
        for (CapabilityClassification classification : CapabilityClassification.values()) {
            long a = countsA.getOrDefault(classification, 0L);
            long b = countsB.getOrDefault(classification, 0L);
            if (a == 0 && b == 0) continue;
            text.append(String.format(Locale.ROOT, "%-24s %12d %12d%n", classification, a, b));
        }

        appendLimitationVerdict(text, core, reference, byA, byB);
        return text.toString();
    }

    /**
     * The question the matrix exists to answer: for every case RaishxCore refuses, did the reference
     * engine actually solve it, refuse it too, or did this adapter simply fail to express it?
     */
    private static void appendLimitationVerdict(StringBuilder text, Report core, Report reference,
                                                Map<String, Entry> byA, Map<String, Entry> byB) {
        int solved = 0;
        int refused = 0;
        int gap = 0;
        int other = 0;
        StringBuilder rows = new StringBuilder();
        for (Entry left : core.entries()) {
            if (!left.classification().safeDecline()) continue;
            String key = key(left);
            Entry right = byB.get(key);
            String verdict;
            String detail = "";
            if (right == null) {
                verdict = "NOT-RUN";
                other++;
            } else if (right.classification().supported()) {
                verdict = "REFERENCE SOLVES IT";
                solved++;
            } else if (right.classification().safeDecline()) {
                boolean adapterGap = right.run().diagnostic().startsWith("adapter");
                verdict = adapterGap ? "ADAPTER GAP" : "ALSO REFUSED";
                detail = right.run().diagnostic();
                if (adapterGap) {
                    gap++;
                } else {
                    refused++;
                }
            } else {
                verdict = "REFERENCE DEFECT: " + right.classification();
                detail = right.run().diagnostic();
                other++;
            }
            rows.append(String.format(Locale.ROOT, "%-52s %-30s %-20s %s%n",
                    key, semantics(left.scenario()), verdict, detail));
        }

        text.append("\n== cases ").append(core.planner().name())
                .append(" refuses: what the reference actually does ==\n");
        text.append(rows);
        text.append('\n');
        text.append("solved by the reference : ").append(solved).append('\n');
        text.append("also refused by it      : ").append(refused).append('\n');
        text.append("adapter could not express: ").append(gap).append('\n');
        text.append("other                   : ").append(other).append('\n');
        text.append("\nA=adapter gap is a limitation of this adapter, not a verdict on the reference.\n");
        text.append("Reference elapsed=")
                .append(String.format(Locale.ROOT, "%.3fs", reference.elapsedNanos() / 1e9))
                .append("  A elapsed=")
                .append(String.format(Locale.ROOT, "%.3fs", core.elapsedNanos() / 1e9))
                .append('\n');
    }

    private static Map<String, Entry> index(Report report) {
        Map<String, Entry> index = new LinkedHashMap<>();
        for (Entry entry : report.entries()) {
            index.put(key(entry), entry);
        }
        return index;
    }

    private static String key(Entry entry) {
        return entry.scenario().id() + "/" + entry.mode().name().toLowerCase(Locale.ROOT);
    }

    private static String semantics(CapabilityScenario scenario) {
        StringBuilder joined = new StringBuilder();
        for (CapabilitySemantics value : new TreeSet<>(scenario.requiredSemantics())) {
            if (joined.length() > 0) joined.append(',');
            joined.append(value.name());
        }
        return joined.toString();
    }

    private static String shortName(Report report) {
        return report.planner().name();
    }

    private CapabilityMatrix() {
    }
}
