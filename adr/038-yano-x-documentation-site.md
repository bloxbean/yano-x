# ADR-038 — Yano X public documentation site

- **Status:** Accepted
- **Date:** 2026-08-23
- **Owner:** Yano X
- **Scope:** Public developer documentation, AI-agent ingestion artifacts, and
  their build/deploy pipeline. No runtime, consensus, plugin-contract, or
  distribution behavior changes.

## 1. Context

Yano X ships a large, accurate, and repository-internal documentation set:

| Location | Size | Audience today |
|---|---:|---|
| `docs/APP_CHAIN_USER_GUIDE.md` | ~113 KB | Maintainers, deep integrators |
| `docs/APP_CHAIN_OVERVIEW.md` | ~21 KB | Architecture readers |
| `docs/appchain/tutorials/01..09` | ~9 files | Hands-on developers |
| `docs/appchain/state-machines/*` | 7 files | Application developers |
| `docs/appchain/{CAPABILITIES,PROOF_LAB,OPTIONAL_CONNECTORS,…}.md` | 6 files | Operators, platform engineers |
| `docs/BUILD_AND_TEST.md`, `docs/BUILD_DISTRIBUTIONS.md` | ~6 KB | Contributors |
| `adr/**` | ~60 files | Maintainers |

That corpus is written for readers who have already cloned the repository. It
has three gaps for a first-time user:

1. **No entry point.** There is no page that answers "what is an app chain, why
   Yano X, and how do I have one running" in a single reading pass. The nearest
   equivalent, `docs/appchain/README.md`, is a routing table that assumes the
   reader already accepted the premise.
2. **No install story.** Yano X has **no published release**. Every documented
   command carries build flags (`-PyanoVersion`, `-PuseMavenLocal`,
   `-PyanoJvmDist`) drawn from the *contributor* workflow, so a user cannot tell
   which flags they actually need.
3. **No AI-agent surface.** Yano X is an extension framework whose primary
   authoring act is writing a plugin against an SPI. That is exactly the task
   developers now delegate to coding agents, and agents have no Yano X training
   data. The repository already contains a machine-readable DX layer
   (`tooling/devtools/src/main/resources/appchain-dx/v1alpha1/`) including a
   `configure-yano-appchain` skill, but nothing publishes it.

The sibling JuLC project (`bloxbean/julc`) solved the same problem with an
Astro + Starlight site plus a generated AI-ingestion layer (`/llms.txt`,
`/llms-full.txt`, `/ai/starter-pack.md`, `/ai/catalog.json`). That stack is
proven inside the organization and is the requested basis for this work.

### 1.1 The install story is better than the documentation implies

This was verified rather than assumed, and it is the single most important
input to the site's structure.

`gradle.properties` pins `yanoVersion=0.1.0-pre13`. Because that is a
non-SNAPSHOT version, the distribution tasks resolve the base Yano JVM ZIP from
the matching GitHub release. The asset exists and is public:

```
https://github.com/bloxbean/yano/releases/download/v0.1.0-pre13/yano-0.1.0-pre13.zip
  277,165,009 bytes   sha256:1d280bbed66f1824e779a12f26ef1f565bebbd31dac994136936e2810b506746
```

Therefore the **user** self-build track is a clean clone and one command, with
no Yano checkout, no Maven Local, and no `-P` flags:

```bash
git clone https://github.com/bloxbean/yano-x.git
cd yano-x
./gradlew clean build -PskipSigning=true
# → distribution/jvm/build/distributions/yano-x-jvm-<version>.zip
```

The two-repository dance in `AGENTS.md` and `docs/BUILD_AND_TEST.md`
(`publishToMavenLocal` + `:app:yanoDistZip` + `-PyanoJvmDist`) exists for
**coordinated Yano/Yano X development** and must not be presented as the
install path.

## 2. Decision

Build `www/`, an Astro 6 + Starlight documentation site for Yano X,
deployed to **`yanox.dev`** via GitHub Pages, with an AI-first ingestion layer
generated at build time from the repository's own canonical sources.

### 2.1 Decisions in brief

