# Planner configuration

RaishxCore writes the planner settings to `config/raishxcore/core.toml` as a
NeoForge COMMON configuration. Defaults preserve the tested behavior and every
numeric value has a validated range.

| Key | Default | Purpose | Reload behavior |
| --- | ---: | --- | --- |
| `planner.enabled` | `true` | Use the Core planner; `false` cancels Core work and delegates new requests to AE2. | Next request |
| `planner.workers` | `2` | Global calculation worker count. | Restart after pool creation |
| `planner.queueCapacity` | `32` | Global pending calculation capacity. | Restart after pool creation |
| `planner.timeoutMillis` | `2000` | Deadline shared by all attempts of one request. | Next request |
| `planner.maxOperations` | `10000000` | Deterministic operation ceiling per attempt. | Next request |
| `planner.maxDepth` | `100000` | Maximum dependency depth. | Next request |
| `planner.checkpointInterval` | `128` | Operations between timeout and cancellation checks. | Next request |
| `planner.snapshot.timeoutMillis` | `50` | Whole-capture deadline, counted across ticks, before AE2 fallback. | Next capture |
| `planner.snapshot.maxEdges` | `100000` | Pattern, input and output edge ceiling for one capture. | Next capture |
| `planner.snapshot.maxKeys` | `25000` | Distinct serialized AE key ceiling for one capture. | Next capture |
| `planner.snapshot.maxEstimatedBytes` | `67108864` | Conservative heap estimate ceiling for one snapshot. | Next capture |
| `planner.snapshot.cacheEntries` | `16` | Target snapshots retained per grid revision. | Next cache insertion |
| `planner.snapshot.cacheBytes` | `134217728` | Byte ceiling for the whole snapshot cache; least recently used snapshots are evicted first. | Next cache insertion |
| `planner.snapshot.sliceMillis` | `2` | Main-thread time one grid may spend on one capture slice before yielding the tick. | Next slice |
| `planner.snapshot.sliceEdges` | `512` | Edges one capture slice may consume; makes a slice deterministic. | Next slice |
| `planner.snapshot.tickBudgetMillis` | `4` | Capture budget shared by every grid in one server tick. | Restart after first use |
| `planner.snapshot.maxPendingCaptures` | `8` | Captures one grid may keep open across ticks before further requests wait for AE2. | Next request |
| `planner.maxInFlightPerGrid` | `4` | Distinct calculations admitted concurrently for one grid; equivalent requests still deduplicate. | Next request |
| `planner.circuitBreaker.failureThreshold` | `3` | Consecutive calculation failures before that grid delegates to AE2. | Next failure |
| `planner.circuitBreaker.cooldownMillis` | `10000` | Delay before one recovery probe is admitted for an unhealthy grid. | Next circuit transition |

Worker, queue and shared-tick-budget settings are startup-shaped because Java's
bounded queue capacity cannot be resized safely while calculations are active and
the shared budget hands out reservations that are already in flight. All other
settings are captured into an immutable policy for each request, so a running
calculation never observes half of an edited configuration.

Snapshot capture is cooperative: a request gets one bounded slice inside the
shared tick budget, and a capture that does not finish is resumed on later ticks
while AE2 holds a deferred future. Running out of tick budget always defers, never
falls back. Exceeding a total snapshot bound, or a semantic the model cannot
represent, discards the capture in full: the current request is answered by AE2 in
the same tick, and an already deferred request is handed to AE2's planner with the
reason recorded in the status. Exceeding a worker deadline or mathematical bound
after submission completes the returned future exceptionally; it is never silently
retried with different semantics.

## Diagnostics

`/raishxcore planner` prints one JSON line per live planner: revision, cache hits and
misses, status, the last plan's operation count and elapsed time, the shortage split
described below, queue and worker counts, backpressure and circuit-breaker state,
capture counters and byte ceilings, and the capture histograms.

Inside `lastPlan`, `missingConsumable`, `missingSeed` and `missingCarrier` are how
much of the shortage is material that is gone once used, material that is handed
back so one unit covers the batch, and carriers that wear out. The three `...Kinds`
fields count how many distinct keys fall in each. This is the part that tells an
operator a shortage includes a catalyst they get back rather than material they have
to find. Item identities are deliberately absent: the payload stays numbers. It needs permission level 2, which is the same level as
most operator diagnostics, and it is deliberately a command rather than a background
log: nothing is written unless somebody asks.

The payload is a bag of numbers. It holds no `AEKey`, no NBT and no reference to the
grid, so a line captured now cannot keep a network alive after its lifecycle ends.
At most eight planners are printed and the remainder is counted rather than silently
dropped.

Backpressure is isolated per grid, so one busy network cannot consume every
global queue slot. A circuit breaker also isolates repeated failures from one
grid. Lifecycle cancellation and graph revision changes reset that circuit;
ordinary cancellation never counts as a planner failure.
