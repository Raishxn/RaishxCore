# Cooperative planner capture

State on 2026-09-17. This document describes R2.2 up to its second slice. The first made
graph capture cooperative: it no longer runs to completion in one server-thread call. The
second made the slice fine enough to interrupt inside a key, and made the resulting
durations measurable. It records what is implemented, what is measured, and what is still
missing. Nothing here claims superiority over another planner; no differential run has
been executed.

## Problem

Before the first slice of R2.2 one request captured its whole reachable graph inside a
single call that had only a wall-clock ceiling (`planner.snapshot.timeoutMillis`, 50 ms).
A large or cold grid could consume that budget in one tick, several grids could each do
the same in the same tick, and the only escape was to decline to AE2.

The first slice introduced a resumable traversal, but it could only stop between two keys.
On a grid where one item is produced by many patterns, all of them had to be validated in a
single uninterruptible call, so the slice still grew with the size of the fattest key and
the 2 ms target of Gate O was neither met by construction nor measured.

## Shape of the implementation

| Component | Responsibility |
| --- | --- |
| `CooperativeGraphCapture` | Resumable, engine-independent traversal. Works exclusively on canonical key ids, never on AE2 handles, walks one pattern at a time and publishes only a complete graph. |
| `Ae2CaptureSource` | The only class that touches AE2/Minecraft. Answers `describe(id)`, `patternCount(id)` and `patternAt(id, index)`, caches each answer per id, and raises `Declined` for every semantic it cannot represent. |
| `CaptureSliceMetrics` | Bounded slice and tick histograms with p50/p95/p99, plus per-phase accumulators. Fixed buckets, no per-sample allocation. |
| `Ae2PlanningSnapshot` | Snapshot boundary plus the total limits and the byte estimate of one capture. `capture(...)` remains as a blocking one-shot entry point. |
| `CaptureBudgetPool` | One shared allowance per server tick for every grid, granted per slice and settled with the time the slice really spent. |
| `CaptureSnapshotCache` | LRU snapshot cache bounded by entry count **and** estimated bytes. |
| `Ae2PlannerBridge` | Per-grid pending captures, rotation between them, waiters, refusal/fallback policy and diagnostics. |
| `DeferredPlanFuture` | The future handed to AE2 while a capture is still running. Delegates `isDone()`/`get()` to the real planning future once it exists. |
| `CoreCraftingPlannerServiceMixin` | Routes requests into the bridge and exposes AE2's own planner as the fallback for a discarded attempt. |

The traversal keeps its original semantics: breadth-first over ids, one key per visit,
patterns deduplicated by canonical id, inputs enqueued, a key described once. What changed
is the interruption point: a slice performs one unit of work at a time - the lookup of one
key, or one pattern of the key being walked - and may end between any two of them. The graph
is still built only at the end.

## Budgets

Three limits apply at the same time:

1. **Total capture limits** (`snapshot.timeoutMillis`, `maxEdges`, `maxKeys`,
   `maxEstimatedBytes`) bound the whole capture. The timeout accumulates only main-thread time
   spent inside its slices; idle time between server ticks is excluded. Exceeding one declines
   the capture.
2. **Slice limits per grid** (`snapshot.sliceMillis`, `snapshot.sliceEdges`) bound one
   slice. The edge allowance makes a slice reproducible in tests; the time allowance is a
   safety net.
3. **Shared tick budget** (`snapshot.tickBudgetMillis`) bounds what all grids together may
   spend on capture in one tick. Rotation is applied inside each grid's pending captures and
   across the active grids. `Ae2PlannerBridge.tick()` advances the first grid each tick, so even
   when one slice consumes the whole shared budget, every active grid becomes first within one
   complete rotation.

Guarantees:

- A slice always performs **at least one unit** of work - one key lookup or one pattern. A
  slice allowance smaller than the clock resolution therefore cannot yield forever without
  progress.
- A slice stops only **between units**, so progress never depends on wall-clock jitter.
- `settle` returns unused reservation time to the tick and charges an **overrun in full**:
  one unusually heavy key can exceed its own slice, and the next grid must not then spend a
  budget that is already gone. The allowance may go negative, which only means every later
  reservation waits for the next tick.
- Declining is never caused by the tick budget. Running out of tick budget defers the
  capture to the next tick.

## Cancellation matrix

