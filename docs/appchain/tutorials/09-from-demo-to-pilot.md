# Tutorial 9 — From Local Demo to a Permissioned Pilot

- **Goal:** turn a successful tutorial into a pilot that several
  organizations run together, and a written plan for operating it.
- **You'll learn:** how the operators agree on one chain without sharing
  private keys, where each key lives, and which decisions to record before
  traffic.
- **Before you start:** an extracted Yano X JVM distribution and Java 25.
  Step 1 runs on one machine and spends nothing. Tutorial 1 helps but is not
  required.
- **Level:** advanced; for platform, security, and application leads
- **Time:** 10 minutes for the rehearsal, then a planning session
- **Outcome:** a rehearsed setup, a pilot draft your operators can act on, and
  a plan that does not inherit local-demo assumptions.

This is a decision checklist rather than one launch command. Yano remains
pre-release. The target is a controlled permissioned pilot, not an unqualified
production or public-chain claim.

## 1. Rehearse the setup on one machine

A pilot is run by several organizations. Each runs one member node and keeps
that node's private key on its own machine. One coordinator defines the
application and collects public member cards. Rehearse the whole procedure
first:

```bash
./yano.sh appchain try
```

> **✓ You should see** seven numbered steps (create, request, seal, join,
> start, status, submit) and a message finalized at the same height and root
> on alice's, bob's, and carol's nodes.

`try` runs the same commands your operators will run, each printed before it
runs. Read them, then remove the rehearsal with `./yano.sh appchain try --clean`.

## 2. Draft the pilot

The coordinator lists what this release offers and creates the draft:

```bash
./yano.sh appchain recipes
./yano.sh appchain capabilities

./yano.sh appchain new permissioned-pilot --network preprod \
  --operators acme,globex,initech --chain pilot=audit-log --threshold all --yes
```

> **✓ You should see** `✓ Wrote ./permissioned-pilot.draft.yaml`, the invite
> code, `lead acme`, and `3 operators, 3 signatures per block`.

Open the draft and decide each value with the other organizations before
anyone makes a card. The draft can change until seal:

- **Operators and lead.** The first operator is the lead. With
  `sequencing: fixed` (the default) it is the block proposer and cannot be
  removed later, so the lead organization must back up its node directory.
- **Sequencing.** `rotating` (`new --sequencing rotating`) lets the members
  take turns proposing, so no chain depends on the lead's node, and the lead
  can later be removed like any other member.
- **Threshold.** `all` here means three of three: the strongest agreement, but
  one offline member stops the chain. `majority` (two of three) keeps going
  with one member down. See section 8 for dishonest members.
- **Membership.** `governed` (the default) lets the operators add and remove
  members later with `approve`. `static` fixes the set.
- **Chains.** One recipe per chain, or a composite of several state machines
  joined by [bindings](../bindings/README.md). A chain's state machines are
  fixed at genesis. A recipe that needs answers gets them with `new --answer
  <chain>.<name>=<value>` (the wizard asks otherwise); they are public.
- **Anchoring.** An optional `anchor: {mode: script, every-blocks: 10}` policy
  per chain. Who anchors is decided later, not in the draft.

Check what you wrote with:

```bash
./yano.sh appchain inspect permissioned-pilot.draft.yaml
```