| # | Decision | Rationale |
|---|---|---|
| D1 | The site lives at `www/`; the `docs/` markdown corpus is left where it is | The corpus has ~150 inbound references across READMEs, ADRs, release-gated catalogs, a Gradle packaging task, and a cross-repo link. When two things collide on a name, the one with fewer inbound references moves — and that is the brand-new site. See §3 |
| D2 | Astro 6 + Starlight 0.38, matching JuLC | Proven in-org; search, dark mode, MDX, and content collections without bespoke work |
| D3 | Custom domain `yanox.dev`; `site` set, **no `base`** | Keeps every generated absolute URL prefix-free; a project-page `base` would have to be threaded through every generated artifact |
| D4 | Curated authored pages **plus** a build-time import of the tutorial and state-machine corpus | Full hands-on coverage without hand-copying files that then diverge |
| D5 | The 113 KB user guide and other exhaustive references are **linked, not imported** | Duplicating it guarantees drift; a Reference Shelf page deep-links to GitHub blobs |
| D6 | All catalog/version data is **generated** from existing JSON + `gradle.properties` | The repository already publishes machine-readable catalogs; prose tables would rot |
| D7 | Ship `/llms.txt`, `/llms-full.txt`, `/ai/catalog.json`, `/ai/starter-pack.md` | Matches the JuLC contract and `llmstxt.org`; the starter pack is the highest-leverage artifact for an extension framework |
| D8 | Two clearly separated build tracks: **Use** (self-build) and **Contribute** (coordinated) | The current documentation conflates them, which is the main install-story defect |
| D9 | Deploy on a `dv*` tag, matching JuLC | Documentation ships independently of code releases, which matters while Yano X is unreleased |
| D10 | Mirror the App-Chain Studio into `/studio/` | The tutorials already deep-link to `tooling/studio/src/main/web/index.html#recipe=…`. Linking those to GitHub would show HTML source; mirroring the Gradle `prepareStudio` task instead makes them open a working blueprint builder |
| D11 | An internal-link checker is a build gate | With imported content, generated tables, and cross-section links, a broken link is the most likely defect. `npm run build` fails on one rather than shipping it |

### 2.2 Non-goals

- No change to `docs/`, `adr/`, or any runtime module. The site reads them; it
  never rewrites them.
- No versioned documentation (`/v0.1/…`). Yano X is pre-release and has one
  line; versioning is a later decision.
- No mirror of `APP_CHAIN_USER_GUIDE.md`, `adr/**`, or module READMEs.
- No Yano-core documentation. The site covers Yano X and links to Yano for host
  concerns.
- No native-image, packaging, or CI-gate changes beyond the new deploy
  workflow.

## 3. Directory layout

```text
www/    the published documentation site (Astro + Starlight)
docs/   the markdown corpus — source of truth, promoted to the site
adr/    architecture decisions
```

### 3.1 Why the site moved instead of the corpus

Naming the site `docs/` (matching the sibling JuLC repository) would have
required renaming the existing corpus. That option was explored and measured
before being rejected:

| Coupling | Count |
|---|---:|
| Markdown/Gradle/script references resolving into the corpus | 97 |
| `documentation` fields in the release-gated recipe and capability catalogs, printed at runtime by the CLI and Studio | 33 |
| Hand-written GitHub URLs in authored site pages | 17 |
| Gradle packaging task shipping the corpus into the JVM distribution | 1 |
| Test reading the tutorial corpus by path (`studio.test.mjs`) | 1 |
| Inbound link from the sibling Yano repository | 1 |

Two of those fail silently. `jvm-distribution.gradle` reads
`rootProject.file('docs')`; had the site taken that name, the release ZIP would
have packaged the website — `node_modules` included — instead of the
documentation. And `docs/` is an overloaded token in this repository: it also
means `examples/showcase/docs/` and the `docs/TRUST.md` files
`AppChainProjectRenderer` writes into *generated user projects*, so no global
rename is safe.

The rule that settles it: when two things collide on a name, the one with fewer
inbound references moves. That is the brand-new, entirely uncommitted site.
`www/` is unambiguous, leaves every existing reference and external link
working, and keeps the ADRs untouched as dated evidence.

## 4. Information architecture

The site answers four questions in order: *what is this*, *how do I run it*,
*how does it work*, *how do I extend it*.

