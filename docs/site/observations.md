# Bring external evidence into your application

A state machine cannot read the outside world: every member must compute the
same result from the same inputs. **Observations** bring outside facts in as
ordered, checked inputs instead. The network call happens outside deterministic
execution, and every member applies the same accepted input.

**You'll learn:** the two kinds of observation, how a certified observation
round works, its outcomes and deadlines, and what the evidence does and does
not prove.

## Two kinds of observation

| | L1 observers | Generic certified observations |
|---|---|---|
| Brings in | Cardano facts: deposits to an address, transactions with a metadata label, epoch data | Facts from outside Cardano, such as a signed shipment report |
| Who checks it | Every member re-derives the fact from its own Cardano view | Members, or configured external reporters, sign matching reports; a certificate carries them |
| Arrives as | A framework message on `~l1/<observer-id>` | A certified result on `~obs/result/v1` |
| Your code receives it in | `context.l1Observations()` during `apply()` | `onObservationResult(...)` |
| Status | Bundled, **preview** | **Preview**, disabled by default |

Both are consensus inputs: every member must use the same observer settings and
the same genesis-pinned observation profile.

## L1 observers

Each member watches its own Cardano view. An observed fact is injected only
after it is `l1.stability-depth` blocks deep, and followers re-derive the claim
from their own L1 stream before they accept it, so a proposer cannot invent an
L1 fact.

```yaml
yano:
  app-chain:
    l1:
      stability-depth: 36          # observers require stability-depth > 0
    observation:
      l1-network-genesis-id: "<SHA-256 of the exact Shelley genesis file, 64 hex>"
    observers:
      deposits:
        type: address-deposit      # built in: lovelace paid to one address
        address: "addr_test1..."
      papers:
        type: metadata-label       # built in: transactions with a metadata label
        label: "20250712"
```

