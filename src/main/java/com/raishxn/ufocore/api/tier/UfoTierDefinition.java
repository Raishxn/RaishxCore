package com.raishxn.ufocore.api.tier;

import com.raishxn.ufocore.api.amount.UfoAmount;
import com.raishxn.ufocore.api.amount.UfoRatio;
import java.util.Map;
import java.util.Objects;

/** Data-oriented tier. Attribute names belong to the consuming addon. */
public record UfoTierDefinition(
        String id,
        int level,
        String displayName,
        Map<String, UfoAmount> amounts,
        Map<String, UfoRatio> ratios) {

    public UfoTierDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        if (id.isBlank()) throw new IllegalArgumentException("tier id must not be blank");
        if (level < 0) throw new IllegalArgumentException("tier level must be non-negative");
        amounts = Map.copyOf(Objects.requireNonNull(amounts, "amounts"));
        ratios = Map.copyOf(Objects.requireNonNull(ratios, "ratios"));
    }

    public UfoAmount amount(String key, UfoAmount fallback) {
        return amounts.getOrDefault(key, fallback);
    }

    public UfoRatio ratio(String key, UfoRatio fallback) {
        return ratios.getOrDefault(key, fallback);
    }
}
