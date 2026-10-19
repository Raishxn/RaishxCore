package com.raishxn.ufocore.headtohead;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Head-to-head benchmark: RaishxCore {@code plan} and {@code planFast} against Thunderbolt 1.0.6
 * {@code CraftPlannerV2} on the same neutral scenarios.
 *
 * <p>Per case and engine it records wall time (one warm-up pass over every case first, then several
 * warm runs, median and minimum), whether the plan is complete, the total reported shortage, the
 * total number of pattern executions, and an order-free material balance check of the plan.
 *
 * <p>Arguments: output directory, then an optional regex selecting case ids.
 */
public final class PlannerHeadToHead {

    /** Harness deadline per planning call. RaishxCore stops itself at 2 s; Thunderbolt has no clock. */
    private static final long CALL_TIMEOUT_SECONDS = 60;

    record Measured(Engine.Result result, PlanBalance.Check balance, double medianMs, double minMs,
                    int runs, boolean deterministic) {
    }

    record Row(BenchCase benchCase, boolean productionFast, Map<String, Measured> byEngine) {
    }

    public static void main(String[] args) throws IOException {
        Locale.setDefault(Locale.ROOT);
        Path outDir = Path.of(args.length > 0 ? args[0] : "build/reports/planner");
        Pattern filter = args.length > 1 ? Pattern.compile(args[1]) : null;
        List<Engine> engines = List.of(new RaishxCoreEngine(true), new RaishxCoreEngine(false),
                new Thunderbolt106Engine());
        List<BenchCase> cases = new ArrayList<>();
        for (BenchCase benchCase : BenchScenarios.all()) {
            if (filter == null || filter.matcher(benchCase.id()).find()) cases.add(benchCase);
        }
        System.out.println("cases=" + cases.size() + " engines=" + engines.size());

        Harness harness = new Harness();
        try {
            // Prepare every case untimed, then one pass over everything so no engine is measured cold.
            Map<String, Map<String, Engine.Prepared>> prepared = new LinkedHashMap<>();
            Map<String, Map<String, String>> unsupported = new LinkedHashMap<>();
            for (BenchCase benchCase : cases) {
                for (Engine engine : engines) {
                    try {
                        prepared.computeIfAbsent(benchCase.id(), ignored -> new LinkedHashMap<>())
                                .put(engine.name(), engine.prepare(benchCase));
                    } catch (Engine.Unsupported | ArithmeticException gap) {
                        unsupported.computeIfAbsent(benchCase.id(), ignored -> new LinkedHashMap<>())
                                .put(engine.name(), gap.getMessage());
                    }
                }
            }
            Map<String, Double> warmMs = new LinkedHashMap<>();
            for (int pass = 0; pass < 2; pass++) {
                for (BenchCase benchCase : cases) {
                    for (Engine engine : engines) {
                        Engine.Prepared run = prepared.getOrDefault(benchCase.id(), Map.of()).get(engine.name());
                        if (run == null) continue;
                        String key = benchCase.id() + "|" + engine.name();
                        if (warmMs.containsKey(key) && warmMs.get(key) < 0) continue;
                        Timed timed = harness.time(run);
                        warmMs.put(key, timed.result().status() == Engine.Status.TIMEOUT ? -1.0 : timed.ms());
                    }
                }
            }

            List<Row> rows = new ArrayList<>();
            for (BenchCase benchCase : cases) {
                Map<String, Measured> byEngine = new LinkedHashMap<>();
                for (Engine engine : engines) {
                    Engine.Prepared run = prepared.getOrDefault(benchCase.id(), Map.of()).get(engine.name());
                    if (run == null) {
                        String why = unsupported.getOrDefault(benchCase.id(), Map.of()).getOrDefault(engine.name(), "");
                        byEngine.put(engine.name(), new Measured(Engine.Result.failed(Engine.Status.UNSUPPORTED, why),
                                new PlanBalance.Check(PlanBalance.Verdict.NOT_CHECKED, ""), Double.NaN, Double.NaN,
                                0, true));
                        continue;
                    }
                    byEngine.put(engine.name(), measure(harness, benchCase, run,
                            warmMs.get(benchCase.id() + "|" + engine.name())));
                }
                Row row = new Row(benchCase, RaishxCoreEngine.productionUsesFast(benchCase), byEngine);
                rows.add(row);
                System.out.println(line(row));
            }
            Files.createDirectories(outDir);
            Path markdown = outDir.resolve("head-to-head.md");
            Path csv = outDir.resolve("head-to-head.csv");
            Files.writeString(markdown, HeadToHeadReport.markdown(rows, engines));
            Files.writeString(csv, HeadToHeadReport.csv(rows, engines));
            System.out.println("REPORT=" + markdown.toAbsolutePath());
            System.out.println("CSV=" + csv.toAbsolutePath());
        } finally {
            harness.close();
        }
    }

