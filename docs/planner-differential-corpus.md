# Differential capability corpus (R2.1)

This document describes the first usable slice of the R2.1 differential corpus: a neutral
specification of capability scenarios, a common replay oracle, a production-path runner and a
deterministic gate. It deliberately stops short of any performance or superiority claim.

## Run

```shell
./gradlew test                 # includes the corpus gate and the oracle/runner unit tests
./gradlew plannerDifferential  # prints the per-scenario report and exits non-zero on a gate failure
```

The Gradle task writes `build/reports/planner/differential.txt`, which is the artefact to attach to a
future comparison report.

## Files

All of it lives in `src/test/java/com/raishxn/ufocore/api/crafting/planner/differential`. Nothing
here is part of the runtime JAR and nothing here imports production classes from another planner.

| Type | Role |
| --- | --- |
| `CapabilityGraph`, `CapabilityPattern`, `CapabilityInput`, `CapabilityOutput` | Engine-neutral graph. Quantities are always `UfoAmount`, never narrowed to `long`. |
| `CapabilityInput.Kind` | `EXACT`, `REUSABLE`, `FINITE_USE`, `FUZZY`, `EMITTER` |
| `CapabilityOutput.Kind` | `PRIMARY`, `BYPRODUCT`, `PROBABILISTIC` |
| `CapabilityGraph.CapabilityStock.Kind` | `CONSUMABLE`, `REUSABLE`, `EMITTED` |
| `CapabilitySemantics` | Behaviour a scenario requires from an engine |
| `CapabilityScenario` | One case: graph, material mode, expectation, minimum witness, refill |
| `CapabilityCorpus` | The 17 groups and their three material modes |
| `CapabilityPlanReplay` | The common oracle |
| `RaishxCoreSemanticModel` | Which semantics the current model claims, and the lowering |
| `RaishxCoreCapabilityPlanner` | Runs the corpus through the production planner API |
| `CapabilityRunner` | Hard deadline, cooperative-timeout separation, classification |
| `DifferentialHarness` | Determinism verification, readable report, CI gate |

## Classification

The eight required classes are represented independently, so no failure can be silently folded into
another:

| Class | Meaning |
| --- | --- |
| `SUPPORTED` | Planning completed and the independently replayed plan is valid |
| `CHECK_REJECTED` | Admission refused a request the engine declares unsupported |
| `ATTEMPT_DECLINED` | Admission passed and planning actively declined |
| `FALSE_NEGATIVE` | A feasible scenario produced an unusable or incomplete result |
| `FALSE_POSITIVE` | A delivered plan fails replay |
| `ENGINE_ERROR` | Admission or planning threw |
| `ENGINE_TIMEOUT` | The engine stopped on its own deadline, or honoured a hard deadline |
| `NON_COOPERATIVE_TIMEOUT` | The engine ignored interruption past the grace period |

`FALSE_NEGATIVE` and `FALSE_POSITIVE` are never counted as support. A plan is never counted as valid
without replay.

## Replay oracle

`CapabilityPlanReplay` re-executes the reported schedule over the declared inventory and compares the
simulated residue with the declared one. It proves, per case:

- the schedule multiset equals the declared executions, every run is whole and positive, and every
  referenced pattern is declared;
- inputs are funded in the reported order, so no stock ever goes negative and no shortage is
  fabricated;
- the per-key balance `available = remaining + demand + request` holds for the declared numbers;
- the simulated residue equals the declared `remaining`, so declared accounting cannot drift from
  execution;
- the replay never withdraws more stock than the plan declares in `extractedFromInventory`, because
  that is what an AE2 commit would try to consume;
- the request is funded on its own (`complete`) or only after injecting exactly the reported
  shortage, which is what makes a shortage report usable;
- every reported shortage kind is actually consumed, and a kind that is never consumed is reported
  as a finding;
- overproduction, byproduct leftovers and their totals are recomputed independently.

## Corpus layout

27 groups, each in `MISSING`, `MINIMUM` and `UNBOUNDED`: 81 cases.

Representable today and therefore `REQUIRED`, 81 cases:

