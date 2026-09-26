# 5. Operate, diagnose and evolve a workflow

A validated YAML document is the beginning of deployment qualification, not its
end. This chapter turns the [first workflow](01-first-workflow.md) into an
operational checklist. Keep the complete [language reference](../DECLARATIVE_BINDINGS.md)
and [upgrade preflight guide](../DECLARATIVE_BINDINGS_UPGRADES.md) nearby.

## Distinguish admission from business success

| Observation | Meaning | Next action |
|---|---|---|
| HTTP 400 application rejection | The submitted command failed early admission; it was not pooled | Correct its encoding, size or other stated invalidity and submit again |
| HTTP 503 admission unavailable | The admission callback could not complete | Investigate node warnings and plugin health; it is not business acceptance |
| HTTP 202 | The node accepted the submission into its processing path | Wait for finalization, then inspect the receipt |
| Finalized accepted receipt | This source's cascade committed | Inspect the resulting business state; external delivery may still be pending |
| Finalized rejected receipt | This source's business writes and effects did not commit | Read the code and failed step, correct the cause and submit a fresh signed message if appropriate |

Early admission cannot know all future state or remaining block capacity. A
well-formed command can therefore still fail during execution. The host does
not invalidate the whole finalized block because one cascade was rejected.
Its terminal receipt remains, while the source's business changes are rolled back.

Do not retry a finalized rejection using the same message ID: replay returns
the original receipt. A fresh envelope/ID is a new attempt, not a bypass of
business idempotency, authorization or one-use approval consumption. A capacity
failure may succeed in a later block; a bad signature or invalid action needs a
real correction.

## Find the receipt and its proof key

Retain the submitted source message ID in your application. Query
`composite/binding-receipt-v1/<source-message-id-hex>` through the ordinary chain
query API. The canonical receipt contains the overall result, visited steps,
emitted events and condition results. A planned step inside a **rejected**
receipt is not a committed operation.

To discover its physical authenticated key without scanning state:

```bash
./yano.sh appchain bindings receipt-key "$SOURCE_MESSAGE_ID"
```

This offline command returns `stateKeyHex` and the receipt/key query paths.
Alternatively query `composite/binding-receipt-key-v1/<source-message-id-hex>`
with empty parameters. Its generic query response's `payloadHex` is already the
raw physical key—do not decode it as a second CBOR value.

Key discovery works even if no receipt exists. Request a state proof for the key
and verify its presence/value against independently trusted chain identity,
root and finality. It is not enough to trust the same server's claimed root.
An app-final proof is not automatically a Cardano-anchored proof; see
[anchors and verification](../tutorials/07-anchors-and-verification.md).

## Diagnose “my binding did not fire”

1. Confirm the source finalized on the expected chain and profile.
2. Inspect the receipt's overall status before looking at individual steps.
3. Check the source instance and **event ID**, not just the command topic.
4. Inspect the first false/error `failedClause` (zero-based). `-1` usually means
   all clauses matched, but a pre-clause budget failure also uses it; read the
   rejection code and step together. Unvisited bindings have no invented trace.
5. Verify mapping types and target admission/authorization. A successful source
   transition alone does not guarantee its derived target succeeds.
