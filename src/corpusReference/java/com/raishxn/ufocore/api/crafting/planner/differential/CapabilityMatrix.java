package com.raishxn.ufocore.api.crafting.planner.differential;

import com.raishxn.ufocore.api.crafting.planner.differential.DifferentialHarness.Entry;
import com.raishxn.ufocore.api.crafting.planner.differential.DifferentialHarness.Report;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Runs the differential corpus through RaishxCore and the unmodified Thunderbolt V2 reference and
 * prints two levels of answer.
 *
 * <p><b>Claim level</b> is comparable across engines: it uses only the fields every planner reports
 * and answers "does this engine claim to solve the case, and how good is the shortage it declares".
 *
 * <p><b>Replay level</b> is proof, and it stays with RaishxCore. The shared oracle re-executes an
 * ordered schedule and compares a reported residue; a foreign plan need not carry either field, so
 * a replay verdict for it would measure the adapter rather than the engine.
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
        Map<String, Entry> byA = index(core);
        Map<String, Entry> byB = index(reference);
        Map<String, CapabilityClaim> claimsA = claims(byA);
        Map<String, CapabilityClaim> claimsB = claims(byB);

        TreeSet<String> keys = new TreeSet<>(byA.keySet());
        keys.addAll(byB.keySet());

        StringBuilder text = new StringBuilder();
        appendProvenance(text);
        text.append("== differential capability matrix ==\n");
        text.append("A=").append(core.planner().name())
                .append(" B=").append(reference.planner().name())
                .append(" scenarios=").append(keys.size()).append('\n');
        text.append("claim = what the engine reports; it is comparable but it is not proof\n\n");

        text.append(String.format(Locale.ROOT, "%-50s %-24s %-17s %-17s%n",
                "case", "semantics", "A:" + core.planner().name(), "B:" + reference.planner().name()));
        for (String key : keys) {
            Entry left = byA.get(key);
            Entry right = byB.get(key);
            CapabilityScenario scenario = left != null ? left.scenario() : right.scenario();
            text.append(String.format(Locale.ROOT, "%-50s %-24s %-17s %-17s%n", key, semantics(scenario),
                    claimName(claimsA.get(key)), claimName(claimsB.get(key))));
        }

        appendCounts(text, core, reference, claimsA, claimsB);
        appendShortageQuality(text, core, reference, byA, byB, claimsA, claimsB);
        appendReferenceVerdict(text, core, claimsA, claimsB);
        appendPerformance(text);
        return text.toString();
    }

    /**
     * Performance is measured by a separate task, so this section reproduces it when the benchmark has
     * already run and says plainly when it has not. The roadmap asks for one reproducible report
     * covering capability, quality and cost; a matrix without the numbers is half of it.
     */
    private static void appendPerformance(StringBuilder text) {
        Path csv = Path.of("build", "reports", "planner", "benchmark.csv");
        text.append("\n== performance (from plannerBenchmark) ==\n");
        if (!Files.exists(csv)) {
            text.append("not measured in this run: execute plannerBenchmark first\n");
            return;
        }
        List<String> rows;
        try {
            rows = Files.readAllLines(csv);
        } catch (IOException unreadable) {
            text.append("unreadable: ").append(unreadable).append('\n');
            return;
        }
        text.append(String.format(Locale.ROOT, "%-14s %-28s %12s %12s %14s%n",
                "engine", "scenario/phase", "p50_us", "p95_us", "alloc_bytes_op"));
        for (String row : rows) {
            String[] parts = row.split(",");
            if (parts.length < 7 || "engine".equals(parts[0])) continue;
            text.append(String.format(Locale.ROOT, "%-14s %-28s %12s %12s %14s%n",
                    parts[0], parts[1] + "/" + parts[2], parts[4], parts[5], parts[6]));
        }
        text.append("\nplannerBenchmark gates these against a recorded baseline; this report only\n")
                .append("reproduces them alongside the capability and shortage evidence above\n");
    }

    /**
     * Shortage quality is where "better" actually means something once both engines can plan a case.
     * The number is the reported shortage divided by the best shortage the corpus knows, so 1.000 is
     * optimal and anything above it is a plan that asks the player for more than necessary.
     */
    private static void appendShortageQuality(StringBuilder text, Report core, Report reference,
                                              Map<String, Entry> byA, Map<String, Entry> byB,
                                              Map<String, CapabilityClaim> claimsA,
                                              Map<String, CapabilityClaim> claimsB) {
        text.append("\n== shortage quality on the missing mode (reported / known minimum) ==\n");
        text.append(String.format(Locale.ROOT, "%-50s %14s %14s%n", "case",
                core.planner().name(), reference.planner().name()));
        int mineOptimal = 0;
        int mineWorse = 0;
        double worstMine = 0;
        int theirsOptimal = 0;
        int theirsWorse = 0;
        for (String key : new TreeSet<>(byA.keySet())) {
            if (!key.endsWith("/missing")) continue;
            CapabilityClaim mine = claimsA.get(key);
            CapabilityClaim theirs = claimsB.get(key);
            text.append(String.format(Locale.ROOT, "%-50s %14s %14s%n", key,
                    overhead(mine), overhead(theirs)));
            if (mine != null && !Double.isNaN(mine.overhead())) {
                if (mine.overhead() <= 1.0 + 1e-9) {
                    mineOptimal++;
                } else {
                    mineWorse++;
                    worstMine = Math.max(worstMine, mine.overhead());
                }
            }
            if (theirs != null && !Double.isNaN(theirs.overhead())) {
                if (theirs.overhead() <= 1.0 + 1e-9) {
                    theirsOptimal++;
                } else {
                    theirsWorse++;
                }
            }
        }
        text.append('\n').append(core.planner().name()).append(": optimal on ").append(mineOptimal)
                .append(", worse than optimal on ").append(mineWorse)
                .append(String.format(Locale.ROOT, " (worst %.3fx)%n", worstMine));
        text.append(reference.planner().name()).append(": optimal on ").append(theirsOptimal)
                .append(", worse than optimal on ").append(theirsWorse).append('\n');
        text.append("\nA cell is empty when the engine did not report a comparable shortage.\n");
    }

    private static String overhead(CapabilityClaim claim) {
        if (claim == null || Double.isNaN(claim.overhead())) return "-";
        return String.format(Locale.ROOT, "%.3f", claim.overhead());
    }

    private static void appendCounts(StringBuilder text, Report core, Report reference,
                                     Map<String, CapabilityClaim> claimsA,
                                     Map<String, CapabilityClaim> claimsB) {
        text.append("\n== claim level (comparable across engines) ==\n");
        text.append(String.format(Locale.ROOT, "%-20s %12s %12s%n", "verdict",
                core.planner().name(), reference.planner().name()));
        for (CapabilityClaim.Verdict verdict : CapabilityClaim.Verdict.values()) {
            long a = claimsA.values().stream().filter(claim -> claim.verdict() == verdict).count();
            long b = claimsB.values().stream().filter(claim -> claim.verdict() == verdict).count();
            if (a == 0 && b == 0) continue;
            text.append(String.format(Locale.ROOT, "%-20s %12d %12d%n", verdict, a, b));
        }

        text.append("\n== ").append(core.planner().name())
                .append(" strict gate (replay-verified evidence) ==\n");
        text.append(String.format(Locale.ROOT, "%-24s %12s%n", "classification", "count"));
        for (Map.Entry<CapabilityClassification, Long> entry : core.counts().entrySet()) {
            if (entry.getValue() == 0L) continue;
            text.append(String.format(Locale.ROOT, "%-24s %12d%n", entry.getKey(), entry.getValue()));
        }
        text.append("\nReplay verdicts are reported for ").append(core.planner().name())
                .append(" only: the oracle needs an ordered schedule and a residue, which a foreign\n")
                .append("plan need not carry. Judging ").append(reference.planner().name())
                .append(" by it would measure this adapter, not that engine.\n");
    }

    private static void appendReferenceVerdict(StringBuilder text, Report core,
                                               Map<String, CapabilityClaim> claimsA,
                                               Map<String, CapabilityClaim> claimsB) {
        int complete = 0;
        int shortage = 0;
        int unsupported = 0;
        int error = 0;
        StringBuilder rows = new StringBuilder();
        for (Entry left : core.entries()) {
            CapabilityClaim mine = claimsA.get(key(left));
            if (mine == null || mine.verdict() != CapabilityClaim.Verdict.UNSUPPORTED) continue;
            String key = key(left);
            CapabilityClaim theirs = claimsB.get(key);
            String verdict = theirs == null ? "NOT-RUN" : theirs.verdict().name();
            String detail = theirs == null ? "" : theirs.detail();
            if (theirs != null) {
                switch (theirs.verdict()) {
                    case CLAIMS_COMPLETE -> complete++;
                    case CLAIMS_SHORTAGE -> shortage++;
                    case UNSUPPORTED -> unsupported++;
                    case ERROR -> error++;
                }
            }
            rows.append(String.format(Locale.ROOT, "%-50s %-24s %-17s %s%n",
                    key, semantics(left.scenario()), verdict, detail));
        }

        text.append("\n== cases ").append(core.planner().name())
                .append(" does not support: what the reference claims ==\n");
        if (complete + shortage + unsupported + error == 0) {
            // Worth saying out loud rather than printing four zeroes: this section is the comparison
            // that only exists while there is a gap, and the gap closing is the result, not a bug.
            text.append("none: the engine claims every case in the corpus, so there is nothing left\n")
                    .append("for the reference to be the only one to answer. The comparison that\n")
                    .append("remains is the claim level and the shortage quality above.\n");
        }
        text.append(rows).append('\n');
        text.append("reference claims to solve it : ").append(complete).append('\n');
        text.append("reference claims a shortage  : ").append(shortage).append('\n');
        text.append("reference does not claim it  : ").append(unsupported).append('\n');
        text.append("reference errored            : ").append(error).append('\n');
        text.append("\nA claim is not a verdict: for the families above, confirm the reference with its\n")
                .append("own replay or an independent oracle before treating any of these as solved.\n");
    }

    /**
     * Provenance header. A comparison number only reproduces if it names what produced it, so the
     * report leads with the commits and the environment instead of leaving them to be guessed - the
     * roadmap asks for a reproducible report, and a table without this is an anecdote.
     */
    private static void appendProvenance(StringBuilder text) {
        text.append("== provenance ==\n");
        text.append("raishxcore commit    : ").append(gitRevision(Path.of("."))).append('\n');
        text.append("thunderbolt checkout : ").append(Path.of("..", "Thunderbolt-Core").toAbsolutePath().normalize())
                .append(" @ ").append(gitRevision(Path.of("..", "Thunderbolt-Core"))).append('\n');
        text.append("java                 : ").append(System.getProperty("java.version")).append(' ')
                .append(System.getProperty("java.vm.name")).append('\n');
        text.append("os                   : ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append(" cpus=")
                .append(Runtime.getRuntime().availableProcessors()).append('\n');
        text.append("generated            : ").append(Instant.now()).append('\n');
        text.append("note                 : timing is not measured here; the engines run once for the\n")
                .append("                       capability verdict, while the benchmark gates performance\n\n");
    }

    /** Reads a checkout's revision, or says so plainly when the directory is not a git checkout. */
    static String gitRevision(Path directory) {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 && !output.isEmpty() ? output : "unknown";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "unknown";
        } catch (Exception unavailable) {
            return "unknown";
        }
    }

    private static Map<String, CapabilityClaim> claims(Map<String, Entry> byKey) {
        Map<String, CapabilityClaim> claims = new LinkedHashMap<>();
        byKey.forEach((key, entry) -> claims.put(key, CapabilityClaim.of(entry.scenario(), entry.run())));
        return claims;
    }

    private static String claimName(CapabilityClaim claim) {
        return claim == null ? "-" : claim.verdict().name();
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

    private CapabilityMatrix() {
    }
}
