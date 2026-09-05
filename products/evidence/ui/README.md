# Yano X Evidence Desk

Browser workbench for role-gated evidence release (ADR-048): directory of organizations, actors,
and policies; proposals with clause progress; in-browser actor signing; the document-review
release; and proof-bound records with an export bundle. Built with SvelteKit, Svelte 5, Tailwind 4,
and Vitest; published as a static site with a runtime configuration file, following ADR-040 and
the Attest UI scaffold.

The user guide is `docs/appchain/EVIDENCE_DESK.md`.

## Local development

Java is not required for frontend-only iteration. Node 22 is the repository build identity.

```bash
npm install
npm test
npm run check
npm run dev
```

`npm test` skips `src/lib/live.test.ts` unless `EVIDENCE_DESK_LIVE_NODE_URL` names a running
chain with the role components; with it set, the test runs propose, approve, release, and the
negative cases against that node with the showcase demo seeds.

The connection form defaults to `http://localhost:7070` and `/api/v1`. Override those development
defaults when needed:

```bash
cp .env.example .env.local
```

`VITE_YANO_API_KEY` is a development convenience only: Vite exposes it to browser code, so never
put a production key there.

## Golden fixture

`src/lib/fixtures/golden-role-workflow.json` is written by the Java contracts in
`examples/showcase` (`EvidenceDeskGoldenTest`). Regenerate it after a contract change with:

```bash
./gradlew :examples:showcase:test --tests EvidenceDeskGoldenTest -PevidenceGoldenWrite=true
```

## Runtime configuration

The static site reads `evidence-ui-config.json` next to `index.html` at start-up:

```json
{
  "schemaVersion": 1,
  "productId": "evidence",
  "endpoints": [{ "id": "showcase", "nodeUrl": "http://127.0.0.1:7070", "apiPrefix": "/api/v1", "label": "Showcase" }],
  "defaultChainId": "document-review-chain",
  "expectedNetwork": "",
  "allowEndpointOverride": true,
  "directoryHints": {
    "document-review-chain": {
      "organizations": ["acme-manufacturing", "auditor-guild-a", "auditor-guild-b"],
      "actors": ["issuer-a", "auditor-a", "auditor-b", "registry-admin-a"],
      "policies": ["document-release"]
    }
  }
}
```

`directoryHints` seeds the directory with identifiers to look up before any command has been seen
on the chain; the desk also learns identifiers from finalized commands.

## Production artifact

```bash
./gradlew :products:evidence:ui:uiZip
```

The zip holds the static site and a CycloneDX SBOM. The JVM distribution places the site under
`product-ui/evidence`.
