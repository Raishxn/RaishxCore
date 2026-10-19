# Planner head-to-head: RaishxCore vs Thunderbolt 1.0.6 `CraftPlannerV2`

This report answers one question with measurements: is RaishxCore's staged planner better than the
`CraftPlannerV2` that AE2 Lightning Tech and AE2-VM use through Thunderbolt?

**Short answer, after the improvements below: RaishxCore now wins on plan quality everywhere and
on speed almost everywhere.**

- **Quality:** RaishxCore wins on 28 of 102 cases and ties on the other 74; Thunderbolt wins none.
  RaishxCore matches the known optimum on all 93 cases that have one, and Thunderbolt on 71.
- **Speed (production entry point, geometric mean):**
  - corpus: 1.85× faster than Thunderbolt;
  - generated graphs: 4.2× faster, faster on 12 of 13;
  - regression shapes: about even (0.82).
- **Before these changes** Thunderbolt was 4–24× faster on large multi-route graphs, and it won two
  cases on quality. Those baseline results and their causes are kept below, because the changes
  were made from that analysis.

## Run it

```shell
./gradlew plannerHeadToHead
./gradlew plannerHeadToHead --args="$PWD/build/reports/planner generated/"   # filter by case-id regex
```

The task writes two files:

- `build/reports/planner/head-to-head.md`: every case, with the summary tables reproduced here.
- `build/reports/planner/head-to-head.csv`: one row per case and engine.

A full run takes about 4 minutes.

### How Thunderbolt is put on the classpath

- The Thunderbolt classes come from `../references/thunderbolt-1.0.6`. To point somewhere else, pass
  `-Pthunderbolt106Classes=<dir>`.
- `thunderbolt106PlannerClasses` copies only `com/moakiee/thunderbolt/core/planner/**` into
  `build/classes/thunderbolt106`. That package depends on `java.base` only.
- Only the `headToHead` source set (`src/headToHead/java`) sees those classes. Nothing from
  Thunderbolt is compiled into the RaishxCore jar, and the normal `test` and `build` tasks do not
  touch them.

## Method

**Engines.** Three engines are measured:

- `RaishxCore.plan` and `RaishxCore.planFast` go through the same path as the corpus and as
  `Ae2PlannerBridge`: `RaishxCoreSemanticModel.lower`, then `FeedbackCyclePlanner.analyse`, then
  `IterativeCraftingPlanner`, then `expand`. They use the shipped `CoreConfig` limits: 10M
  operations, depth 100k, a 2 s deadline and a checkpoint every 128 operations.
- `prod` is the entry point the bridge would pick for that graph. It uses `planFast` once the graph
  has at least 8 route-choice alternatives (`exactSearchChoiceLimit`), and `plan` otherwise.
- `Thunderbolt-1.0.6` is `CraftPlannerV2.plan(graph, target, amount)` with its default visit cap and
  its scaled search budget. That is the call its AE2 glue (`FastCraftingPlanner`) makes.

**Lowering.** Every case is one engine-neutral `CapabilityScenario`. Thunderbolt's lowering mirrors
the existing Thunderbolt 2.0 corpus adapter (`ThunderboltCapabilityPlanner`), with these choices:

- **Multi-output patterns.** A pattern is registered under its first primary output only, and the
  other outputs become byproducts. That is what Thunderbolt's own AE2 glue does: `FastCraftingPlanner`
  adds a pattern only when `key.equals(details.getPrimaryOutput().what())`.
- **Probabilistic outputs** are dropped for both engines. A chance output is never promised.
- **Emitter inputs** become no requirement for both engines.

**What is timed.** Lowering and graph construction are not timed for either engine, because both
integrations cache the graph per grid revision. The timed region is:

- for RaishxCore, the feedback-cycle analysis, planning and plan expansion;
- for Thunderbolt, the `plan` call.

**Timing.** Before any measurement, every case runs twice on every engine. Then each case runs one
more warm-up call, followed by 3–31 measured runs depending on its cost. The tables report the
median. Each call runs on a worker thread with a 60 s harness deadline; no call ever reached it.

