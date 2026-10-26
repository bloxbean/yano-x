# ADR-056 — Simple app-chain setup: create, invite, join

- **Status:** Proposed (2026-10-26).
- **Amends:** ADR-055 Phases 2 and 3 (what replaces the project CLI and the Studio blueprint page).
- **Related:** ADR-047 (cohesive application deployment), app-layer ADR-DX-0001 (project CLI),
  app-layer ADR-031.2 (binding editor), ADR-040 (standalone product UIs), ADR-039 (VM deployment).

## 1. Context

A real app chain is run by several operators. Each operator holds a private signing key that must
never leave their machine. Someone has to collect everyone's public key, fix the chain's rules and
member set, and hand every operator the same genesis. Today's tools do not model that step:

- The project CLI and Studio assume **one author who knows everything**: every chain, every
  member's public key, every host and port (`spec.chains[].topology` in
  `appchain-blueprint.schema.json`). For remote members, keys are pasted into Studio by hand.
- `appchain prepare` creates member keys, but only for a local devnet where one person owns all of
  them.
- Unless a machine derives its own, a chain's genesis id is
  `sha256("yano-appchain-project-genesis-v1\0" + projectName + "\0" + chainId + "\0" + capabilities)`
  (`AppChainProjectResolver.materializeStateCommitmentIdentity`). Two unrelated projects that use
  the same names get the same genesis id, and the id does not cover the member set or the rules.
- The project lifecycle has 11 verbs (`init`, `render`, `doctor`, `prepare`, `plan`, `apply`,
  `diff`, `drift`, `gitops`, `chain add`, `migrate`), about 7.8k of devtools' 19.2k main lines
  (ADR-055 §3). ADR-055 already proposes removing it and Studio, but not what replaces them.

The runtime already has what a multi-operator chain needs: Ed25519 member keys (at most 32 members
in the v1 profile), an explicit `state.genesis-id` setting (`StateCommitmentIdentity`), and
governed membership (`membership.mode: governed`), where members are added or removed by
threshold-many identical member commands and a new member catches up from the chain. The single-host
launcher `scripts/appchain-cluster/cluster.sh` already automates the hard parts of growth (raising
the threshold, driving activation, refreshing peers) for one machine. Only multi-operator tooling
is missing.

## 2. Goals and non-goals

Goals:

1. One tool takes a beginner from nothing to a running multi-operator chain, and every step tells
   them what to do next.
2. Private keys are created where they are used and never travel.
3. Every operator can check, before starting, that they all have the same chain.
4. An expert can script every step.

Non-goals: no runtime, consensus, wire or storage change; no VM provisioning (ADR-039 stays);
no change to `yano.sh appchain cluster`, the existing single-host demo launcher.

## 3. The user experience

There are two roles. The **coordinator** defines the application. Each **operator** runs one
member node. The coordinator is usually also an operator. Files are named after the application
(`supply` below), which may hold several chains.

```text
coordinator$ yano.sh appchain new supply              # wizard → supply.draft.yaml, prints an invite code
             # send supply.draft.yaml to each operator (email, chat, …)

operator$    yano.sh appchain request supply.draft.yaml
             # asks for this node's public address; creates the key if needed → alice.member
             # send alice.member back; the coordinator drops it next to the draft

coordinator$ yano.sh appchain seal supply.draft.yaml  # uses the *.member files beside the draft
             # → supply.chain, prints the chain fingerprint
             # send supply.chain to each operator; read the fingerprint out on a call

operator$    yano.sh appchain join supply.chain     # shows the summary and fingerprint, asks to confirm
operator$    yano.sh appchain start
anyone$      yano.sh appchain status supply
```

Every command ends with a `Next:` line, and `status` shows where the application is:

```text
supply  chain fingerprint 3f9a-77c1-0b2e-d4a8-91c0-5e2b-aa17-04d3
  Draft ─ Members 3/3 ─ Sealed ─ Starting ─ Live
                                    ▲ 2 of 3 members online; waiting for carol (node-c.example:13337)
Next: ask carol to run  yano.sh appchain start
```

Before a chain is sealed, `status supply.draft.yaml` counts the member cards beside the draft against
the draft's `operators` list (`Members 2/3, waiting for carol`). On a public Cardano network `status`
also shows `L1 syncing 42%` while a node catches up with Cardano.

