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
 * Selection is deterministic; the first feasible plan is returned, not a claim of global optimality.
 * Quantities are batched. Stateful feedback/catalyst optimization belongs to a separate adapter.
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
                if (!search(graph, request, state, ranks, budget, false)) {
                    // Shortage reporting starts from a fresh snapshot, never speculative leftovers.
                    state = new State(graph, request);
                    search(graph, request, state, ranks, budget, true);
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

    private boolean search(ImmutableCraftingGraph<K> graph, PlanningRequest<K> request, State state,
                            Map<K, Integer> ranks, Budget budget, boolean simulate) {
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
                    : candidates(graph, task.key, required, state, ranks, budget);
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
        Task<K> pending = new Task<>(key, covered, depth, option.pattern, option.runs, next);
        state.activate(key, true);
        List<PatternEntry<K>> inputs = option.pattern.inputs();
        for (int i = inputs.size() - 1; i >= 0; i--) {
            budget.operation(depth);
            PatternEntry<K> input = inputs.get(i);
            pending = new Task<>(input.key(), multiply(input.amount(), option.runs), depth + 1, null, null, pending);
        }
        return pending;
    }

    private List<Candidate<K>> candidates(ImmutableCraftingGraph<K> graph, K key, UfoAmount required,
                                          State state, Map<K, Integer> ranks, Budget budget) {
        ArrayList<Candidate<K>> options = new ArrayList<>();
        for (CompiledPattern<K> pattern : graph.compiledPatternsFor(key)) {
            budget.operation(0);
            UfoAmount runs = ceil(required, pattern.outputAmount(key));
            UfoAmount capacity = runs;
            BigInteger deficit = BigInteger.ZERO;
            BigInteger inputCost = BigInteger.ZERO;
            int rank = 0;
            boolean cycle = false;
            for (PatternEntry<K> input : pattern.inputs()) {
                budget.operation(0);
                UfoAmount available = state.available(input.key());
                UfoAmount needed = multiply(input.amount(), runs);
                if (state.active.contains(input.key()) && available.compareTo(needed) < 0) cycle = true;
                // Positive self-reproduction and feedback need explicit seed semantics.
                if (input.key().equals(key)) cycle = true;
                capacity = capacity.min(UfoAmount.of(available.asBigInteger().divide(input.amount().asBigInteger())));
                deficit = deficit.add(needed.subtractClamped(available).asBigInteger());
                inputCost = inputCost.add(needed.asBigInteger());
                rank = Math.max(rank, ranks.getOrDefault(input.key(), Integer.MAX_VALUE));
            }
            if (cycle) continue;
            options.add(new Candidate<>(pattern, runs, deficit, inputCost, rank));
            if (!capacity.isZero() && capacity.compareTo(runs) < 0) {
                options.add(new Candidate<>(pattern, capacity, BigInteger.ZERO, inputCost, rank));
            }
        }
        for (int i = 0; i < options.size(); i++) budget.operation(0);
        Comparator<Candidate<K>> order = Comparator.<Candidate<K>>comparingInt(option -> option.pattern.pattern().priority())
                .reversed()
                .thenComparing(option -> option.deficit.signum() != 0)
                .thenComparingInt(option -> option.rank)
                .thenComparing(option -> option.deficit)
                .thenComparing(option -> option.cost)
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
                                 BigInteger cost, int rank) {}
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
