# RaishxCore

Reusable NeoForge 1.21.1 foundation for AE2-oriented addons.

Current API areas:

- exact high-scale amounts and rational rates;
- data-oriented tiers;
- pure multiblock definitions and runtime states;
- deterministic aggregated transfer plans;
- technology-neutral ports for energy, fluids, items, chemicals and coolant;
- reusable AE2-style GUI widgets;
- guarded server-side machine actions with per-player rate limiting.

Stable-domain contracts live under `com.raishxn.ufocore.api`. NeoForge and
client adapters deliberately live outside that package and may evolve until
API 1.0. The Core contains infrastructure only; blocks, recipes and progression
belong to consuming addons.

Currently consumed by UFO Future: the port contracts (`api.port`), crafting
capacity and shared CPU pool (`api.crafting`), the AE2 planner integration
(replaces AE2's planner; kill-switch in `config/raishxcore/core.toml`), the
guarded machine action/network contract and the reusable GUI widgets. The
`api.amount`, `api.multiblock`, `api.transaction` and `api.tier` domains are
**experimental**: implemented and tested, but no consumer uses them yet, so
their contracts may still change.
composite build during development and declares `raishxcore` as a required mod.
See [docs/consumer-integration.md](docs/consumer-integration.md) for the exact
development and release setup.
