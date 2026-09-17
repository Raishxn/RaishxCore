# Cooperative planner capture

State on 2026-09-17. This document describes the first slice of R2.2: graph capture no
longer runs to completion in one server-thread call. It records what is implemented,
what is measured, and what is still missing. Nothing here claims superiority over
another planner; no differential run has been executed.

## Problem

Before this change one request captured its whole reachable graph inside a single call
that had only a wall-clock ceiling (`planner.snapshot.timeoutMillis`, 50 ms). A large or
cold grid could consume that budget in one tick, several grids could each do the same in
the same tick, and the only escape was to decline to AE2.

## Shape of the implementation

| Component | Responsibility |
| --- | --- |
| `CooperativeGraphCapture` | Resumable, engine-independent traversal. Works exclusively on canonical key ids, never on AE2 handles, and publishes only a complete graph. |
| `Ae2CaptureSource` | The only class that touches AE2/Minecraft. Answers `describe(id)` and `patternsFor(id)`, caches each answer per id, and raises `Declined` for every semantic it cannot represent. |
| `Ae2PlanningSnapshot` | Snapshot boundary plus the total limits and the byte estimate of one capture. `capture(...)` remains as a blocking one-shot entry point. |
| `CaptureBudgetPool` | One shared allowance per server tick for every grid, granted per slice and settled with the time the slice really spent. |
| `CaptureSnapshotCache` | LRU snapshot cache bounded by entry count **and** estimated bytes. |
| `Ae2PlannerBridge` | Per-grid pending captures, rotation between them, waiters, refusal/fallback policy and diagnostics. |
| `DeferredPlanFuture` | The future handed to AE2 while a capture is still running. Delegates `isDone()`/`get()` to the real planning future once it exists. |
| `CoreCraftingPlannerServiceMixin` | Routes requests into the bridge and exposes AE2's own planner as the fallback for a discarded attempt. |

The traversal keeps its original semantics: breadth-first over ids, one key per visit,
patterns deduplicated by canonical id, inputs enqueued, a key described once. What changed
is that a slice may end between two keys, and that the graph is built only at the end.

## Budgets

Three limits apply at the same time:

1. **Total capture limits** (`snapshot.timeoutMillis`, `maxEdges`, `maxKeys`,
   `maxEstimatedBytes`) bound the whole capture, across ticks. Exceeding one declines the
   capture.
2. **Slice limits per grid** (`snapshot.sliceMillis`, `snapshot.sliceEdges`) bound one
   slice. The edge allowance makes a slice reproducible in tests; the time allowance is a
   safety net.
3. **Shared tick budget** (`snapshot.tickBudgetMillis`) bounds what all grids together may
   spend on capture in one tick. Captures are visited in rotating order, so one grid cannot
   starve another.

Guarantees:

- A slice always performs **at least one key** of work. A slice allowance smaller than the
  clock resolution therefore cannot yield forever without progress.
- A slice stops only **between keys**, so progress never depends on wall-clock jitter.
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

## Limits of this slice (not implemented, not measured)

- **Slice granularity is one key.** A key with an unusual number of patterns can exceed its
  own slice; the overrun is charged honestly to the shared budget, but the per-slice p95 of
  2 ms is therefore **not yet measured and not yet met by construction**. In-key slicing is
  the next refinement.
- **No metrics export.** Diagnostics expose counters (pending captures, deferred requests,
  slices, cancellations, captured patterns, cache bytes, cache evictions, remaining tick
  budget) but there are no p50/p95/p99 histograms or per-phase timings yet. Gate O remains
  unchecked for that reason.
- **The tick budget and the worker pool are fixed at first use.** Changing
  `snapshot.tickBudgetMillis` or worker settings requires a restart, as with the existing
  pool settings.
- **No multi-grid soak or hostile-engine test.** Round-robin fairness is implemented and
  unit tested only through the pool arithmetic.
- **The corpus does not cover capture behaviour.** `plannerDifferential` still measures the
  planner, not the capture; the capture guarantees live in unit tests and GameTests.
- **The deferred path is not benchmarked.** `plannerBenchmark` exercises the planner API
  directly and does not go through the bridge.
- **`snapshot.timeoutMillis` still applies across ticks.** A capture that keeps yielding for
  longer than that deadline is declined and handed to AE2.

## Next steps

1. Split a slice inside a key (per-pattern resumable cursor) and measure the main-thread
   slice p95 per grid and per tick.
2. Export per-phase timings and bounded histograms for Gate O, and add a soak GameTest with
   several grids and a deliberately slow grid.
3. Continue R2.1 with the Thunderbolt V2 and AE2-VM adapters and the frozen-environment
   report, so the capture work can be measured in the differential corpus too.
