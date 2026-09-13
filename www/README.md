# Yano X documentation site

The public documentation site for Yano X, published at
[yanox.dev](https://yanox.dev). Built with [Astro](https://astro.build) and
[Starlight](https://starlight.astro.build).

Design decisions, content strategy, and rationale are in
[ADR-038](../adr/038-yano-x-documentation-site.md).

## Quick start

```bash
cd www
npm ci
npm run dev      # http://localhost:4321
```

Requires Node 22 or newer. No Java, no Gradle, no Yano artifacts — the site
reads repository text files only.

| Command | What it does |
|---|---|
| `npm run import` | Regenerate imported pages, mirror Studio, render catalog blocks. |
| `npm run dev` | `import`, then serve with hot reload. |
| `npm run build` | `import`, build to `dist/`, then generate the AI artifacts. |
| `npm run preview` | Serve the built `dist/` locally. |
| `npm run check:links` | Verify every internal link and anchor in `dist/`. Runs as part of `build`. |

## Three kinds of content

| Kind | Location | Committed? | How it stays correct |
|---|---|---|---|
| **Authored** | `src/content/docs/**` | Yes | Reviewed like code. |
| **Imported** | `src/content/docs/{tutorials,state-machines}/` | No (git-ignored) | Regenerated from `../docs/` on every build. |
| **Generated** | `<!-- catalog:… -->` blocks, `dist/llms*.txt`, `dist/ai/*` | Blocks yes, artifacts no | Derived from the repository's JSON catalogs and `gradle.properties`. |

`../docs/` remains the source of truth for imported pages. Edit the repository
markdown, not the copy under `src/content/docs/`.

**Never hand-edit content between `<!-- catalog:name-start -->` and
`<!-- catalog:name-end -->`** — it is overwritten on the next import.

## Scripts

| File | Responsibility |
|---|---|
| `scripts/repo-sources.mjs` | Locates the repository root; loads `gradle.properties` and the JSON catalogs. Declares which repo docs are imported and where they land. |
| `scripts/import-repo-docs.mjs` | Imports repo markdown, rewrites links, mirrors the App-Chain Studio, and renders catalog blocks. |
| `scripts/generate-catalog.mjs` | Builds `/ai/catalog.json` and every generated markdown table. |
| `scripts/generate-llms-txt.mjs` | Builds `/llms.txt`, `/llms-full.txt`, and the raw `/ai/*.md` copies. |
| `scripts/llms-integration.mjs` | Astro integration: writes the artifacts at build time, serves them in dev. |
| `scripts/remark-mermaid.mjs` | Converts ` ```mermaid ` fences into client-rendered diagrams. |
| `scripts/check-links.mjs` | Post-build gate: every internal link and anchor in `dist/` must resolve. |

### Where the generated data comes from

| Site output | Source of truth |
|---|---|
| Versions | `../gradle.properties` |
| Recipes | `../tooling/devtools/.../appchain-recipe-catalog.json` |
| Capabilities, runtime artifacts | `../tooling/devtools/.../appchain-capability-catalog.json` |
| Configuration properties | `../tooling/devtools/.../appchain-first-party-metadata.json` |
| Distributions | `../tooling/devtools/.../appchain-release-capability-index.json` |
| Gradle modules | `../config/artifacts-v1.json` |
| App-Chain Studio | `../tooling/studio/src/main/web/` plus catalog assets |

No version string, recipe id, capability id, or module name is typed by hand
into a page.

## Link rewriting

Imported documents link into `ledgers/`, `scripts/`, `adr/`, `tooling/`, and
`config/` with repo-relative paths. The importer resolves each link against its
source file's directory to a repository-root-relative path, then maps it to:

1. a site route, if that document is published here;
2. `/studio/...`, for the mirrored App-Chain Studio; or
3. a GitHub `blob/` or `tree/` URL.

**An unresolvable link fails the build.** That is deliberate — a broken link
should not ship.

After the build, `scripts/check-links.mjs` re-checks the *rendered* output: every
site-relative `href` and `src` must resolve to a real file or route, and every
`#anchor` into a generated page must exist. Hashes that are application state
rather than an element anchor — the App-Chain Studio encodes a whole blueprint
as `#recipe=…&members=3&…` — are skipped, so a report of `0 anchor(s)` checked
is normal today.

## Adding a page

1. Create `src/content/docs/<section>/<page>.md` with `title` and `description`
   frontmatter.
2. Add it to the `sidebar` in `astro.config.mjs`.
3. Add it to `SECTIONS` in `scripts/generate-llms-txt.mjs`, so it is curated
   rather than dumped into "Other". The build warns if you forget; the deploy
   workflow sets `YANO_X_REQUIRE_LISTED_DOCS=1`, which makes that warning an
   error.

To import another repository document instead, add it to `IMPORTED_DOCS` in
`scripts/repo-sources.mjs` — that is a **one-place** edit. The importer derives
its output path from the route, and `generate-llms-txt.mjs` derives its
`Tutorials` and `State machines` sections from the same map.

## Diagrams

Use a fenced ` ```mermaid ` block. It is converted to `<pre class="mermaid">` at
the remark stage and rendered client-side, with the mermaid bundle loaded
lazily only on pages that have a diagram, and re-rendered on a theme switch.

## Deployment

`.github/workflows/docs-deploy.yml` publishes `www/dist` to GitHub Pages
with the `yanox.dev` CNAME. It triggers on a `dv*` tag, or manually via
`workflow_dispatch`:

```bash
git tag dv1 && git push origin dv1
```

Documentation ships independently of code releases. The site describes the
current checkout; release archives retain their own versioned manifests.

## Guided landing and canonical introductory pages

The landing page uses the Yano visual direction with ink surfaces, mint accents,
an interactive command walkthrough, and three outcome selectors. Controls are
native buttons; without JavaScript all outcome examples remain readable.
Reduced-motion preferences disable entrance animation and make replay advance
one step at a time. The illustration contains example data, not live node status.

The learning path and revised build, namespace, module, and scaffold guides are
owned by `../docs/site/` and imported into stable routes. Edit those source
files, not their generated site copies. Catalog blocks in `docs/site/` are
regenerated before import, using the same catalog renderer as other pages.

## Browser validation

The Mermaid source lint is a quick pattern check, not a parser or a rendering
test. Before publishing, validate the built site with Chromium:

```bash
npm run build
npx playwright install chromium
npm run check:browser
```

`tests/docs.spec.mjs` discovers every built page containing Mermaid, exercises
actual parsing and SVG rendering in light and dark themes, and changes themes
rapidly to catch asynchronous render races. It also checks the landing-page
controls and documentation entry on mobile. The GitHub Pages workflow installs
Chromium and runs this gate before deployment.

The renderer preserves the original diagram text, serializes rendering, and
passes the text directly to Mermaid's render API. It does not feed generated
SVG or HTML-decoded source back into the parser. If rendering fails, the source
remains readable and the browser check fails.

## Brand and illustration examples

`public/logo.svg` adapts the Yano folded mark with a mint palette;
`public/favicon.svg` places it on a dark tile. The shared
`src/components/shared/Brand.astro` wordmark is used on the landing page and
through Starlight's `SiteTitle` override.

`HeroPipeline.astro` owns the four illustrated examples and their stage copy.
Keep them explicitly illustrative, preserve the difference between role
approval, member finality, and source evidence, and link each example to a
guide. Browser checks cover tab navigation, mobile layout, reduced motion,
and matching branding on documentation pages.