**Metrics.** Per case and engine the benchmark records:

- the status: complete, shortage, engine limit, unsupported, timeout or error;
- the total reported missing amount, and its weighted cost under the case's missing weights;
- the total number of pattern executions;
- whether two runs gave different answers (none did);
- Thunderbolt's `budgetExhausted` flag.

**Balance check.** `PlanBalance` is an order-free material check that works for any engine:

- stock + reported shortage + everything produced must cover everything consumed plus the request;
- every catalyst must be present.

Thunderbolt exposes no schedule, so the corpus replay oracle cannot judge it. This check is weaker
than a replay, but it treats both engines the same.

**Scenarios (102; the baseline run had the first 101).** The cases come from four sources:

- **The frozen differential corpus (81):** all 27 groups × 3 material modes.
- **Regression shapes from `IterativeCraftingPlannerTest` (8):**
  - The shared two-route plate used in 20, 60 or 200 places with one missing raw. The only correct
    shortage is `{zrare: 1}`.
  - The same shape where greedy starves the rod and a single route pin on `plate` makes it complete.
  - Nested two-route choices of depth 11 and 20 over an unobtainable leaf.
- **Generated layered graphs (13):** seeded, with 258, 858 and 2405 patterns; the 13th asks the 2405-pattern network for an intermediate (`ample-narrow`).
  - Shared hub intermediates. About a third of the keys have a second route, which leans on odd raws.
    Some patterns batch and some emit byproducts.
  - Four stock policies:
    - `ample`: everything is plentiful.
    - `scarce_hub`: the raw a quarter of the bottom layer starts from is absent.
    - `contended`: even raws are at half of what the first-declared routes need.
    - `dry`: there is no stock at all.

Machine: Java 21.0.12 OpenJDK, 16 CPUs, `-Xmx4g -XX:+UseParallelGC`, Linux (CachyOS). Each
table cell is a single run. A second full run reproduced every status and every amount, and every
time ratio in the summary was within about 10%.

## After the improvements

Measured with the same benchmark on the same machine after the planner changes listed under
"What was changed" below. One case was added: `generated/l-12x150-ample-narrow` asks the
2405-pattern network for an intermediate a few layers up. That is the production shape: the
network holds every recipe, and a request only reaches part of it.

| engine | complete | shortage | balance fail | optimal on known (93) |
|---|---:|---:|---:|---:|
| RaishxCore.plan | 64 | 38 | 0 | **93** |
| RaishxCore.planFast | 64 | 38 | 0 | **93** (was 88/92) |
| RaishxCore (production pick) | 64 | 38 | 0 | **93** |
| Thunderbolt-1.0.6 | 50 | 52 | 0 | 71 |

**Quality against Thunderbolt** (better / tie / worse): 28 / 74 / 0 for every RaishxCore entry
point. Before: 27 / 72 / 2 for the production pick.

**Speed** (geometric mean of Thunderbolt median ÷ RaishxCore median; > 1 means RaishxCore is faster):

| origin | RaishxCore.plan | RaishxCore.planFast | production pick | production pick before |
|---|---:|---:|---:|---:|
| corpus (81) | 1.76 | 1.72 | 1.85 | 1.87 |
| regression (8) | 0.78 | 1.08 | 0.82 | 0.58 |
| generated (13) | 2.24 | 4.22 | 4.22 | 0.81 |
| all (102) | 1.70 | 1.86 | 1.93 | 1.55 |

**Cases that decided the baseline,** before and after:

