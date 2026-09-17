package com.raishxn.ufocore.api.crafting.planner.differential;

import com.raishxn.ufocore.api.amount.UfoAmount;
import com.raishxn.ufocore.api.crafting.planner.CraftingPattern;
import com.raishxn.ufocore.api.crafting.planner.ImmutableCraftingGraph;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The semantics the current RaishxCore model can represent, and the lowering into its public API.
 *
 * <p>The set below is deliberately small. Anything outside it is refused before planning instead of
 * being approximated: fuzzy routing, reusable seeds, finite durability, emitters, probabilistic
 * outputs, conversion cycles and feedback all require the explicit model of roadmap phases
 * R2.3-R2.5. A refusal here is honest, not a hidden failure.
 */
public final class RaishxCoreSemanticModel {

    /**
     * The semantics this model claims. Always a subset of what the corpus may require.
     *
     * <p>{@code REUSABLE_INPUT} is claimed now that the shared replay oracle can verify a plan that
     * uses a catalyst: it checks the seed is present and hands it back rather than drawing it. Before
     * that the model refused the semantics, because a capability that cannot be proved is not
     * claimed.
     */
    private static final Set<CapabilitySemantics> SUPPORTED = Set.copyOf(EnumSet.of(
            CapabilitySemantics.DETERMINISTIC_EXACT_DAG,
            CapabilitySemantics.DETERMINISTIC_BYPRODUCT,
            CapabilitySemantics.BATCHING,
            CapabilitySemantics.MULTI_ROUTE,
            CapabilitySemantics.REUSABLE_INPUT,
            CapabilitySemantics.FINITE_DURABILITY,
            CapabilitySemantics.FUZZY_ALTERNATIVES,
            CapabilitySemantics.EMITTER,
            CapabilitySemantics.POSITIVE_FEEDBACK,
            CapabilitySemantics.CONVERSION_CYCLE,
            CapabilitySemantics.CONSERVATIVE_FEEDBACK,
            CapabilitySemantics.LOSSY_FEEDBACK));

    private RaishxCoreSemanticModel() {
    }

    /** Every semantic this model claims. Always a subset of what the corpus may require. */
    public static Set<CapabilitySemantics> supported() {
        return SUPPORTED;
    }

    public static boolean supports(CapabilitySemantics semantics) {
        return SUPPORTED.contains(semantics);
    }

    /** Lowered scenario: patterns in the public API plus ordinary consumable stock. */
    public record Lowered(ImmutableCraftingGraph<String> graph, Map<String, UfoAmount> stock,
                          String target, UfoAmount amount) {
    }

    /** Raised when a scenario needs behaviour the current model does not represent. */
    public static final class UnsupportedSemantics extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;

        public UnsupportedSemantics(String message) {
            super(message);
        }
    }

    /**
     * Lowers a scenario into the public RaishxCore planner API.
     *
     * @param revision graph revision handed to the planner; production uses the grid revision
     * @throws UnsupportedSemantics when the scenario needs behaviour the model does not represent
     */
    public static Lowered lower(CapabilityScenario scenario, long revision) {
        List<CapabilitySemantics> missing = new ArrayList<>();
        for (CapabilitySemantics semantics : scenario.requiredSemantics()) {
            if (!supports(semantics)) {
                missing.add(semantics);
            }
        }
        if (!missing.isEmpty()) {
            throw new UnsupportedSemantics("unsupported semantics: " + missing);
        }

        LinkedHashMap<String, UfoAmount> stock = new LinkedHashMap<>();
        scenario.graph().stock().forEach((key, entry) -> {
            switch (entry.kind()) {
                // A host-owned reusable seed is ordinary inventory as far as the planner is concerned:
                // it has to be present, and the reusable input it feeds is never consumed, so the
                // seed survives the whole plan.
                case CONSUMABLE, REUSABLE -> stock.put(key, entry.amount());
                case EMITTED -> throw new UnsupportedSemantics("unsupported " + entry.kind() + " stock for " + key);
            }
        });

        ArrayList<CraftingPattern<String>> patterns = new ArrayList<>();
        for (CapabilityPattern pattern : scenario.graph().patterns()) {
            patterns.add(lower(pattern));
        }
        ImmutableCraftingGraph<String> graph = ImmutableCraftingGraph.create(revision,
                Comparator.naturalOrder(), patterns);
        if (graph.patternsFor(scenario.target()).isEmpty() && !stock.containsKey(scenario.target())) {
            throw new UnsupportedSemantics("no route produces " + scenario.target());
        }
        return new Lowered(graph, stock, scenario.target(), scenario.amount());
    }

    private static CraftingPattern<String> lower(CapabilityPattern pattern) {
        LinkedHashMap<String, UfoAmount> inputs = new LinkedHashMap<>();
        LinkedHashMap<String, UfoAmount> reusable = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> durable = new LinkedHashMap<>();
        LinkedHashMap<String, Set<String>> fuzzy = new LinkedHashMap<>();
        LinkedHashMap<String, UfoAmount> emitted = new LinkedHashMap<>();
        for (CapabilityInput input : pattern.inputs()) {
            switch (input.kind()) {
                case EXACT -> inputs.merge(input.key(), input.amount(), UfoAmount::add);
                case REUSABLE -> reusable.merge(input.key(), input.amount(), UfoAmount::add);
                case FINITE_USE -> {
                    // A durable carrier is an ordinary input that survives a number of firings.
                    inputs.merge(input.key(), input.amount(), UfoAmount::add);
                    durable.merge(input.key(), input.uses(), Integer::max);
                }
                case FUZZY -> {
                    // A logical slot any listed variant may fill, held as a reusable seed.
                    reusable.merge(input.key(), input.amount(), UfoAmount::add);
                    fuzzy.merge(input.key(), Set.copyOf(input.alternatives()), (left, right) -> {
                        LinkedHashSet<String> merged = new LinkedHashSet<>(left);
                        merged.addAll(right);
                        return Set.copyOf(merged);
                    });
                }
                case EMITTER -> {
                    // Declared so the pattern says what it expects, but an external source satisfies
                    // it, so the plan carries no requirement for it at all.
                    emitted.merge(input.key(), input.amount(), UfoAmount::add);
                }
                default -> throw new UnsupportedSemantics(
                        "pattern " + pattern.id() + " needs " + input.kind() + " input " + input.key());
            }
        }
        LinkedHashMap<String, UfoAmount> outputs = new LinkedHashMap<>();
        for (CapabilityOutput output : pattern.outputs()) {
            if (output.kind() == CapabilityOutput.Kind.PROBABILISTIC) {
                throw new UnsupportedSemantics(
                        "pattern " + pattern.id() + " declares probabilistic output " + output.key());
            }
            outputs.merge(output.key(), output.amount(), UfoAmount::add);
        }
        return new CraftingPattern<>(pattern.id(), pattern.priority(), inputs, reusable, durable, fuzzy,
                emitted, outputs, pattern.craftableOutputs());
    }
}
