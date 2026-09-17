package com.raishxn.ufocore.api.crafting.planner.differential;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

/**
 * Independent exact oracle for the minimum weighted external shortage of one differential corpus
 * scenario.
 *
 * <p>This class never calls {@code IterativeCraftingPlanner} and imports no production planner code:
 * it works only on the neutral corpus types ({@link CapabilityGraph}, {@link CapabilityPattern},
 * {@link CapabilityInput}, {@link CapabilityOutput}) and computes the shortage by bounded
 * enumeration. It is a test instrument and is not part of the shipped jar.
 *
 * <p>The semantics mirror {@link CapabilityPlanReplay}: exact inputs are consumed per firing,
 * reusable and fuzzy inputs are presence only, a finite-use carrier budget is shared across the
 * whole plan, emitters cost nothing, probabilistic outputs are ignored entirely, and a pattern may
 * be selected for any deterministic output (primary or byproduct). A key nothing produces is a leaf
 * and may be injected as external shortage; a key on a cycle may also be injected as the seed that
 * bootstraps the cycle, because the replay funds a cycle from injected material. Injection is
 * deliberately not allowed for an ordinary intermediate (a key with a producer that is not on a
 * cycle): that is what keeps "buy the target" from trivialising the objective.
 */
public final class MissingShortageOracle {

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final int MAX_BRANCH_STATES = 2_000_000;
    private static final int MAX_CYCLIC_STATES = 2_000_000;

    private final CapabilityGraph graph;
    private final String target;
    private final BigInteger amount;
    /** Exact integer weights; weight one is absent, exactly like {@code PlanningRequest}. */
    private final Map<String, Long> weights;
    private final Map<String, List<CapabilityPattern>> producers;
    private final Map<String, BigInteger> reusableStock;
    private final Set<String> leafKeys;
    private final Set<String> cycleKeys;
    private final Set<String> allKeys;
    private final Map<String, Solution> memo = new HashMap<>();
    private long explored;

    private MissingShortageOracle(CapabilityScenario scenario) {
        Objects.requireNonNull(scenario, "scenario");
        this.graph = scenario.graph();
        this.target = scenario.target();
        this.amount = scenario.amount().asBigInteger();
        this.weights = exactWeights(scenario);
        this.producers = deterministicProducers(graph);
        this.reusableStock = reusableStock(graph);
        this.allKeys = allKeys(graph, target);
        this.leafKeys = new HashSet<>();
        for (String key : allKeys) {
            if (!producers.containsKey(key)) leafKeys.add(key);
        }
        this.cycleKeys = cyclicKeys(graph, allKeys);
    }

    /** The computed minimum: weighted cost, one achieving shortage set, and work done. */
    public record Result(BigInteger cost, Map<String, BigInteger> shortage, long exploredStates) {
        public Result {
            shortage = java.util.Collections.unmodifiableMap(new TreeMap<>(shortage));
        }
    }

    /** Computes the exact minimum weighted external shortage for {@code scenario}. */
    public static Result minimum(CapabilityScenario scenario) {
        MissingShortageOracle oracle = new MissingShortageOracle(scenario);
        State root = oracle.rootState();
        Solution solution = oracle.solve(root);
        return new Result(solution.cost(), solution.shortage(), oracle.explored);
    }

    private State rootState() {
        State state = new State();
        state.need.put(target, amount);
        for (Map.Entry<String, CapabilityGraph.CapabilityStock> entry : graph.stock().entrySet()) {
            if (entry.getValue().kind() == CapabilityGraph.CapabilityStock.Kind.CONSUMABLE) {
                state.pool.put(entry.getKey(), entry.getValue().amount().asBigInteger());
            }
        }
        return state;
    }

    // ------------------------------------------------------------------ solving

    private Solution solve(State state) {
        normalize(state);
        if (state.need.isEmpty()) return Solution.EMPTY;
        List<Part> parts = components(state);
        if (parts.size() > 1) {
            BigInteger cost = ZERO;
            TreeMap<String, BigInteger> shortage = new TreeMap<>();
            for (Part part : parts) {
                Solution piece = solvePart(part);
                cost = cost.add(piece.cost());
                mergeInto(shortage, piece.shortage());
            }
            return new Solution(cost, shortage);
        }
        return solvePart(parts.get(0));
    }