| Group | Cases |
| --- | --- |
| `single-dag/dispersed` | one-route DAG, batching, backtracking neighbour |
| `single-dag/fibonacci-depth32` | deep one-route Fibonacci with `BigInteger` demand |
| `multi-dag/sibling-routes` | two viable routes with a shared material and two minimum witnesses |
| `multi-dag/fibonacci-depth12` | two routes per level, the frontier is minimal |
| `multi-dag/greedy-trap` | route ordering trap at the reference scale of 32 conflicts |
| `multi-dag/weighted-shortage` | two routes identical in units, told apart only by weight |
| `multi-dag/weighted-leaf-cost` | routes differing in how much material they need, weighed |
| `multi-dag/shared-stock-conflict` | two demands sharing two scarce materials, no feasible mix |
| `batching/multi-output` | whole-batch rounding with a deterministic coproduct |
| `byproduct/shared-coproduct` | one coproduct produced by two routes |
| `byproduct/feeds-later-stage` | coproduct consumed by a later stage |
| `byproduct/surplus-secondary-demand` | more secondary wanted than the primary induces |
| `byproduct/secondary-outbids-declared` | a secondary route that is cheaper than the declared one |
| `deep-chain/linear-20000` | 20 000-deep chain, no stack growth |
| `cycle/conversion-ring` | a ring of conversions, priced by the cycle guard |
| `cycle/self-growth` | a step that feeds itself, funded one seed at a time |
| `catalyst/raw-feedback-loop` | a loop that returns its catalyst in full |
| `catalyst/lossy-feedback-loop` | a loop that loses a fixed amount per turn |
| `catalyst/returned-seed` | a declared catalyst, present once and handed back |
| `catalyst/secondary-through-catalyst` | a secondary chased through a recipe carrying a catalyst |
| `durability/finite-use-chain` | a carrier that survives a limited number of firings |
| `durability/reuse-across-expansions` | a carrier budget shared across two expansions |
| `durability/catalyst-and-carrier` | a catalyst and a carrier on one recipe, expanded twice |
| `fuzzy/variant-route` | a slot any of several variants may satisfy |
| `fuzzy/secondary-with-variant` | a fuzzy slot and a secondary output, expanded twice |
| `emitter/authorized-stream` | an input an authorized external source supplies |
| `probabilistic/chance-route` | a chance route that must not be promised to a request |

There are no declared limitations left. `probabilistic/chance-route` was the last one, and it was
worth declaring because the wrong answer is plausible rather than obviously broken: the target has a
deterministic route and a chance route, and only the chance route's input is in stock, so a planner
that treats a probabilistic output as an output answers the request from the wrong material and
reports it complete. The guaranteed answer is that every unit has to come from the deterministic
route, and that is now what the engine returns.

The reading is that a chance output is not production, not demand and not a route, so the guaranteed
problem is the one without it. The corpus builds the case so a planner that counted the roll would
answer from the wrong stock and be caught, and the oracle drops the roll the same way, so neither
side can pass by agreeing with the other about a promise that was never made.

The reference does not answer this family: its adapter declines the case rather than guessing, so on
this one the engine is ahead rather than behind.

A limitation is never counted as support, even when the diagnostic forced execution happens to
replay: it is listed separately in the report. With none left, that machinery is driven by a planner
stub that declines everything, so the refusal path is still exercised by a case rather than by
nothing.

## Documented deviations from the reference standard

These must never be presented as the reference suite's own results:

- the deep chain uses 20 000 instead of the reference depth for its own suite;
- one minimum witness is retained for the multi-route Fibonacci frontier instead of the exponential
  frontier, so its `missingOverhead` ratio is a lower bound and not a claim about the true frontier;
- the reference suite's `PARTIALLY_SUPPORTED` and `UNKNOWN` outcomes are represented as
  `SUPPORTED` with a recorded `missingOverhead` greater than one, because the required taxonomy has
  exactly eight classes.

## Weighted shortages

Weights are how the corpus says that one missing unit is worse than another, and `missingOverhead`
has always been weighted — the witness is the cheapest weighted shortage and the ratio is against
that. Until now no case declared any, so every weight was one and the machinery was never exercised.

`multi-dag/weighted-shortage` exercises it. Two routes reach the target, each leaving exactly one
unit missing, so the unit count cannot tell them apart, and the valuable route is named so that it
sorts first. The report used to name the material worth a hundred times more, at an overhead of
`100.0` against a minimum of one; it now names the cheap one, at `1.000`.

`multi-dag/weighted-leaf-cost` is the sharper of the two. The routes differ in how much material
they need as well as in what it is worth, and the comparison that runs *before* the shortage
comparison — the bottom-up leaf demand — was still counted in bare units. Ten cheap units therefore
looked worse than one valuable unit, the route comparison never handed the decision to the weighted
one, and the report named the valuable material at an overhead of `10.0`. A leaf now costs what the
request says a unit of it is worth, so the whole pass is made in the same currency as the shortage it
is trying to avoid.

