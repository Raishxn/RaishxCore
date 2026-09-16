package com.raishxn.ufocore.neoforge.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InFlightRequestCoordinatorTest {
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final InFlightRequestCoordinator<String, Integer> coordinator =
            new InFlightRequestCoordinator<>(executor);

    @AfterEach
    void closeExecutor() throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void equivalentRequestsShareOneCalculationAndReturnIndependentViews() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calculations = new AtomicInteger();
        var first = coordinator.submit("same", () -> {
            calculations.incrementAndGet();
            started.countDown();
            release.await();
            return 42;
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var second = coordinator.submit("same", () -> 99);

        assertNotSame(first, second);
        assertTrue(first.cancel(false));
        assertThrows(CancellationException.class, first::get);
        release.countDown();
        assertEquals(42, second.get(5, TimeUnit.SECONDS));
        assertEquals(1, calculations.get());
        assertEquals(new InFlightRequestCoordinator.Stats(0, 1, 1, 0), coordinator.stats());
    }

    @Test
    void differentKeysRunIndependently() throws Exception {
        var calculations = new AtomicInteger();
        var first = coordinator.submit("first", calculations::incrementAndGet);
        var second = coordinator.submit("second", calculations::incrementAndGet);
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertEquals(2, calculations.get());
        assertEquals(2, coordinator.stats().submitted());
        assertEquals(0, coordinator.stats().deduplicated());
    }

    @Test
    void ownerCancellationInterruptsEveryObsoleteCalculation() throws Exception {
        var started = new CountDownLatch(2);
        var interrupted = new CountDownLatch(2);
        for (String key : new String[]{"first", "second"}) {
            coordinator.submit(key, () -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                    return 1;
                } catch (InterruptedException stopped) {
                    interrupted.countDown();
                    throw stopped;
                }
            });
        }
        assertTrue(started.await(5, TimeUnit.SECONDS));

        coordinator.cancelAll();

        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertEquals(2, coordinator.stats().cancelled());
    }

    @Test
    void cancellingOneOwnerPreservesSharedWorkForAnotherOwner() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = coordinator.submit("same", "first-owner", () -> {
            started.countDown();
            release.await();
            return 7;
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var second = coordinator.submit("same", "second-owner", () -> 99);

        assertEquals(1, coordinator.cancelOwner("first-owner"));
        assertThrows(CancellationException.class, first::get);
        release.countDown();
        assertEquals(7, second.get(5, TimeUnit.SECONDS));
        assertEquals(0, coordinator.stats().cancelled());
    }

    @Test
    void cancellingTheLastOwnerInterruptsTheSharedWorker() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var future = coordinator.submit("only", "player", () -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return 1;
            } catch (InterruptedException stopped) {
                interrupted.countDown();
                throw stopped;
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertEquals(1, coordinator.cancelOwner("player"));

        assertThrows(CancellationException.class, future::get);
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertEquals(1, coordinator.stats().cancelled());
    }

    @Test
    void rejectedExecutionDoesNotLeaveAPhantomInFlightRequest() {
        var rejecting = new InFlightRequestCoordinator<String, Integer>(command -> {
            throw new RejectedExecutionException("full");
        });

        assertThrows(RejectedExecutionException.class, () -> rejecting.submit("request", () -> 1));
        assertEquals(new InFlightRequestCoordinator.Stats(0, 0, 0, 0), rejecting.stats());
    }

    @Test
    void perGridLimitRejectsDistinctWorkButStillDeduplicatesEquivalentWork() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = coordinator.submit("same", "first", () -> {
            started.countDown();
            release.await();
            return 7;
        }, 1);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        var duplicate = coordinator.submit("same", "second", () -> 99, 1);
        assertThrows(InFlightRequestCoordinator.PerGridLimitExceededException.class,
                () -> coordinator.submit("different", "third", () -> 11, 1));

        release.countDown();
        assertEquals(7, first.get(5, TimeUnit.SECONDS));
        assertEquals(7, duplicate.get(5, TimeUnit.SECONDS));
        assertEquals(new InFlightRequestCoordinator.Stats(0, 1, 1, 0), coordinator.stats());
    }

    @Test
    void concurrentSubmissionsCannotOversubscribePerGridLimit() throws Exception {
        var gate = new CountDownLatch(1);
        var callers = Executors.newFixedThreadPool(8);
        try {
            var accepted = new AtomicInteger();
            var rejected = new AtomicInteger();
            var futures = new java.util.concurrent.CopyOnWriteArrayList<Future<Integer>>();
            var submissions = new java.util.ArrayList<Future<?>>();
            for (int index = 0; index < 32; index++) {
                String key = "request-" + index;
                submissions.add(callers.submit(() -> {
                    try {
                        futures.add(coordinator.submit(key, key, () -> { gate.await(); return 1; }, 2));
                        accepted.incrementAndGet();
                    } catch (InFlightRequestCoordinator.PerGridLimitExceededException expected) {
                        rejected.incrementAndGet();
                    }
                }));
            }
            for (Future<?> submission : submissions) submission.get(5, TimeUnit.SECONDS);
            assertEquals(2, accepted.get());
            assertEquals(30, rejected.get());
            assertEquals(2, coordinator.stats().inFlight());
            gate.countDown();
            for (Future<Integer> future : futures) assertEquals(1, future.get(5, TimeUnit.SECONDS));
        } finally {
            gate.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
