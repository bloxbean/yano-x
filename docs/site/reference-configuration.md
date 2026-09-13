# Configuration reference

Yano X plugins declare their configuration as **typed metadata**, not as prose.
That metadata is what makes `appchain config validate` and `appchain explain`
work, and it is what this page is generated from at documentation build time.

Source:
`tooling/devtools/src/main/resources/appchain-dx/v1alpha1/appchain-first-party-metadata.json`.

:::caution[Read the scope column first]
`CONSENSUS_SHARED` means **every member must set the identical value**. A
mismatch diverges the state root and the chain stops finalizing — the same
failure as a mismatched state machine, and just as hard to spot after the fact.

Node-local values — ports, storage paths, credentials, executor placement — are
safe to differ, and generally *should* differ.
:::

## Change policy

| Policy | What it means |
|---|---|
| `GOVERNED_ACTIVATION` | Change only through the supported, approved activation procedure; all members must apply the same committed change. |
| `NEW_CHAIN_REQUIRED` | The value is part of chain identity. Changing it requires a new chain; a YAML edit or generic governance action cannot override that constraint. |

Anything genesis-selected — a commitment profile, a state encoding, a proof
subject descriptor, an enabled state index — falls into this class. See
[Consensus rules for plugins](/plugins/consensus-rules/).

## Coverage

Each property reports how well its constraints are known:

- **`FULL`** — the constraint is verified against the runtime.
- **`PARTIAL`** — derived, but not exhaustively verified. Treat third-party
  plugin metadata as `PARTIAL` unless Yano reports `FULL`, and verify the signed
  metadata and its runtime-manifest binding before trusting it.

## Properties by owner

<!-- catalog:configuration-start -->

### `yano-x-first-party/stdlib`

| Property | Type | Default | Allowed | Scope | Change policy | Description |
|---|---|---|---|---|---|---|
| `yano.app-chain.machines.approvals.on-approved-effect.enabled` | `BOOLEAN` | `false` | — | `CONSENSUS_SHARED` | `GOVERNED_ACTIVATION` | Emit one generic deterministic effect when a proposal is approved |
| `yano.app-chain.machines.approvals.on-approved-effect.expiry-blocks` | `LONG` | — | — | `CONSENSUS_SHARED` | `GOVERNED_ACTIVATION` | Generic on-approved effect expiry in app-chain blocks |
| `yano.app-chain.machines.approvals.on-approved-effect.gate` | `STRING` | — | `chain-default`, `app-final`, `l1-anchored`, `zk-settled` | `CONSENSUS_SHARED` | `GOVERNED_ACTIVATION` | Finality gate for the generic on-approved effect |
| `yano.app-chain.machines.approvals.on-approved-effect.type` | `STRING` | — | — | `CONSENSUS_SHARED` | `GOVERNED_ACTIVATION` | Exact executor routing type emitted after approval |
| `yano.app-chain.machines.balances.minter` | `STRING` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Optional 32-byte hexadecimal member identity allowed to mint balances |
| `yano.app-chain.machines.kv-registry.value-format` | `STRING` | `raw` | `raw`, `cbor`, `utf8` | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Encoding accepted by the packaged key-value registry state machine |

### `yano-x-first-party/composite`

| Property | Type | Default | Allowed | Scope | Change policy | Description |
|---|---|---|---|---|---|---|
| `yano.app-chain.machines.composite.evidence-capacity-per-block` | `INTEGER` | `8` | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Maximum evidence workflows evaluated in one block |
| `yano.app-chain.machines.composite.preset` | `STRING` | `evidence-v1-gated` | `evidence-v1`, `evidence-v1-gated`, `role-evidence-v1` | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Packaged deterministic composite profile preset |
| `yano.app-chain.machines.composite.profile-governance.max-epochs` | `INTEGER` | `1024` | — | `CONSENSUS_SHARED` | `GOVERNED_ACTIVATION` | Maximum retained composite profile epochs |
| `yano.app-chain.machines.composite.profile-governance.min-activation-lag` | `INTEGER` | `20` | — | `CONSENSUS_SHARED` | `GOVERNED_ACTIVATION` | Minimum blocks between profile approval and activation |
| `yano.app-chain.machines.composite.profile-governance.proposal-ttl-blocks` | `INTEGER` | `600` | — | `CONSENSUS_SHARED` | `GOVERNED_ACTIVATION` | Lifetime of a composite profile proposal |
| `yano.app-chain.machines.composite.profile-mode` | `STRING` | `fixed` | `fixed`, `governed` | `CONSENSUS_SHARED` | `GOVERNED_ACTIVATION` | Fixed or governed composite profile evolution |
| `yano.app-chain.machines.composite.roles.maximum-mutation-lifetime-blocks` | `INTEGER` | `1000` | — | `CONSENSUS_SHARED` | `GOVERNED_ACTIVATION` | Maximum lifetime of role-governance mutations |

