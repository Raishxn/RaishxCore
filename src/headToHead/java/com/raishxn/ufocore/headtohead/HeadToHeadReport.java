package com.raishxn.ufocore.headtohead;

import com.raishxn.ufocore.api.amount.UfoAmount;
import com.raishxn.ufocore.headtohead.PlannerHeadToHead.Measured;
import com.raishxn.ufocore.headtohead.PlannerHeadToHead.Row;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders the measured rows as a markdown report and a flat CSV. */
final class HeadToHeadReport {

    static final String EXACT = "RaishxCore.plan";
    static final String FAST = "RaishxCore.planFast";
    static final String THUNDERBOLT = "Thunderbolt-1.0.6";

    /**
     * Plan quality, lower is better: a complete and balanced plan, then a shortage ordered by its
     * weighted size, then anything that is not a usable plan (a limit, an error, a failed balance).
     */
    static Quality quality(BenchCase benchCase, Measured measured) {
        Engine.Result result = measured.result();
        if (!result.planned() || measured.balance().verdict() == PlanBalance.Verdict.FAIL) {
            return new Quality(2, BigInteger.ZERO);
        }
        if (result.status() == Engine.Status.COMPLETE) return new Quality(0, BigInteger.ZERO);
        return new Quality(1, PlannerHeadToHead.weightedCost(benchCase, result));
    }

    record Quality(int tier, BigInteger cost) implements Comparable<Quality> {
        @Override
        public int compareTo(Quality other) {
            int byTier = Integer.compare(tier, other.tier);
            return byTier != 0 ? byTier : cost.compareTo(other.cost);
        }
    }

    static int compare(BenchCase benchCase, Measured left, Measured right) {
        return Integer.signum(quality(benchCase, left).compareTo(quality(benchCase, right)));
    }

    /** Whether the plan matches the case's known optimum; {@code null} when no optimum is known. */
    static Boolean optimal(BenchCase benchCase, Measured measured) {
        if (!benchCase.optimumKnown()) return null;
        Engine.Result result = measured.result();
        if (!result.planned() || measured.balance().verdict() == PlanBalance.Verdict.FAIL) return false;
        if (benchCase.scenario().expectedFeasible()) return result.status() == Engine.Status.COMPLETE;
        if (result.status() == Engine.Status.COMPLETE) return false;
        Map<String, UfoAmount> missing = new LinkedHashMap<>();
        result.missing().forEach((key, amount) -> missing.put(key, UfoAmount.of(amount)));
        return Math.abs(benchCase.scenario().missingOverhead(missing) - 1.0) < 1e-9;
    }

    static Measured production(Row row) {
        return row.byEngine().get(row.productionFast() ? FAST : EXACT);
    }

    static String markdown(List<Row> rows, List<Engine> engines) {
        StringBuilder text = new StringBuilder();
        text.append("# Planner head-to-head (generated)\n\n");
        text.append("- generated: ").append(PlannerHeadToHead.generatedAt()).append('\n');
        text.append("- java: ").append(System.getProperty("java.version")).append(' ')
                .append(System.getProperty("java.vm.name")).append(", cpus=")
                .append(Runtime.getRuntime().availableProcessors()).append('\n');
        text.append("- engines: ");
        for (Engine engine : engines) text.append('`').append(engine.name()).append("` ");
        text.append("\n- `prod` = the RaishxCore entry point Ae2PlannerBridge picks (fast once the graph has >= ")
                .append(RaishxCoreEngine.PRODUCTION_EXACT_CHOICE_LIMIT).append(" route-choice alternatives)\n\n");

        appendSummary(text, rows);

        text.append("\n## Per case\n\n");
        text.append("Cell: `status missing executions median-ms`. Status: C complete, S shortage, L engine limit, ")
                .append("U unsupported, T harness timeout (60 s), E error. Suffix `!` balance check failed, ")
                .append("`~` answer changed between runs, `b` Thunderbolt reported its search budget exhausted, ")
                .append("`*` matches the known optimum.\n\n");
        text.append("| case | patterns | prod | ").append(EXACT).append(" | ").append(FAST).append(" | ")
                .append(THUNDERBOLT).append(" | prod vs TB quality | TB/prod time |\n");
        text.append("|---|---:|---|---|---|---|---|---:|\n");
        for (Row row : rows) {
            Measured prod = production(row);
            Measured thunderbolt = row.byEngine().get(THUNDERBOLT);
            text.append("| ").append(row.benchCase().id()).append(" | ").append(row.benchCase().patternCount())
                    .append(" | ").append(row.productionFast() ? "fast" : "exact")
                    .append(" | ").append(cell(row.benchCase(), row.byEngine().get(EXACT)))
                    .append(" | ").append(cell(row.benchCase(), row.byEngine().get(FAST)))
                    .append(" | ").append(cell(row.benchCase(), thunderbolt))
                    .append(" | ").append(verdict(compare(row.benchCase(), prod, thunderbolt)))
                    .append(" | ").append(ratio(thunderbolt, prod)).append(" |\n");
        }
        text.append("\n## Notes per engine\n\n");
        for (Row row : rows) {
            for (Map.Entry<String, Measured> entry : row.byEngine().entrySet()) {
                Engine.Result result = entry.getValue().result();
                boolean interesting = !result.planned() || entry.getValue().balance().verdict() == PlanBalance.Verdict.FAIL;
                if (!interesting) continue;
                text.append("- `").append(row.benchCase().id()).append("` ").append(entry.getKey()).append(": ")
                        .append(result.status()).append(' ').append(result.note());
                if (entry.getValue().balance().verdict() == PlanBalance.Verdict.FAIL) {
                    text.append(" balance ").append(entry.getValue().balance());
                }
                text.append('\n');
            }
        }
        return text.toString();
    }

