# RaishxCore and UFO Future robustness roadmap

This roadmap records the order agreed after the 2026-09-16 technical audit.
Foundation work takes precedence over new gameplay. Gameplay ideas are retained
for later updates and are not part of the current hardening milestone.

## Milestone 1 — release hardening

- [x] Give RaishxCore its own build, unit-test, GameTest and benchmark CI.
- [x] Exclude datagen caches from the UFO runtime JAR.
- [x] Include project licenses/notices in runtime JARs.
- [x] Produce a UFO sources JAR.
- [x] Correct the documented Core name, artifact and current version.
- [x] Rate-limit the custom C2S actions that target players or held items.
- [ ] Add ownership/team/claim authorization to machine actions.
- [x] Remove the unfinished side-configuration protocol surface; reintroduce it
      only with persistence, UI, capability filtering and tests.
- [ ] Add contract and GameTests for every armor module.
- [ ] Move armor limits and costs into server-authoritative configuration.
- [ ] Complete the legacy 2.x world and real-modpack release QA.

Gate: clean reproducible artifacts, all C2S actions under a central policy,
green builds/GameTests and documentation matching the published code.

## Milestone 2 — planner hardening

- [ ] Replace monolithic server-thread graph capture with incremental snapshots
      or cooperative capture budgeted across ticks.
- [x] Deduplicate equivalent in-flight requests with independent caller futures.
- [ ] Cancel obsolete work on graph revision, unload, logout and server stop
      (revision changes and planner disablement are complete).
- [ ] Configure worker, queue, timeout, graph and memory limits.
- [ ] Add per-grid backpressure and a circuit breaker.
- [ ] Expose latency, queue, cache, decline, timeout and fallback metrics
      (request, deduplication, cancellation, worker and queue counters are complete).
- [ ] Test several grids and requests concurrently.
- [ ] Make the benchmark a regression gate.
- [ ] Test the lowest and highest supported AE2 versions.

Gate: a cold request cannot monopolize a server tick, concurrent load remains
bounded, and resource conservation remains proven.

## Milestone 3 — transactions and persistence

- [ ] Add a shared `simulate -> reserve -> validate -> commit` engine.
- [ ] Support rollback, refund, partial commit and idempotency keys.
- [ ] Persist a journal and recover safely after crashes.
- [ ] Support item, fluid, chemical, FE, AE and exact large amounts.
- [ ] Add explicit data versions and sequential migrations to persistent state.
- [ ] Keep golden fixtures for every supported release.
- [ ] Bound all NBT and network collection/number sizes.

Gate: crash and unload tests prove no duplication or resource loss at every
transaction boundary.

## Milestone 4 — multiblock toolkit extraction

- [ ] Extract definitions, compiled patterns, constraints and orientation.
- [ ] Extract the scanner with explainable diagnostics.
- [ ] Extract membership indexing and event-driven invalidation.
- [ ] Extract state snapshots and delta-budgeted synchronization.
- [ ] Extract auto-build, holograms and viewer adapters.
- [ ] Extract reusable ports and optional integration adapters.
- [ ] Convert UFO Future to the APIs and remove duplicate implementations.

Gate: one canonical definition drives formation, diagnostics, highlight,
auto-build, JEI/EMI and GuideME.

## Milestone 5 — scheduling, security and observability

- [ ] Add a cooperative, fair work scheduler with per-tick budgets.
- [ ] Support pausable, cancellable and persistent jobs.
- [ ] Add owner/team permissions and optional claim adapters.
- [ ] Add structured machine blockage reasons.
- [ ] Add commands, JSON diagnostics and JFR markers.
- [ ] Publish metrics per grid, machine and workload type.

## Milestone 6 — platform validation

- [ ] Publish an addon testkit.
- [ ] Build a small second addon using public Core APIs only.
- [ ] Refine contracts based on that consumer.
- [ ] Enforce binary compatibility.
- [ ] Publish API 1.0 only after the second consumer succeeds without forks.

## Later gameplay updates

- network operations dashboard;
- reusable multiblock blueprints;
- production contracts with distributed jobs;
- armor loadouts and energy budgets;
- dynamic, telegraphed Stellar Nexus events;
- portable UFO terminal;
- telemetry-driven maintenance;
- domain-based technology progression;
- guided recovery for structures and jobs;
- validated data/KubeJS APIs for tiers, coolants and simulations.

## Suggested release sequence

1. UFO `3.0.0-alpha.2`: Milestone 1 hardening.
2. RaishxCore `0.1.0-alpha.3`: independent CI/release and API hygiene.
3. RaishxCore `0.2.0`: planner hardening.
4. RaishxCore `0.3.0`: transactions and migrations.
5. RaishxCore `0.4.0`: multiblock toolkit.
6. RaishxCore `0.5.0`: scheduler, diagnostics and ownership.
7. UFO `3.0.0-beta.1`: full migration onto the extracted toolkit.
8. RaishxCore `0.9.0`: second-addon validation.
9. RaishxCore `1.0.0` and UFO `3.0.0`: stable releases.
