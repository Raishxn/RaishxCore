package com.raishxn.ufocore.api.crafting.planner.differential;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs one capability case through an engine's production path under a hard deadline.
 *
 * <p>A plan is never counted as valid without replay. A diagnostic forced execution after a rejected
 * admission is reported as {@link CapabilityClassification#FALSE_NEGATIVE}; it never becomes
 * support. An engine that ignores interruption is reported separately from a cooperative timeout.
 */
public final class CapabilityRunner {
    private static final Duration DEFAULT_CANCELLATION_OBSERVATION = Duration.ofMillis(500);

    private final Duration deadline;
    private final Duration cancellationObservation;

    /**
     * @param deadline                hard limit shared by the admission check and the planning pass;
     *                                a reported-shortage refill starts a fresh pass with its own limit
     * @param cancellationObservation diagnostic-only grace period used after the hard timeout to
     *                                distinguish a cooperative stop from a stuck worker
     */
    public CapabilityRunner(Duration deadline, Duration cancellationObservation) {
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(cancellationObservation, "cancellationObservation");
        if (deadline.isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("deadline must be positive");
        }
        if (cancellationObservation.isNegative() || cancellationObservation.isZero()) {
            throw new IllegalArgumentException("cancellation observation must be positive");
        }
        this.deadline = deadline;
        this.cancellationObservation = cancellationObservation;
    }

    public CapabilityRunner(Duration deadline) {
        this(deadline, DEFAULT_CANCELLATION_OBSERVATION);
    }

    public CapabilityRun run(CapabilityPlanner planner, CapabilityScenario scenario) {
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(scenario, "scenario");
        long started = System.nanoTime();

        Invocation<CapabilityPlanner.Check> admission = invoke(() -> planner.check(scenario), started);
        if (admission.status != InvocationStatus.COMPLETED) {
            return failure(scenario, admission, started);
        }
        if (!admission.value.accepted()) {
            return forcedDiagnostic(planner, scenario, admission.value.reason(), started);
        }

        Invocation<CapabilityPlanner.Outcome> attempted = invoke(() -> planner.plan(scenario), started);
        if (attempted.status != InvocationStatus.COMPLETED) {
            return failure(scenario, attempted, started);
        }
        CapabilityPlanner.Outcome outcome = attempted.value;
        if (outcome.kind() == CapabilityPlanner.Outcome.Kind.DECLINED) {
            return build(scenario, CapabilityClassification.ATTEMPT_DECLINED, null, null, null, null,
                    null, outcome.reason(), Double.NaN, started);
        }
        if (outcome.kind() == CapabilityPlanner.Outcome.Kind.TIMED_OUT) {
            return build(scenario, CapabilityClassification.ENGINE_TIMEOUT, null, null, null, null,
                    null, outcome.reason(), Double.NaN, started);
        }
        CapabilityPlan plan = outcome.plan();
        if (plan == null) {
            return build(scenario, CapabilityClassification.ATTEMPT_DECLINED, null, null, null, null,
                    null, "engine reported a plan without content", Double.NaN, started);
        }
        if (!CapabilityPlanReplay.isReplayable(scenario.graph())) {
            // An unreplayable plan is never counted as support, even when the engine claims success.
            return build(scenario, CapabilityClassification.ATTEMPT_DECLINED, plan, null, null, null,
                    null, "plan cannot be replayed by the common oracle", Double.NaN, started);
        }
        if (!plan.target().equals(scenario.target()) || !plan.requested().equals(scenario.amount())) {
            return build(scenario, CapabilityClassification.FALSE_POSITIVE, plan, null, null, null,
                    null, "plan answers a different request", Double.NaN, started);
        }
        return scenario.expectedFeasible()
                ? feasible(scenario, plan, started)
                : infeasible(planner, scenario, plan, started);
    }

    private CapabilityRun feasible(CapabilityScenario scenario, CapabilityPlan plan, long started) {
        if (!plan.complete()) {
            CapabilityPlanReplay.Report report =
                    CapabilityPlanReplay.replay(scenario.graph(), plan, true);
            return build(scenario, CapabilityClassification.FALSE_NEGATIVE, plan, null, report, null,
                    null, "reported shortage " + plan.missing() + " on a feasible scenario",
                    Double.NaN, started);
        }
        CapabilityPlanReplay.Report report =
                CapabilityPlanReplay.replay(scenario.graph(), plan, false);
        if (!report.valid()) {
            return build(scenario, CapabilityClassification.FALSE_POSITIVE, plan, null, report, null,
                    null, report.summary(), Double.NaN, started);
        }
        return build(scenario, CapabilityClassification.SUPPORTED, plan, null, report, null, null,
                report.summary(), Double.NaN, started);
    }

    private CapabilityRun infeasible(CapabilityPlanner planner, CapabilityScenario scenario,
                                     CapabilityPlan plan, long started) {
        if (plan.complete()) {
            CapabilityPlanReplay.Report report =
                    CapabilityPlanReplay.replay(scenario.graph(), plan, false);
            if (report.valid()) {
                return build(scenario, CapabilityClassification.SUPPORTED, plan, null, report, null,
                        null, "corpus expected infeasibility, the replayed plan is complete",
                        Double.NaN, started);
            }
            return build(scenario, CapabilityClassification.FALSE_POSITIVE, plan, null, report, null,
                    null, report.summary(), Double.NaN, started);
        }

        CapabilityPlanReplay.Report report =
                CapabilityPlanReplay.replay(scenario.graph(), plan, true);
        if (!report.valid()) {
            // Wrong shortage data is never acceptable, even for a scenario we know is infeasible.
            return build(scenario, CapabilityClassification.FALSE_POSITIVE, plan, null, report, null,
                    null, report.summary(), Double.NaN, started);
        }

        double overhead = scenario.missingOverhead(plan.missing());
        CapabilityScenario refilled = scenario.refilled(plan.missing());
        long refillStarted = System.nanoTime();
        Invocation<CapabilityPlanner.Check> admission = invoke(() -> planner.check(refilled), refillStarted);
        if (admission.status != InvocationStatus.COMPLETED) {
            return failure(scenario, admission, started);
        }
        if (!admission.value.accepted()) {
            return build(scenario, CapabilityClassification.ATTEMPT_DECLINED, plan, null, report, null,
                    null, "reported shortage does not unlock the case: " + admission.value.reason(),
                    overhead, started);
        }
        Invocation<CapabilityPlanner.Outcome> refillAttempt =
                invoke(() -> planner.plan(refilled), refillStarted);
        if (refillAttempt.status != InvocationStatus.COMPLETED) {
            return failure(scenario, refillAttempt, started);
        }
        CapabilityPlanner.Outcome refillOutcome = refillAttempt.value;
        if (refillOutcome.kind() != CapabilityPlanner.Outcome.Kind.PLANNED
                || refillOutcome.plan() == null || !refillOutcome.plan().complete()) {
            return build(scenario, CapabilityClassification.ATTEMPT_DECLINED, plan, refillOutcome.plan(),
                    report, null, null, "supplying the reported shortage still declines: "
                            + refillOutcome.reason(), overhead, started);
        }
        CapabilityPlanReplay.Report refillReport =
                CapabilityPlanReplay.replay(refilled.graph(), refillOutcome.plan(), false);
        if (!refillReport.valid()) {
            return build(scenario, CapabilityClassification.FALSE_POSITIVE, plan, refillOutcome.plan(),
                    report, refillReport, null, refillReport.summary(), overhead, started);
        }
        return build(scenario, CapabilityClassification.SUPPORTED, plan, refillOutcome.plan(), report,
                refillReport, null, report.summary() + "; refill " + refillReport.summary(),
                overhead, started);
    }

    private CapabilityRun forcedDiagnostic(CapabilityPlanner planner, CapabilityScenario scenario,
                                          String admissionReason, long started) {
        Invocation<CapabilityPlanner.Outcome> forced = invoke(() -> planner.plan(scenario), started);
        if (forced.status == InvocationStatus.COMPLETED && forced.value != null
                && forced.value.kind() == CapabilityPlanner.Outcome.Kind.PLANNED
                && forced.value.plan() != null && forced.value.plan().complete()
                && CapabilityPlanReplay.isReplayable(scenario.graph())) {
            CapabilityPlanReplay.Report report =
                    CapabilityPlanReplay.replay(scenario.graph(), forced.value.plan(), false);
            if (report.valid()) {
                return build(scenario, CapabilityClassification.FALSE_NEGATIVE, forced.value.plan(),
                        null, report, null, null,
                        "admission rejected a case the production path can replay", Double.NaN, started);
            }
        }
        return build(scenario, CapabilityClassification.CHECK_REJECTED, null, null, null, null, null,
                admissionReason, Double.NaN, started);
    }

    private CapabilityRun failure(CapabilityScenario scenario, Invocation<?> invocation, long started) {
        CapabilityClassification classification = switch (invocation.status) {
            case ERROR -> CapabilityClassification.ENGINE_ERROR;
            case TIMEOUT -> CapabilityClassification.ENGINE_TIMEOUT;
            case NON_COOPERATIVE_TIMEOUT -> CapabilityClassification.NON_COOPERATIVE_TIMEOUT;
            case COMPLETED -> throw new IllegalStateException("a completed invocation is not a failure");
        };
        return build(scenario, classification, null, null, null, null, invocation.failure,
                describe(invocation.failure), Double.NaN, started);
    }

    private static String describe(Throwable failure) {
        if (failure == null) {
            return "no detail";
        }
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private CapabilityRun build(CapabilityScenario scenario, CapabilityClassification classification,
                                CapabilityPlan plan, CapabilityPlan refillPlan,
                                CapabilityPlanReplay.Report report,
                                CapabilityPlanReplay.Report refillReport, Throwable failure,
                                String diagnostic, double missingOverhead, long started) {
        return new CapabilityRun(scenario, classification, Math.max(0L, System.nanoTime() - started),
                missingOverhead, plan, refillPlan, report, refillReport, failure, diagnostic);
    }

    private <T> Invocation<T> invoke(ThrowingSupplier<T> operation, long runStarted) {
        long elapsed = Math.max(0L, System.nanoTime() - runStarted);
        long budget = deadline.toNanos();
        long remaining = budget - Math.min(budget, elapsed);
        if (remaining <= 0L) {
            return Invocation.timeout(new TimeoutException("capability case exceeded its shared deadline"));
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread worker = Thread.ofPlatform()
                .daemon(true)
                .name("raishxcore-capability-reference")
                .unstarted(() -> {
                    try {
                        future.complete(operation.get());
                    } catch (Throwable thrown) {
                        future.completeExceptionally(thrown);
                    }
                });
        worker.start();
        try {
            return Invocation.completed(future.get(remaining, TimeUnit.NANOSECONDS));
        } catch (TimeoutException timeout) {
            worker.interrupt();
            try {
                long grace = cancellationObservation.toNanos();
                worker.join(grace / 1_000_000L, (int) (grace % 1_000_000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Invocation.error(interrupted);
            }
            return worker.isAlive()
                    ? Invocation.nonCooperativeTimeout(timeout)
                    : Invocation.timeout(timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Invocation.error(interrupted);
        } catch (ExecutionException failed) {
            return Invocation.error(failed.getCause());
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private enum InvocationStatus {
        COMPLETED,
        ERROR,
        TIMEOUT,
        NON_COOPERATIVE_TIMEOUT
    }

    private record Invocation<T>(InvocationStatus status, T value, Throwable failure) {
        static <T> Invocation<T> completed(T value) {
            return new Invocation<>(InvocationStatus.COMPLETED, value, null);
        }

        static <T> Invocation<T> error(Throwable failure) {
            return new Invocation<>(InvocationStatus.ERROR, null, failure);
        }

        static <T> Invocation<T> timeout(Throwable failure) {
            return new Invocation<>(InvocationStatus.TIMEOUT, null, failure);
        }

        static <T> Invocation<T> nonCooperativeTimeout(Throwable failure) {
            return new Invocation<>(InvocationStatus.NON_COOPERATIVE_TIMEOUT, null, failure);
        }
    }
}