| Transition | Behaviour |
| --- | --- |
| Grid revision changed before a request | Cached snapshots cleared, in-flight calculations cancelled, pending captures cancelled. |
| Grid revision changed while a capture is open, noticed by the tick pump | Capture discarded, caller served by AE2's planner, status `ae2 fallback: grid revision changed during capture`. |
| Grid node removed / grid closed | Pending captures cancelled, deferred futures completed as cancelled. |
| Player logout | That player's waiters dropped from their captures; a capture without waiters is cancelled with its cancellation flag set. |
| Server stopping | Every grid invalidated, all pending captures cancelled. |
| Planner disabled by config | No capture starts; a later request cancels pending captures through the same invalidation path. |
| Caller cancels its deferred future | Waiter is marked abandoned; the pump stops feeding it on the next tick and the capture is dropped when its last waiter goes. |
| Capture declined by a total limit or by unrepresentable semantics | Capture discarded whole; waiters that already hold a deferred future are served by AE2, the current request answers `null` and AE2 plans it in the same tick. |
| Any cancellation point inside a slice | Checked before the first key, before every key and before publishing. A discarded capture keeps no keys, patterns or graph. |

Nothing partial is ever published: `CaptureSnapshotCache` only ever receives a snapshot
built from a completed capture, and `result()` refuses to answer before completion.

## Slice granularity and its measured cost

A unit is one key lookup or one pattern. The only work a slice cannot interrupt - its
**atomic tail** - is therefore one key lookup plus the widest single pattern of that key.
Every call to `advance` reports how many edges it really consumed, so the property is
checked deterministically instead of being argued from timing:

- bounded unit: `lastSliceEdges() <= sliceEdges + tailEdges`;
- the tail is the widest pattern of the grid plus the edge its acceptance costs (3 edges
  for a one-input, one-output pattern, 4 for a two-input one in the corpus);
- slicing never changes the result: a bounded capture produces exactly the same keys,
  pattern ids, `multiplePaths` flag and byte estimate as one unbounded capture.

`plannerCaptureSlices` measures the same machine on synthetic grids. This is the run of
2026-09-17 on the development machine, with `sliceEdges = 64`, a 2 ms slice allowance and a
4 ms shared tick budget, and a simulated cost of 20 µs for every grid call:

| case | keys | patterns | slices | max edges | p50 | p95 | p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `CHAIN` 64 keys | 64 | 63 | 3 | 66 | 0.2 ms | 4.0 ms* | 4.0 ms* |
| `FIBONACCI` depth 16, no cost | 17 | 31 | 2 | 66 | 0.2 ms | 0.4 ms* | 0.4 ms* |
| `FAT_KEY` 10 000 patterns, no cost | 2 | 10 000 | 455 | 66 | 0.1 ms | 0.2 ms | 0.4 ms |
| `FAT_KEY` 1 pattern, costed | 2 | 1 | 1 | 3 | 0.8 ms | 0.8 ms* | 0.8 ms* |
| `FAT_KEY` 100 patterns, costed | 2 | 100 | 5 | 66 | 0.8 ms | 0.8 ms* | 0.8 ms* |
| `FAT_KEY` 1 000 patterns, costed | 2 | 1 000 | 46 | 66 | 0.8 ms | 0.8 ms | 1.0 ms |
| `FAT_KEY` 10 000 patterns, costed | 2 | 10 000 | 455 | 66 | 0.8 ms | 0.8 ms | 1.0 ms |
| `FIBONACCI` depth 40, costed | 41 | 79 | 5 | 67 | 0.8 ms | 0.8 ms* | 0.8 ms* |

Multi-grid fairness on the same run: four concurrent captures, one of them a `FAT_KEY` of
2 000 patterns, all four completed in 91 ticks and 97 slices, with a longest wait of 0 ticks
and a tick p95 of 0.8 ms.

How to read this table:

- The percentiles are **bucket upper edges**, never below the truth, so 0.8 ms means "at most
  0.8 ms in the 800 µs bucket". Rows marked `*` produced fewer than 20 slices; there the
  number is dominated by the worst slice and is not a percentile.
- The costed rows are the claim that matters: with one pattern costing 20 µs, the p95 stays
  at 0.8 ms while one key grows from 1 to 10 000 patterns. Before the cursor, the 10 000
  pattern key would have been a single uninterruptible call of roughly 200 ms.
- The uncosted rows measure the machinery alone: about 5 µs per pattern, plus the graph
  build. That is the floor the real adapter adds to.
- Slice counts move by a few percent between runs, because the 2 ms time allowance can also
  end a slice; the edge bound and the percentiles do not depend on that.
- The simulated cost stands in for the AE2 calls `Ae2CaptureSource` makes. The harness does
  **not** measure a live AE2 grid, and the 2 ms target in game is therefore still unverified
  - what changed is that it is now observable, through the same histograms the harness reads.

### In game

`PlannerCaptureSoakGameTests` measures the same histograms against real AE2 grids. It captures two
targets on one grid and logs each reading separately, because the first capture in a process pays
class loading and JIT for the whole adapter path:

