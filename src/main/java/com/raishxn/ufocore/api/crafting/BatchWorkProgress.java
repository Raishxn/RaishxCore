package com.raishxn.ufocore.api.crafting;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.Objects;

/**
 * Persistent-friendly progress for work that may exceed the range accepted by a downstream API.
 * Callers take one {@link #nextBatch(long)} at a time and only advance after that batch commits.
 */
public final class BatchWorkProgress {
    private final UfoAmount total;
    private UfoAmount completed;

    public BatchWorkProgress(UfoAmount total) {
        this(total, UfoAmount.ZERO);
    }

    public BatchWorkProgress(UfoAmount total, UfoAmount completed) {
        this.total = Objects.requireNonNull(total, "total");
        this.completed = Objects.requireNonNull(completed, "completed");
        if (completed.compareTo(total) > 0) {
            throw new IllegalArgumentException("completed work exceeds total work");
        }
    }

    public UfoAmount total() {
        return total;
    }

    public UfoAmount completed() {
        return completed;
    }

    public UfoAmount remaining() {
        return total.subtract(completed);
    }

    public boolean isComplete() {
        return completed.equals(total);
    }

    /**
     * Returns an API-safe batch size. It never exceeds the remaining work or {@code maxBatchSize}.
     */
    public long nextBatch(long maxBatchSize) {
        if (maxBatchSize < 1L) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        return remaining().min(UfoAmount.of(maxBatchSize)).longValueExact();
    }

    /** Records only work that was successfully committed by the downstream system. */
    public void advance(long committedWork) {
        if (committedWork < 0L) {
            throw new IllegalArgumentException("committedWork must be non-negative");
        }
        advance(UfoAmount.of(committedWork));
    }

    public void advance(UfoAmount committedWork) {
        Objects.requireNonNull(committedWork, "committedWork");
        if (committedWork.compareTo(remaining()) > 0) {
            throw new IllegalArgumentException("committed work exceeds remaining work");
        }
        completed = completed.add(committedWork);
    }
}
