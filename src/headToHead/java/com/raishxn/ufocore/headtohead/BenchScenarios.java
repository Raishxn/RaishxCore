package com.raishxn.ufocore.headtohead;

import com.raishxn.ufocore.api.amount.UfoAmount;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityCorpus;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityExpectation;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityFamily;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityGraph;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityInput;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityMaterialMode;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityOutput;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityPattern;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilityScenario;
import com.raishxn.ufocore.api.crafting.planner.differential.CapabilitySemantics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The shared scenario set: the frozen differential corpus, the regression shapes that motivated the
 * staged planner, and seeded generated graphs in the hundreds-to-thousands of patterns.
 */
final class BenchScenarios {

    private static final Set<CapabilitySemantics> EXACT_SEMANTICS = Set.of(
            CapabilitySemantics.DETERMINISTIC_EXACT_DAG, CapabilitySemantics.DETERMINISTIC_BYPRODUCT,
            CapabilitySemantics.BATCHING, CapabilitySemantics.MULTI_ROUTE);

    static List<BenchCase> all() {
        List<BenchCase> cases = new ArrayList<>();
        for (CapabilityScenario scenario : CapabilityCorpus.all()) {
            cases.add(new BenchCase("corpus/" + scenario.label(), "corpus", scenario, true));
        }
        cases.addAll(regressions());
        cases.addAll(generated());
        return List.copyOf(cases);
    }

    // ------------------------------------------------------------------ regression shapes

    static List<BenchCase> regressions() {
        List<BenchCase> cases = new ArrayList<>();
        for (int uses : new int[] {20, 60, 200}) {
            // A two-route key used in many places with one raw that is genuinely short: the shape of
            // the 58-pattern graph that timed out in play. The only correct shortage is {zrare: 1}.
            CapabilityGraph missingRaw = sharedPlate(uses, "zrare", 2, false,
                    Map.of("ingot", 1_000L, "dust", 1_000L, "zrare", 1L));
            cases.add(new BenchCase(String.format(Locale.ROOT, "regression/shared-plate-%d-missing-raw", uses),
                    "regression", known("shared-plate-missing-raw", missingRaw, "target", 1,
                    Map.of("zrare", 1L)), true));

            // The same shape where greedy picks the ingot route for every plate and starves the rod,
            // which needs every ingot. One route pin on the plate makes it complete.
            CapabilityGraph pin = sharedPlate(uses, "rod", uses, true,
                    Map.of("ingot", (long) uses, "dust", (long) uses));
            cases.add(new BenchCase(String.format(Locale.ROOT, "regression/shared-plate-%d-route-pin", uses),
                    "regression", known("shared-plate-route-pin", pin, "target", 1, Map.of()), true));
        }
        for (int depth : new int[] {11, 20}) {
            // Nested two-route choices over an unobtainable leaf; the exact search used to enumerate
            // all 2^depth combinations to prove the one-unit shortage.
            List<CapabilityPattern> patterns = new ArrayList<>();
            for (int i = 0; i < depth; i++) {
                patterns.add(simple("a-" + i, Map.of("k" + (i + 1), 1L), "k" + i, 1));
                patterns.add(simple("b-" + i, Map.of("k" + (i + 1), 1L), "k" + i, 1));
            }
            cases.add(new BenchCase("regression/nested-choices-" + depth, "regression",
                    known("nested-choices", CapabilityGraph.of(patterns, Map.of()), "k0", 1,
                            Map.of("k" + depth, 1L)), true));
        }
        return cases;
    }

    private static CapabilityGraph sharedPlate(int uses, String finalKey, long finalAmount, boolean rod,
                                               Map<String, Long> stock) {
        List<CapabilityPattern> patterns = new ArrayList<>();
        patterns.add(simple("plate-a", Map.of("ingot", 1L), "plate", 1));
        patterns.add(simple("plate-b", Map.of("dust", 1L), "plate", 1));
        Map<String, Long> top = new LinkedHashMap<>();
        for (int i = 0; i < uses; i++) {
            String part = String.format(Locale.ROOT, "part%03d", i);
            patterns.add(simple("make-" + part, Map.of("plate", 1L), part, 1));
            top.put(part, 1L);
        }
        top.put(finalKey, finalAmount);
        patterns.add(simple("assemble", top, "target", 1));
        if (rod) patterns.add(simple("rod", Map.of("ingot", 1L), "rod", 1));
        return CapabilityGraph.of(patterns, stock);
    }

    // ------------------------------------------------------------------ generated graphs

    /** How raw stock is set for a generated graph. */
    enum Stock {
        /** Every raw plentiful: both planners must find a complete plan, so this measures pure cost. */
        AMPLE,
        /** The raw most primary routes start from is absent: rerouting decides the shortage. */
        SCARCE_HUB,
        /** Even raws at half of what the first-declared routes need: route choice is contended. */
        CONTENDED,
        /** No stock at all: measures how expensive proving the shortage is. */
        DRY
    }