### `yano-x-first-party/eutxo-bridge-cardano`

| Property | Type | Default | Allowed | Scope | Change policy | Description |
|---|---|---|---|---|---|---|
| `yano.app-chain.machines.eutxo.bridge.confirmation-observer-id` | `STRING` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Configured exact stable withdrawal-confirmation observer instance |
| `yano.app-chain.machines.eutxo.bridge.epoch` | `LONG` | `0` | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Bridge migration epoch bound into every withdrawal claim |
| `yano.app-chain.machines.eutxo.bridge.max-pending-withdrawals` | `INTEGER` | `1024` | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Maximum committed pending withdrawal claims |
| `yano.app-chain.machines.eutxo.bridge.max-withdrawal-lovelace` | `LONG` | `45000000000000000` | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Hard per-claim lovelace withdrawal limit |
| `yano.app-chain.machines.eutxo.bridge.observer-id` | `STRING` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Configured exact vault-deposit observer instance |
| `yano.app-chain.machines.eutxo.bridge.vault-address` | `STRING` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Exact Cardano vault address accepted by the EUTxO ledger |
| `yano.app-chain.machines.eutxo.bridge.vault-script-hash` | `STRING` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Pinned 28-byte bridge vault script hash |
| `yano.app-chain.machines.eutxo.bridge.withdrawal-address` | `STRING` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | L2 sink address whose signed outputs become irrevocable withdrawal claims |
| `yano.app-chain.machines.eutxo.bridge.withdrawals-paused` | `BOOLEAN` | `false` | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Fail-closed consensus switch for new withdrawal claims |
| `yano.app-chain.observers.bridge-deposits.type` | `STRING` | `eutxo-vault-deposit-v1` | `eutxo-vault-deposit-v1` | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Exact accepted-vault deposit observer type |
| `yano.app-chain.observers.bridge-withdrawals.type` | `STRING` | `eutxo-withdrawal-confirmation-v1` | `eutxo-withdrawal-confirmation-v1` | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Exact stable Cardano withdrawal-confirmation observer type |

### `yano-x-first-party/eutxo-ledger`

| Property | Type | Default | Allowed | Scope | Change policy | Description |
|---|---|---|---|---|---|---|
| `yano.app-chain.machines.eutxo.bridge.params.fallback-delay-slots` | `LONG` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Genesis fallback arming delay in L1 slots (governed thereafter) (ADR-UTXO-009, v3 profiles only) |
| `yano.app-chain.machines.eutxo.bridge.params.fee-basis-points` | `LONG` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Genesis basis-point executor bounty (governed thereafter) (ADR-UTXO-009, v3 profiles only) |
| `yano.app-chain.machines.eutxo.bridge.params.fee-flat-lovelace` | `LONG` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Genesis flat executor bounty (governed thereafter) (ADR-UTXO-009, v3 profiles only) |
| `yano.app-chain.machines.eutxo.bridge.params.min-withdrawal-lovelace` | `LONG` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Genesis minimum withdrawal payout (governed thereafter) (ADR-UTXO-009, v3 profiles only) |
| `yano.app-chain.machines.eutxo.bridge.params.rooting-blocks` | `LONG` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Genesis rooting cadence in L2 blocks (governed thereafter) (ADR-UTXO-009, v3 profiles only) |
| `yano.app-chain.machines.eutxo.bridge.params.rooting-seconds` | `LONG` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Genesis rooting cadence in seconds (governed thereafter) (ADR-UTXO-009, v3 profiles only) |
| `yano.app-chain.machines.eutxo.bridge.params.soft-batch-cap` | `LONG` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Genesis soft settlement batch cap (governed thereafter) (ADR-UTXO-009, v3 profiles only) |
| `yano.app-chain.machines.eutxo.expected-profile-digest` | `STRING` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Expected digest of every consensus-relevant EUTxO profile field |
| `yano.app-chain.machines.eutxo.genesis.address` | `STRING` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Testnet address receiving the first no-real-funds genesis allocation |
| `yano.app-chain.machines.eutxo.genesis.inline-datum-hex` | `STRING` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Optional canonical lowercase Plutus datum CBOR for genesis output zero |
| `yano.app-chain.machines.eutxo.genesis.l2-address` | `STRING` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Key-controlled L2 address registered without creating virtual funds |
| `yano.app-chain.machines.eutxo.genesis.l2-key-epoch` | `LONG` | `1` | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Initial consensus key epoch for the registered L2 Jubjub key |
| `yano.app-chain.machines.eutxo.genesis.l2-public-key` | `STRING` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Lowercase 32-byte Jubjub public key registered for the initial L2 address |
| `yano.app-chain.machines.eutxo.genesis.lovelace` | `LONG` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Positive no-real-funds lovelace allocated to genesis output zero |
| `yano.app-chain.machines.eutxo.profile` | `STRING` | `yano-eutxo-v2-plutus-v3` | `yano-eutxo-v1`, `yano-eutxo-v2-plutus-v3`, `yano-eutxo-v3-bridge-settlement` | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Pinned deterministic EUTxO ledger profile |