**Practice first.** `yano.sh appchain try` runs the same steps on one machine with three simulated
operators in separate directories under `./appchain-try/`. It chooses free ports, prints each real
command as it runs it, submits a test message and shows it finalized. `try --clean` stops and
removes the rehearsal.

**Adding or removing a member later.** A new operator runs the same `request` against the `.chain`
file and sends the card to the existing members. Each existing operator runs
`yano.sh appchain approve dave.member` once; `approve --remove carol` removes a member. The command
does the whole quorum-safe procedure on every chain (D5), including one restart of the operator's
own node to add or drop the peer. Then `dave` runs `join` and `start` and catches up.

**Lost key.** The other operators first `approve --remove carol`, then carol runs `request` again
with a fresh key and they `approve` the new card. Removing first keeps the chain live: adding first
would raise the threshold to a level the lost key can never help reach. The lead's key is the
exception: the runtime refuses to remove the fixed proposer, so losing it means sealing a new
application. The `new` wizard says this, and `status` reminds operators to back up the key.

**Anchoring to Cardano (optional).** A chain goes live without anchoring. Once it is live, `status`
shows `Anchoring: not set up`. Any one operator runs `yano.sh appchain anchor supply` and follows the
prompts (D10).

**Hosting another application on the same node.** The operator runs `join` with a second `.chain`
file. Adding a chain to a running node still needs a stop and start (a current host limitation), and
`join` says so.

## 4. Decisions

### D1 — Three files, one per step

| File | Written by | Contains | Secret? |
|---|---|---|---|
| `<app>.draft.yaml` | Coordinator (`new`, then edited freely until seal) | Invite code, expected operators, network, chains and their rules | No |
| `<operator>.member` | Operator (`request`) | Invite code, name, Ed25519 public key, advertised node and HTTP addresses, and a signature by that key over all of those fields | No |
| `<app>.chain` | Coordinator (`seal`) | A **genesis** section (everything consensus depends on) and a **peers** section (addresses) | No |

- The draft is YAML, because people read and edit it. `new` writes a random **invite code** into
  it. Member cards sign the invite code, not the draft's content, so the coordinator can fix the
  draft until seal without asking for new cards.
- A member card means "I hold this key and want to join ceremony X". It is not consent to the final
  rules: an operator consents at `join`, which shows the sealed summary and fingerprint.
- The private key stays in the operator's node directory, owner-readable only.
- The draft lists the expected `operators`. `seal` refuses a missing, unexpected, duplicate or
  wrongly-signed card, and refuses a key used twice.
- A card proves its key, not who its sender is. Each member is therefore shown with a short **key id**
  (the first 16 hex characters of the key) at `request`, `seal` and `join`, and the call that compares
  the chain fingerprint also compares each operator's key id. A **founding** `join` refuses unless the
  operator's own key is in the roster under its name, so a substituted card is caught by the operator
  it replaced. A **later** member is not in the sealed roster: its `join` is accepted only on the node
  directory that made a card for that `.chain` (`request <app>.chain`), keeps the original genesis,
  and `status` shows it as `waiting for approval` until governed history admits its key.
- The first operator listed is the **lead**: by default the fixed block proposer and, on a devnet,
  the node that produces the Cardano devnet (D11).

**What a draft holds.** A draft holds one or more chains (up to 32, as today), all sharing the
founding members. Each chain runs either **one state machine**, chosen by recipe, or **several state
machines working together**: a composite of up to 16 components joined by bindings (ADR-031.1),
which commit atomically in the same block. The `new` wizard asks, for each chain, "one state machine
or several?", offers the binding starters from `examples/bindings/`, and then asks "add another
chain?". Experts edit the YAML directly:

```yaml
name: supply
invite: 7c1e-52aa-9d03            # written by `new`; do not edit
network: preprod                  # devnet | preview | preprod | mainnet
operators: [alice, bob, carol]    # alice is the lead
threshold: 2                      # signatures per block; default: a majority
membership: governed
chains:
  - id: orders                       # one state machine
    recipe: audit-log
  - id: approvals                    # several state machines in one chain
    recipe: declarative-composite
    bindings: ./approval-to-audit.yaml   # components + bindings, or inline under `composite:`
    anchor: {mode: script, every-blocks: 10}   # optional policy; no wallet here
```

