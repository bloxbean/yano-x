# Yano X EUTxO UI

This is the standalone browser application for a Yano X EUTxO product. One built artifact can be
served beside a Yano node or from an independent static host and connected to any compatible Yano
installation at runtime.

It discovers EUTxO chains from their live capability manifests. It does not hard-code a deployment
hostname or require a particular chain name.

## Local development

Java is not required for frontend-only iteration. Node 22 is the repository build identity.

```bash
npm install
npm test
npm run check
npm run dev
```

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

Edit `eutxo-ui-config.json` after building or supply it while hosting the static assets:

```json
{
  "schemaVersion": 1,
  "productId": "eutxo",
  "endpoints": [
    {
      "id": "primary",
      "label": "Preprod payments",
      "nodeUrl": "https://node.example.com",
      "apiPrefix": "/api/v1"
    }
  ],
  "defaultChainId": "",
  "expectedNetwork": "preprod",
  "allowEndpointOverride": true
}
```

- `endpoints` may be empty. The connection screen then accepts a node URL.
- `expectedNetwork` may be empty. When set, a node reporting another network is rejected.
- `defaultChainId` is only a selection hint. Capability and route discovery still decide whether a
  chain is eligible.
- `allowEndpointOverride` controls whether a user may enter another node explicitly.
- API keys are never stored in this file. A user-entered key remains in memory for the browser tab.

Query-string endpoint overrides are intentionally unsupported.

The JSON file is the runtime configuration mechanism for a built static artifact. Frontend
environment variables are resolved by Vite at build/start time and cannot read environment changes
made later on a static hosting VM. Deployment automation should therefore render
`eutxo-ui-config.json` with the selected node origin and API prefix.

## Production artifact

```bash
./gradlew :products:eutxo:ui:uiZip
```

The versioned ZIP is written below `build/distributions`. It contains the static application,
runtime configuration template, and a CycloneDX frontend dependency SBOM. The combined Yano X JVM
distribution also places the unpacked application below `product-ui/eutxo`.

## Product functions

The UI provides:

- node identity verification and capability-based EUTxO chain discovery;
- CIP-30 wallet discovery with Cardano network validation;
- reviewed L1 deposits, L2 transfers, and withdrawal claims;
- finalized transaction, account, UTxO, deposit, withdrawal, and lineage views;
- visible-tab polling every five seconds for live chain identity, activity, bridge, index, and anchor
  state;
- L1 transaction inspection;
- anchor commitment, validity batch, settlement, and message proof views; and
- explicit degraded states when an optional bridge, index, validity, or anchor surface is absent.

The browser never accepts a mnemonic, seed, or private key. Cardano transaction construction and
EUTxO state-transition rules remain on the Java side of the public Yano/plugin-domain API boundary.