    private static void appendSummary(StringBuilder text, List<Row> rows) {
        text.append("## Summary\n\n");
        text.append("| engine | complete | shortage | limit | unsupported | timeout/error | balance fail | optimal on known |\n");
        text.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (String engine : List.of(EXACT, FAST, THUNDERBOLT, "prod")) {
            int complete = 0, shortage = 0, limit = 0, unsupported = 0, failed = 0, balance = 0, optimal = 0, known = 0;
            for (Row row : rows) {
                Measured measured = engine.equals("prod") ? production(row) : row.byEngine().get(engine);
                switch (measured.result().status()) {
                    case COMPLETE -> complete++;
                    case SHORTAGE -> shortage++;
                    case LIMIT -> limit++;
                    case UNSUPPORTED -> unsupported++;
                    case TIMEOUT, ERROR -> failed++;
                }
                if (measured.balance().verdict() == PlanBalance.Verdict.FAIL) balance++;
                Boolean isOptimal = optimal(row.benchCase(), measured);
                if (isOptimal != null) {
                    known++;
                    if (isOptimal) optimal++;
                }
            }
            text.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %d | %d | %d/%d |%n",
                    engine.equals("prod") ? "RaishxCore (production pick)" : engine,
                    complete, shortage, limit, unsupported, failed, balance, optimal, known));
        }

        text.append("\n### Quality against Thunderbolt, by origin\n\n");
        text.append("| origin | RaishxCore entry | better | tie | worse |\n|---|---|---:|---:|---:|\n");
        for (String origin : List.of("corpus", "regression", "generated", "all")) {
            for (String engine : List.of(EXACT, FAST, "prod")) {
                int better = 0, tie = 0, worse = 0;
                for (Row row : rows) {
                    if (!origin.equals("all") && !row.benchCase().origin().equals(origin)) continue;
                    Measured mine = engine.equals("prod") ? production(row) : row.byEngine().get(engine);
                    int verdict = compare(row.benchCase(), mine, row.byEngine().get(THUNDERBOLT));
                    if (verdict < 0) better++;
                    else if (verdict == 0) tie++;
                    else worse++;
                }
                text.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %d |%n", origin, engine, better, tie, worse));
            }
        }

        text.append("\n### Plan size when both are complete (total pattern executions)\n\n");
        text.append("| origin | RaishxCore entry | both complete | RC fewer | equal | TB fewer |\n|---|---|---:|---:|---:|---:|\n");
        for (String origin : List.of("corpus", "regression", "generated", "all")) {
            for (String engine : List.of(EXACT, FAST, "prod")) {
                int both = 0, fewer = 0, equal = 0, more = 0;
                for (Row row : rows) {
                    if (!origin.equals("all") && !row.benchCase().origin().equals(origin)) continue;
                    Measured mine = engine.equals("prod") ? production(row) : row.byEngine().get(engine);
                    Measured theirs = row.byEngine().get(THUNDERBOLT);
                    if (mine.result().status() != Engine.Status.COMPLETE
                            || theirs.result().status() != Engine.Status.COMPLETE) continue;
                    both++;
                    int byExecutions = mine.result().executions().compareTo(theirs.result().executions());
                    if (byExecutions < 0) fewer++;
                    else if (byExecutions == 0) equal++;
                    else more++;
                }
                text.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %d | %d |%n",
                        origin, engine, both, fewer, equal, more));
            }
        }

        text.append("\n### Speed against Thunderbolt (cases where both returned a plan)\n\n");
        text.append("Geometric mean of Thunderbolt median / RaishxCore median; above 1 means RaishxCore is faster.\n\n");
        text.append("| origin | RaishxCore entry | cases | geo-mean TB/RC | RC faster | TB faster |\n|---|---|---:|---:|---:|---:|\n");
        for (String origin : List.of("corpus", "regression", "generated", "all")) {
            for (String engine : List.of(EXACT, FAST, "prod")) {
                List<Double> ratios = new ArrayList<>();
                int faster = 0, slower = 0;
                for (Row row : rows) {
                    if (!origin.equals("all") && !row.benchCase().origin().equals(origin)) continue;
                    Measured mine = engine.equals("prod") ? production(row) : row.byEngine().get(engine);
                    Measured theirs = row.byEngine().get(THUNDERBOLT);
                    if (!mine.result().planned() || !theirs.result().planned()) continue;
                    double ratio = Math.max(theirs.medianMs(), 1e-4) / Math.max(mine.medianMs(), 1e-4);
                    ratios.add(ratio);
                    if (ratio > 1) faster++;
                    else if (ratio < 1) slower++;
                }
                double log = 0;
                for (double ratio : ratios) log += Math.log(ratio);
                String mean = ratios.isEmpty() ? "-" : String.format(Locale.ROOT, "%.2f", Math.exp(log / ratios.size()));
                text.append(String.format(Locale.ROOT, "| %s | %s | %d | %s | %d | %d |%n",
                        origin, engine, ratios.size(), mean, faster, slower));
            }
        }
    }

    private static String cell(BenchCase benchCase, Measured measured) {
        Engine.Result result = measured.result();
        String status = switch (result.status()) {
            case COMPLETE -> "C";
            case SHORTAGE -> "S";
            case LIMIT -> "L";
            case UNSUPPORTED -> "U";
            case TIMEOUT -> "T";
            case ERROR -> "E";
        };
        StringBuilder flags = new StringBuilder();
        if (measured.balance().verdict() == PlanBalance.Verdict.FAIL) flags.append('!');
        if (!measured.deterministic()) flags.append('~');
        if (result.budgetHit()) flags.append('b');
        if (Boolean.TRUE.equals(optimal(benchCase, measured))) flags.append('*');
        if (!result.planned()) {
            return status + flags + (Double.isNaN(measured.medianMs()) ? "" : String.format(Locale.ROOT, " %s", ms(measured.medianMs())));
        }
        return status + flags + " " + result.missingTotal() + " " + result.executions() + " " + ms(measured.medianMs());
    }

    private static String ms(double value) {
        if (Double.isNaN(value)) return "-";
        if (value < 1) return String.format(Locale.ROOT, "%.3f", value);
        if (value < 100) return String.format(Locale.ROOT, "%.2f", value);
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private static String verdict(int comparison) {
        return comparison < 0 ? "**RC better**" : comparison > 0 ? "**TB better**" : "tie";
    }

    private static String ratio(Measured thunderbolt, Measured mine) {
        if (!thunderbolt.result().planned() || !mine.result().planned()) return "-";
        return String.format(Locale.ROOT, "%.2f", Math.max(thunderbolt.medianMs(), 1e-4) / Math.max(mine.medianMs(), 1e-4));
    }

    static String csv(List<Row> rows, List<Engine> engines) {
        StringBuilder text = new StringBuilder("case,origin,patterns,production,engine,status,complete,missing_total,"
                + "weighted_missing,executions,median_ms,min_ms,runs,balance,deterministic,budget_hit,optimal,note\n");
        for (Row row : rows) {
            for (Engine engine : engines) {
                Measured measured = row.byEngine().get(engine.name());
                Engine.Result result = measured.result();
                Boolean isOptimal = optimal(row.benchCase(), measured);
                text.append(String.join(",", row.benchCase().id(), row.benchCase().origin(),
                        Integer.toString(row.benchCase().patternCount()), row.productionFast() ? "fast" : "exact",
                        engine.name(), result.status().name(), Boolean.toString(result.status() == Engine.Status.COMPLETE),
                        result.planned() ? result.missingTotal().toString() : "",
                        result.planned() ? PlannerHeadToHead.weightedCost(row.benchCase(), result).toString() : "",
                        result.planned() ? result.executions().toString() : "",
                        String.format(Locale.ROOT, "%.4f", measured.medianMs()),
                        String.format(Locale.ROOT, "%.4f", measured.minMs()), Integer.toString(measured.runs()),
                        measured.balance().verdict().name(), Boolean.toString(measured.deterministic()),
                        Boolean.toString(result.budgetHit()), isOptimal == null ? "" : isOptimal.toString(),
                        '"' + result.note().replace('"', '\'') + '"')).append('\n');
            }
        }
        return text.toString();
    }

    private HeadToHeadReport() {
    }
}