    private Solution solvePart(Part part) {
        for (String key : part.keys()) {
            if (cycleKeys.contains(key)) return solveCyclic(part);
        }
        if (isForced(part)) return solveForced(part.state());
        return solveBranching(part.state());
    }

    /** True when every key of the component has at most one deterministic producer. */
    private boolean isForced(Part part) {
        for (String key : part.keys()) {
            if (producers.getOrDefault(key, List.of()).size() > 1) return false;
        }
        return true;
    }

    /**
     * Deterministic propagation: with at most one producer per key there is no route to choose, so
     * satisfying a need fixes the firing count. This keeps the 20 000-deep chain and the broad
     * Fibonacci chain off the call stack.
     */
    private Solution solveForced(State state) {
        State current = state.copy();
        BigInteger cost = ZERO;
        TreeMap<String, BigInteger> shortage = new TreeMap<>();
        long guard = 0;
        while (!current.need.isEmpty()) {
            if (++guard > 5_000_000L) throw new IllegalStateException("forced propagation did not settle");
            normalize(current);
            if (current.need.isEmpty()) break;
            String key = chooseKey(current.need);
            List<CapabilityPattern> routes = producers.getOrDefault(key, List.of());
            if (routes.isEmpty()) {
                BigInteger quantity = current.need.remove(key);
                cost = cost.add(costOf(key, quantity));
                mergeInto(shortage, key, quantity);
                continue;
            }
            CapabilityPattern pattern = routes.get(0);
            BigInteger firings = ceilDiv(current.need.get(key), outputAmount(pattern, key));
            Injection injection = fire(current, pattern, firings);
            cost = cost.add(injection.cost());
            mergeInto(shortage, injection.shortage());
        }
        return new Solution(cost, shortage);
    }

    /** Exact minimisation with route choice, memoised on the canonical state. */
    private Solution solveBranching(State state) {
        String memoKey = state.memoKey();
        Solution cached = memo.get(memoKey);
        if (cached != null) return cached;
        if (++explored > MAX_BRANCH_STATES) {
            throw new IllegalStateException("branch search exceeded " + MAX_BRANCH_STATES + " states");
        }
        String key = chooseKey(state.need);
        List<CapabilityPattern> routes = producers.getOrDefault(key, List.of());
        Solution best;
        if (routes.isEmpty()) {
            State child = state.copy();
            BigInteger quantity = child.need.remove(key);
            BigInteger cost = costOf(key, quantity);
            Solution rest = solve(child);
            TreeMap<String, BigInteger> shortage = new TreeMap<>();
            mergeInto(shortage, key, quantity);
            mergeInto(shortage, rest.shortage());
            best = new Solution(cost.add(rest.cost()), shortage);
        } else {
            best = null;
            for (CapabilityPattern pattern : routes) {
                BigInteger out = outputAmount(pattern, key);
                if (out.signum() == 0) continue;
                State child = state.copy();
                BigInteger firings = ceilDiv(child.need.get(key), out);
                Injection injection = fire(child, pattern, firings);
                Solution rest = solve(child);
                TreeMap<String, BigInteger> shortage = new TreeMap<>();
                mergeInto(shortage, injection.shortage());
                mergeInto(shortage, rest.shortage());
                Solution candidate = new Solution(injection.cost().add(rest.cost()), shortage);
                if (best == null || candidate.cost().compareTo(best.cost()) < 0) best = candidate;
            }
            if (best == null) throw new IllegalStateException("no usable producer for " + key);
        }
        memo.put(memoKey, best);
        return best;
    }

    // ------------------------------------------------------------------ cycle solver