```
/                                  Landing page (custom, non-Starlight)
├── start-here/
│   ├── what-is-an-app-chain       The concept, before any Yano specifics
│   ├── why-yano-x                 Yano vs Yano X boundary; what X adds
│   ├── build-from-source          The self-build track (D8, "Use")
│   └── quickstart                 3-member chain running in ~10 minutes
├── concepts/
│   ├── architecture               Four layers, two-plane design
│   ├── consensus-and-finality     Proposer, threshold, certificates, membership
│   ├── state-and-proofs           Deterministic state, MPF, proof subjects
│   ├── effects                    Intent/execution split, finality gates
│   ├── anchoring                  Metadata vs script anchors, verification
│   └── determinism-rules          What consensus code may not do
├── recipes/
│   ├── index                      GENERATED recipe table + availability vocabulary
│   └── choosing-a-recipe          Decision guidance
├── plugins/                       ← the Yano X plugin framework
│   ├── index                      Extension ladder: config → composite → custom
│   ├── scaffold-sign-install      scaffold → implement → sign → validate → pin → install
│   ├── spi-and-manifest           PluginProviderRegistry, manifest v1, catalog, isolation
│   ├── consensus-rules            Determinism, versioning, upgrade discipline
│   └── testing-and-deployment     Testing ladder, plugin pack, doctor
├── tutorials/                     IMPORTED from docs/appchain/tutorials/
├── state-machines/                IMPORTED from docs/appchain/state-machines/
├── products/
│   ├── index                      Evidence, Cardano History, eUTxO/ZK
│   ├── evidence
│   ├── cardano-history
│   └── eutxo-and-zk               Marked experimental
├── reference/
│   ├── cli                        yano.sh appchain surface
│   ├── configuration              GENERATED from appchain-first-party-metadata.json
│   ├── capabilities               GENERATED from appchain-capability-catalog.json
│   ├── modules                    GENERATED from config/artifacts-v1.json
│   ├── rest-api                   Public API surface
│   └── shelf                      Deep links to the exhaustive repo documents (D5)
├── ai/
│   ├── index                      Per-tool setup: Claude Code, Cursor, Continue, ChatGPT
│   └── starter-pack               The single highest-leverage ingestion artifact
└── contributing/
    └── index                      Coordinated Yano + Yano X development (D8, "Contribute")
```

## 5. Content strategy

### 5.1 Three content classes

| Class | Where it lives | How it stays correct |
|---|---|---|
| **Authored** | `www/src/content/docs/**` (committed) | Reviewed like code; sourced from repo docs at writing time |
| **Imported** | `www/src/content/docs/{tutorials,state-machines}/**` (git-ignored) | Regenerated from `docs/` on every build; `docs/` stays the single source of truth |
| **Generated** | Catalog tables, `/ai/*`, `/llms*.txt` (git-ignored) | Derived from JSON catalogs and `gradle.properties` at build time |

Only the authored class is a maintenance burden. The other two cannot drift by
construction.

### 5.2 The App-Chain Studio (D10)

Every tutorial opens with a link like:

```markdown
[Open this outcome in App-Chain Studio](../../../tooling/studio/src/main/web/index.html#recipe=audit-log&…)
```

Studio is a dependency-free static app that fetches three JSON catalogs from
`assets/`. The Gradle task `:tooling:studio:prepareStudio` assembles it by
copying `src/main/web/` plus five catalog files, expanding one version
placeholder. The importer reproduces exactly that into `www/public/studio/`,
and rewrites those links to `/studio/index.html#…`.

The result is that a reader following a tutorial link lands in a working
blueprint builder pre-filled with that tutorial's recipe, rather than on a page
of HTML source.

### 5.3 Import and link rewriting

`www/scripts/import-repo-docs.mjs` runs before `astro dev` and
`astro build`. For each imported file it:

1. reads the source under `docs/`;
2. lifts the leading `# Heading` into Starlight `title` frontmatter and derives
   a `description`;
3. strips the "On this page" / table-of-contents block Starlight renders itself;
4. rewrites every relative markdown link.

Link rewriting is **path-resolution based, not pattern based**. A link is
resolved against the source file's directory to a repository-root-relative
path, then:

