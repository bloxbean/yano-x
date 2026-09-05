# Yano X Attest UI

This is the standalone browser application for the Yano X Attest product (ADR-047). One built
artifact can be served beside a Yano node or from an independent static host and connected to any
compatible Yano installation at runtime. It lists only app chains that run the `doc-trail` state
machine, discovered from their live status.

The UI hashes documents in the browser, records the digest as a canonical doc-trail append,
assembles a portable certificate from the node's message proof, evidence bundle, and trail head,
and runs the local checks of a certificate (digest, signed envelope, message id, sender signature,
command binding, inclusion path). Threshold finality and Cardano anchor verification stay with the
`yano-attest` CLI. See `docs/appchain/ATTEST.md` for the full user guide.

## Local development

Java is not required for frontend-only iteration. Node 22 is the repository build identity.

```bash
npm install
npm test
npm run check
npm run dev
```

`npm test` skips `src/lib/live.test.ts` unless `ATTEST_LIVE_NODE_URL` names a running doc-trail
node; with it set, the test runs the page's attest and verify sequence against that node.

Open the local address printed by Vite. The connection form defaults to `http://localhost:7070`
and `/api/v1`. Override those development defaults when needed:

```bash
VITE_YANO_NODE_URL=https://node.example.com \
VITE_YANO_API_PREFIX=/api/v1 \
npm run dev
```

`VITE_YANO_API_KEY` may prefill the key only while running the Vite development server. Like every
`VITE_*` variable, its value is visible to browser code; do not use it to embed a shared or
production secret. Production users enter a key for the current tab, or an authenticated reverse
proxy supplies credentials server-side.

HTTP node URLs are accepted only for loopback development; remote nodes must use HTTPS.

The node or its reverse proxy must allow the UI origin through CORS, including `GET`, `POST`,
`Content-Type`, and the optional `X-API-Key` request header. Same-origin deployment avoids CORS and
is recommended for a managed installation.

## Runtime configuration

Edit `attest-ui-config.json` after building or supply it while hosting the static assets:

```json
{
  "schemaVersion": 1,
  "productId": "attest",
  "endpoints": [
    {
      "id": "primary",
      "label": "Preprod documents",
      "nodeUrl": "https://node.example.com",
      "apiPrefix": "/api/v1"
    }
  ],
  "defaultChainId": "documents-chain",
  "expectedNetwork": "preprod",
  "allowEndpointOverride": true
}
```

`expectedNetwork` rejects a node that reports another Cardano network. `allowEndpointOverride`
set to `false` hides the manual connection form and keeps users on the configured endpoints.

## Fixtures

`src/lib/fixtures/golden-*` are produced by the client module's cluster test
(`./gradlew :products:attest:client:test -PattestGoldenWrite=true`) and let the browser tests run
against a certificate that a real three-node doc-trail cluster finalized.
`adr037-message-proof-v1.json` is the shared Yano message proof vector set.

## Build

```bash
npm run build      # build/site
npm run sbom       # build/attest-ui.cdx.json
```

From the repository root, `./gradlew :products:attest:ui:uiZip` runs check, test, build, and SBOM
generation and produces `build/distributions/yano-x-attest-ui-<version>.zip`.
