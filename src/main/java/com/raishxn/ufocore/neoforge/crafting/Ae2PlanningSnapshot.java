package com.raishxn.ufocore.neoforge.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.me.service.CraftingService;
import com.raishxn.ufocore.api.amount.UfoAmount;
import com.raishxn.ufocore.api.crafting.planner.CraftingPattern;
import com.raishxn.ufocore.api.crafting.planner.ImmutableCraftingGraph;
import java.io.Serial;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

/**
 * Server-thread capture boundary. Only String keys and exact amounts enter the mathematical graph.
 * Native handles are used solely to build the final AE2 plan, never queried by the planning worker.
 */
public record Ae2PlanningSnapshot(ImmutableCraftingGraph<String> graph, String target,
                                  Map<String, AEKey> keys, Map<AEKey, String> keyIds,
                                  Map<String, IPatternDetails> patterns, Map<String, Integer> amountsPerByte,
                                  boolean multiplePaths) {
    public Ae2PlanningSnapshot {
        keys = Map.copyOf(keys); keyIds = Map.copyOf(keyIds); patterns = Map.copyOf(patterns);
        amountsPerByte = Map.copyOf(amountsPerByte);
    }

    /** Throws Declined before publishing anything if any reachable pattern needs richer semantics. */
    public static Ae2PlanningSnapshot capture(Level level, ICraftingService service, AEKey target, long revision) {
        return capture(level, service, target, revision, CaptureLimits.DEFAULT);
    }

    /** Throws Declined before publishing anything if semantics or configured budgets cannot be honored. */
    public static Ae2PlanningSnapshot capture(Level level, ICraftingService service, AEKey target, long revision,
                                               CaptureLimits limits) {
        CaptureBudget budget = new CaptureBudget(limits);
        Map<AEKey, String> ids = new HashMap<>();
        Map<String, AEKey> keys = new HashMap<>();
        Map<String, Integer> byteAmounts = new HashMap<>();
        Map<String, IPatternDetails> handles = new LinkedHashMap<>();
        Map<String, CraftingPattern<String>> patterns = new LinkedHashMap<>();
        Set<AEKey> visited = new HashSet<>();
        ArrayDeque<AEKey> pending = new ArrayDeque<>();
        pending.add(target);
        boolean alternatives = false;
        while (!pending.isEmpty()) {
            budget.checkpoint();
            AEKey output = pending.removeFirst();
            if (!visited.add(output)) continue;
            id(output, level, ids, keys, byteAmounts, budget);
            if (service.canEmitFor(output)) throw new Declined("crafting emitter");
            var recipes = service.getCraftingFor(output);
            alternatives |= recipes.size() > 1;
            for (IPatternDetails pattern : recipes) {
                budget.edge();
                if (pattern instanceof AECraftingPattern crafting
                        && (crafting.canSubstitute() || crafting.canSubstituteFluids())) {
                    throw new Declined("substitution pattern");
                }
                var definition = pattern.getDefinition();
                if (definition == null) throw new Declined("pattern without stable definition");
                String patternId = canonical(definition.toTagGeneric(level.registryAccess()));
                if (patterns.containsKey(patternId)) continue;
                budget.pattern(patternId);
                Map<String, UfoAmount> inputs = new HashMap<>();
                Map<String, UfoAmount> outputs = new HashMap<>();
                for (var input : pattern.getInputs()) {
                    budget.edge();
                    var options = input.getPossibleInputs();
                    if (options.length != 1 || options[0].amount() <= 0 || input.getMultiplier() <= 0
                            || input.getRemainingKey(options[0].what()) != null
                            || !input.isValid(options[0].what(), level)) {
                        throw new Declined("non-exact or remainder input");
                    }
                    AEKey key = options[0].what();
                    String inputId = id(key, level, ids, keys, byteAmounts, budget);
                    UfoAmount amount = UfoAmount.of(options[0].amount()).multiply(input.getMultiplier());
                    inputs.merge(inputId, amount, UfoAmount::add);
                    pending.addLast(key);
                }
                for (var result : pattern.getOutputs()) {
                    budget.edge();
                    if (result.amount() <= 0) throw new Declined("invalid output");
                    String outputId = id(result.what(), level, ids, keys, byteAmounts, budget);
                    outputs.merge(outputId, UfoAmount.of(result.amount()), UfoAmount::add);
                }
                if (outputs.isEmpty() || inputs.keySet().stream().anyMatch(outputs::containsKey)) {
                    throw new Declined("feedback or catalyst pattern");
                }
                String primary = id(pattern.getPrimaryOutput().what(), level, ids, keys, byteAmounts, budget);
                int priority = Integer.MIN_VALUE;
                if (service instanceof CraftingService nativeService) {
                    for (var provider : nativeService.getProviders(pattern)) {
                        priority = Math.max(priority, provider.getPatternPriority());
                    }
                }
                patterns.put(patternId, new CraftingPattern<>(patternId,
                        priority == Integer.MIN_VALUE ? 0 : priority, inputs, outputs, Set.of(primary)));
                handles.put(patternId, pattern);
            }
        }
        var graph = ImmutableCraftingGraph.create(revision, Comparator.<String>naturalOrder(), patterns.values());
        budget.checkpoint();
        return new Ae2PlanningSnapshot(graph, ids.get(target), keys, ids, handles, byteAmounts, alternatives);
    }

    private static String id(AEKey key, Level level, Map<AEKey, String> ids, Map<String, AEKey> keys,
                              Map<String, Integer> byteAmounts, CaptureBudget budget) {
        String known = ids.get(key);
        if (known != null) return known;
        String id = canonical(key.toTagGeneric(level.registryAccess()));
        AEKey previous = keys.putIfAbsent(id, key);
        if (previous != null && !previous.equals(key)) throw new Declined("ambiguous key serialization");
        budget.key(id);
        ids.put(key, id);
        int amount = key.getAmountPerByte();
        if (amount <= 0) throw new Declined("invalid byte conversion");
        byteAmounts.put(id, amount);
        return id;
    }

    public record CaptureLimits(Duration timeout, int maxEdges, int maxKeys, long maxEstimatedBytes) {
        private static final CaptureLimits DEFAULT =
                new CaptureLimits(Duration.ofMillis(50), 100_000, 25_000, 64L * 1024 * 1024);

        public CaptureLimits {
            if (timeout == null || timeout.isZero() || timeout.isNegative()
                    || maxEdges < 1 || maxKeys < 1 || maxEstimatedBytes < 1) {
                throw new IllegalArgumentException("snapshot limits must be positive");
            }
        }
    }

    static final class CaptureBudget {
        private static final long EDGE_BYTES = 64;
        private static final long KEY_OVERHEAD_BYTES = 256;
        private static final long PATTERN_OVERHEAD_BYTES = 256;
        private final CaptureLimits limits;
        private final long started = System.nanoTime();
        private final long timeoutNanos;
        private int edges;
        private int keys;
        private long estimatedBytes;

        CaptureBudget(CaptureLimits limits) {
            this.limits = java.util.Objects.requireNonNull(limits, "limits");
            long nanos;
            try { nanos = limits.timeout().toNanos(); }
            catch (ArithmeticException overflow) { nanos = Long.MAX_VALUE; }
            timeoutNanos = nanos;
        }

        void checkpoint() {
            if (System.nanoTime() - started >= timeoutNanos) throw new Declined("snapshot time limit");
        }

        void edge() {
            if (++edges > limits.maxEdges()) throw new Declined("snapshot edge limit");
            addBytes(EDGE_BYTES);
            checkpoint();
        }

        void key(String id) {
            if (++keys > limits.maxKeys()) throw new Declined("snapshot key limit");
            addBytes(KEY_OVERHEAD_BYTES + stringBytes(id));
        }

        void pattern(String id) {
            addBytes(PATTERN_OVERHEAD_BYTES + stringBytes(id));
        }

        private static long stringBytes(String value) {
            try { return Math.multiplyExact((long) value.length(), Character.BYTES); }
            catch (ArithmeticException overflow) { throw new Declined("snapshot memory limit"); }
        }

        private void addBytes(long amount) {
            if (amount > limits.maxEstimatedBytes() - estimatedBytes) {
                throw new Declined("snapshot memory limit");
            }
            estimatedBytes += amount;
        }
    }

    /** Sorted compound keys preserve components without depending on HashMap/NBT iteration order. */
    static String canonical(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            List<String> names = new ArrayList<>(compound.getAllKeys());
            names.sort(Comparator.naturalOrder());
            StringBuilder result = new StringBuilder("{");
            for (String name : names) {
                result.append(StringTag.quoteAndEscape(name)).append(':')
                        .append(canonical(compound.get(name))).append(',');
            }
            return result.append('}').toString();
        }
        if (tag instanceof ListTag list) {
            StringBuilder result = new StringBuilder("[");
            for (Tag entry : list) result.append(canonical(entry)).append(',');
            return result.append(']').toString();
        }
        return tag.toString();
    }

    public static final class Declined extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;
        Declined(String reason) { super(reason, null, false, false); }
    }
}
