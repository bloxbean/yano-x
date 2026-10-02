# Tutorial 3 — Choose a Stock State Machine

[Open a stock approval workflow in App-Chain Studio](../../../tooling/studio/src/main/web/index.html#recipe=approval-workflow&network=devnet&members=3&finality=two-thirds&sequencing=fixed&runtime=jvm&deployment=host&name=approval-workflow&chainId=approval-workflow)

- **Level:** beginner for selection, advanced for wire integration
- **Outcome:** choose the smallest built-in deterministic model that matches
  your application, encode its commands, and configure a chain for it.

Selecting a state machine is a consensus decision. Every member must use the
same machine id and settings. For a new chain it is configuration; for an
existing chain, changing it needs a governed profile activation or a new chain.

## 1. Answer three questions

<!-- illustration: sm-chooser -->

## 2. Compare the stock machines

| Machine | Use it when | Who may change state | What a proof shows |
|---|---|---|---|
| [`ordered-log`](../state-machines/ordered-log.md) | You need one agreed order of opaque events | Any member's message | A message's height, index, topic and sender |
| [`kv-registry`](../state-machines/kv-registry.md) | You need mutable named records | The first writer of a key | Current owner and value per key |
| [`authenticated-map`](../state-machines/authenticated-map.md) | You need several collections with their own rules | Per collection: open, owner, member, role or approval | Status, revision and value digest per entry |
| [`approvals`](../state-machines/approvals.md) | Member nodes are the approvers | Distinct member keys | Status, approval count and payload hash |
| [`role-approvals`](../state-machines/role-approvals.md) | Approvers are business actors, not member nodes | Governed actors, organizations and roles | Policy outcome and payload hash |
| [`balances`](../state-machines/balances.md) | You need internal credits or netting | A minter mints; a member spends its own account | Balance per account |
| [`doc-trail`](../state-machines/doc-trail.md) | You need an ordered history per product or case | Any member appends | Entry count and chained head |

Two stock workflows are composites rather than single machines:

| Machine id | Use it when | Notes |
|---|---|---|
| `composite` with `machines.composite.preset: evidence-v1-gated` | Approval coordinates S3, IPFS and Kafka publication | One root across components and effects; see [Tutorial 4](04-evidence-publication.md) |
| `role-evidence` | The evidence flow needs business actors and roles | Governed actors, organizations and policies; see [Tutorial 5](05-domain-role-approvals.md) |

## 3. Encode a command

Stock machines read bounded, canonical CBOR commands. Use their Java contracts
or the tutorial helper; never serialize arbitrary Java objects.

<!-- illustration: wire-builder -->
1. **Write the command.** A `kv-registry` PUT is `[0, key, value]`.
2. **Encode.** The helper prints the canonical CBOR as hex.
3. **Admit.** The receiving member decodes it and answers `202`, or refuses it
   with `400`.
4. **Apply.** In the final block, the machine's rule decides what changes.
<!-- /illustration -->

Try the helper from the top-level directory of the extracted release:

```bash
TOOL=docs/appchain/tutorials/tools/stdlib_command.py

python3 "$TOOL" kv-registry put supplier-42 --value-text active
python3 "$TOOL" approvals propose release-7 --required 2 --payload-text go
python3 "$TOOL" balances mint alice 200
python3 "$TOOL" doc-trail product-42 abcd --reference ''
```

The command shapes are:

```text
kv-registry  [0, key, value]                                    PUT
             [1, key, h'']                                      DELETE
approvals    [0, itemId, payload, requiredApprovals, deadline]  PROPOSE
             [1, itemId] / [2, itemId]                          APPROVE / REJECT
balances     [0, account, positiveAmount]                       MINT
             [1, account, positiveAmount]                       TRANSFER from the sender's account
doc-trail    [entityId, entryHash, reference]                   APPEND; reference "" when none
```

`authenticated-map` and `role-approvals` carry signed actions and evidence; use
their CLI commands and Java authoring helpers described on their reference
pages. `ordered-log` takes any non-empty body.

## 4. Configure a chain for the local launcher

The cluster launcher reads `config/application-appchain.yml` in the release's
top-level directory (`$YANO_HOME/config/application-appchain.yml` when you set
`YANO_HOME`). It already defines `orders-chain`, `registry-chain` and
`effects-chain` as `chains[0]` to `chains[2]`. Add a fourth chain before you
start a fresh cluster:

```yaml
yano:
  app-chain:
    chains[3]:
      chain-id: "workflow-chain"
      state-machine: approvals
      state:
        commitment-profile: mpf-blake2b256-v1
        format-fingerprint: 91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b
        genesis-id: <output of: openssl rand -hex 32>
      membership:
        mode: governed
      block:
        interval-ms: 1000
```

The host refuses a chain without all three `state.*` settings. Copy the profile
and fingerprint from an existing chain and give each new chain its own genesis
id. The launcher injects members, threshold, signing keys, peers and the fixed
proposer; do not put that material in the shared file.

A generated project, created with `./yano.sh appchain init`, is different:
`appchain render` writes every chain setting, including the state identity,
and you never edit the rendered files by hand. To add a chain there, use the
[add-chain workflow](../deployment/add-chain.md).

## 5. Configuration is not arbitrary composition

Configuration selects and parameterizes semantics that already exist. It cannot
safely express new component order or terminal transitions, because those
change every member's state root. Use, in order of preference:

1. **stock configuration** when one machine or profile already fits;
2. **declarative bindings** when stock components must react to each other's
   events; see [declarative bindings](../bindings/README.md);
3. **a composite plugin** when components need a new committed order or
   transition; or
4. **a custom state-machine plugin** for new business state or rules.

## Common mistakes

- Treating a REST API key as an approval identity.
- Changing machine settings on one member only.
- Treating `202`, or a final message, as proof that state changed.
- Putting large or secret documents in replicated command bodies.
- Adding network, clock, DNS or random behavior inside `apply()`.
- Reusing a machine id after changing its deterministic behavior.

## Go deeper

- [State machines](../state-machines/README.md) lists every machine with its
  maturity, state key and proof subject.
- Each reference page, for example [`kv-registry`](../state-machines/kv-registry.md),
  covers REST, Java, proofs and design choices.
- The Yano [consensus guide](https://github.com/bloxbean/yano/blob/main/docs/APP_CHAIN_CONSENSUS_GUIDE.md)
  explains how blocks are ordered, re-executed and certified.
- For business actors, continue with
  [domain-role approvals](05-domain-role-approvals.md); for coordinated
  publication, with [the evidence scenario](04-evidence-publication.md).
