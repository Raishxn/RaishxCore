package com.raishxn.ufocore.api.crafting.planner;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Set;

/**
 * Immutable crafting graph compiled once for one grid revision.
 * Pattern and ingredient iteration order is canonical and never depends on hash order.
 *
 * <p>Within one pattern, an input that has a selectable route is resolved before an input that has
 * none. A deterministic coproduct is captured as a secondary output, so it is never a route: it can
 * only be collected after the sibling branch that produces it has run. Resolving it in raw key order
 * made a composite whose coproduct key sorts before its routable keys report an impossible shortage.
 */
public final class ImmutableCraftingGraph<K> {
    private final long revision;
    private final Comparator<? super K> keyComparator;
    private final List<CraftingPattern<K>> patterns;
    private final NavigableMap<K, List<CompiledPattern<K>>> byOutput;
    private final Map<K, List<CompiledPattern<K>>> byInput;
    private final List<CompiledPattern<K>> compiled;
    private final List<K> simpleDemandOrder;

    private ImmutableCraftingGraph(long revision, Comparator<? super K> keyComparator,
                                   Collection<CraftingPattern<K>> source) {
        if (revision < 0L) throw new IllegalArgumentException("revision must be non-negative");
        this.revision = revision;
        this.keyComparator = Objects.requireNonNull(keyComparator, "keyComparator");
        ArrayList<CraftingPattern<K>> ordered = new ArrayList<>(Objects.requireNonNull(source, "patterns"));
        ordered.sort(Comparator.comparing(CraftingPattern::id));
        HashSet<String> ids = new HashSet<>();
        TreeMap<K, List<CompiledPattern<K>>> index = new TreeMap<>(keyComparator);
        TreeMap<K, List<CompiledPattern<K>>> consumers = new TreeMap<>(keyComparator);
        ArrayList<CompiledPattern<K>> compiledPatterns = new ArrayList<>();
        TreeMap<K, K> uniqueKeys = new TreeMap<>(keyComparator);
        TreeSet<K> routable = new TreeSet<>(keyComparator);
        for (CraftingPattern<K> pattern : ordered) {
            Objects.requireNonNull(pattern, "pattern");
            if (!ids.add(pattern.id())) throw new IllegalArgumentException("duplicate pattern id: " + pattern.id());
            routable.addAll(pattern.craftableOutputs());
        }
        for (CraftingPattern<K> pattern : ordered) {
            CompiledPattern<K> compiled = compile(pattern, keyComparator, routable);
            compiledPatterns.add(compiled);
            for (K key : pattern.inputs().keySet()) checkKey(uniqueKeys, key);
            for (K key : pattern.outputs().keySet()) checkKey(uniqueKeys, key);
            for (PatternEntry<K> output : compiled.outputs()) {
                if (pattern.craftableOutputs().contains(output.key())) {
                    index.computeIfAbsent(output.key(), ignored -> new ArrayList<>()).add(compiled);
                }
            }
            for (PatternEntry<K> input : compiled.inputs()) {
                consumers.computeIfAbsent(input.key(), ignored -> new ArrayList<>()).add(compiled);
            }
        }
        index.replaceAll((key, value) -> List.copyOf(value));
        this.patterns = List.copyOf(ordered);
        this.byOutput = Collections.unmodifiableNavigableMap(index);
        consumers.replaceAll((key, value) -> List.copyOf(value));
        this.byInput = Collections.unmodifiableMap(consumers);
        this.compiled = List.copyOf(compiledPatterns);
        this.simpleDemandOrder = compileSimpleDemandOrder(uniqueKeys.keySet());
    }

    public static <K> ImmutableCraftingGraph<K> create(long revision,
                                                        Comparator<? super K> keyComparator,
                                                        Collection<CraftingPattern<K>> patterns) {
        return new ImmutableCraftingGraph<>(revision, keyComparator, patterns);
    }

