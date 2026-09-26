# 1. Your first declarative workflow

Build a registry that automatically records an audit entry when a value is
written. You will author the workflow, check it against actual plugin bundles,
and execute a synthetic command through the real composite engine offline.

This is a rehearsal, not a deployed chain. No private keys or running nodes are
needed. Read the [overview](README.md) first if components and bindings are new.

## 1. Prepare the tools

Use Java 25 and an extracted Yano X JVM distribution containing declarative
bindings. Run the commands below from its root, where `yano.sh` and `plugins/`
are present. If building from this checkout, follow
[the distribution build guide](../../BUILD_DISTRIBUTIONS.md).
Keep the launcher and plugins from the same build; upstream Yano alone is not
the Yano X distribution. `jq` is needed only for extracting the Studio snapshot.

Create three text files in a disposable working directory. These instructions
use files in the distribution root for shorter commands; absolute paths work too.
Do not reuse the synthetic chain identity below for a retained deployment.

## 2. Define the components and connection

Save this as `bindings.yaml`:

```yaml
composite:
  components:
    - {id: records, machine: kv-registry}
    - {id: audit, machine: doc-trail}
  bindings:
    - id: audit-record
      from: {component: records, event: kv-registry.entry-put.v1}
      when: [{expr: 'event.valueLength < 100'}]
      to:
        component: audit
        command: append
        map:
          entityId: {fn: hex, args: [{field: key}]}
          entryHash: {field: valueHash}
          reference: {literal: published}
```

`records` and `audit` are your instance names; `kv-registry` and `doc-trail`
select installed machines. The registry receives commands at the default topic
`records.command.v1`. A successful put emits `kv-registry.entry-put.v1`.

The condition matches values shorter than 100 bytes. The mapping converts the
binary registry key to a text entity ID, copies the value's hash into the audit
entry, and sets a text label. The field names belong to the machine contracts,
not arbitrary application variables. Consult the
[registry](../state-machines/kv-registry.md) and
[document trail](../state-machines/doc-trail.md) references for their semantics.

## 3. Declare the rehearsal context

Save this as `context.json`. This strict JSON supplies chain identity, membership
and host limits; it is neither a node properties file nor a place for secrets.
The zero genesis and repeated public-key bytes are synthetic offline inputs.

```json
{
  "chainId": "offline-test",
  "settings": {
    "state.commitment-profile": "mpf-blake2b256-v1",
    "state.format-fingerprint": "91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b",
    "state.genesis-id": "0000000000000000000000000000000000000000000000000000000000000000"
  },
  "consensusProfile": {
    "schemaVersion": 2,
    "maxMessageBytes": 65536,
    "maxBlockMessages": 100,
    "maxBlockBytes": 1048576,
    "l1StabilityDepth": 0,
    "epochStabilityDepth": 0,
    "enforceSenderSeq": false,
    "effectsEnabled": false,
    "effectsMaxPerBlock": 0,
    "effectsMaxPayloadBytes": 0,
    "effectsMaxExpiryBlocks": 0,
    "effectsResultWindowBlocks": 0,
    "effectsDefaultGate": "APP_FINAL",
    "effectsOutcomeCommitment": "PER_EFFECT",
    "effectsStrictReservedPrefix": true,
    "effectResultSigners": []
  },
  "membership": {
    "fromHeight": 0,
    "members": ["2222222222222222222222222222222222222222222222222222222222222222"],
    "threshold": 1
  }
}
```

## 4. Validate, compile and inspect

```bash
./yano.sh appchain bindings validate bindings.yaml \
  --plugins-directory plugins --context context.json > validated.json
./yano.sh appchain bindings compile bindings.yaml \
  --plugins-directory plugins --context context.json > bindings.ir.hex
./yano.sh appchain bindings graph bindings.yaml \
  --plugins-directory plugins --context context.json > bindings.dot
```

All three commands must exit successfully. Validation constructs the actual
catalog-selected profile, catching errors such as an unknown event or mapping
type mismatch. Compilation prints canonical IR hex; graphing prints DOT, not an
image. Neither submits a transaction or installs a workflow into a running node.

