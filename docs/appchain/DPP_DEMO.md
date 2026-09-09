# Demonstrating the DPP Starter end to end

This guide runs the Digital Product Passport starter as a demonstration: who the actors are, where
their roles come from, how their keys are held, and how a product moves through its lifecycle in
the browser. The console guides the lifecycle itself, so this document is for the person setting
the demo up and for anyone who wants to know what the demo is actually proving.

The DPP starter is a **prototype**, not the DPP product of ADR-026. It shows infrastructure:
governed writes, proofs, and an approval round. It claims no conformance to any DPP regulation.
See [ADR-051](../../adr/051-digital-product-passport-starter.md) and the
[product guide](DPP_STARTER.md).

## What the demonstration proves

| The demo shows | The chain proves | It does not prove |
|---|---|---|
| A manufacturer registering a product and publishing a passport version | Which governed actor wrote each record, under which policy and key, at which height | That the product exists or that the document is true |
| A logistics operator appending shipping events | The events in ledger order, each bound to its writer | That anything physically happened |
| A lab issuing a claim, in the clear or as a commitment | The claim's bytes or its salted commitment, and who issued it | That the claim is accurate |
| A certifier and two auditors certifying the product | That the certificate was applied through the approval route with its proposal consumed once | That the certifier is accredited |
| Anyone verifying the passport offline | Every record under one root, certified by the chain's members | Anything about the world outside the chain |

## The cast, and where it comes from

Roles are not application configuration. They are declared in the chain's **genesis** and enforced
by the chain on every write. The demo consortium is defined in `DppGenesis.demo(chainId)` in
`products/dpp/profile`, which the launcher turns into the genesis the nodes start with. Changing
any of it changes the genesis id, which means a different chain.

| Actor | Organization | Roles | What it may write |
|---|---|---|---|
| `maker-a` | `acme-manufacturing` | `manufacturer`, `operator` | products, versions, status, events |
| `logistics-a` | `swift-logistics` | `operator` | events |
| `issuer-a` | `green-labs` | `claim-issuer` | claims |
| `certifier-a` | `cert-body-a` | `certifier` | proposes certifications |
| `auditor-a` | `cert-body-a` | `auditor` | approves certifications |
| `auditor-b` | `audit-guild-b` | `auditor` | approves certifications |
| `dpp-admin-a` | `dpp-consortium` | `dpp-admin` | administrative records |

Two facts matter for the demo. `maker-a` holds two roles, so it can both register a product and
append events. `auditor-a` and `certifier-a` are in the **same** organization, which is why a
certification needs `auditor-b` as well: the approval policy requires two approvals from distinct
organizations, and the chain refuses the apply until it has them.

Each collection is bound to a policy in the same genesis: products and versions to
`manufacturer-write`, claims to `claim-issuer-write`, events to `operator-write`, and certificates
to the approval-gated `certification` policy. A write signed by an actor without the policy's role
is rejected by the chain with an error code, not by the console.

## How keys are held

Every write carries a one-use authorization signed with the actor's Ed25519 key, valid for a
bounded number of blocks and scoped to the chain and its genesis. The key has to live somewhere,
and the demo makes that choice visible:

| Where the key is | How the demo uses it | What a proof then means |
|---|---|---|
| A seed file on your machine | `yano-dpp` reads it for CLI writes | the holder of that file signed |
| The operator gateway | `dpp.sh gateway` loads every demo seed and signs for the console | the gateway signed as that actor |
| The browser tab | not yet for DPP; the trust registry console does this today | that actor's key signed |

The demo seeds are derived from a public string, `sha256("yano-dpp-starter-demo-actor:" + actorId)`,
and the launcher writes them to owner-only files under the instance's `seeds` directory. They are
demonstration material and must never be reused anywhere else. The gateway prints a random bearer
token once at startup and keeps it in memory; the console holds it in memory too, never in storage.

The important honesty point for an audience: while the demo runs through the gateway, a proof that
names `maker-a` proves the gateway signed as `maker-a`. Anyone with the gateway's token could have
asked for it. That is acceptable for a local demonstration and it is the reason the production
guide replaces the shared gateway with per-organization signing.

## Setting the demo up

