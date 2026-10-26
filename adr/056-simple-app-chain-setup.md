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
- Unless set explicitly, a chain's genesis id is
  `sha256("yano-appchain-project-genesis-v1\0" + projectName + "\0" + chainId + "\0" + capabilities)`
  (`AppChainProjectResolver.materializeStateCommitmentIdentity`). Two unrelated projects that use
  the same names get the same genesis id, and the id does not cover the member set or the rules.
- The project lifecycle has 11 verbs (`init`, `render`, `doctor`, `prepare`, `plan`, `apply`,
  `diff`, `drift`, `gitops`, `chain add`, `migrate`), about 7.8k of devtools' 19.2k main lines
  (ADR-055 §3). ADR-055 already proposes removing it and Studio, but not what replaces them.

The runtime already has what a multi-operator chain needs: Ed25519 member keys (at most 32 members
in the v1 profile), an explicit `state.genesis-id` setting (`StateCommitmentIdentity`), and
governed membership (`membership.mode: governed`), where a new member is added by threshold-many
identical member commands and catches up from the chain. Only the tooling is missing.

## 2. Goals and non-goals

Goals:

1. One tool takes a beginner from nothing to a running multi-operator chain, and every step tells
   them what to do next.
2. Private keys are created where they are used and never travel.
3. Every operator can check, before starting, that they all have the same chain.
4. An expert can script every step.

Non-goals: no runtime, consensus, wire or storage change; no VM provisioning (ADR-039 stays);
no change to `yano.sh appchain cluster`, which is Yano's single-host demo launcher.

## 3. The user experience

There are two roles. The **coordinator** defines the chain. Each **operator** runs one member node.
The coordinator is usually also an operator.

```text
coordinator$ yano.sh appchain new orders            # wizard → orders.draft.yaml
             # send orders.draft.yaml to each operator (email, chat, …)

operator$    yano.sh appchain request orders.draft.yaml
             # creates this operator's key if needed → alice.member
             # send alice.member back to the coordinator

coordinator$ yano.sh appchain add orders.draft.yaml alice.member bob.member carol.member
coordinator$ yano.sh appchain seal orders.draft.yaml  # → orders.chain, prints its fingerprint
             # send orders.chain to each operator; read the fingerprint out on a call

operator$    yano.sh appchain join orders.chain     # shows the summary and fingerprint, asks to confirm
operator$    yano.sh appchain start
anyone$      yano.sh appchain status orders
```

Every command ends with a `Next:` line, and `status` shows where the chain is:

```text
orders  fingerprint 3f9a-77c1-0b2e-d4a8
  Draft ─ Members 3/3 ─ Sealed ─ Starting ─ Live
                                    ▲ 2 of 3 members online; waiting for carol (node-c:13337)
Next: ask carol to run  yano.sh appchain start
```

**Practice first.** `yano.sh appchain try` runs the same steps on one machine with three simulated
operators in separate directories. It prints each real command as it runs it, so a beginner learns
the production flow, not a demo-only one.

**Adding a member later.** The new operator runs the same `request` against the `.chain` file and
sends the card to the existing members. Each of them runs `yano.sh appchain approve dave.member`,
which submits the governed-membership command on their own node. Once the threshold is reached,
`dave` runs `join` and `start` and catches up.

**Hosting another chain on the same node.** The operator runs `join` with a second `.chain` file.
Adding a chain to a running node still needs a stop and start (a current host limitation), and
`join` says so.

## 4. Decisions

### D1 — Three files, one per step

| File | Written by | Contains | Secret? |
|---|---|---|---|
| `<chain>.draft.yaml` | Coordinator (`new`, then edited freely) | Chains, recipes, capabilities, bindings reference, finality and membership policy, network, Yano version | No |
| `<operator>.member` | Operator (`request`) | Name, Ed25519 public key, node endpoint, and a signature by that key over the draft fingerprint and endpoint | No |
| `<chain>.chain` | Coordinator (`seal`) | The draft plus the founding members and the pinned plugin bundle digests | No |

- The private key stays in the operator's node directory, owner-readable only.
- The member card's signature proves the operator holds the key and binds the card to this draft,
  so a mistyped or reused key is caught at `add`, not at startup.
- The draft is YAML, because people read and edit it.