The engine takes whole-number weights rather than fractions, so the comparison stays exact and the
plan stays reproducible, and a weight of one is not stored at all. That last part is what keeps the
promise that a request declaring no weights behaves exactly as it did before weights existed: on the
benchmark's own conflict case the allocation is byte-identical either way, `20248` before and after.

Weights are not yet supplied by the bridge. Nothing in AE2 says what a material is worth, so wiring
them to provider priorities is still open; the API and the proof are what landed.

## Carriers are per pattern

A durable carrier is drawn per pattern and per key, not per firing and not across patterns. Two
recipes consuming the same tool each keep their own budget, which over-reports rather than
under-reports and is the safe direction for a shortage. The alternative — a single pool shared by
every recipe that consumes the key — is a modelling question this corpus does not answer either way,
and the planner and the oracle agree on the reading they do share.

## What the corpus is for

It is a falsification instrument before it is a scoreboard. The ordering the planner uses consults
priority, deficit, weighted leaf demand, reachability, weighted shortage, input cost, executions,
yield and identifier, and the case for replacing it with an integer solver rests on that ordering
losing somewhere. Six attempts to build such a case have failed, in different rounds and from
different directions, and the shapes that came closest are in the corpus as regression guards —
`multi-dag/shared-stock-conflict` among them. The roadmap's phase 4 records the conclusion: the
solver stays unbuilt until a case makes it necessary, and the corpus is what would say so.

## Scale

The greedy trap runs at the reference suite's own scale, 32 independent conflicts, and resolves in
about 12 ms on the hardest material mode with the frontier exactly the 32 witnesses. The deviation
that held it at eight is withdrawn: it rested on the assumption that the full-scale case was an
operation-budget question, and it is not one. Doubling the scale to 64 also resolves, at 28 ms and 129
executions, so the reference scale sits well inside the budget rather than at its edge.

That matters for a reason beyond the number: a case held at a fraction of the reference scale cannot
be presented as parity with it, and the caveat was doing the work of a measurement.

## Gate

`DifferentialHarness.gate()` fails when:

- a required capability is neither `SUPPORTED` nor an explicitly listed confirmed defect;
- any case is a defect that is not listed in `CONFIRMED_DEFECTS`;
- a listed confirmed defect does not reproduce, so the list can never go stale;
- any case is not deterministic across a reversed pattern declaration order.

Advisory findings do not fail the gate: a limitation that replayed successfully, and a shortage
overhead above one on a required case. They are printed so they cannot be overlooked.

## Defect found and fixed by this corpus

| Cases | Was | Cause and fix |
| --- | --- | --- |
| a decaying catalyst with no stock at all | shortage over-reported by the whole decay | The catalyst presence check demanded the working stock plus the decay, and the decay is also drawn by its own consumed entry, so an absent catalyst was charged twice. Nothing in the corpus had a decaying catalyst at zero stock, and the unit tests that do exist all leave it partly covered. The check now charges only the part the normal draw cannot explain, which is the shortfall of the total beyond the shortfall of the decay. Found by writing the shortage split, which made the double charge visible as two categories for the same unit. |
| `byproduct/secondary-outbids-declared/{minimum,unbounded}` | `FALSE_NEGATIVE` | A secondary output was consulted only when nothing declared the key, so a recipe making nine of it from one scrap lost to a recipe naming it from five ore, and the report asked for the ore. Secondary routes now compete with declared ones and the comparison prices the extra firings and the primary that comes along. Restoring the old behaviour fails this case at an overhead of `5.0`. |
| `durability/reuse-across-expansions/{minimum,missing,unbounded}` | `FALSE_NEGATIVE` and `FALSE_POSITIVE` | A durable carrier budget was drawn at each expansion of a recipe rather than across the plan. Chasing a secondary output expands the same recipe twice, so the two ceilings of one and three firings charged three carriers where a carrier lasting two firings covers all four. The planner now draws the budget per pattern and the oracle does the same; the oracle had been internally inconsistent, using the total in its balance pass and per step in its ordered replay. |
| `multi-dag/weighted-leaf-cost/missing` | `missingOverhead` 10.0 | The route comparison runs before the shortage comparison, and it counted leaf demand in bare units, so ten cheap units lost to one valuable unit and the declared weight was never consulted. Leaf keys are now priced at their declared weight, which puts the whole pass in the same currency as the shortage. |
| `catalyst/secondary-through-catalyst/missing` | `missingOverhead` 1.200 | A catalyst was charged once per *expansion* of its recipe rather than once per plan. Chasing a secondary output separately from the primary expands the same recipe twice, so the presence check ran twice and demanded two catalysts when one is handed back and covers both. `State` now records the working stock already demanded and charges only the decay again. |
| `byproduct/shared-coproduct/{minimum,unbounded}`, `byproduct/feeds-later-stage/{minimum,unbounded}` | `FALSE_NEGATIVE` | A demanded coproduct was resolved before the sibling route that produces it, so a composite whose coproduct key sorts before its routable keys reported an impossible shortage. `ImmutableCraftingGraph` now orders each pattern's inputs so that an input with a selectable route is resolved before an input that can only be collected as a deterministic coproduct. |