`seal` copies referenced bindings into the `.chain` file, so it is self-contained. Settings not
written in the draft take safe defaults, which `seal` shows: for example `l1.stability-depth` is 36
on public networks and 0 on devnet, because it cannot change after seal. A chain's state machines
are fixed at genesis: to add another later, seal a new application with the same operators, or use
governed composite profile evolution (app-layer ADR-015), which this ADR does not change.

### D2 — Seal fixes the resolved settings, and the fingerprint covers them

`seal` resolves every chain through the release's recipe and capability catalog **once** and writes
the exact consensus settings of each chain into the genesis section. `join` applies those settings
as written; it never re-resolves them, so operators with different tool builds cannot interpret the
same file differently. The genesis section also records the Yano and Yano X versions, and the SHA-256 digest
of every plugin bundle each chain runs on. `join` and every `start` check the node's bundles against
those digests and refuse a mismatch, naming the release to install; a different Yano or Yano X version
string with identical bundles only produces a warning. Deciding whether new bundles are safe for a
running chain stays a host-upgrade decision; the tool only makes the change explicit (below).

Three identities, each with its own name in the tool:

- **Chain fingerprint**: SHA-256 of the canonical JSON of the genesis section. The tool shows the
  first 128 bits in eight groups for reading aloud, and stores the full digest when an operator
  joins. The peers section is outside it, so an address can change without a new chain.
- **Genesis id** (`state.genesis-id`) of each chain: for state machines that derive their own (the
  authenticated map and machines built on it), the derived value, recomputed and compared by
  `join` and checked again by the node at startup. For every other chain,
  `SHA-256("yano-x-appchain-genesis-v1\0" || invite code || chainId || canonical settings without the
  genesis id)`. Either way the id now covers the members and the rules. For non-deriving machines it also
  covers the invite code, so two ceremonies never share one; a derived id does not cover the invite,
  so `request` always makes a fresh key unless `--key` is given, and `join` refuses a second chain
  with the same chain id and genesis id on one node. Chain ids must be unique on a node, and the
  node-wide settings of two applications on one node must agree; `join` refuses otherwise.
- **Accepted bundles**: `join` records the sealed bundle digests as this node's accepted set, and
  `start` checks the installed bundles against it. After a host upgrade, or for a later member on a
  newer release, `join --accept-bundles` shows which bundles changed and records the new set, in a
  logged step. `status` compares the plugin catalog fingerprint each member reports.