    private static Measured measure(Harness harness, BenchCase benchCase, Engine.Prepared run, Double warm) {
        if (warm != null && warm < 0) {
            return new Measured(Engine.Result.failed(Engine.Status.TIMEOUT, "no answer in "
                    + CALL_TIMEOUT_SECONDS + " s"), new PlanBalance.Check(PlanBalance.Verdict.NOT_CHECKED, ""),
                    Double.NaN, Double.NaN, 0, true);
        }
        double estimate = warm == null ? 1.0 : warm;
        int runs = estimate < 1 ? 31 : estimate < 20 ? 15 : estimate < 300 ? 7 : 3;
        harness.time(run);
        double[] samples = new double[runs];
        Engine.Result first = null;
        boolean deterministic = true;
        for (int i = 0; i < runs; i++) {
            Timed timed = harness.time(run);
            samples[i] = timed.ms();
            if (first == null) {
                first = timed.result();
            } else if (!sameAnswer(first, timed.result())) {
                deterministic = false;
            }
            if (timed.result().status() == Engine.Status.TIMEOUT) {
                return new Measured(timed.result(), new PlanBalance.Check(PlanBalance.Verdict.NOT_CHECKED, ""),
                        Double.NaN, Double.NaN, i + 1, deterministic);
            }
        }
        Arrays.sort(samples);
        return new Measured(first, PlanBalance.check(benchCase.scenario(), first), samples[runs / 2], samples[0],
                runs, deterministic);
    }

    private static boolean sameAnswer(Engine.Result left, Engine.Result right) {
        return left.status() == right.status() && left.missing().equals(right.missing())
                && left.firings().equals(right.firings());
    }

    static BigInteger weightedCost(BenchCase benchCase, Engine.Result result) {
        Map<String, UfoAmount> missing = new LinkedHashMap<>();
        result.missing().forEach((key, amount) -> missing.put(key, UfoAmount.of(amount)));
        return benchCase.scenario().exactWeightedCost(missing);
    }

    private static String line(Row row) {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%-60s", row.benchCase().id()));
        row.byEngine().forEach((name, measured) -> text.append(String.format(Locale.ROOT, " | %s %s miss=%s exe=%s %.3fms %s",
                name, measured.result().status(), measured.result().planned() ? measured.result().missingTotal() : "-",
                measured.result().planned() ? measured.result().executions() : "-", measured.medianMs(),
                measured.balance())));
        return text.toString();
    }

    record Timed(Engine.Result result, double ms) {
    }

    /** Runs each call on a worker thread so a call that never returns cannot stall the whole run. */
    static final class Harness implements AutoCloseable {
        private ExecutorService worker = newWorker();

        private static ExecutorService newWorker() {
            return Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "planner-head-to-head");
                thread.setDaemon(true);
                return thread;
            });
        }

        Timed time(Engine.Prepared run) {
            Future<Timed> future = worker.submit(() -> {
                long started = System.nanoTime();
                Engine.Result result;
                try {
                    result = run.run();
                } catch (StackOverflowError overflow) {
                    result = Engine.Result.failed(Engine.Status.ERROR, "StackOverflowError");
                } catch (RuntimeException failure) {
                    result = Engine.Result.failed(Engine.Status.ERROR, failure.getClass().getSimpleName()
                            + ": " + failure.getMessage());
                }
                return new Timed(result, (System.nanoTime() - started) / 1_000_000.0);
            });
            try {
                return future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException expired) {
                future.cancel(true);
                // The stuck thread cannot be killed; abandon it and keep measuring on a fresh one.
                worker.shutdownNow();
                worker = newWorker();
                return new Timed(Engine.Result.failed(Engine.Status.TIMEOUT, "no answer in "
                        + CALL_TIMEOUT_SECONDS + " s"), CALL_TIMEOUT_SECONDS * 1000.0);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } catch (ExecutionException failed) {
                return new Timed(Engine.Result.failed(Engine.Status.ERROR, String.valueOf(failed.getCause())), 0);
            }
        }

        @Override
        public void close() {
            worker.shutdownNow();
        }
    }

    static String generatedAt() {
        return Instant.now().toString();
    }

    private PlannerHeadToHead() {
    }
}
