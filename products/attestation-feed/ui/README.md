# Yano X Attestation Feed console

Browser console for the data attestation feed starter (ADR-052), an experimental,
configuration-only observation ledger on the governed authenticated map. The **Round** view reads
a public portal (`yano-feed serve`): the feed policy, one row per configured source with its
observation and disposition, the round recomputed in the browser with the same integer rules as
the JVM beside the consortium's record, the candidate datum, and one proof row per answer; it
checks in the browser that every proof names one chain and genesis and sits at the declared
heights, and exports the `feed-round-v1` bundle that `yano-feed verify` checks offline. The
**Source** view submits a signed observation through a signing gateway (`yano-feed gateway`);
the **Operator** view defines feeds and runs the round-close approval round (propose, approve
after recomputation, apply). Built with SvelteKit, Svelte 5, Tailwind 4, and Vitest; published as
a static site with a runtime configuration file, following ADR-040 and the Trust Registry
scaffold.

The user guide is `docs/appchain/ATTESTATION_FEED.md`. This is a starter: it is not the oracle
pipeline of ADR app-layer/012, aggregates are recomputed by verifiers, and nothing is published
to Cardano.

## Local development

Java is not required for frontend-only iteration. Node 22 is the repository build identity.

```bash
npm install
npm test
npm run check
npm run dev
```

The portal field defaults to `http://localhost:8680` and the gateway field to
`http://localhost:8690`; `cp .env.example .env.local` to change the development defaults. The
gateway token is pasted at run time and kept in memory only.

## Runtime configuration

The built site reads `feed-ui-config.json` next to `index.html`:

```json
{
  "schemaVersion": 1,
  "productId": "attestation-feed",
  "serviceUrl": "https://feeds.example.com",
  "gatewayUrl": "",
  "allowServiceOverride": true
}
```

`serviceUrl` is the portal every visitor reads; `gatewayUrl` is a convenience for sources and
operators and should stay empty on a public deployment. Remote URLs must be HTTPS; HTTP is
allowed on loopback.

## Build

```bash
npm run build      # build/site
npm run sbom       # build/feed-ui.cdx.json
```

`./gradlew :products:attestation-feed:ui:frontendBuild` runs check, test, and build under the
pinned Node.