| case | RaishxCore before | RaishxCore after | Thunderbolt 1.0.6 |
|---|---|---|---|
| generated/l-12x150-ample (fast) | C 1 453 482 exec, 101 ms | C 1 457 632 exec, **3.50 ms** | C 1 895 491 exec, 4.22 ms |
| generated/l-12x150-contended (fast) | C, 101 ms | C, **3.58 ms** | C, 4.23 ms |
| generated/l-12x150-scarce_hub (fast) | S 140 830, 111 ms | **S 68 041, 4.11 ms** | S 510 911 (budget hit), 423 ms |
| generated/l-12x150-dry (fast) | S 2 176 228, 104 ms | S 2 180 189, **3.99 ms** | S 21 600 073 (budget hit), 638 ms |
| generated/l-12x150-ample-narrow (fast) | — | C, **0.075 ms** | C, 0.086 ms |
| generated/s-5x40-contended (fast) | S 20, 0.76 ms | **C**, 0.65 ms | C, 0.48 ms |
| generated/s-5x40-scarce_hub (fast) | S 356, 0.71 ms | **S 239**, 0.45 ms | S 245, 1.19 ms |
| regression/shared-plate-200-route-pin (exact) | C, 1.86 ms | C, **0.36 ms** | C, 0.53 ms |
| regression/shared-plate-200-missing-raw (exact) | S 1, 1.27 ms | S 1, 0.93 ms | S 1, **0.64 ms** |
| corpus/multi-dag/greedy-trap/missing (`plan`) | S* 32, 825 ms | S* 32, **22.9 ms** | S* 32, 0.44 ms |
| corpus/deep-chain/linear-20000/minimum (exact) | C, 51 ms | C, **24 ms** | C, 49 ms |

**Where Thunderbolt is still faster:**
- the exact mode on the shared-plate shapes with a missing raw (about 1.5×);
- `s-5x40-contended` (0.48 vs 0.65 ms);
- `greedy-trap/missing` through `plan()`, which production does not use there.

In all three cases the exact planner runs the aggregated pass and then, because the result is
short, its own stages as well, to keep their quality guarantee.

**The large shortage cases depend on the entry point.** `RaishxCore.plan` still spends about 50 ms
on `l-12x150-scarce_hub` and `l-12x150-dry` for the same reason. On those two the fast planner's
answer differs from `plan`'s by at most 0.2%, and it is what production uses on graphs with ≥ 8
route choices.

### What was changed

All changes are in `IterativeCraftingPlanner`, `ImmutableCraftingGraph` and `CraftingPattern`:

1. **Aggregated pass for route-choice DAGs** (`aggregatedOrder`, `aggregatedPass`).
   - When the request's cone is acyclic and uses plain inputs only, every key is planned once with
     its total demand, in topological order, using the existing route comparator.
   - A route's inputs count as available only beyond what earlier choices in the pass already
     claimed (`State.pending`).
   - A coproduct may be netted against an earlier firing. The schedule is then rebuilt by
     simulation, and if no executable order exists the request falls back to the per-occurrence
     planner. Without coproducts the reverse of the pass is already a valid order.
2. **Blame-directed repair** (`aggregatedPlan`, `blame`).
   - The largest shortage is traced back through the recipes that consumed it, and the route on a
     contended key that carries the most of it is capped. Then the pass runs again.
   - The repair stops early when the shortage is provably unavoidable or after 3 passes without
     improvement.
   - A cap never makes an intermediate short: if every route is capped, the best one takes the rest.
3. **Absent-leaf cost in route choice.** Next to the leaf demand, each route carries the demand on
   leaves the request has *none* of, and that is compared first. This is what moved the generated
   `scarce_hub` cases from losing to Thunderbolt to beating it by 1.03–7.5×.
4. **Best incomplete variant** in the exact discrepancy search, by weighted shortage, instead of
   always the greedy plan.
5. **Exact stages bounded by greedy cost.** They may use at most 256× the greedy pass's operations
   (minimum 50 000), on top of the existing half-of-limits soft budget.
6. **Cone-local precomputation.** Reachability ranks and leaf costs are computed over the request's
   cone with a cone-local use index, instead of the whole graph and its global consumer lists. The
   graph indexes gain hash mirrors for lookups.
7. **Cheaper state.**
   - Planner state uses hash maps, sorted only when the plan is frozen.
   - Stock is an overlay on the request inventory instead of a copy per state.
   - `CraftingPattern` caches its hash code.