**What a draft holds.** A draft holds one or more chains (up to 32, as today), all sharing the
founding members. Each chain runs either **one state machine**, chosen by recipe, or **several state
machines working together**: a composite of up to 16 components joined by bindings (ADR-031.1),
which commit atomically in the same block. The `new` wizard asks, for each chain, "one state machine
or several?", offers the binding starters from `examples/bindings/`, and then asks "add another
chain?". Experts edit the YAML directly:

```yaml
name: supply
network: preprod
membership: {mode: governed}
chains:
  - id: orders                       # one state machine
    recipe: audit-log
  - id: approvals                    # several state machines in one chain
    recipe: declarative-composite
    bindings: ./approval-to-audit.yaml   # components + bindings, or inline under `composite:`
```

`seal` inlines any referenced bindings file, so the `.chain` file is self-contained. A chain's
state machines are fixed at genesis: to add another state machine later, add a new chain, or use
governed composite profile evolution (app-layer ADR-015), which this ADR does not change.

### D2 — The genesis id is the hash of the sealed file

`seal` writes `<chain>.chain` once. Each chain's genesis id is
`SHA-256("yano-x-appchain-genesis-v1\0" || SHA-256(file bytes) || chainId)`, and the file's
**fingerprint** is the first 16 hex digits of the file hash, grouped for reading aloud. `join`
recomputes both and refuses an edited file. This replaces the name-based derivation above; the
runtime is unaffected because it already takes `state.genesis-id` as an explicit setting.

### D3 — One CLI with a few verbs

Phase A verbs: `new`, `request`, `add`, `seal`, `join`, `start`, `stop`, `status`, `approve`,
`inspect` and `try`. Each is interactive by default and accepts flags plus `--yes` for scripts.
Errors say what to do, for example
`carol.member was signed for a different draft (fingerprint 9b1e-…); ask carol to run request again`.

### D4 — Node setup is local

`join` asks only for local choices, with defaults: data directory, HTTP and node ports, and which
existing key to use. It writes the node configuration from the `.chain` file; peers come from the
members' endpoints. Changing local settings later does not touch the chain identity. On Linux, `join`
also writes a systemd unit file and prints the command to install it; it never installs it itself.

### D5 — Later members use governed membership

New chains default to `membership.mode: governed`, so `approve` works without a coordinated
restart. A coordinator may choose `static` in the draft; `approve` then explains the static runbook
instead.

### D6 — Phase B: join links

`yano.sh appchain invite orders.draft.yaml --serve` starts a temporary listener on the coordinator's
machine and prints one link per operator:

```text
yano.sh appchain request yano://203.0.113.10:7099/9QmV2x#3f9a-77c1-0b2e-d4a8
```

The operator's `request` downloads the draft, checks it against the fingerprint in the link,
creates the key, posts the member card and waits. The coordinator sees cards arrive live and runs
`seal`. Each waiting operator then receives the `.chain`, sees the summary and fingerprint, and
confirms the `join`. The files and checks are those of Phase A, only carried automatically:

- No node and no runtime plugin is involved; the listener is part of the CLI and stops after seal.
- Links are single-use and expire (default 24 hours).
- Everything transferred is public and checked by fingerprint or signature, so the link secret only
  stops strangers from adding cards. Comparing the final fingerprint on a call is still advised.

### D7 — Phase C: a local setup UI in Yano X

`yano.sh appchain ui` starts a localhost-only web UI over the same engine and opens the browser. It
has the same steps as the CLI: create (wizard), members, seal, join, and status, with a link to the
node's own console once live. The binding editor (ADR-031.2) becomes the optional "workflow" step of
create. The UI is built like `products/eutxo/ui` (ADR-040). It lives in Yano X, is served by the
CLI, and needs no node, plugin or Yano change. Yano's node console stays in Yano.

### D8 — Modules and packaging

- `tooling:appchain-formats`: the three file formats, fingerprint and genesis id, signature checks.
  A small library with no node dependency.
- `tooling:appchain-cli`: the verbs above. It is installed at `tools/yano-appchain/` in the JVM ZIP,
  where Yano's `yano.sh appchain` already dispatches every verb except `cluster`.
