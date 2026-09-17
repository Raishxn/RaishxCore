package com.raishxn.ufocore.api.crafting.planner.differential;

import com.raishxn.ufocore.api.amount.UfoAmount;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Independent reconstruction of the published capability reference standard.
 *
 * <p>This is a behavioural specification only. It deliberately does not import, copy or run any
 * production class of another planner; every case is rebuilt from the published description and
 * expressed in the neutral model of this package.
 *
 * <p>Documented deviations from the reference suite, which must never be presented as the reference
 * suite's own results:
 *
 * <ul>
 *   <li>the multi-route greedy trap runs at the reference scale, {@value #GREEDY_TRAP_GROUPS}
 *       independent conflicts. It was held at eight while the bounded local backtracking made the
 *       full-scale case read as a budget question; it is not one, and the deviation is withdrawn
 *       rather than kept as a caveat that no longer applies;</li>
 *   <li>the deep single-route chain uses {@value #DEEP_CHAIN_DEPTH} instead of the reference depth
 *       used for its own suite;</li>
 *   <li>the multi-route Fibonacci case keeps one minimum witness instead of the exponential
 *       frontier, so the recorded optimum is a lower-bound denominator and not a claimed frontier.</li>
 * </ul>
 */
public final class CapabilityCorpus {

    /** Stock value used for the "effectively unlimited" leaf inventory mode. */
    public static final UfoAmount UNBOUNDED = UfoAmount.of(1_000_000_000_000L);

    public static final int SINGLE_DAG_DEPTH = 32;
    public static final int MULTI_DAG_DEPTH = 12;
    public static final int GREEDY_TRAP_GROUPS = 32;
    public static final int DEEP_CHAIN_DEPTH = 20_000;

    private static final Set<CapabilitySemantics> DAG =
            Set.of(CapabilitySemantics.DETERMINISTIC_EXACT_DAG);
    private static final Set<CapabilitySemantics> DAG_BATCHING = Set.of(
            CapabilitySemantics.DETERMINISTIC_EXACT_DAG, CapabilitySemantics.BATCHING);
    private static final Set<CapabilitySemantics> DAG_MULTI_ROUTE = Set.of(
            CapabilitySemantics.DETERMINISTIC_EXACT_DAG, CapabilitySemantics.MULTI_ROUTE);
    private static final Set<CapabilitySemantics> DAG_BYPRODUCT = Set.of(
            CapabilitySemantics.DETERMINISTIC_EXACT_DAG, CapabilitySemantics.DETERMINISTIC_BYPRODUCT);
    private static final Set<CapabilitySemantics> DAG_BYPRODUCT_MULTI_ROUTE = Set.of(
            CapabilitySemantics.DETERMINISTIC_EXACT_DAG, CapabilitySemantics.DETERMINISTIC_BYPRODUCT,
            CapabilitySemantics.MULTI_ROUTE);

    private CapabilityCorpus() {
    }

    /** Every scenario of the corpus, in report order. */
    public static List<CapabilityScenario> all() {
        List<CapabilityScenario> scenarios = new ArrayList<>();
        addDispersedSingleDag(scenarios);
        addFibonacciSingleDag(scenarios);
        addSiblingRoutes(scenarios);
        addFibonacciMultiDag(scenarios);
        addGreedyTrap(scenarios);
        addBatching(scenarios);
        addSharedCoproduct(scenarios);
        addCoproductFeedsLaterStage(scenarios);
        addDeepChain(scenarios);
        addConversionRing(scenarios);
        addSurplusSecondaryDemand(scenarios);
        addSecondaryThroughCatalyst(scenarios);
        addDurabilityAcrossExpansions(scenarios);
        addCatalystWithCarrier(scenarios);
        addSecondaryOutbidsDeclared(scenarios);
        addFuzzyWithSecondary(scenarios);
        addWeightedShortage(scenarios);
        addWeightedLeafCost(scenarios);
        addChanceRoute(scenarios);
        addSelfGrowth(scenarios);
        addRawFeedbackLoop(scenarios);
        addLossyFeedbackLoop(scenarios);
        addReturnedCatalyst(scenarios);
        addFiniteDurability(scenarios);
        addFuzzyVariant(scenarios);
        addEmitter(scenarios);
        return List.copyOf(scenarios);
    }

    /** Scenarios the current model claims; anything but SUPPORTED here is a finding. */
    public static List<CapabilityScenario> required() {
        return all().stream()
                .filter(scenario -> scenario.expectation() == CapabilityExpectation.REQUIRED)
                .toList();
    }

    /** Scenarios declared as explicit limitations until their roadmap phase lands. */
    public static List<CapabilityScenario> limitations() {
        return all().stream()
                .filter(scenario -> scenario.expectation() == CapabilityExpectation.LIMITATION)
                .toList();
    }

    // ---------------------------------------------------------------- representable families

    private static void addDispersedSingleDag(List<CapabilityScenario> out) {
        Map<String, UfoAmount> minimum = amounts(Map.of("D", 4L, "E", 4L, "F", 4L, "G", 4L));
        Map<String, UfoAmount> starved = amounts(Map.of("D", 2L, "E", 4L, "F", 4L, "G", 3L));
        threeModes(out, "single-dag/dispersed", CapabilityFamily.SINGLE_DAG, 3, "A", UfoAmount.of(4),
                minimum, starved, List.of(amounts(Map.of("D", 2L, "G", 1L))), true, DAG,
                CapabilityExpectation.REQUIRED, CapabilityCorpus::dispersed);
    }

    private static CapabilityGraph dispersed(Map<String, UfoAmount> stock) {
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("a-from-bc", List.of(input("B", 1), input("C", 1)), List.of(primary("A", 1))),
                CapabilityPattern.of("b-from-de", List.of(input("D", 1), input("E", 1)), List.of(primary("B", 1))),
                CapabilityPattern.of("c-from-fg", List.of(input("F", 1), input("G", 1)), List.of(primary("C", 1))));
        return graph(patterns, stock);
    }

    private static void addFibonacciSingleDag(List<CapabilityScenario> out) {
        Map<String, UfoAmount> minimum = fibonacciLeafDemand(SINGLE_DAG_DEPTH);
        LinkedHashMap<String, UfoAmount> starved = new LinkedHashMap<>(minimum);
        starved.put("X0", starved.get("X0").subtract(UfoAmount.of(3)));
        starved.put("X1", starved.get("X1").subtract(UfoAmount.of(5)));
        threeModes(out, "single-dag/fibonacci-depth" + SINGLE_DAG_DEPTH, CapabilityFamily.SINGLE_DAG,
                SINGLE_DAG_DEPTH, "X" + SINGLE_DAG_DEPTH, UfoAmount.ONE, minimum, starved,
                List.of(amounts(Map.of("X0", 3L, "X1", 5L))), true, DAG_BATCHING,
                CapabilityExpectation.REQUIRED, CapabilityCorpus::fibonacciSingle);
    }

    private static CapabilityGraph fibonacciSingle(Map<String, UfoAmount> stock) {
        ArrayList<CapabilityPattern> patterns = new ArrayList<>();
        for (int i = 2; i <= SINGLE_DAG_DEPTH; i++) {
            patterns.add(CapabilityPattern.of("x" + i + "-from-pair",
                    List.of(input("X" + (i - 1), 1), input("X" + (i - 2), 1)), List.of(primary("X" + i, 1))));
        }
        return graph(patterns, stock);
    }

    private static void addSiblingRoutes(List<CapabilityScenario> out) {
        Map<String, UfoAmount> minimum = amounts(Map.of("C", 1L, "D", 1L));
        Map<String, UfoAmount> starved = amounts(Map.of("C", 1L));
        threeModes(out, "multi-dag/sibling-routes", CapabilityFamily.MULTI_DAG, 4, "target",
                UfoAmount.ONE, minimum, starved,
                List.of(amounts(Map.of("D", 1L)), amounts(Map.of("C", 1L))), false, DAG_MULTI_ROUTE,
                CapabilityExpectation.REQUIRED, CapabilityCorpus::siblingRoutes);
    }

    private static CapabilityGraph siblingRoutes(Map<String, UfoAmount> stock) {
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("assemble", List.of(input("A", 1), input("B", 1)),
                        List.of(primary("target", 1))),
                CapabilityPattern.of("a-from-c", List.of(input("C", 1)), List.of(primary("A", 1))),
                CapabilityPattern.of("a-from-d", List.of(input("D", 1)), List.of(primary("A", 1))),
                CapabilityPattern.of("b-from-c", List.of(input("C", 1)), List.of(primary("B", 1))));
        return graph(patterns, stock);
    }

    private static void addFibonacciMultiDag(List<CapabilityScenario> out) {
        Map<String, UfoAmount> minimum = multiFibonacciMinimum(MULTI_DAG_DEPTH);
        threeModes(out, "multi-dag/fibonacci-depth" + MULTI_DAG_DEPTH, CapabilityFamily.MULTI_DAG,
                MULTI_DAG_DEPTH, "X" + MULTI_DAG_DEPTH, UfoAmount.ONE, minimum, Map.of(),
                List.of(minimum), false, DAG_MULTI_ROUTE,                CapabilityExpectation.REQUIRED, CapabilityCorpus::fibonacciMulti);
    }

    private static CapabilityGraph fibonacciMulti(Map<String, UfoAmount> stock) {
        ArrayList<CapabilityPattern> patterns = new ArrayList<>();
        for (int i = 3; i <= MULTI_DAG_DEPTH; i++) {
            patterns.add(CapabilityPattern.of("x" + i + "-from-narrow",
                    List.of(input("X" + (i - 1), 1), input("X" + (i - 2), 1)), List.of(primary("X" + i, 1))));
            patterns.add(CapabilityPattern.of("x" + i + "-from-wide",
                    List.of(input("X" + (i - 2), 1), input("X" + (i - 3), 1)), List.of(primary("X" + i, 1))));
        }
        return graph(patterns, stock);
    }

    /**
     * The reference trap registers the shared material before the independent one, so consuming it
     * for one branch starves the other. The number of independent conflicts is documented above.
     */
    private static void addGreedyTrap(List<CapabilityScenario> out) {
        LinkedHashMap<String, UfoAmount> minimum = new LinkedHashMap<>();
        LinkedHashMap<String, UfoAmount> starved = new LinkedHashMap<>();
        LinkedHashMap<String, UfoAmount> allShared = new LinkedHashMap<>();
        LinkedHashMap<String, UfoAmount> allIndependent = new LinkedHashMap<>();
        for (int group = 0; group < GREEDY_TRAP_GROUPS; group++) {
            String suffix = String.format(java.util.Locale.ROOT, "%02d", group);
            minimum.put("shared_" + suffix, UfoAmount.ONE);
            minimum.put("independent_" + suffix, UfoAmount.ONE);
            starved.put("shared_" + suffix, UfoAmount.ONE);
            allShared.put("shared_" + suffix, UfoAmount.ONE);
            allIndependent.put("independent_" + suffix, UfoAmount.ONE);
        }
        List<CapabilityPattern> patterns = greedyTrapPatterns();
        threeModes(out, "multi-dag/greedy-trap", CapabilityFamily.MULTI_DAG, GREEDY_TRAP_GROUPS,
                "trap_target", UfoAmount.ONE, minimum, starved,
                List.of(allShared, allIndependent), false, DAG_MULTI_ROUTE,
                CapabilityExpectation.REQUIRED, stock -> new CapabilityGraph(patterns, consumable(stock)));
    }

    private static List<CapabilityPattern> greedyTrapPatterns() {
        ArrayList<CapabilityPattern> patterns = new ArrayList<>();
        ArrayList<CapabilityInput> outputs = new ArrayList<>();
        for (int group = 0; group < GREEDY_TRAP_GROUPS; group++) {
            String suffix = String.format(java.util.Locale.ROOT, "%02d", group);
            patterns.add(CapabilityPattern.of("branch_" + suffix,
                    List.of(input("shared_" + suffix, 1)), List.of(primary("left_" + suffix, 1))));
            // The shared material is registered first, so choosing it for the right branch is the trap.
            patterns.add(CapabilityPattern.of("right-a_" + suffix,
                    List.of(input("shared_" + suffix, 1)), List.of(primary("right_" + suffix, 1))));
            patterns.add(CapabilityPattern.of("right-b_" + suffix,
                    List.of(input("independent_" + suffix, 1)), List.of(primary("right_" + suffix, 1))));
            outputs.add(input("left_" + suffix, 1));
            outputs.add(input("right_" + suffix, 1));
        }
        patterns.add(CapabilityPattern.of("trap-assemble", outputs, List.of(primary("trap_target", 1))));
        return List.copyOf(patterns);
    }

    private static void addBatching(List<CapabilityScenario> out) {
        Map<String, UfoAmount> minimum = amounts(Map.of("ingot", 9L));
        Map<String, UfoAmount> starved = amounts(Map.of("ingot", 8L));
        threeModes(out, "batching/multi-output", CapabilityFamily.BATCHING, 3, "plate", UfoAmount.of(5),
                minimum, starved, List.of(amounts(Map.of("ingot", 1L))), true, DAG_BATCHING,
                CapabilityExpectation.REQUIRED, CapabilityCorpus::batching);
    }

    private static CapabilityGraph batching(Map<String, UfoAmount> stock) {
        List<CapabilityPattern> patterns = List.of(CapabilityPattern.of("plate-from-ingots",
                List.of(input("ingot", 3)),
                List.of(primary("plate", 2), byproduct("dust", 1))));
        return graph(patterns, stock);
    }

    private static void addSharedCoproduct(List<CapabilityScenario> out) {
        Map<String, UfoAmount> minimum = amounts(Map.of("raw_a", 1L, "raw_b", 1L));
        Map<String, UfoAmount> starved = amounts(Map.of("raw_a", 1L));
        threeModes(out, "byproduct/shared-coproduct", CapabilityFamily.BYPRODUCT, 3, "assembled",
                UfoAmount.ONE, minimum, starved, List.of(amounts(Map.of("raw_b", 1L))), true,
                DAG_BYPRODUCT_MULTI_ROUTE, CapabilityExpectation.REQUIRED,
                CapabilityCorpus::sharedCoproduct);
    }

    private static CapabilityGraph sharedCoproduct(Map<String, UfoAmount> stock) {
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("piece-a", List.of(input("raw_a", 1)),
                        List.of(primary("part_a", 1), byproduct("coupler", 1))),
                CapabilityPattern.of("piece-b", List.of(input("raw_b", 1)),
                        List.of(primary("part_b", 1), byproduct("coupler", 1))),
                CapabilityPattern.of("assembled", List.of(input("part_a", 1), input("part_b", 1), input("coupler", 1)),
                        List.of(primary("assembled", 1))));
        return graph(patterns, stock);
    }

    private static void addCoproductFeedsLaterStage(List<CapabilityScenario> out) {
        Map<String, UfoAmount> minimum = amounts(Map.of("ore", 1L));
        Map<String, UfoAmount> starved = Map.of();
        threeModes(out, "byproduct/feeds-later-stage", CapabilityFamily.BYPRODUCT, 3, "finished",
                UfoAmount.ONE, minimum, starved, List.of(amounts(Map.of("ore", 1L))), true,
                DAG_BYPRODUCT_MULTI_ROUTE, CapabilityExpectation.REQUIRED,
                CapabilityCorpus::coproductChain);
    }

    private static CapabilityGraph coproductChain(Map<String, UfoAmount> stock) {
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("refine", List.of(input("ore", 1)),
                        List.of(primary("bloom", 1), byproduct("slag", 1))),
                CapabilityPattern.of("draw", List.of(input("bloom", 1)),
                        List.of(primary("wire", 1), byproduct("slag", 1))),
                CapabilityPattern.of("finished", List.of(input("wire", 1), input("slag", 2)),
                        List.of(primary("finished", 1))));
        return graph(patterns, stock);
    }

    private static void addDeepChain(List<CapabilityScenario> out) {
        Map<String, UfoAmount> minimum = amounts(Map.of("k0", 1L));
        threeModes(out, "deep-chain/linear-" + DEEP_CHAIN_DEPTH, CapabilityFamily.DEEP_CHAIN,
                DEEP_CHAIN_DEPTH, "k" + DEEP_CHAIN_DEPTH, UfoAmount.ONE, minimum, Map.of(),
                List.of(amounts(Map.of("k0", 1L))), true, DAG,
                CapabilityExpectation.REQUIRED, CapabilityCorpus::deepChain);
    }

    private static CapabilityGraph deepChain(Map<String, UfoAmount> stock) {
        ArrayList<CapabilityPattern> patterns = new ArrayList<>(DEEP_CHAIN_DEPTH);
        for (int i = 1; i <= DEEP_CHAIN_DEPTH; i++) {
            patterns.add(CapabilityPattern.of("link" + i, List.of(input("k" + (i - 1), 1)),
                    List.of(primary("k" + i, 1))));
        }
        return graph(patterns, stock);
    }

    // ---------------------------------------------------------------- declared limitations

    private static void addConversionRing(List<CapabilityScenario> out) {
        Set<CapabilitySemantics> semantics = Set.of(CapabilitySemantics.CONVERSION_CYCLE);
        Map<String, UfoAmount> minimum = amounts(Map.of("A", 2L));
        Map<String, UfoAmount> starved = amounts(Map.of("A", 1L));
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("t-from-ac", List.of(input("A", 1), input("C", 1)),
                        List.of(primary("T", 1))),
                CapabilityPattern.of("a-from-b", List.of(input("B", 9)), List.of(primary("A", 1))),
                CapabilityPattern.of("b-from-a", List.of(input("A", 1)), List.of(primary("B", 9))),
                CapabilityPattern.of("b-from-c", List.of(input("C", 9)), List.of(primary("B", 1))),
                CapabilityPattern.of("c-from-b", List.of(input("B", 1)), List.of(primary("C", 9))));
        threeModes(out, "cycle/conversion-ring", CapabilityFamily.CONVERSION_CYCLE, 3, "T",
                UfoAmount.ONE, minimum, starved,
                List.of(amounts(Map.of("A", 1L)), amounts(Map.of("C", 1L))), false, semantics,
                CapabilityExpectation.REQUIRED, stock -> new CapabilityGraph(patterns, consumable(stock)));
    }

    /**
     * The target has a deterministic route and a chance route, and only {@code raw} is in stock. A
     * planner that counts a chance output as an output answers this from {@code raw} and calls it
     * complete; the guaranteed answer is that eight {@code ore} are missing, because a chance route
     * promises nothing to a deterministic request.
     */
    /**
     * The same secondary-demand shape reached through a recipe that also carries a catalyst. The
     * fallback route is new and the exotic input kinds are not, so this is where they meet: a catalyst
     * is present once and handed back no matter how many times its recipe fires, including the extra
     * firings that exist only to yield the secondary.
     */
    /**
     * A durable carrier survives a fixed number of firings, and the secondary-demand shape fires the
     * same recipe twice: once for the primary it is asked for and again for the secondary it is chased
     * for. A carrier budget divided between the two expansions instead of across the whole plan looks
     * like a shortage of tools where two are enough for four firings.
     */
    /**
     * A catalyst and a durable carrier on the same recipe, and the recipe is expanded twice because a
     * secondary output is chased separately. Both kinds of carrier are now shared across the plan, and
     * this is where the two sharing rules meet: the catalyst is present once, while the tool budget is
     * divided by the total firings.
     */
    /**
     * A key that a recipe declares, and a recipe that makes nine of it per firing as a secondary. The
     * declared route needs five ore; the secondary needs one scrap and comes out alongside dust. A
     * secondary output used to be consulted only when nothing declared the key, so the expensive route
     * was taken and the request was reported short while a single scrap would have covered it.
     */
    private static void addSecondaryOutbidsDeclared(List<CapabilityScenario> out) {
        Set<CapabilitySemantics> semantics = Set.of(CapabilitySemantics.DETERMINISTIC_EXACT_DAG,
                CapabilitySemantics.DETERMINISTIC_BYPRODUCT, CapabilitySemantics.MULTI_ROUTE);
        Map<String, UfoAmount> minimum = amounts(Map.of("scrap", 1L));
        threeModes(out, "byproduct/secondary-outbids-declared", CapabilityFamily.BYPRODUCT, 9, "widget",
                UfoAmount.ONE, minimum, Map.of(), List.of(amounts(Map.of("scrap", 1L))), true,
                semantics, CapabilityExpectation.REQUIRED, CapabilityCorpus::secondaryOutbidsDeclared);
    }

    private static CapabilityGraph secondaryOutbidsDeclared(Map<String, UfoAmount> stock) {
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("widget-declared", List.of(input("ore", 5)),
                        List.of(primary("widget", 1))),
                CapabilityPattern.of("widget-by-secondary", List.of(input("scrap", 1)),
                        List.of(primary("dust", 1), byproduct("widget", 9))));
        return graph(patterns, stock);
    }

    private static void addCatalystWithCarrier(List<CapabilityScenario> out) {
        Set<CapabilitySemantics> semantics = Set.of(CapabilitySemantics.DETERMINISTIC_EXACT_DAG,
                CapabilitySemantics.DETERMINISTIC_BYPRODUCT, CapabilitySemantics.REUSABLE_INPUT,
                CapabilitySemantics.FINITE_DURABILITY);
        Map<String, UfoAmount> minimum = amounts(Map.of("ore", 4L, "tool", 2L, "catalyst", 1L));
        Map<String, UfoAmount> starved = amounts(Map.of("ore", 4L));
        threeModes(out, "durability/catalyst-and-carrier", CapabilityFamily.FINITE_DURABILITY, 2,
                "finished", UfoAmount.ONE, minimum, starved,
                List.of(amounts(Map.of("tool", 2L, "catalyst", 1L))), true, semantics,
                CapabilityExpectation.REQUIRED, CapabilityCorpus::catalystAndCarrier);
    }

    private static CapabilityGraph catalystAndCarrier(Map<String, UfoAmount> stock) {
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("refine",
                        List.of(CapabilityInput.reusable("catalyst", 1, "refine"), input("ore", 1),
                                CapabilityInput.finiteUse("tool", 1, 2)),
                        List.of(primary("bloom", 1), byproduct("slag", 1))),
                CapabilityPattern.of("assemble", List.of(input("bloom", 1), input("slag", 4)),
                        List.of(primary("finished", 1))));
        return graph(patterns, stock);
    }

    /**
     * A fuzzy slot and a secondary output on the same recipe, expanded twice for the same reason. The
     * slot accepts a damaged tool, so presence is the total across what it accepts, and the recipe must
     * not be charged for the tool again just because it ran twice.
     */
    private static void addFuzzyWithSecondary(List<CapabilityScenario> out) {
        Set<CapabilitySemantics> semantics = Set.of(CapabilitySemantics.FUZZY_ALTERNATIVES,
                CapabilitySemantics.DETERMINISTIC_BYPRODUCT);
        String host = "polish-host";
        List<String> variants = List.of("logical_tool", "damaged_tool");
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("polish",
                        List.of(CapabilityInput.fuzzy("logical_tool", 1, host, variants), input("ingot", 1)),
                        List.of(primary("plate", 1), byproduct("filings", 1))),
                CapabilityPattern.of("assemble", List.of(input("plate", 1), input("filings", 4)),
                        List.of(primary("gear", 1))));
        Map<String, UfoAmount> unboundedIngots = amounts(Map.of("ingot", UNBOUNDED.asBigInteger().longValue()));
        String id = "fuzzy/secondary-with-variant";
        addScenario(out, id, CapabilityFamily.FUZZY_VARIANT, 4, CapabilityMaterialMode.MISSING,
                fuzzySecondaryGraph(patterns, host, amounts(Map.of("ingot", 4L)), Map.of()), "gear",
                UfoAmount.ONE, false, List.of(amounts(Map.of("logical_tool", 1L))), semantics,
                CapabilityExpectation.REQUIRED,
                // Exactly the reported shortage and nothing else: handing the refill a damaged tool as
                // well would let it pass on a variant the report never asked for, which is the check
                // this path exists to make.
                supplied -> fuzzySecondaryGraph(patterns, host, amounts(Map.of("ingot", 4L)), supplied));
        addScenario(out, id, CapabilityFamily.FUZZY_VARIANT, 4, CapabilityMaterialMode.MINIMUM,
                fuzzySecondaryGraph(patterns, host, amounts(Map.of("ingot", 4L)),
                        amounts(Map.of("damaged_tool", 1L))), "gear", UfoAmount.ONE, true, List.of(),
                semantics, CapabilityExpectation.REQUIRED,
                supplied -> fuzzySecondaryGraph(patterns, host, amounts(Map.of("ingot", 4L)),
                        merge(amounts(Map.of("damaged_tool", 1L)), supplied)));
        addScenario(out, id, CapabilityFamily.FUZZY_VARIANT, 4, CapabilityMaterialMode.UNBOUNDED,
                fuzzySecondaryGraph(patterns, host, unboundedIngots,
                        amounts(Map.of("damaged_tool", UNBOUNDED.asBigInteger().longValue()))),
                "gear", UfoAmount.ONE, true, List.of(), semantics, CapabilityExpectation.REQUIRED,
                supplied -> fuzzySecondaryGraph(patterns, host, unboundedIngots,
                        merge(amounts(Map.of("damaged_tool", UNBOUNDED.asBigInteger().longValue())),
                                supplied)));
    }

    private static CapabilityGraph fuzzySecondaryGraph(List<CapabilityPattern> patterns, String host,
                                                       Map<String, UfoAmount> consumables,
                                                       Map<String, UfoAmount> reusable) {
        LinkedHashMap<String, CapabilityGraph.CapabilityStock> entries =
                new LinkedHashMap<>(consumable(consumables));
        reusable.forEach((key, value) -> {
            if (!value.isZero()) {
                entries.put(key, new CapabilityGraph.CapabilityStock(value,
                        CapabilityGraph.CapabilityStock.Kind.REUSABLE, host));
            }
        });
        return new CapabilityGraph(patterns, entries);
    }

    private static void addDurabilityAcrossExpansions(List<CapabilityScenario> out) {
        Set<CapabilitySemantics> semantics = Set.of(CapabilitySemantics.DETERMINISTIC_EXACT_DAG,
                CapabilitySemantics.DETERMINISTIC_BYPRODUCT, CapabilitySemantics.FINITE_DURABILITY);
        Map<String, UfoAmount> minimum = amounts(Map.of("ore", 4L, "tool", 2L));
        Map<String, UfoAmount> starved = amounts(Map.of("ore", 4L));
        threeModes(out, "durability/reuse-across-expansions", CapabilityFamily.FINITE_DURABILITY, 2,
                "finished", UfoAmount.ONE, minimum, starved,
                List.of(amounts(Map.of("tool", 2L))), true, semantics,
                CapabilityExpectation.REQUIRED, CapabilityCorpus::durabilityAcrossExpansions);
    }

    private static CapabilityGraph durabilityAcrossExpansions(Map<String, UfoAmount> stock) {
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("refine",
                        List.of(input("ore", 1), CapabilityInput.finiteUse("tool", 1, 2)),
                        List.of(primary("bloom", 1), byproduct("slag", 1))),
                CapabilityPattern.of("assemble", List.of(input("bloom", 1), input("slag", 4)),
                        List.of(primary("finished", 1))));
        return graph(patterns, stock);
    }

    private static void addSecondaryThroughCatalyst(List<CapabilityScenario> out) {
        Set<CapabilitySemantics> semantics = Set.of(CapabilitySemantics.DETERMINISTIC_EXACT_DAG,
                CapabilitySemantics.DETERMINISTIC_BYPRODUCT, CapabilitySemantics.REUSABLE_INPUT);
        Map<String, UfoAmount> minimum = amounts(Map.of("ore", 4L, "catalyst", 1L));
        threeModes(out, "catalyst/secondary-through-catalyst", CapabilityFamily.REUSABLE_CATALYST, 4,
                "finished", UfoAmount.ONE, minimum, Map.of(),
                List.of(amounts(Map.of("ore", 4L, "catalyst", 1L))), true, semantics,
                CapabilityExpectation.REQUIRED, CapabilityCorpus::secondaryThroughCatalyst);
    }

    private static CapabilityGraph secondaryThroughCatalyst(Map<String, UfoAmount> stock) {
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("refine",
                        List.of(CapabilityInput.reusable("catalyst", 1, "refine"), input("ore", 1)),
                        List.of(primary("bloom", 1), byproduct("slag", 1))),
                CapabilityPattern.of("assemble", List.of(input("bloom", 1), input("slag", 4)),
                        List.of(primary("finished", 1))));
        return graph(patterns, stock);
    }

    /**
     * The target needs four of a secondary output but only one of the primary. Collecting the
     * secondary only when the primary is wanted for its own sake yields one, so the producing pattern
     * has to be fired three more times for the secondary alone. A planner that treats a secondary
     * output as unrequestable reports the other three as impossible to obtain.
     */
    private static void addSurplusSecondaryDemand(List<CapabilityScenario> out) {
        Map<String, UfoAmount> minimum = amounts(Map.of("ore", 4L));
        threeModes(out, "byproduct/surplus-secondary-demand", CapabilityFamily.BYPRODUCT, 4, "finished",
                UfoAmount.ONE, minimum, Map.of(), List.of(amounts(Map.of("ore", 4L))), true,
                DAG_BYPRODUCT_MULTI_ROUTE, CapabilityExpectation.REQUIRED,
                CapabilityCorpus::surplusSecondaryChain);
    }

    private static CapabilityGraph surplusSecondaryChain(Map<String, UfoAmount> stock) {
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("refine", List.of(input("ore", 1)),
                        List.of(primary("bloom", 1), byproduct("slag", 1))),
                CapabilityPattern.of("assemble", List.of(input("bloom", 1), input("slag", 4)),
                        List.of(primary("finished", 1))));
        return graph(patterns, stock);
    }

    private static void addChanceRoute(List<CapabilityScenario> out) {
        Set<CapabilitySemantics> semantics = Set.of(CapabilitySemantics.PROBABILISTIC_OUTPUT);
        Map<String, UfoAmount> minimum = amounts(Map.of("raw", 8L, "ore", 8L));
        Map<String, UfoAmount> starved = amounts(Map.of("raw", 8L));
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("gem-from-ore", List.of(input("ore", 1)),
                        List.of(primary("gem", 1))),
                // A recipe has to declare something it deterministically makes, so the chance route
                // also yields dross. The gem it sometimes gives is still not a promise.
                CapabilityPattern.of("gem-by-chance", List.of(input("raw", 1)),
                        List.of(primary("dross", 1), CapabilityOutput.probabilistic("gem", 1))));
        threeModes(out, "probabilistic/chance-route", CapabilityFamily.PROBABILISTIC_OUTPUT, 8, "gem",
                UfoAmount.of(8), minimum, starved, List.of(amounts(Map.of("ore", 8L))), true, semantics,
                CapabilityExpectation.REQUIRED, stock -> new CapabilityGraph(patterns, consumable(stock)));
    }

    private static void addSelfGrowth(List<CapabilityScenario> out) {
        Set<CapabilitySemantics> semantics = Set.of(CapabilitySemantics.POSITIVE_FEEDBACK);
        Map<String, UfoAmount> minimum = amounts(Map.of("A", 1L));
        List<CapabilityPattern> patterns = List.of(CapabilityPattern.of("a-grows",
                List.of(input("A", 1)), List.of(primary("A", 2))));
        threeModes(out, "cycle/self-growth", CapabilityFamily.POSITIVE_FEEDBACK, 8, "A", UfoAmount.of(8),
                minimum, Map.of(), List.of(amounts(Map.of("A", 1L))), true, semantics,
                CapabilityExpectation.REQUIRED, stock -> new CapabilityGraph(patterns, consumable(stock)));
    }

    private static void addRawFeedbackLoop(List<CapabilityScenario> out) {
        Set<CapabilitySemantics> semantics = Set.of(CapabilitySemantics.CONSERVATIVE_FEEDBACK);
        Map<String, UfoAmount> minimum = amounts(Map.of("A", 1L, "C", 8L));
        Map<String, UfoAmount> starved = amounts(Map.of("C", 8L));
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("b-from-a", List.of(input("A", 1)), List.of(primary("B", 2))),
                CapabilityPattern.of("e-from-bc", List.of(input("B", 2), input("C", 1)),
                        List.of(primary("E", 1), byproduct("D", 1))),
                CapabilityPattern.of("a-from-d", List.of(input("D", 1)), List.of(primary("A", 1))));
        threeModes(out, "catalyst/raw-feedback-loop", CapabilityFamily.CONSERVATIVE_FEEDBACK, 8, "E",
                UfoAmount.of(8), minimum, starved,
                List.of(amounts(Map.of("A", 1L)), amounts(Map.of("D", 1L))), false, semantics,
                CapabilityExpectation.REQUIRED, stock -> new CapabilityGraph(patterns, consumable(stock)));
    }

    private static void addLossyFeedbackLoop(List<CapabilityScenario> out) {
        Set<CapabilitySemantics> semantics = Set.of(CapabilitySemantics.LOSSY_FEEDBACK);
        Map<String, UfoAmount> minimum = amounts(Map.of("A", 10L));
        Map<String, UfoAmount> starved = amounts(Map.of("A", 8L));
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("b-from-a", List.of(input("A", 3)), List.of(primary("B", 2))),
                CapabilityPattern.of("d-from-b", List.of(input("B", 2)),
                        List.of(primary("D", 1), byproduct("A", 2))));
        threeModes(out, "catalyst/lossy-feedback-loop", CapabilityFamily.LOSSY_FEEDBACK, 8, "D",
                UfoAmount.of(8), minimum, starved, List.of(amounts(Map.of("A", 2L))), true, semantics,
                CapabilityExpectation.REQUIRED, stock -> new CapabilityGraph(patterns, consumable(stock)));
    }

    private static void addReturnedCatalyst(List<CapabilityScenario> out) {
        Set<CapabilitySemantics> semantics = Set.of(CapabilitySemantics.REUSABLE_INPUT);
        String host = "catalyst-host";
        List<CapabilityPattern> patterns = List.of(CapabilityPattern.of("e-from-catalyst",
                List.of(CapabilityInput.reusable("A", 1, host), input("C", 1)),
                List.of(primary("E", 1))));
        Map<String, UfoAmount> seed = amounts(Map.of("A", 1L));
        Map<String, UfoAmount> consumables = amounts(Map.of("C", 1_000L));
        addScenario(out, "catalyst/returned-seed", CapabilityFamily.REUSABLE_CATALYST, 1_000,
                CapabilityMaterialMode.MISSING, catalystGraph(patterns, host, Map.of(), consumables),
                "E", UfoAmount.of(1_000), false, List.of(seed), semantics,
                CapabilityExpectation.REQUIRED,
                supplied -> catalystGraph(patterns, host, supplied, consumables));
        addScenario(out, "catalyst/returned-seed", CapabilityFamily.REUSABLE_CATALYST, 1_000,
                CapabilityMaterialMode.MINIMUM, catalystGraph(patterns, host, seed, consumables),
                "E", UfoAmount.of(1_000), true, List.of(), semantics,
                CapabilityExpectation.REQUIRED,
                supplied -> catalystGraph(patterns, host, merge(seed, supplied), consumables));
        addScenario(out, "catalyst/returned-seed", CapabilityFamily.REUSABLE_CATALYST, 1_000,
                CapabilityMaterialMode.UNBOUNDED,
                catalystGraph(patterns, host, Map.of("A", UNBOUNDED), Map.of("C", UNBOUNDED)),
                "E", UfoAmount.of(1_000), true, List.of(), semantics,
                CapabilityExpectation.REQUIRED,
                supplied -> catalystGraph(patterns, host, merge(Map.of("A", UNBOUNDED), supplied),
                        Map.of("C", UNBOUNDED)));
    }

    private static CapabilityGraph catalystGraph(List<CapabilityPattern> patterns, String host,
                                                 Map<String, UfoAmount> reusableSeed,
                                                 Map<String, UfoAmount> consumables) {
        LinkedHashMap<String, CapabilityGraph.CapabilityStock> entries = new LinkedHashMap<>();
        reusableSeed.forEach((key, value) -> {
            if (!value.isZero()) {
                entries.put(key, new CapabilityGraph.CapabilityStock(value,
                        CapabilityGraph.CapabilityStock.Kind.REUSABLE, host));
            }
        });
        consumables.forEach((key, value) -> {
            if (!value.isZero()) {
                entries.put(key, CapabilityGraph.CapabilityStock.consumable(value));
            }
        });
        return new CapabilityGraph(patterns, entries);
    }

    private static void addFiniteDurability(List<CapabilityScenario> out) {
        Set<CapabilitySemantics> semantics = Set.of(CapabilitySemantics.FINITE_DURABILITY);
        int uses = 100;
        UfoAmount produced = UfoAmount.of(10_000);
        List<CapabilityPattern> patterns = List.of(CapabilityPattern.of("product-from-tool",
                List.of(input("raw", 1), CapabilityInput.finiteUse("tool", 1, uses)),
                List.of(primary("product", 1))));
        Map<String, UfoAmount> minimum = amounts(Map.of("raw", 10_000L, "tool", 100L));
        Map<String, UfoAmount> starved = amounts(Map.of("raw", 10_000L, "tool", 99L));
        threeModes(out, "durability/finite-use-chain", CapabilityFamily.FINITE_DURABILITY, uses,
                "product", produced, minimum, starved, List.of(amounts(Map.of("tool", 1L))), true,
                semantics, CapabilityExpectation.REQUIRED,
                stock -> new CapabilityGraph(patterns, consumable(stock)));
    }

    private static void addFuzzyVariant(List<CapabilityScenario> out) {
        Set<CapabilitySemantics> semantics = Set.of(CapabilitySemantics.FUZZY_ALTERNATIVES);
        String host = "fuzzy-host";
        List<String> variants = List.of("logical_tool", "damaged_tool");
        List<CapabilityPattern> patterns = List.of(CapabilityPattern.of("product-from-logical-tool",
                List.of(CapabilityInput.fuzzy("logical_tool", 1, host, variants)),
                List.of(primary("product", 1))));
        Map<String, UfoAmount> minimum = Map.of("damaged_tool", UfoAmount.ONE);
        Map<String, UfoAmount> unbounded = Map.of("damaged_tool", UNBOUNDED);
        addScenario(out, "fuzzy/variant-route", CapabilityFamily.FUZZY_VARIANT, 1_000,
                CapabilityMaterialMode.MISSING, fuzzyGraph(patterns, host, Map.of()), "product",
                UfoAmount.of(1_000), false, List.of(amounts(Map.of("logical_tool", 1L))), semantics,
                CapabilityExpectation.REQUIRED,
                supplied -> fuzzyGraph(patterns, host, supplied));
        addScenario(out, "fuzzy/variant-route", CapabilityFamily.FUZZY_VARIANT, 1_000,
                CapabilityMaterialMode.MINIMUM, fuzzyGraph(patterns, host, minimum), "product",
                UfoAmount.of(1_000), true, List.of(), semantics,
                CapabilityExpectation.REQUIRED,
                supplied -> fuzzyGraph(patterns, host, merge(minimum, supplied)));
        addScenario(out, "fuzzy/variant-route", CapabilityFamily.FUZZY_VARIANT, 1_000,
                CapabilityMaterialMode.UNBOUNDED, fuzzyGraph(patterns, host, unbounded), "product",
                UfoAmount.of(1_000), true, List.of(), semantics,
                CapabilityExpectation.REQUIRED,
                supplied -> fuzzyGraph(patterns, host, merge(unbounded, supplied)));
    }

    private static CapabilityGraph fuzzyGraph(List<CapabilityPattern> patterns, String host,
                                              Map<String, UfoAmount> reusableStock) {
        LinkedHashMap<String, CapabilityGraph.CapabilityStock> entries = new LinkedHashMap<>();
        reusableStock.forEach((key, value) -> {
            if (!value.isZero()) {
                entries.put(key, new CapabilityGraph.CapabilityStock(value,
                        CapabilityGraph.CapabilityStock.Kind.REUSABLE, host));
            }
        });
        return new CapabilityGraph(patterns, entries);
    }

    private static void addEmitter(List<CapabilityScenario> out) {
        Set<CapabilitySemantics> semantics = Set.of(CapabilitySemantics.EMITTER);
        List<CapabilityPattern> patterns = List.of(CapabilityPattern.of("product-from-emitter",
                List.of(CapabilityInput.emitter("field_flux", 10, "authorized-source"), input("ore", 1)),
                List.of(primary("product", 1))));
        Map<String, UfoAmount> minimum = amounts(Map.of("ore", 100L));
        Map<String, UfoAmount> starved = amounts(Map.of("ore", 50L));
        threeModes(out, "emitter/authorized-stream", CapabilityFamily.EMITTER, 100, "product",
                UfoAmount.of(100), minimum, starved, List.of(amounts(Map.of("ore", 50L))), true,
                semantics, CapabilityExpectation.REQUIRED,
                stock -> new CapabilityGraph(patterns, consumable(stock)));
    }

    // ---------------------------------------------------------------- builder helpers

    private static void threeModes(List<CapabilityScenario> out, String id, CapabilityFamily family,
                                   int scale, String target, UfoAmount amount,
                                   Map<String, UfoAmount> minimum, Map<String, UfoAmount> starved,
                                   List<Map<String, UfoAmount>> minimalMissing, boolean uniqueMinimum,
                                   Set<CapabilitySemantics> semantics, CapabilityExpectation expectation,
                                   Function<Map<String, UfoAmount>, CapabilityGraph> factory) {
        addScenario(out, id, family, scale, CapabilityMaterialMode.MISSING, factory.apply(starved), target,
                amount, false, minimalMissing, semantics, expectation,
                supplied -> factory.apply(merge(starved, supplied)));
        addScenario(out, id, family, scale, CapabilityMaterialMode.MINIMUM, factory.apply(minimum), target,
                amount, true, List.of(), semantics, expectation,
                supplied -> factory.apply(merge(minimum, supplied)));
        LinkedHashMap<String, UfoAmount> unbounded = new LinkedHashMap<>();
        minimum.keySet().forEach(key -> unbounded.put(key, UNBOUNDED));
        addScenario(out, id, family, scale, CapabilityMaterialMode.UNBOUNDED, factory.apply(unbounded),
                target, amount, true, List.of(), semantics, expectation,
                supplied -> factory.apply(merge(unbounded, supplied)));
        if (uniqueMinimum && minimalMissing.size() != 1) {
            throw new IllegalStateException(id + " declares a unique minimum without one witness");
        }
    }

    private static void addScenario(List<CapabilityScenario> out, String id, CapabilityFamily family,
                                    int scale, CapabilityMaterialMode mode, CapabilityGraph graph,
                                    String target, UfoAmount amount, boolean feasible,
                                    List<Map<String, UfoAmount>> minimalMissing,
                                    Set<CapabilitySemantics> semantics, CapabilityExpectation expectation,
                                    Function<Map<String, UfoAmount>, CapabilityGraph> refill) {
        addScenario(out, id, family, scale, mode, graph, target, amount, feasible, minimalMissing,
                Map.of(), semantics, expectation, refill);
    }

    private static void addScenario(List<CapabilityScenario> out, String id, CapabilityFamily family,
                                    int scale, CapabilityMaterialMode mode, CapabilityGraph graph,
                                    String target, UfoAmount amount, boolean feasible,
                                    List<Map<String, UfoAmount>> minimalMissing,
                                    Map<String, Double> weights,
                                    Set<CapabilitySemantics> semantics, CapabilityExpectation expectation,
                                    Function<Map<String, UfoAmount>, CapabilityGraph> refill) {
        out.add(new CapabilityScenario(id, family, mode, scale, graph, target, amount, feasible,
                minimalMissing, weights, minimalMissing.size() == 1, semantics, expectation, refill));
    }

    /**
     * The same three material modes, with weights declared. A group only needs this when the cheapest
     * thing to leave short in units is not the cheapest to leave short in value.
     */
    private static void weightedThreeModes(List<CapabilityScenario> out, String id,
                                           CapabilityFamily family, int scale, String target,
                                           UfoAmount amount, Map<String, UfoAmount> minimum,
                                           Map<String, UfoAmount> starved,
                                           List<Map<String, UfoAmount>> minimalMissing,
                                           Map<String, Double> weights,
                                           Set<CapabilitySemantics> semantics,
                                           CapabilityExpectation expectation,
                                           Function<Map<String, UfoAmount>, CapabilityGraph> factory) {
        addScenario(out, id, family, scale, CapabilityMaterialMode.MISSING, factory.apply(starved), target,
                amount, false, minimalMissing, weights, semantics, expectation,
                supplied -> factory.apply(merge(starved, supplied)));
        addScenario(out, id, family, scale, CapabilityMaterialMode.MINIMUM, factory.apply(minimum), target,
                amount, true, List.of(), weights, semantics, expectation,
                supplied -> factory.apply(merge(minimum, supplied)));
        LinkedHashMap<String, UfoAmount> unbounded = new LinkedHashMap<>();
        minimum.keySet().forEach(key -> unbounded.put(key, UNBOUNDED));
        addScenario(out, id, family, scale, CapabilityMaterialMode.UNBOUNDED, factory.apply(unbounded),
                target, amount, true, List.of(), weights, semantics, expectation,
                supplied -> factory.apply(merge(unbounded, supplied)));
    }

    /**
     * Two routes to the same key, one missing unit each, and the cheaper material named so its route
     * sorts first. In units the two are identical, so the identifier decided, and the plan could ask
     * for the valuable material to be supplied when the cheap one would have done. The weights are what
     * tells the two apart, and nothing else can.
     */
    private static void addWeightedShortage(List<CapabilityScenario> out) {
        Map<String, UfoAmount> minimum = amounts(Map.of("cheap", 1L, "gold", 1L));
        weightedThreeModes(out, "multi-dag/weighted-shortage", CapabilityFamily.MULTI_DAG, 2, "widget",
                UfoAmount.ONE, minimum, Map.of(),
                List.of(amounts(Map.of("cheap", 1L)), amounts(Map.of("gold", 1L))),
                Map.of("cheap", 1.0D, "gold", 100.0D), DAG_MULTI_ROUTE,
                CapabilityExpectation.REQUIRED, CapabilityCorpus::weightedShortageRoutes);
    }

    /**
     * The same two-route tie, with the routes differing in how much of their material they need. The
     * order that compares raw leaf demand runs before the one that compares weighted shortage, so the
     * cheaper route wins on a count of units and the weight never gets consulted.
     */
    private static void addWeightedLeafCost(List<CapabilityScenario> out) {
        Map<String, UfoAmount> minimum = amounts(Map.of("gold", 1L, "cheap", 10L));
        weightedThreeModes(out, "multi-dag/weighted-leaf-cost", CapabilityFamily.MULTI_DAG, 2, "widget",
                UfoAmount.ONE, minimum, Map.of(),
                List.of(amounts(Map.of("gold", 1L)), amounts(Map.of("cheap", 10L))),
                Map.of("gold", 100.0D, "cheap", 1.0D), DAG_MULTI_ROUTE,
                CapabilityExpectation.REQUIRED, CapabilityCorpus::weightedLeafCostRoutes);
    }

    private static CapabilityGraph weightedLeafCostRoutes(Map<String, UfoAmount> stock) {
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("widget-a-gold", List.of(input("gold", 1)),
                        List.of(primary("widget", 1))),
                CapabilityPattern.of("widget-b-cheap", List.of(input("cheap", 10)),
                        List.of(primary("widget", 1))));
        return graph(patterns, stock);
    }

    private static CapabilityGraph weightedShortageRoutes(Map<String, UfoAmount> stock) {
        List<CapabilityPattern> patterns = List.of(
                CapabilityPattern.of("widget-a-gold", List.of(input("gold", 1)),
                        List.of(primary("widget", 1))),
                CapabilityPattern.of("widget-b-cheap", List.of(input("cheap", 1)),
                        List.of(primary("widget", 1))));
        return graph(patterns, stock);
    }

    private static CapabilityOutput primary(String key, long amount) {
        return CapabilityOutput.primary(key, amount);
    }

    private static CapabilityInput input(String key, long amount) {
        return CapabilityInput.exact(key, amount);
    }

    private static CapabilityOutput byproduct(String key, long amount) {
        return CapabilityOutput.byproduct(key, amount);
    }

    private static CapabilityGraph graph(List<CapabilityPattern> patterns, Map<String, UfoAmount> stock) {
        return new CapabilityGraph(patterns, consumable(stock));
    }

    private static Map<String, CapabilityGraph.CapabilityStock> consumable(Map<String, UfoAmount> stock) {
        LinkedHashMap<String, CapabilityGraph.CapabilityStock> entries = new LinkedHashMap<>();
        stock.forEach((key, value) -> {
            if (!value.isZero()) {
                entries.put(key, CapabilityGraph.CapabilityStock.consumable(value));
            }
        });
        return entries;
    }

    /**
     * Sorted rather than insertion-ordered, and fed from sorted rather than from {@code Map.of}. The
     * iteration order of {@code Map.of} is randomised per JVM run, so a stock map built from one made
     * the corpus inputs differ between runs, and with them the rendered report. The report is meant to
     * be a frozen artefact, so nothing that feeds it may depend on a salt.
     */
    private static Map<String, UfoAmount> amounts(Map<String, Long> values) {
        TreeMap<String, UfoAmount> result = new TreeMap<>();
        values.forEach((key, value) -> result.put(key, UfoAmount.of(value)));
        return result;
    }

    private static Map<String, UfoAmount> merge(Map<String, UfoAmount> base, Map<String, UfoAmount> additions) {
        LinkedHashMap<String, UfoAmount> merged = new LinkedHashMap<>(base);
        additions.forEach((key, value) -> merged.merge(key, value, UfoAmount::add));
        return merged;
    }

    private static Map<String, UfoAmount> addAmounts(Map<String, UfoAmount> left, Map<String, UfoAmount> right) {
        LinkedHashMap<String, UfoAmount> result = new LinkedHashMap<>();
        left.forEach((key, value) -> result.merge(key, value, UfoAmount::add));
        right.forEach((key, value) -> result.merge(key, value, UfoAmount::add));
        return result;
    }

    private static UfoAmount total(Map<String, UfoAmount> values) {
        return values.values().stream().reduce(UfoAmount.ZERO, UfoAmount::add);
    }

    private static Map<String, UfoAmount> fibonacciLeafDemand(int depth) {
        Map<String, UfoAmount> x0 = amounts(Map.of("X0", 1L));
        Map<String, UfoAmount> x1 = amounts(Map.of("X1", 1L));
        for (int i = 2; i <= depth; i++) {
            Map<String, UfoAmount> next = addAmounts(x0, x1);
            x0 = x1;
            x1 = next;
        }
        return x1;
    }

    private static Map<String, UfoAmount> multiFibonacciMinimum(int depth) {
        ArrayList<Map<String, UfoAmount>> options = new ArrayList<>();
        options.add(amounts(Map.of("X0", 1L)));
        options.add(amounts(Map.of("X1", 1L)));
        options.add(amounts(Map.of("X2", 1L)));
        for (int i = 3; i <= depth; i++) {
            Map<String, UfoAmount> narrow = addAmounts(options.get(i - 1), options.get(i - 2));
            Map<String, UfoAmount> wide = addAmounts(options.get(i - 2), options.get(i - 3));
            Map<String, UfoAmount> next = total(narrow).compareTo(total(wide)) <= 0 ? narrow : wide;
            options.add(next);
        }
        return options.get(depth);
    }

    private static CapabilityOutput primary(String key, UfoAmount amount) {
        return CapabilityOutput.primary(key, amount);
    }
}