    /**
     * Bounded Dijkstra over operational states for a component that contains a cycle. Firing is
     * costless; cost is only the weighted material injected to fund a firing that the current pools
     * cannot cover. Injecting just in time is optimal for a fixed firing order, and the search
     * explores every order within the bound.
     */
    private Solution solveCyclic(Part part) {
        State initial = part.state().copy();
        Map<String, BigInteger> demand = new TreeMap<>(initial.need);
        BigInteger sumDemand = ZERO;
        for (BigInteger value : demand.values()) sumDemand = sumDemand.add(value);
        long firingCap = 32L + 8L * sumDemand.longValueExact();
        PriorityQueue<CycNode> queue = new PriorityQueue<>(Comparator.comparing(CycNode::cost));
        Map<String, BigInteger> best = new HashMap<>();
        queue.add(new CycNode(initial, ZERO, new TreeMap<>(), 0L));
        long visited = 0;
        while (!queue.isEmpty()) {
            CycNode node = queue.poll();
            String key = node.state().memoKey();
            BigInteger seen = best.get(key);
            if (seen != null && seen.compareTo(node.cost()) <= 0) continue;
            best.put(key, node.cost());
            if (++visited > MAX_CYCLIC_STATES) {
                throw new IllegalStateException("cyclic search exceeded " + MAX_CYCLIC_STATES + " states");
            }
            if (satisfies(node.state(), demand)) {
                return new Solution(node.cost(), node.shortage());
            }
            if (node.firings() >= firingCap) continue;
            for (CapabilityPattern pattern : part.patterns()) {
                State child = node.state().copy();
                Injection injection = fireCyclic(child, pattern);
                if (injection == null) continue;
                TreeMap<String, BigInteger> shortage = new TreeMap<>(node.shortage());
                mergeInto(shortage, injection.shortage());
                queue.add(new CycNode(child, node.cost().add(injection.cost()), shortage,
                        node.firings() + 1L));
            }
        }
        throw new IllegalStateException("cyclic component has no plan within the firing bound "
                + firingCap + " for demand " + demand);
    }

