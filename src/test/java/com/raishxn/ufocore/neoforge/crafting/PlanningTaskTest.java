package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PlanningTaskTest {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private final AtomicInteger reports = new AtomicInteger();

    @AfterEach
    void closeWorker() throws InterruptedException {
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS), "worker did not terminate");
    }

    private <T> Future<T> submit(Callable<T> calculation) {
        return worker.submit(PlanningTask.classified(calculation, status::set, failure -> reports.incrementAndGet()));
    }

    @Test
    void successKeepsCalculationStatusAndRunsOnWorker() throws Exception {
        Thread caller = Thread.currentThread();
        Object plan = new Object();
        assertSame(plan, submit(() -> {
            assertNotSame(caller, Thread.currentThread());
            status.set("COMPLETE");
            return plan;
        }).get(5, TimeUnit.SECONDS));
        assertEquals("COMPLETE", status.get());
        assertEquals(0, reports.get());
    }

    @Test
    void deadlineIsReportedAndPreservedInFailedFuture() {
        var deadline = new TimeoutException("deterministic deadline");
        var future = submit(() -> { throw deadline; });
        var failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertSame(deadline, failure.getCause().getCause());
        assertEquals("RaishxCore planning deadline exceeded", failure.getCause().getMessage());
        assertEquals("timeout", status.get());
        assertEquals(0, reports.get());
    }

    @Test
    void cooperativeCancellationFailsTheFutureWithoutRetry() {
        var cancelled = new CancellationException("deterministic cancellation");
        var calculations = new AtomicInteger();
        var future = submit(() -> { calculations.incrementAndGet(); throw cancelled; });
        var failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertSame(cancelled, failure.getCause());
        assertEquals("cancelled", status.get());
        assertEquals(1, calculations.get());
        assertEquals(0, reports.get());
    }

    @Test
    void unexpectedFailureIsReportedOnceWithOriginalCause() {
        var unexpected = new IllegalArgumentException("deterministic failure");
        var calls = new AtomicInteger();
        var future = worker.submit(PlanningTask.classified(() -> {
            calls.incrementAndGet(); throw unexpected;
        }, status::set, failure -> { assertSame(unexpected, failure); reports.incrementAndGet(); }));
        var failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertSame(unexpected, failure.getCause());
        assertEquals("failed: " + unexpected, status.get());
        assertEquals(1, reports.get());
        assertEquals(1, calls.get());
    }

    @Test
    void cancellingQueuedFutureDoesNotInventWorkerDiagnosis() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        worker.submit(() -> { started.countDown(); release.await(); return null; });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var calls = new AtomicInteger();
        var future = submit(calls::incrementAndGet);
        assertTrue(future.cancel(true));
        assertThrows(CancellationException.class, future::get);
        release.countDown();
        worker.submit(() -> null).get(5, TimeUnit.SECONDS);
        assertEquals(0, calls.get());
        assertEquals("idle", status.get());
        assertEquals(0, reports.get());
    }

    @Test
    void cancellingRunningFutureInterruptsAndRecordsCooperativeStop() throws Exception {
        var started = new CountDownLatch(1);
        var classified = new CountDownLatch(1);
        var future = worker.submit(PlanningTask.classified(() -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("worker interrupted");
            }
            return null;
        }, value -> { status.set(value); classified.countDown(); }, failure -> reports.incrementAndGet()));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(future.cancel(true));
        assertThrows(CancellationException.class, future::get);
        assertTrue(classified.await(5, TimeUnit.SECONDS));
        assertEquals("cancelled", status.get());
        assertEquals(0, reports.get());
    }
}
