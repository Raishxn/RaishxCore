# Changelog

## Unreleased

## 0.1.0-beta.2 - 2026-09-21

### Added

- AE2's crafting-confirmation screen now appends `RaishxPlanner` only when that
  exact request was calculated by RaishxCore. The source is carried with the
  request future and synchronized with the menu, so concurrent requests and a
  deferred fallback to AE2 cannot display a stale or false badge.

## 0.1.0-beta.1

### Fixed

- Cooperative snapshot timeout now counts only cumulative main-thread work inside capture slices;
  idle time between server ticks no longer forces healthy captures to fall back to AE2.
- `DeferredPlanFuture` now remains interruptible while waiting for its delegate and applies one
  timeout budget to both the delegate handoff and the plan calculation.
- The production capture pump now rotates which active grid is served first each tick, preventing a
  continuously busy grid from starving later grids when it consumes the shared budget.

## 0.1.0-alpha.4

### Added

- Missing-weight policy: a consumer declares exact integer weights per serialized
  key through `MissingWeights`, the operator scales or overrides them in
  `raishxcore/core.toml` (`planner.missingWeights.multiplier` and
  `planner.missingWeights.overrides`), and `Ae2PlannerBridge` hands the effective
  map to every planning attempt. No weight is declared by default, so a key nobody
  registered weighs one and the unweighted path is unchanged.
- An exact oracle for the differential corpus (`MissingShortageOracle`) that
  computes the minimum weighted shortage by bounded enumeration over small
  components, used only by the corpus and never by production. A test checks every
  declared witness against it, and another checks the engine's reported shortage
  against it on all 27 MISSING cases.
- `lastPlan.routeChoiceLinks`: one count per comparison link that decided a route
  choice, so an operator sees why a route won and not only which one. The histogram
  is allocated only when a plan has a choice, so a single-route graph still pays
  nothing.

## 0.1.0-alpha.3

### Added

- Cooperative crafting-graph capture: the AE2 crafting graph is now captured
  across ticks in bounded slices under a shared per-tick budget, instead of one
  monolithic server-thread pass, so a cold or very large grid can no longer
  consume a whole tick before planning even starts. A slice stays interruptible
  inside a single key, so its cost no longer grows with the number of patterns
  that key holds.
- `raishxcore/core.toml` now exposes the full planner policy: workers, queue
  capacity, timeout, operation and depth limits, snapshot limits, cache entries
  and bytes, slice and tick budget, pending captures, per-grid in-flight cap, and
  circuit-breaker threshold and cooldown.
- Cancellation of obsolete planner work on graph revision change, grid unload or
  change, player logout, planner disablement and server stop.
- Deduplication of equivalent in-flight requests, with an independent future per
  caller, so one caller cancelling never cancels another caller's plan.
- A per-grid circuit breaker and per-grid backpressure, with the shared per-tick
  capture budget bounding total main-thread capture cost across all grids.
- Capture slice and tick histograms with p50/p95/p99, per-phase accumulators and
  the `plannerCaptureSlices` harness, which gates the deterministic slicing
  invariants against a p95 ceiling.
- A differential capability corpus with a common replay oracle, a production-path
  runner, an eight-way classification taxonomy and a CI gate.

### Fixed

- Coproduct ordering false negative found by the corpus: an input with a
  selectable route is now resolved before an input that only exists as a
  deterministic coproduct.
- The sources artifact now carries `META-INF/LICENSE-raishxcore.md`, like the
  runtime artifact.

### Notes

- "Verified superiority" over Thunderbolt V2 and AE2-VM is **not** claimed. The
  corpus, the oracle and the CI gate exist, but only the RaishxCore side runs
  today, and 15 roadmap items remain open. The authoritative state is
  `docs/planner-superiority-roadmap.md`.
- The deployed scheduler rotates between the pending captures of one grid, not
  across grids: the shared tick budget bounds the total cost per tick, and every
  grid finishes because captures are finite. See
  `docs/planner-cooperative-capture.md`.

## 0.1.0-alpha.2

### Added

- Server config `raishxcore/core.toml` with `planner.enabled` (default `true`): a
  kill-switch that hands every crafting calculation back to AE2's built-in planner
  without removing the mod. Diagnostics report the disabled state.
- Unexpected exceptions inside the async planner are now contained and logged with
  structured status (`timeout`, `cancelled`, `failed: ...`) instead of surfacing as
  raw execution errors, matching AE2's own calculation error contract.
- GameTest proving the decline path: a graph the Core cannot represent falls back
  to AE2's planner, which still serves the request.

### Changed

- `api.amount`, `api.multiblock`, `api.transaction` and `api.tier` are now marked
  experimental (implemented and tested, but no consumer yet).

## 0.1.0-alpha.1

- Created the standalone NeoForge 1.21.1 Core mod with AE2 integration.
- Added arbitrary-scale exact amount and rational-rate primitives.
- Added reusable tier, multiblock, port and transactional transfer contracts.
- Added universal AE2-style widget implementations.
- Added server-side machine packet validation and rate limiting.
- Added standalone tests, sources jar and Maven publication support.
- Integrated UFO Future 3.0.0-alpha.1 as the first real consumer.