- if that path is itself imported, it becomes the site route;
- otherwise it becomes
  `https://github.com/bloxbean/yano-x/blob/main/<repo-path>`, preserving the
  anchor.

This is the reason the import is safe: the tutorials link into `ledgers/`,
`scripts/`, `adr/`, `tooling/`, and `config/`, and no enumeration of those
patterns has to be maintained. An unresolvable link fails the build rather than
shipping a 404.

### 5.4 Generated data sources

Everything version- or catalog-shaped is read from files the build already
maintains:

| Site output | Source of truth |
|---|---|
| Yano X version, Yano version, Java version | `gradle.properties` |
| Recipe table | `tooling/devtools/.../appchain-recipe-catalog.json` (11 recipes) |
| Capability table | `tooling/devtools/.../appchain-capability-catalog.json` (43 capabilities, 18 artifacts) |
| Configuration reference | `tooling/devtools/.../appchain-first-party-metadata.json` |
| Distribution/runtime matrix | `tooling/devtools/.../appchain-release-capability-index.json` |
| Module and artifact inventory | `config/artifacts-v1.json` (52 modules) |
| Base Yano ZIP URL and digest | `bloxbean/yano` release convention in `docs/BUILD_DISTRIBUTIONS.md` |

Injection uses the JuLC anchor convention already present in
`docs/appchain/CAPABILITIES.md`:

```markdown
<!-- catalog:recipes-start -->
<!-- catalog:recipes-end -->
```

No version string is hardcoded in prose.

## 6. AI-first layer

Yano X's authoring surface is a plugin SPI, so the agent-facing content differs
in kind from JuLC's. JuLC's starter pack teaches a restricted language; the
Yano X starter pack teaches **a decision ladder and a set of hard invariants**.

Published artifacts:

| Path | Content |
|---|---|
| `/llms.txt` | `llmstxt.org` curated index with the key facts an agent needs before generating anything |
| `/llms-full.txt` | Every site page concatenated, MDX-sanitized, headings demoted |
| `/ai/starter-pack.md` | Raw markdown, catalog-injected, fetchable as `CLAUDE.md` |
| `/ai/catalog.json` | Machine-readable recipes, capabilities, artifacts, config properties, versions |

Starter-pack content (the invariants an agent gets wrong without it):

1. **Yano vs Yano X.** Dependency direction is strictly `yano-x → yano`. Never
   propose a composite build, sibling task invocation, or source dependency on
   a Yano checkout.
2. **The extension ladder.** Configuration → composite plugin → custom
   state-machine plugin. Always choose the smallest rung that works.
3. **Everything optional crosses the plugin boundary.** Activation is
   `PluginProviderRegistry` + a schema-v1 manifest. Never raw `ServiceLoader`,
   direct host construction, or product-specific CDI/REST activation.
4. **Determinism rules for `apply`.** No wall-clock, randomness,
   environment-dependent iteration order, network I/O, or node-local mutable
   decisions. External work is an emitted effect record, never a call.
5. **Invariants that look like typos.** Packages stay
   `com.bloxbean.cardano.yano.appchain.*` while artifacts are `yano-x-*`; the
   property is `yano.plugins.directory` and never `yaci.plugins.directory`;
   Yano X is JVM-only and `verifyJvmOnlyBuild` enforces it.
6. **The real workflow.** `scaffold → implement → sign → validate → init
   --plugin-jar → doctor → copy into plugins/ on every member`.
7. **Consensus-semantics changes are versioned upgrades**, not rolling code
   changes.
8. **Secrets discipline**, aligned verbatim with the in-repo
   `configure-yano-appchain` skill.

The starter pack **links to and aligns with** that existing skill
(`tooling/devtools/src/main/resources/appchain-dx/v1alpha1/skills/configure-yano-appchain/SKILL.md`)
rather than inventing a competing contract. Where they overlap, the skill wins
and the starter pack quotes it.

## 7. Technical design

### 7.1 Layout