Read them like any messages, for example
`GET …/messages/by-topic/~l1%2Fdeposits`. Custom block and epoch observers are
plugins; see the
[user guide's L1 observations section](https://github.com/bloxbean/yano-x/blob/main/docs/APP_CHAIN_USER_GUIDE.md#55-l1-observations-reacting-to-l1-events).

## Generic certified observations (preview)

A generic observation asks members to fetch a fact from a configured source,
check its evidence, and agree on the value before the application sees it.
Yano owns the protocol, verification, certification, and callbacks; your state
machine only asks and reacts.

**Ask** during a deterministic callback, through the `AppObservationEmitter`
handed to the observation-aware `apply(context, writer, effects, observations)`:

```java
ObservationSubscriptionId id = observations.watch(ObservationIntent.oneShot(
        "shipment-delivery",            // definition id, pinned in the observation profile
        "shipment-workflow-reference-v1", // route; the example uses its machine id
        paymentTxHash,                  // public parameters, opaque to the host
        ObservationAnchorType.APP_HEIGHT,
        height + 1,                     // first due: strictly in the future
        height + 3,                     // report deadline
        height + 3));                   // subscription expiry
```

**React** in `onObservationResult(context, result, writer, effects, observations)`,
which every member calls when a certified result, or an expiry, is final.
`observations.cancel(id)` cancels a subscription.

### One round, step by step

This round comes from the shipment reference workflow, described below.

<!-- illustration: observation-round -->
1. **Payment lands.** A stable Cardano deposit reaches the state machine through
   an L1 observer.
2. **Watch.** The state machine asks for the delivery fact, bound to the payment
   transaction.
3. **Open a round.** At the due height, every member opens the same round with
   the same members, threshold, and deadline.
4. **Fetch.** Outside block execution, each member fetches the attestor's signed
   Merkle root and leaf.
5. **Check and report.** Each member verifies the evidence and gossips a signed
   report.
6. **Certify.** A member holding the threshold of matching reports assembles a
   certificate.
7. **Include.** The proposer adds the certified result to a block; followers
   verify the certificate before voting.
8. **Apply.** Every member calls `onObservationResult` with the value, and the
   state machine emits a payment effect.
9. **Release and settle.** A second stable Cardano deposit, matching the
   payment's transaction hash, completes the workflow.
<!-- /illustration -->

### Outcomes

| Status | Meaning |
|---|---|
| `VALUE` | A certified value was finalized. Under `exact-value-quorum-v1`, exactly the round's report threshold of reports agreed on it. |
| `EXPIRED` | No valid certificate was included in time. Members derive it from committed round state, without a certificate. |
| `CANCELLED` | The application cancelled the subscription. |
| `NO_RESULT` | Reserved. The current protocol never produces it, and missing reports can never derive it. |

A `VALUE` is only as good as its source: it says what the configured source
committed, not that it is true.

### Deadlines are heights, not seconds

Due and deadline values are logical anchors. With `APP_HEIGHT` they count app
ledger blocks, which are produced only when there are messages; an idle chain
does not age. `VERIFIED_L1_SLOT` scheduling needs `logicalTimeVersion=2` in the
observation profile and a positive `l1.stability-depth`.

- The first due anchor must be strictly in the future.
- A round stays open through its report deadline. After the deadline and a
  pinned inclusion grace, it becomes `EXPIRED`.
- Cadence zero is one-shot; a positive cadence makes the subscription recurring.

### When things go wrong

| Problem | Example | What happens |
|---|---|---|
| Local acquisition | DNS failure, timeout, rate limit | The member retries locally. It signs nothing about the failure. |
| Evidence or report | Bad signature, broken Merkle path, stale proof | The member rejects it locally. It is never turned into a "false" answer. |
| Round or policy | Too few or disagreeing reports, deadline passed | The round stays open until its deadline, then becomes `EXPIRED`. |

### Evidence and policies

| Policy or verifier | What it checks |
|---|---|
| `exact-value-quorum-v1` | Exactly the threshold of member reports agree on one value, source, and version. |
| `ed25519-merkle-inclusion-v1` with the `https-attested-merkle-v1` adapter | A leaf for this round sits under a Merkle root signed by an authorized attestor, with at most 20 sibling hashes. |
| `complete-source-median-v1` with external reporters | Each required source has exactly the required number of agreeing reports from configured reporter keys; the value is a lower median after outlier filtering. |

A wake hint, `POST …/observations/wake` with a subscription id, asks a node to
try sooner. Its `202 HINT_ACCEPTED` means only that: it cannot open a round,
extend a deadline, or supply a value.

The observation profile is genesis-pinned through
`observations.profile-cbor-hex`. Changing it on a retained chain is not an
in-place upgrade. Read Yano's
[certified observations guide](https://github.com/bloxbean/yano/blob/main/docs/appchain/observations.md)
before you create an application identity that uses them.

## The shipment reference workflow

The `shipment-workflow-reference-v1` stdlib plugin shows one order across four
authorities: a stable Cardano payment, a certified delivery observation, a
`cardano.payment` effect that pays the merchant, and a second stable Cardano
deposit that settles it. Its `workflow` query reports one phase:

| Phase | Name | Meaning |
|---|---|---|
| 0 | `WAITING_PAYMENT` | Waiting for a deposit of at least the minimum to the payment address |
| 1 | `WAITING_SHIPMENT` | Waiting for a certified delivery result |
| 2 | `RELEASE_PENDING` | Waiting for the payment effect's result |
| 3 | `WAITING_SETTLEMENT` | Waiting for a merchant deposit with the release transaction's hash |
| 4 | `COMPLETE` | Terminal: all four authorities agree |
| 5 | `SHIPMENT_UNRESOLVED` | Terminal: the result was `EXPIRED`, or a value other than `DELIVERED` |
| 6 | `RELEASE_FAILED` | Terminal: the payment effect did not confirm with a transaction hash |

An executor receipt alone never completes the workflow, and an expired or
non-`DELIVERED` observation never releases funds. The
[shipment reference](../appchain/shipment-observation-reference.md) gives the
configuration, pinned inputs, and validation boundaries. It is a single-order
teaching example, not a production escrow contract.

## What the evidence means

A valid source signature and inclusion proof establish what the authorized
attestor committed. They do not prove that a parcel physically arrived or
that a source is honest. The source trust policy remains part of the
application design. Generic observations do not replace Cardano validation.

The [ADA/USD reference](../appchain/ada-usd-observation-reference.md) demonstrates
multiple source reports and deterministic aggregation with synthetic fixtures.
It is a teaching example, not a production price oracle.

## Observations, roles, and effects

| Need | Capability |
| --- | --- |
| Bring supported external evidence into the application | Observations |
| Decide who may propose or approve a business action | [Domain roles and approvals](/tutorials/05-domain-role-approvals/) |
| Authorize work in an external system after a transition | [Effects](/concepts/effects/) |

Yano X provides application examples and domain behavior through the plugin
catalog. Observation profiles and source policies are pinned inputs; follow
the version-matched reference before creating a new application identity.
The examples are preview workflows, not production escrow or oracle products.