### Mode selection

- The fast planner always starts from the aggregated pass and keeps its answer even when it is
  short. Measured against the per-occurrence pass, it tied or won on every shortage case except
  the two largest, where it was 0.2–0.3% worse at a fifteenth of the cost.
- The exact planner starts from it once the cone has ≥ 16 patterns. On a shortage it still runs
  its own stages and keeps the better answer.

### Gates

- `./gradlew test`: all tests pass.
- `plannerDifferential`: 81/81, gate passed.
- `plannerBenchmark`: gate passed, after re-recording the baseline
  (`src/benchmark/baseline/planner-baseline.csv`) with the user's approval.
  - Before the re-record, the allocation gate failed on two tiny cases: `byproduct` (2 patterns,
    1.25× the old baseline) and `sibling_conflict` (4 patterns, 1.75×). `sibling_conflict` was
    already failing at 1.64× from the earlier staged-planner work.
  - The extra cost is the cone index, about 1–2 KB per plan, plus the sorted copy made when a plan
    is frozen (`chain2048` +10%).
  - `wide1024` allocates 1.11 MB per plan instead of the 1.30 MB the staged planner had reached.

## Baseline results (before the improvements)

### Outcomes

| engine | complete | shortage | limit / error | balance fail | optimal on known (92) |
|---|---:|---:|---:|---:|---:|
| RaishxCore.plan | 63 | 38 | 0 | 0 | **92** |
| RaishxCore.planFast | 58 | 43 | 0 | 0 | 88 |
| RaishxCore (production pick) | 62 | 39 | 0 | 0 | **92** |
| Thunderbolt-1.0.6 | 49 | 52 | 0 | 0 | 70 |

**How to read "optimal on known":**

- A case counts as known when the corpus or the regression gives a minimum shortage witness, or when
  a complete plan is known to exist (the corpus, the regressions and generated `ample`).
- "Optimal" means the planner returned a complete plan, or a shortage equal to the weighted minimum.

### Quality against Thunderbolt (better, tie, or worse)

A complete plan beats any shortage, and a smaller weighted shortage beats a larger one.

| origin | RaishxCore.plan | RaishxCore.planFast | production pick |
|---|---|---|---|
| corpus (81) | 22 / 59 / 0 | 22 / 58 / 1 | 22 / 59 / 0 |
| regression (8) | 0 / 8 / 0 | 0 / 5 / 3 | 0 / 8 / 0 |
| generated (12) | 5 / 6 / 1 | 5 / 5 / 2 | 5 / 5 / 2 |
| **all (101)** | **27 / 73 / 1** | 27 / 68 / 6 | **27 / 72 / 2** |

### Plan size when both return a complete plan

The count is total pattern executions. RaishxCore never needs more executions than Thunderbolt
outside two small corpus feedback loops. On every generated graph it needs 9–32% fewer.

| origin | both complete | RC fewer | equal | TB fewer |
|---|---:|---:|---:|---:|
| corpus | 40 | 1 | 37 | 2 (`raw-feedback-loop` minimum/unbounded) |
| regression | 3 | 0 | 3 | 0 |
| generated | 6 | 6 | 0 | 0 |

### Speed (geometric mean of Thunderbolt median ÷ RaishxCore median; > 1 means RaishxCore is faster)

| origin | RaishxCore.plan | RaishxCore.planFast | production pick |
|---|---:|---:|---:|
| corpus (81) | 1.68 | 2.20 | 1.87 |
| regression (8) | 0.55 | 0.82 | 0.58 |
| generated (12) | 0.56 | 0.81 | 0.81 |
| all (101) | 1.35 | 1.81 | 1.55 |

### Decisive cases

**How to read the cells:** each cell is `status missing executions median-ms`.

- Status: `C` complete, `S` shortage.
- `*`: the result matches the known optimum.
- `b`: Thunderbolt reported its search budget exhausted.