```
www/
├── astro.config.mjs           site: https://yanox.dev, sidebar, integrations
├── package.json               dev/build run the importer first
├── tsconfig.json
├── .gitignore                 dist/, .astro/, node_modules/, imported + generated content
├── README.md
├── public/{CNAME,logo.svg,favicon.svg}
├── scripts/
│   ├── repo-sources.mjs       Shared: repo root resolution, version, JSON loaders
│   ├── import-repo-docs.mjs   docs/ → content collection, with link rewriting
│   ├── generate-catalog.mjs   → /ai/catalog.json, markdown table renderers
│   ├── generate-llms-txt.mjs  → /llms.txt, /llms-full.txt, raw /ai/*.md
│   └── llms-integration.mjs   Astro integration: build hook + dev middleware
└── src/
    ├── content.config.ts
    ├── content/docs/**        Authored pages (+ imported, git-ignored)
    ├── components/
    │   ├── overrides/         Header, Head
    │   └── landing/           Landing sections
    ├── pages/index.astro      Landing page
    └── styles/                Starlight theme + landing CSS
```

### 7.2 Build pipeline

```
npm run build
  └─ node scripts/import-repo-docs.mjs      docs/ → src/content/docs/{tutorials,state-machines}
  └─ astro build
       ├─ Starlight renders HTML + Pagefind search index
       └─ astro:build:done  (llms-integration)
            ├─ generateCatalog()      JSON catalogs + gradle.properties
            ├─ generateLlmsFiles()    llms.txt, llms-full.txt, raw /ai/*.md
            └─ writeCatalog()         /ai/catalog.json
```

In `astro dev` the same generators run behind a Vite middleware on the served
paths, so `/llms.txt` and `/ai/catalog.json` work locally without a build.

### 7.3 Theme

The Yano mark is a cyan → indigo → violet → magenta gradient. The site leans on
the **violet/magenta end**, which is its distinctive half: a flat blue accent on
a navy ground is the default look of every developer documentation site, and
reads as generic. The ground is near-black with a violet cast (`#0b0818`), the
accent is `#7c5cff` on dark and `#6d28d9` on light, and the four-stop gradient
itself appears as the brand signature — under the site header, on the hero
headline, and through the hero illustration.

The JuLC color-scheme picker is intentionally **not** carried over; one accent
keeps the brand tighter.

Two implementation traps are worth recording, because both produce defects that
look like design mistakes:

**`.header` is not unique in a Starlight page.** The site header, Starlight's
inner header flex container, and *every* Expressive Code code-block caption
carry that class. A bare `.header::after` therefore paints the brand gradient on
all of them. The rule is scoped to `.page > header.header`, which matches
exactly one element per page.

**Starlight's palette lives inside `@layer starlight.base`, and unlayered CSS
beats layered CSS regardless of specificity.** A bare `:root` block of dark
values in `customCss` therefore overrides Starlight's *light* theme too — even
`:root[data-theme='light']` inside the layer loses to it. The result is a broken
mix: Starlight's light nav background above a page whose `--sl-color-bg` is
still dark. Both palettes must be declared here, each mirroring the selector
Starlight itself uses (`:root, ::backdrop` for dark;
`:root[data-theme='light'], [data-theme='light'] ::backdrop` for light), with a
complete inverted gray ramp for light.

### 7.4 The hero illustration

The landing page leads with an inline SVG of the app-chain pipeline rather than
a screenshot or an abstract graphic. It shows the one claim that separates an
app chain from a shared database: a signed command reaches three members, each
independently re-executes it and derives the **identical** state root, a
threshold signs the block, and that root settles on Cardano while effects fan
out.

The three root chips are the point of the drawing, so they pulse in unison
rather than in sequence — a staggered animation would imply replication, which
is exactly the wrong mental model. All motion is disabled under
`prefers-reduced-motion`.

### 7.5 Deployment

`.github/workflows/docs-deploy.yml`, triggered by `dv*` tags and
`workflow_dispatch`. Node 22, `npm ci`, `npm run build`, publish `www/dist`
to GitHub Pages with `cname: yanox.dev`. No Gradle, no Java, no Yano artifact
resolution — the site build reads repository text files only, so it is fast and
cannot be broken by an upstream Yano release.

## 8. Consequences

### Positive

- A first-time user reaches a running three-member app chain from a clean
  clone, with the correct minimal command set.
- The Yano X value proposition — the plugin framework — gets first-class
  treatment instead of being tutorial 8 of 9.
- Coding agents get a real ingestion surface for a framework with no training
  data.
