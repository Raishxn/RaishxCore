package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import appeng.api.networking.crafting.ICraftingPlan;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * AE2 polls {@code isDone()} and blocks in {@code get()} on its own planner's futures, so the future
 * handed over for a capture that spans ticks must behave exactly like one that is already running.
 */
class DeferredPlanFutureTest {
    @Test
    void reportsDoneOnlyAfterThePlanFutureItselfIsDone() {
        var deferred = new DeferredPlanFuture();
        var plan = new CompletableFuture<ICraftingPlan>();

        assertFalse(deferred.isDone(), "a capture that is still running cannot report done");
        deferred.complete(plan);
        assertFalse(deferred.isDone(), "the plan must exist before the request reports done");

        plan.complete(null);

        assertTrue(deferred.isDone());
    }

    @Test
    void returnsThePlanProducedByTheDelegate() throws Exception {
        var deferred = new DeferredPlanFuture();
        var plan = new CompletableFuture<ICraftingPlan>();
        deferred.complete(plan);
        plan.complete(null);

        assertNull(deferred.get());
        assertNull(deferred.get(1, TimeUnit.SECONDS));
    }

    @Test
    void timesOutInsteadOfBlockingForeverWhenTheCaptureNeverFinishes() {
        var deferred = new DeferredPlanFuture();

        assertThrows(TimeoutException.class, () -> deferred.get(1, TimeUnit.MILLISECONDS));
    }

    @Test
    void timedGetUsesOneDeadlineForTheHandoffAndThePlan() {
        var deferred = new DeferredPlanFuture();
        deferred.complete(new CompletableFuture<>());

        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertThrows(TimeoutException.class, () -> deferred.get(10, TimeUnit.MILLISECONDS)));
    }

    @Test
    void untimedGetCanBeInterruptedWhileWaitingForTheHandoff() throws Exception {
        var deferred = new DeferredPlanFuture();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread waiter = new Thread(() -> {
            try {
                deferred.get();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "deferred-plan-future-test");

        waiter.start();
        waiter.interrupt();
        waiter.join(1_000L);
        if (waiter.isAlive()) deferred.abandon();

        assertFalse(waiter.isAlive(), "get() ignored interruption while waiting for its delegate");
        assertTrue(failure.get() instanceof InterruptedException,
                "get() must expose InterruptedException, got " + failure.get());
    }

    @Test
    void cancellingBeforeThePlanExistsMarksTheRequestAbandoned() {
        var deferred = new DeferredPlanFuture();

        assertTrue(deferred.cancel(true));

        assertTrue(deferred.isCancelled());
        assertTrue(deferred.abandoned(), "the bridge must be able to stop feeding an abandoned request");
        assertTrue(deferred.isDone());
        assertThrows(CancellationException.class, deferred::get);
    }

    @Test
    void cancellingAfterThePlanExistsCancelsTheRunningCalculation() {
        var deferred = new DeferredPlanFuture();
        var plan = new CompletableFuture<ICraftingPlan>();
        deferred.complete(plan);

        assertTrue(deferred.cancel(true));

        assertTrue(plan.isCancelled(), "the worker calculation must be cancelled too");
        assertTrue(deferred.isCancelled());
    }

    @Test
    void abandoningIsAvailableForInvalidationsThatAreNotCallerCancellations() {
        var deferred = new DeferredPlanFuture();
        var plan = new CompletableFuture<ICraftingPlan>();
        deferred.complete(plan);

        deferred.abandon();

        assertTrue(deferred.abandoned());
        assertTrue(deferred.isDone());
        assertTrue(plan.isCancelled(), "a discarded attempt must not keep a calculation running");
    }

    @Test
    void onlyTheFirstCompletionIsPublished() throws Exception {
        var deferred = new DeferredPlanFuture();
        var first = new CompletableFuture<ICraftingPlan>();
        var second = new CompletableFuture<ICraftingPlan>();

        deferred.complete(first);
        deferred.complete(second);
        first.complete(null);

        assertTrue(deferred.isDone());
        assertNull(deferred.get());
        assertFalse(second.isDone(), "a second completion must be ignored");
    }

    @Test
    void abandoningTwiceIsHarmless() {
        var deferred = new DeferredPlanFuture();

        deferred.abandon();
        deferred.abandon();

        assertTrue(deferred.abandoned());
    }
}