The `prod` column says which RaishxCore entry point production would use. Every other case is in the
generated report.

| case | patterns | prod | RaishxCore.plan | RaishxCore.planFast | Thunderbolt 1.0.6 |
|---|---:|---|---|---|---|
| regression/shared-plate-200-missing-raw | 203 | exact | S* 1 401 1.27 | S* 1 401 1.09 | S* 1 401 0.651 |
| regression/shared-plate-200-route-pin | 204 | exact | C* 0 601 1.86 | S 200 601 0.964 | C* 0 601 0.495 |
| regression/nested-choices-20 | 40 | fast | S* 1 20 0.058 | S* 1 20 0.046 | S* 1 20 0.070 |
| generated/s-5x40-scarce_hub | 258 | fast | S 356 3501 0.819 | S 356 3501 0.714 | **S 245** 6035 1.16 |
| generated/s-5x40-contended | 258 | fast | C 0 3355 **38.15** | S 20 3539 0.758 | **C 0 4732 0.470** |
| generated/m-8x80-ample | 858 | fast | C* 0 32787 5.90 | C* 0 32787 5.63 | C* 0 37338 **1.23** |
| generated/m-8x80-scarce_hub | 858 | fast | **S 2226** 32787 6.10 | S 2226 32787 5.65 | S 5873 132228 18.50 |
| generated/l-12x150-ample | 2405 | fast | C* 0 1453482 101 | C* 0 1453482 98.6 | C* 0 1895491 **4.21** |
| generated/l-12x150-contended | 2405 | fast | C 0 1453482 102 | C 0 1453482 102 | C 0 2128387 **4.35** |
| generated/l-12x150-scarce_hub | 2405 | fast | **S 140830** 1472714 103 | S 140830 1472714 111 | Sb 510911 7311896 500 |
| generated/l-12x150-dry | 2405 | fast | **S 2176228** 1424841 104 | S 2176228 1424841 104 | Sb 21600073 12634954 736 |
| corpus/multi-dag/fibonacci-depth12/minimum | 20 | fast | C* 0 20 0.054 | C* 0 20 0.050 | Sb 64 84 3.90 |
| corpus/multi-dag/greedy-trap/missing | 97 | fast | S* 32 65 **825** | S* 32 65 0.230 | S* 32 65 0.349 |
| corpus/cycle/self-growth/minimum | 1 | exact | C* 0 7 0.004 | C* 0 7 0.003 | S 7 0 0.033 |
| corpus/byproduct/secondary-outbids-declared/minimum | 2 | exact | C* 0 1 0.005 | C* 0 1 0.004 | S 5 1 0.007 |
| corpus/multi-dag/weighted-leaf-cost/missing | 2 | exact | S* 10 1 0.008 | S* 10 1 0.006 | S 1 1 0.011 |

## Where RaishxCore won (baseline)

1. **Requesting a secondary output (15 corpus cases).** Thunderbolt indexes a pattern only under its
   primary output. A request that is cheapest through another pattern's secondary output therefore
   has no route. This affects the `secondary-*`, `surplus-secondary-demand`,
   `reuse-across-expansions` and `catalyst-and-carrier` groups, in their minimum, unbounded and
   most missing modes. This limitation is in the model, not the search: Thunderbolt's own AE2 glue
   filters on `getPrimaryOutput()`.
2. **Route choice by leaf cost when stock does not decide.**
   - Thunderbolt orders a key's routes by capacity, meaning the stock the route can reach. With no
     stock to compare, it falls back to declaration order: on the generated `dry` graphs it never
     took a second route.
   - RaishxCore's comparator ranks routes by leaf demand. That cuts the reported shortage 2–10× on
     the generated `dry` graphs and the m and l `scarce_hub` graphs, and the executions up to 9×.
   - The same ranking finds the multi-route `fibonacci-depth12` plan directly. Thunderbolt runs out
     of budget there (`b`) and reports 64 missing on a case that can be completed.
   - **Caveat:** the generator's second route is usually cheaper in raws. On a graph where the
     first-declared route is also the cheapest, this advantage shrinks. In AE2 the declaration order
     is the player's pattern priority.
