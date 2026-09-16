package com.raishxn.ufocore.neoforge.crafting;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shares equivalent in-flight calculations without sharing cancellation between
 * their callers. The owner can still cancel every worker when its source data
 * becomes obsolete.
 */
final class InFlightRequestCoordinator<K, V> {
    private final Executor executor;
    private final ConcurrentHashMap<K, SharedTask> tasks = new ConcurrentHashMap<>();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong deduplicated = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();

    InFlightRequestCoordinator(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    Future<V> submit(K key, Callable<V> calculation) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(calculation, "calculation");
        while (true) {
            SharedTask existing = tasks.get(key);
            if (existing != null) {
                if (!existing.isDone()) {
                    deduplicated.incrementAndGet();
                    return existing.newView();
                }
                tasks.remove(key, existing);
                continue;
            }

            SharedTask created = new SharedTask(key, calculation);
            if (tasks.putIfAbsent(key, created) != null) continue;
            try {
                executor.execute(created.worker);
                submitted.incrementAndGet();
                return created.newView();
            } catch (RuntimeException rejected) {
                tasks.remove(key, created);
                created.fail(rejected);
                throw rejected;
            }
        }
    }

    void cancelAll() {
        for (SharedTask task : tasks.values()) {
            if (task.worker.cancel(true)) cancelled.incrementAndGet();
        }
    }

    Stats stats() {
        return new Stats(tasks.size(), submitted.get(), deduplicated.get(), cancelled.get());
    }

    record Stats(int inFlight, long submitted, long deduplicated, long cancelled) {}

    private final class SharedTask {
        private final K key;
        private final CompletableFuture<V> result = new CompletableFuture<>();
        private final FutureTask<V> worker;

        private SharedTask(K key, Callable<V> calculation) {
            this.key = key;
            this.worker = new FutureTask<>(calculation) {
                @Override protected void done() {
                    publish();
                }
            };
        }

        private boolean isDone() {
            return worker.isDone();
        }

        /** A caller may cancel this view without cancelling another caller's plan. */
        private Future<V> newView() {
            return result.thenApply(value -> value);
        }

        private void fail(RuntimeException failure) {
            result.completeExceptionally(failure);
        }

        private void publish() {
            try {
                result.complete(worker.get());
            } catch (CancellationException stopped) {
                result.cancel(false);
            } catch (ExecutionException failure) {
                result.completeExceptionally(failure.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                result.completeExceptionally(interrupted);
            } finally {
                tasks.remove(key, this);
            }
        }
    }
}
