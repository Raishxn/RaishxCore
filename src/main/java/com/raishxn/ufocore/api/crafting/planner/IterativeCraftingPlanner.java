package com.raishxn.ufocore.api.crafting.planner;

import com.raishxn.ufocore.api.amount.UfoAmount;
import com.raishxn.ufocore.api.crafting.planner.ImmutableCraftingGraph.CompiledPattern;
import com.raishxn.ufocore.api.crafting.planner.ImmutableCraftingGraph.PatternEntry;
import java.math.BigInteger;
import java.io.Serial;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Exact, stack-safe planner over immutable snapshots.
 * Single-route DAGs aggregate demands in topological order. An acyclic cone of plain recipes with
 * route choices does too, choosing each key's routes once for its whole demand and repairing a
 * shortage by capping the route it is most to blame on; see {@code aggregatedPlan}. Other graphs, and
 * a plan that pass leaves short in exact mode, use explicit
 * continuations, and the exact mode settles route choices in stages that stay polynomial on the
 * common shapes: greedy selection, cheap shortage proofs, a discrepancy search over route pins and,
 * last, a bounded reversible search that can revise choices conflicting with later siblings. See
 * {@code exactPlan}.
 * Selection is deterministic. Between routes that are otherwise equally viable it prefers the one
 * with the smaller total leaf demand, computed bottom-up once per plan, so a correct plan is not
 * several times more expensive than it has to be. That is a preference, not a proof: the first
 * feasible plan is still returned, and general multi-route optimality under shared stock is not
 * claimed. Quantities are batched. A recipe that yields a key as a secondary output is a route to that
 * key like any other, so it competes with the recipes that declare it: the extra firings and the
 * primary that comes out alongside are priced by the same comparison, and the cheapest route wins
 * whichever kind it is. Naming a secondary as missing without considering the recipe that makes it
 * would name a key nobody can supply. Stateful feedback/catalyst optimization
 * belongs to a separate adapter, and is one: see {@link FeedbackCyclePlanner}, which states a
 * recycling loop as a decaying catalyst this planner can balance and rewrites the plan back to the
 * real patterns. That adapter composes with this one rather than being built into it, so a caller
 * that does not want it pays nothing for it.
 */
public final class IterativeCraftingPlanner<K> {
    private final LongSupplier nanoTime;

    public IterativeCraftingPlanner() { this(System::nanoTime); }
    IterativeCraftingPlanner(LongSupplier nanoTime) { this.nanoTime = Objects.requireNonNull(nanoTime); }

    /**
     * How many comparison links can decide a route choice, in the order the comparator consults them:
     * pattern priority, whether the route is short of an input, whether its leaf demand is known, its
     * leaf demand, its reachability rank, its shortage deficit, its input cost, its execution count,
     * its yield and finally its identifier. The histogram is exposed by diagnostics so an operator can
     * say <em>why</em> a route won, which the chosen plan alone cannot.
     */
    public static final int CHOICE_LINK_COUNT = 10;

    /**
     * One comparison step without materialising the comparator twice: whether link {@code index}
     * separates the winner from the runner-up. Only the two best candidates are compared, once, and
     * only when a choice actually exists, which is what keeps the diagnostic off the common path.
     */
    private static <K> boolean decides(int index, K key, Candidate<K> left, Candidate<K> right) {
        return switch (index) {
            case 0 -> left.pattern.pattern().priority() != right.pattern.pattern().priority();
            case 1 -> (left.deficit.signum() != 0) != (right.deficit.signum() != 0);
            case 2 -> (left.leafCost == null) != (right.leafCost == null);
            case 3 -> left.leafCost != null && right.leafCost != null
                    && (left.leafCost.compareTo(right.leafCost) != 0
                    || !Objects.equals(left.absentCost, right.absentCost));
            case 4 -> left.rank != right.rank;
            case 5 -> left.deficit.compareTo(right.deficit) != 0;
            case 6 -> left.cost.compareTo(right.cost) != 0;
            case 7 -> left.runs.compareTo(right.runs) != 0;
            case 8 -> left.pattern.outputAmount(key).compareTo(right.pattern.outputAmount(key)) != 0;
            default -> !left.pattern.pattern().id().equals(right.pattern.pattern().id());
        };
    }

    public PlanningResult<K> plan(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request) {
        return plan(graph, request, true);
    }

    /**
     * Plans without enumerating the Cartesian product of producer choices.
     *
     * <p>The same stock-aware route ordering, batching, byproduct accounting and cycle guards are
     * used as by the exact planner, but a shortage is returned from the best deterministic route
     * selection instead of trying every combination merely to prove that shortage. This keeps large
     * modpack graphs linear in the selected plan and, importantly, remains a RaishxCore calculation:
     * no external AE2 planning provider is involved.
     */
    public PlanningResult<K> planFast(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request) {
        return plan(graph, request, false);
    }

    /**
     * Share of what is left of the hard limits that the route search may spend once the greedy plan
     * is known. The rest is slack, so the search giving up never turns into a hard halt.
     */
    static final int EXACT_BUDGET_DIVISOR = 2;

    /** Cone size from which the exact planner starts from the aggregated pass. */
    static final int EXACT_AGGREGATE_MIN_PATTERNS = 16;

    /** The exact stages may spend this many times the operations of the greedy pass they improve on. */
    static final long EXACT_GREEDY_MULTIPLE = 256L;

    /** Floor of that allowance, so a tiny greedy pass still leaves room for a few route pins. */
    static final long EXACT_MINIMUM_OPERATIONS = 50_000L;

