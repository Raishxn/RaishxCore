package com.raishxn.ufocore.api.crafting.planner.differential;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Engine-neutral view of one produced plan.
 *
 * <p>Every quantity is an exact {@link UfoAmount}; no adapter may narrow the neutral model to
 * {@code long}. Pattern executions are keyed by pattern id so two engines and two runs can be
 * compared without sharing class identity.
 */
public record CapabilityPlan(String target, UfoAmount requested,
                             Map<String, UfoAmount> executions,
                             Map<String, UfoAmount> extractedFromInventory,
                             Map<String, UfoAmount> missing,
                             Map<String, UfoAmount> remaining,
                             List<Step> schedule,
                             boolean complete) {

    /** One ordered scheduling step: how many times a pattern runs at that point. */
    public record Step(String patternId, UfoAmount runs) {
        public Step {
            Objects.requireNonNull(patternId, "patternId");
            Objects.requireNonNull(runs, "runs");
            if (runs.isZero()) throw new IllegalArgumentException("scheduled runs must be positive");
        }
    }

    public CapabilityPlan {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(requested, "requested");
        executions = freeze(executions);
        extractedFromInventory = freeze(extractedFromInventory);
        missing = freeze(missing);
        remaining = freeze(remaining);
        schedule = List.copyOf(Objects.requireNonNull(schedule, "schedule"));
        if (complete != missing.isEmpty()) {
            throw new IllegalArgumentException("complete must equal an empty missing set");
        }
    }

    private static Map<String, UfoAmount> freeze(Map<String, UfoAmount> source) {
        Objects.requireNonNull(source, "amounts");
        ArrayList<String> keys = new ArrayList<>(source.keySet());
        Collections.sort(keys);
        LinkedHashMap<String, UfoAmount> copy = new LinkedHashMap<>();
        for (String key : keys) {
            UfoAmount amount = Objects.requireNonNull(source.get(key), "amount");
            if (!amount.isZero()) {
                copy.put(key, amount);
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    /** Semantic equality used for determinism: schedule, executions, shortage and leftovers. */
    public boolean sameSemantics(CapabilityPlan other) {
        return other != null
                && target.equals(other.target)
                && requested.equals(other.requested)
                && complete == other.complete
                && executions.equals(other.executions)
                && extractedFromInventory.equals(other.extractedFromInventory)
                && missing.equals(other.missing)
                && remaining.equals(other.remaining)
                && schedule.equals(other.schedule);
    }

    /** Total number of pattern firings the plan declares. */
    public UfoAmount totalExecutions() {
        return executions.values().stream().reduce(UfoAmount.ZERO, UfoAmount::add);
    }

    /** Total reported shortage units across every key. */
    public UfoAmount totalMissing() {
        return missing.values().stream().reduce(UfoAmount.ZERO, UfoAmount::add);
    }

    /** One-line, log-safe rendering used by the harness report. */
    public String describe() {
        return "executions=" + totalExecutions() + " missing=" + missing
                + " complete=" + complete + " steps=" + schedule.size();
    }
}
