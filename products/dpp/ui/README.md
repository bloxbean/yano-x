# Yano X DPP Starter console

Browser console for the Digital Product Passport starter (ADR-051), a configuration-only
prototype on the governed authenticated map. The **Passport** view reads a public portal
(`yano-dpp serve`): identity and status, the current document, public and committed claims, the
ledger-ordered timeline, certificates, and one proof row per record; it checks in the browser
that every proof names one chain, genesis, height, root, and block, recomputes a disclosed
claim's commitment, and exports the `dpp-passport-v1` bundle that `yano-dpp verify` checks
offline. The **Operator** view drives an operator gateway (`yano-dpp gateway`) that signs on the
operator's machine: register, publish a version, set status, attach claims, append events, and
run the certification round. Built with SvelteKit, Svelte 5, Tailwind 4, and Vitest; published as
a static site with a runtime configuration file, following ADR-040 and the Trust Registry
scaffold.

The user guide is `docs/appchain/DPP_STARTER.md`. This is a prototype: it is not the DPP product
of ADR-026 and claims no conformance.

## Local development

Java is not required for frontend-only iteration. Node 22 is the repository build identity.

```bash
npm install
npm test
npm run check
npm run dev
```

The portal field defaults to `http://localhost:8580` and the gateway field to
`http://localhost:8590`; `cp .env.example .env.local` to change the development defaults. The
gateway token is pasted at run time and kept in memory only.

## Runtime configuration

The built site reads `dpp-ui-config.json` next to `index.html`:

```json
{
  "schemaVersion": 1,
  "productId": "dpp",
  "serviceUrl": "https://passports.example.com",
  "gatewayUrl": "",
  "allowServiceOverride": true
}
```

`serviceUrl` is the portal every visitor reads; `gatewayUrl` is a convenience for operators and
should stay empty on a public deployment. Remote URLs must be HTTPS; HTTP is allowed on loopback.

## Build

```bash
npm run build      # build/site
npm run sbom       # build/dpp-ui.cdx.json
```

`./gradlew :products:dpp:ui:frontendBuild` runs check, test, and build under the pinned Node.