6. Check event, work, fan-out, receipt and effect bounds. The
   [rejection-code table](../DECLARATIVE_BINDINGS.md#why-did-a-binding-not-fire)
   distinguishes them.

Reproduce with the exact document/IR, plugin catalog and explicit context, using
appropriate pre-block state, message order and height. For multi-block rehearsals,
carry `postState` with `--prior-result` and advance exactly one height each time.
Use `messages: []` for intervening empty blocks. The
[approval tutorial](03-approval-workflows.md) demonstrates this progression.

Continuation files are editable, unauthenticated test fixtures. They are not
node backups or trusted state exports and must not be imported into retained
stores. Dry-run does not calculate the predecessor root for your next fixture.

## Budget for the actual application

The 64-KiB host message limit is not a promise that every 64-KiB command can
produce every event or fan-out. Events add metadata; copied values, hashes,
expressions and derived dispatch consume bounded work.

The default per-cascade work allowance is **1,048,576** units; the shared block
allowance is **33,554,432**. These are deterministic accounting units, not CPU
milliseconds. For the reference `source.v1` near-limit raw-body tee, 65,369 body
bytes cost 392,650 cascade units and 196,278 shared units. The binding-work
allowance alone could cover 170 such cascades; host block-byte/message-count
limits and other work may permit fewer. In particular, chapter 1's 1-MiB block
limit cannot fit 170 such commands. This exact body boundary depends on
metadata length; do not generalize it to every topic or sender layout.

An original source event with no subscribers does not incur shared event-decode
work. An unbound source is not rejected merely because binding work is exhausted.
Derived dispatch and preparation still consume shared work, even for an unbound
target. Rejected attempts do not refund work, and the block budget resets for
the next block.

Before fixing your profile, test typical and maximum payloads, longest paths,
fan-out, false conditions, failing targets and crowded blocks. Include the
trace itself: receipts are bounded too. Increasing one limit cannot establish
that all other limits accommodate the workload. Limits are part of the committed
program, not node-local knobs you can raise on a busy server.

## External effects are a separate boundary

A cascade can atomically commit an effect **intent** with its state changes.
The external HTTP service, object store or other receiver does not participate
in that state transaction. Delivery is at-least-once; implement receiver-side
idempotency using the effect identity, observe outcomes and plan retries.

The procurement recipe's `test` effect is demonstration plumbing, not a
production webhook integration. Select a real installed executor, configure its
security and credentials outside consensus data, and qualify failure/restart
behavior. Dry-run prints intents; it never proves external delivery. See
[webhook effects](../tutorials/06-webhook-effects.md).

## Changing a workflow

Changing YAML is not hot reload. Rendering commits component settings and
defaults, routes, quotas, generation heights and the binding program into a
profile. Machine application versions and query subjects also affect identity.
Even a seemingly additive bundle update can make a retained profile impossible
to reconstruct.

For a chain created in governed mode, evolution requires an executable catalog
that reproduces the necessary historical profiles, qualified target semantics,
and the existing proposal/approval/readiness/activation protocol. Changed
bindings require a new workflow generation; changed component configuration
requires a new component generation. Neither migrates incompatible stored
genesis or state automatically.

Use the read-only preflight against candidate bundles:

```bash
./yano.sh appchain bindings profile-check \
  --profiles retained-profiles.json --context context.json \
  --plugins-directory /absolute/path/to/candidate/plugins
```

Here `retained-profiles.json` is a nonempty JSON array of canonical profile hex
strings, genesis first, including the historical/target profiles being checked.
Multiple profiles require the appropriate governed membership context. Follow
the [full preflight contract](../DECLARATIVE_BINDINGS_UPGRADES.md#check-a-candidate-bundle-set-without-touching-retained-state)
before constructing these inputs.

Success means byte-exact profile reconstruction only. It does not prove replay,
binary semantic equivalence, migration safety, historical proof correctness or
effect-result compatibility. The current stock provider executes version 1.1.0;
it cannot restore an old 1.0.0 implementation simply by receiving old IR. Do not
upgrade such retained chains in place or rewrite their identity markers.

## Before sharing a deployment

- Archive the exact host/X artifacts, plugin catalog, YAML, IR, canonical
  profiles, lock file and deployment identity; keep key custody separate.
- Test accepted and rejected cascades, authorization failures, replay and
  independent receipt proofs with your real command codecs.
- Compare finalized heights, roots and profile identities across members;
  exercise catch-up, restart and historical reads.
- Qualify expected peak payloads and throughput, including rejected attempts.
- Check product boundaries: derived writes do not automatically appear in every
  legacy projection, and derived audit commands are not independently signed
  message-attestation certificates.
- Keep backups and rehearse upgrades without modifying retained production state.

Return to the [learning path](README.md), continue with the
[retained-chain upgrade reference](../DECLARATIVE_BINDINGS_UPGRADES.md), or author the
same documents with guided forms in [chapter 6](06-guided-editor.md).