- **Effective runtime identity**: what a running node reports (`GET /api/v1/app-chain/chains`).
  `status` compares it across members, together with the Cardano network genesis id (on devnet it
  only exists after the lead's first start, so it is outside the fingerprint; see D11). The runtime
  does not report that id, so `status` hashes each node's stored genesis file and compares the
  results.

The runtime is unchanged: it already takes `state.genesis-id` as a setting and already validates a
derived one.

### D3 — One CLI with a few verbs

Phase A verbs: `new`, `request`, `seal`, `join`, `start`, `stop`, `status`, `approve`, `anchor`,
`inspect` and `try`. Each is interactive by default and takes flags for scripts (D13). Commands that
act on a node take `--node-dir` (default `./yano-node`); commands about one chain of a multi-chain
application take `--chain`. Errors say what to do, for example
`carol.member was made for a different invite (9b1e-…); ask carol to run request again`.

### D4 — The advertised address is fixed at `request`; everything else is local

`request` asks the operator for the address other members reach this node at (`host:port` for
node-to-node traffic, and the HTTP address used by `status`), with suggestions, and writes the ports
into the node directory. `join` listens on those ports. If a NAT or proxy means the listening port
differs, `join --listen <port>` keeps the advertised address and warns once.

`join` asks only for local choices that peers do not see (data directory, key file) and writes the
node configuration: the chain's sealed settings, peers from the peers section, a local API key for
the admin calls `approve` and `anchor` make, and storage under the node directory. Addresses can
change later without a new chain: `join --peer bob=host:port` overrides one peer, and `status`
names unreachable peers. On Linux, `join` also writes a systemd unit file and prints the command to
install it. Once the unit is installed and enabled, `start`, `stop` and the restarts that `approve`
and `anchor` make go through `systemctl`, so one node never has two managers.

### D5 — Membership changes are governed and quorum-safe

New applications default to `membership: governed`. `approve` performs, on every chain of the
application, the same procedure `cluster.sh` uses on one machine:

1. Read the chain's active members and threshold.
2. If the change would leave no safe quorum (for example 2-of-3 growing to four), first submit the
   threshold change and wait until it is active.
3. Submit the add or remove command.
4. Changes activate ten blocks after approval, and a chain only makes blocks when it has messages,
   so while waiting `approve` can submit small heartbeat messages where the state machine supports
   them. They are not invisible: on an ordered log each one is a permanent entry, so `approve` asks
   first (`--heartbeat` for scripts) and otherwise says to send ordinary traffic.
5. Add or drop the peer in the local node configuration and restart the node once.

Every existing operator runs `approve`, in any order. It waits while other operators catch up and
is safe to run again: it skips what is already done and reports what is still waiting
(`2 of 3 approvals for threshold 3; waiting for bob`). It refuses to raise the threshold unless
every member counted in the new threshold is online, so growth can never strand the chain.
Approvals expire after 600 blocks; `status` shows pending changes and how long they have left. The
`new` wizard explains the cost up front: while a 3-member chain grows, it runs at 3-of-3, so one
offline member stops it.

If fewer members than the threshold are online, the chain stops finalizing until enough return. That
is the safety the threshold buys, and no command can bypass it; `status` names who is offline.

`approve --remove` refuses to remove the fixed proposer (the runtime rejects it) and says what to do
instead. A coordinator may choose `membership: static`; `approve` then prints the static runbook
instead of acting.

### D6 — Phase B: join links

`yano.sh appchain invite supply.draft.yaml --serve` starts a temporary listener on the coordinator's
machine and prints one link per expected operator:

```text
yano.sh appchain request yano://203.0.113.10:7099/9QmV2x#7c1e-52aa-9d03
```

The operator's `request` downloads the draft, checks the invite code in the link, creates the key,
posts the member card and waits. The coordinator sees cards arrive live and runs `seal`. Each waiting
operator then receives the `.chain`, sees the summary and fingerprint, and confirms the `join`. On a
devnet the link also carries the lead's address, so the devnet genesis download (D11) needs no extra
step. The files and checks are those of Phase A, only carried automatically:

- No node and no runtime plugin is involved; the listener is part of the CLI and stops once every
  operator has received the `.chain`, or when the links expire.
- Links are single-use and expire (default 24 hours).
- A link is an invitation, not a credential that protects anything secret. Everything transferred is
  public and checked by invite code or signature. Someone who steals a link can at most post a card
  under an expected name; the real operator's `join` then refuses because its own key is missing, and
  the key ids compared on the call show the substitution. **The fingerprint and key-id comparison
  stays the trust step**; join links never skip it.

### D7 — Phase C: a local setup UI in Yano X

`yano.sh appchain ui` starts a localhost-only web UI over the same engine and opens the browser. It
has the same steps as the CLI: create (wizard), members, seal, join, and status, with a link to the
node's own console once live. The binding editor (ADR-031.2) becomes the optional "workflow" step of
create. The UI is built like `products/eutxo/ui` (ADR-040). It lives in Yano X, is served by the
CLI, and needs no node, plugin or Yano change. Yano's node console stays in Yano.

### D8 — Modules and packaging

- `tooling:appchain-formats`: the three file formats, the identities of D2, and signature checks.
  A small library with no node dependency.
- `tooling:appchain-cli`: the verbs above. It is installed at `tools/yano-appchain/` in the JVM ZIP,
  where Yano's `yano.sh appchain` already dispatches every verb except `cluster`. It uses the recipe
  and capability compiler from `tooling:devtools` and delegates the verbs devtools keeps (D9).
- A **self-contained jlink image per OS** is also published for coordinators who do not run a node:
  it holds the CLI, a trimmed Java 25 runtime and the stock plugin bundles needed to validate a draft.
  It is built from the same release as the ZIP, and `.chain` records that release. Operators who run
  a node still use the JVM ZIP and Java 25. It is not GraalVM native: Yano X is JVM-only
  (`verifyJvmOnlyBuild`), and validating a draft loads plugin bundles through
  `PluginProviderRegistry`, which needs a JVM.

### D9 — What this replaces

| Today | After Phase A |
|---|---|
| `init`, `prepare`, `render` | `new`, `request`, `seal`, `join` |
| `chain add`, `plan`, `apply` | `join` with another `.chain` file |
| `gitops` (cluster-wide Ansible, Helm, Kustomize) | Removed; each operator deploys their own node (systemd unit from `join`). ADR-039 is unchanged |
| `diff`, `migrate` | Removed |
| `doctor`, `drift` | Folded into `status` |
| `recipes`, `capabilities`, `bindings`, `state`, `role`, `plugin`, `metadata`, `config` | Kept |
| Studio blueprint page | Removed in Phase A |
| Studio binding editor | Kept until Phase C absorbs it |

For ADR-055: Phase 2's first need, deploying bindings without `init`, is met by the draft
referencing a bindings document; its acceptance gates move to `appchain try`. Phase 3's Studio
removal happens in two steps, as in the table. Nothing has been publicly released, so no aliases
are kept. Yano's `yano.sh` help text still names `init`, `render` and `doctor`; updating it is a
small follow-up in the Yano repository, outside this change.

### D10 — Anchoring is optional, after start, and one command

Anchoring never blocks `start`: a chain finalizes with or without it, and a stopped anchor is a
liveness problem only (user guide §5.1). Only the anchor leader needs a wallet; in `script` mode the
other members co-sign with no anchor configuration, though every member needs the Cardano view the
network provides.

- The draft may carry a chain's anchor **policy** (`mode`, `every-blocks`). Who leads is not sealed:
  it is a local role that can move.
- `yano.sh appchain anchor <app>` turns the operator's node into the leader in one guided flow:
  1. checks that no other member already leads. To move leadership, the old leader runs
     `anchor --stop` first; `--take-over` is only for a leader that is gone, and is allowed only on a
     node whose status already shows the chain's confirmed anchor identity (a node learns it only by
     co-signing anchors, so a late joiner cannot take over until it has);
  2. creates the anchor wallet key on that machine, separate from the member key;
  3. shows the wallet address, the expected cost per anchor and the current balance, and waits for
     funds; on devnet it asks the lead's node to fund the wallet;
  4. asks for confirmation (`--yes` for scripts), writes the anchor settings, including protocol
     parameter tracking, and restarts the node;
  5. if the chain already has an anchor thread on Cardano, adopts it; only if no member reports one
     does it run the one-time script bootstrap. It then waits until the first anchor is confirmed.
