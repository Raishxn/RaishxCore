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
| `planner.snapshot.timeoutMillis` | `50` | Main-thread capture deadline before AE2 fallback. | Next capture |
| `planner.snapshot.maxEdges` | `100000` | Pattern, input and output edge ceiling. | Next capture |
| `planner.snapshot.maxKeys` | `25000` | Distinct serialized AE key ceiling. | Next capture |
| `planner.snapshot.maxEstimatedBytes` | `67108864` | Conservative heap estimate ceiling for one snapshot. | Next capture |
| `planner.snapshot.cacheEntries` | `16` | Target snapshots retained per grid revision. | Next cache insertion |

Worker and queue settings are startup-shaped because Java's bounded queue
capacity cannot be resized safely while calculations are active. All other
settings are captured into an immutable policy for each request, so a running
calculation never observes half of an edited configuration.

Exceeding a snapshot bound declines the Core path before worker submission and
lets AE2 handle the request. Exceeding a worker deadline or mathematical bound
after submission completes the returned future exceptionally; it is never
silently retried with different semantics.
