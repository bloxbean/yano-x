# Yano X architecture decisions

This directory retains the Yano decisions that define the Yano X repository boundary and its
extension contracts. The files are copied at the repository split boundary so Yano X development
does not require a sibling Yano checkout.

| Decision | Why it is retained here |
|---|---|
| [ADR-029](029-repository-split-analysis.md) | Original repository-split inventory, options, and rationale |
| [ADR-030](refactoring/030-repository-split-yano-x-execution-plan.md) | Repository ownership, execution phases, naming, packaging, and release gates |
| [ADR-011](app-layer/011-plugin-architecture.md) | Host/plugin SPI, catalog, compatibility, isolation, and lifecycle contract |
| [ADR-031](app-layer/031-composable-state-machine-foundation-and-portable-proofs.md) | Reusable state-machine, capability, composition, and portable-proof contract |
| [Phase evidence](refactoring/baselines/) | Executed baselines and regression evidence for Phases A-D |

The canonical pre-split history remains in the Yano repository. After extraction, decisions owned
by Yano X evolve here; host-contract changes that affect both repositories must be reflected in
both decision sets.