```bash
./gradlew :examples:showcase:installDist :products:dpp:cli:installDist :products:dpp:ui:frontendBuild
export DPP_YANO_HOME=$PWD/examples/showcase/build/install/yano-showcase/yano
products/dpp/harness/dpp.sh up            # three members on 7470..7472
products/dpp/harness/dpp.sh portal        # public portal on 8580
products/dpp/harness/dpp.sh gateway       # operator gateway on 8590, prints the token
products/dpp/harness/dpp.sh seeds         # the demo seed files, if you want the CLI too
```

Serve the console from `products/dpp/ui/build/site` on any static host and set `serviceUrl` and
`gatewayUrl` in `dpp-ui-config.json` next to `index.html` to the portal and gateway. The portal and
gateway allow any origin, so the console needs no proxy for them.

`dpp.sh demo` runs the whole journey non-interactively if you want data on the chain before an
audience arrives. `dpp.sh status`, `stop`, and `clean` complete the launcher.

## The walkthrough, in the browser

Open the console, go to **Operator**, enter the gateway URL and its token, and press Connect. Enter
a product id such as `gtin:09506000134352` and press **Read progress**. The console reads that
product's public passport from the portal and shows the lifecycle as steps, each marked `DONE`,
`READY`, `NEEDS <role>`, or `BLOCKED`, with the role it needs and a *Sign as* shortcut. One step is
marked as the one to do next. You do not need this document open while demonstrating: the steps
carry the same information.

1. **Register the product**, signing as `maker-a`. The passport appears at status `DRAFT`.
2. **Publish a version.** Choose any small JSON file. It is hashed in the browser and only the
   digest, media type, and reference are sent. The version becomes current in the same command,
   with a compare-and-set on the product's revision so a concurrent rewrite is refused.
3. **Attach a claim**, signing as `issuer-a`. Leave it public to put the text on chain, or tick
   *Committed* to put only a salted commitment there. A committed claim returns a disclosure
   document **once**: keep it, because the console cannot show it again, and hand it to verifiers
   out of band. The Passport view's *Check a disclosure* box recomputes the commitment in the
   browser.
4. **Append events**, signing as `logistics-a`. Try `SHIPPED` then `RECEIVED`. The trail is in
   ledger order, not in the order of the timestamps you type.
5. **Run a certification round.** This is the part worth slowing down for.
   - Sign as `certifier-a`, choose an evidence document, and press **1. Propose**. A request
     document appears in the box. That document is what travels between the parties.
   - Sign as `auditor-a` and press **2. Approve**. The round shows one organization approved and
     asks for one more from a different one.
   - Press **3. Apply** now, to show that it is refused: the policy has not been met.
   - Sign as `auditor-b`, approve, then apply. The certificate is written with its proposal's
     consumption proven.
6. **Change status or revoke** as `maker-a`, to show that revocation is terminal.

To show that the guide is not the authority, pick a step whose role the current signer lacks and
run it anyway. The console lets you: the write is signed, submitted, and refused by the chain with
its own error code.

## Showing the proof

Switch to the **Passport** view and open the same product. Every record carries its proof row, and
the browser checks that all of them name one chain, genesis, height, root, and block, and that
every key belongs to the product. Press **Download passport bundle**, then verify it away from the
console:

```bash
yano-dpp verify --bundle passport.json --members <instance>/members.json
```

Expect exit 5, `CALLER_PINNED_ROOT`. Without `--members` it is exit 6, consistent only. The exit
codes are the honest summary: only 0 and 5 mean verified.

The Passport view also shows flags a configuration-only starter cannot prevent but the ledger
exposes, such as `REWRITTEN` and `FOREIGN_WRITER`. Showing one of those deliberately, by rewriting
a product as a second manufacturer, is a good way to explain what the full provider of ADR-026
would enforce on chain.

## What is demo-only

- Seeds derived from a public string, and one gateway holding all of them.
- The launcher's local API key, and services bound to loopback with no TLS.
- A single operator acting as every organization. In a real consortium each organization runs its
  own signer and holds its own key.
- Anything about conformance. The starter demonstrates infrastructure only.

For what changes when this stops being a demo, see [Production deployment](PRODUCTION_DEPLOYMENT.md).

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| The gateway refuses with 401 | The token is regenerated on every start; read it from the instance's `gateway-token` file |
| A write is `REJECTED` with an error code | The chain refused it: usually the signer's role does not match the collection's policy |
| Apply is refused after two approvals | Both approvals came from the same organization; the policy needs two distinct ones |
| The console cannot reach the portal | The portal must be served over the same scheme as the console; loopback HTTP works |
| A committed claim's disclosure is gone | It is returned once by design; re-issue the claim to get a new one |
