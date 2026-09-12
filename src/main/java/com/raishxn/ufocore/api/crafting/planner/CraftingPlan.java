package com.raishxn.ufocore.api.crafting.planner;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Immutable exact plan, including shortages and deterministic quality metrics. */
public record CraftingPlan<K>(K target, UfoAmount requested,
                              Map<CraftingPattern<K>, UfoAmount> patternExecutions,
                              Map<K, UfoAmount> extractedFromInventory,
                              Map<K, UfoAmount> missing,
                              Map<K, UfoAmount> remaining,
                              List<Execution<K>> schedule,
                              PlanQuality quality) {
    public CraftingPlan {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(requested, "requested");
        patternExecutions = freeze(patternExecutions);
        extractedFromInventory = freeze(extractedFromInventory);
        missing = freeze(missing);
        remaining = freeze(remaining);
        schedule = List.copyOf(schedule);
        Objects.requireNonNull(quality, "quality");
    }

    public boolean complete() { return missing.isEmpty(); }

    private static <T> Map<T, UfoAmount> freeze(Map<T, UfoAmount> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    public record Execution<K>(CraftingPattern<K> pattern, UfoAmount runs) {}

    public record PlanQuality(boolean complete, int distinctPatterns, UfoAmount totalExecutions,
                              UfoAmount missingUnits, UfoAmount overproducedUnits) {
        public PlanQuality {
            Objects.requireNonNull(totalExecutions, "totalExecutions");
            Objects.requireNonNull(missingUnits, "missingUnits");
            Objects.requireNonNull(overproducedUnits, "overproducedUnits");
        }
    }
}