- The real spending limit is the wallet balance: the tool tells the operator to fund the wallet only
  with what they are willing to spend on fees. It does not promise a per-run ceiling, which would
  need a runtime change.
- The step is safe to re-run after the command is interrupted while the node keeps running: it
  resumes from what the node reports, and records a submitted bootstrap so a re-run waits for it
  instead of submitting another. If the node itself restarts between submitting the bootstrap and its
  confirmation on Cardano, the node forgets the pending bootstrap (it is held in memory). `anchor` then
  reports the submitted transaction and stops, asking the operator to check it on Cardano before
  deciding; durable recovery of that window needs host support and is outside this ADR.
- Every leader change, not only `--take-over`, is allowed only on a node whose status shows the
  chain's confirmed anchor identity once any member reports an anchor thread.

### D11 — Devnet: the lead produces the Cardano devnet

On `network: devnet`, the lead's node is the Cardano devnet block producer and every other node
follows it. The devnet genesis can only be created when the lead first starts (a genesis with an old
start time fails at node startup with `Effective protocol parameters are unavailable for epoch N`),
so it is not part of the `.chain` file. Instead:

1. The lead's first `start` creates the devnet genesis.
2. Every other node's first `start` downloads it from the lead's node
   (`GET /api/v1/devnet/genesis/download`), stores it, and refuses later starts if it changes.
3. Funding for anchoring on devnet goes through the lead's node, the only one with the devnet faucet.

