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

17 groups, each in `MISSING`, `MINIMUM` and `UNBOUNDED`: 51 cases.

Representable today and therefore `REQUIRED`:

| Group | Cases |
| --- | --- |
| `single-dag/dispersed` | one-route DAG, batching, backtracking neighbour |
| `single-dag/fibonacci-depth32` | deep one-route Fibonacci with `BigInteger` demand |
| `multi-dag/sibling-routes` | two viable routes with a shared material and two minimum witnesses |
| `multi-dag/fibonacci-depth12` | two routes per level, `SUPPORTED` with a non-minimal shortage |
| `multi-dag/greedy-trap` | route ordering trap, 8 independent conflicts |
| `batching/multi-output` | whole-batch rounding with a deterministic coproduct |
| `byproduct/shared-coproduct` | one coproduct produced by two routes |
| `byproduct/feeds-later-stage` | coproduct consumed by a later stage |
| `deep-chain/linear-20000` | 20 000-deep chain, no stack growth |

Declared limitations, refused before planning because the model cannot represent them yet:

| Group | Required semantics |
| --- | --- |
| `cycle/conversion-ring` | `CONVERSION_CYCLE` |
| `cycle/self-growth` | `POSITIVE_FEEDBACK` |
| `catalyst/raw-feedback-loop` | `CONSERVATIVE_FEEDBACK` |
| `catalyst/lossy-feedback-loop` | `LOSSY_FEEDBACK` |
| `catalyst/returned-seed` | `REUSABLE_INPUT` |
| `durability/finite-use-chain` | `FINITE_DURABILITY` |
| `fuzzy/variant-route` | `FUZZY_ALTERNATIVES` |
| `emitter/authorized-stream` | `EMITTER` |

A limitation is never counted as support, even when the diagnostic forced execution happens to
replay: it is listed separately in the report.

## Documented deviations from the reference standard

These must never be presented as the reference suite's own results:

- the greedy trap uses 8 independent conflicts instead of the reference scale of 32, because at the
  current scale the case is an operation-budget question rather than a semantic one;
- the deep chain uses 20 000 instead of the reference depth for its own suite;
- one minimum witness is retained for the multi-route Fibonacci frontier instead of the exponential
  frontier, so its `missingOverhead` ratio is a lower bound and not a claim about the true frontier;
- the reference suite's `PARTIALLY_SUPPORTED` and `UNKNOWN` outcomes are represented as
  `SUPPORTED` with a recorded `missingOverhead` greater than one, because the required taxonomy has
  exactly eight classes.

## Gate

`DifferentialHarness.gate()` fails when:

- a required capability is neither `SUPPORTED` nor an explicitly listed confirmed defect;
- any case is a defect that is not listed in `CONFIRMED_DEFECTS`;
- a listed confirmed defect does not reproduce, so the list can never go stale;
- any case is not deterministic across a reversed pattern declaration order.

Advisory findings do not fail the gate: a limitation that replayed successfully, and a shortage
overhead above one on a required case. They are printed so they cannot be overlooked.

## Open defects found by this corpus

`DifferentialHarness.CONFIRMED_DEFECTS` records them; the gate keeps them visible.

| Cases | Class | Cause |
| --- | --- | --- |
| `byproduct/shared-coproduct/minimum`, `/unbounded`, `byproduct/feeds-later-stage/minimum`, `/unbounded` | `FALSE_NEGATIVE` | A demanded coproduct is resolved before the sibling route that produces it. A composite whose coproduct key sorts before its primary keys therefore reports an impossible shortage instead of running the producing route first. |

The same cause makes the `MISSING` modes of those two groups report the coproduct as missing and
inflate `missingOverhead` to 2.0 and 3.0 instead of 1.0.

## Non-minimal shortage, recorded not asserted

`multi-dag/fibonacci-depth12/missing` returns a valid but non-minimal report
(`missingOverhead = 6.857`). That matches the behaviour the reference standard documents for its own
multi-route Fibonacci case. It is printed as a finding, and the case is excluded from the
`canonicalShortagesAreMinimal` assertion because only one minimum witness is retained.

## Next steps

1. Fix the coproduct ordering defect and delete its `CONFIRMED_DEFECTS` entries.
2. Extend the corpus with the remaining reference-scale cases and the Raishx additional corpus
   (`BigInteger` extremes, wide graphs, probabilistic outputs, item plus fluid, lifecycle
   cancellation, concurrent grids).
3. Add the Thunderbolt V2 and AE2 adapters behind the same `CapabilityPlanner` contract and freeze
   the environment for the first real differential report.
