# ADR-043 — Late activation of L1 epoch observers without retained-chain reset

- **Status:** Proposed; design only
- **Date:** 2026-09-05
- **Owners:** Yano app-chain host and Yano X Cardano History product and deployment tooling
- **Scope:** Adding an epoch-level L1 dataset to an already operating deployment when the operator
  accepts coverage beginning at a future epoch boundary and does not require historical backfill
- **Related:**
  [ADR-028 historical L1 state](app-layer/028-l1-historical-ledger-state-attestation-chain.md),
  [ADR-035 Cardano History](app-layer/035-cardano-history-product-console-and-distribution.md),
  [ADR-039 deployment automation](039-geographically-distributed-deployment-automation.md),
  [ADR-041 observation reliability](041-trust-preserving-l1-observation-delivery-and-consensus-recovery.md),
  and [ADR-042 observation lifecycle](042-l1-observation-and-eutxo-settlement-lifecycle.md)

## 1. Context

Cardano History currently pins its state-machine preset, component set, observer identities,
authenticated-snapshot series, limits, commitment profile, format fingerprint, and genesis ID into
one chain's consensus identity. Changing from `params-only-v1` to `params-stake-v1`,
`params-governance-v1`, or `full-v1` therefore changes the chain's capability manifest and
consensus profile. The runtime correctly rejects that configuration against retained state.

A full L1 and app-chain reset is appropriate when an operator requires complete history from the
first supported Cardano epoch. It is unnecessarily destructive when the operator accepts a clear
coverage boundary such as “stake history is available from epoch 350 onward.” In that case the
existing L1 chainstate already has everything needed to observe future epoch transitions.

The design must not mutate an already certified chain's meaning, silently present partial history
as complete, trust one node to choose an activation point, or turn deployment automation into an
L1 oracle.

## 2. Decision

Late activation will initially be **additive**. Deployment tooling will create a new, versioned
Cardano History chain identity for the expanded observer set and leave every existing chain and its
retained state untouched.

For example:

```text
cardano-history-chain       params only, original genesis, retained and readable
cardano-history-full-v2     params + stake + governance, new genesis, future coverage
```

The new chain will declare:

1. a new chain ID and genesis ID;
2. the complete immutable observer/component preset;
3. any required authenticated-snapshot series and bounded message/block limits;
4. an explicit consensus-selected `activationEpoch`; and
5. a coverage policy of `forward-only`.

All validators receive the same configuration through a coordinated deployment. The new chain may
finalize ordinary genesis state before activation, but it must not emit an epoch observation below
`activationEpoch`. Its first dataset is the first natural completed-epoch dataset produced at or
after that boundary according to the selected observer's existing semantics.

No L1 reset or replay is required. Existing Cardano synchronization continues from its current
point. Existing app chains, anchors, finality certificates, roots, proofs, and indexes remain
valid. Only the new chain starts empty and obtains a new SCRIPT anchor identity if anchoring is
enabled.

## 3. Coverage is committed data

Forward-only activation must never look like complete historical coverage. The new chain commits a
versioned coverage record containing at least:

```text
policy                 = forward-only
configuredEpoch        = activationEpoch
firstObservedEpoch     = first dataset actually finalized, or absent
components             = exact observer/component identities
```

The Cardano History status, domain API, client, CLI, proof bundles, and console expose this record.
Queries below `firstObservedEpoch` return a typed `OUTSIDE_COVERAGE` result rather than `not found`.
“Latest” endpoints remain valid but also return the coverage boundary. Snapshot descriptors keep
their existing per-epoch source-boundary semantics.

If an activation boundary is missed because the required canonical L1 snapshot is unavailable,
the chain records a gap and waits for the next valid boundary. It does not approximate data, copy
another node's local snapshot, or move `firstObservedEpoch` backward.

## 4. Deployment contract

The deployment manifest will support an additive Cardano History generation rather than rewriting
the existing chain in place. A future schema may use a structure equivalent to:

```yaml
application:
  cardanoHistoryGenerations:
    - chainId: cardano-history-full-v2
      genesisId: <64-lower-case-hex>
      activationEpoch: 350
      coverage: forward-only
      l1Observations:
        - l1-epoch-params-v1
        - l1-epoch-stake-v1
        - l1-epoch-governance-v1
```

Render and apply must fail closed unless:

- the new chain ID and genesis ID are unused;
- `activationEpoch` is later than the deployment's observed stable epoch by a documented safety
  margin;
- every validator has the required provider and bundle identities;
- the full observer selection, limits, snapshot series, and source-network genesis identity are
  lock-pinned; and
- the existing chain configuration is byte-for-byte/identity-equivalent to its retained lock.

Apply performs a coordinated restart because runtime plugin selection is startup configuration.
It does not delete any store. If the new chain fails before producing useful state, a future
per-chain reset operation may remove only that new chain after exact chain-ID confirmation.

## 5. Why the existing chain is not changed

An observer is not a passive local indexer. Its reserved `~l1/*` messages become threshold-finalized
state transitions and alter the application's capability manifest, state root, proof subjects, and
potentially block sizing. Enabling it under an existing genesis without a protocol transition would
let the same chain identity mean different things to different validators or at different times.

The runtime must therefore continue rejecting an ad hoc observer or preset change against retained
state. A deployment flag, restart, operator signature, or database edit is not sufficient authority
to redefine an existing chain.

## 6. Deferred in-place evolution

A future protocol may support an in-place upgrade, but it is separate work. It would require a
threshold/governance-certified capability-manifest transition with:

- a unique activation height and L1 epoch;
- old and new manifest digests committed before activation;
- deterministic state migration and rollback behavior;
- rules for blocks and catch-up across the transition;
- proof and anchor semantics spanning both manifest eras; and
- downgrade, partial-upgrade, replay, crash, and byzantine-validator tests.

Until that protocol is designed and implemented in Yano core, additive versioned chains are the
only supported no-reset path.

## 7. Consequences

### Positive

- Existing certified history and unrelated app chains are preserved.
- No Cardano resynchronization or historical source-snapshot retention is needed for future-only
  coverage.
- The new dataset has an unambiguous, committed start point.
- Failure isolation and rollback are limited to the new chain identity.

### Costs

- Clients may need to select between historical generations.
- A new chain requires its own genesis, anchor identity, monitoring, and storage.
- Cross-generation query aggregation is a client/product concern and cannot imply one continuous
  proof history.
- Deployment and catalog tooling must support additive chain generations and exact per-chain reset.

## 8. Acceptance criteria for future implementation

This ADR remains design-only until tests demonstrate:

1. adding the new generation changes no existing chain height, root, identity, anchor, or proof;
2. all validators agree on the activation epoch, first observed epoch, manifest digest, and roots;
3. no observation below the activation boundary can finalize;
4. missed source data yields an explicit gap rather than partial or fabricated completeness;
5. restart, crash recovery, catch-up, rollback, and proposer rotation preserve the boundary;
6. required authenticated snapshots are generated and recoverable for selected large datasets;
7. deployment apply performs no retained-store deletion; and
8. per-chain recovery cannot target L1 chainstate or another app chain.