The download is not authenticated beyond the lead's address, which is acceptable for a test network:
`status` compares the resulting Cardano genesis id across all members, so a substituted file shows up
as a mismatch. Addresses are never trusted for safety in any network: app-chain traffic is signed by
member keys, so a wrong address can only cost liveness. The lead's devnet node also exposes the
devnet's unauthenticated rollback and time controls on its HTTP address; operators keep a shared
devnet on a trusted network.

`status` shows `waiting for the lead (alice) to start` until the lead is up. `try` uses exactly this
mechanism on one machine. On public networks there is no producer: every node syncs Cardano itself,
and `status` shows the sync progress.

### D12 — Safe to repeat, safe to interrupt

- `seal` writes its file atomically and refuses to overwrite a different sealed file without
  `--force`.
- `join` is idempotent. Before the node first starts, joining a newer `.chain` with the same name
  replaces the old one after a clear prompt (`--replace` for scripts). Once the node has data for a
  chain, `join` refuses to change that chain's genesis or the node's key and says to seal a new
  application instead.
- `start` refuses to run while a `join` is half-written; re-running `join` finishes it.
- Nothing in this tool deletes chain data. `try --clean` deletes only its own rehearsal directory.

### D13 — Scriptable without skipping the trust step

- `join --yes` requires `--expect-fingerprint <fingerprint>`, so a script cannot skip the check.
- `status` and `inspect` accept `--json`. `status --wait live --timeout 10m` blocks until the
  application is live.
- `request --key <file>` uses an existing key.
- Exit codes: `0` success, `1` failure, `2` usage error, `3` not ready yet or timed out.

## 5. Phases

| Phase | Delivers | Done when |
|---|---|---|
| **A1** | `appchain-formats`; `new`, `request`, `seal`, `inspect` | Three cards from three node directories seal into one file whose fingerprint every directory reproduces; bad, duplicate and foreign cards are refused |
| **A2** | `join`, `start`, `stop`, `status`, `try`, `approve`, `anchor`; old verbs and the blueprint page removed; docs and tutorials rewritten | A new user finishes `try` using only printed instructions; a three-node application (separate node directories and ports) goes live, grows to four members with `approve`, anchors on devnet with one command and survives a restart |
| **A3** | jlink image per OS | The image creates and seals an application on a machine without Java |
| **B** | `invite --serve`, link-based `request` | Three operators join from links with no manual file transfer; a wrong invite code or a reused link is refused |
| **C** | `appchain ui` with the binding editor folded in; Studio removed | A user creates, seals and joins in the browser and gets byte-identical files to the CLI |

Later, not decided here: D (a git repository as the shared draft, with CI validating member cards)
and E (publishing the chain fingerprint and member cards on Cardano).

## 6. Alternatives considered

- **Keep the one-author blueprint.** Simple for a demo but cannot serve independent operators
  without moving keys around.
- **Start with one member and let everyone join through governance.** One join path, but early
  history is signed by a single key. Rejected: founding members are fixed at seal.
- **Re-resolve the draft at every `join`.** Smaller files, but different tool builds could resolve
  the same file differently. Rejected for D2.
- **Seal the devnet genesis.** Simpler on paper, but a devnet genesis cannot start late (D11).
- **GraalVM native binary.** Breaks the JVM-only rule and cannot load plugin bundles.
- **A setup UI inside the node.** Setup happens before any node exists, and it would need a host or
  plugin change.
- **A separate repository.** The sealed file must match the runtime and the catalogs exactly; a
  separate release would add a version matrix.

## 7. Consequences

- Operators send only public files. A key never needs to be copied.
- The genesis id now covers the whole definition and member set.
- Fewer verbs, one way to deploy; the Studio blueprint page and the cluster-wide export are gone.
- Operators who relied on the cluster-wide Ansible export deploy per node with the systemd unit.
- Growing a chain is one command per operator, but it costs one restart per operator and a period
  at a higher threshold.
- The jlink image is a second download per OS, built from the same release. As everywhere, differing
  bundle digests fail and differing version strings only warn.
- Rewritten: tutorials 8 and 9, the deployment guides and bindings chapters 1, 4 and 6.

## 8. Open questions

