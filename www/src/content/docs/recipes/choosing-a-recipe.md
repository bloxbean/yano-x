---
title: Choosing a recipe
description: A decision path from business outcome to a concrete recipe, state machine, and extension level — including when configuration is not enough.
sidebar:
  order: 2
---

Start from the outcome you need, not from the technology. Almost every
application maps onto a stock model; writing code should be the last resort,
not the first move.

## Start from the outcome

| I want to… | Start with | Coding required? |
|---|---|---|
| See three members finalize the same events | [Your first app chain](/tutorials/01-first-app-chain/) | No |
| Keep an append-only log of opaque application records | `audit-log` recipe (`ordered-log`) | No |
| Maintain a provable, owner-controlled registry | `owned-registry` recipe ([`kv-registry`](/state-machines/kv-registry/)) | No |
| Maintain several proof-oriented collections, optionally with value validation | `authenticated-map` recipe ([guide](/state-machines/authenticated-map/)) | Configuration; a plugin only for custom rules |
| Collect member approvals and optionally trigger an action | `approval-workflow` recipe ([`approvals`](/state-machines/approvals/)) | Configuration plus typed commands |
| Track balances with a non-negative account ledger | [`balances`](/state-machines/balances/) | Configuration plus typed commands |
| Maintain a document-hash trail per product or case | [`doc-trail`](/state-machines/doc-trail/) | Configuration plus typed commands |
| Approve payload hashes using application-defined roles | `role-approval` recipe ([`role-approvals`](/state-machines/role-approvals/)) | Configuration plus actor integration |
| Require manufacturers, auditors, and regulators to sign by role | [Domain-role approvals](/tutorials/05-domain-role-approvals/) | No, for the stock scenario |
| Publish immutable evidence to object storage or IPFS and notify Kafka | `evidence-ledger` recipe ([Evidence](/products/evidence/)) | No for the demo; connector plugins in deployments |
| Call an ERP or API after a finalized decision | [Webhook effects](/tutorials/06-webhook-effects/) | Configuration; emission is stock or plugin logic |
| Query and prove historical Cardano parameters, stake, and governance | [Cardano History](/products/cardano-history/) | Plugin configuration and CLI |
| Run a UTxO-style ledger or explore ZK settlement | [eUTxO and ZK](/products/eutxo-and-zk/) | Experimental; Cardano builder integration |
| Implement business rules Yano does not ship | [The plugin framework](/plugins/) | A small Java plugin |

If you are unsure, run tutorials 1, 2, 4, and 5 in that order. They show the
progression from a replicated log to proofs, external actions, and business-role
authorization.

## Then pick the extension level

```text
Does a stock machine or profile already model the outcome?
  ├─ yes → configuration only
  └─ no
      Are all required components already available?
        ├─ yes → a small composite plugin
        └─ no  → a custom state-machine plugin
```

Read [the extension ladder](/plugins/) before deciding you need the third rung.
Most teams that think they need a custom state machine actually need a
composite, and many that think they need a composite need only configuration.

## Questions that change the answer

**Who authorizes an action — a node or a person?**
If the approver is a validator member, `approvals` is enough. If the approver is
a business actor at an organization (an auditor, a regulator, a QA manager)
whose key is not a node key, you need `role-approvals` or the `role-evidence`
profile. Those separate the identity that *transports* a command from the
identity that *authorizes* its business meaning.

**Do you need to prove absence, or only presence?**
Presence is straightforward. Absence requires dataset completeness — a marker, a
paired subject, or an authenticated snapshot descriptor. Design for it up front;
see [State and proofs](/concepts/state-and-proofs/).

**Does an external system have to act on a decision?**
Then you need [effects](/concepts/effects/), and you must decide the finality
gate (`app-final` vs `l1-anchored`), the expiry, and who may attest to results.

**Do several capabilities need one atomic state root?**
That is exactly what a composite profile is for. If they can live on separate
chains, use separate chains — a node can host several.

**Is the data volume large and immutable per period?**
Look at
[authenticated snapshots](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/AUTHENTICATED_SNAPSHOTS.md)
rather than growing primary authenticated state indefinitely.

**Will the semantics change after launch?**
Choose governed composite profiles from the start. Retrofitting governance onto
a fixed-profile chain means a new chain.

## What "no code" honestly means

No-code means the required state machine, composite, connector, and launcher
already ship with Yano X. A real application still:

- sends typed commands;
- owns its UI, identity onboarding, key custody, and business data; and
- decides its retention, authorization, and operational policy.

Configuration cannot invent arbitrary consensus transitions. New combinations of
existing components use a small composite plugin; genuinely new domain logic
uses a custom state-machine plugin.

## Before you commit

```bash
./yano.sh appchain recipes                 # what this build offers
./yano.sh appchain capabilities            # support tier, scope, selection
./yano.sh appchain capabilities --format json
```

The catalog is release-pinned, so the binary you built is the authority for the
version you will run. The [recipe catalog](/recipes/) and
[capability catalog](/reference/capabilities/) here are generated from the same
files.
