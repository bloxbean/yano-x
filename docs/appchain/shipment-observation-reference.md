# Certified shipment workflow reference (preview)

The `shipment-workflow-reference-v1` standard-library plugin illustrates one
order across four different authorities:

1. A host-validated, stable Cardano `address-deposit` observation establishes payment.
2. A generic observation certificate establishes inclusion of `DELIVERED` in
   a shipment attestor's signed Merkle root, bound to that payment transaction.
3. A deterministic `cardano.payment` effect requests release to the merchant.
4. A second stable Cardano deposit observation must match the confirmed
   release transaction hash, merchant address, and minimum release amount.

An executor receipt alone never completes the workflow. Generic observations
do not replace Cardano validation. A signed Merkle inclusion proves what an
authorized attestor committed, not that a physical shipment actually arrived.

This is a single-order teaching example, not a production escrow contract,
inventory system, refund policy, or proof that the release wallet controls the
original payment. The operator supplies and secures the effect executor and
wallet. No private key is held in application state.

## Activation and pinned inputs

Use the ordinary Yano JVM distribution and the dependency-complete Yano X
stdlib plugin bundle through the plugin catalog. The plugin requires host API
major 3, level 8. The current coordinated development host is
`0.1.0-pre14-d3adba688`; local publication is not a public release.

Select `shipment-workflow-reference-v1` as the state machine. The suffix-keyed
chain settings must include:

```properties
observers.shipment-payment.type=address-deposit
observers.shipment-payment.address=<payment Cardano address>
observers.shipment-settlement.type=address-deposit
observers.shipment-settlement.address=<merchant Cardano address>
machines.shipment-workflow-reference-v1.minimum-payment-lovelace=10000000
machines.shipment-workflow-reference-v1.release-lovelace=5000000
```

These are suffixes under the chain's `yano.app-chain` configuration, not a
complete runnable node configuration. Effects must be enabled, L1 stability
depth must be positive, and a working `cardano.payment` executor must be
installed/configured. Choose the effect finality gate for the deployment's
security policy. The plugin uses the chain's default gate and bounded expiry.
Addresses must be actual network-appropriate Cardano addresses; the example's
configuration type only checks bounded ASCII text, not address validity.

The enabled, genesis-pinned observation profile must contain definition
`shipment-delivery`, verifier `ed25519-merkle-inclusion-v1`, and the active-member
exact-value policy. Configure the host's `https-attested-merkle-v1` adapter,
public HTTPS endpoint, and authorized attestor keys. Commit the canonical
endpoint/method/key-set digest with
`ObservationSourceConfiguration.merkleAttestedHttpsSourceDigest`. Configure
normal definition/profile evidence, report, and source bounds. There is no
runtime mutation of this profile by the example.

The subscription parameter bytes are the original payment transaction hash.
The Merkle leaf commits their digest, source ID, and exact ASCII `DELIVERED`
value using `ObservationMerkleEvidence`'s domain-separated format. A receipt
for another payment, round, subscription, or source cannot be reused. The
attestor's signed root envelope also binds the round, definition, source
version, and freshness anchor. Paths are bounded to twenty siblings; the host
rejects invalid proofs before reporter journaling/signing.

The one-shot round is due at payment incorporation height + 1 and collects
through height + 3, with the host's pinned inclusion grace. These are logical
app heights, **not seconds**. The ordinary command topic `shipment/advance`
with body byte `01` can advance an otherwise idle chain, but conveys no
payment or shipment authority.

## Wake hints and acquisition

`POST /api/v1/app-chain/chains/{chainId}/observations/wake` takes the raw 32-byte
subscription ID as `application/octet-stream` using submission-level access.
Its HTTP 202 `HINT_ACCEPTED` receipt means only a best-effort local scheduling
hint. Hints cannot open a round, extend a deadline, supply a value, or bypass
the normal evidence checks and request limits. Periodic acquisition remains
the fallback; hints may coalesce or be lost. Query the certified result to
determine outcome, not the webhook receipt.

The Java SDK exposes the same operation as `AppChainClient.wakeObservation(subscriptionId)`
on a chain-scoped client. It validates a bounded, chain-matched hint receipt;
successful return does not mean the subscription or round exists.

## Authenticated lifecycle diagnostics

Query `workflow` with empty parameters through the normal app-chain query API.
The canonical CBOR array is:

```text
[1, phase, paymentL1, subscriptionId, observationResult, releaseEffectId,
 effectResultEnvelope, releaseTransactionHash, settlementL1, candidateOverflow]
```

Evidence fields are byte strings containing their canonical encoding (empty
until available); the effect ID is its canonical UTF-8 representation.
Phase codes are 0 waiting payment, 1 waiting shipment, 2 release pending,
3 waiting settlement, 4 complete, 5 shipment unresolved, and 6 release failed.
The generic result retains both result ID and certificate digest. The full
payment and settlement L1 records and release effect identity remain in
authenticated application state; the host query includes state-root/height
context. This is an application view, not a new generic shared-feed protocol.
A query response alone is not a cryptographic proof. Across a trust boundary,
retrieve and verify the host's state proofs for the retained keys under
`shipment-reference/`: `phase`, `payment`, `subscription`, `observation`,
`release-id`, `release-result`, `release-tx`, and `settlement`.

For SDK `ProofVerifier.verifyCertified`, supply a `FinalityTrustContext` with
independently trusted genesis, state profile, member keys, quorum and the
height-specific consensus-context digest. Resolve that digest from trusted
chain configuration and membership history, not from the proof response.
API-8 certified headers include view, context, proposer and justification
digest. Signatures authenticate the domain-separated COMMIT digest; old
incomplete headers and signatures over a bare block hash are rejected.

An expired/non-`DELIVERED` observation never releases funds. A failed or
malformed effect receipt never completes settlement. Duplicate terminal
callbacks are ignored. The example does not retry release or accept a second
order automatically.

A stable settlement fact can arrive before the effect receipt. Up to 32
distinct qualifying transaction candidates are retained before the release
receipt. Excess candidates set an authenticated overflow flag and are ignored.
After the receipt identifies the exact release hash, unrelated deposits are
ignored without allocation and a matching stable deposit completes directly,
even if the earlier candidate set is full. If the required transaction was
dropped before the receipt and is not delivered again, the example may remain
waiting indefinitely and needs operator investigation; it never substitutes
an unrelated deposit. This deliberate bound is unsuitable for a high-volume
shared merchant address without a separately designed correlation/recovery
policy. Use dedicated addresses for the example.

## Validation boundaries

`ShipmentWorkflowReferenceTest` checks canonical L1-envelope callback handling,
causal ordering, duplicate suppression, incorrect payment/settlement rejection,
bounded overflow, replay state equality, and reconstructed-state queries.
Those are deterministic callback fixtures, not actual L1 admission, disk
crash recovery, or Preprod transactions. `ShipmentWorkflowRuntimeTest` runs
the ordinary host runtime with one and three nodes, real N2N diffusion and
quorum signatures, signed Merkle evidence, external effect claim/report, and
synthetic already-applied Cardano blocks. It verifies matching state roots,
profiles, capability manifests, finality certificates, SDK verification of
actual host state proofs, rejection of an incorrect context pin, and
disk restart parity. It does not submit real Cardano transactions or claim
power-loss recovery. Host tests separately exercise rejected Merkle paths and
periodic acquisition when wake hints are absent.

Exact packaged-distribution and Phase 5 Preprod qualification remain required
before graduation; the feature stays preview and disabled by default.