- A **self-contained jlink image per OS** is also published, so a coordinator can create and seal a
  chain without installing Java. It is not GraalVM native: Yano X is JVM-only
  (`verifyJvmOnlyBuild`), and validating a draft loads plugin bundles through
  `PluginProviderRegistry`, which needs a JVM.
- Useful pieces of `tooling:devtools` move into these modules (catalog loading, metadata trust, the
  bindings compiler). The rest is removed with the old verbs (D9).

### D9 — What this replaces

| Today | After Phase A |
|---|---|
| `init`, `prepare`, `render` | `new`, `request`, `add`, `seal`, `join` |
| `chain add`, `plan`, `apply` | `join` with another `.chain` file |
| `gitops` (cluster-wide Ansible, Helm, Kustomize) | Removed; each operator deploys their own node. ADR-039 is unchanged |
| `diff`, `migrate` | Removed |
| `doctor`, `drift` | Folded into `status` |
| `recipes`, `capabilities`, `bindings`, `state`, `role`, `plugin`, `metadata` | Kept |
| Studio blueprint page | Removed in Phase A |
| Studio binding editor | Kept until Phase C absorbs it |

For ADR-055: Phase 2's first need, deploying bindings without `init`, is met by the draft
referencing a bindings document; its acceptance gates move to `appchain try`. Phase 3's Studio
removal happens in two steps, as in the table. Nothing has been publicly released, so no aliases
are kept.

## 5. Phases

| Phase | Delivers | Done when |
|---|---|---|
| **A1** | `appchain-formats`, `new`, `request`, `add`, `seal`, `inspect` | Three cards from three machines seal into one file whose fingerprint every machine reproduces |
| **A2** | `join`, `start`, `stop`, `status`, `try`, `approve`; old verbs and blueprint page removed; tutorials rewritten | A new user finishes `try` using only printed instructions; a real three-machine chain goes live, adds a fourth member and survives a restart |
| **A3** | jlink images per OS | The image seals a chain on a machine without Java |
| **B** | `invite --serve`, link-based `request` | Three operators join from links with no manual file transfer; an altered draft or a reused link is refused |
| **C** | `appchain ui` with the binding editor folded in; Studio removed | A user creates, seals and joins in the browser and gets byte-identical files to the CLI |

Later, not decided here: D (a git repository as the shared draft, with CI validating member cards)
and E (publishing the sealed file's hash and member cards on Cardano).

## 6. Alternatives considered

- **Keep the one-author blueprint.** Simple for a demo but cannot serve independent operators
  without moving keys around.
- **Start with one member and let everyone join through governance.** One join path, but early
  history is signed by a single key. Rejected in r2: founding members are fixed at seal.
- **GraalVM native binary.** Breaks the JVM-only rule and cannot load plugin bundles.
- **A setup UI inside the node.** Setup happens before any node exists, and it would need a host or
  plugin change.
- **A separate repository.** The sealed file must match the runtime and the catalogs exactly; a
  separate release would add a version matrix.

## 7. Consequences

- Operators send only public files. A key never needs to be copied.
- The genesis id now commits to the whole definition and member set.
- Fewer verbs, one way to deploy, and the Studio blueprint page and cluster-wide export are gone.
- Operators who relied on the cluster-wide Ansible export deploy per node instead.
- Rewritten: tutorials 8 and 9, the deployment guides and bindings chapters 1, 4 and 6.

## 8. Open questions

1. **Draft format.** Resolved in r2: YAML (D1).
2. **Solo genesis.** Resolved in r2: not supported; founding members are fixed at seal.
3. **Running as a service.** Resolved in r2: `join` writes a systemd unit file the operator
   installs (D4).
4. **Anchoring.** Anchor policy belongs in the draft; the anchor wallet and its bootstrap stay
   per-operator explicit steps. Confirm that no anchor setup is needed before `start`.
5. **Governed membership by default.** Resolved in r2: yes (D5).

## Revision history

- **r1** (2026-10-26): initial proposal.
- **r2** (2026-10-26; maintainer decisions on `3bbfc465`): draft is YAML; solo genesis rejected;
  `join` writes a systemd unit file; governed membership confirmed as the default. D1 now states
  that a chain runs one state machine or a composite of several, that a draft holds several chains,
  and shows an example draft.