The same cause also inflated the `MISSING` modes of those two groups to `missingOverhead` 2.0 and
3.0. All six cases now classify as `SUPPORTED`, both `MISSING` modes report exactly one known
minimum at `missingOverhead = 1.000`, and `DifferentialHarness.CONFIRMED_DEFECTS` is empty again.
The registry keeps working the same way: an unlisted defect fails the gate, and an entry that stops
reproducing fails it too. `DifferentialHarnessGateTest` covers every branch of that logic.
`PlannerConservationTest.collectsACoproductFromItsSiblingBranchBeforeDemandingIt` pins the fix.

## The byproduct boundary

A secondary output is still not a selectable route: `Ae2PlanningSnapshot` declares
`craftableOutputs = Set.of(primary)`, so nothing that asks what a recipe makes starts seeing a
secondary as one, and `ImmutableCraftingGraph.patternsFor` keeps returning nothing for it.

It is a route for planning, though, and it competes rather than waiting for nothing else to declare
the key. `byproduct/secondary-outbids-declared` is the case that says so: a recipe declaring the key
from five ore, and a recipe making nine of it as a secondary from one scrap. Consulted only when
nothing declared the key, the expensive route was taken and the request reported five ore short while
a single scrap would have covered it. Restoring that behaviour makes the case fail at an overhead of
`5.0` and two false negatives, which is what makes it worth keeping.

It is nevertheless obtainable, because firing the recipe for its primary yields it, and when the
primary is not wanted for its own sake that is the only way. `byproduct/surplus-secondary-demand`
pins the difference: the target needs four of a secondary and one of the primary, so the producing
pattern has to be fired three more times for the secondary alone. Before, the engine reported the
other three as missing secondary, which named a key nobody can supply; it now reports the primary's
input, which is what actually has to be bought.

`PlannerConservationTest.byproductsStayAvailableAndCannotBeSelectedAsAe2PrimaryOutputs` asserts both
halves: the selectable view is unchanged, and the request is planned anyway. The surplus produced
while chasing a secondary is inherent to the recipe and is reported as overproduction rather than
hidden.

## Reproducibility

The rendered report used to depend on the JVM: stock maps were built from `Map.of`, whose iteration
order is randomised per run, and the replay's residue was assembled in the order keys happened to be
seen. Two runs of the same commit could therefore print the same residue with its keys in a different
order, which is exactly what a report meant to be frozen cannot do. Both are sorted now, and two runs
are identical once timings are normalised. Timings are the one thing that cannot be pinned, which is
why the assertions are on deterministic invariants and the benchmark gates the numbers separately.

## Shortage quality

Every missing-mode case now reports exactly the known minimum (`missingOverhead = 1.000`), all 27 of
them, so the frontier is asserted rather than merely printed. `multi-dag/fibonacci-depth12/missing`
used to be the exception at 6.857, matching what the reference standard documents for its own
multi-route Fibonacci case; the bottom-up leaf-demand pass closed it, and the case is asserted now.
An overhead above one on a required case stays advisory rather than fatal, so a regression here is
reported loudly without pretending the plan is invalid.

## Next steps

1. Extend the corpus with the remaining reference-scale cases and the Raishx additional corpus
   (`BigInteger` extremes, wide graphs, item plus fluid, lifecycle cancellation, concurrent grids).
   These are cases inside families already covered, so their value is finding defects rather than
   covering a semantics that is missing.
2. Freeze the environment for a differential report: the report is reproducible now, but the
   reference checkout is not pinned in the artefact and the AE2-VM leg still needs a credential this
   checkout does not have, so only Thunderbolt V2 is measured.
3. Decide the global-solver question of roadmap phase R2.4, which is the only remaining way to claim
   optimality rather than minimality-on-the-cases-that-were-tried.