| reading | slices | slice p50 (cumulative) | key phase | pattern phase | publish phase |
| --- | ---: | ---: | ---: | ---: | ---: |
| after the first capture (cold) | 2 | 4.0 ms | 0.53 ms | 2.66 ms | 2.16 ms |
| after a second target (warm) | +2 | 0.8 ms | +0.06 ms | +0.40 ms | +0.08 ms |

The cold reading is what one 2-key grid costs while the code is still being compiled; the warm
second capture settles at a few hundred microseconds per slice, and the publish step at tens of
microseconds. Two captures are not a percentile, and a two-key grid says nothing about a large one:
**the 2 ms target needs a session on a real server with a large grid**, and the per-phase
accumulators are the instrument for it. What the test does establish is that the metric is live in
game, that a cold first capture must not be mistaken for a regression, and that nothing pathological
happens at game scale.

### Multi-grid soak with a slow neighbour

The same file runs three real grids competing for one shared tick budget, with the slow grid
registered first so that a pump which stopped at the first exhausted budget would starve the other
two. The slow provider burns 4 ms - more than the whole tick budget - inside one `getPatternPriority`
call, which is a slice's atomic tail. Observed: 15 slices over 8 ticks for the three captures, five
slow calls, every request planned exactly by the Core, no capture left pending, no backpressure
rejection and no circuit rejection on any grid. The overrun is charged to the shared budget in full
and the other grids simply wait for the next tick. What carries the test is that each capture is
finite, the budget resets every tick and the deployed bridge rotates which grid is served first.
This gives bounded scheduling wait even when the slow grid consumes a whole tick's allowance.

## Fallback policy

Falling back is never counted as a RaishxCore success, and it never mixes two engines in
one attempt:

- If the capture finishes inside the request's own slice, behaviour is exactly the previous
  same-tick path: the plan future is returned directly.
- If the slice ends with work left, AE2 receives a `DeferredPlanFuture` and the capture
  continues on later ticks. Diagnostics report `capture deferred`.
- If a deferred attempt is later discarded, the whole attempt is dropped and AE2's planner
  plans the request from the current grid. The status records `ae2 fallback: <reason>`.
- If there is no fallback available (caller cancelled, or no fallback was supplied), the
  deferred future completes as cancelled instead of returning a partial result.

## Inventory semantics

The inventory a request saw is copied at request time and used when the plan is finally
submitted, including for captures that span ticks. AE2's own planner also simulates from a
snapshot taken when the calculation starts, so a capture that deferred does not drift to a
later inventory.

## Configuration added in this slice

| Key | Default | Purpose |
| --- | ---: | --- |
| `planner.snapshot.cacheBytes` | `134217728` | Byte ceiling for the whole per-grid snapshot cache. |
| `planner.snapshot.sliceMillis` | `2` | Main-thread time one grid may spend on one slice. |
| `planner.snapshot.sliceEdges` | `512` | Edges one slice may consume; makes a slice deterministic. |
| `planner.snapshot.tickBudgetMillis` | `4` | Shared capture budget for all grids in one tick. |
| `planner.snapshot.maxPendingCaptures` | `8` | Captures one grid may keep open across ticks. |

## Tests

Unit tests (`src/test/java/com/raishxn/ufocore/neoforge/crafting/`):

- `CooperativeGraphCaptureTest` — one unbounded slice and many bounded slices capture the
  same graph; a one-edge and a one-nanosecond slice still finish; cancellation between
  slices discards everything; a declined key, an emitter, a feedback pattern and a total
  edge limit all discard partial state; registration order does not change the result;
  duplicate pattern ids never reach the graph; byte weight and slice time are reported.
- `CaptureBudgetPoolTest` — one tick allowance shared by several grids, settle refunds,
  overrun charged in full, tick reset, exhaustion accounting.
- `CaptureSnapshotCacheTest` — LRU by entries, eviction by bytes, replacement accounting,
  policy shrink, byte release, invalid input.
- `DeferredPlanFutureTest` — done only after the plan future is done, timeout instead of a
  hang, caller cancellation before and after the plan exists, invalidation abandonment and
  first-completion-wins.
- `Ae2PlanningSnapshotBudgetTest` — total limits, slice allowance, byte estimate.

Additional unit tests for the second slice: `CooperativeGraphCaptureTest` proves that a
200-pattern key becomes dozens of slices, that no slice grew past its allowance plus one
atomic tail with 500 patterns, and that an adapter reporting an impossible or negative
pattern count is discarded whole; `CaptureSliceMetricsTest` covers the histograms,
percentiles, overflow, phase accounting and invalid input; `CaptureBudgetPoolTest` covers
the per-tick spend; `CaptureSliceHarnessTest` proves the harness gate rejects a slice that
grew past its allowance, a sliced capture that changed the graph, a capture larger than one
allowance that was never sliced, a p95 above the target, an incomplete capture, and a
starved multi-grid run.