1. **Draft format.** Resolved in r2: YAML (D1).
2. **Solo genesis.** Resolved in r2: not supported; founding members are fixed at seal.
3. **Running as a service.** Resolved in r2: `join` writes a systemd unit file the operator
   installs (D4).
4. **Anchoring.** Resolved in r3: optional, never needed before `start`, one guided command (D10).
5. **Governed membership by default.** Resolved in r2: yes (D5).
6. **Join-link transport.** Plain HTTP is enough while links only carry public, signed data (D6).
   A pinned TLS certificate in the link is a possible later hardening. Lean: not in Phase B.

## Revision history

- **r1** (2026-10-26): initial proposal.
- **r2** (2026-10-26; maintainer decisions on `3bbfc465`): draft is YAML; solo genesis rejected;
  `join` writes a systemd unit file; governed membership confirmed as the default. D1 now states
  that a chain runs one state machine or a composite of several, that a draft holds several chains,
  and shows an example draft.
- **r3** (2026-10-26): anchoring is optional and never blocks `start`; new D10 adds the guided
  `anchor` command run by one leader; §3, D3, A2 and §8.4 updated.
- **r4** (2026-10-26; responds to the UX (F1–F10), Codex (C1–C8) and runtime (R1–R9) reviews of
  `bf007a50`):
  - Identity (UX F1, F3; C1, C3, C5, C7; R1, R4): D1 adds the invite code, the `operators` list and
    the genesis/peers split. D2 now seals resolved settings, separates fingerprint, genesis id and
    effective identity, keeps derived genesis ids for the authenticated map, uses a 128-bit displayed
    fingerprint, and states that consent is given at `join`. D4 fixes advertised addresses at
    `request`.
  - Membership (UX F5; C2, C8; R2, R6): D5 is rewritten as the quorum-safe procedure, with
    heartbeats, one restart, re-runs, removal and lost-key recovery. The draft states the threshold
    and the lead/proposer.
  - Devnet (UX F2; R3): new D11; L1 sync progress in `status`.
  - Anchoring (UX F8; C4, C6; R5): the leader is not sealed, `--take-over`/`--stop` added, the
    sequence is configure → restart → adopt or bootstrap, the false `--max-fee-ada` guarantee is
    dropped, and stability depth defaults are fixed at seal.
  - Recovery and scripting (C8; UX F4): new D12 and D13.
  - Naming and simplicity (UX F6, F7, F9): files named after the application, `--node-dir`,
    `--chain`, systemd routing; `add` folded into `seal`.
  - Multi-application nodes (R7): `join` refuses a network mismatch; `status` checks identity per
    chain.
  - Packaging (UX F10; R8): A3 narrowed to coordinators, stating that operators still need Java;
    kept because the maintainer chose it.
  - Facts (R9): `cluster.sh` is a Yano X script; the Yano help-text follow-up is noted.
  - Disputed: UX F9's proposal to defer Phase B, because the maintainer asked for Phase B.
- **r5** (2026-10-26; responds to the round-2 re-reviews of `047a1451`): F11/C8 → lost-key recovery
  removes first, `approve` refuses a threshold raise without the members online, the lost-lead case
  is stated; C12 → key ids compared on the call and `join` requires the operator's own key; C10 →
  bundle digests sealed and checked at `join` and `start`; F14 → version strings only warn; C9/F15 →
  uniqueness narrowed for derived ids, fresh keys by default, duplicate refusal; C11/F12/F18 →
  devnet download trust, network genesis id compared by `status`, dev-mode exposure noted; F13/C6 →
  take-over rules and pending-bootstrap handling; F16/F17 → heartbeat consent, listener lifetime,
  `approve` waits and re-runs.
- **r6** (2026-10-26; responds to the round-3 reviews of `508415a4`): C13 → founding and later joins
  distinguished (D1); F19 → accepted bundle set and `join --accept-bundles`, catalog fingerprint
  compared by `status` (D2); C6 → bootstrap recovery limited to command interruptions, the node-restart
  window reported (D10); F20 → identity rule applies to every leader change; F21 → key ids are 16 hex
  characters; F22 → `status` hashes the stored devnet genesis; C8 → insufficient quorum stated (D5);
  C14 → §7 aligned; R7 → node-wide settings across applications must agree.
