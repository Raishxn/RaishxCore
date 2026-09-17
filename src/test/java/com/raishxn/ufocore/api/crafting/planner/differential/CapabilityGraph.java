package com.raishxn.ufocore.api.crafting.planner.differential;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Engine-neutral immutable graph: patterns plus declared stock of every kind. */
public record CapabilityGraph(List<CapabilityPattern> patterns, Map<String, CapabilityStock> stock) {

    /** One declared inventory entry. */
    public record CapabilityStock(UfoAmount amount, Kind kind, String host) {
        /** Inventory semantics that must never be mixed for the same physical resource. */
        public enum Kind {
            /** Ordinary consumable stock. */
            CONSUMABLE,
            /** Reusable stock owned by one host/route. */
            REUSABLE,
            /** Authorized external source availability. */
            EMITTED
        }

        public CapabilityStock {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(kind, "kind");
            if (amount.isZero()) throw new IllegalArgumentException("stock amount must be positive");
            if (kind != Kind.CONSUMABLE && (host == null || host.isBlank())) {
                throw new IllegalArgumentException(kind + " stock requires a host identity");
            }
        }

        public static CapabilityStock consumable(long amount) {
            return new CapabilityStock(UfoAmount.of(amount), Kind.CONSUMABLE, null);
        }

        public static CapabilityStock consumable(UfoAmount amount) {
            return new CapabilityStock(amount, Kind.CONSUMABLE, null);
        }

        public static CapabilityStock reusable(String host, long amount) {
            return new CapabilityStock(UfoAmount.of(amount), Kind.REUSABLE, host);
        }
    }

    public CapabilityGraph {
        Objects.requireNonNull(patterns, "patterns");
        Objects.requireNonNull(stock, "stock");
        patterns = List.copyOf(patterns);
        LinkedHashMap<String, CapabilityStock> copy = new LinkedHashMap<>();
        stock.forEach((key, value) -> {
            Objects.requireNonNull(key, "stock key");
            copy.put(key, Objects.requireNonNull(value, "stock entry"));
        });
        stock = Collections.unmodifiableMap(copy);
    }

    public static CapabilityGraph of(List<CapabilityPattern> patterns, Map<String, Long> stock) {
        LinkedHashMap<String, CapabilityStock> entries = new LinkedHashMap<>();
        stock.forEach((key, amount) -> entries.put(key, CapabilityStock.consumable(amount)));
        return new CapabilityGraph(patterns, entries);
    }

    /** Selectable routes for one output key, in declaration order. */
    public List<CapabilityPattern> routesFor(String key) {
        ArrayList<CapabilityPattern> result = new ArrayList<>();
        for (CapabilityPattern pattern : patterns) {
            if (pattern.routesTo(key)) {
                result.add(pattern);
            }
        }
        return List.copyOf(result);
    }

    /** Ordinary stock of one key, never counting reusable or emitted availability. */
    public UfoAmount consumableStock(String key) {
        CapabilityStock entry = stock.get(key);
        return entry == null || entry.kind() != CapabilityStock.Kind.CONSUMABLE
                ? UfoAmount.ZERO : entry.amount();
    }

    /** Adds ordinary stock on top of the declared inventory. */
    public CapabilityGraph withAdditionalStock(Map<String, UfoAmount> additions) {
        LinkedHashMap<String, CapabilityStock> merged = new LinkedHashMap<>(stock);
        additions.forEach((key, amount) -> {
            if (amount.isZero()) return;
            CapabilityStock existing = merged.get(key);
            if (existing != null && existing.kind() != CapabilityStock.Kind.CONSUMABLE) {
                throw new IllegalArgumentException(
                        "cannot add consumable stock over " + existing.kind() + " stock of " + key);
            }
            UfoAmount base = existing == null ? UfoAmount.ZERO : existing.amount();
            merged.put(key, CapabilityStock.consumable(base.add(amount)));
        });
        return new CapabilityGraph(patterns, merged);
    }

    /** Reverses declaration order; used to prove order-independent determinism. */
    public CapabilityGraph reversedPatternOrder() {
        ArrayList<CapabilityPattern> reversed = new ArrayList<>(patterns);
        Collections.reverse(reversed);
        return new CapabilityGraph(reversed, stock);
    }
}
