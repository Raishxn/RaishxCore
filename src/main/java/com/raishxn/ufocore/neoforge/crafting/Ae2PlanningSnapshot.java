package com.raishxn.ufocore.neoforge.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.me.service.CraftingService;
import com.raishxn.ufocore.api.amount.UfoAmount;
import com.raishxn.ufocore.api.crafting.planner.CraftingPattern;
import com.raishxn.ufocore.api.crafting.planner.ImmutableCraftingGraph;
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
        long started = System.nanoTime();
        Map<AEKey, String> ids = new HashMap<>();
        Map<String, AEKey> keys = new HashMap<>();
        Map<String, Integer> byteAmounts = new HashMap<>();
        Map<String, IPatternDetails> handles = new LinkedHashMap<>();
        Map<String, CraftingPattern<String>> patterns = new LinkedHashMap<>();
        Set<AEKey> visited = new HashSet<>();
        ArrayDeque<AEKey> pending = new ArrayDeque<>();
        pending.add(target);
        boolean alternatives = false;
        int edges = 0;
        while (!pending.isEmpty()) {
            if (System.nanoTime() - started > 50_000_000L || edges > 100_000 || visited.size() > 25_000) {
                throw new Declined("snapshot budget");
            }
            AEKey output = pending.removeFirst();
            if (!visited.add(output)) continue;
            id(output, level, ids, keys, byteAmounts);
            if (service.canEmitFor(output)) throw new Declined("crafting emitter");
            var recipes = service.getCraftingFor(output);
            alternatives |= recipes.size() > 1;
            for (IPatternDetails pattern : recipes) {
                if (++edges > 100_000 || System.nanoTime() - started > 50_000_000L) {
                    throw new Declined("snapshot budget");
                }
                if (pattern instanceof AECraftingPattern crafting
                        && (crafting.canSubstitute() || crafting.canSubstituteFluids())) {
                    throw new Declined("substitution pattern");
                }
                var definition = pattern.getDefinition();
                if (definition == null) throw new Declined("pattern without stable definition");
                String patternId = canonical(definition.toTagGeneric(level.registryAccess()));
                if (patterns.containsKey(patternId)) continue;
                Map<String, UfoAmount> inputs = new HashMap<>();
                Map<String, UfoAmount> outputs = new HashMap<>();
                for (var input : pattern.getInputs()) {
                    if (++edges > 100_000) throw new Declined("snapshot budget");
                    var options = input.getPossibleInputs();
                    if (options.length != 1 || options[0].amount() <= 0 || input.getMultiplier() <= 0
                            || input.getRemainingKey(options[0].what()) != null
                            || !input.isValid(options[0].what(), level)) {
                        throw new Declined("non-exact or remainder input");
                    }
                    AEKey key = options[0].what();
                    String inputId = id(key, level, ids, keys, byteAmounts);
                    UfoAmount amount = UfoAmount.of(options[0].amount()).multiply(input.getMultiplier());
                    inputs.merge(inputId, amount, UfoAmount::add);
                    pending.addLast(key);
                }
                for (var result : pattern.getOutputs()) {
                    if (++edges > 100_000 || result.amount() <= 0) throw new Declined("invalid output");
                    String outputId = id(result.what(), level, ids, keys, byteAmounts);
                    outputs.merge(outputId, UfoAmount.of(result.amount()), UfoAmount::add);
                }
                if (outputs.isEmpty() || inputs.keySet().stream().anyMatch(outputs::containsKey)) {
                    throw new Declined("feedback or catalyst pattern");
                }
                String primary = id(pattern.getPrimaryOutput().what(), level, ids, keys, byteAmounts);
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
        if (System.nanoTime() - started > 50_000_000L) throw new Declined("snapshot budget");
        return new Ae2PlanningSnapshot(graph, ids.get(target), keys, ids, handles, byteAmounts, alternatives);
    }

    private static String id(AEKey key, Level level, Map<AEKey, String> ids, Map<String, AEKey> keys,
                              Map<String, Integer> byteAmounts) {
        String known = ids.get(key);
        if (known != null) return known;
        String id = canonical(key.toTagGeneric(level.registryAccess()));
        AEKey previous = keys.putIfAbsent(id, key);
        if (previous != null && !previous.equals(key)) throw new Declined("ambiguous key serialization");
        ids.put(key, id);
        int amount = key.getAmountPerByte();
        if (amount <= 0) throw new Declined("invalid byte conversion");
        byteAmounts.put(id, amount);
        return id;
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
        Declined(String reason) { super(reason, null, false, false); }
    }
}