### `yano-x-first-party/evidence-registry`

| Property | Type | Default | Allowed | Scope | Change policy | Description |
|---|---|---|---|---|---|---|
| `yano.app-chain.machines.evidence-registry.issuers` | `STRING_LIST` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Member public keys allowed to issue evidence |
| `yano.app-chain.machines.evidence-registry.notification-expiry-blocks` | `LONG` | `0` | — | `CONSENSUS_SHARED` | `GOVERNED_ACTIVATION` | Evidence notification effect expiry in app-chain blocks |
| `yano.app-chain.machines.evidence-registry.notify-senders` | `STRING_LIST` | — | — | `CONSENSUS_SHARED` | `NEW_CHAIN_REQUIRED` | Member public keys allowed to emit notifications |
| `yano.app-chain.machines.evidence-registry.storage-expiry-blocks` | `LONG` | `0` | — | `CONSENSUS_SHARED` | `GOVERNED_ACTIVATION` | Evidence storage effect expiry in app-chain blocks |
| `yano.app-chain.machines.evidence-registry.storage-gate` | `STRING` | `app-final` | `app-final`, `l1-anchored`, `app_final`, `l1_anchored` | `CONSENSUS_SHARED` | `GOVERNED_ACTIVATION` | Finality gate required before evidence storage execution |

<!-- catalog:configuration-end -->

## Host configuration

The properties above are the ones **Yano X plugins** own. Core app-chain
configuration — chain id, members, threshold, sequencing, block cadence,
storage, API authentication, anchoring, effects caps, retention — belongs to the
Yano host and is documented in
[section 7 of the app-chain user guide](https://github.com/bloxbean/yano-x/blob/main/docs/APP_CHAIN_USER_GUIDE.md).

Frequently needed host values:

| Property | Notes |
|---|---|
| `yano.app-chain.chain-id` | 1–128 valid UTF-8 bytes. One group of participants = one chain id. |
| `yano.app-chain.state-machine` | The selected machine or profile id. Consensus-shared. |
| `yano.plugins.directory` | Where bundles are loaded from. **Not** `yaci.plugins.directory`. |
| `yano.app-chain.effects.*` | Enablement and deterministic caps. All consensus-shared — see [Effects](/concepts/effects/). |
| `yano.app-chain.anchor.*` | Anchor leader only — see [Cardano anchoring](/concepts/anchoring/). |
| `yano.app-chain.message.enforce-sender-seq` | Consensus-visible when on; all members must agree. |

## Working with configuration safely

```bash
# Validate a project's blueprint and its resolved configuration.
./yano.sh appchain config validate --mode project <project>

# Redacted effective configuration, and per-property explanation.
./yano.sh appchain config explain <project> --key <property>

# Compare a project against running nodes.
./yano.sh appchain drift <project> --peer <node-identity-url>
```

Edit only `appchain.yaml`; generated runtime files are derived output.

### Secrets

Five secret classes, five blast radii — keep them separate:

1. member signing keys,
2. business-actor keys,
3. API keys,
4. effect and connector credentials, and
5. anchor wallet funds.

Never place a credential in consensus-shared configuration or in a replicated
effect payload — both are visible to every member and provable to anyone holding
a proof. Use node-local overlays or a secret provider, and refer to secrets by
documented environment-variable or provider names only.

For private per-node connector configuration, the cluster launcher supports a
strictly validated overlay directory (`YANO_CLUSTER_NODE_CONFIG_DIR`) with
`chmod 600` files at a fixed precedence ordinal — see the
[cluster launcher README](https://github.com/bloxbean/yano-x/blob/main/scripts/appchain-cluster/README.md).
