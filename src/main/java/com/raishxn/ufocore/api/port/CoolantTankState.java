package com.raishxn.ufocore.api.port;

import java.util.Objects;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/** Technology-neutral state for a persistent, single-fluid filtered tank. */
public final class CoolantTankState<K> {
    private final int capacity;
    private final Predicate<K> validator;
    private @Nullable K coolant;
    private int amount;

    public CoolantTankState(int capacity, Predicate<K> validator) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public int capacity() {
        return capacity;
    }

    public @Nullable K coolant() {
        return coolant;
    }

    public int amount() {
        return amount;
    }

    public boolean isValid(@Nullable K candidate) {
        return candidate != null && validator.test(candidate);
    }

    public int fill(@Nullable K candidate, int requested, boolean simulate) {
        if (!isValid(candidate) || requested <= 0 || (coolant != null && !coolant.equals(candidate))) return 0;
        int accepted = Math.min(requested, capacity - amount);
        if (!simulate && accepted > 0) {
            coolant = candidate;
            amount += accepted;
        }
        return accepted;
    }

    public long extract(@Nullable K candidate, long requested, boolean simulate) {
        if (candidate == null || requested <= 0L || !candidate.equals(coolant)) return 0L;
        int extracted = (int) Math.min(Math.min(requested, Integer.MAX_VALUE), amount);
        if (!simulate && extracted > 0) {
            amount -= extracted;
            if (amount == 0) coolant = null;
        }
        return extracted;
    }

    public void restore(@Nullable K candidate, int storedAmount) {
        if (!isValid(candidate) || storedAmount <= 0) {
            coolant = null;
            amount = 0;
            return;
        }
        coolant = candidate;
        amount = Math.min(storedAmount, capacity);
    }
}