3. **Weighted shortages.** Thunderbolt has no missing weights. In `weighted-shortage` and
   `weighted-leaf-cost` it reports the smallest unweighted shortage, which is the expensive one.
4. **Positive feedback (`self-growth`).** RaishxCore's `FeedbackCyclePlanner` states the loop as a
   decaying catalyst and completes it. Thunderbolt makes two runs and reports the whole request
   missing.
5. **Small graphs are faster.** On the corpus RaishxCore is about 1.9× faster, with tiny absolute
   numbers (microseconds). Thunderbolt's fixed costs are the reachability estimate, preparing the
   graph and the variant bookkeeping.
6. **Plan size.** On every generated graph where both are complete, RaishxCore's plan needs 9–32%
   fewer executions. The cause is again leaf-cost route choice.

## Where Thunderbolt won (baseline), and why

1. **Speed on large multi-route graphs (4–24×).** On `l-12x150-ample` (2405 patterns) RaishxCore
   takes 101 ms and Thunderbolt 4.2 ms. The causes are structural, not tuning:
   - **Thunderbolt** builds the reachable sub-graph in post-order once (`buildDag`), then runs one
     `linearPass`: each key is visited once with its *aggregated* demand. `allocateLinear` splits
     that demand across the key's routes by capacity. Everything is on primitive `long` maps.
   - **RaishxCore** uses its aggregated path (`planDag`) only when no key has a route choice. Every
     other graph goes through `search`, which re-ranks the candidate routes
     (`IterativeCraftingPlanner.candidates`) every time a key is demanded.
   - A JFR profile of `l-12x150-ample` puts about two thirds of the samples inside `search` under
     `candidates`. The top self-time frames are `TreeMap` natural-order comparisons and `BigInteger`
     arithmetic, in `State.put` and `State.consume`.
   - Thunderbolt solved every generated graph in a single plan run (`planRuns = 1` in
     `planDetailed` diagnostics). Its discrepancy search never ran.
2. **Contended stock (`s-5x40-contended`).**
   - Thunderbolt is complete in 0.47 ms in one run. Its `allocationRepair` re-runs the linear pass
     up to 4 + 2·contended times. Each time, `tightenBlamedRoute` caps the contended route that the
     largest shortfall is proportionally blamed on.
   - `RaishxCore.plan` also completes, but needs 206k operations (38 ms) of route-pin discrepancy
     search.
   - `planFast`, which production picks because the graph has ≥ 8 route choices, stops at a
     shortage of 20.
   - Blame-directed caps fix a shared-raw conflict with arithmetic on aggregate amounts.
     Pins fix it by re-running the whole search for each pin.
3. **Amount of an unavoidable shortage (`s-5x40-scarce_hub`).** Thunderbolt reports 245 missing
   and RaishxCore 356; both are short only of `raw00`.
   - Thunderbolt's single linear pass already places that demand better.
   - RaishxCore's `exactPlan` returns the greedy plan once `provablyShort` holds, and it keeps no
     "best incomplete" variant, so nothing tries to reduce the amount.
   - Thunderbolt keeps `betterIncompletePlan` (fewer missing kinds, then a smaller total) across its
     variants.
   - At the m and l sizes this reverses: Thunderbolt's capacity order lacks leaf costs, and it
     exhausts its budget on `l`.
4. **Regression shapes (2–4× faster).** On `shared-plate-*-route-pin` Thunderbolt is complete in one
   pass. The plate demand is aggregated (200 plates as a single demand), so the conflict with the
   rod resolves inside `allocateLinear` without search. RaishxCore needs its greedy pass and then a
   pin. Both reach the same plan; Thunderbolt is 3.7× faster at 200 uses.