The coordinator then sends the draft to each organization, or runs
`./yano.sh appchain invite permissioned-pilot.draft.yaml --serve --host <coordinator-host>`
and sends each organization its own join link instead; see
[join links](../deployment/configure.md#easier-hand-out-join-links).

## 3. Each organization makes its own key

Each organization, on the server that will run its node:

```bash
./yano.sh appchain request permissioned-pilot.draft.yaml --host node-a.pilot.example
```

> **✓ You should see** `✓ Created node directory …/yano-node with a new member
> key` and `✓ Wrote ./acme.member  (key id …, reachable at
> node-a.pilot.example:13337)`.

The member key stays in `yano-node/secrets/member.key`, readable only by its
owner. Only the card travels to the coordinator. Each card is signed by its
key over the draft's invite code.

## 4. Seal, and compare on a call

With every card beside the draft, the coordinator seals:

```bash
./yano.sh appchain seal permissioned-pilot.draft.yaml
```

> **✓ You should see** `✓ Sealed …/permissioned-pilot.chain`, each member with
> its key id, each chain with its genesis id, and the chain fingerprint.

`seal` fixes every chain's exact settings, the release versions, and the
plugin bundle digests. Hold a call: the coordinator reads out the
**chain fingerprint**, and each organization reads out its **key id** and
checks it is listed under its own name. A card proves a key, not who sent it,
so this call is the trust step. Record the fingerprint and the key ids where
the organizations and your verifiers can find them.

## 5. Join, start, and check

Each organization follows [Run your node on a server](../deployment/operators.md):
`join permissioned-pilot.chain` (it shows the summary and asks to confirm),
install the systemd unit that `join` writes, start, and run
`./yano.sh appchain status permissioned-pilot`.

> **✓ You should see** `[Live]`, `members online 3/3`, and `members agree` for
> every chain.

On preprod every node first syncs Cardano; the `Cardano:` line of `status`
shows how far behind it is until it reports `up to date`. Compare the
`Plugins:` catalog fingerprint across the organizations too. To anchor a chain
later, one organization runs `./yano.sh appchain anchor permissioned-pilot` and
funds the wallet it creates; that spends test ADA, so authorize it explicitly.

## 6. Record the application contract

The sealed file is the contract. Record and review alongside it:

- chain ids, network, and the chain fingerprint;
- member key ids, threshold, and the lead;
- state-machine or composite profile, and its bindings document;
- deterministic limits and any activation schedule;
- command, state, and effect contract versions;
- anchor mode, cadence, and which organization leads anchoring; and
- effect outcome trust policy.

Every consensus-affecting value is in the sealed genesis section, so the
fingerprint covers it. Node-local settings, such as ports, storage paths and
connector endpoints, must not decide application semantics. Each operator keeps
their own in `yano-node/config/local.yaml`, which `join` never rewrites and
`start` refuses if it touches a sealed setting.

## 7. Separate every identity and secret

| Material | Purpose | Where it lives |
|---|---|---|
| Member key | App-block votes and envelopes | `yano-node/secrets/member.key` on that member's server only |
| Node API key | Authorizes calls to that node's API | `yano-node/secrets/api.key`; issue gateway keys per caller in front of it |
| Business actor key | Domain authorization | The actor organization, or a delegated signing service; see `appchain role` |
| Anchor wallet key | Cardano fees for anchors | `yano-node/secrets/anchor.key`, only on the anchor leader; fund it with a capped amount |
| Connector credential | Kafka, S3, IPFS, or API access | The executor host only |
| TLS key and trust roots | Transport identity | Platform PKI |

Back up each node directory. Never copy the deterministic demo keys, launcher
API key, sample actor seeds, or local connector credentials into a shared
environment.

## 8. Choose the trust statement

Document what the system actually proves:

- threshold members finalized exact application state;
- actor signatures authorized exact statements under a governed policy;
- an MPF proof connects a record to a state root;
- an L1 anchor connects a certified descendant to Cardano; and
- an effect result is a member/executor attestation unless independently
  verified.

Do not claim that an inspection, oracle value, shipment, API response, or
payment outcome is independently true unless a separate auditor/source check
establishes it.

Consensus recovers from an offline leader through a certified view change,
but by default it assumes no dishonest members. Choose the threshold for the
faults you must survive; see Yano's
[consensus guide](https://github.com/bloxbean/yano/blob/main/docs/APP_CHAIN_CONSENSUS_GUIDE.md).

## 9. Replace demo infrastructure deliberately

| Demo component | Pilot decision |
|---|---|
| RustFS | AWS S3 or reviewed compatible service; versioning/retention policy |
| Single Kubo | Managed/redundant IPFS pinning and retrieval policy |
| Local Kafka | TLS/mTLS/SASL cluster, ACLs, consumer deduplication |
| Local webhook | Named allow-listed target, authentication, idempotent receiver |
| Devnet anchor wallet | Dedicated funded preview/preprod key with spend cap |
| Docker-local secrets | KMS/HSM/Vault or orchestrator secret mounts |

Changing an executor destination does not change deterministic effect intent,
but it does change operational identity, reconciliation, and credentials.

## 10. Establish operations before traffic

- Health and readiness for every member and plugin; `status` on a schedule.
- Root and identity parity across members (`members agree` in `status`).
- Metrics and alerts for app lag, finality, pool pressure, anchor lag, effect
  backlog/age/retries/parking, sink lag, and disk pressure.
- Backup and restore of each node directory, and a rehearsed member addition
  and removal with `approve` ([Grow and change](../deployment/add-chain.md)).
- Member-key and actor-key rotation and revocation exercises.
- Connector outage and executor crash recovery.
- Anchor-leader failure and recovery (`anchor --stop`, `anchor --take-over`).
- Evidence and proof archival before configured pruning horizons.
- An explicit maintenance and upgrade process (`join --accept-bundles`).

## 11. Run acceptance in layers

1. Clean deterministic unit and conformance suites.
2. The `try` rehearsal, then a three-organization devnet application.
3. Restart one member and prove catch-up and root parity.
4. Stop and restart every node from retained state.
5. Add and remove a member with `approve`.
6. Connector fault matrix and duplicate-boundary tests.
7. Load/soak test with recorded topology, rate, payloads, duration, lag,
   resources, and failures.
8. Preview/preprod anchor smoke with independent L1 verification.
9. Restore rehearsal from a node-directory backup.

Keep acceptance artifacts with the release rather than relying on screenshots
or a remembered manual session.

## 12. Know the current escalation gates

- Material Cardano funds require the production action hardening tracked for
  `cardano.payment` and native assets.
- Semi-trusted members/executors require governed result-signer policy and
  independent outcome auditing before receipts are marketed as independently
  verified.
- Regulated personal data requires encryption/erasure guidance; immutable
  object/IPFS/on-chain digests are not a deletion mechanism.
- Public validator participation is outside the current permissioned model.

## 13. Choose the deployment shape

- **JVM distribution:** supports plugin-directory installation and is the
  simplest extensible pilot shape. Every operator installs the same release.
- **Yano native image:** runs the core ordered-log capability only. Yano X
  plugins are JVM-only and cannot be installed into that image; choose the JVM
  distribution for a Yano X pilot.
- **Embedded library:** appropriate when an application team owns lifecycle,
  configuration, APIs, and dependency integration in one Java service.

## Troubleshooting

| You see | What it means and what to do |
|---|---|
| `Members 2/3: waiting for initech` from `seal` | A card is missing. Ask that organization to run `request` and send its card. |
| `This node's key (…) is not a member of permissioned-pilot` at `join` | The sealed file holds another key under your name. Do not start; tell the coordinator. |
| `This distribution's plugin bundles differ from the sealed ones: …` | Install the same Yano X release the coordinator sealed with. |
| `threshold 2 is not safe for 4 members; …` | A numeric threshold must be more than half of the members and at most all of them. |

## Go deeper

- [Set up an application](../deployment/configure.md) and
  [grow and change it](../deployment/add-chain.md)
- [From demonstration to production](../PRODUCTION_DEPLOYMENT.md)
- [Full user/operations guide](../../APP_CHAIN_USER_GUIDE.md)
- [Profile-governance runbook](../../APP_CHAIN_PROFILE_GOVERNANCE.md)
- [Canonical app-layer open items](../../../adr/app-layer/open_item.md)
- [Evidence flow and trust limits](../../EVIDENCE_CHAIN_DEMO.md)
- [Domain-role production signing and recovery](../../APP_CHAIN_DOMAIN_ROLES.md)

**Next:** plan a real deployment with [Deploy Yano X](../deployment/README.md).
