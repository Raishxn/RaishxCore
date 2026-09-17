package com.raishxn.ufocore.neoforge.crafting;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.me.service.CraftingService;
import com.raishxn.ufocore.api.amount.UfoAmount;
import com.raishxn.ufocore.neoforge.crafting.CooperativeGraphCapture.KeyDetails;
import com.raishxn.ufocore.neoforge.crafting.CooperativeGraphCapture.PatternDetails;
import com.raishxn.ufocore.neoforge.crafting.CooperativeGraphCapture.Slot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.Level;

/**
 * Server-thread adapter that answers the neutral capture machine in terms of canonical key ids.
 *
 * <p>Every AE2 call and every semantic refusal lives here, so {@link CooperativeGraphCapture} stays
 * free of grid types and can be driven across ticks and unit tested with plain strings. Native
 * handles are only kept to rebuild the AE2 plan after the worker is done; no worker ever sees them.
 *
 * <p>Refusals are deliberately loud: a pattern with substitution, remainders, feedback, an unstable
 * definition or a non-exact input raises {@link Ae2PlanningSnapshot.Declined} instead of producing a
 * graph that would misrepresent the grid. The answers are cached per id, so a capture that resumes on
 * a later tick never repeats a grid query.
 */
final class Ae2CaptureSource implements CooperativeGraphCapture.Source<IPatternDetails> {
    private final Level level;
    private final ICraftingService service;
    private final Map<AEKey, String> ids = new HashMap<>();
    private final Map<String, AEKey> keys = new LinkedHashMap<>();
    private final Map<String, Integer> amountsPerByte = new LinkedHashMap<>();
    private final Map<String, List<IPatternDetails>> available = new HashMap<>();
    private final Map<String, KeyDetails> described = new HashMap<>();
    private final Map<String, List<PatternDetails<IPatternDetails>>> recipes = new HashMap<>();
    private final Set<String> capturedPatterns = new HashSet<>();

    Ae2CaptureSource(Level level, ICraftingService service) {
        this.level = level;
        this.service = service;
    }

    /** Canonical id of the requested key; also registers it with its byte conversion. */
    String targetId(AEKey target) {
        return id(target);
    }

    /** Native handle of every key the capture registered, by canonical id. */
    Map<String, AEKey> keys() {
        return keys;
    }

    @Override
    public KeyDetails describe(String id) {
        KeyDetails known = described.get(id);
        if (known != null) return known;
        AEKey key = nativeKey(id);
        var details = new KeyDetails(id, amountPerByte(id), service.canEmitFor(key),
                availableFor(id).size());
        described.put(id, details);
        return details;
    }

    @Override
    public List<PatternDetails<IPatternDetails>> patternsFor(String id) {
        List<PatternDetails<IPatternDetails>> known = recipes.get(id);
        if (known != null) return known;
        List<PatternDetails<IPatternDetails>> captured = new ArrayList<>();
        for (IPatternDetails pattern : availableFor(id)) capture(pattern, captured);
        List<PatternDetails<IPatternDetails>> result = List.copyOf(captured);
        recipes.put(id, result);
        return result;
    }

    /** Canonical id for a native key. Two keys with the same id make the capture incoherent. */
    String id(AEKey key) {
        String known = ids.get(key);
        if (known != null) return known;
        String id = Ae2PlanningSnapshot.canonical(key.toTagGeneric(level.registryAccess()));
        AEKey previous = keys.putIfAbsent(id, key);
        if (previous != null && !previous.equals(key)) {
            throw new Ae2PlanningSnapshot.Declined("ambiguous key serialization");
        }
        int amount = key.getAmountPerByte();
        if (amount <= 0) throw new Ae2PlanningSnapshot.Declined("invalid byte conversion");
        ids.put(key, id);
        amountsPerByte.put(id, amount);
        return id;
    }

    private void capture(IPatternDetails pattern, List<PatternDetails<IPatternDetails>> captured) {
        if (pattern instanceof AECraftingPattern crafting
                && (crafting.canSubstitute() || crafting.canSubstituteFluids())) {
            throw new Ae2PlanningSnapshot.Declined("substitution pattern");
        }
        var definition = pattern.getDefinition();
        if (definition == null) throw new Ae2PlanningSnapshot.Declined("pattern without stable definition");
        String patternId = Ae2PlanningSnapshot.canonical(definition.toTagGeneric(level.registryAccess()));
        if (capturedPatterns.contains(patternId)) return;
        Map<String, Slot> inputs = new LinkedHashMap<>();
        for (var input : pattern.getInputs()) {
            var options = input.getPossibleInputs();
            if (options.length != 1 || options[0].amount() <= 0 || input.getMultiplier() <= 0
                    || input.getRemainingKey(options[0].what()) != null
                    || !input.isValid(options[0].what(), level)) {
                throw new Ae2PlanningSnapshot.Declined("non-exact or remainder input");
            }
            AEKey inputKey = options[0].what();
            String inputId = id(inputKey);
            UfoAmount amount = UfoAmount.of(options[0].amount()).multiply(input.getMultiplier());
            inputs.merge(inputId, new Slot(amount, amountPerByte(inputId)), Ae2CaptureSource::merge);
        }
        Map<String, Slot> outputs = new LinkedHashMap<>();
        for (var result : pattern.getOutputs()) {
            if (result.amount() <= 0) throw new Ae2PlanningSnapshot.Declined("invalid output");
            String outputId = id(result.what());
            outputs.merge(outputId, new Slot(UfoAmount.of(result.amount()), amountPerByte(outputId)),
                    Ae2CaptureSource::merge);
        }
        if (outputs.isEmpty()) throw new Ae2PlanningSnapshot.Declined("pattern without outputs");
        String primary = id(pattern.getPrimaryOutput().what());
        if (!outputs.containsKey(primary)) {
            throw new Ae2PlanningSnapshot.Declined("primary output is not a declared output");
        }
        capturedPatterns.add(patternId);
        captured.add(new PatternDetails<>(pattern, patternId, priority(pattern), inputs, outputs, Set.of(primary)));
    }

    private int priority(IPatternDetails pattern) {
        int highest = Integer.MIN_VALUE;
        if (service instanceof CraftingService nativeService) {
            for (var provider : nativeService.getProviders(pattern)) {
                highest = Math.max(highest, provider.getPatternPriority());
            }
        }
        return highest == Integer.MIN_VALUE ? 0 : highest;
    }

    private List<IPatternDetails> availableFor(String id) {
        return available.computeIfAbsent(id, known -> List.copyOf(service.getCraftingFor(nativeKey(known))));
    }

    private int amountPerByte(String id) {
        Integer amount = amountsPerByte.get(id);
        if (amount == null) throw new Ae2PlanningSnapshot.Declined("unknown key id: " + id);
        return amount;
    }

    private AEKey nativeKey(String id) {
        AEKey key = keys.get(id);
        if (key == null) throw new Ae2PlanningSnapshot.Declined("unknown key id: " + id);
        return key;
    }

    private static Slot merge(Slot left, Slot right) {
        return new Slot(left.amount().add(right.amount()), left.amountPerByte());
    }
}