- Catalog and version data cannot go stale; a new recipe or capability appears
  on the site on the next build.
- `docs/` keeps its role as the maintainer-facing source of truth, unchanged.

### Negative / accepted

- **A second documentation surface exists.** Mitigated by keeping authored
  pages navigational and conceptual, and pushing exhaustive detail to imported
  or linked repo documents.
- **The importer is a maintenance surface.** Mitigated by path-resolution link
  rewriting and by failing the build on an unresolvable link.
- **Node/npm enters the repository toolchain**, but only under `docs/` and
  only in the docs workflow. The Gradle build is untouched, and
  `verifyJvmOnlyBuild` is unaffected.
- **`yanox.dev` must be registered and pointed at GitHub Pages** before the
  first `dv*` tag. Until then the workflow can be run manually and the artifact
  previewed locally.
- **The starter pack is hand-authored** and can drift from the SPI. Mitigated
  by sourcing its hard invariants from `AGENTS.md` and ADR-011, both of which
  change rarely and are reviewed when they do.

## 9. Implementation plan

| Phase | Deliverable | Verification |
|---|---|---|
| 1 | Branch `feat/docsite`; this ADR; `adr/README.md` row | — |
| 2 | Astro scaffold, theme, landing page, `public/` assets | `npm run dev` serves `/` |
| 3 | `repo-sources.mjs`, `generate-catalog.mjs` | `/ai/catalog.json` matches the source JSON counts |
| 4 | `import-repo-docs.mjs` | 9 tutorials + 7 state-machine pages import; zero unresolved links |
| 5 | Authored pages: start-here, concepts, recipes | `npm run build` clean |
| 6 | Authored pages: plugins, products, reference | `npm run build` clean |
| 7 | `ai/index`, `ai/starter-pack`, llms generation | `/llms.txt`, `/llms-full.txt`, `/ai/starter-pack.md` present in `dist/` |
| 8 | `docs-deploy.yml`; `docs/README.md`; root README pointer | Workflow lint; full `npm run build` |

All eight phases are implemented. The delivered build produces 50 pages
(24 authored, 18 imported, plus the landing page, 404, and section indexes), a
Pagefind search index, a sitemap, the four AI artifacts, and the mirrored
Studio. `npm run build` runs the importer, Astro, and the link checker in one
command; the deploy workflow additionally sets `YANO_X_REQUIRE_LISTED_DOCS=1`
so an unlisted page fails CI instead of landing silently in an "Other"
section.

## 10. Alternatives considered

| Alternative | Rejected because |
|---|---|
| **Full mirror of `docs/` into the site** | Two copies of a 113 KB guide; guaranteed drift; the repo-relative link graph reaches into `ledgers/`, `scripts/`, and `adr/` |
| **Curated pages only, no import** | The nine tutorials are the best asset Yano X has; leaving them on GitHub wastes them and splits the reading experience |
| **Docusaurus / MkDocs / VitePress** | JuLC's Astro + Starlight stack is already proven in-org, and the AI-generation scripts port directly |
| **GitHub Pages project page (`/yano-x` base)** | Every generated absolute URL would need the base prefix threaded through; a silent source of broken AI-artifact links |
| **Site inside `docs/`, JuLC-style** | `docs/` is an occupied namespace here; co-locating would make both roles ambiguous |
| **Publish `adr/**` to the site** | ADRs are point-in-time decisions, several explicitly marked pre-split evidence; publishing them as documentation would mislead |
| **Hand-written capability/recipe tables** | Three machine-readable catalogs already exist and are release-gated |

## 11. References

- `adr/app-layer/011-plugin-architecture.md` — host/plugin SPI and catalog contract
- `adr/refactoring/030-repository-split-yano-x-execution-plan.md` — repository boundary
- `AGENTS.md` — architecture invariants quoted by the AI starter pack
- `docs/appchain/README.md` — routing table the site's IA is derived from
- `docs/BUILD_AND_TEST.md`, `docs/BUILD_DISTRIBUTIONS.md` — build tracks
- `tooling/devtools/.../skills/configure-yano-appchain/SKILL.md` — existing AI contract
- `bloxbean/julc` `docs/` — reference implementation of the Astro + AI stack
