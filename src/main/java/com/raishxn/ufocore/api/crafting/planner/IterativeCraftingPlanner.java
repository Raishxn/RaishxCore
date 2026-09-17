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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Exact, stack-safe planner over immutable snapshots.
 * Single-route DAGs aggregate demands in topological order. Other graphs use explicit
 * continuations and a reversible journal, including choices that conflict with later siblings.
 * Selection is deterministic. Between routes that are otherwise equally viable it prefers the one
 * with the smaller total leaf demand, computed bottom-up once per plan, so a correct plan is not
 * several times more expensive than it has to be. That is a preference, not a proof: the first
 * feasible plan is still returned, and general multi-route optimality under shared stock is not
 * claimed. Quantities are batched. A key no recipe declares as something it makes is still planned
 * when a recipe yields it as a secondary output, since firing that recipe for its primary is the only
 * way to obtain it and naming the secondary as missing would name a key nobody can supply; a
 * secondary is never preferred over a declared route. Stateful feedback/catalyst optimization
 * belongs to a separate adapter, and is one: see {@link FeedbackCyclePlanner}, which states a
 * recycling loop as a decaying catalyst this planner can balance and rewrites the plan back to the
 * real patterns. That adapter composes with this one rather than being built into it, so a caller
 * that does not want it pays nothing for it.
 */
public final class IterativeCraftingPlanner<K> {
    private final LongSupplier nanoTime;

    public IterativeCraftingPlanner() { this(System::nanoTime); }
    IterativeCraftingPlanner(LongSupplier nanoTime) { this.nanoTime = Objects.requireNonNull(nanoTime); }

    public PlanningResult<K> plan(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request) {
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
                Map<K, Integer> ranks = reachability(graph, request, budget);
                // The leaf demand pass only informs a choice between routes. A graph where every key
                // has a single route has nothing to choose, so it must not pay for the computation.
                Map<K, BigInteger> leafCosts = hasRouteChoice(graph)
                        ? leafCosts(graph, budget) : Map.of();
                if (!search(graph, request, state, ranks, leafCosts, budget, false)) {
                    // Shortage reporting starts from a fresh snapshot, never speculative leftovers.
                    state = new State(graph, request);
                    search(graph, request, state, ranks, leafCosts, budget, true);
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
                budget.operations, budget.maximumDepth, Math.max(0, nanoTime.getAsLong() - started)));
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

