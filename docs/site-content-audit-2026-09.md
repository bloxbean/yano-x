# Documentation audit — September 2026

Scope: the current `www/` documentation collection, landing page, generated
catalogs, and selected canonical guides linked by the site. The checkout pins
Yano `0.1.0-pre15` and the `org.yanoproject.x` extension namespace.

## Corrections

| Area | Correction | Evidence |
| --- | --- | --- |
| Mermaid | Serialize asynchronous rendering; retain source as text; render directly from that source; handle theme changes without reusing generated SVG. Simplify the boundary diagram. | Chromium rendering checks for all 11 diagrams across 7 pages, including rapid theme changes. |
| Java plugin examples | Use `AppBlockExecutionContext` and the effect emitter in `apply` and `onEffectResult`. Remove the legacy ServiceLoader-only activation claim. | Public host `AppStateMachine` SPI and the current `scaffolds/plugin-template` implementation. |
| REST proof reference | OrderedLog uses a namespace-derived key; it is not the raw message id. Prefer the typed finalized-message subject. | Host `OrderedLogStateMachine` and `AppChainResource` implementations. |
| Proof verification | Separate online proof retrieval from offline verification with independently authenticated identity/root inputs. | `tooling/devtools/.../AppChainStateCli.java`, including required verify options. |
| Product overview | Distinguish runtime plugins from clients, static UIs, derived services, and products configured on stock state machines. | Product modules, artifact catalog, and Attest/Trust Registry/Explorer guides. |
| Evidence Desk | Start from a packaged showcase, preserve the working-directory context, and use the printed node ports. | Showcase distribution layout and `distribution/jvm/jvm-distribution.gradle`. |
| Contributor entry | Correct `docsite/` to `www/` and direct first-time users to releases. | Repository layout and release/download workflow. |
| Configuration and anchoring | Do not imply any genesis setting can be changed through governance. Distinguish script threshold protection from fee-wallet security and anchor liveness. | Configuration metadata and host/plugin boundaries. |
| Introductory/AI wording | Remove claims about model training, clarify chain ids and product scope, and retain technical terminology in guides. | Architecture invariants and current API contracts. |

Revised site prose lives in `docs/site/` and is imported into its existing URL.
Version and artifact tables remain generated; third-party Cardano, Yaci, JuLC,
and ZeroJ namespaces are deliberately preserved.

## Validation

- Production Astro build, Mermaid source lint, search/AI artifact generation,
  and internal link checks passed.
- Eight Chromium tests passed: every diagram page in both themes, rapid theme
  changes, and mobile landing controls/navigation.
- Namespace/stale-path scan covered all 61 documentation pages.
- All 130 distinct Yano X repository targets linked from those pages exist.
- Java callback shapes and proof CLI arguments were checked against source;
  the illustrative snippets were not compiled as standalone applications.

This is a documentation and browser validation pass, not a runtime release
qualification. It does not rerun deployment, connector, multi-node, or public
network scenarios, or establish the correctness of every domain claim. Retained
clusters, keys, host sources, and running application state were not modified.