    public long revision() { return revision; }
    public Comparator<? super K> keyComparator() { return keyComparator; }
    public List<CraftingPattern<K>> patterns() { return patterns; }
    public List<CraftingPattern<K>> patternsFor(K output) {
        List<CompiledPattern<K>> matches = byOutput.get(output);
        return matches == null ? List.of() : matches.stream().map(CompiledPattern::pattern).toList();
    }

    List<CompiledPattern<K>> compiledPatternsFor(K output) {
        return byOutput.getOrDefault(output, List.of());
    }

    List<CompiledPattern<K>> consumersOf(K input) { return byInput.getOrDefault(input, List.of()); }
    List<CompiledPattern<K>> compiledPatterns() { return compiled; }
    /** Nonempty only for a single-route, single-output DAG. Ordered from products to ingredients. */
    List<K> simpleDemandOrder() { return simpleDemandOrder; }

    private void checkKey(TreeMap<K, K> keys, K key) {
        K previous = keys.putIfAbsent(key, key);
        if (previous != null && !previous.equals(key)) {
            throw new IllegalArgumentException("key comparator equates different resource keys");
        }
    }

    private List<K> compileSimpleDemandOrder(Set<K> keys) {
        if (byOutput.values().stream().anyMatch(value -> value.size() != 1)
                || compiled.stream().anyMatch(value -> value.outputs().size() != 1)) return List.of();
        Map<K, Integer> incoming = new HashMap<>();
        for (K key : keys) incoming.put(key, 0);
        for (CompiledPattern<K> pattern : compiled) {
            for (PatternEntry<K> input : pattern.inputs()) incoming.merge(input.key(), 1, Integer::sum);
        }
        ArrayDeque<K> ready = new ArrayDeque<>();
        for (K key : keys) if (incoming.get(key) == 0) ready.add(key);
        ArrayList<K> order = new ArrayList<>(keys.size());
        while (!ready.isEmpty()) {
            K key = ready.removeFirst();
            order.add(key);
            for (CompiledPattern<K> pattern : compiledPatternsFor(key)) {
                for (PatternEntry<K> input : pattern.inputs()) {
                    if (incoming.merge(input.key(), -1, Integer::sum) == 0) ready.addLast(input.key());
                }
            }
        }
        return order.size() == keys.size() ? List.copyOf(order) : List.of();
    }

    private static <K> CompiledPattern<K> compile(CraftingPattern<K> pattern,
                                                   Comparator<? super K> comparator, Set<K> routable) {
        return new CompiledPattern<>(pattern, entries(pattern.inputs(), comparator, routable),
                entries(pattern.outputs(), comparator, null));
    }

    /**
     * Canonical entry order. When {@code routableFirst} is given, inputs with a selectable route come
     * before inputs that can only be collected as a deterministic coproduct of a sibling branch.
     */
    private static <K> List<PatternEntry<K>> entries(Map<K, UfoAmount> amounts,
                                                      Comparator<? super K> comparator,
                                                      Set<K> routableFirst) {
        ArrayList<PatternEntry<K>> entries = new ArrayList<>(amounts.size());
        amounts.forEach((key, amount) -> entries.add(new PatternEntry<>(key, amount)));
        entries.sort((left, right) -> {
            if (routableFirst != null) {
                boolean leftRoutable = routableFirst.contains(left.key());
                boolean rightRoutable = routableFirst.contains(right.key());
                if (leftRoutable != rightRoutable) return leftRoutable ? -1 : 1;
            }
            return comparator.compare(left.key(), right.key());
        });
        return List.copyOf(entries);
    }

    record PatternEntry<K>(K key, UfoAmount amount) {}
    record CompiledPattern<K>(CraftingPattern<K> pattern, List<PatternEntry<K>> inputs,
                              List<PatternEntry<K>> outputs) {
        UfoAmount outputAmount(K key) {
            for (PatternEntry<K> output : outputs) if (output.key().equals(key)) return output.amount();
            throw new IllegalArgumentException("pattern does not produce requested key: " + key);
        }
    }
}
