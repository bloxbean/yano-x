# Yano X architecture decisions

This directory retains the Yano decisions that define the Yano X repository boundary and its
extension contracts. The files are copied at the repository split boundary so Yano X development
does not require a sibling Yano checkout.

| Decision | Why it is here |
|---|---|
| [ADR-029](029-repository-split-analysis.md) | Original repository-split inventory, options, and rationale |
| [ADR-030](refactoring/030-repository-split-yano-x-execution-plan.md) | Repository ownership, execution phases, naming, packaging, and release gates |
| [ADR-011](app-layer/011-plugin-architecture.md) | Host/plugin SPI, catalog, compatibility, isolation, and lifecycle contract |
| [ADR-031](app-layer/031-composable-state-machine-foundation-and-portable-proofs.md) | Reusable state-machine, capability, composition, and portable-proof contract |
| [ADR-038](038-yano-x-documentation-site.md) | Public documentation site, its content strategy, and the AI ingestion layer |
| [ADR-039](039-geographically-distributed-deployment-automation.md) | Provider-neutral deployment to Contabo, Hetzner Cloud, DigitalOcean, existing VMs, or mixed placement using a locally promoted showcase ZIP |
| [ADR-040](040-product-specific-user-interfaces.md) | Separately deployable Yano X product UIs with runtime node discovery, beginning with EUTxO |
| [ADR-041](041-trust-preserving-l1-observation-delivery-and-consensus-recovery.md) | Quorum-certified recovery and durable L1 observation delivery without adding an operator or external oracle as a new authority |
| [ADR-042](042-l1-observation-and-eutxo-settlement-lifecycle.md) | End-to-end L1 observation, different-tip consensus behavior, and EUTxO deposit/withdrawal settlement lifecycle |
| [ADR-043](043-late-bound-l1-epoch-observer-activation.md) | Additive, forward-only activation design for new epoch observers without resetting retained chains |
| [ADR-044](044-yano-x-independent-review-and-gaps.md) | Independent implementation review, closure audit, and remaining gaps |
| [ADR-045](045-yano-x-release-readiness.md) | Release and deployment-posture readiness assessment |
| [ADR-046](046-out-of-the-box-product-portfolio.md) | Candidate out-of-the-box products under `products/`, selection criteria, capability coverage, and sequencing, for discussion |
| [ADR-047](047-attestation-and-certificate-service.md) | Attestation and Certificate Service: certificate format, offline verification model, CLI, and browser UI over a stock doc-trail chain |
| [ADR-048](048-evidence-desk.md) | Evidence Desk: browser UI for the role workflow and the evidence product, with in-browser actor signing, proof-bound records, and a document-review release adapter |
| [ADR-049](049-trust-and-status-registry.md) | Trust and Status Registry: config-only registry chain on the governed authenticated map, proof-answering client and CLI, Bitstring Status List and TRQP service, verifier console |
| [Phase evidence](refactoring/baselines/) | Executed baselines and regression evidence for Phases A-F |

The canonical pre-split history remains in the Yano repository. After extraction, decisions owned
by Yano X evolve here; host-contract changes that affect both repositories must be reflected in
both decision sets.

Commands, Gradle paths, and artifact IDs recorded in ADRs before ADR-030 are point-in-time
pre-split evidence. Use the repository [build guide](../docs/BUILD_AND_TEST.md), current
`settings.gradle`, and [artifact inventory](../config/artifacts-v1.json) for executable commands and
published coordinates. Links to core-owned decisions intentionally point to the Yano repository;
the decisions themselves were not duplicated into Yano X.
