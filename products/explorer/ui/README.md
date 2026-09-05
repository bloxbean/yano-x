# Yano X Verifiable Explorer console

Browser console for the Verifiable Explorer (ADR-050): browse the blocks, messages, and typed
subjects of a chain through a `yano-explorer serve` index; open an entity's trail with its head
check and content availability; search; and verify any row in the browser (envelope copy, message
id, sender signature, inclusion path, block record) before downloading its bundle for
`yano-explorer verify`. Built with SvelteKit, Svelte 5, Tailwind 4, and Vitest; published as a
static site with a runtime configuration file, following ADR-040 and the Attest scaffold.

The user guide is `docs/appchain/EXPLORER.md`.

## Local development

Java is not required for frontend-only iteration. Node 22 is the repository build identity.

```bash
npm install
npm test
npm run check
npm run dev
```

The connection form defaults to `http://localhost:8490`. Override that development default when
needed:

```bash
cp .env.example .env.local
```

## Golden fixtures

`src/lib/fixtures/golden-*.json` are written by the in-process cluster test in
`products/explorer/core` (`ExplorerClusterTest`). Regenerate them after a contract change with:

```bash
./gradlew :products:explorer:core:test -PexplorerGoldenWrite=true
```

## Runtime configuration

`static/explorer-ui-config.json` is copied next to `index.html`. It may pin `serviceUrl`, a list
of named services, and a default chain, and may forbid manual service URLs.

## Build

```bash
npm run build      # build/site
npm run sbom       # build/explorer-ui.cdx.json
```

Or `./gradlew :products:explorer:ui:frontendBuild` from the repository root.
