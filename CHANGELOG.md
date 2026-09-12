# Changelog

## Unreleased

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