    record Size(String name, int layers, int width) {
    }

    static List<BenchCase> generated() {
        List<BenchCase> cases = new ArrayList<>();
        Size[] sizes = {new Size("s", 5, 40), new Size("m", 8, 80), new Size("l", 12, 150)};
        for (Size size : sizes) {
            for (Stock stock : Stock.values()) {
                long seed = 0x5EEDL * 31 + size.layers() * 1_000L + size.width();
                CapabilityGraph graph = generate(seed, size.layers(), size.width(), stock, 16);
                String id = String.format(Locale.ROOT, "generated/%s-%dx%d-%s", size.name(), size.layers(),
                        size.width(), stock.name().toLowerCase(Locale.ROOT));
                cases.add(new BenchCase(id, "generated", unknown(id, graph, "top", 16),
                        stock == Stock.AMPLE));
            }
        }
        // A production network holds every recipe, and a request reaches only part of it: the large
        // graph again, asked for one intermediate a few layers up.
        Size large = sizes[sizes.length - 1];
        long seed = 0x5EEDL * 31 + large.layers() * 1_000L + large.width();
        CapabilityGraph network = generate(seed, large.layers(), large.width(), Stock.AMPLE, 16);
        String id = String.format(Locale.ROOT, "generated/%s-%dx%d-ample-narrow", large.name(), large.layers(),
                large.width());
        cases.add(new BenchCase(id, "generated", unknown(id, network, "i03_000", 16), true));
        return cases;
    }

    private static final int RAWS = 24;

    /**
     * A layered graph with shared hub intermediates, a second route on about a third of the keys,
     * occasional batching and byproducts. The second route of a key prefers odd raws, so a planner
     * that reroutes can escape a shortage of the even raws the first routes lean on.
     */
    static CapabilityGraph generate(long seed, int layers, int width, Stock stock, long requested) {
        Random random = new Random(seed);
        List<CapabilityPattern> patterns = new ArrayList<>();
        List<List<String>> keys = new ArrayList<>();
        for (int layer = 0; layer < layers; layer++) {
            List<String> row = new ArrayList<>();
            for (int j = 0; j < width; j++) row.add(String.format(Locale.ROOT, "i%02d_%03d", layer, j));
            keys.add(row);
        }
        for (int layer = 0; layer < layers; layer++) {
            for (int j = 0; j < width; j++) {
                String key = keys.get(layer).get(j);
                Map<String, Long> primary = new LinkedHashMap<>();
                if (layer == 0) {
                    // Raw 0 is the hub raw: a quarter of the bottom layer starts from it.
                    primary.merge(random.nextInt(4) == 0 ? raw(0) : raw(random.nextInt(RAWS)), 1L, Long::sum);
                    primary.merge(raw(random.nextInt(RAWS)), 1L + random.nextInt(2), Long::sum);
                } else {
                    int inputs = 2 + random.nextInt(3);
                    for (int n = 0; n < inputs; n++) {
                        primary.merge(pick(random, keys, layer), 1L + (random.nextInt(4) == 0 ? 1 : 0), Long::sum);
                    }
                }
                long yield = random.nextInt(5) == 0 ? 2L + random.nextInt(3) : 1L;
                List<CapabilityOutput> outputs = new ArrayList<>();
                outputs.add(CapabilityOutput.primary(key, yield));
                if (j + 1 < width && random.nextInt(12) == 0) {
                    outputs.add(CapabilityOutput.byproduct(keys.get(layer).get(j + 1), 1));
                }
                patterns.add(CapabilityPattern.of("p-" + key, inputs(primary), outputs));

                if (random.nextInt(3) == 0) {
                    Map<String, Long> alternative = new LinkedHashMap<>();
                    if (layer > 0) alternative.merge(pick(random, keys, layer), 1L, Long::sum);
                    alternative.merge(raw(1 + 2 * random.nextInt(RAWS / 2)), 1L + random.nextInt(3), Long::sum);
                    if (random.nextBoolean()) {
                        alternative.merge(raw(1 + 2 * random.nextInt(RAWS / 2)), 1L, Long::sum);
                    }
                    patterns.add(CapabilityPattern.of("q-" + key, inputs(alternative),
                            List.of(CapabilityOutput.primary(key, 1))));
                }
            }
        }
        Map<String, Long> top = new LinkedHashMap<>();
        List<String> last = keys.get(layers - 1);
        for (int n = 0; n < 6; n++) top.merge(last.get(random.nextInt(last.size())), 1L, Long::sum);
        patterns.add(CapabilityPattern.of("p-top", inputs(top), List.of(CapabilityOutput.primary("top", 1))));

        Map<String, Long> amounts = new LinkedHashMap<>();
        switch (stock) {
            case AMPLE -> {
                for (int r = 0; r < RAWS; r++) amounts.put(raw(r), 1_000_000_000_000L);
            }
            case SCARCE_HUB -> {
                for (int r = 1; r < RAWS; r++) amounts.put(raw(r), 1_000_000_000_000L);
            }
            case CONTENDED -> {
                Map<String, Double> demand = firstRouteDemand(patterns, "top", requested);
                for (int r = 0; r < RAWS; r++) {
                    long need = (long) Math.ceil(demand.getOrDefault(raw(r), 0.0));
                    long amount = r % 2 == 0 ? need / 2 : 1_000_000_000_000L;
                    if (amount > 0) amounts.put(raw(r), amount);
                }
            }
            case DRY -> {
            }
        }
        return CapabilityGraph.of(patterns, amounts);
    }