`plannerCaptureSlices` (`CaptureSliceHarness`) drives the production capture machine through a
harness-owned pump with the same scheduling rule as `Ae2PlannerBridge.tick()`: rotating grid order
and one shared budget per simulated tick. It reports the table above, gates on the deterministic
invariants plus a p95 ceiling for the costed cases, and compares every sliced capture with an
unbounded capture of the same grid.

GameTests (`src/gameTest/java/com/raishxn/ufocore/gametest/PlannerCooperativeCaptureGameTests.java`,
one batch per scenario so grid and config state cannot race):

- `aCaptureThatSpansTicksStillProducesTheExactPlan` — with `sliceEdges = 1` a request is
  deferred, then planned exactly by the Core planner with status `COMPLETE`.
- `aGridMutationDuringCaptureHandsTheRequestToAe2` — a provider change while the capture is
  open cancels the attempt, records `ae2 fallback`, caches no snapshot, and AE2 plans the
  current grid.
- `aSecondTargetIsServedByAe2WhenTheCaptureCapIsReached` — at the per-grid cap the second
  target is planned by AE2 (counted as backpressure) while the first capture still finishes
  on a later tick.
- `aKeyWithSeveralRoutesIsCapturedAcrossSeveralSlices` — three routes for one product with
  `sliceEdges = 1`: the capture needs at least four slices rather than the two a key-granular
  slice would use, all three patterns arrive in the graph, and the plan is still exact.
- `threeGridsWithOneSlowProviderAllFinish` — the multi-grid soak described above.
- `aRealGridReportsItsSlicePercentiles` — the cold/warm in-game readings above.

## Limits of this slice (not implemented, not measured)

- **The live 2 ms target is still unverified.** The harness measures the machine with a
  simulated per-pattern cost; only a session on a real grid can confirm what one AE2 pattern
  validation really costs and whether the in-game p95 meets the target. The histograms that
  answer it are already in `Diagnostics`.
- **The atomic tail is one key lookup plus one pattern.** A single pattern with an unusually
  large input/output list is still uninterruptible, and the overrun it causes is charged to
  the shared budget in full rather than hidden.
- **The slice percentile is server-wide.** `Diagnostics.captureMetrics()` reports the shared
  histograms, because the capture budget they describe is server-wide; the per-grid counters
  (slices, cancellations, pending captures) stay per grid.
- **No metrics export.** Percentiles, phases, queue, cache and memory are readable from
  `Diagnostics`, but nothing exports them to a monitoring system.
- **The tick budget and the worker pool are fixed at first use.** Changing
  `snapshot.tickBudgetMillis` or worker settings requires a restart, as with the existing
  pool settings.
- **No long soak with a real save.** The multi-grid soak above lives in a GameTest with three
  grids and a few ticks; a long run against a real world, with many players and grids coming and
  going, belongs to Gate R.
- **A blocking adapter call is contained, not preempted.** A provider that blocks inside a slice
  holds the server thread for that call; the capture charges the overrun to the shared budget,
  but it cannot shorten the call itself.
- **Cross-grid rotation bounds scheduling wait, not provider runtime.** Every active grid becomes
  first within one rotation, but an adapter call already in progress is still cooperative rather
  than preemptive and may overrun its slice before the next grid can run.
- **Slice percentiles are server-wide, not per grid.** `CaptureMetrics` is one static instance, so
  the slice histogram aggregates every grid. Per-grid percentiles would need a meter per bridge.
- **The corpus does not cover capture behaviour.** `plannerDifferential` still measures the
  planner, not the capture; the capture guarantees live in unit tests and GameTests.
- **The deferred path is not benchmarked.** `plannerBenchmark` exercises the planner API
  directly and does not go through the bridge.
- **`snapshot.timeoutMillis` accumulates active capture work across ticks.** A capture that uses
  more active main-thread time than the deadline is declined and handed to AE2; waiting between
  ticks does not consume that allowance.

## Next steps

1. Confirm the slice p95 in game through the diagnostics histograms, on a grid with many
   patterns for one item, and record the session in the roadmap.
2. Reload the shared tick budget without a restart, which is the last configuration item R2.2
   leaves open.
3. Continue R2.1 with the Thunderbolt V2 and AE2-VM adapters and the frozen-environment
   report, so the capture work can be measured in the differential corpus too.
