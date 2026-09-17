package com.raishxn.ufocore.api.crafting.planner;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, technology-neutral crafting pattern used by the Core planner. */
public final class CraftingPattern<K> {
    private final String id;
    private final int priority;
    private final Map<K, UfoAmount> inputs;
    private final Map<K, UfoAmount> reusableInputs;
    private final Map<K, Integer> durableUses;
    private final Map<K, UfoAmount> outputs;
    private final Set<K> craftableOutputs;

    public CraftingPattern(String id, int priority, Map<K, UfoAmount> inputs, Map<K, UfoAmount> outputs) {
        this(id, priority, inputs, outputs, outputs.keySet());
    }

    public CraftingPattern(String id, int priority, Map<K, UfoAmount> inputs, Map<K, UfoAmount> outputs,
                            Set<K> craftableOutputs) {
        this(id, priority, inputs, Map.of(), Map.of(), outputs, craftableOutputs);
    }

    /**
     * @param reusableInputs catalysts the pattern must have on hand but hands back after every
     *                       execution: the amount is a seed that is never consumed, so it is required
     *                       once for the whole plan rather than once per run
     */
    public CraftingPattern(String id, int priority, Map<K, UfoAmount> inputs,
                            Map<K, UfoAmount> reusableInputs, Map<K, UfoAmount> outputs,
                            Set<K> craftableOutputs) {
        this(id, priority, inputs, reusableInputs, Map.of(), outputs, craftableOutputs);
    }

    /**
     * @param durableUses carriers among {@code inputs} that survive a limited number of executions:
     *                    a run needs one carrier per {@code uses} firings, so a batch of {@code n}
     *                    firings consumes {@code ceil(n / uses)} of them
     */
    public CraftingPattern(String id, int priority, Map<K, UfoAmount> inputs,
                            Map<K, UfoAmount> reusableInputs, Map<K, Integer> durableUses,
                            Map<K, UfoAmount> outputs, Set<K> craftableOutputs) {
        this.id = Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("pattern id must not be blank");
        this.priority = priority;
        this.inputs = copyAmounts(inputs, "input");
        this.reusableInputs = copyAmounts(reusableInputs, "reusable input");
        for (K key : this.reusableInputs.keySet()) {
            if (this.inputs.containsKey(key)) {
                throw new IllegalArgumentException("input cannot be both consumed and reusable: " + key);
            }
        }
        LinkedHashMap<K, Integer> uses = new LinkedHashMap<>();
        Objects.requireNonNull(durableUses, "durableUses").forEach((key, value) -> {
            Objects.requireNonNull(key, "durable input key");
            if (!this.inputs.containsKey(key)) {
                throw new IllegalArgumentException("durable input must be a consumed input: " + key);
            }
            if (value == null || value < 1) {
                throw new IllegalArgumentException("durable input uses must be positive: " + key);
            }
            uses.put(key, value);
        });
        this.durableUses = Collections.unmodifiableMap(uses);
        this.outputs = copyAmounts(outputs, "output");
        if (this.outputs.isEmpty()) throw new IllegalArgumentException("pattern must have an output");
        this.craftableOutputs = Set.copyOf(craftableOutputs);
        if (this.craftableOutputs.isEmpty() || !this.outputs.keySet().containsAll(this.craftableOutputs)) {
            throw new IllegalArgumentException("craftable outputs must be a nonempty subset of outputs");
        }
    }

    public CraftingPattern(String id, Map<K, UfoAmount> inputs, Map<K, UfoAmount> outputs) {
        this(id, 0, inputs, outputs);
    }

    public String id() { return id; }
    public int priority() { return priority; }
    public Map<K, UfoAmount> inputs() { return inputs; }
    /** Catalysts that must be present but are returned, so they are never consumed. */
    public Map<K, UfoAmount> reusableInputs() { return reusableInputs; }
    /** Firings one carrier of a consumed input survives; absent means it is consumed outright. */
    public Map<K, Integer> durableUses() { return durableUses; }
    public Map<K, UfoAmount> outputs() { return outputs; }
    /** Outputs selectable as a crafting route; other outputs remain usable byproducts. */
    public Set<K> craftableOutputs() { return craftableOutputs; }

    private static <K> Map<K, UfoAmount> copyAmounts(Map<K, UfoAmount> source, String kind) {
        Objects.requireNonNull(source, kind + "s");
        LinkedHashMap<K, UfoAmount> copy = new LinkedHashMap<>();
        source.forEach((key, amount) -> {
            Objects.requireNonNull(key, kind + " key");
            Objects.requireNonNull(amount, kind + " amount");
            if (amount.isZero()) throw new IllegalArgumentException(kind + " amount must be positive");
            if (copy.put(key, amount) != null) throw new IllegalArgumentException("duplicate " + kind + " key");
        });
        return Collections.unmodifiableMap(copy);
    }

    @Override public boolean equals(Object object) {
        return object instanceof CraftingPattern<?> other && id.equals(other.id) && priority == other.priority
                && inputs.equals(other.inputs) && reusableInputs.equals(other.reusableInputs)
                && durableUses.equals(other.durableUses) && outputs.equals(other.outputs)
                && craftableOutputs.equals(other.craftableOutputs);
    }

    @Override public int hashCode() {
        return Objects.hash(id, priority, inputs, reusableInputs, durableUses, outputs, craftableOutputs);
    }
    @Override public String toString() { return id; }
}