    /** A lower-layer key, biased towards the first four "hub" keys of the layer just below. */
    private static String pick(Random random, List<List<String>> keys, int layer) {
        int from = random.nextInt(5) == 0 && layer >= 2 ? layer - 2 : layer - 1;
        List<String> row = keys.get(from);
        return random.nextInt(3) == 0 ? row.get(random.nextInt(4)) : row.get(random.nextInt(row.size()));
    }

    /** Raw demand when every key uses its first-declared route, ignoring byproducts. */
    private static Map<String, Double> firstRouteDemand(List<CapabilityPattern> patterns, String target,
                                                        long amount) {
        Map<String, CapabilityPattern> first = new LinkedHashMap<>();
        for (CapabilityPattern pattern : patterns) {
            for (String key : pattern.craftableOutputs()) first.putIfAbsent(key, pattern);
        }
        Map<String, Map<String, Double>> memo = new LinkedHashMap<>();
        Map<String, Double> perUnit = perUnit(target, first, memo);
        Map<String, Double> total = new LinkedHashMap<>();
        perUnit.forEach((raw, units) -> total.put(raw, units * amount));
        return total;
    }

    private static Map<String, Double> perUnit(String key, Map<String, CapabilityPattern> first,
                                               Map<String, Map<String, Double>> memo) {
        Map<String, Double> known = memo.get(key);
        if (known != null) return known;
        CapabilityPattern pattern = first.get(key);
        Map<String, Double> result = new LinkedHashMap<>();
        if (pattern == null) {
            result.put(key, 1.0);
        } else {
            double yield = 0;
            for (CapabilityOutput output : pattern.outputs()) {
                if (output.key().equals(key)) yield += output.amount().asBigInteger().doubleValue();
            }
            for (CapabilityInput input : pattern.inputs()) {
                double per = input.amount().asBigInteger().doubleValue() / yield;
                perUnit(input.key(), first, memo).forEach((raw, units) -> result.merge(raw, units * per, Double::sum));
            }
        }
        memo.put(key, result);
        return result;
    }

    private static String raw(int index) {
        return String.format(Locale.ROOT, "raw%02d", index);
    }

    // ------------------------------------------------------------------ helpers

    private static CapabilityPattern simple(String id, Map<String, Long> inputs, String output, long amount) {
        return CapabilityPattern.of(id, inputs(inputs), List.of(CapabilityOutput.primary(output, amount)));
    }

    private static List<CapabilityInput> inputs(Map<String, Long> amounts) {
        List<CapabilityInput> inputs = new ArrayList<>();
        amounts.forEach((key, amount) -> inputs.add(CapabilityInput.exact(key, amount)));
        return inputs;
    }

    /** A scenario whose minimal shortage is known (empty means a complete plan exists). */
    private static CapabilityScenario known(String id, CapabilityGraph graph, String target, long amount,
                                            Map<String, Long> minimalMissing) {
        Map<String, UfoAmount> witness = new LinkedHashMap<>();
        minimalMissing.forEach((key, value) -> witness.put(key, UfoAmount.of(value)));
        boolean feasible = witness.isEmpty();
        return CapabilityScenario.of(id, CapabilityFamily.MULTI_DAG, CapabilityMaterialMode.MISSING, 0, graph,
                target, UfoAmount.of(amount), feasible, feasible ? List.of() : List.of(witness), Map.of(),
                !feasible, EXACT_SEMANTICS, CapabilityExpectation.REQUIRED, graph::withAdditionalStock);
    }

    /**
     * A scenario without a known optimum. The neutral record insists on a witness for an infeasible
     * case, so it is declared feasible; the harness does not read that flag unless
     * {@link BenchCase#optimumKnown()} is set.
     */
    private static CapabilityScenario unknown(String id, CapabilityGraph graph, String target, long amount) {
        return CapabilityScenario.of(id, CapabilityFamily.MULTI_DAG, CapabilityMaterialMode.MISSING, 0, graph,
                target, UfoAmount.of(amount), true, List.of(), Map.of(), false, EXACT_SEMANTICS,
                CapabilityExpectation.REQUIRED, graph::withAdditionalStock);
    }

    private BenchScenarios() {
    }
}
