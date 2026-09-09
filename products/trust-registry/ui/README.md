# Yano X Trust Registry console

Browser console for the Trust and Status Registry (ADR-049): look up subjects, credential status,
status lists, and issuers on a governed authenticated-map chain; see the entry, who wrote it, and
whether the proofs bind to the certified block; check a served Bitstring Status List against the
chain's list entry; export an answer that `yano-trust verify` checks offline. Built with SvelteKit,
Svelte 5, Tailwind 4, and Vitest; published as a static site with a runtime configuration file,
following ADR-040 and the Attest and Evidence Desk scaffold.

The user guide is `docs/appchain/TRUST_REGISTRY.md`.

## Local development

Java is not required for frontend-only iteration. Node 22 is the repository build identity.

```bash
npm install
npm test
npm run check
npm run dev
```

The connection form defaults to `http://localhost:7070` and `/api/v1`. Override those development
defaults when needed:

```bash
cp .env.example .env.local
```

`VITE_YANO_API_KEY` is a development convenience only: Vite exposes it to browser code, so never
put a production key there.

## Golden fixtures

`src/lib/fixtures/golden-*.json` are written by the in-process cluster test in
`products/trust-registry/client` (`TrustRegistryClusterTest`). Regenerate them after a contract
change with:

```bash
./gradlew :products:trust-registry:client:test -PtrustGoldenWrite=true
```

## Runtime configuration

`static/trust-registry-ui-config.json` is copied next to `index.html`. It may pin endpoints, a
default chain, an expected network, and `serviceUrl`, the base URL of a `yano-trust serve`
instance whose status lists the console checks.

## Build

```bash
npm run build      # build/site
npm run sbom       # build/trust-registry-ui.cdx.json
```

Or `./gradlew :products:trust-registry:ui:frontendBuild` from the repository root.