    /** Linear hypergraph reachability. Each input edge is visited once, even in a reverse-sorted deep chain. */
    private Map<K, Integer> reachability(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request,
                                        Budget budget) {
        Map<K, Integer> ranks = new HashMap<>();
        ArrayDeque<K> queue = new ArrayDeque<>();
        request.inventory().forEach((key, amount) -> { ranks.put(key, 0); queue.add(key); });
        Map<CompiledPattern<K>, Integer> pending = new IdentityHashMap<>();
        Map<CompiledPattern<K>, Integer> maxima = new IdentityHashMap<>();
        for (CompiledPattern<K> pattern : graph.compiledPatterns()) {
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
            for (CompiledPattern<K> pattern : graph.consumersOf(key)) {
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

    /** True when some key is produced by more than one pattern, so route choice actually exists. */
    private static <K> boolean hasRouteChoice(ImmutableCraftingGraph<K> graph) {
        for (CompiledPattern<K> pattern : graph.compiledPatterns()) {
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
    private Map<K, BigInteger> leafCosts(ImmutableCraftingGraph<K> graph, Budget budget) {
        Map<K, BigInteger> costs = new HashMap<>();
        Map<CompiledPattern<K>, Integer> unresolvedInputs = new IdentityHashMap<>();
        Map<CompiledPattern<K>, BigInteger> inputTotals = new IdentityHashMap<>();
        Map<K, Integer> unresolvedProducers = new HashMap<>();
        Set<K> keys = new HashSet<>();

        for (CompiledPattern<K> pattern : graph.compiledPatterns()) {
            budget.operation(0);
            unresolvedInputs.put(pattern, pattern.inputs().size());
            inputTotals.put(pattern, BigInteger.ZERO);
            for (PatternEntry<K> input : pattern.inputs()) {
                keys.add(input.key());
            }
            for (PatternEntry<K> output : pattern.outputs()) {
                keys.add(output.key());
                unresolvedProducers.merge(output.key(), 1, Integer::sum);
            }
        }

        ArrayDeque<K> finalized = new ArrayDeque<>();
        for (K key : keys) {
            if (unresolvedProducers.getOrDefault(key, 0) == 0) {
                costs.put(key, BigInteger.ONE);
                finalized.add(key);
            }
        }

        while (!finalized.isEmpty()) {
            K key = finalized.removeFirst();
            BigInteger cost = costs.get(key);
            for (CompiledPattern<K> pattern : graph.consumersOf(key)) {
                budget.operation(0);
                BigInteger units = BigInteger.ZERO;
                int matches = 0;
                for (PatternEntry<K> input : pattern.inputs()) {
                    if (input.key().equals(key)) {
                        units = units.add(input.amount().asBigInteger());
                        matches++;
                    }
                }
                if (matches == 0) continue;
                inputTotals.merge(pattern, cost.multiply(units), BigInteger::add);
                if (unresolvedInputs.merge(pattern, -matches, Integer::sum) != 0) continue;
                resolveLeafCost(pattern, costs, unresolvedProducers, inputTotals, finalized);
            }
        }
        return costs;
    }

    private void resolveLeafCost(CompiledPattern<K> pattern, Map<K, BigInteger> costs,
                                 Map<K, Integer> unresolvedProducers,
                                 Map<CompiledPattern<K>, BigInteger> inputTotals,
                                 ArrayDeque<K> finalized) {
        BigInteger total = inputTotals.get(pattern);
        for (PatternEntry<K> output : pattern.outputs()) {
            K key = output.key();
            BigInteger perUnit = ceilDiv(total, output.amount().asBigInteger());
            BigInteger existing = costs.get(key);
            if (existing == null || perUnit.compareTo(existing) < 0) {
                costs.put(key, perUnit);
            }
            if (unresolvedProducers.merge(key, -1, Integer::sum) == 0) {
                costs.putIfAbsent(key, BigInteger.ONE);
                finalized.add(key);
            }
        }
    }

    private static BigInteger ceilDiv(BigInteger numerator, BigInteger denominator) {
        BigInteger[] quotient = numerator.divideAndRemainder(denominator);
        return quotient[1].signum() == 0 ? quotient[0] : quotient[0].add(BigInteger.ONE);
    }

    private boolean search(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request, State state,
                            Map<K, Integer> ranks, Map<K, BigInteger> leafCosts, Budget budget,
                            boolean simulate) {
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
        Map<K, UfoAmount> consumedPerKey = new HashMap<>();
        for (PatternEntry<K> input : option.pattern.inputs()) {
            if (input.reusable() || input.key().equals(key)) continue;
            UfoAmount drawn = input.durable()
                    ? multiply(input.amount(), ceil(option.runs, UfoAmount.of(input.uses())))
                    : multiply(input.amount(), option.runs);
            consumedPerKey.merge(input.key(), drawn, UfoAmount::add);
        }
        for (PatternEntry<K> input : option.pattern.inputs()) {
            budget.operation(depth);
            if (input.reusable()) {
                // A catalyst must be on hand but is handed back, so it is never consumed and no
                // demand is propagated for it. It is checked once, when the pattern is expanded, so a
                // catalyst that this same plan would craft only later still reads as missing.
                UfoAmount alsoConsumed = consumedPerKey.getOrDefault(input.key(), UfoAmount.ZERO);
                // Only the working stock is charged once for the whole plan; the decay is charged every
                // time, because it really is consumed every time.
                UfoAmount working = input.amount()
                        .subtractClamped(state.presence.getOrDefault(input.key(), UfoAmount.ZERO));
                if (!working.isZero()) {
                    state.put(state.presence, input.key(), input.amount());
                }
                state.add(state.missing, input.key(), state.requirePresent(input.key(),
                        working.add(alsoConsumed), input.variants()));
                continue;
            }
            UfoAmount drawn = input.durable()
                    ? multiply(input.amount(), ceil(option.runs, UfoAmount.of(input.uses())))
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

    private List<Candidate<K>> candidates(ImmutableCraftingGraph<K> graph, K key, UfoAmount required,
                                          State state, Map<K, Integer> ranks,
                                          Map<K, BigInteger> leafCosts, Budget budget) {
        ArrayList<Candidate<K>> options = new ArrayList<>();
        List<CompiledPattern<K>> routes = graph.compiledPatternsFor(key);
        if (routes.isEmpty()) {
            // Nothing declares this key as something it makes. That is not the same as nothing being
            // able to make it: a secondary product comes out of whatever makes its primary, so a
            // shortage reported here would be a shortage of a key no one can buy rather than of the
            // material that actually has to be supplied.
            routes = graph.secondaryRoutesFor(key);
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
            BigInteger selfNet = selfProduced.subtract(selfConsumed).asBigInteger();
            boolean growth = !selfConsumed.isZero() && selfNet.signum() > 0;
            // The caller already took the seed from stock, so it is not subtracted again: each run
            // adds the net, and the runs needed are the ceiling of what is still missing over it.
            UfoAmount runs = growth
                    ? UfoAmount.of(required.asBigInteger().add(selfNet).subtract(BigInteger.ONE).divide(selfNet))
                    : ceil(required, pattern.outputAmount(key));
            UfoAmount capacity = runs;
            BigInteger deficit = BigInteger.ZERO;
            BigInteger inputCost = BigInteger.ZERO;
            BigInteger leafCost = BigInteger.ZERO;
            boolean leafKnown = true;
            int rank = 0;
            boolean cycle = false;
            for (PatternEntry<K> input : pattern.inputs()) {
                budget.operation(0);
                // A fuzzy slot is satisfied by any variant it accepts, so presence is their total.
                UfoAmount available = input.reusable() && !input.variants().isEmpty()
                        ? state.availableAcross(input.variants())
                        : state.available(input.key());
                // A catalyst is required once and handed back; a durable carrier is consumed but one
                // unit survives several firings, so neither scales with the run count the way an
                // ordinary input does.
                UfoAmount needed;
                if (input.reusable()) {
                    needed = input.amount();
                } else if (input.durable()) {
                    needed = multiply(input.amount(), ceil(runs, UfoAmount.of(input.uses())));
                } else {
                    needed = multiply(input.amount(), runs);
                }
                if (state.active.contains(input.key()) && available.compareTo(needed) < 0) cycle = true;
                // A self-feeding pattern is admitted only when it gains material; a self-loop that
                // consumes at least as much as it makes cannot close.
                if (input.key().equals(key) && !growth) cycle = true;
                if (!input.reusable()) {
                    UfoAmount carriers =
                            UfoAmount.of(available.asBigInteger().divide(input.amount().asBigInteger()));
                    capacity = capacity.min(input.durable()
                            ? multiply(carriers, UfoAmount.of(input.uses())) : carriers);
                }
                deficit = deficit.add(needed.subtractClamped(available).asBigInteger());
                inputCost = inputCost.add(needed.asBigInteger());
                rank = Math.max(rank, ranks.getOrDefault(input.key(), Integer.MAX_VALUE));
                BigInteger inputLeaf = leafCosts.get(input.key());
                if (inputLeaf == null) {
                    leafKnown = false;
                } else {
                    // Per execution, so routes with different yields stay comparable.
                    leafCost = leafCost.add(inputLeaf.multiply(input.amount().asBigInteger()));
                }
            }
            if (cycle) continue;
            BigInteger routeLeafCost = leafKnown ? leafCost : null;
            options.add(new Candidate<>(pattern, runs, deficit, inputCost, rank, routeLeafCost));
            if (!capacity.isZero() && capacity.compareTo(runs) < 0) {
                options.add(new Candidate<>(pattern, capacity, BigInteger.ZERO, inputCost, rank,
                        routeLeafCost));
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
                .thenComparing(option -> option.pattern.pattern().id())
                .thenComparing(option -> option.runs, Comparator.reverseOrder());
        options.sort((left, right) -> { budget.operation(0); return order.compare(left, right); });
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
                                 BigInteger cost, int rank, BigInteger leafCost) {}
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
            if (++operations % request.limits().checkpointInterval() == 0) check();
        }
        void check() {
            if (request.cancellation().isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new PlanningHalt(PlanningResult.Status.CANCELLED);
            }
            if (nanoTime.getAsLong() - started >= timeout) throw new PlanningHalt(PlanningResult.Status.TIMED_OUT);
        }
    }
    private static final class PlanningHalt extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;
        final PlanningResult.Status status;
        PlanningHalt(PlanningResult.Status status) { super(null, null, false, false); this.status = status; }
    }

    private final class State {
        final Comparator<? super K> keys;
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
        final Map<K, UfoAmount> presence = new HashMap<>();
        final Map<CraftingPattern<K>, UfoAmount> executions = new TreeMap<>(Comparator.comparing(CraftingPattern::id));
        final Set<K> active = new HashSet<>();
        final ArrayList<CraftingPlan.Execution<K>> schedule = new ArrayList<>();
        final ArrayList<Runnable> journal = new ArrayList<>();
        boolean journaling;
        State(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request) {
            keys = graph.keyComparator();
            stock = new TreeMap<>(keys); stock.putAll(request.inventory());
            crafted = new TreeMap<>(keys); extracted = new TreeMap<>(keys); missing = new TreeMap<>(keys);
        }
        UfoAmount available(K key) {
            return stock.getOrDefault(key, UfoAmount.ZERO).add(crafted.getOrDefault(key, UfoAmount.ZERO));
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
            UfoAmount stored = stock.getOrDefault(key, UfoAmount.ZERO);
            taken = stored.min(rest);
            if (!taken.isZero()) {
                put(stock, key, stored.subtract(taken));
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
            remaining.putAll(stock);
            crafted.forEach((key, amount) -> remaining.merge(key, amount, UfoAmount::add));
            UfoAmount runs = executions.values().stream().reduce(UfoAmount.ZERO, UfoAmount::add);
            UfoAmount shortfall = missing.values().stream().reduce(UfoAmount.ZERO, UfoAmount::add);
            UfoAmount surplus = crafted.values().stream().reduce(UfoAmount.ZERO, UfoAmount::add);
            return new CraftingPlan<>(target, requested, executions, extracted, missing, remaining, schedule,
                    new CraftingPlan.PlanQuality(missing.isEmpty(), executions.size(), runs, shortfall, surplus));
        }
    }
}
