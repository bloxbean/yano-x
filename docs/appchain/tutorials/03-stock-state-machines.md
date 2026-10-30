# Tutorial 3 — Choose a Stock State Machine

To run this outcome with other operators, follow [Set up an application](../deployment/configure.md).

- **Level:** beginner for selection, advanced for wire integration
- **Outcome:** choose the smallest built-in deterministic model that matches
  your application, encode its commands, and configure a chain for it.

Selecting a state machine is a consensus decision. Every member must use the
same machine id and settings. For a new chain it is configuration; for an
existing chain, changing it needs a governed profile activation or a new chain.

## 1. Answer three questions

<!-- illustration: sm-chooser -->
1. **What must the ledger keep for you?**
   - Only the order of events, with your applications reading the payloads:
     `ordered-log`.
   - Current records that writers can change: go to question 2.
   - Quantities that move between accounts: `balances`.
   - An ordered history of document hashes per product or case: `doc-trail`.
   - A decision that several approvers make: go to question 3.
2. **Who may change a record?** The member that wrote it first:
   `kv-registry`. It depends on the collection (open, owner, member, a role or
   an approval, with revisions, tombstones and atomic batches):
   `authenticated-map`.
3. **Who approves?** The member nodes themselves: `approvals`. Business actors
   with roles and organizations, separate from the members: `role-approvals`.
<!-- /illustration -->

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

## 4. Choose the machine for each chain

When you set up an application, each chain names a *recipe*: a stock state
machine with the settings it needs. List the recipes, then the answers each
one needs:

```bash
./yano.sh appchain recipes
./yano.sh appchain recipes --format json | jq '.recipes[] | {id, nonSecretAnswers}'
```

| Machine | Recipe |
|---|---|
| `ordered-log` | `audit-log` |
| `kv-registry` | `owned-registry` |
| `authenticated-map` | `authenticated-map` |
| `approvals` | `approval-workflow` |
| `role-approvals` | `role-approval` |
| `role-evidence` | `evidence-ledger` |
| `doc-trail` | `document-trail` |

A machine without a recipe of its own, such as `balances`, uses the
`custom-plugin` recipe with its `stateMachine` answer. Read the machine's
reference page for the settings it needs.

To rehearse a choice on one machine, give `try` one `--chain <id>=<recipe>`
per chain. `new` takes the same options for a real application:

```bash
./yano.sh appchain try --chain orders=audit-log --chain workflow=approval-workflow
./yano.sh appchain try --clean
```

For more than a couple of chains, or for answers and settings, write the
chains in a small template and pass it with `--from`:

```yaml
# chains.template.yaml
chains:
  - id: orders
    recipe: audit-log
  - id: workflow
    recipe: approval-workflow
  - id: credits
    recipe: custom-plugin
    answers:
      stateMachine: balances
    settings:
      block.interval-ms: "1000"
```

```bash
./yano.sh appchain try --from chains.template.yaml
```

`answers:` fill in what a recipe asks for; `--answer <chain>.<name>=<value>`
does the same on the command line. `settings:` are flat chain settings that
`seal` fixes for every member; `--setting <chain>.<key>=<value>` adds one, and
`--without <chain>` leaves a template chain out. Settings that `seal` derives
from the draft, such as members, threshold, sequencing, and membership, are
refused there. A mistyped recipe or a missing answer stops `try` before
anything runs and leaves no rehearsal behind; a refused setting stops it at
`seal`, so run `./yano.sh appchain try --clean` before the next attempt.

> **✓ You should see** `seal` list one line per chain with its recipe and
> genesis, for example `chain workflow approval-workflow genesis …` (the
> `credits` line shows the recipe `custom-plugin`;
> `./yano.sh appchain inspect appchain-try/coordinator/demo.chain --json`
> shows its `"state-machine" : "balances"`), and the final `status` show every chain with
> `members agree`.

`seal` writes every chain setting, including the state identity (commitment
profile, format fingerprint, and genesis id), into the sealed `<app>.chain`
file, and `appchain join` turns it into each node's configuration. You never
edit those files by hand, and no member can run different settings. To add a
chain to a live application, see
[grow and change](../deployment/add-chain.md).

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

## Clean up

If you ran a rehearsal, remove it:

```bash
./yano.sh appchain try --clean
rm -f chains.template.yaml
```

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