5. **A RaishxCore pathology unrelated to Thunderbolt.** `RaishxCore.plan` takes 825 ms on
   `corpus/multi-dag/greedy-trap/missing`, against 0.23 ms for `planFast` and the same answer.
   - Production picks `planFast` there (97 patterns, 32 choices), so players do not see it.
   - Any caller of `plan()` on a choice-heavy graph with a real shortage pays the discrepancy budget
     before getting the greedy answer back.

## The Thunderbolt features named in the brief

| feature | what it is in 1.0.6 | does it explain a Thunderbolt win here? |
|---|---|---|
| `priorityRoots` | The cycle re-orientation part of a plan variant. When the first run cut cycle outputs, up to 4 variants plan from a different cycle member first (`CycleAnalysis.mayReorient`). | **No.** Every Thunderbolt win is on an acyclic graph. On the corpus cycles both planners tie, except `self-growth`, where RaishxCore wins. |
| `PositiveIntegerLinearSolver` | An exact simplex plus integer rounding over `BigInteger`. A constant-pool scan of every class in the 1.0.6 jar shows that **no other class references it.** It ships, but the planner never calls it. | **No.** It is dead code in 1.0.6. Thunderbolt 2.0 replaces it with `BoundedIntegerLinearSolver` (called from `ConservativeFeedbackAnalysis`) and an optional CP-SAT engine. That version is not measured here; see `plannerCapabilityMatrix`. |
| `ReusableStockMatcher` | A max-flow assignment of reusable or fuzzy seed demand per route to concrete variant stock. It tries exact variants first, so two routes never claim the same physical seed. | **No.** On the corpus catalyst, durability and fuzzy cases it ties RaishxCore wherever both can express the case. It would only matter with several routes competing for one variant pool, which no current case builds. That gap is worth a corpus case. |

**The mechanisms that do explain Thunderbolt's wins:**

- `buildDag` / `linearPass`: a topological pass over aggregated per-key demand, using primitive
  longs.
- `allocateLinear`: splitting a key's demand across routes by capacity.
- `allocationRepair` / `tightenBlamedRoute`: blame-directed capacity caps.
- `betterIncompletePlan`: keeping the best shortage found across variants.

## What this suggested for RaishxCore

Written before the improvements. Items 1–5 were implemented as described under "What was changed";
item 5 was implemented as hash maps, an inventory overlay and cached hashes rather than a `long`
fast path:

1. **Extend the aggregated DAG path to graphs with route choices.** Visit each key once in
   topological order with its total demand, and split it across routes the way `allocateLinear`
   does, ranking routes with RaishxCore's leaf-cost comparator. This targets the 4–24× gap and keeps
   RaishxCore's quality advantage.
2. **Try blame-directed caps before pin search on contended keys.** This would let `planFast` (the
   production pick) solve `s-5x40-contended` in one or two passes instead of reporting a shortage.
3. **Keep the best incomplete variant** in `exactPlan` and the discrepancy search, by weighted
   shortage, within the soft budget. An unavoidable shortage would then be minimised in amount, not
   just proven.
4. **Bound `plan()` on choice-heavy graphs with a proven shortage** (the `greedy-trap/missing`
   825 ms).
5. **Use a `long` fast path** for amounts that fit, instead of `UfoAmount`/`BigInteger` and
   comparator-ordered `TreeMap`s in the hot `State` maps.

## Caveats

- **Only Thunderbolt 1.0.6 is measured.** AE2 Lightning Tech now pins Thunderbolt
  `2.0.0-beta.3`. The local `Thunderbolt-Core` checkout (2.0.0-beta.2, a different package) is
  covered only at claim level, by `plannerCapabilityMatrix`.
- **The generated graphs are synthetic**, and their second routes are deliberately cheaper in odd
  raws. "Who wins" on `dry` and `scarce_hub` depends on that choice; `ample` and `contended` do not.
- **Times are single-machine medians.** The ratios are stable across runs; the absolute
  milliseconds are not portable.
- **The balance check proves material sufficiency, not an execution order.** It cannot catch a
  cyclic plan that is balanced but needs an impossible bootstrap.