    private boolean satisfies(State state, Map<String, BigInteger> demand) {
        for (Map.Entry<String, BigInteger> entry : demand.entrySet()) {
            if (state.pool.getOrDefault(entry.getKey(), ZERO).compareTo(entry.getValue()) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * One firing of {@code pattern} in the cycle solver. Inputs are drawn from the pools; a deficit
     * is injected only when the key is a leaf or lies on a cycle, otherwise the firing is not yet
     * possible and {@code null} is returned.
     */
    private Injection fireCyclic(State state, CapabilityPattern pattern) {
        BigInteger cost = ZERO;
        TreeMap<String, BigInteger> shortage = new TreeMap<>();
        for (CapabilityInput input : pattern.inputs()) {
            if (input.kind() == CapabilityInput.Kind.EMITTER) continue;
            if (presenceOnly(input)) {
                BigInteger amountNeeded = input.amount().asBigInteger();
                BigInteger present = presenceOf(state, input);
                if (present.compareTo(amountNeeded) < 0) {
                    BigInteger deficit = amountNeeded.subtract(present);
                    if (!injectable(input.key())) return null;
                    state.presence.merge(input.key(), deficit, BigInteger::add);
                    cost = cost.add(costOf(input.key(), deficit));
                    mergeInto(shortage, input.key(), deficit);
                }
                continue;
            }
            BigInteger total = draw(state, pattern, input, ONE);
            BigInteger taken = state.pool.getOrDefault(input.key(), ZERO).min(total);
            if (taken.signum() > 0) {
                BigInteger left = state.pool.get(input.key()).subtract(taken);
                if (left.signum() == 0) state.pool.remove(input.key());
                else state.pool.put(input.key(), left);
            }
            BigInteger deficit = total.subtract(taken);
            if (deficit.signum() > 0) {
                if (!injectable(input.key())) return null;
                cost = cost.add(costOf(input.key(), deficit));
                mergeInto(shortage, input.key(), deficit);
            }
        }
        for (CapabilityOutput output : pattern.outputs()) {
            if (output.kind() == CapabilityOutput.Kind.PROBABILISTIC) continue;
            state.pool.merge(output.key(), output.amount().asBigInteger(), BigInteger::add);
        }
        return new Injection(cost, shortage);
    }

    private boolean injectable(String key) {
        return leafKeys.contains(key) || cycleKeys.contains(key);
    }

    // ------------------------------------------------------------------ transitions

    /**
     * One batch of {@code firings} of {@code pattern} in the acyclic solvers. Exact and finite-use
     * inputs are drawn from the pools and any shortfall is added to the need map; reusable and fuzzy
     * inputs are presence-checked and a missing presence is injected straight away.
     */
    private Injection fire(State state, CapabilityPattern pattern, BigInteger firings) {
        BigInteger cost = ZERO;
        TreeMap<String, BigInteger> shortage = new TreeMap<>();
        for (CapabilityInput input : pattern.inputs()) {
            if (input.kind() == CapabilityInput.Kind.EMITTER) continue;
            if (presenceOnly(input)) {
                BigInteger amountNeeded = input.amount().asBigInteger();
                BigInteger present = presenceOf(state, input);
                if (present.compareTo(amountNeeded) < 0) {
                    BigInteger deficit = amountNeeded.subtract(present);
                    state.presence.merge(input.key(), deficit, BigInteger::add);
                    cost = cost.add(costOf(input.key(), deficit));
                    mergeInto(shortage, input.key(), deficit);
                }
                continue;
            }
            BigInteger total = draw(state, pattern, input, firings);
            BigInteger taken = state.pool.getOrDefault(input.key(), ZERO).min(total);
            if (taken.signum() > 0) {
                BigInteger left = state.pool.get(input.key()).subtract(taken);
                if (left.signum() == 0) state.pool.remove(input.key());
                else state.pool.put(input.key(), left);
            }
            BigInteger remainder = total.subtract(taken);
            if (remainder.signum() > 0) {
                state.need.merge(input.key(), remainder, BigInteger::add);
            }
        }
        for (CapabilityOutput output : pattern.outputs()) {
            if (output.kind() == CapabilityOutput.Kind.PROBABILISTIC) continue;
            state.pool.merge(output.key(), output.amount().asBigInteger().multiply(firings), BigInteger::add);
        }
        return new Injection(cost, shortage);
    }

    /** Amount of one input one batch draws, honouring the shared finite-use carrier budget. */
    private BigInteger draw(State state, CapabilityPattern pattern, CapabilityInput input,
                            BigInteger firings) {
        if (input.kind() != CapabilityInput.Kind.FINITE_USE) {
            return input.amount().asBigInteger().multiply(firings);
        }
        String firingKey = pattern.id() + '\u0000' + input.key();
        BigInteger before = state.firings.getOrDefault(firingKey, ZERO);
        BigInteger owed = carriers(before.add(firings), input.uses()).subtract(carriers(before, input.uses()));
        state.firings.put(firingKey, before.add(firings));
        return input.amount().asBigInteger().multiply(owed);
    }

    private BigInteger presenceOf(State state, CapabilityInput input) {
        BigInteger present = state.presence.getOrDefault(input.key(), ZERO);
        for (String accepted : acceptedKeys(input)) {
            present = present.add(reusableStock.getOrDefault(accepted, ZERO));
        }
        return present;
    }

    // ------------------------------------------------------------------ components

    private List<Part> components(State state) {
        Set<String> keys = new LinkedHashSet<>(state.need.keySet());
        Set<CapabilityPattern> patterns = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(keys);
        while (!queue.isEmpty()) {
            String key = queue.poll();
            for (CapabilityPattern pattern : producers.getOrDefault(key, List.of())) {
                if (!patterns.add(pattern)) continue;
                for (CapabilityInput input : pattern.inputs()) {
                    if (input.kind() == CapabilityInput.Kind.EMITTER) continue;
                    for (String accepted : acceptedKeys(input)) {
                        if (keys.add(accepted)) queue.add(accepted);
                    }
                }
            }
        }
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (String key : keys) adjacency.put(key, new LinkedHashSet<>());
        for (CapabilityPattern pattern : patterns) {
            Set<String> involved = new LinkedHashSet<>();
            for (CapabilityInput input : pattern.inputs()) {
                if (input.kind() != CapabilityInput.Kind.EMITTER) involved.addAll(acceptedKeys(input));
            }
            for (CapabilityOutput output : pattern.outputs()) {
                if (output.kind() != CapabilityOutput.Kind.PROBABILISTIC) involved.add(output.key());
            }
            involved.retainAll(keys);
            for (String left : involved) {
                for (String right : involved) {
                    adjacency.get(left).add(right);
                    adjacency.get(right).add(left);
                }
            }
        }
        List<Part> parts = new ArrayList<>();
        Set<String> assigned = new HashSet<>();
        for (String seed : new ArrayList<>(state.need.keySet())) {
            if (!assigned.add(seed)) continue;
            Set<String> componentKeys = new LinkedHashSet<>();
            Deque<String> walk = new ArrayDeque<>();
            walk.add(seed);
            componentKeys.add(seed);
            while (!walk.isEmpty()) {
                String key = walk.poll();
                for (String neighbour : adjacency.getOrDefault(key, Set.of())) {
                    if (componentKeys.add(neighbour)) {
                        assigned.add(neighbour);
                        walk.add(neighbour);
                    }
                }
            }
            List<CapabilityPattern> componentPatterns = new ArrayList<>();
            Set<String> componentPatternIds = new HashSet<>();
            for (CapabilityPattern pattern : patterns) {
                if (touches(pattern, componentKeys)) {
                    componentPatterns.add(pattern);
                    componentPatternIds.add(pattern.id());
                }
            }
            parts.add(new Part(filter(state, componentKeys, componentPatternIds), componentKeys,
                    componentPatterns));
        }
        if (parts.isEmpty()) throw new IllegalStateException("need set has no component");
        // A single component may still carry keys reachable from its needs that the walk above did
        // not include when a need key was reached from another need key; merge by re-walking once.
        if (parts.size() == 1) {
            Set<String> keysOnly = new LinkedHashSet<>(parts.get(0).keys());
            List<CapabilityPattern> patternList = parts.get(0).patterns();
            Set<String> patternIds = new HashSet<>();
            for (CapabilityPattern pattern : patternList) patternIds.add(pattern.id());
            return List.of(new Part(filter(state, keysOnly, patternIds), keysOnly, patternList));
        }
        return parts;
    }

    private static boolean touches(CapabilityPattern pattern, Set<String> keys) {
        for (CapabilityInput input : pattern.inputs()) {
            if (input.kind() != CapabilityInput.Kind.EMITTER) {
                for (String accepted : acceptedKeys(input)) if (keys.contains(accepted)) return true;
            }
        }
        for (CapabilityOutput output : pattern.outputs()) {
            if (output.kind() != CapabilityOutput.Kind.PROBABILISTIC && keys.contains(output.key())) {
                return true;
            }
        }
        return false;
    }

    private static State filter(State state, Set<String> keys, Set<String> patternIds) {
        State filtered = state.copy();
        filtered.need.keySet().retainAll(keys);
        filtered.pool.keySet().retainAll(keys);
        filtered.presence.keySet().retainAll(keys);
        filtered.firings.keySet().removeIf(firingKey -> patternIds.stream().noneMatch(firingKey::startsWith));
        return filtered;
    }

    // ------------------------------------------------------------------ state

    private static final class State {
        final TreeMap<String, BigInteger> need = new TreeMap<>();
        final TreeMap<String, BigInteger> pool = new TreeMap<>();
        final TreeMap<String, BigInteger> firings = new TreeMap<>();
        final TreeMap<String, BigInteger> presence = new TreeMap<>();

        State copy() {
            State copy = new State();
            copy.need.putAll(need);
            copy.pool.putAll(pool);
            copy.firings.putAll(firings);
            copy.presence.putAll(presence);
            return copy;
        }

        String memoKey() {
            StringBuilder builder = new StringBuilder();
            append(builder, 'n', need);
            append(builder, 'p', pool);
            append(builder, 'f', firings);
            append(builder, 'r', presence);
            return builder.toString();
        }

        private static void append(StringBuilder builder, char tag, TreeMap<String, BigInteger> map) {
            builder.append(tag).append('[');
            for (Map.Entry<String, BigInteger> entry : map.entrySet()) {
                if (entry.getValue().signum() == 0) continue;
                builder.append(entry.getKey()).append('=').append(entry.getValue()).append(',');
            }
            builder.append(']');
        }
    }

    private record Part(State state, Set<String> keys, List<CapabilityPattern> patterns) {
    }

    private record Injection(BigInteger cost, TreeMap<String, BigInteger> shortage) {
    }

    private record Solution(BigInteger cost, TreeMap<String, BigInteger> shortage) {
        static final Solution EMPTY = new Solution(ZERO, new TreeMap<>());
    }

    private record CycNode(State state, BigInteger cost, TreeMap<String, BigInteger> shortage,
                           long firings) {
    }

    // ------------------------------------------------------------------ helpers

    private static void normalize(State state) {
        for (Map.Entry<String, BigInteger> entry : new ArrayList<>(state.need.entrySet())) {
            String key = entry.getKey();
            BigInteger available = state.pool.getOrDefault(key, ZERO);
            if (available.signum() == 0) continue;
            BigInteger consumed = entry.getValue().min(available);
            BigInteger left = available.subtract(consumed);
            if (left.signum() == 0) state.pool.remove(key);
            else state.pool.put(key, left);
            BigInteger need = entry.getValue().subtract(consumed);
            if (need.signum() == 0) state.need.remove(key);
            else state.need.put(key, need);
        }
        state.need.values().removeIf(value -> value.signum() == 0);
        state.pool.values().removeIf(value -> value.signum() == 0);
    }

    private String chooseKey(Map<String, BigInteger> need) {
        String best = null;
        int bestRoutes = Integer.MAX_VALUE;
        for (Map.Entry<String, BigInteger> entry : need.entrySet()) {
            int routes = producers.getOrDefault(entry.getKey(), List.of()).size();
            if (routes < bestRoutes) {
                bestRoutes = routes;
                best = entry.getKey();
            }
        }
        return best;
    }

    private BigInteger outputAmount(CapabilityPattern pattern, String key) {
        BigInteger total = ZERO;
        for (CapabilityOutput output : pattern.outputs()) {
            if (output.kind() != CapabilityOutput.Kind.PROBABILISTIC && output.key().equals(key)) {
                total = total.add(output.amount().asBigInteger());
            }
        }
        return total;
    }

    private BigInteger costOf(String key, BigInteger quantity) {
        return quantity.multiply(BigInteger.valueOf(weights.getOrDefault(key, 1L)));
    }

    private static boolean presenceOnly(CapabilityInput input) {
        return input.kind() == CapabilityInput.Kind.REUSABLE || input.kind() == CapabilityInput.Kind.FUZZY;
    }

    private static List<String> acceptedKeys(CapabilityInput input) {
        return input.kind() == CapabilityInput.Kind.FUZZY ? input.alternatives() : List.of(input.key());
    }

    private static BigInteger carriers(BigInteger firings, int uses) {
        BigInteger[] quotient = firings.divideAndRemainder(BigInteger.valueOf(uses));
        return quotient[1].signum() == 0 ? quotient[0] : quotient[0].add(ONE);
    }

    private static BigInteger ceilDiv(BigInteger numerator, BigInteger denominator) {
        BigInteger[] quotient = numerator.divideAndRemainder(denominator);
        return quotient[1].signum() == 0 ? quotient[0] : quotient[0].add(ONE);
    }

    private static void mergeInto(TreeMap<String, BigInteger> target, String key, BigInteger amount) {
        if (amount == null || amount.signum() == 0) return;
        target.merge(key, amount, BigInteger::add);
    }

    private static void mergeInto(TreeMap<String, BigInteger> target, Map<String, BigInteger> source) {
        if (source == null) return;
        for (Map.Entry<String, BigInteger> entry : source.entrySet()) {
            mergeInto(target, entry.getKey(), entry.getValue());
        }
    }

    // ------------------------------------------------------------------ graph analysis

    private static Map<String, Long> exactWeights(CapabilityScenario scenario) {
        Map<String, Long> result = new HashMap<>();
        scenario.missingWeights().forEach((key, weight) -> {
            long exact = Math.round(weight);
            if (Math.abs(weight - exact) > 1e-9D) {
                throw new IllegalArgumentException(
                        "missing weight for " + key + " is not a whole number: " + weight);
            }
            if (exact != 1L) result.put(key, exact);
        });
        return result;
    }

    private static Map<String, List<CapabilityPattern>> deterministicProducers(CapabilityGraph graph) {
        Map<String, List<CapabilityPattern>> result = new HashMap<>();
        for (CapabilityPattern pattern : graph.patterns()) {
            Set<String> seen = new HashSet<>();
            for (CapabilityOutput output : pattern.outputs()) {
                if (output.kind() == CapabilityOutput.Kind.PROBABILISTIC) continue;
                if (seen.add(output.key())) {
                    result.computeIfAbsent(output.key(), key -> new ArrayList<>()).add(pattern);
                }
            }
        }
        return result;
    }

    private static Map<String, BigInteger> reusableStock(CapabilityGraph graph) {
        Map<String, BigInteger> result = new HashMap<>();
        for (Map.Entry<String, CapabilityGraph.CapabilityStock> entry : graph.stock().entrySet()) {
            if (entry.getValue().kind() == CapabilityGraph.CapabilityStock.Kind.REUSABLE) {
                result.merge(entry.getKey(), entry.getValue().amount().asBigInteger(), BigInteger::add);
            }
        }
        return result;
    }

    private static Set<String> allKeys(CapabilityGraph graph, String target) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(target);
        keys.addAll(graph.stock().keySet());
        for (CapabilityPattern pattern : graph.patterns()) {
            for (CapabilityInput input : pattern.inputs()) keys.addAll(acceptedKeys(input));
            for (CapabilityOutput output : pattern.outputs()) keys.add(output.key());
        }
        return keys;
    }

    /**
     * Keys that can reach themselves through "consumed key to produced key" edges. Such a key may be
     * injected as a cycle seed; an ordinary intermediate may not.
     */
    private static Set<String> cyclicKeys(CapabilityGraph graph, Set<String> allKeys) {
        Map<String, Set<String>> edges = new HashMap<>();
        for (String key : allKeys) edges.put(key, new LinkedHashSet<>());
        for (CapabilityPattern pattern : graph.patterns()) {
            Set<String> consumed = new LinkedHashSet<>();
            for (CapabilityInput input : pattern.inputs()) {
                if (input.kind() == CapabilityInput.Kind.EMITTER) continue;
                consumed.addAll(acceptedKeys(input));
            }
            Set<String> produced = new LinkedHashSet<>();
            for (CapabilityOutput output : pattern.outputs()) {
                if (output.kind() == CapabilityOutput.Kind.PROBABILISTIC) continue;
                produced.add(output.key());
            }
            for (String from : consumed) {
                for (String to : produced) edges.get(from).add(to);
            }
        }
        Set<String> cyclic = new HashSet<>();
        for (String key : allKeys) {
            if (reaches(edges, key, key)) cyclic.add(key);
        }
        return cyclic;
    }

    private static boolean reaches(Map<String, Set<String>> edges, String start, String goal) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String next : edges.getOrDefault(start, Set.of())) {
            if (next.equals(goal)) return true;
            if (seen.add(next)) queue.add(next);
        }
        while (!queue.isEmpty()) {
            String key = queue.poll();
            for (String next : edges.getOrDefault(key, Set.of())) {
                if (next.equals(goal)) return true;
                if (seen.add(next)) queue.add(next);
            }
        }
        return false;
    }
}