    private PlanningResult<K> plan(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request,
                                   boolean exhaustiveChoices) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(request, "request");
        long started = nanoTime.getAsLong();
        Budget budget = new Budget(request, started);
        State state = new State(graph, request);
        PlanningResult.Status halt = null;
        try {
            budget.check();
            if (!graph.simpleDemandOrder().isEmpty()) {
                planDag(graph, request, state, budget);
            } else {
                // Everything below works on the part of the graph the request can reach. Production graphs
                // are a whole network, and the passes below used to walk all of it for every request.
                Cone<K> cone = coneOf(graph, request.target(), budget);
                Map<K, Integer> ranks = reachability(graph, request, cone, budget);
                // The leaf demand pass only informs a choice between routes. A graph where every key
                // has a single route has nothing to choose, so it must not pay for the computation.
                boolean choice = hasRouteChoice(graph, cone);
                Map<K, BigInteger> leafCosts = choice ? leafCosts(graph, cone, budget) : Map.of();
                // An acyclic cone of plain recipes is planned once per key with its whole demand,
                // which is linear in the cone however many places a key is used in. Only a plan it
                // cannot complete goes on to the per-occurrence stages below, and the better of the
                // two answers is kept, so the stages can only improve on it.
                // The exact planner's own stages are cheap and strongest on a handful of recipes; the
                // aggregated pass pays off once a cone is large enough for keys to be shared. The fast
                // planner always takes it, since there it is the better answer at any size.
                boolean aggregate = !exhaustiveChoices || cone.patterns().size() >= EXACT_AGGREGATE_MIN_PATTERNS;
                List<K> order = aggregate ? aggregatedOrder(graph, cone, request.target(), budget) : null;
                State aggregated = order == null ? null
                        : aggregatedPlan(graph, request, order, cone, ranks, leafCosts, budget);
                // The fast planner keeps the aggregated answer even when it is short: measured against the
                // per-occurrence pass it ties or wins on every shortage but the largest graphs, where it
                // is a fraction of a percent off and the per-occurrence pass costs fifteen times more.
                if (aggregated != null && (aggregated.deadEnds == 0 || !exhaustiveChoices)) {
                    state = aggregated;
                } else {
                    if (!exhaustiveChoices) {
                        // Dense graphs normally spend nearly all their time proving that every other
                        // combination has the same shortage. Select the best routes once and preserve
                        // that useful plan instead of discarding it for an exponential proof.
                        search(graph, request, state, ranks, leafCosts, budget, true, null, null);
                    } else {
                        state = exactPlan(graph, request, ranks, leafCosts, budget);
                    }
                    if (aggregated != null && better(aggregated, state, request)) {
                        state = aggregated;
                    }
                }
            }
            budget.check();
        } catch (PlanningHalt stop) {
            halt = stop.status;
            // A partial search is not an executable plan, even if its missing map was still empty.
            state = new State(graph, request);
            state.missing.put(request.target(), request.amount());
        }
        CraftingPlan<K> plan = state.freeze(request.target(), request.amount());
        var status = halt != null ? halt : plan.complete()
                ? PlanningResult.Status.COMPLETE : PlanningResult.Status.MISSING_INGREDIENTS;
        return new PlanningResult<>(status, plan, new PlanningResult.Diagnostics(graph.revision(),
                budget.operations, budget.maximumDepth, Math.max(0, nanoTime.getAsLong() - started),
                PlanningResult.ShortageSummary.of(plan.shortage()), budget.cycleCuts,
                budget.choiceLinks));
    }

    /**
     * Finds a plan without a dead end, cheapest search first.
     *
     * <p>A dead end is a key that has to be supplied and has no route left: a raw material the stock
     * cannot cover, or a key the plan is already expanding. The old exact search stopped at the first
     * dead end and backtracked chronologically through every earlier choice, with one choice point per
     * <em>occurrence</em> of a key. A key with two routes used in twenty places is one route choice in
     * the graph and a million combinations to that search, which is how a 58-pattern graph spent eight
     * million operations on a request that was simply short of one material.
     *
     * <p>The stages, each run only when the one before did not settle the request:
     * <ol>
     * <li>The greedy selection. Without a dead end it is exactly the plan the old exact search
     *     returned, because that search's first descent makes the same choices.</li>
     * <li>Two cheap proofs that every plan reaches a dead end: the target cannot be obtained from the
     *     stock at all, or a raw material is needed beyond its stock on every route even when every
     *     stocked intermediate counts as free. A request that is simply short ends here.</li>
     * <li>A discrepancy search, as Thunderbolt's planner does it: a variant pins one key to another
     *     route at every occurrence, variants with fewer pins run first, and each is one linear greedy
     *     pass. A shared-stock conflict needs a pin or two, never a combination per occurrence.</li>
     * <li>The chronological search, as a backstop for a plan that needs different routes for the same
     *     key at different occurrences.</li>
     * </ol>
     * Stages two to four share a soft budget. When it runs out, or nothing better is found, the greedy
     * plan is the answer: a request is never failed because proving its shortage was expensive.
     */
    private State exactPlan(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request,
                            Map<K, Integer> ranks, Map<K, BigInteger> leafCosts, Budget budget) {
        List<Decision<K>> decisions = new ArrayList<>();
        State greedy = new State(graph, request);
        long before = budget.operations;
        search(graph, request, greedy, ranks, leafCosts, budget, true, null, decisions);
        long[] greedyLinks = budget.choiceLinks;
        if (greedy.deadEnds == 0) {
            return greedy;
        }
        // The stages may spend a multiple of what the greedy pass cost, not a share of the whole limit:
        // a choice-heavy graph that is simply short otherwise burned a second proving what greedy found.
        budget.beginSoftLimit(EXACT_BUDGET_DIVISOR, Math.max(EXACT_MINIMUM_OPERATIONS,
                (budget.operations - before) * EXACT_GREEDY_MULTIPLE));
        List<State> best = new ArrayList<>(List.of(greedy));
        try {
            if (!provablyShort(graph, request, greedy, budget)) {
                State found = discrepancySearch(graph, request, ranks, leafCosts, budget, greedy, decisions, best);
                if (found == null) {
                    State exhaustive = new State(graph, request);
                    budget.choiceLinks = null;
                    if (search(graph, request, exhaustive, ranks, leafCosts, budget, false, null, null)) {
                        found = exhaustive;
                    }
                }
                if (found != null) {
                    return found;
                }
            }
        } catch (SoftLimitReached expensive) {
            // The greedy plan below is the answer; nothing of the abandoned search is kept.
        } catch (PlanningHalt halt) {
            // A route the greedy pass never took can run deeper than the limit. That rules the route
            // out, not the request; time, operation and cancellation halts still end the request.
            if (halt.status != PlanningResult.Status.DEPTH_LIMIT) throw halt;
        } finally {
            budget.endSoftLimit();
        }
        // The greedy pass made the choices worth reporting, even when a variant's shortage is smaller.
        budget.choiceLinks = greedyLinks;
        return best.getFirst();
    }

    /**
     * Best-first search over route pins. Returns the first state without a dead end, or {@code null}
     * once every pin reachable from the decisions the runs actually made has been tried.
     */
    private State discrepancySearch(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request,
                                    Map<K, Integer> ranks, Map<K, BigInteger> leafCosts, Budget budget,
                                    State greedy, List<Decision<K>> greedyDecisions, List<State> best) {
        PriorityQueue<Variant<K>> queue = new PriorityQueue<>(Comparator.<Variant<K>>comparingInt(Variant::pins)
                .thenComparing(Variant::parentShortfall)
                .thenComparingLong(Variant::sequence));
        Set<Map<K, CraftingPattern<K>>> seen = new HashSet<>();
        seen.add(Map.of());
        long[] sequence = {0L};
        enqueuePins(Map.of(), 0, greedy.shortfall(), greedyDecisions, queue, seen, sequence, budget);
        while (!queue.isEmpty()) {
            budget.operation(0);
            Variant<K> variant = queue.poll();
            State state = new State(graph, request);
            List<Decision<K>> decisions = new ArrayList<>();
            budget.choiceLinks = null;
            search(graph, request, state, ranks, leafCosts, budget, true, variant.preferences(), decisions);
            if (state.deadEnds == 0) {
                return state;
            }
            // Keeping the best incomplete variant means a shortage no pin removes is still reported
            // at the smallest weighted amount any variant reached, not at whatever greedy found.
            if (better(state, best.getFirst(), request)) best.set(0, state);
            enqueuePins(variant.preferences(), variant.pins(), state.shortfall(), decisions, queue, seen,
                    sequence, budget);
        }
        return null;
    }

    /** Queues one more pin on top of {@code base} for every route a run passed over. */
    private void enqueuePins(Map<K, CraftingPattern<K>> base, int pins, BigInteger shortfall,
                             List<Decision<K>> decisions, PriorityQueue<Variant<K>> queue,
                             Set<Map<K, CraftingPattern<K>>> seen, long[] sequence, Budget budget) {
        for (Decision<K> decision : decisions) {
            if (base.containsKey(decision.key())) {
                continue;
            }
            for (CraftingPattern<K> alternative : decision.routes()) {
                budget.operation(0);
                if (alternative.equals(decision.chosen())) {
                    continue;
                }
                Map<K, CraftingPattern<K>> pinned = new HashMap<>(base);
                pinned.put(decision.key(), alternative);
                if (seen.add(pinned)) {
                    queue.add(new Variant<>(Map.copyOf(pinned), pins + 1, shortfall, sequence[0]++));
                }
            }
        }
    }

    /** Moves the pinned route's candidates to the front, keeping every other order as it was. */
    private static <K> List<Candidate<K>> pinned(List<Candidate<K>> options, CraftingPattern<K> pin) {
        if (pin == null || options.getFirst().pattern.pattern().equals(pin)) {
            return options;
        }
        ArrayList<Candidate<K>> reordered = new ArrayList<>(options.size());
        for (Candidate<K> option : options) {
            if (option.pattern.pattern().equals(pin)) reordered.add(option);
        }
        if (reordered.isEmpty()) {
            return options;
        }
        for (Candidate<K> option : options) {
            if (!option.pattern.pattern().equals(pin)) reordered.add(option);
        }
        return reordered;
    }

    /**
     * True when every plan reaches a dead end, by one of two arguments that cost a pass over the part
     * of the graph below the target.
     *
     * <p>Obtainability ignores catalysts and self-fed seeds, since a missing one of those is reported
     * as a shortage rather than ending the plan: what is left is a key with stock, or the output of a
     * pattern whose consumed inputs are all obtainable. A target outside that set reaches a dead end on
     * every route.
     *
     * <p>The amount bound is a least fixpoint, so it never exceeds what a real plan consumes. One unit
     * of a key costs at least its cheapest producing pattern's consumed inputs divided by everything
     * that pattern yields, so a coproduct is not charged twice; a stocked key and a catalyst cost
     * nothing. When the target needs more of a raw material than the stock holds even so, every plan
     * runs out of it.
     */
    private boolean provablyShort(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request, State greedy,
                                  Budget budget) {
        UfoAmount have = request.inventory().getOrDefault(request.target(), UfoAmount.ZERO);
        if (have.compareTo(request.amount()) >= 0) {
            return false;
        }
        Set<K> cone = cone(graph, request.target(), budget);
        if (!obtainable(graph, request, cone, budget).contains(request.target())) {
            return true;
        }
        double needed = request.amount().subtract(have).asBigInteger().doubleValue();
        for (K raw : greedy.missing.keySet()) {
            budget.operation(0);
            if (raw.equals(request.target()) || !graph.compiledPatternsFor(raw).isEmpty()
                    || !graph.secondaryRoutesFor(raw).isEmpty()) {
                continue;
            }
            double stock = request.inventory().getOrDefault(raw, UfoAmount.ZERO).asBigInteger().doubleValue();
            // Doubles can only make this bound slightly off, so a margin keeps rounding from proving
            // a shortage that is not there.
            if (rawCostPerUnit(graph, request, cone, raw, budget) * needed > stock * (1 + 1e-9) + 1e-6) {
                return true;
            }
        }
        return false;
    }

    /** Every key some route to {@code target} may ask for, the target included. */
    private Set<K> cone(ImmutableCraftingGraph<K> graph, K target, Budget budget) {
        Set<K> cone = new HashSet<>();
        ArrayDeque<K> queue = new ArrayDeque<>();
        cone.add(target);
        queue.add(target);
        while (!queue.isEmpty()) {
            K key = queue.removeFirst();
            for (CompiledPattern<K> pattern : routesTo(graph, key)) {
                for (PatternEntry<K> input : pattern.inputs()) {
                    budget.operation(0);
                    if (cone.add(input.key())) queue.add(input.key());
                    for (K variant : input.variants()) {
                        if (cone.add(variant)) queue.add(variant);
                    }
                }
            }
        }
        return cone;
    }

    private static <K> List<CompiledPattern<K>> routesTo(ImmutableCraftingGraph<K> graph, K key) {
        List<CompiledPattern<K>> secondary = graph.secondaryRoutesFor(key);
        if (secondary.isEmpty()) {
            return graph.compiledPatternsFor(key);
        }
        ArrayList<CompiledPattern<K>> both = new ArrayList<>(graph.compiledPatternsFor(key));
        both.addAll(secondary);
        return both;
    }

    private Set<K> obtainable(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request, Set<K> cone,
                              Budget budget) {
        Set<K> reached = new HashSet<>();
        ArrayDeque<K> queue = new ArrayDeque<>();
        request.inventory().forEach((key, amount) -> {
            if (!amount.isZero() && cone.contains(key) && reached.add(key)) queue.add(key);
        });
        // Patterns that can make a key of the cone, each waiting on the consumed inputs it lacks.
        Map<CompiledPattern<K>, Integer> pending = new IdentityHashMap<>();
        Map<K, List<CompiledPattern<K>>> waiting = new HashMap<>();
        for (K key : cone) {
            for (CompiledPattern<K> pattern : routesTo(graph, key)) {
                budget.operation(0);
                if (pending.containsKey(pattern)) continue;
                List<PatternEntry<K>> consumed = consumedFromOutside(pattern);
                pending.put(pattern, consumed.size());
                for (PatternEntry<K> input : consumed) {
                    waiting.computeIfAbsent(input.key(), ignored -> new ArrayList<>()).add(pattern);
                }
                if (consumed.isEmpty()) {
                    for (PatternEntry<K> output : pattern.outputs()) {
                        if (reached.add(output.key())) queue.add(output.key());
                    }
                }
            }
        }
        while (!queue.isEmpty()) {
            K key = queue.removeFirst();
            for (CompiledPattern<K> pattern : waiting.getOrDefault(key, List.of())) {
                budget.operation(0);
                if (pending.merge(pattern, -1, Integer::sum) == 0) {
                    for (PatternEntry<K> output : pattern.outputs()) {
                        if (reached.add(output.key())) queue.add(output.key());
                    }
                }
            }
        }
        return reached;
    }

    /** Consumed inputs the pattern does not make itself: no catalysts and no self-fed seeds. */
    private static <K> List<PatternEntry<K>> consumedFromOutside(CompiledPattern<K> pattern) {
        List<PatternEntry<K>> consumed = new ArrayList<>(pattern.inputs().size());
        for (PatternEntry<K> input : pattern.inputs()) {
            if (input.reusable()) continue;
            boolean selfFed = false;
            for (PatternEntry<K> output : pattern.outputs()) {
                if (output.key().equals(input.key())) {
                    selfFed = true;
                    break;
                }
            }
            if (!selfFed) consumed.add(input);
        }
        return consumed;
    }

    /** A lower bound on the units of {@code raw} one more unit of the target consumes. */
    private double rawCostPerUnit(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request, Set<K> cone,
                                  K raw, Budget budget) {
        Map<K, Double> cost = new HashMap<>();
        // Kleene iteration from zero: every iterate lies below the least fixpoint, so stopping after a
        // bounded number of passes still leaves a valid bound.
        int passes = Math.min(64, cone.size() + 1);
        for (int pass = 0; pass < passes; pass++) {
            boolean changed = false;
            for (K key : cone) {
                if (key.equals(raw) || key.equals(request.target())) continue;
                if (!request.inventory().getOrDefault(key, UfoAmount.ZERO).isZero()) continue;
                List<CompiledPattern<K>> routes = routesTo(graph, key);
                if (routes.isEmpty()) continue;
                double best = Double.POSITIVE_INFINITY;
                for (CompiledPattern<K> pattern : routes) {
                    best = Math.min(best, patternUnitCost(pattern, raw, cost, budget));
                }
                if (best > cost.getOrDefault(key, 0.0) * (1 + 1e-12) + 1e-12) {
                    cost.put(key, best);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        double best = Double.POSITIVE_INFINITY;
        for (CompiledPattern<K> pattern : routesTo(graph, request.target())) {
            best = Math.min(best, patternUnitCost(pattern, raw, cost, budget));
        }
        return Double.isInfinite(best) ? 0.0 : best;
    }

    private double patternUnitCost(CompiledPattern<K> pattern, K raw, Map<K, Double> cost, Budget budget) {
        double inputs = 0.0;
        for (PatternEntry<K> input : pattern.inputs()) {
            budget.operation(0);
            if (input.reusable()) continue;
            double each = input.key().equals(raw) ? 1.0 : cost.getOrDefault(input.key(), 0.0);
            for (K variant : input.variants()) {
                each = Math.min(each, variant.equals(raw) ? 1.0 : cost.getOrDefault(variant, 0.0));
            }
            double amount = input.amount().asBigInteger().doubleValue();
            inputs += input.durable() ? each * amount / Math.max(1, input.uses()) : each * amount;
        }
        double yield = 0.0;
        for (PatternEntry<K> output : pattern.outputs()) {
            yield += output.amount().asBigInteger().doubleValue();
        }
        return yield <= 0.0 ? Double.POSITIVE_INFINITY : inputs / yield;
    }

    private void planDag(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request, State state, Budget budget) {
        Map<K, UfoAmount> demand = new HashMap<>();
        Map<K, Integer> depths = new HashMap<>();
        demand.put(request.target(), request.amount());
        depths.put(request.target(), 1);
        if (graph.compiledPatternsFor(request.target()).isEmpty()) {
            UfoAmount shortfall = state.consume(request.target(), request.amount());
            state.add(state.missing, request.target(), shortfall);
            return;
        }
        for (K key : graph.simpleDemandOrder()) {
            budget.operation(0);
            UfoAmount needed = demand.get(key);
            if (needed == null) continue;
            int depth = depths.get(key);
            budget.operation(depth);
            UfoAmount required = state.consume(key, needed);
            if (required.isZero()) continue;
            List<CompiledPattern<K>> options = graph.compiledPatternsFor(key);
            if (options.isEmpty()) {
                state.add(state.missing, key, required);
                continue;
            }
            CompiledPattern<K> pattern = options.getFirst();
            UfoAmount runs = ceil(required, pattern.outputAmount(key));
            state.add(state.executions, pattern.pattern(), runs);
            state.schedule.add(new CraftingPlan.Execution<>(pattern.pattern(), runs));
            state.add(state.crafted, key, multiply(pattern.outputAmount(key), runs).subtract(required));
            for (PatternEntry<K> input : pattern.inputs()) {
                budget.operation(depth);
                demand.merge(input.key(), multiply(input.amount(), runs), UfoAmount::add);
                depths.merge(input.key(), depth + 1, Math::max);
            }
        }
        Collections.reverse(state.schedule);
    }

    /**
     * Demand order for the aggregated pass, products before ingredients, or {@code null} when the
     * request's cone does not qualify for it.
     *
     * <p>The cone qualifies when every route in it consumes plain inputs only - no catalyst, no
     * durable carrier, no variant slot, no input a recipe also makes - and the keys can be ordered so
     * that a key comes before every input of every route to it and before every coproduct of its
     * declared routes. The first rule makes a key's total demand final by the time it is planned; the
     * second lets a coproduct be netted against the surplus that firing left behind.
     */
    private List<K> aggregatedOrder(ImmutableCraftingGraph<K> graph, Cone<K> cone, K target, Budget budget) {
        // Two sweeps over the cone instead of an adjacency list: one counts each key's predecessors
        // and checks the recipes qualify, the other walks the same edges again in Kahn order. A
        // coproduct outside the cone is never demanded, so it needs no place in the order.
        Map<K, Integer> incoming = new HashMap<>();
        for (K key : cone.keys()) {
            for (CompiledPattern<K> pattern : routesTo(graph, key)) {
                for (PatternEntry<K> input : pattern.inputs()) {
                    budget.operation(0);
                    if (input.reusable() || input.durable() || !input.variants().isEmpty()) return null;
                    for (PatternEntry<K> output : pattern.outputs()) {
                        if (output.key().equals(input.key())) return null;
                    }
                    incoming.merge(input.key(), 1, Integer::sum);
                }
                if (!pattern.pattern().craftableOutputs().contains(key)) continue;
                for (PatternEntry<K> output : pattern.outputs()) {
                    if (!output.key().equals(key) && cone.keys().contains(output.key())) {
                        incoming.merge(output.key(), 1, Integer::sum);
                    }
                }
            }
        }
        if (incoming.containsKey(target)) return null;
        ArrayList<K> order = new ArrayList<>(cone.keys().size());
        ArrayDeque<K> queue = new ArrayDeque<>();
        queue.add(target);
        while (!queue.isEmpty()) {
            K key = queue.removeFirst();
            order.add(key);
            for (CompiledPattern<K> pattern : routesTo(graph, key)) {
                for (PatternEntry<K> input : pattern.inputs()) {
                    budget.operation(0);
                    if (incoming.merge(input.key(), -1, Integer::sum) == 0) queue.addLast(input.key());
                }
                if (!pattern.pattern().craftableOutputs().contains(key)) continue;
                for (PatternEntry<K> output : pattern.outputs()) {
                    if (!output.key().equals(key) && cone.keys().contains(output.key())
                            && incoming.merge(output.key(), -1, Integer::sum) == 0) {
                        queue.addLast(output.key());
                    }
                }
            }
        }
        return order.size() == cone.keys().size() ? order : null;
    }

    /**
     * Plans the cone once per key in {@code order}, then repairs a shortage by capping the route the
     * shortage is most to blame on, the way Thunderbolt's planner repairs its linear pass. Returns the
     * best pass by weighted shortage, or {@code null} when no pass could be scheduled.
     *
     * <p>Each key is planned with the demand every consumer above it has already placed, so a key used
     * in two hundred places is one route decision, not two hundred. A route's inputs count as available
     * only beyond what earlier decisions in the same pass already claimed of them, which is what makes
     * a shared raw push later keys onto their other routes.
     *
     * <p>The repair needs nothing but the pass itself: the largest shortage is traced back through
     * the recipes that consumed it, each charged in proportion to what it drew, and the route on a key
     * with a choice that carries the most of it is capped so its share comes off. A capped route
     * leaves the rest of the demand to the next route in the usual order.
     */
    private State aggregatedPlan(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request, List<K> order,
                                 Cone<K> cone, Map<K, Integer> ranks, Map<K, BigInteger> leafCosts,
                                 Budget budget) {
        int contended = 0;
        for (K key : order) {
            if (routesTo(graph, key).size() > 1) contended++;
        }
        int passes = contended == 0 ? 1 : (int) Math.min(64L, 4L + 2L * contended);
        // Built on the first repair only: most requests complete on the first pass.
        Map<CompiledPattern<K>, UfoAmount> caps = null;
        Set<CompiledPattern<K>> ineffective = null;
        State best = null;
        int stale = 0;
        for (int pass = 0; pass < passes; pass++) {
            Aggregate run = aggregatedPass(graph, request, order, ranks, leafCosts, budget,
                    caps == null ? Map.of() : caps);
            if (run.scheduled && (best == null || better(run.state, best, request))) {
                best = run.state;
                stale = 0;
            } else if (++stale >= REPAIR_PATIENCE) {
                break;
            }
            if (run.scheduled && run.state.deadEnds == 0) break;
            if (pass + 1 >= passes) break;
            // A shortage every plan has cannot be repaired away; moving demand between routes would
            // only spend passes rediscovering it.
            if (pass == 0 && run.scheduled && provablyShort(graph, request, run.state, budget)) break;
            if (caps == null) {
                caps = new IdentityHashMap<>();
                ineffective = Collections.newSetFromMap(new IdentityHashMap<>());
            }
            if (!blame(graph, order, cone, run, caps, ineffective, budget)) break;
        }
        return best;
    }

    /** Repair passes in a row that may fail to improve the best plan before the repair gives up. */
    static final int REPAIR_PATIENCE = 3;

    /**
     * One aggregated pass: its state, whether a schedule exists for it, and what blame needs. Patterns
     * are the graph's own compiled instances, so they are keyed by identity and never hashed.
     */
    private final class Aggregate {
        final State state;
        final boolean scheduled;
        final Map<K, UfoAmount> gross;
        final Map<CompiledPattern<K>, UfoAmount> fired;
        final Map<CompiledPattern<K>, K> firedFor;
        Aggregate(State state, boolean scheduled, Map<K, UfoAmount> gross,
                  Map<CompiledPattern<K>, UfoAmount> fired, Map<CompiledPattern<K>, K> firedFor) {
            this.state = state; this.scheduled = scheduled; this.gross = gross; this.fired = fired;
            this.firedFor = firedFor;
        }
    }

    private Aggregate aggregatedPass(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request,
                                     List<K> order, Map<K, Integer> ranks, Map<K, BigInteger> leafCosts,
                                     Budget budget, Map<CompiledPattern<K>, UfoAmount> caps) {
        State state = new State(graph, request);
        Map<K, UfoAmount> demand = new HashMap<>();
        Map<K, UfoAmount> gross = new HashMap<>();
        Map<CompiledPattern<K>, UfoAmount> fired = new IdentityHashMap<>(order.size());
        Map<CompiledPattern<K>, K> firedFor = new IdentityHashMap<>(order.size());
        // First firing of each pattern in demand order; reversed, it is the schedule to try first.
        List<CompiledPattern<K>> firing = new ArrayList<>();
        state.pending = demand;
        demand.put(request.target(), request.amount());
        for (K key : order) {
            budget.operation(0);
            UfoAmount needed = demand.remove(key);
            if (needed == null) continue;
            gross.put(key, needed);
            UfoAmount required = state.consume(key, needed);
            while (!required.isZero()) {
                budget.operation(0);
                Candidate<K> chosen = null;
                List<Candidate<K>> options = candidates(graph, key, required, state, ranks, leafCosts, budget);
                for (Candidate<K> option : options) {
                    UfoAmount cap = caps.get(option.pattern);
                    if (cap == null) {
                        chosen = option;
                        break;
                    }
                    UfoAmount left = cap.subtractClamped(fired.getOrDefault(option.pattern, UfoAmount.ZERO));
                    if (left.isZero()) continue;
                    chosen = left.compareTo(option.runs) < 0
                            ? new Candidate<>(option.pattern, left, option.deficit, option.cost, option.rank,
                                    option.leafCost, option.absentCost)
                            : option;
                    break;
                }
                // A cap moves demand onto another route; it never makes a key with a route short. When
                // every route is capped the best one takes the rest, so a shortage is always a leaf.
                if (chosen == null && !options.isEmpty()) chosen = options.getFirst();
                if (chosen == null) {
                    state.add(state.missing, key, required);
                    state.deadEnds++;
                    break;
                }
                CompiledPattern<K> pattern = chosen.pattern;
                if (!fired.containsKey(pattern)) firing.add(pattern);
                fired.merge(pattern, chosen.runs, UfoAmount::add);
                firedFor.putIfAbsent(pattern, key);
                state.add(state.executions, pattern.pattern(), chosen.runs);
                for (PatternEntry<K> output : pattern.outputs()) {
                    state.add(state.crafted, output.key(), multiply(output.amount(), chosen.runs));
                }
                for (PatternEntry<K> input : pattern.inputs()) {
                    budget.operation(0);
                    demand.merge(input.key(), multiply(input.amount(), chosen.runs), UfoAmount::add);
                }
                required = state.consume(key, required);
            }
        }
        state.pending = null;
        boolean scheduled = schedule(state, request, firing, fired, budget);
        return new Aggregate(state, scheduled, gross, fired, firedFor);
    }

    /**
     * Orders the pass's firings so each runs only once its inputs are on hand, counting stock and the
     * reported shortage as supplied up front - the contract every plan is replayed under. The pass
     * already balances in total; what this proves is that a coproduct it netted is really made before
     * the recipe that draws it. A firing that can only partly run is split. Returns {@code false} when
     * no order exists, which sends the request back to the per-occurrence planner.
     */
    private boolean schedule(State state, PlanningRequest<K> request, List<CompiledPattern<K>> firing,
                             Map<CompiledPattern<K>, UfoAmount> fired, Budget budget) {
        boolean coproducts = false;
        for (CompiledPattern<K> pattern : firing) {
            if (pattern.outputs().size() > 1) {
                coproducts = true;
                break;
            }
        }
        if (!coproducts) {
            // Without a coproduct every input of a firing is made by firings planned after it, so the
            // reverse of the pass is an order by construction and needs no simulation to prove it.
            for (int i = firing.size() - 1; i >= 0; i--) {
                state.schedule.add(new CraftingPlan.Execution<>(firing.get(i).pattern(), fired.get(firing.get(i))));
            }
            return true;
        }
        // Only what the firings move is tracked; stock and shortage are read from where they already are.
        Map<K, BigInteger> moved = new HashMap<>();
        // Ingredients last in demand order, so the reverse is usually already a valid order and one
        // sweep schedules everything.
        ArrayList<CompiledPattern<K>> remaining = new ArrayList<>(firing.size());
        BigInteger[] runsLeft = new BigInteger[firing.size()];
        for (int i = firing.size() - 1; i >= 0; i--) {
            runsLeft[remaining.size()] = fired.get(firing.get(i)).asBigInteger();
            remaining.add(firing.get(i));
        }
        int left = remaining.size();
        while (left > 0) {
            boolean progress = false;
            for (int i = 0; i < remaining.size(); i++) {
                BigInteger runs = runsLeft[i];
                if (runs.signum() == 0) continue;
                budget.operation(0);
                CompiledPattern<K> pattern = remaining.get(i);
                for (PatternEntry<K> input : pattern.inputs()) {
                    BigInteger have = request.inventory().getOrDefault(input.key(), UfoAmount.ZERO).asBigInteger()
                            .add(state.missing.getOrDefault(input.key(), UfoAmount.ZERO).asBigInteger())
                            .add(moved.getOrDefault(input.key(), BigInteger.ZERO));
                    runs = runs.min(have.divide(input.amount().asBigInteger()));
                }
                if (runs.signum() == 0) continue;
                for (PatternEntry<K> input : pattern.inputs()) {
                    moved.merge(input.key(), input.amount().asBigInteger().multiply(runs).negate(), BigInteger::add);
                }
                for (PatternEntry<K> output : pattern.outputs()) {
                    moved.merge(output.key(), output.amount().asBigInteger().multiply(runs), BigInteger::add);
                }
                state.schedule.add(new CraftingPlan.Execution<>(pattern.pattern(), UfoAmount.of(runs)));
                runsLeft[i] = runsLeft[i].subtract(runs);
                if (runsLeft[i].signum() == 0) left--;
                progress = true;
            }
            if (!progress) return false;
        }
        return true;
    }

    /**
     * Traces the largest shortage of a pass back through what consumed it and caps the route on a
     * contended key that carries the most of it. Returns {@code false} when nothing is left to cap.
     */
    private boolean blame(ImmutableCraftingGraph<K> graph, List<K> order, Cone<K> cone, Aggregate run,
                          Map<CompiledPattern<K>, UfoAmount> caps, Set<CompiledPattern<K>> ineffective,
                          Budget budget) {
        K shortKey = null;
        BigInteger shortfall = BigInteger.ZERO;
        for (K key : order) {
            UfoAmount amount = run.state.missing.get(key);
            if (amount != null && amount.asBigInteger().compareTo(shortfall) > 0) {
                shortKey = key;
                shortfall = amount.asBigInteger();
            }
        }
        if (shortKey == null) return false;
        while (true) {
            Map<K, Double> blameByKey = new HashMap<>();
            blameByKey.put(shortKey, shortfall.doubleValue());
            CompiledPattern<K> blamed = null;
            double blamedShare = 0.0;
            for (int i = order.size() - 1; i >= 0; i--) {
                K item = order.get(i);
                Double blame = blameByKey.get(item);
                if (blame == null || blame <= 0.0) continue;
                UfoAmount demand = run.gross.get(item);
                if (demand == null || demand.isZero()) continue;
                double total = demand.asBigInteger().doubleValue();
                for (Use<K> use : cone.usesOf(item)) {
                    budget.operation(0);
                    CompiledPattern<K> consumer = use.pattern();
                    UfoAmount runs = run.fired.get(consumer);
                    K firedFor = run.firedFor.get(consumer);
                    if (runs == null || firedFor == null) continue;
                    double units = use.entry().amount().asBigInteger().doubleValue()
                            * runs.asBigInteger().doubleValue();
                    double share = blame * units / total;
                    if (share <= 0.0) continue;
                    if (routesTo(graph, firedFor).size() > 1 && !ineffective.contains(consumer)
                            && share > blamedShare) {
                        blamed = consumer;
                        blamedShare = share;
                    }
                    blameByKey.merge(firedFor, share, Double::sum);
                }
            }
            if (blamed == null) return false;
            double allocated = run.fired.get(blamed).asBigInteger().doubleValue();
            double leafDemand = run.gross.get(shortKey).asBigInteger().doubleValue();
            double contribution = blamedShare / shortfall.doubleValue() * leafDemand;
            double delta = contribution <= 0.0 ? allocated : Math.ceil(shortfall.doubleValue() * allocated / contribution);
            long cut = (long) Math.min(allocated, Math.max(1.0, delta));
            UfoAmount newCap = run.fired.get(blamed).subtractClamped(UfoAmount.of(cut));
            UfoAmount existing = caps.get(blamed);
            if (existing != null && existing.compareTo(newCap) <= 0) {
                ineffective.add(blamed);
                continue;
            }
            caps.put(blamed, newCap);
            return true;
        }
    }

    /**
     * Whether {@code candidate} is a better answer than {@code incumbent}: complete first, then the
     * smaller weighted shortage, then fewer executions.
     */
    private boolean better(State candidate, State incumbent, PlanningRequest<K> request) {
        if (candidate.missing.isEmpty() != incumbent.missing.isEmpty()) return candidate.missing.isEmpty();
        int byShortage = candidate.weightedShortfall(request).compareTo(incumbent.weightedShortfall(request));
        if (byShortage != 0) return byShortage < 0;
        return candidate.totalExecutions().compareTo(incumbent.totalExecutions()) < 0;
    }

    /**
     * The keys a request can ask for and every pattern that yields one of them. Closed downwards: each
     * input of such a pattern is a key of the cone, so any bottom-up quantity computed over it equals
     * the same quantity over the whole graph for every key of the cone.
     */
    private record Cone<K>(Set<K> keys, List<CompiledPattern<K>> patterns, Set<CompiledPattern<K>> contains,
                           Map<K, List<Use<K>>> uses) {
        /** Input entries of cone patterns that draw {@code key}, one per entry. */
        List<Use<K>> usesOf(K key) {
            return uses.getOrDefault(key, List.of());
        }
    }

    /** One input entry of one pattern: the unit a bottom-up pass charges a key's cost to. */
    private record Use<K>(CompiledPattern<K> pattern, PatternEntry<K> entry) {}

    private Cone<K> coneOf(ImmutableCraftingGraph<K> graph, K target, Budget budget) {
        Set<K> keys = new LinkedHashSet<>();
        List<CompiledPattern<K>> patterns = new ArrayList<>();
        Set<CompiledPattern<K>> contains = Collections.newSetFromMap(new IdentityHashMap<>(8));
        // The graph's consumer index covers the whole network, so a raw material used everywhere would
        // be walked across every recipe that uses it. This one only holds what the cone uses.
        Map<K, List<Use<K>>> uses = new HashMap<>();
        ArrayDeque<K> queue = new ArrayDeque<>();
        keys.add(target);
        queue.add(target);
        while (!queue.isEmpty()) {
            K key = queue.removeFirst();
            for (CompiledPattern<K> pattern : routesTo(graph, key)) {
                budget.operation(0);
                if (!contains.add(pattern)) continue;
                patterns.add(pattern);
                for (PatternEntry<K> input : pattern.inputs()) {
                    uses.computeIfAbsent(input.key(), ignored -> new ArrayList<>()).add(new Use<>(pattern, input));
                    if (keys.add(input.key())) queue.add(input.key());
                    for (K variant : input.variants()) {
                        if (keys.add(variant)) queue.add(variant);
                    }
                }
            }
        }
        return new Cone<>(keys, patterns, contains, uses);
    }

    /** Linear hypergraph reachability. Each input edge is visited once, even in a reverse-sorted deep chain. */
    private Map<K, Integer> reachability(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request,
                                        Cone<K> cone, Budget budget) {
        Map<K, Integer> ranks = new HashMap<>();
        ArrayDeque<K> queue = new ArrayDeque<>();
        request.inventory().forEach((key, amount) -> {
            if (cone.keys().contains(key)) { ranks.put(key, 0); queue.add(key); }
        });
        Map<CompiledPattern<K>, Integer> pending = new IdentityHashMap<>(cone.patterns().size());
        Map<CompiledPattern<K>, Integer> maxima = new IdentityHashMap<>(cone.patterns().size());
        for (CompiledPattern<K> pattern : cone.patterns()) {
            budget.operation(0);
            pending.put(pattern, pattern.inputs().size());
            if (pattern.inputs().isEmpty()) {
                for (PatternEntry<K> output : pattern.outputs()) {
                    if (ranks.putIfAbsent(output.key(), 0) == null) queue.add(output.key());
                }
            }
        }
        while (!queue.isEmpty()) {
            K key = queue.removeFirst();
            for (Use<K> use : cone.usesOf(key)) {
                CompiledPattern<K> pattern = use.pattern();
                budget.operation(0);
                maxima.merge(pattern, ranks.get(key), Math::max);
                if (pending.merge(pattern, -1, Integer::sum) == 0) {
                    int rank = maxima.get(pattern) + 1;
                    for (PatternEntry<K> output : pattern.outputs()) {
                        if (ranks.putIfAbsent(output.key(), rank) == null) queue.addLast(output.key());
                    }
                }
            }
        }
        return ranks;
    }

    /** True when some key of the cone is produced by more than one pattern, so route choice actually exists. */
    private static <K> boolean hasRouteChoice(ImmutableCraftingGraph<K> graph, Cone<K> cone) {
        for (CompiledPattern<K> pattern : cone.patterns()) {
            for (PatternEntry<K> output : pattern.outputs()) {
                if (graph.compiledPatternsFor(output.key()).size() > 1) return true;
            }
        }
        return false;
    }

    /**
     * Minimum total leaf demand needed to obtain one unit of every key, computed bottom-up over the
     * acyclic part of the graph.
     *
     * <p>Choosing between two viable routes otherwise looks one level deep, so a narrow and a wide
     * route tie on input count and an identifier breaks the tie. A plan can then be correct and
     * still ask the player for several times the material it needs: on the multi-route Fibonacci
     * corpus case the reported shortage was 6.857 times the known minimum. Leaf demand is the
     * quantity shortage quality is measured with, so it has to drive the choice.
     *
     * <p>A key nothing produces is one unit of shortage. A pattern's cost is the sum of its inputs'
     * costs divided by how much it yields, rounded up, because a fractional unit cannot be supplied.
     * Keys and patterns on a cycle never resolve and stay absent, so they keep the previous ordering.
     */
    private Map<K, BigInteger> leafCosts(ImmutableCraftingGraph<K> graph, Cone<K> cone, Budget budget) {
        Map<K, BigInteger> costs = new HashMap<>();
        // The same pass also fills the request's absent-leaf costs, which share every step but the
        // value a leaf starts from: zero for a leaf the request holds, its weight otherwise.
        Map<K, BigInteger> absent = new HashMap<>();
        budget.absentCosts = absent;
        Map<CompiledPattern<K>, Integer> unresolvedInputs = new IdentityHashMap<>(cone.patterns().size());
        Map<CompiledPattern<K>, BigInteger[]> inputTotals = new IdentityHashMap<>(cone.patterns().size());
        Map<K, Integer> unresolvedProducers = new HashMap<>();
        Set<K> keys = new HashSet<>();

        for (CompiledPattern<K> pattern : cone.patterns()) {
            budget.operation(0);
            unresolvedInputs.put(pattern, pattern.inputs().size());
            inputTotals.put(pattern, new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO});
            for (PatternEntry<K> input : pattern.inputs()) {
                keys.add(input.key());
            }
            for (PatternEntry<K> output : pattern.outputs()) {
                keys.add(output.key());
                unresolvedProducers.merge(output.key(), 1, Integer::sum);
            }
        }

        ArrayDeque<K> finalized = new ArrayDeque<>();
        // A leaf costs one unit of itself, or what the request says that unit is worth. The rest of
        // the pass multiplies amounts through, so a root ends up holding weighted leaf value, and the
        // route comparison is then made in the same currency as the shortage it is trying to avoid.
        // Leaves were counted in bare units before, which meant a route needing ten cheap units beat
        // one needing a single valuable unit and the declared weights were never consulted at all.
        // One is the default and stays the same object, so an unweighted request is untouched.
        Map<K, Long> weights = budget.request.missingWeights();
        for (K key : keys) {
            if (unresolvedProducers.getOrDefault(key, 0) == 0) {
                BigInteger weight = weights.isEmpty() ? BigInteger.ONE
                        : BigInteger.valueOf(weights.getOrDefault(key, 1L));
                costs.put(key, weight);
                // Counting only leaves the request has none of gives the shortage a route would cause,
                // which is what a route must be judged by first when some raw material is absent.
                absent.put(key, budget.request.inventory().getOrDefault(key, UfoAmount.ZERO).isZero()
                        ? weight : BigInteger.ZERO);
                finalized.add(key);
            }
        }

        while (!finalized.isEmpty()) {
            K key = finalized.removeFirst();
            BigInteger cost = costs.get(key);
            BigInteger absentCost = absent.get(key);
            for (Use<K> use : cone.usesOf(key)) {
                CompiledPattern<K> pattern = use.pattern();
                budget.operation(0);
                BigInteger units = use.entry().amount().asBigInteger();
                BigInteger[] totals = inputTotals.get(pattern);
                totals[0] = totals[0].add(cost.multiply(units));
                totals[1] = totals[1].add(absentCost.multiply(units));
                if (unresolvedInputs.merge(pattern, -1, Integer::sum) != 0) continue;
                resolveLeafCost(pattern, costs, absent, unresolvedProducers, totals, finalized);
            }
        }
        return costs;
    }

    private void resolveLeafCost(CompiledPattern<K> pattern, Map<K, BigInteger> costs, Map<K, BigInteger> absent,
                                 Map<K, Integer> unresolvedProducers, BigInteger[] totals,
                                 ArrayDeque<K> finalized) {
        for (PatternEntry<K> output : pattern.outputs()) {
            K key = output.key();
            BigInteger perUnit = ceilDiv(totals[0], output.amount().asBigInteger());
            BigInteger existing = costs.get(key);
            if (existing == null || perUnit.compareTo(existing) < 0) {
                costs.put(key, perUnit);
            }
            BigInteger absentPerUnit = ceilDiv(totals[1], output.amount().asBigInteger());
            BigInteger existingAbsent = absent.get(key);
            if (existingAbsent == null || absentPerUnit.compareTo(existingAbsent) < 0) {
                absent.put(key, absentPerUnit);
            }
            if (unresolvedProducers.merge(key, -1, Integer::sum) == 0) {
                costs.putIfAbsent(key, BigInteger.ONE);
                absent.putIfAbsent(key, BigInteger.ONE);
                finalized.add(key);
            }
        }
    }

    private static BigInteger ceilDiv(BigInteger numerator, BigInteger denominator) {
        BigInteger[] quotient = numerator.divideAndRemainder(denominator);
        return quotient[1].signum() == 0 ? quotient[0] : quotient[0].add(BigInteger.ONE);
    }

    /**
     * One pass over the demand. {@code preferences} pins keys to a route wherever that route is a
     * candidate, and {@code decisions} collects every choice the pass made between routes; both are
     * {@code null} when unused.
     */
    private boolean search(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request, State state,
                            Map<K, Integer> ranks, Map<K, BigInteger> leafCosts, Budget budget,
                            boolean simulate, Map<K, CraftingPattern<K>> preferences,
                            List<Decision<K>> decisions) {
        Task<K> pending = new Task<>(request.target(), request.amount(), 1, null, null, null);
        ArrayDeque<Choice<K>> choices = new ArrayDeque<>();
        while (pending != null) {
            Task<K> task = pending;
            pending = task.next;
            budget.operation(task.depth);
            if (task.pattern != null) {
                state.add(state.executions, task.pattern.pattern(), task.runs);
                state.schedule.add(new CraftingPlan.Execution<>(task.pattern.pattern(), task.runs));
                for (PatternEntry<K> output : task.pattern.outputs()) {
                    budget.operation(task.depth);
                    state.add(state.crafted, output.key(), multiply(output.amount(), task.runs));
                }
                UfoAmount remainder = state.consume(task.key, task.amount);
                if (!remainder.isZero()) throw new IllegalStateException("selected pattern did not cover demand");
                state.activate(task.key, false);
                continue;
            }
            UfoAmount required = state.consume(task.key, task.amount);
            if (required.isZero()) continue;
            List<Candidate<K>> options = state.active.contains(task.key) ? List.of()
                    : candidates(graph, task.key, required, state, ranks, leafCosts, budget);
            if (options.isEmpty()) {
                if (simulate) {
                    state.add(state.missing, task.key, required);
                    state.deadEnds++;
                    continue;
                }
                boolean recovered = false;
                while (!choices.isEmpty()) {
                    budget.operation(0);
                    Choice<K> choice = choices.peek();
                    state.rollback(choice.mark, choice.scheduleSize);
                    if (choice.next < choice.options.size()) {
                        pending = expand(choice.key, choice.required, choice.depth,
                                choice.options.get(choice.next++), choice.continuation, state, budget);
                        recovered = true;
                        break;
                    }
                    choices.pop();
                }
                if (!recovered) return false;
                continue;
            }
            if (preferences != null && options.size() > 1) {
                options = pinned(options, preferences.get(task.key));
            }
            if (decisions != null && options.size() > 1) {
                decisions.add(Decision.of(task.key, options));
            }
            if (!simulate && options.size() > 1) {
                state.journaling = true;
                choices.push(new Choice<>(task.key, required, task.depth, options, pending,
                        state.journal.size(), state.schedule.size()));
            }
            pending = expand(task.key, required, task.depth, options.getFirst(), pending, state, budget);
        }
        return true;
    }

    private Task<K> expand(K key, UfoAmount required, int depth, Candidate<K> option, Task<K> next,
                           State state, Budget budget) {
        UfoAmount covered = multiply(option.pattern.outputAmount(key), option.runs).min(required);
        if (covered.compareTo(required) < 0) {
            next = new Task<>(key, required.subtract(covered), depth, null, null, next);
        }
        state.activate(key, true);
        // A self-feeding input is produced by the pattern it feeds, so it has to be claimed after that
        // pattern has run. Every other input is wrapped into the head of the chain, where it would be
        // drawn before the pattern could create it and the cycle guard would refuse the whole plan.
        List<PatternEntry<K>> outside = new ArrayList<>();
        List<UfoAmount> outsideDraws = new ArrayList<>();
        List<UfoAmount> feedingDraws = new ArrayList<>();
        UfoAmount selfConsumedPerRun = UfoAmount.ZERO;
        // A decaying catalyst is declared twice, as a catalyst and as a consumed input. The presence
        // check below has to demand both at once, because it runs before the draws and would otherwise
        // see the whole stock and wave through a batch that eats into the catalyst it just approved.
        Map<K, UfoAmount> consumedPerKey = null;
        boolean hasCatalyst = false;
        for (PatternEntry<K> input : option.pattern.inputs()) {
            if (input.reusable()) {
                hasCatalyst = true;
                break;
            }
        }
        if (hasCatalyst) {
            // Only a pattern that carries a catalyst needs this at all. Building it unconditionally
            // charged every pattern in the graph for a map that stays empty, which showed up as bytes
            // per operation on a case that has no catalyst in it whatsoever.
            consumedPerKey = new HashMap<>();
            for (PatternEntry<K> input : option.pattern.inputs()) {
                if (input.reusable() || input.key().equals(key)) continue;
                consumedPerKey.merge(input.key(), input.durable()
                        ? multiply(input.amount(), ceil(option.runs, UfoAmount.of(input.uses())))
                        : multiply(input.amount(), option.runs), UfoAmount::add);
            }
        }
        for (PatternEntry<K> input : option.pattern.inputs()) {
            budget.operation(depth);
            if (input.reusable()) {
                // A catalyst must be on hand but is handed back, so it is never consumed and no
                // demand is propagated for it. It is checked once, when the pattern is expanded, so a
                // catalyst that this same plan would craft only later still reads as missing.
                UfoAmount alsoConsumed = consumedPerKey == null ? UfoAmount.ZERO
                        : consumedPerKey.getOrDefault(input.key(), UfoAmount.ZERO);
                // Only the working stock is charged once for the whole plan; the decay is charged every
                // time, because it really is consumed every time.
                // Allocated on first use: a graph with no catalyst at all must not pay for a map that
                // would stay empty, and most graphs are that.
                UfoAmount working = input.amount().subtractClamped(state.presence == null
                        ? UfoAmount.ZERO : state.presence.getOrDefault(input.key(), UfoAmount.ZERO));
                if (!working.isZero()) {
                    if (state.presence == null) {
                        state.presence = new HashMap<>();
                    }
                    state.put(state.presence, input.key(), input.amount());
                }
                // The decay is drawn by its own consumed entry a few lines below, so charging the whole
                // of working plus decay here double-counts it whenever the stock cannot cover the
                // draw: with nothing in stock, a working stock of two and a decay of two demanded four
                // here and two more there. What is charged here is the part the normal draw cannot
                // explain, which is the shortfall of the total beyond the shortfall of the decay.
                UfoAmount present = input.variants().isEmpty() ? state.available(input.key())
                        : state.availableAcross(input.variants());
                UfoAmount totalShort = working.add(alsoConsumed).subtractClamped(present);
                UfoAmount decayShort = alsoConsumed.subtractClamped(present);
                state.add(state.missing, input.key(), totalShort.subtractClamped(decayShort));
                continue;
            }
            UfoAmount drawn = input.durable()
                    ? durableDraw(state, option.pattern, input, option.runs, true)
                    : multiply(input.amount(), option.runs);
            if (input.key().equals(key)) {
                selfConsumedPerRun = selfConsumedPerRun.add(input.amount());
                feedingDraws.add(drawn);
            } else {
                outside.add(input);
                outsideDraws.add(drawn);
            }
        }
        if (!feedingDraws.isEmpty()) {
            // The loop needs a seed to start, and the caller already took whatever stock there was.
            // One seed is enough to reach any amount, so a shortage is the seed rather than the
            // request. It is also modelled as available: the balance contract assumes a reported
            // shortage is supplied, and unlike a consumed input a seed is not used up, so the residue
            // it leaves has to appear in the plan.
            UfoAmount seed = state.extracted.getOrDefault(key, UfoAmount.ZERO);
            UfoAmount shortfall = selfConsumedPerRun.subtractClamped(seed);
            if (!shortfall.isZero()) {
                state.add(state.missing, key, shortfall);
                state.add(state.crafted, key, shortfall);
            }
        }
        Task<K> chained = next;
        for (int i = feedingDraws.size() - 1; i >= 0; i--) {
            chained = new Task<>(key, feedingDraws.get(i), depth + 1, null, null, chained);
        }
        Task<K> head = new Task<>(key, covered, depth, option.pattern, option.runs, chained);
        for (int i = outside.size() - 1; i >= 0; i--) {
            head = new Task<>(outside.get(i).key(), outsideDraws.get(i), depth + 1, null, null, head);
        }
        return head;
    }

    /** One durable carrier group: a pattern and the input key whose carriers it consumes. */
    private record DurableGroup<K>(CompiledPattern<K> pattern, K key) {}

    /**
     * How many carriers a batch of {@code runs} more firings needs beyond what earlier batches of the
     * same group already claimed. The increments telescope to the ceiling of the total firings, which
     * is what the schedule really consumes; a ceiling per batch does not, and two ceilings of one and
     * three firings across a carrier that lasts two add up to three carriers where two suffice.
     */
    private UfoAmount durableDraw(State state, CompiledPattern<K> pattern, PatternEntry<K> input,
                                  UfoAmount runs, boolean commit) {
        DurableGroup<K> group = new DurableGroup<>(pattern, input.key());
        UfoAmount before = state.firings == null ? UfoAmount.ZERO
                : state.firings.getOrDefault(group, UfoAmount.ZERO);
        UfoAmount after = before.add(runs);
        if (commit) {
            if (state.firings == null) {
                state.firings = new HashMap<>();
            }
            state.put(state.firings, group, after);
        }
        UfoAmount uses = UfoAmount.of(input.uses());
        return multiply(ceil(after, uses).subtract(ceil(before, uses)), input.amount());
    }

    /** Firings of a durable group already claimed, so a capacity can be stated in what is left. */
    private UfoAmount firingsClaimed(State state, CompiledPattern<K> pattern, PatternEntry<K> input) {
        return state.firings == null ? UfoAmount.ZERO
                : state.firings.getOrDefault(new DurableGroup<>(pattern, input.key()), UfoAmount.ZERO);
    }

    private List<Candidate<K>> candidates(ImmutableCraftingGraph<K> graph, K key, UfoAmount required,
                                          State state, Map<K, Integer> ranks,
                                          Map<K, BigInteger> leafCosts, Budget budget) {
        ArrayList<Candidate<K>> options = new ArrayList<>();
        List<CompiledPattern<K>> routes = graph.compiledPatternsFor(key);
        // A secondary output is a way to obtain the key, not a lesser kind of way, so it competes. It
        // used to be consulted only when nothing declared the key at all, which meant a recipe yielding
        // nine of it per firing lost to any recipe that named it, however expensive. The comparison
        // below already prices the extra firings and the primary that comes out alongside, so the
        // recipe that is actually cheapest wins. The list is built only when there is something to add.
        List<CompiledPattern<K>> secondary = graph.secondaryRoutesFor(key);
        if (!secondary.isEmpty()) {
            ArrayList<CompiledPattern<K>> both = new ArrayList<>(routes.size() + secondary.size());
            both.addAll(routes);
            both.addAll(secondary);
            routes = both;
        }
        for (CompiledPattern<K> pattern : routes) {
            budget.operation(0);
            // A pattern that feeds itself only closes when it produces more than it consumes: that is
            // a growth step, and the material it needs is the seed it also makes. Refusing it, as the
            // cycle guard did, discarded the family outright.
            UfoAmount selfConsumed = UfoAmount.ZERO;
            UfoAmount selfProduced = UfoAmount.ZERO;
            for (PatternEntry<K> entry : pattern.inputs()) {
                if (entry.key().equals(key) && !entry.reusable()) {
                    selfConsumed = selfConsumed.add(entry.amount());
                }
            }
            for (PatternEntry<K> entry : pattern.outputs()) {
                if (entry.key().equals(key)) selfProduced = selfProduced.add(entry.amount());
            }
            // A returned input/byproduct may be smaller than the consumed amount. This is a
            // signed balance, not a non-negative material quantity; keep it signed so lossy
            // self-routes reach the cycle guard instead of aborting the entire calculation.
            BigInteger selfNet = selfProduced.asBigInteger().subtract(selfConsumed.asBigInteger());
            boolean growth = !selfConsumed.isZero() && selfNet.signum() > 0;
            // The caller already took the seed from stock, so it is not subtracted again: each run
            // adds the net, and the runs needed are the ceiling of what is still missing over it.
            UfoAmount runs = growth
                    ? UfoAmount.of(required.asBigInteger().add(selfNet).subtract(BigInteger.ONE).divide(selfNet))
                    : ceil(required, pattern.outputAmount(key));
            UfoAmount capacity = runs;
            BigInteger deficit = BigInteger.ZERO;
            // Only built when a weight was actually declared, so the ordinary request never pays for it.
            Map<K, Long> weights = budget.request.missingWeights();
            BigInteger weighted = weights.isEmpty() ? null : BigInteger.ZERO;
            BigInteger inputCost = BigInteger.ZERO;
            BigInteger leafCost = BigInteger.ZERO;
            BigInteger absentCost = BigInteger.ZERO;
            boolean leafKnown = true;
            int rank = 0;
            boolean cycle = false;
            for (PatternEntry<K> input : pattern.inputs()) {
                budget.operation(0);
                // A fuzzy slot is satisfied by any variant it accepts, so presence is their total.
                UfoAmount available = input.reusable() && !input.variants().isEmpty()
                        ? state.availableAcross(input.variants())
                        : state.free(input.key());
                // A catalyst is required once and handed back; a durable carrier is consumed but one
                // unit survives several firings, so neither scales with the run count the way an
                // ordinary input does.
                UfoAmount needed;
                if (input.reusable()) {
                    needed = input.amount();
                } else if (input.durable()) {
                    needed = durableDraw(state, pattern, input, runs, false);
                } else {
                    needed = multiply(input.amount(), runs);
                }
                if (state.active.contains(input.key()) && available.compareTo(needed) < 0) {
                    cycle = true;
                    budget.cycleCuts++;
                }
                // A self-feeding pattern is admitted only when it gains material; a self-loop that
                // consumes at least as much as it makes cannot close.
                if (input.key().equals(key) && !growth) {
                    cycle = true;
                    budget.cycleCuts++;
                }
                if (!input.reusable()) {
                    UfoAmount carriers =
                            UfoAmount.of(available.asBigInteger().divide(input.amount().asBigInteger()));
                    capacity = capacity.min(input.durable()
                            ? multiply(carriers, UfoAmount.of(input.uses()))
                                    .subtractClamped(firingsClaimed(state, pattern, input))
                            : carriers);
                }
                BigInteger gap = needed.subtractClamped(available).asBigInteger();
                deficit = deficit.add(gap);
                if (weighted != null && gap.signum() != 0) {
                    weighted = weighted.add(gap.multiply(BigInteger.valueOf(weights.getOrDefault(
                            input.key(), 1L))));
                }
                inputCost = inputCost.add(needed.asBigInteger());
                rank = Math.max(rank, ranks.getOrDefault(input.key(), Integer.MAX_VALUE));
                BigInteger inputLeaf = leafCosts.get(input.key());
                BigInteger inputAbsent = budget.absentCosts.get(input.key());
                if (inputLeaf == null) {
                    leafKnown = false;
                } else {
                    // Per execution, so routes with different yields stay comparable.
                    leafCost = leafCost.add(inputLeaf.multiply(input.amount().asBigInteger()));
                    if (absentCost != null && inputAbsent != null) {
                        absentCost = absentCost.add(inputAbsent.multiply(input.amount().asBigInteger()));
                    } else {
                        absentCost = null;
                    }
                }
            }
            if (cycle) continue;
            BigInteger routeLeafCost = leafKnown ? leafCost : null;
            BigInteger routeAbsentCost = leafKnown ? absentCost : null;
            // The same slot carries whichever shortage measure the request asked for: units when
            // nothing was weighted, weighted units when something was. It is one field either way, so
            // a request without weights allocates exactly what it allocated before.
            BigInteger ranking = weighted == null ? deficit : weighted;
            options.add(new Candidate<>(pattern, runs, ranking, inputCost, rank, routeLeafCost, routeAbsentCost));
            if (!capacity.isZero() && capacity.compareTo(runs) < 0) {
                options.add(new Candidate<>(pattern, capacity, BigInteger.ZERO, inputCost, rank,
                        routeLeafCost, routeAbsentCost));
            }
        }
        for (int i = 0; i < options.size(); i++) budget.operation(0);
        Comparator<Candidate<K>> order = Comparator.<Candidate<K>>comparingInt(option -> option.pattern.pattern().priority())
                .reversed()
                .thenComparing(option -> option.deficit.signum() != 0)
                // Real leaf demand decides between routes that are otherwise equally viable. Without
                // it an identifier chose, and a correct plan could still ask for several times the
                // material the case needs.
                .thenComparing(option -> option.leafCost == null)
                // Leaves the request has none of come first: they are the shortage the route causes.
                .thenComparing(option -> option.absentCost, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(option -> option.leafCost, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(option -> option.rank)
                .thenComparing(option -> option.deficit)
                .thenComparing(option -> option.cost)
                // Two routes can cost the same and fire the same number of times while one leaves
                // material behind. The roadmap puts overproduction after the execution count and
                // before the identifier, so without this the identifier decided it and a plan could
                // carry avoidable surplus because its recipe happened to sort first. Overshoot is
                // runs times yield less the demand, and the demand is the same for every candidate
                // here, so ordering by runs and then by yield orders by overshoot exactly, without
                // computing it: the two keys are already on hand and both are needed anyway.
                .thenComparing(option -> option.runs)
                .thenComparing(option -> option.pattern.outputAmount(key))
                .thenComparing(option -> option.pattern.pattern().id());
        options.sort((left, right) -> { budget.operation(0); return order.compare(left, right); });
        // Allocated only when a choice exists, so a graph with a single route per key never pays for
        // the histogram. The winner is options.get(0); whatever separates it from options.get(1) is why.
        if (options.size() > 1) {
            if (budget.choiceLinks == null) {
                budget.choiceLinks = new long[CHOICE_LINK_COUNT];
            }
            for (int index = 0; index < CHOICE_LINK_COUNT; index++) {
                if (decides(index, key, options.get(0), options.get(1))) {
                    budget.choiceLinks[index]++;
                    break;
                }
            }
        }
        return options;
    }

    private static UfoAmount ceil(UfoAmount numerator, UfoAmount denominator) {
        BigInteger[] quotient = numerator.asBigInteger().divideAndRemainder(denominator.asBigInteger());
        return UfoAmount.of(quotient[1].signum() == 0 ? quotient[0] : quotient[0].add(BigInteger.ONE));
    }
    private static UfoAmount multiply(UfoAmount left, UfoAmount right) {
        return UfoAmount.of(left.asBigInteger().multiply(right.asBigInteger()));
    }

    private record Candidate<K>(CompiledPattern<K> pattern, UfoAmount runs, BigInteger deficit,
                                 BigInteger cost, int rank, BigInteger leafCost, BigInteger absentCost) {}
    /** A choice between routes one pass made: the key, the route taken and every route offered. */
    private record Decision<K>(K key, CraftingPattern<K> chosen, List<CraftingPattern<K>> routes) {
        static <K> Decision<K> of(K key, List<Candidate<K>> options) {
            ArrayList<CraftingPattern<K>> routes = new ArrayList<>(options.size());
            for (Candidate<K> option : options) {
                CraftingPattern<K> route = option.pattern.pattern();
                if (!routes.contains(route)) routes.add(route);
            }
            return new Decision<>(key, options.getFirst().pattern.pattern(), routes);
        }
    }
    /** Route pins to try, the shortfall of the run they came from, and insertion order for ties. */
    private record Variant<K>(Map<K, CraftingPattern<K>> preferences, int pins, BigInteger parentShortfall,
                              long sequence) {}
    private record Task<K>(K key, UfoAmount amount, int depth, CompiledPattern<K> pattern,
                           UfoAmount runs, Task<K> next) {}
    private static final class Choice<K> {
        final K key;
        final UfoAmount required;
        final int depth;
        final List<Candidate<K>> options;
        final Task<K> continuation;
        final int mark;
        final int scheduleSize;
        int next = 1;
        Choice(K key, UfoAmount required, int depth, List<Candidate<K>> options, Task<K> continuation,
                int mark, int scheduleSize) {
            this.key = key; this.required = required; this.depth = depth; this.options = options;
            this.continuation = continuation; this.mark = mark; this.scheduleSize = scheduleSize;
        }
    }

    private final class Budget {
        final PlanningRequest<K> request;
        final long started;
        final long timeout;
        long operations;
        int maximumDepth;
        /** Routes refused because they would have to reach into a key the plan is already expanding. */
        long cycleCuts;
        /**
         * One count per comparison link, or {@code null} when the plan never had a route to choose.
         * Nothing allocates it on the common single-route path.
         */
        long[] choiceLinks;
        /** Per-unit leaf demand counting only leaves the request has none of; empty without a choice. */
        Map<K, BigInteger> absentCosts = Map.of();
        /** Operation count and clock reading at which the exact search gives way; unset is MAX_VALUE. */
        long softOperations = Long.MAX_VALUE;
        long softDeadline = Long.MAX_VALUE;
        Budget(PlanningRequest<K> request, long started) {
            this.request = request; this.started = started;
            long nanos;
            try { nanos = request.limits().timeout().toNanos(); }
            catch (ArithmeticException overflow) { nanos = Long.MAX_VALUE; }
            timeout = nanos;
        }
        void operation(int depth) {
            maximumDepth = Math.max(maximumDepth, depth);
            if (depth > request.limits().maxDepth()) throw new PlanningHalt(PlanningResult.Status.DEPTH_LIMIT);
            if (operations >= request.limits().maxOperations()) {
                throw new PlanningHalt(PlanningResult.Status.OPERATION_LIMIT);
            }
            if (operations >= softOperations) throw new SoftLimitReached();
            if (++operations % request.limits().checkpointInterval() == 0) check();
        }
        void check() {
            if (request.cancellation().isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new PlanningHalt(PlanningResult.Status.CANCELLED);
            }
            long elapsed = nanoTime.getAsLong() - started;
            if (elapsed >= timeout) throw new PlanningHalt(PlanningResult.Status.TIMED_OUT);
            if (elapsed >= softDeadline) throw new SoftLimitReached();
        }
        /** Allows the exact search {@code 1/divisor} of what is left of each hard limit, and at most {@code allowance} operations. */
        void beginSoftLimit(int divisor, long allowance) {
            long elapsed = Math.max(0L, nanoTime.getAsLong() - started);
            softOperations = operations + Math.max(1L, Math.min(allowance,
                    (request.limits().maxOperations() - operations) / divisor));
            softDeadline = elapsed + Math.max(1L, (timeout - elapsed) / divisor);
        }
        void endSoftLimit() {
            softOperations = Long.MAX_VALUE;
            softDeadline = Long.MAX_VALUE;
        }
    }
    private static final class SoftLimitReached extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;
        SoftLimitReached() { super(null, null, false, false); }
    }
    private static final class PlanningHalt extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;
        final PlanningResult.Status status;
        PlanningHalt(PlanningResult.Status status) { super(null, null, false, false); this.status = status; }
    }

    private static final Comparator<CraftingPattern<?>> BY_ID = Comparator.comparing(CraftingPattern::id);

    private final class State {
        final Comparator<? super K> keys;
        final Map<K, UfoAmount> inventory;
        /** Stock left of each key this plan has drawn from; a key it never touched reads the inventory. */
        final Map<K, UfoAmount> stock;
        final Map<K, UfoAmount> crafted;
        final Map<K, UfoAmount> extracted;
        final Map<K, UfoAmount> missing;
        /**
         * Working stock already demanded of each catalyst. A catalyst is handed back, so one unit
         * covers every firing of its recipe; a plan that expands the same recipe in two steps, as it
         * does when a secondary output is chased separately from the primary, must not be charged for
         * the same catalyst twice.
         */
        Map<K, UfoAmount> presence;
        final ImmutableCraftingGraph<K> graph;
        /**
         * Firings already served by each durable carrier group. A carrier survives several firings, so
         * a plan that expands the same recipe twice - once for its primary and again to chase a
         * secondary - has to draw the carrier budget across the whole plan rather than at each ceiling
         * separately, or it charges for a tool that the schedule never consumes.
         */
        Map<DurableGroup<K>, UfoAmount> firings;
        /** Graph instances, so identity is equality; sorted by id only when the plan is frozen. */
        final Map<CraftingPattern<K>, UfoAmount> executions = new IdentityHashMap<>(8);
        final Set<K> active = new HashSet<>();
        final ArrayList<CraftingPlan.Execution<K>> schedule = new ArrayList<>();
        final ArrayList<Runnable> journal = new ArrayList<>();
        boolean journaling;
        /** Keys a simulated pass had to leave missing because no route was left for them. */
        int deadEnds;
        /**
         * Demand the aggregated pass has placed on keys it has not planned yet, or {@code null} outside
         * that pass. Those units are spoken for, so a route choice must not count them as available.
         */
        Map<K, UfoAmount> pending;
        State(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request) {
            this.graph = graph;
            keys = graph.keyComparator();
            // Hash maps while planning, sorted once when the plan is frozen: these are read and written on
            // every step, and the comparator was the hottest frame of a large plan. Stock is an overlay
            // on the request's inventory, which is a whole network's contents in production and was
            // copied for every state the planner made.
            inventory = request.inventory();
            stock = new HashMap<>();
            crafted = new HashMap<>(); extracted = new HashMap<>(); missing = new HashMap<>();
        }
        /** Total missing amount, the measure the discrepancy search ranks its variants by. */
        BigInteger shortfall() {
            BigInteger total = BigInteger.ZERO;
            for (UfoAmount amount : missing.values()) total = total.add(amount.asBigInteger());
            return total;
        }
        UfoAmount available(K key) {
            return stored(key).add(crafted.getOrDefault(key, UfoAmount.ZERO));
        }
        UfoAmount stored(K key) {
            UfoAmount left = stock.get(key);
            return left != null ? left : inventory.getOrDefault(key, UfoAmount.ZERO);
        }
        /** Records stock left of a key, zero included: an absent entry would read the inventory again. */
        void setStock(K key, UfoAmount amount) {
            UfoAmount previous = stock.put(key, amount);
            if (journaling) journal.add(() -> { if (previous == null) stock.remove(key); else stock.put(key, previous); });
        }
        /** Available units not already claimed by demand the aggregated pass has still to plan. */
        UfoAmount free(K key) {
            UfoAmount available = available(key);
            if (pending == null) return available;
            UfoAmount claimed = pending.get(key);
            return claimed == null ? available : available.subtractClamped(claimed);
        }
        /** Missing amount weighted by the request's missing weights, the measure answers are ranked by. */
        BigInteger weightedShortfall(PlanningRequest<K> request) {
            BigInteger total = BigInteger.ZERO;
            for (Map.Entry<K, UfoAmount> entry : missing.entrySet()) {
                total = total.add(entry.getValue().asBigInteger().multiply(BigInteger.valueOf(request.weightOf(entry.getKey()))));
            }
            return total;
        }
        BigInteger totalExecutions() {
            BigInteger total = BigInteger.ZERO;
            for (UfoAmount runs : executions.values()) total = total.add(runs.asBigInteger());
            return total;
        }
        /** How much of {@code key} is missing for it to be present at all. Consumes nothing. */
        UfoAmount requirePresent(K key, UfoAmount amount) {
            return requirePresent(key, amount, Set.of());
        }

        /** Availability summed across every key a fuzzy slot accepts. */
        UfoAmount availableAcross(Set<K> variants) {
            UfoAmount total = UfoAmount.ZERO;
            for (K variant : variants) {
                total = total.add(available(variant));
            }
            return total;
        }

        /**
         * The same, for a slot any of {@code variants} may satisfy: a logical tool accepts a damaged
         * one, so presence is the total across everything the slot accepts.
         */
        UfoAmount requirePresent(K key, UfoAmount amount, Set<K> variants) {
            UfoAmount present = variants.isEmpty() ? available(key) : availableAcross(variants);
            return amount.subtractClamped(present);
        }
        UfoAmount consume(K key, UfoAmount amount) {
            UfoAmount surplus = crafted.getOrDefault(key, UfoAmount.ZERO);
            UfoAmount taken = surplus.min(amount);
            if (!taken.isZero()) put(crafted, key, surplus.subtract(taken));
            UfoAmount rest = amount.subtract(taken);
            UfoAmount stored = stored(key);
            taken = stored.min(rest);
            if (!taken.isZero()) {
                setStock(key, stored.subtract(taken));
                add(extracted, key, taken);
            }
            return rest.subtract(taken);
        }
        <T> void add(Map<T, UfoAmount> map, T key, UfoAmount amount) {
            if (!amount.isZero()) put(map, key, map.getOrDefault(key, UfoAmount.ZERO).add(amount));
        }
        <T> void put(Map<T, UfoAmount> map, T key, UfoAmount amount) {
            UfoAmount previous = map.get(key);
            if (journaling) journal.add(() -> { if (previous == null) map.remove(key); else map.put(key, previous); });
            if (amount.isZero()) map.remove(key); else map.put(key, amount);
        }
        void activate(K key, boolean value) {
            boolean previous = active.contains(key);
            if (journaling) journal.add(() -> { if (previous) active.add(key); else active.remove(key); });
            if (value) active.add(key); else active.remove(key);
        }
        void rollback(int mark, int scheduleSize) {
            for (int i = journal.size() - 1; i >= mark; i--) journal.removeLast().run();
            schedule.subList(scheduleSize, schedule.size()).clear();
        }
        CraftingPlan<K> freeze(K target, UfoAmount requested) {
            TreeMap<K, UfoAmount> remaining = new TreeMap<>(keys);
            inventory.forEach((key, amount) -> {
                UfoAmount left = stock.getOrDefault(key, amount);
                if (!left.isZero()) remaining.put(key, left);
            });
            crafted.forEach((key, amount) -> remaining.merge(key, amount, UfoAmount::add));
            UfoAmount runs = executions.values().stream().reduce(UfoAmount.ZERO, UfoAmount::add);
            UfoAmount shortfall = missing.values().stream().reduce(UfoAmount.ZERO, UfoAmount::add);
            UfoAmount surplus = crafted.values().stream().reduce(UfoAmount.ZERO, UfoAmount::add);
            Map<K, UfoAmount> sortedExtracted = sorted(extracted, keys);
            Map<K, UfoAmount> sortedMissing = sorted(missing, keys);
            Map<CraftingPattern<K>, UfoAmount> sortedExecutions = sorted(executions, BY_ID);
            return new CraftingPlan<>(target, requested, sortedExecutions, sortedExtracted, sortedMissing, remaining,
                    schedule, new CraftingPlan.PlanQuality(missing.isEmpty(), executions.size(), runs, shortfall,
                    surplus), splitShortage(sortedMissing));
        }

        /** A map of at most one entry is already in order, so only a larger one pays for a sorted copy. */
        private static <T> Map<T, UfoAmount> sorted(Map<T, UfoAmount> map, Comparator<? super T> order) {
            if (map.size() <= 1) return map;
            TreeMap<T, UfoAmount> copy = new TreeMap<>(order);
            copy.putAll(map);
            return copy;
        }

        /**
         * Charges a missing key to what was consumed first, then to what wore out, and calls whatever
         * cannot be explained that way a seed. A key can be short for more than one reason at once, and
         * the consumed part is the one worth naming.
         */
        /** Shared by every plan that is short of nothing, so an empty split costs nothing. */
        private static final CraftingPlan.Shortage<Object> NO_SHORTAGE =
                new CraftingPlan.Shortage<>(Map.of(), Map.of(), Map.of());

        /** True when the pattern produces the key, which makes an input of it a seed rather than food. */
        private boolean produces(CompiledPattern<K> pattern, K key) {
            for (PatternEntry<K> output : pattern.outputs()) {
                if (output.key().equals(key)) {
                    return true;
                }
            }
            return false;
        }

        @SuppressWarnings("unchecked")
        private CraftingPlan.Shortage<K> splitShortage(Map<K, UfoAmount> missing) {
            if (missing.isEmpty()) {
                // Three maps for a plan with no shortage is three maps too many, and most plans have
                // none: the allocation gate charged seven and a half percent for it on a case whose
                // plan is complete.
                return (CraftingPlan.Shortage<K>) NO_SHORTAGE;
            }
            // Derived from the executions the plan settled on rather than accumulated while it was
            // built. Accumulating per draw charged every plan for a diagnostic only a short one needs,
            // which is the same reason the split is computed here rather than in the search.
            // One pass over the plan, for the keys that are actually missing: asking per key walked the
            // whole plan once per key. The graph is walked rather than indexed, so nothing is built to
            // look up what is already reachable.
            Map<K, UfoAmount[]> askedByKey = new HashMap<>();
            for (CompiledPattern<K> pattern : graph.compiledPatterns()) {
                UfoAmount runs = executions.get(pattern.pattern());
                if (runs == null) {
                    continue;
                }
                for (PatternEntry<K> input : pattern.inputs()) {
                    if (!missing.containsKey(input.key())) {
                        continue;
                    }
                    UfoAmount[] asked = askedByKey.computeIfAbsent(input.key(),
                            ignored -> new UfoAmount[] {UfoAmount.ZERO, UfoAmount.ZERO, UfoAmount.ZERO});
                    if (input.reusable()) {
                        asked[2] = asked[2].add(input.amount());
                    } else if (input.durable()) {
                        asked[1] = asked[1].add(multiply(input.amount(),
                                ceil(runs, UfoAmount.of(input.uses()))));
                    } else if (produces(pattern, input.key())) {
                        // A self-feeding input is the material it makes, so it is handed back.
                        asked[2] = asked[2].add(multiply(input.amount(), runs));
                    } else {
                        asked[0] = asked[0].add(multiply(input.amount(), runs));
                    }
                }
            }
            Map<K, UfoAmount> consumable = new LinkedHashMap<>();
            Map<K, UfoAmount> carrier = new LinkedHashMap<>();
            Map<K, UfoAmount> seed = new LinkedHashMap<>();
            for (K key : missing.keySet()) {
                UfoAmount[] asked = askedByKey.getOrDefault(key,
                        new UfoAmount[] {UfoAmount.ZERO, UfoAmount.ZERO, UfoAmount.ZERO});
                UfoAmount left = missing.get(key);
                UfoAmount byConsumable = left.min(asked[0]);
                if (!byConsumable.isZero()) {
                    consumable.put(key, byConsumable);
                    left = left.subtract(byConsumable);
                }
                UfoAmount byCarrier = left.min(asked[1]);
                if (!byCarrier.isZero()) {
                    carrier.put(key, byCarrier);
                    left = left.subtract(byCarrier);
                }
                if (!left.isZero()) {
                    // Whatever the plan's own demand cannot explain was asked for as a seed, or the key
                    // is the target of a request nothing routes at all, which is material to be found.
                    seed.put(key, left);
                }
            }
            return new CraftingPlan.Shortage<>(consumable, seed, carrier);
        }

    }
}