If Graphviz is installed, render the graph with
`dot -Tsvg bindings.dot -o bindings.svg`. Graphviz is optional and separate from
Yano X. For the browser viewer, extract the capability manifest from validation
as described below; a DOT file is not Studio's import format.

## 5. Submit an offline test command

Save this as `fixture.json`:

```json
{
  "height": 1,
  "timestamp": 123,
  "stateRootHex": "0000000000000000000000000000000000000000000000000000000000000000",
  "pendingEffects": 0,
  "state": [],
  "messages": [{
    "messageIdHex": "1111111111111111111111111111111111111111111111111111111111111111",
    "senderHex": "2222222222222222222222222222222222222222222222222222222222222222",
    "senderSeq": 1,
    "expiresAt": 9223372036854775807,
    "topic": "records.command.v1",
    "bodyHex": "8300420102420304",
    "authProofHex": "00"
  }]
}
```

The canonical CBOR command means “put bytes `0304` at key `0102`.” The synthetic
authentication byte is only valid for this rehearsal: dry-run assumes envelope
authentication has already happened. Do not send this fixture as a real signed
message. [Java integration](04-java-integration.md) uses the command codecs
instead of requiring application developers to hand-write CBOR.

```bash
./yano.sh appchain bindings dry-run bindings.yaml \
  --plugins-directory plugins --context context.json \
  --fixture fixture.json > result.json
```

The CLI preserves the receipt's versioned positional-array encoding in its
decoded JSON. For a compact view of this v1 receipt:

```bash
jq '.receipts[] | {outcome: .receipt[3], components: [.receipt[6][] | .[3]]}' result.json
```

Expect `ACCEPTED` and components `["records", "audit"]`. Also inspect
`stateChanges` for physical state writes. Expect an
accepted source cascade with a registry step and a derived audit step. The audit
entity is text `0102`, its entry is the registry value hash, and its reference is
`published`. There are no external effects in this example. `postState` contains
the complete rehearsal state, including internal markers and the receipt—not
just your two business records.

Dry-run does not authenticate the input state, verify signatures or finality,
compute a post-state root, or prove anything about a live chain. Success here is
one useful test, not a substitute for multi-node deployment validation.

## 6. See a condition skip

Change the condition to `event.valueLength < 2` and run the same fixture again
as a fresh height-1 rehearsal. The two-byte value no longer matches. The registry
put still succeeds, but no audit append is derived. A **false condition skips a
binding**; it does not reject the source command. A mapping or target rejection,
on the other hand, rejects the cascade and rolls back its business changes.

Restore `< 100` before continuing. This edit creates a different committed
program; on a real chain it would require qualified profile evolution.

## 7. View the graph in Studio

Studio is the static site in the distribution's `studio/` directory. Serve it
locally, for example with `python3 -m http.server 8080 --directory studio`, and
open the localhost URL. This starts a local static web server, not a Yano node.
See [Studio configuration](../deployment/configure.md).

Extract the validation result's manifest into `manifest.json`:

```bash
jq '.manifest' validated.json > manifest.json
```

Then open
**Inspect a declarative binding graph** and select that file. Use the manifest
object itself, not the enclosing validation result. This viewer is read-only:
it shows components and edges but cannot edit bindings, conditions or mappings.
An imported snapshot is not authenticated chain evidence. To author the document
itself with guided forms, use Studio's **Bindings** page
([chapter 6](06-guided-editor.md)).

## From rehearsal to a project

To deploy, import the document with `appchain init --recipe declarative-composite`
and `--bindings bindings.yaml --plugins-directory plugins`, supplying your real
topology member public keys and other required project options. Follow the full
[blueprint project example](../DECLARATIVE_BINDINGS_CLI.md#blueprint-projects)
and [deployment guide](../deployment/README.md); do not deploy the dummy context
or authentication bytes from this tutorial.

Next: [Conditions and mappings](02-conditions-and-mappings.md).
