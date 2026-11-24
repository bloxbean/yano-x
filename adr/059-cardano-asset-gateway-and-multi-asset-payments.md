# ADR-059 — Cardano asset gateway, with a multi-asset payment ledger as its first consumer

- **Status:** Proposed (2026-11-24, r3). D8 decided by the maintainer in r3. Design only; nothing here is implemented.
- **Issues:**
  - [#110](https://github.com/bloxbean/yano-x/issues/110): generic Cardano asset ingress and
    settlement framework.
  - [#109](https://github.com/bloxbean/yano-x/issues/109): multi-asset, account-based payment app
    ledger backed by Cardano L1.

  This ADR covers milestone 1 of both issues ("ADR and inventory / threat model").
- **Evidence base:** `origin/main` at `00274933` (2026-11-17). Every Yano X path and line below
  was read at that commit.
- **Related (Yano X):**
  - ADR-041: trust-preserving L1 observation delivery
  - ADR-042: L1 observation and EUTxO settlement lifecycle
  - app-layer/utxo/001: EUTxO ledger and Cardano bridge
  - app-layer/utxo/009: claim settlement and vault conditions
  - app-layer/011: plugin architecture
  - app-layer/031 and 031.1: composable state machines and event bindings
  - app-layer/031.3: policy plane
  - app-layer/038: topic policies and session keys
  - ADR-057: framework extraction
  - ADR-058: execution runtime SPI (open PR #107)
- **Related (Yano core):**
  - app-layer/039: topic registry, which lists CIP-8 as a v1 non-goal at l.166
  - ADR-063: standalone application ledger and host API
- **Fulfils:** ADR app-layer/utxo/001 l.368-370 says: "A future generic L1 transaction co-signing
  SPI is permitted only through a separate ADR after the bridge implementation demonstrates a
  reusable need." This ADR is that separate ADR, and it covers settlement as a whole.
- **Risk:** high. The design moves real Cardano value, adds on-chain validators, and adds a
  consensus path that verifies end-user wallet signatures.

## 1. Context

Issue #110 asks for one reusable layer that brings Cardano assets into an app ledger and pays
them back out, so application authors write business logic instead of another bridge. Issue
#109 asks for the first account-based application on that layer: users deposit ADA or
registered native tokens, transfer them with wallet-signed or session-signed intents, and
withdraw part or all of a balance while the ledger keeps running.

Yano X already has one bridge: the EUTxO Cardano bridge (`ledgers/eutxo/bridge-cardano`,
`ledgers/eutxo/bridge-onchain`). This ADR starts from what that code actually does, not from
the component list in #110.

### 1.1 What exists today

Reusable framework pieces (all in Yano X since ADR-057):

| Piece | Where | Relevance |
|---|---|---|
| Block-scoped L1 observers | `L1ObserverProvider` / `L1Observer` (`framework/api/.../appchain/l1view/`). `observe(slot, blockHash, Block)` returns `List<L1Observation>` (`L1Observer.java:42`) | Ingress and confirmation observation. Every validator recomputes; the proposer injects stability-deep facts as `~l1/<id>` messages; followers verify (`L1ObservationService.java:333-370`). The observer sees only the parsed block, not resolved inputs. |
| Observation identity and order | `L1Observation(observerId, anchor, eventOrdinal, slot, blockHash, claim)`. `key()` includes `eventOrdinal` (`L1Observation.java:43, 224-226`). Canonical order is slot, block hash, observer id, anchor, ordinal (`L1Observation.java:228-256`), not position in the block | One transaction may carry several observations. The order of facts within a block is not L1 transaction order. |
| Consensus-pinned observer identity | `L1ObserverProvider.consensusIdentity(...)` (`L1ObserverProvider.java:26`) | Claim schema and ABI changes fail startup instead of forking silently. |
| Durable observation journal | `L1ObservationJournal`, wired at `AppChainSubsystem.java:1115` | Delivery metadata. A deep rollback below a finalized observation quarantines the node; it does not undo state. ADR-041 is still marked Proposed even though the journal exists. |
| Stability depth | `yano.app-chain.l1.stability-depth`. Observers refuse to start when it is 0 (`AppChainSubsystem.java:1124-1130`) | One depth per chain. No per-observer depth. |
| Deterministic effects | `AppEffectEmitter.emit(EffectIntent)`; executors through `AppEffectExecutorFactory` (`framework/cardano-api/.../effects/AppEffectExecutorFactory.java:17`) | Outbox for settlement. Delivery is at least once, so the executor must be idempotent on `idHash`. |
| Effect results | `~fx/result`. The check is at `FxKernel.java:288`; the rule (any member, or a designated signer list) is in `EffectsSettings.java:56-58` | One member's attestation. It must never confirm value movement. |
| Script anchoring | `ScriptAnchorService` (`framework/cardano/.../ScriptAnchorService.java:80-101`) advances a thread-NFT UTxO under `anchor.ak` (`framework/cardano-onchain/aiken/validators/anchor.ak`) | The anchor is an L1-visible state root. The rules that matter here: a threshold of the **input** datum's members must sign (`anchor.ak:91-99`); height increases strictly; identity fields are preserved. Followers adopt the anchor identity from the first sign request (`ScriptAnchorService.adoptOrMatchIdentity`, l.938). |
| On-chain proof library | `sdk/proof-onchain`: `MpfAnchorLib.acceptedAnchor(...)` returns `AnchorRoot(stateRoot)` only (`MpfAnchorLib.java:18`). `MpfOnChainVerifier.verifyInclusion(proof, root)` takes key and value from the proof (`MpfOnChainVerifier.java:29-52`) | A script can authenticate the anchored root and verify inclusion. Binding the key and value is the caller's job (`sdk/proof-onchain/README.md`). Measured cost of one inclusion with two folds: 445,952,815 CPU and 1,462,219 memory units (`README.md:9`). |
| State proof retention | Proof pruning keeps proofs from a height derived from the tip (`AppLedgerRuntime.java:4072-4082`) | A proof at the latest L1 anchor's height can be pruned while that anchor is still the one in use. |
| Delegated session keys | Topic registry: `TopicWire.Delegation(...)` (`TopicWire.java:371`); `WriteStatement(..., delegationHash)` (l.392); single-use statement entries; revocation in `TopicRegistryKernel` (l.325) | Ed25519 session keys scoped to a topic namespace, with expiry and revocation, but no amount limits. |
| Atomic multi-machine transitions | Java `CompositeWorkflow` and declarative `EventBindingWorkflow` (ADR-031.1). Component state keys are `CompositeCommitmentV1.componentKey(componentId, localKey)` (`CompositeCommitmentV1.java:50-66`) | Two machines can commit in one block transaction. Inside a composite, physical keys are namespaced per component. |
| Block time | `AppBlock.timestamp` is the proposer's clock. Voters reject a timestamp more than `maxForwardSkewMs` ahead (`AppChainEngine.java:1269`) | This is the consensus clock for intent expiry. |
| Plugin catalog | 15 contribution kinds: 4 core plus 11 from the Yano X framework (`AppLedgerContributionKindsTest.java:19-46`). `effect-executor` comes from `framework/cardano-api` | Activation is always explicit through configuration. |

### 1.2 The EUTxO bridge, measured

**Wired at runtime**
- Three observers: `eutxo-vault-deposit-v1`, `eutxo-withdrawal-confirmation-v1` and
  `eutxo-batch-withdrawal-confirmation-v1`.
- The `eutxo-settlement` effect executor. `EutxoSettlementExecutorFactory` builds
  `QuickTxSettlePipeline`, `SettlementCosignService` (which implements the nested
  `BatchSettlementExecutor.ThresholdCosigner`) and `PipelinedSettlementExecutor`.
- A domain API.
- Manifest: `ledgers/eutxo/bridge-cardano/src/main/resources/META-INF/yano/plugins/org.yanoproject.x.eutxo.bridge.cardano.json`.

**Never constructed by the runtime**
- `WithdrawalCoordinator`, `FileSettlementJournal`, `HttpExternalSettlementSigner`,
  `BatchSettlementExecutor` (the class itself), `VaultWithdrawalTransactionBuilder`,
  `DepositAcceptanceBuilder`, `BridgeRollbackGuard` and `BridgeDoctor`. Of these, only tests and
  READMEs reference them.
- `ProofWithdrawalTransactionBuilder` is also never constructed, but it has a main-source caller,
  `ProofWithdrawalRelayClient.prepare` (l.31-39). That client is itself unwired.

**ADA only**
- The deposit observer rejects outputs that carry a native asset
  (`AcceptedVaultDepositObserver.java:210-226`).
- The confirmation observers reject native assets (`BatchWithdrawalConfirmationObserver.java:188-203`).
- The co-signer's custody check rejects multi-asset bodies (`SettlementClaimsView.java:124-126`).
- The vault validator checks lovelace equality positionally (`SettlementVaultValidator.java:229-233, 267-278`).
- ADR-042 §15.2 item 5 makes ADA-only a property of the initial settlement profile.

**Settlement journal on the wired path**
- It is in memory (`EutxoSettlementExecutorFactory.java:85, 96-99`).
- Restart safety comes from re-reading committed state and the on-chain nullifier.

**Protocol-neutral parts**
- `SettlementCosignService` and `SettlementCosigner`. The custody check is passed in as a
  `Predicate<byte[]>`.
- `CardanoSettlementBackend`, `NodeSettlementBackend` and `CclNodeAdapters`.
- `ValidTransactions`, `BridgeDatumPreflight`, `ObserverConsensusIdentity` and `BridgeRollbackGuard`.
- The journals. `FileSettlementJournal` uses claim-id keys with a 32-byte-hex rule;
  `BatchSettlementJournal` uses effect keys.
- The on-chain MPF library.

**EUTxO-bound parts**
- Every claim record and datum in `ledgers/eutxo/contracts`.
- The claim-id derivation.
- `SettlementClaimsView`.
- The ledger's `importDeposit` and `confirmWithdrawal`.

**Vault (ADR app-layer/utxo/009, implemented)**
- `SettlementVaultValidator` has two paths:
  - a threshold-signed Settle path;
  - a permissionless Exit path, armed only after a fallback delay. It proves claims against a
    bridge-specific root thread.
- Payout claim ids must equal, in order, the ids inserted into exactly one paired nullifier shard
  (l.157-161, 339-359).
- The continuation output carries a batch marker datum.
- Nullifiers live in 16 shard threads under a one-shot policy (`ShardThreadPolicy`).
- ADR-UTXO-009 §13 records two gaps:
  - the fallback-delay floor is not enforced on chain;
  - with `updatedAtMillis = 0`, Exit is armed from genesis.
- No runtime service advances the bridge root thread.

### 1.3 What does not exist

- **A native-asset or multi-asset observer.** `address-deposit` reports lovelace only.
- **A native-asset payout executor.** `cardano.payment` (`connectors/effects-cardano`) builds ADA
  outputs only.
- **A multi-asset ledger.** Stdlib `balances` is a single-unit account ledger. It has `MINT`, has
  no burn, and spending is bound to the member who sends the envelope (`BalancesContract.java:16-28, 40-44`).
- **CIP-8 / COSE_Sign1 verification**, in either repository.
  - `EutxoL2SessionKey.fromCip8Signature` derives a key from a signature; it does not verify one.
  - cardano-client-lib 0.8.0-pre7 ships `cardano-client-cip8` and `cardano-client-cip30`.
    `CIP30DataSigner.verify(DataSignature)` checks the signature and the address. It takes no
    expected account, domain, nonce or expiry.
- **A general end-user account-key SPI.** Users who are not members authorize through topic
  writer proofs, ADR-019 actor keys, or application-specific keys. These always arrive inside a
  member-signed envelope.
- **In-script verification of the app ledger's `FinalityCert`.**
- **A public write class for domain APIs.** `DomainApiAccess` offers `READ`, `PRIVILEGED` and the
  reserved `INTERNAL`. Message submit needs `SUBMIT` (`AppChainResource.java:142`).

## 2. Goals and non-goals

**Goals**

1. One optional, plugin-packaged Cardano asset gateway for ADA and native fungible assets. It
   covers deposit observation, settlement execution, payout confirmation and reconciliation.
2. A small deterministic contract that any state machine can consume, whatever its execution
   model: accounts, virtual UTxOs or pools.
3. A reference custody profile in which any relayer can pay out a certified, matured claim
   without custody keys, with an L1-visible brake.
4. Reusable Cardano wallet authorization: CIP-8 through CIP-30 `signData`, plus owner-authorized
   session keys.
5. The #109 payment ledger as the first consumer. A named second consumer that does not use
   accounts (D17a) validates the contract before it is frozen. The EUTxO bridge adopts shared
   helpers with no change in behaviour.

**Non-goals**

These follow #110 §14 and #109's non-goals:
- trustless or unilateral exit;
- a universal vault script;
- liquidity shared across applications;
- swaps, issuance or lending;
- a DEX product;
- changes to EUTxO bridge semantics or script hashes;
- a new contribution kind or host SPI in v1;
- production funds before §12's gates close.

**Scope reductions, stated explicitly:**
- Owner recovery after the owner loses the wallet key (owner rotation itself is in D14a).
- Automatic refunds of uncreditable deposits (D6, Q3).
- Per-asset confirmation depth (D5).
- Consolidation without a payout (Q8).

## 3. Threat model and trust assumptions

### Parties

**Cardano consensus**
- Assumed honest.
- Treated as final beyond the chain's stability depth.
- Deeper rollbacks are incidents (D15b).

**App ledger members** (n keys, threshold t)
- They certify blocks and advance the anchored root.
- A colluding threshold can anchor a fabricated root, and once claims mature (D8a) it can
  authorize payouts from the reference vault. This is the custody assumption; it restates
  ADR-UTXO-009 §7.2 and §13.3.
- The quorum can also censor users and can engage the brake.
- A minority below threshold cannot move funds or forge facts.
- Liveness follows the framework's consensus threshold. With the illustrative 3-of-4, one faulty
  member is tolerated.

**Liveness-only parties.** These can delay or stop progress but have no authority over facts or
funds:
- the scheduled proposer and the anchor leader (the node with `anchor.enabled`), per ADR-042 §3;
- the settlement relayer, per ADR-UTXO-009 §11.3;
- the application gateway, which relays user intents.

**End users**
- Untrusted.
- They sign intents with wallet keys or session keys.
- A stolen session key is limited by its grant until the grant is revoked.

**Anyone on L1**
- Untrusted.
- They can author any output, datum or token, and any payment to a vault address.
- They can submit **any `Exit` transaction the vault script accepts**. The `Exit` path has no
  co-signer, so its safety rests on the script (D8) and on the consumer accepting only
  script-authenticated facts (D10).
- A `Settle` transaction needs the threshold's signatures. Its safety rests on the members' body
  check (D9) plus the shared script rules V4-V8.

### Secrets

- member signing keys and anchor co-signing keys;
- relayer fee wallets, which hold no custody authority;
- user wallet keys, which never leave the wallet;
- session private keys, which stay in the client as non-extractable WebCrypto keys where
  available.

### Trust boundaries

1. **L1 → observer → finalized `~l1/*` message.** Operators never author facts (ADR-042).
2. **User intent → state machine.** Only the state machine decides.
3. **Finalized state → anchored root → L1 vault script.** Only the script releases value.
4. **Local executor → L1 transaction.** The executor has liveness only.

### Trust labels (D18)

The reference profile is labelled by its settlement mode (D8e).

`FEDERATED_COSIGNED_WITH_PERMISSIONLESS_EXIT` is the default:
- payouts are co-signed by the threshold;
- once a reserved, anchored claim is older than `exitDelayBlocks`, anyone can pay it.

`FEDERATED_CUSTODY_PERMISSIONLESS_RELAY` applies to `PROOF_ONLY`:
- anyone can relay a certified, matured claim.

Both labels also carry:
- exit for a claim the quorum never reserved or never anchored is not provided;
- if the threshold's keys are lost, the anchor freezes and so does all custody (§11).

## 4. Pinned references

| Ref | Version | Used for |
|---|---|---|
| CIP-0008 Message Signing | cardano-foundation/CIPs `86b89208` (2026-06-04), Status: Active | COSE_Sign1 layout and `Sig_structure` (l.103-120). The `hashed` unprotected header, which l.253-262 says MUST be present. |
| CIP-0030 dApp-Wallet Web Bridge | cardano-foundation/CIPs `695efa05` (2026-10-13), Status: Active | `api.signData` profile (l.350-368, quoted below). `api.getNetworkId` (l.273-277). |
| RFC 8152 (COSE) | §§3-4.4, as cited by CIP-0008 | `Sig_structure` is signed over the received protected-header bytes. |
| RFC 8949 | §4.2.1 | Core deterministic encoding: map keys are sorted by bytewise order of their encodings. |
| Conway ledger CDDL | IntersectMBO/cardano-ledger `9ef91412` (2026-07-19), `eras/conway/impl/cddl/data/conway.cddl` | `asset_name = bytes .size (0 .. 32)` (l.52); `value = coin / [coin, multiasset<positive_coin>]` (l.174); `multiasset<a0> = {* policy_id => {+ asset_name => a0}}` (l.178); `positive_coin = 1 .. max_word64` (l.193). The value grammar puts no bound on the number of assets. |
| cardano-client-lib | 0.8.0-pre7 (`gradle/libs.versions.toml:4`) | A differential oracle for the cryptographic step only (D13). |

CIP-0030 `signData`, from l.354-368:

- **Key selection:** "The payment key from `addr` will be used for base, enterprise and pointer
  addresses to determine the EdDSA25519 key used. The staking key will be used for reward
  addresses."
- **`COSE_Sign1` headers:**
  - `alg` (1) must be `EdDSA` (-8);
  - `kid` is optional;
  - `"address"` must be the raw address bytes.
- **Payload:** "The payload is not hashed and no `external_aad` is used."
- **`COSE_Key`:** must have `kty` OKP (1), `alg` EdDSA (-8), `crv` Ed25519 (6) and `x` (-2), the
  public key.

**Conflict between the two CIPs.** CIP-0008 requires the `hashed` header and prescribes hashing
in two hardware-wallet cases. CIP-0030 says the payload is not hashed. D13 accepts an absent
`hashed` header as an interoperability exception, rejects `hashed: true`, and leaves the
hardware-wallet case as Q5.

## 5. Decisions

### D1. An optional capability built only from existing contribution kinds

The gateway is a set of libraries plus one runtime bundle. The bundle contributes only existing
kinds:
- `l1-observer` (CONSENSUS);
- `effect-executor` (PRIVILEGED_LOCAL);
- `domain-api` (PRIVILEGED_LOCAL), offering read routes and unsigned-transaction builders.

The consuming application ships its own `app-state-machine`.

No new contribution kind is introduced, and no new host SPI. Framework additions are listed in
§7: an additive `MpfAnchorLib` result, a proof-retention floor, and a membership handoff gate.
Each of them needs its own API-level review.

Nothing activates because a bundle is installed:
- observers activate through `observers.<id>.type`;
- the executor through `effects.executors.<scheme>.*`;
- the machine through the chain's recipe (ADR app-layer/011 P4/P5).

*Alternatives considered:*
- A new `asset-gateway` contribution kind. Rejected: it needs an API-level bump and a second
  activation path.
- Building the gateway into the framework. Rejected by #110 §3.1 and by the AGENTS.md
  catalog-boundary rule.

### D2. Modules and dependency direction

Names are provisional and are settled in M1 (Q11).

| Module | Kind | Contents |
|---|---|---|
| `capabilities/asset-gateway-contracts` | pure library | Asset and value types, identifiers, CDDL and canonical codecs, deposit/claim/payout/receipt records, the accounting helper (D12), invariant checkers, test vectors. No Cardano client dependency. |
| `capabilities/asset-gateway-cardano-support` | pure library (no manifest) | Protocol-neutral helpers lifted from the EUTxO bridge (D16), plus the vault and shard transaction builder and the shard mirror (D9). |
| `capabilities/asset-gateway-cardano` | runtime plugin bundle | Observers (D5, D10), relayer executor (D9), domain API (deposit instructions, unsigned deposit builder, claim and shard witnesses, reconciliation report, doctor). |
| `capabilities/asset-gateway-onchain` | on-chain artifacts | Julc sources and checked `plutus.json` for the vault, the shard validator and the shard thread policy (D8, D8c). |
| `capabilities/cardano-wallet-auth` | pure library plus a JS SDK piece | CIP-8 verifier, account credentials, intent envelope, session grants (D13). |
| `ledgers/asset-payments/{contracts,ledger,client}` | contracts library, state-machine bundle, client | The #109 payment ledger (D14). |
| `examples/asset-escrow` | example state-machine bundle | The second consumer (D17a). |
| `examples/asset-payments-client` | example | Reference web client and recipe. |

Dependency rules:
- Deterministic modules (`*-contracts`, `cardano-wallet-auth`, the payment ledger, the escrow
  example) never depend on `asset-gateway-cardano` or on network code.
- The EUTxO bridge embeds `asset-gateway-cardano-support`. It never depends on the gateway
  bundle.
- All modules follow `yano-x → yano` and are registered in `config/artifacts-v1.json`.

### D3. Canonical assets and amounts

**`AssetId`**
- Shape: `(policyId: 0 or 28 bytes, assetName: 0..32 bytes)`.
- ADA is `(empty, empty)`. An empty policy with a non-empty name is rejected.
- Names are raw bytes, never tickers or hex.

**Encoding**
- All structures use RFC 8949 §4.2.1 core deterministic encoding with definite lengths. Decoders
  reject non-canonical bytes.
- There is **one ordering**, used for both business logic and the wire: bytewise order of the
  canonical encoding. That order is length-first for byte strings: under one policy, the name
  `ff` sorts before `00 00`.
- r1 defined a second, raw-lexicographic order. It is withdrawn.

**Quantities and counters**
- A `Quantity` is an unsigned 64-bit integer, matching L1 `positive_coin`.
- Balances, reservations and per-claim amounts are checked u64. Overflow rejects only the
  offending command.
- Lifetime counters (`credited`, `paid`) are checked **u128**. Recirculating a fixed supply can
  push them past u64; a u128 counter cannot overflow within any feasible lifetime. A
  deposit-credit transition therefore cannot fail on counter width.

**`AssetValue`**
- A canonical map from `AssetId` to a positive `Quantity`, with no zero entries.
- Its entry count is bounded by the profile; the default is 16.
- L1 values may exceed that bound. D5 defines the bounded summary used for them.

**Display data.** Decimals and tickers are display metadata in the registry. They never enter
arithmetic or identity.

**Vectors.** M1 publishes exact CDDL, tags and byte vectors (V1, V2).

### D4. Domains and identifiers

Every identifier is `blake2b-256(tag ‖ canonical CBOR of its fields)`. Each tag is a distinct
ASCII string ending in `\0`.

| Id | Fields |
|---|---|
| `GatewayDomain` | Address network id, network magic, app ledger chain id, genesis id, application id, profile id, profile version |
| `VaultId` | `GatewayDomain`, `AssetId`, vault ordinal |
| `DepositId` | `VaultId`, accepted L1 tx id, output index |
| `ClaimId` | `GatewayDomain`, `VaultId`, claim kind, application withdrawal key, recipient address bytes, `AssetValue`, `vaultLovelace`, sequence |
| `IntentId` | the hash of the exact signed `IntentEnvelopeV1` bytes (D13) |

**Derivation order, which must be acyclic (resolves the r1 cycle).** Each item depends only on
the items before it:
1. one-shot seeds;
2. `GatewayDomain`;
3. `VaultId`;
4. shard thread policy (seeded per vault);
5. vault script parameters (`VaultId` fields, the anchor identity, the shard policy and shard
   script hash, the claim key prefix, profile parameters);
6. vault script hash;
7. vault address.

The script hash is **not** part of `VaultId`. It is recorded in the registry next to it.

**When ids are fixed.**
- `ClaimId` is fixed when funds are reserved. Finality evidence and anchors never feed into it.
- `DepositId` is the permanent key that guarantees exactly-once processing (ADR-041 §10).

### D5. Ingress: one generic multi-asset deposit observer; custom observers allowed

The bundle ships observer type `asset-vault-deposit-v1`.

**Settings.** These feed `consensusIdentity`:
- one `GatewayDomain`;
- the vault set: `VaultId`, asset, script hash and address;
- datum bounds: 16 KiB, depth 16 (as in ADR-042);
- the profile's minimum stability depth. Startup fails if the chain's
  `l1.stability-depth` is lower.

**Output shapes.** The vault script guarantees that the shapes are disjoint (D8, rule V7):

| Output at a vault address | Datum | Meaning |
|---|---|---|
| Deposit | `DepositDatumV1(beneficiaryAccount, …)`, with its own constructor tag | Creditable if admitted (D6) |
| Continuation | `VaultContinuationV1(claimIds)`, a different tag | Created only by `Settle` or `Exit`. Never a deposit. |
| Floor top-up | `FloorTopUpV1` | Operational ADA. Never credited. |
| Anything else | none, or any other datum | Unattributed custody. Never credited. |

A vault-spending transaction may not create any output at the vault address other than its single
continuation. This rule is in the script (rule V7). A deposit-shaped output therefore never comes
from a vault spend, and the observer can classify outputs without resolving inputs. This answers
r1's open problem of recirculated custody.

**Per block.**
- Only phase-2-valid transactions are read.
- One observation is emitted per output at a configured vault address, with
  `eventOrdinal = output index`.
- The claim is `AssetDepositClaimV1`:
  - `DepositId` and `VaultId`;
  - the outpoint;
  - the shape;
  - the decoded beneficiary, or a `MALFORMED` reason;
  - a **bounded value summary**:
    - the quantity of the vault's registered asset;
    - lovelace;
    - the count of other assets;
    - `blake2b-256` of the full value CBOR.

  The full value stays on L1 for reconciliation. Every output therefore yields a bounded
  observation, whatever its asset count.
- The observer reports facts and does not decide whether a deposit is creditable (D6).
- It never throws on L1 content (ADR-042 amendment of 2026-10-10).

**Creditable deposit.** All of the following hold:
- the deposit shape;
- exactly one registered asset, the vault's own;
- no other assets;
- a positive quantity.

For a native-token vault, the lovelace in the output becomes the depositor's account-bound
min-ADA credit in that vault (D11). For the ADA vault, all lovelace is credited.

**Flooding.** Each vault output costs its author at least min-ADA on L1. A dust flood is bounded
by that cost and by the journal's back-pressure, which halts intake rather than losing a fact
(ADR-041). §11 records the residual liveness risk.

**Custom observers.** An application with its own datum or staging protocol writes its own
`L1ObserverProvider`. It reuses the support helpers and must emit `AssetDepositClaimV1` through
the same path, so consumers stay unchanged. No callback or webhook can create a deposit
(ADR-041 §7.1, l.402-404).

**Vault additions.** Adding a vault after genesis is a coordinated configuration change:
1. new observer settings are added, carrying an `activeFromSlot`;
2. every member restarts with identical settings. `consensusIdentity` fails closed on a mismatch;
3. a governed `ASSET_REGISTER` transition takes effect at the same slot.

The v1 profile limits this to adding vaults. A vault binding is never changed in place (D14).

*Alternatives considered:*
- A runtime codec SPI inside the observer. Rejected for v1: it enlarges the consensus surface.
- Classifying outputs by whether the transaction spends tracked custody. Rejected: the observer
  cannot resolve inputs, and same-block ordering is not L1 order (A-F1, B-F4).

### D6. Exactly-once credit belongs to the consumer; the library makes it hard to get wrong

The consuming machine credits in its own `apply()`.
`DepositAdmission.admit(reader, writer, sequencedObservation, registry)` performs these steps in
one pass over the staged writer:

1. **Check the envelope:**
   - the observer id is the configured one;
   - the anchor equals the claim tx id;
   - `eventOrdinal` equals the output index;
   - slot and block hash match.
2. **Recompute `DepositId`** from the verified domain and outpoint, and compare it with the
   claim's.
3. **Check that `deposit/<DepositId>` is absent.** The check reads staged writes, so two outputs
   of one transaction in one block are handled.
4. **Apply the shape rule, the creditability rule and the registry status.**
5. **Write the deposit record:**
   - `CREDITED`;
   - `UNCREDITED(reason)`;
   - `UNATTRIBUTED`, for non-deposit shapes;
   - `OPERATIONAL`, for top-ups.
6. **Return a credit instruction**, which the caller must apply in the same transition.

The record and the credit commit together or not at all. A replayed observation is a no-op (I1).

**Uncreditable inputs are never errors.** These include:
- unregistered or paused assets;
- malformed datums;
- the wrong domain;
- mixed assets;
- a zero amount.

They are recorded and counted as unattributed custody (D15). The v1 profile has no automatic
refund. Unattributed custody can leave the vault only through a governed sweep claim (D8d). Q3
covers a later staging profile with depositor refunds.

**Deep rollback.** A deep L1 rollback below a credited deposit triggers the journal quarantine
(`DEEP_L1_ROLLBACK_BELOW_FINALIZED_OBSERVATION`) and the incident procedure in D15b. ADR-042 §1
item 8 says: "a rollback behind finalized value halts and reconciles". Nothing compensates
automatically.

### D7. Settlement starts with an effect at the reserving transition; effect results never confirm

When a consumer authorizes a withdrawal, the same `apply()` does the following:

1. **Feasibility.** It checks that the claim can be paid, and rejects it otherwise:
   - the recipient is a Shelley address on the domain's network;
   - the recipient address is not a script address;
   - an ADA claim is at least the governed `minAdaPayout`.
2. **Reservation.** It moves the amount from available to reserved.
3. **Claim records.** It writes:
   - `claim/<ClaimId>` with status `RESERVED`;
   - the **claim leaf** at the key the vault script derives (D8, rule V2).

   The leaf value is `ClaimLeafV1(ClaimId, VaultId, claim kind, AssetValue, vaultLovelace,
   recipient, reservedHeight)`. It is immutable. Status lives under a separate key, and the leaf
   is never deleted.
4. **Effect.** It emits an `EffectIntent` of type `asset.settlement` with these settings:
   - payload: `SettlementIntentV1(ClaimId)`;
   - `FinalityGate.APP_FINAL`;
   - `ResultPolicy.NONE`.

**Who authors a claim.** Every field of a claim comes from finalized state. A relayer chooses
none of them.

**Confirmation.** Only the payout observer (D10) confirms. `~fx/result` is one member's
attestation (`FxKernel.java:288`), so it may drive local telemetry but never claim status
(ADR app-layer/011 §12).

**Claim status.**
- Consensus lifecycle: `RESERVED → PAID`, or `PAID_MISMATCH`, which halts (D10).
- Derived views:
  - `EXIT_ELIGIBLE`: `reservedHeight + exitDelayBlocks ≤` latest anchored height, and the
    domain status leaf is `ACTIVE` under that anchor (D8a, D8e);
  - `SETTLEMENT_PENDING`: in the default mode, waiting for a co-signed batch.
- `SUBMITTED` is relayer telemetry only.

**No release in v1.** A reserved claim is never returned to a balance, because it stays payable on
L1 (I8). Expiry with enforceable invalidation is Q7.

### D8. Reference custody profile: `anchored-claim-vault-v1` (rewritten in r3)

**Decided by the maintainer (2026-11-24, r3).** The default is co-signed batch settlement on the
normal path, with a per-claim proof exit as the fallback. A deployment can choose proof-only
instead (D8e).

**Vault layout**
- Each registered asset has its own vault script, parameterized as described in D4. The
  parameters include the settlement mode (D8e).
- Each vault has its own 16 nullifier shard threads (D8c).
- A vault may hold many UTxOs.

**Redeemers.**
- `Settle(claims[k], shardIndex)` is the co-signed fast path, with 1 ≤ k ≤ `maxSettleBatch`.
- `Exit(claims[k], shardIndex)` is the proof path, with 1 ≤ k ≤ `maxExitBatch`.

`Settle` is rejected outright when the script's mode parameter is `PROOF_ONLY`.

**Rules for both redeemers.** These are the safety rules shared by the two paths:

- **V4 — nullifier bijection.** Exactly one shard input of this vault is spent, at the pinned
  shard script with that vault's shard thread token. It is the shard named by `shardIndex`.
  - All claim ids route to that shard by their last nibble.
  - The ordered list of ids inserted by the shard redeemer **equals** the ordered list of claim
    ids paid. No insert is missing and none is extra.
  - The shard validator folds each exclusion proof over the previous insert's root.
  - Both paths use the **same** shards and the **same** `ClaimId`. A claim paid on one path can
    never be paid on the other.
- **V5 — payouts.** Output `i < k` pays `claim[i].recipient`.
  - Its value contains exactly `claim[i].AssetValue` of the vault's asset, plus at least
    `claim[i].vaultLovelace` lovelace.
  - The builder may add more lovelace from non-vault inputs.
  - Recipients are never script addresses.
- **V6 — exact vault value equation.** Write `in` for the sum of all vault inputs and `out` for
  the continuation output. Then:
  - for the vault's asset: `in − out = Σ claim.AssetValue`;
  - for lovelace: `in − out = Σ claim.vaultLovelace`;
  - every other asset in `in` is preserved exactly in `out`;
  - `out` lovelace is at least the profile's `continuationFloor`.
- **V7 — output shape.**
  - Output `k` is the only output at this vault's address.
  - Its datum is `VaultContinuationV1(claimIds in order)`.
  - No other output in the transaction pays this vault's address.
- **V8 — script inputs.**
  - The only script inputs are this vault's own UTxOs and the one shard input.
  - The anchor is used as a reference input only.
  - Fees and collateral come from non-script inputs.

**Rule for `Settle` only:**

- **V9 — threshold.** At least `t` of the member keys in the **current anchor datum** appear as L1
  required signers, and the ledger has verified their witnesses.
  - The script reads the anchor reference input pinned under V1, but proves no claim against it.
  - Using the anchor datum's member set, rather than a separate settlement root thread, means
    membership handoff (D8b) rotates both paths at once.
  - Whether a claim in a `Settle` is real is **not** checked on chain. Each member checks it
    before signing (D9), as in ADR-UTXO-009 §13.3.

**Rules for `Exit` only:**

- **V1 — anchor.**
  - Exactly one reference input carries the pinned anchor thread token (policy and asset name) at
    the pinned anchor script hash.
  - Its datum matches the pinned genesis id, application id, commitment profile
    (`mpf-blake2b256-v1`) and format fingerprint.
  - This is `MpfAnchorLib.acceptedAnchor`, extended additively to also return the anchored
    `height`, the member keys and the threshold.
  - The script reads every pinned value from parameters, never from the redeemer.
  - `Settle` uses the same check to locate the member set.
- **V2 — claim key and value bound.** For each claim, the script itself computes two values:
  - the expected physical key, `claimKeyPrefix ‖ ClaimId`. `claimKeyPrefix` is a script
    parameter:
    - for a payment ledger running standalone, it is the ledger's own local prefix;
    - for one running inside a composite, it is the `CompositeCommitmentV1.componentKey`
      encoding for the component id pinned at genesis.
  - the expected value: the canonical `ClaimLeafV1` bytes of the redeemer's claim fields.

  It then requires all three of:
  - `proof.key == expectedKey`;
  - `proof.value == expectedValue`;
  - `MpfOnChainVerifier.verifyInclusion(proof, stateRoot)`.

  A proof of any other key or value fails, even where two components write identical bytes
  (B-F1, A-F3).
- **V3 — exit delay and brake.**
  - Each claim must satisfy `claim.reservedHeight + exitDelayBlocks ≤ anchor.height`. The value
    of `exitDelayBlocks` depends on the mode (D8e).
  - One extra inclusion proof, at a pinned key, must show that the domain status leaf under the
    same root is `ACTIVE` (D8a).

**Why two paths**

- **`Settle` serves normal operation.**
  - The script only checks threshold signatures and nullifier inserts, with no per-claim Merkle
    proof. That fits about 16 claims per transaction, the cap ADR-UTXO-009 already ships and
    tests.
  - It does not wait for an anchor or for a maturity delay.
  - The brake is the members themselves: they refuse to sign while the domain is not `ACTIVE`.
- **`Exit` serves the case where `Settle` does not happen.** Examples: the owner node is down,
  operators stop co-signing, or one user's claim is skipped.
  - Once a claim has been anchored for `exitDelayBlocks`, anyone can pay it with a proof against
    the threshold-signed anchor, and no custody keys are needed.
  - The delay is counted **per claim**, from the claim's reservation height. This is stronger
    than ADR-UTXO-009's Exit, which opens only when the whole root has gone stale and therefore
    does not help a user whose claim is selectively skipped while anchoring continues.
- **"Funds are not locked" holds only within limits.**
  - What it covers: a claim that was already reserved and anchored is payable without the
    operators.
  - What it does not cover:
    - a quorum that refuses to reserve the claim in the first place;
    - a quorum that stops anchoring, which leaves claims reserved after the last anchor stuck;
    - lost threshold keys, which freeze everything.
  - #109 lists independent exit as a non-goal. The documentation must say "certified claims can be
    paid without the operators".

**What the dual path adds to the threat model (§3)**

1. **Common-mode bugs.** A bug in the member body check, running in the same software on every
   honest member, could sign a wrong `Settle`. The script cannot catch it, because `Settle`
   proves nothing against the root. The check must stay small and get its own independent review
   (§12). The payout observer halts on any mismatch (D10).
2. **Burning a real claim's nullifier.** A colluding or buggy threshold can insert a real claim's
   nullifier while paying the wrong recipient. That claim can then no longer be paid by `Exit`.
   This sits within the custody assumption, and the confirmation observer detects it (D10).
3. **The owner node.** It builds `Settle` bodies. It is liveness-only, because members verify each
   body before signing (ADR-UTXO-009 §11.3).

**Cost**

- **`Exit`.** One two-fold inclusion costs a measured 445,952,815 CPU and 1,462,219 memory
  (`sdk/proof-onchain/README.md:9`). A transaction has a limit of 16,500,000 memory and
  10,000,000,000 CPU (mainnet, epoch 669). Memory is the binding limit. Each claim needs:
  - a state inclusion at the real tree depth;
  - a shard exclusion;
  - a shard insert.

  Each transaction also needs one status-leaf inclusion. **Estimate, to be measured in M4:** 1–5
  claims per `Exit` transaction. That is acceptable for a fallback.
- **`Settle`.** About 16 claims per transaction. M4 measures the actual cap.
- **Anchor advances.** Each advance spends the anchor UTxO that `Settle` and `Exit` reference, so
  in-flight transactions built against the old anchor must be rebuilt.

*Alternatives considered:*

- **Proof-only as the only design (r1/r2).** Kept as a configurable mode (D8e), not as the
  default. Its throughput cost is too high for the normal path.
- **On-chain Ed25519 verification of a per-claim threshold certificate.** Rejected for v1. Signer
  epochs would have to be verified on chain, which duplicates the anchor.
- **The EUTxO bridge's separate settlement root thread.** Rejected. The generic anchor already
  carries both the members and the root, and nothing advances the bridge's root thread at
  runtime.
- **A two-level withdrawal root, to make `Exit` cheaper.** Kept as an optimization (Q15), and
  most useful for `PROOF_ONLY` deployments.

### D8a. The L1 brake: maturity plus a domain status leaf (new in r2; answers Q4)

r1 left the anchored claims always payable. Under r1, a ledger bug that wrongly created claims
became irreversible within one anchor interval (A-F5). r2 adds a brake that L1 can see:

- **Status leaf.** The consumer keeps a domain status leaf at a pinned key:
  `DomainStatusV1(status ∈ {ACTIVE, PAUSED, HALTED}, sinceHeight)`. Governance (`PAUSE`,
  `RESUME`) and the deterministic halts in D10 and D15 update it.
- **Scope of the brake.** The brake is enforced on chain for `Exit` (V3). For `Settle`, the brake
  is the members themselves: the co-sign check refuses to sign unless the domain is `ACTIVE`
  (D9).
- **What V3 requires.** A claim's `reservedHeight` must be at least `exitDelayBlocks` below the
  anchored height, and the status leaf must be `ACTIVE` under the same root. In `PROOF_ONLY`
  mode, `exitDelayBlocks = maturityBlocks` (D8e).
- **How the brake takes effect.**
  - A pause committed at height `h` takes effect on L1 at the first anchor of height `h` or
    later. That anchor spends the previous one, so older roots can no longer be referenced.
  - Claims reserved after `h − maturityBlocks` are protected as long as
    `maturityBlocks` is greater than (anchor interval + detection time).
  - Claims that were already matured and anchored before the pause stay payable until the next
    anchor. After that they are payable only once the domain is `ACTIVE` again.
- **Parameters.** `maturityBlocks` is a governed profile parameter with a floor. M4 sets the floor
  from measured anchor intervals.
- **What the brake does not do.** Claims that are matured, anchored and paid are irrevocable. The
  brake does not prevent theft by a colluding threshold, which can unpause. It contains bugs and
  gives operators response time.

**Answer to Q4.** The always-armed lean from r1 is withdrawn, as reviewer A argued in F5.

### D8b. Anchor identity, handoff, staleness and proof availability (new in r2)

**Pinned identity**
- The profile pins the full anchor identity in genesis consensus configuration:
  - thread policy id;
  - thread asset name;
  - anchor script hash;
  - genesis id;
  - application id;
  - commitment profile;
  - format fingerprint.
- Startup fails closed if:
  - the chain's commitment profile is not `mpf-blake2b256-v1`;
  - the anchor mode is not script mode;
  - the node's anchor settings differ from the pinned identity.
- Followers no longer adopt the identity from the first sign request for these chains.
- Deposits are not admitted until an anchor observer has finalized two facts:
  - the stable bootstrap;
  - a first verified advance carrying the approved initial members.

**Membership handoff.** `anchor.ak` requires a threshold of the input datum's members
(`anchor.ak:91-99`). Governed membership can change faster than the L1 anchor can follow
(B-F6). For chains that run this profile:
1. A membership change is proposed and committed as **pending**.
2. The next anchor advance carries the new member set. It is signed by at least the threshold of
   the old set.
3. The anchor observer finalizes the stable L1 output carrying the new set.
4. Only then does the new membership activate in consensus.

Old keys may be retired only after step 3. If a handoff is interrupted, it is retried with the
old set, which keeps authority.

This needs a framework gate on governed membership activation (§7, Q14). Until that gate exists,
the doctor (D15a) and the `appchain` CLI enforce an operational rule: never remove more than
`n_old − t_old` old members before the handoff is stable.

**Staleness and rollback**
- An old anchor UTxO that has been spent cannot be referenced.
- A stale anchor that has not been spent is still a valid root. Age alone does not invalidate a
  claim, because leaves are immutable and nullifiers persist.
- An L1 rollback of an anchor advance restores the previous anchor UTxO, which is also
  threshold-signed. In-flight payouts against the rolled-back anchor fail on L1 and are rebuilt.
- Losing the pinned identity is a halt. The thread token is gone or burned only if the anchor
  script allowed it, which it does not.
- Replacing the anchor identity is a migration (D8d).

**Proof availability.** Nodes running this profile must retain state proofs at the latest
L1-confirmed anchor height. That is a pruning floor:

`retainFrom = min(tip-derived retention, latest confirmed anchor height)`

This is a framework change (§7). The domain API serves claim proofs only from roots that an L1
anchor still references.

### D8c. Nullifier layout (new in r2)

**Threads**
- Each vault has 16 shard threads.
- Their one-shot `ShardThreadPolicy` is seeded per vault.
- The shard validator is parameterized by that vault's script hash.
- A vault therefore never accepts another vault's shard, and a payout can never insert into
  another vault's nullifier set (A-F9, B-F3).

**Bootstrap**
- Bootstrap mints all 16 tokens to the pinned shard address, each with the empty MPF root.
- An observer verifies the bootstrap before deposits are admitted to that vault (D8b gate).

**Batching**
- A `Settle` or `Exit` transaction batches only claims of one shard (V4).
- Both redeemers use the same shards.
- The relayer groups claims by shard, as `QuickTxSettlePipeline` does today (l.98-143).

**Continuity**
- Shard threads have no upgrade path in v1.
- A new shard design requires a new vault version and a migration (D8d).

### D8d. Governed sweep and migration claims (new in r2)

Under D8, value can leave a vault only against a claim leaf. Some custody belongs to no user
claim:
- unattributed custody;
- the operational floor;
- liabilities that must move to a replacement vault.

For these, a threshold-governed transition (the same governance as the asset registry, D14)
creates a governed claim leaf:
- `SWEEP_UNATTRIBUTED(depositId, recipient)`, bounded by that deposit's recorded value summary;
- `SWEEP_OPERATIONAL(amount, recipient)`, bounded by the operational account;
- `MIGRATE(VaultId → new vault address, AssetValue)`.

Governed claims:
- are payable **only through `Exit`**, never through `Settle`, so they are always subject to the
  on-chain delay and the brake;
- follow the same V1-V8 rules;
- are subject to maturity and the brake, which makes them publicly visible before they are
  payable;
- appear in I3/I4 as explicit terms.

`MIGRATE` moves the liability backing to the new `VaultId` in the same transition. Migrations are
batched, because each needs one claim per input UTxO set.

**Limits.** If the threshold's keys are lost, the anchor freezes, so this profile has no recovery
path. That is stated in the trust label and in §11 (#110 §3.1 asks for a safe migration plan;
this is that plan, together with its limit).

### D8e. Settlement mode is fixed per vault, on chain (new in r3)

The genesis profile setting `settlement.mode` takes one of two values:

| Mode | `Settle` | `Exit` delay | Normal path | Trust label |
|---|---|---|---|---|
| `COSIGNED_WITH_PROOF_EXIT` (default) | accepted | `exitDelayBlocks`: long, governed within profile bounds | co-signed batches | `FEDERATED_COSIGNED_WITH_PERMISSIONLESS_EXIT` |
| `PROOF_ONLY` | **rejected by the script** | `exitDelayBlocks = maturityBlocks`: short, the D8a brake | permissionless proof relay | `FEDERATED_CUSTODY_PERMISSIONLESS_RELAY` |

**On-chain parameter.** The mode is a **vault script parameter**, so it is part of the vault's
script hash.
- A `PROOF_ONLY` vault cannot be spent by `Settle`, even if a threshold colludes to co-sign one.
- Anyone can verify the mode from L1.

A setting that is only node-local cannot give this guarantee. A co-signed vault can be operated
proof-only by not running the co-sign service, but only a `PROOF_ONLY` script guarantees it.

**Consensus and configuration.**
- `settlement.mode` and the delay bounds are consensus-shared and pinned with the profile, like
  the rest of D17.
- The ledger behaves per mode:
  - in `COSIGNED_WITH_PROOF_EXIT` it emits settlement batches for the co-sign executor;
  - in `PROOF_ONLY` it emits per-claim relay effects.
- Startup fails closed in these cases:
  - a node's executor configuration does not match the mode, for example a co-sign owner on a
    `PROOF_ONLY` chain;
  - a registered vault's script hash does not match the mode parameter.

**Changing the mode.** The mode cannot be changed in place. Changing it means:
1. register a new vault whose script carries the new mode;
2. move the liabilities with governed `MIGRATE` claims (D8d).

**Ordering of delays.** In the default mode, `exitDelayBlocks` must be greater than the fast
path's expected settlement deadline. Exits then fire only after the fast path has failed, and
the two paths rarely race on shard UTxOs. If they do race, the second transaction simply fails
(V4).

### D9. Settlement executors: the co-sign owner and the exit relayer

**Co-signed path** (default mode). The bundle's `asset-settlement` executor follows
`EutxoSettlementExecutorFactory`:
- **Members.** Every member registers a co-sign handler, reusing `SettlementCosignService`.
- **Owner.** The owner node builds `Settle` batches per shard from the claims in the committed
  `asset.settlement` batch effect.
- **Before signing**, each member runs the **body check** against its own committed state. The
  check passes only if:
  - the domain is `ACTIVE`;
  - every claim id is one of this member's own `RESERVED` claims, with exactly that recipient,
    `AssetValue` and `vaultLovelace`;
  - the shard inserts equal the claim ids;
  - rules V5-V8 hold.

  This check stays small, and it gets its own review (§12). If the batch fails or expires, the
  ledger re-batches the claims, as `onEffectResult` does today.
- **Shared fixes** from the EUTxO stack carry over:
  - a durable journal;
  - a node-local operator wallet for fees;
  - a fail-closed round timeout.

**Exit relayer** (both modes). Any node, or any third party, works through claims in this order:
1. Wait until the claim is `EXIT_ELIGIBLE`, as defined in D7.
2. Fetch the claim proof and the status-leaf proof at the referenced anchor's height. Fetch the
   shard exclusion witnesses from the **shard mirror**. The mirror is rebuilt from the L1 insert
   history and checked against the shard's on-chain root; a mismatch stops the relayer for that
   shard, as `QuickTxSettlePipeline.java:129-138` does.
3. Group claims by shard. Select vault UTxOs in canonical outpoint order, and select again on
   contention.
4. Build and submit the transaction with the relayer's own fee wallet. Supplement payout
   lovelace when protocol parameters require it.
5. Poll until the transaction is included.

**What the relayer can and cannot do.** The relayer has liveness power only. A malicious relayer
can fail to pay. It cannot pay the wrong amount, pay the wrong recipient, or pay twice (I5-I7,
I19).

**Journal**
- The journal is durable and keyed by L1 transaction id, mapping each transaction to its ordered
  claim ids and shard.
- It is a cache, not a trust root. After a restart, committed claim status plus on-chain
  nullifiers decide what is still payable.
- If a claim's nullifier is already present on L1, the relayer treats it as paid by someone else
  and drops it. A third-party relayer can win that race.
- An L1 rollback of a payout removes the nullifier. The claim becomes payable again (D10).

**Third-party relaying.** The domain API serves exactly what a third party needs:
- the claim proof;
- the status proof;
- the anchor reference;
- the shard witnesses;
- the vault UTxO set.

The client SDK carries the same builder. Each node selects its own executor (D17).

### D10. Confirmation is authenticated by the shard thread, not by tracked custody (rewritten in r2)

**Why r1's custody gate is replaced.** r1 required at least one spent vault outpoint to be tracked
custody, a gate inherited from ADR-042. For a permissionless `Exit`, that gate is unsound:
- a payout from a fresh, not-yet-tracked deposit UTxO could be ignored forever (A-F2);
- same-block parent and child payouts can be applied in the wrong order (B-F5).

**What replaces it.** The `asset-vault-payout-v1` observer emits a `PayoutObservationV1` for a
phase-2-valid transaction only when the transaction **has an output carrying one of this vault's
shard thread tokens at the pinned shard script address**. The policy is one-shot and the tokens
never leave the shard script, so their presence proves that the shard validator, and therefore
the vault validator (V4), accepted this transaction.

The observation carries:
- the L1 tx id;
- the ordered claim ids from the `VaultContinuationV1` datum, which V7 forces to be exact;
- per payout output: recipient, quantity of the vault asset, and lovelace;
- the continuation outpoint and its value summary.

No tracked custody is needed.

**How the consumer applies it.** The consumer calls `PayoutConfirmation.apply(...)`, which keys
confirmations by `(GatewayDomain, L1 tx id, ClaimId)` and handles three cases:
- **The claim is `RESERVED`, and the script-enforced fields match** (claim id, recipient, exact
  asset quantity, lovelace ≥ `vaultLovelace`): the claim becomes `PAID`, and `paid(A)` increases.
- **The claim is already `PAID`** (a replay, or a re-payment after a rolled-back payout): no-op,
  recorded.
- **A thread-authenticated payout disagrees with a claim on a script-enforced field:**
  `PAID_MISMATCH`. The domain's status leaf moves to `HALTED`. For `Exit`, this can only happen
  through a validator or ledger bug. For `Settle`, it can also come from a colluding threshold or
  a common-mode bug in the member body check (D8, D9). That case can have burned a real claim's
  nullifier, so the incident procedure (D15) must resolve the affected claim by governance.

The consumer validates the whole observation before mutating anything. Fields the script does not
enforce, such as extra lovelace that a builder added, are never compared. A look-alike
transaction without the thread token is ignored and never halts (I2).

**Rollback of a confirmed payout** (A-F11). The L1 nullifier and payout disappear, but the ledger
still shows `PAID`. The claim leaf is immutable and still anchored, so anyone can pay it again.
The second confirmation is a no-op in the ledger, and L1 ends with exactly one payout. Until then,
reconciliation shows a surplus equal to the claim (D15).

**Custody tracking** is kept for reconciliation only. It is a set of vault outpoints, derived from
deposit, continuation and top-up observations.

### D11. Fees, min-ADA and operational ADA (rewritten in r2)

In r1, an operational-ADA allowance could be drained by many small token withdrawals, and the
destination of the allowance was not enforced (A-F4, B-F2). r2 makes each withdrawer fund their
own min-ADA:

- **Fees and collateral** always come from the relayer's own inputs.
- **Account-bound min-ADA credit.** In a native-token vault, the lovelace of a creditable deposit
  becomes `minAdaCredit(account, VaultId)`. It is a lovelace liability of that vault and is not a
  spendable payment balance:
  - it cannot be transferred;
  - it can be spent only as `vaultLovelace` on the account's own claims from that vault;
  - the account can withdraw it on its own as a lovelace claim from that vault, if the amount is
    at least `minAdaPayout`.
- **`vaultLovelace` on a token claim.** It is fixed at reservation, at most
  `min(minAdaCredit, profile.payoutLovelaceCap)`, and debited from the credit in the same
  transition. V5 and V6 then bind it exactly to the payout output.
- **When the credit is short.** If the account's credit is below the min-UTxO of the payout
  output, the claim still reserves. Whoever relays it must add the missing lovelace from their
  own inputs. The user can self-relay. The claim stays payable and is never stranded.
- **Operational floor.** Each vault keeps an operator-funded `continuationFloor` so that a
  continuation output always exists. That covers withdrawing the last liability from the last
  UTxO (B-F9). The floor is operator-owned operational ADA. It is never a user liability, and it
  can leave only through `SWEEP_OPERATIONAL` (D8d).
  - `FloorTopUpV1` outputs add to the floor. Anyone may author one; that donates lovelace and
    creates no credit.
- **ADA vault.** Customer ADA is the payout. Claims below `minAdaPayout` are rejected at
  reservation. The floor is kept separate in the accounting.
- **No cross-asset charges.** No fee or charge is ever taken from another asset. v1 has no
  relayer bounty (Q10).

**Q1 and Q2 change** (§13). Treating incidental lovelace as an account-bound min-ADA credit
departs from r1's "operational ADA" lean. #109's wording "incidental ADA must not create credits"
is read as "must not create spendable payment balance". The maintainers must confirm that
reading.

### D12. Optional accounting is a deterministic library, not a state machine

`AssetAccounts` lives in the contracts module and provides:
- balances per `(AccountId, AssetId)`, in `available`, `reserved` and app-defined compartments
  such as `locked`;
- per-`VaultId` min-ADA credits;
- per-asset u128 lifetime counters;
- these primitives:
  - `credit(admission)`;
  - `transfer`;
  - `reserve(claim)`;
  - `consumeReserved(confirmation)`;
  - `release(claim)`, which is not usable in v1 (D7);
- checked arithmetic;
- an invariant check;
- a key layout under the consumer's own namespace.

Rules the library enforces:
- `credit` requires a `DepositAdmission` credit instruction;
- `transfer` preserves the total of every asset;
- no primitive increases total liabilities except a deposit credit (I3).

**Why a library and not a state machine.** As a library, business state and balances commit in
one machine's `apply()`, which is atomic by construction. A separate accounting machine would need
an ADR-031.1 cascade for every action, and its kernel would expose debit and credit commands to
other machines.

**Later.** A `TransitionKernel` wrapper can follow when a second consumer needs cross-machine
composition.

### D13. Cardano wallet authorization library (rewritten in r2)

**1. COSE acceptance profile** (a byte-exact profile; B-F13, A-F8)

**Input.** The input is the received `COSE_Sign1` and `COSE_Key` bytes.

**Parsing.**
- Bounded: at most 16 KiB total and depth 8.
- Definite lengths only.
- No duplicate map keys.
- An optional leading tag 18 is accepted. No other tag is accepted.

**`COSE_Sign1` protected header.** This is a byte string holding a map, and it must contain:
- `alg = -8`;
- `"address"`, a byte string;
- no `crit` (label 2);
- no label outside `{1, 4, "address"}`.

**Unprotected header.**
- `hashed` may be absent (an interoperability exception to CIP-0008) or `false`.
- `hashed = true` is rejected (Q5).
- Other unprotected labels are ignored. They are not signed.

**Payload.**
- It must be attached, so a nil payload is rejected.
- It must equal the exact canonical bytes of an `IntentEnvelopeV1`.

**Signature.** The signature is verified over
`Sig_structure = ["Signature1", <received protected bytes>, h'', payload]`:
- it is encoded canonically;
- `external_aad` is fixed to empty by the verifier;
- the protected bytes are **never** re-encoded.

**`COSE_Key`.**
- `kty = 1`, `alg = -8`, `crv = 6`, and a 32-byte `x`.
- `kid` is ignored. It adds no security, and requiring equality would reject real wallets.

**Ed25519 rules.** Ed25519 verification uses the framework's existing member-signature verifier.
M2 records that verifier's exact acceptance rules: non-canonical `S`, non-canonical point
encodings, and small-order points. The CCL and non-JVM oracles must agree on the cryptographic
step, and a corpus records any case where they disagree. Application policy is tested
separately (V3).

**2. Credential binding and accounts** (B-F12)

**Deriving the signer's credential from the protected `address`.**
- **Base, enterprise and pointer addresses:** the payment credential must be a key hash, and the
  credential kind is `PAYMENT_KEY`. A base address with a key payment part and a **script** stake
  part is accepted as `PAYMENT_KEY`.
- **Reward addresses:** the stake credential must be a key hash, and the kind is `STAKE_KEY`.
- **Rejected:** script payment credentials and Byron addresses.

**Binding the signature to the account.**
- The verifier requires `blake2b-224(x) == keyHash`.
- It requires the address network id to equal the domain's address network id. The network
  magic and the genesis id are checked separately through the envelope. Several test networks
  share network id 0.
- The derived `(kind, keyHash)` must equal the **registered owner credential** of the account
  named in the envelope. A valid signature by account A over an envelope naming account B fails.
- `AccountId` is stable: `blake2b-224("acct\0" ‖ initial credential kind ‖ keyHash)`, fixed at
  `REGISTER_ACCOUNT`. The current owner credential is stored separately, which allows rotation
  (D14a).

**3. Intent envelope and replay** (A-F7, B-F14)

**`IntentEnvelopeV1` binds all of the following:**
- the tag `yano-intent-v1`;
- the network magic and address network id;
- the chain id, genesis id and application id;
- the action type and `AccountId`;
- the **authorizer**: `OWNER`, or `SESSION(grantId)`;
- a 16-byte random nonce;
- `notAfter`;
- the canonical action body.

Every executed field must come from the verified envelope.

**Replay protection**
- Replay protection is **single-use** and follows the topic registry's pattern. `IntentId` is
  written under `used/<IntentId>` and retained until `notAfter`.
- `notAfter` must be no later than block time plus `maxIntentTtl`.
- There is no shared counter, so a session cannot lock out the owner, and parallel devices do
  not collide.

**Clock**
- Expiry is checked against `AppBlock.timestamp`: the intent is valid while
  `timestamp ≤ notAfter`.
- `maxIntentTtl` must exceed `maxForwardSkewMs` (`AppChainEngine.java:1269`).

**Receipts and deduplication** are keyed by `IntentId`, never by message bytes. The message bytes
include unsigned COSE parts.

**4. Session grants**

**Registration.** An owner-signed `AUTHORIZE_SESSION` registers a grant. Its `grantId` is the
grant's hash, and it holds:
- an Ed25519 session key;
- allowed actions. By default, withdrawal and further delegation are excluded;
- an asset allowlist;
- a per-payment limit and a cumulative limit per asset;
- an optional recipient allowlist;
- `notAfter`.

**Using a session.** A session-signed intent names `SESSION(grantId)` and carries a plain Ed25519
signature by the session key over the envelope bytes.

**Revocation.**
- `REVOKE_SESSION(grantId)` takes effect when it is finalized.
- An intent processed after the revocation in the same block fails.
- Granting the same key again creates a new `grantId`, so intents signed under the old grant
  stay invalid.

**Limits.** Cumulative usage is read and written through the staged writer (read-your-writes
within the block). Two payments of 6 against a limit of 10 in one block therefore fail the second
time. Nonce use, usage, balances and the receipt commit atomically.

**Key storage.** The client keeps session keys as non-extractable WebCrypto keys where it can.
After a compromise, losses are limited by the grant's limits and stop when the grant is revoked.

**Alternatives considered:**
- **Reusing topic-registry delegations.** Rejected for v1: their scope is a namespace, they have
  no amount limits, and their issuer is a policy writer rather than a wallet credential. The
  grant record is shaped so that it could later become a topic delegation profile.
- **Using CCL's verifier as the verifier of record.** Rejected: it accepts no expected account,
  domain, nonce or expiry. It remains a cryptographic oracle in tests.

**Ingress of user intents.** An application gateway submits the intent as the body of a
member-signed message through the existing `SUBMIT` route. The gateway can censor or delay. It
cannot alter the intent (Q6).

### D14. The #109 payment ledger: `asset-payments`

**State.** The ledger keeps all of the following in the authenticated root:
- chain domain;
- asset registry;
- accounts and their owner credentials;
- session grants;
- used intents;
- balances and min-ADA credits (D12);
- deposit records (D6);
- claims and claim leaves (D7);
- the domain status leaf (D8a);
- the custody set and the operational floor accounts (D10, D11);
- per-asset counters;
- receipts.

**Asset registry.** Each entry holds `AssetId`, `VaultId`, script hash, display metadata,
status, `activeFromSlot` and `addedAtHeight`.

| Status | Meaning |
|---|---|
| `ACTIVE` | Normal operation. |
| `DEPOSITS_PAUSED` | Deposits are not credited. |
| `TRANSFERS_PAUSED` | Transfers are refused. |
| `WIND_DOWN` | Withdrawals only. |
| `RETIRED` | Requires zero liabilities, zero unresolved deposits, zero unpaid claims, and a swept floor. |

Registry changes:
- Genesis supplies the initial set.
- Later changes are governed commands, submitted identically by a threshold of members, the same
  pattern as governed membership. Vault additions follow D5.
- `AssetId` and the `VaultId` binding are immutable once created. A vault is replaced only by
  migration (D8d).

**Commands.** All are user-signed unless noted.

| Command | Effect |
|---|---|
| `REGISTER_ACCOUNT` | Proves the credential with CIP-8 and creates the account. Idempotent. |
| `AUTHORIZE_SESSION` / `REVOKE_SESSION` | Owner only. See D13. |
| `ROTATE_OWNER` | See D14a. |
| `TRANSFER` | One asset. Checks D13 authorization, asset status, a positive amount, sufficient available balance and optional enterprise rules. Debits and credits atomically. |
| `REQUEST_WITHDRAWAL` | Owner only by default. One asset, one exact recipient. Checks feasibility (D7), reserves, creates the claim and leaf, and emits the effect. |
| deposit credit | Not a command. Only a finalized deposit observation credits (D6). |
| payout confirmation | Not a command. Only a thread-authenticated payout observation confirms (D10). |
| `ASSET_*`, `PAUSE` / `RESUME`, governed claims | Threshold-governed. |

There is no administrative mint, no balance override and no withdrawal bypass (I3).

**Enterprise rules.** Merchant allowlists and per-account spending limits are deterministic
machine configuration. They can only forbid. Integration with the ADR-031.3 policy plane is
follow-up work, because it requires the machine to expose a `TransitionKernel`.

**Receipts.** Each accepted or rejected intent writes `receipt/<IntentId>`, holding
`ReceiptV1(IntentId, AccountId, action, AssetId, amount, outcome, height)`. A typed **state**
proof of that key against a finalized root proves both the outcome and the balance effect. A
message-inclusion proof would not: it proves inclusion, not the outcome (B-F17;
`MessageProofVerifier.java:38-83`).

### D14a. Owner rotation (new in r2)

`ROTATE_OWNER(newCredential)` must be signed twice:
- by the current owner, through CIP-8;
- by the new credential, through CIP-8 over the same envelope.

The rotation:
- replaces the stored owner credential while `AccountId` stays the same;
- revokes every session grant on the account.

After the rotation, only intents from the new owner are valid.

v1 has no recovery after the owner key is lost. That is a stated scope reduction.

### D15. Reconciliation (rewritten in r2)

**Consensus counters**

Per `VaultId` V, with asset A:

| Unit | Counters |
|---|---|
| A | `credited`, `paid` (u128); `available`, `reserved` (sums); `unattributed` |
| Lovelace | `minAdaCredit`; reserved `vaultLovelace`; `floor`; `unattributedLovelace` |

**Consensus invariant (I4).** At every finalized height, for each unit:

`credited − paid = available + reserved`

For lovelace in a token vault, the deposit-lovelace credits and the paid `vaultLovelace` take the
places of `credited` and `paid`.

**L1 reconciliation (local, not consensus)**

1. Scan **every** UTxO at each vault address, not only tracked ones, at a named L1 point (slot,
   block hash).
2. Take the finalized app height whose observations cover that point.
3. For each unit, check:

   `L1(V,u) = available + reserved + unattributed + floor + depositsInFlight − payoutsInFlight`

   - `depositsInFlight` is outputs observed on L1 but not yet finalized. They are already on L1
     and not yet counted.
   - `payoutsInFlight` is payouts L1 has seen whose confirmation is not yet finalized. They have
     left L1 and are still counted.
   - Units are never mixed. A surplus in one unit never offsets a deficit in another.

**Incidents**

An unexplained discrepancy:
- raises an alert;
- obliges members, under the runbook, to submit a governed `PAUSE` of the domain, as #109
  requires;
- through D8a, stops further claims from maturing into payability on L1.

Already-matured claims stay payable until the next anchor. A deterministic `PAID_MISMATCH` (D10)
sets `HALTED` with no human step.

No code path "fixes" a discrepancy by writing balances.

### D15a. Observability and diagnostics (new in r2; A-F13)

**Metrics (domain API and the metrics contribution), per vault and unit:**
- reserves against liabilities;
- in-flight deposits and payouts;
- oldest `RESERVED` claim;
- claims that are matured but unpaid;
- anchor age;
- observer lag;
- journal state;
- relayer failures by reason;
- unattributed totals.

**Doctor (the `BridgeDoctor` role, generalized).** It checks:
- anchor identity pinning and mode;
- the proof-retention floor;
- shard bootstrap;
- handoff safety (D8b);
- stability depth against the profile minimum;
- relayer wallet funding;
- trust label display.

### D15b. Deep L1 rollback (new in r2; A-F11)

**Below a credited deposit.**
1. The journal quarantines the node.
2. The members' runbook submits `PAUSE`.
3. If the deposit is gone from the canonical chain, a governed correcting transition debits the
   credited account. If the account's balance is short, the shortfall is recorded as a deficit
   against the operator.

**Below a confirmed payout.** See D10. No ledger change is needed, and the claim can be paid
again.

Both cases are drills in M6.

### D16. The EUTxO bridge adopts shared helpers incrementally, byte for byte

**What moves.** Only the protocol-neutral classes listed in §1.2 move to
`asset-gateway-cardano-support`, and the bridge then depends on them.

**Golden gates.** Each move is gated by golden tests showing no change to:
- observer claim bytes;
- `consensusIdentity` bytes;
- ledger state roots over the existing E2E fixtures;
- compiled script hashes.

No bridge validator changes.

**What stays.** EUTxO claims, datums, the ledger and the lovelace-only profile stay where they
are.

**What the bridge does not prove.** The bridge adopts helpers. It does not consume the deposit,
claim or confirmation contract, so it does not count as a second consumer of that contract
(D17a does).

**Classes nobody constructs.** The bridge owner decides, in a separate PR, whether to keep or
delete the unconstructed classes listed in §1.2.

### D17. Replaceable providers and conformance

| #110 role | Mechanism | How it is replaced | Consensus-relevant? |
|---|---|---|---|
| L1 observation | `l1-observer` type | A custom provider that emits `AssetDepositClaimV1` / `PayoutObservationV1` | Yes, pinned |
| Vault/deposit protocol | profile id, datum tags, script hashes | A new profile version | Yes |
| Settlement executor | `effect-executor` scheme | Any executor or external relayer | No |
| L1 tx construction | builder in the support library or SDK | Any builder, because the script checks the transaction | No |
| Signing provider | none for custody. Anchor co-signing keeps `signer-provider` | Existing SPI | No |
| Settlement journal | journal interface | Any durable implementation | No |
| Action authorization | `cardano-wallet-auth` inside the machine | Another library inside another machine | Yes |
| Accounting | `AssetAccounts` | Custom state, or none | Yes |

**Pinning.** Consensus-relevant choices are pinned at genesis or changed by governed activation.
Startup fails closed on any mismatch, and nothing falls back to a weaker profile.

**Conformance kit** (M5). It runs:
- vectors;
- observer replay;
- adversarial fixtures;
- the validator negative suite;
- an executor crash, contention and race script.

**Substitution tests** in M5 cover two cases:
- a compatible substituted executor passes;
- an incompatible configuration fails closed. Examples: a mismatched observer identity, or a
  missing anchor pin.

### D17a. The second consumer is named now: `asset-escrow` (new in r2; A-F13, B-F17)

`asset-escrow` is a minimal state machine with no accounts:
- A deposit's datum names an `escrowId`, a recipient and an arbiter Ed25519 key.
- Admission creates `escrow/<escrowId>` holding the deposit's value.
- A `RELEASE` signed by the arbiter emits a claim to the recipient.
- A `REFUND` signed by the arbiter after a deadline emits a claim to the depositor-named refund
  address.

It does not use `AssetAccounts` or the wallet-auth library. The M1 contract freeze is reviewed
against both `asset-payments` and `asset-escrow`.

### D18. Trust labels are data

The profile descriptor carries a machine-readable trust label, chosen by the settlement mode
(D8e):
- `FEDERATED_COSIGNED_WITH_PERMISSIONLESS_EXIT` for the default mode;
- `FEDERATED_CUSTODY_PERMISSIONLESS_RELAY` for `PROOF_ONLY`.

These surfaces display it:
- the capability catalog;
- the `appchain` CLI;
- the domain API `info` route;
- the reference client.

The wording follows ADR app-layer/utxo/001 l.350-352: the threshold can censor withdrawals, and
can authorize an invalid root. The surfaces also state:
- the maturity delay;
- what happens if the threshold's keys are lost.

## 6. Invariants

| ID | Invariant | Decisions |
|---|---|---|
| I1 | A deposit credit happens only for a finalized deposit observation whose `DepositId` was never processed. Total liabilities never increase otherwise. Re-delivery, rescan and restart are no-ops. *(r2: worded around liabilities; a transfer recipient's balance may increase.)* | D5, D6 |
| I2 | Invalid or unauthenticated L1 content never makes an observer or `apply()` throw, halt or credit. Valid deposits are credited. Only a thread-authenticated mismatch halts. *(r2 rewording.)* | D5, D6, D10 |
| I3 | Total liabilities of a unit change only by a deposit credit (+), a payout confirmation (−), or a governed claim (−, explicit). `TRANSFER` preserves per-asset totals. No mint, no override, no cross-asset effect. | D8d, D12, D14 |
| I4 | For each vault and unit, at every finalized height: `credited − paid = available + reserved`. Amounts are non-negative, and arithmetic is checked (u64 for balances, u128 for lifetime counters). | D3, D12, D15 |
| I5 | `Exit` pays a claim only if all of these hold: its exact leaf (V2: key and value computed by the script) is under the pinned anchor's root; the claim is older than `exitDelayBlocks`; and the status leaf is `ACTIVE` (V3). `Settle` pays only with a threshold of the current anchor datum's members (V9), each of whom checked the claim against its own state (D9). | D8, D8a, D8b, D9 |
| I6 | Each `ClaimId` is paid at most once: the nullifier insert happens in the same transaction. | D8, D8c |
| I7 | A payout cannot move another asset, or another `VaultId`'s UTxOs or shards. The value equation V6 holds exactly. Lovelace leaves the vault only as the claims' `vaultLovelace`. | D8, D11 |
| I8 | Reserved funds are never released while the claim may be redeemable. v1 has no release. | D7 |
| I9 | Rotation of membership or the anchor never makes a reserved claim permanently unpayable, and never adds a spend path that bypasses I5-I7. Handoffs follow D8b. | D8b |
| I10 | A user intent takes effect only if its signature verifies under D13's profile, the signer's credential equals the named account's owner credential, and the envelope binds every executed field. Replay across network, chain, application, action, account, authorizer or time fails. | D13 |
| I11 | A session never exceeds its limits, allowlists or expiry, including within one block. It never acts after its grant is revoked or replaced, and never withdraws or delegates unless explicitly granted. | D13 |
| I12 | Effect results, operator commands, the executor and the domain API never change deposit or claim status. | D7, D9, D10 |
| I13 | Identical genesis and observed L1 produce identical state roots on every member. Nothing consensus-relevant depends on local configuration. | D1, D5, D8b, D17 |
| I14 | A chain that does not select gateway contributions behaves exactly as today. EUTxO bridge bytes and script hashes are unchanged. | D1, D16 |
| I15 | Fees and collateral never come from a vault. Operational floor, min-ADA credits and customer ADA are separate accounts. | D11 |
| I16 | *(new)* Outputs created by `Settle` or `Exit` are never deposit-shaped, and only deposit-shaped outputs are ever credited. | D5, D8 (V7) |
| I17 | *(new)* The ordered nullifier inserts of a vault-spending transaction equal its ordered paid claim ids, within one shard of that vault. | D8c |
| I18 | *(new)* A payout confirmation is accepted only from a transaction carrying the vault's shard thread token. A confirmation of an already-`PAID` claim is a no-op. | D10 |
| I19 | *(new)* Every reserved claim is payable by someone: the recipient is valid, ADA claims meet the minimum, and token payouts can be supplemented. | D7, D11 |
| I20 | *(new in r3)* Both redeemers share one nullifier set and one `ClaimId`, and the output-shape rules V5-V8 apply to both. A claim is paid at most once, whichever path pays it. | D8, D8c |
| I21 | *(new in r3)* The settlement mode is a vault script parameter. A `PROOF_ONLY` vault rejects `Settle` on chain. The mode cannot change without a new vault and governed `MIGRATE` claims. | D8e, D8d |
| I22 | *(new in r3)* Governed claims are payable only through `Exit`. | D8d |

## 7. Compatibility

**New formats.** All are versioned with explicit tags:
- `AssetId`, `AssetValue`, `GatewayDomain`, `VaultId`, `DepositId`, `ClaimId`, `IntentId`;
- `AssetDepositClaimV1`, `PayoutObservationV1`, `SettlementIntentV1`, `ClaimLeafV1`,
  `DomainStatusV1`;
- `DepositDatumV1`, `VaultContinuationV1`, `FloorTopUpV1`;
- `IntentEnvelopeV1`, session grants, `ReceiptV1`.

Their CDDL lives beside the contracts. A change needs a new version, plus either a governed
activation height or a new profile id.

**Existing formats.** None change. The EUTxO bridge, `balances` and `cardano.payment` keep their
bytes and behaviour (I14).

**Framework additions.** Each needs its own review and API-level decision:
1. `MpfAnchorLib` additionally returns the anchored height, member keys and threshold. This is additive, in an on-chain
   library.
2. A proof-retention floor at the latest confirmed anchor height (D8b).
3. Pinned anchor identity for followers, instead of adopting it from the first sign request
   (D8b).
4. Governed-membership activation gated on an L1-stable anchor handoff (D8b, Q14).

Without 2–4, the profile is not safe to fund. They are M4 entry gates.

**Distribution.** The bundles join the JVM ZIP like other optional bundles and stay off until
selected. The ZIP and Central size budget is checked when they are added.

## 8. Milestones

| ID | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| M0 | This ADR | — | Accepted. D8 decided (r3). Q1, Q2, Q3, Q5, Q6, Q7 and Q14 decided. |
| M1 | Contracts: types, ids, codecs, CDDL, `AssetAccounts`, invariant checker | M0 | V1, V2 and V4 pass. Property tests for I3 and I4. Contract freeze reviewed against `asset-payments` and `asset-escrow` (D17a). Module names fixed. |
| M2 | `cardano-wallet-auth` | M1 (codecs) | V3 passes. The cryptographic oracles agree, or every disagreement is recorded. I10 and I11. |
| M3 | Ingress: deposit observer, `DepositAdmission`, payment ledger without withdrawals, `asset-escrow` deposits | M1, M2 | On devnet, ADA and two native tokens are credited exactly once across restart and rescan. V5 fixtures A1-A10 never credit or halt. I1, I2, I13, I16. |
| M4 | Settlement: vault, shard and policy validators; payout observer; relayer; brake; anchor pinning, handoff and proof floor | M3; framework additions 1-4 (§7); Q2 and Q14 decided | Co-signed `Settle` path, with each member running the body check. V6 negative suite for both redeemers, including that a `PROOF_ONLY` vault rejects `Settle`. Measured `Settle` and `Exit` batch caps and costs. On devnet, an `Exit` after the owner node is stopped. A `Settle` racing an `Exit` on the same claim: exactly one succeeds. Both modes run end to end. Partial and full devnet withdrawals in each asset while transfers continue. A third-party relayer pays a claim. The last liability is withdrawn from the last UTxO. A sub-minimum ADA request is rejected. A token payout supplemented by the builder succeeds. I5-I9, I12, I15, I17-I22. |
| M5 | Second consumer end to end, conformance kit, EUTxO helper adoption | M4 | `asset-escrow` passes the kit. EUTxO golden bytes unchanged (I14). Substitution passes, and an incompatible configuration fails closed. |
| M6 | Hardening and qualification | M5 | Drills: multi-node failure, deep rollback (both D15b cases), relayer, signer and anchor outages, handoff under an anchor outage, contention. Wallet interoperability report covering Lace, Eternl, Yoroi and hardware wallets, mobile, user rejection, and unsupported `signData`. Metrics (D15a) running. Throughput, p50/p95/p99 finality latency, resource use and L1 costs reported for single-asset and multi-asset workloads. Trust and operations documentation. |
| M7 | Production gate | M6 | §12. Not part of this experiment's acceptance. |

## 9. Consequences

**Positive**
- One audited path for deposits, payouts and confirmation, instead of one per application.
- The normal path settles batches of about 16 with no proof cost.
- Certified, anchored claims can be paid without the operators after `exitDelayBlocks`.
- `PROOF_ONLY` stays available by configuration for deployments that do not want co-signing.
- The L1 brake contains bugs.
- The payment ledger and other consumers share code but not custody, state or liquidity.

**Costs**
- Two spending paths to audit, plus the co-sign service.
- In the default mode, a common-mode bug in the member body check can pay out wrongly. The
  script does not catch it.
- Exit, and every withdrawal in `PROOF_ONLY`, waits at least the delay plus one anchor interval,
  and is bounded by the MPF proof cost.
- Every anchor advance invalidates in-flight payout transactions.
- Four additions to the framework.
- Six to eight new modules.
- An application gateway is needed to relay intents, and it can censor them.
- The custody assumption is unchanged, and threshold key loss freezes the vault. These are
  stated, not solved.

## 10. Verification and test vectors

Evidence must be independent of the code under test.

**V1 — asset and value encoding**
- Vectors come from real preprod transaction outputs carrying native tokens.
- They are decoded by an independent tool, `cardano-cli` or `cardano-multiplatform-lib`.
- Cases: empty names, 32-byte names, the `ff` vs `00 00` ordering, more than 16 assets (bounded
  summary), and an empty policy with a non-empty name (rejected).

**V2 — identifiers**
- Each tagged hash is computed outside the JVM, with Python `hashlib.blake2b` and `cbor2` in
  canonical mode.
- Non-canonical encodings are rejected.

**V3 — wallet authorization**
- **Positive vectors** come from real wallets through CIP-30 `signData`: base, enterprise and
  reward addresses, and a base address with a script stake part.
- **Cryptographic oracles:** CCL, plus `@emurgo/cardano-message-signing` or `pycose`.
- **Policy negatives:**
  - payload changed by one byte;
  - swapped `address`;
  - wrong `crv` or `alg`;
  - `hashed = true`;
  - a signature made with non-empty AAD;
  - re-serialized protected headers;
  - duplicate labels, `crit`, nil payload, indefinite lengths;
  - script address;
  - stake key used for a payment account, and the reverse;
  - wrong network id, and two testnets that share network id 0;
  - wrong magic, chain, application or action;
  - a valid signature by account A naming account B;
  - signed account disagreeing with the outer command;
  - expired intent, replayed intent.
- **Session negatives:**
  - concurrent 6 + 6 against a limit of 10 in one block;
  - revoke and pay in one block;
  - the same key granted again;
  - payment by an old grant after rotation.

**V4 — accounting**
- Model-based property tests of credit, transfer, reserve and confirm.
- Overflow at `2^64 − 1` for balances.
- Turnover of lifetime counters beyond `2^64`.

**V5 — observer fixtures A1-A10**
- A1: unsolicited ADA.
- A2: mixed assets in one output.
- A3: unregistered token.
- A4: malformed or oversized datum.
- A5: wrong network or chain.
- A6: phase-2-invalid transaction.
- A7: a fake continuation or payout without a thread token.
- A8: a continuation output, never credited.
- A9: two deposits in one transaction.
- A10: a deposit and a vault spend (`Settle` or `Exit`) in the same block, in both hash orders.

**V6 — validators.** Each V1-V9 rule has at least one negative test:
- `Settle` below threshold;
- `Settle` signed by members of an older anchor datum;
- `Settle` against a `PROOF_ONLY` vault;
- a `Settle` payout whose claim id was already exited;
- a governed claim paid through `Settle`;
- `Exit` before `exitDelayBlocks`;
- forged root;
- spent anchor;
- wrong anchor identity;
- proof of the same bytes under another component's key, at the same root;
- proof of a wrong value;
- immature claim;
- `PAUSED` status;
- missing, extra or reordered insert;
- shard of another vault;
- mixed-shard batch;
- wrong shard bootstrap;
- double pay within one transaction and across transactions;
- wrong recipient, quantity or asset;
- lovelace diverted below or above `vaultLovelace`;
- a second output at the vault address;
- a deposit-shaped continuation;
- fee taken from the vault;
- another script input.

Budgets are measured, not estimated.

**V7 — end to end**
- The payout observer re-derives every paid claim from L1.
- Restart and replay yield identical roots.
- Payout from a UTxO that was never tracked.
- Parent and child payouts in one block, in both hash orders.
- A prefix split across a restart.
- An external relayer racing the local executor.
- A rolled-back payout, paid again.

## 11. Risks

| Risk | Mitigation |
|---|---|
| A colluding member threshold steals | Stated trust label (D18). The brake makes theft visible but does not prevent it. A validity-proof profile is future work. |
| Threshold keys are lost, so the anchor and all custody freeze | Stated. Key-management requirements and drills in M6. No in-profile recovery. |
| Blind signing of intents | The client shows the decoded intent. Short TTL with single-use ids. Session limits. Documentation. |
| Batch size limited by the MPF budget | Measured in M4. The co-signed batch path is a maintainer option. |
| Anchor stalls or anchor churn | Liveness only. Anchor-age metric. Rebuild on churn. Maturity tuned to the measured interval. |
| Handoff mistakes strand claims | D8b gate and doctor check. Drill under an anchor outage. |
| Dust flood to vault addresses | Costs the attacker min-ADA per output. Journal back-pressure. Metric. |
| Extraction changes EUTxO behaviour | Byte-identical golden gates (D16). |
| Scope creep | Non-goals. Two named consumers. |

## 12. Production and audit gates

No real-value deployment until all of these are done:
1. independent review of the D8 vault (both redeemers and the mode parameter), the shard
   validator and the shard thread policy;
1a. independent review of the member `Settle` body check (D9). In the default mode it is
    custody-critical;
2. independent review of `anchor.ak` and the `ScriptAnchorService` co-sign checks, which become
   custody-critical under D8;
3. independent review of the observers, `DepositAdmission` and `PayoutConfirmation`;
4. independent review of the D13 verifier;
5. custody, handoff, deep-rollback and incident drills;
6. reconciliation running in production shape;
7. trust labels visible on every user surface.

Passing tests and benchmarks is not evidence of security. These gates are in addition to FX-002
(`adr/app-layer/open_item.md` l.74).

## 13. Open questions

**Points needing a maintainer decision:**

| ID | Question | Options | Lean |
|---|---|---|---|
| D8 | Reference vault paths | — | **Decided by the maintainer in r3:** co-signed `Settle` as the default normal path, with a per-claim delayed `Exit` as the fallback. `PROOF_ONLY` can be selected by genesis configuration (D8e). Changing mode means a new vault plus governed `MIGRATE`. |
| Q1 | Who owns a token deposit's incidental lovelace? | (a) Operational ADA (r1). (b) An account-bound min-ADA credit (D11). | (b). It funds the depositor's own payouts and cannot be drained by others. This requires reading #109's "must not create credits" as "no spendable balance". |
| Q2 | Who funds payout min-ADA? | (a) The vault's operational ADA (r1; withdrawn, drainable). (b) The account's own min-ADA credit, plus any supplement from the builder. (c) Always the relayer. | (b) |
| Q3 | Direct deposits or staging and acceptance? | (a) Direct. Uncreditable value is unattributed and leaves only by a governed sweep. (b) Staging, with a depositor refund after a deadline. | (a) for v1. (b) as a later profile. |
| Q4 | Delay on the proof path? | — | **Decided in r2:** maturity plus status-leaf brake (D8a). |
| Q5 | Accept `hashed: true` (hardware wallets)? | (a) Reject in v1. (b) Accept, with Blake2b-224 of the envelope. | (a). Revisit after M6 interoperability. |
| Q6 | How do public users submit intents? | (a) Application gateway with a `SUBMIT` credential. (b) A new public write class for domain APIs. | (a) |
| Q7 | Withdrawal expiry or cancellation? | (a) None in v1. (b) Expiry, with an L1 nullifier insert first. | (a) |
| Q14 | *(new)* How is the membership handoff (D8b) enforced? | (a) A framework gate on governed-membership activation. (b) An operational rule enforced by the CLI and doctor only. | (a). (b) only until (a) lands. |

**Open, not blocking M1:**

| ID | Question | Lean |
|---|---|---|
| Q8 | Consolidation without a payout | Out of v1. A vault spend with k ≥ 1 already merges inputs. A k = 0 redeemer can come later. |
| Q9 | Owner recovery after losing the owner key | Out of v1. Rotation is D14a. |
| Q10 | Relayer bounty | None in v1. |
| Q11 | Module names and placement | Decided in M1. |
| Q12 | ADR-041's status versus code that already exists | Flagged for an ADR-041 status update. |
| Q13 | — | **Decided in r2:** V2 binds the key and the value, with the composite key derivation pinned. |
| Q15 | *(new in r3)* Use a two-level withdrawal root to make `Exit` cheaper? Proof once that the pending-claims root is in the state, then prove each claim in that small trie. | Measure in M4. Most valuable for `PROOF_ONLY` deployments. |
| Q16 | *(new in r3)* Defaults and bounds for `exitDelayBlocks` in the default mode | Decide in M4 from the measured settlement deadline and anchor interval. It must exceed the fast path's settlement deadline. |

## 14. Alternatives considered (overall)

- **A payment-specific bridge for #109.** Rejected by both issues.
- **Generalizing the EUTxO bridge in place.** Rejected: it would change a shipped profile's
  semantics and script hashes (ADR-042, D16).
- **Extending stdlib `balances` with an asset dimension.** Rejected: it has `MINT` and spending
  bound to the member who sends the envelope. The new ledger reuses its patterns, not its
  commands.
- **Writing consumers on ADR-058 guest runtimes.** Possible later. Guests cannot yet read L1
  observations, verify Ed25519 or join cascades (ADR-058 Q7). The contracts stay runtime-neutral.

## 15. Related findings (out of scope)

1. The EUTxO bridge classes that the runtime never constructs (§1.2).
2. The Javadoc at `EutxoBatchWithdrawalConfirmation.java:13-21` says observations are keyed by
   `observerId/txHash/slot`. `L1Observation.key()` now includes `eventOrdinal`.
3. `docs/site/reference-configuration.md:80-81` lists the deposit and v1 withdrawal-confirmation
   observers, but not `eutxo-batch-withdrawal-confirmation-v1`, which the v3 showcase uses.
4. Nothing advances the EUTxO bridge root thread at runtime. Together with ADR-UTXO-009
   §13.1/§13.2, this means its Exit path is armed from genesis.
5. `adr/README.md` has no rows for ADR-057 or `047-cohesive-application-deployment.md`.
6. ADR app-layer/utxo/006 and 007 are cited by 008 and 009 but are not in the repository.

## Revision history

- **r1** (2026-11-24): Initial proposal.
- **r2** (2026-11-24): Responds to two independent reviews of `55c3d9fc`: review A (Fable) and
  review B (Codex).
  - **Vault safety**
    - A-F1, B-F4: disjoint output shapes enforced by script rule V7 (D5, D8, I16).
    - B-F1, A-F3: the script computes and binds key and value (V2). Composite key pinned.
      Q13 decided.
    - A-F2, B-F5: confirmation authenticated by the shard thread token instead of tracked
      custody (D10, I18).
    - B-F3, A-F9: per-vault shards, insert bijection, verified bootstrap (D8c, I17).
    - B-F2, A-F4, B-F9: account-bound min-ADA, exact value equation V6, continuation floor,
      payout feasibility (D7, D11, I19). Q1 and Q2 leans changed.
    - A-F5: L1 brake through maturity and the status leaf (D8a). Q4 decided. Anchor leader added
      to the threat model; `anchor.ak` added to the review gates.
  - **Anchor, migration and rollback**
    - B-F6, B-F7: pinned anchor identity, membership handoff, staleness, rollback, proof floor
      (D8b, Q14, §7 framework additions).
    - A-F6, B-F10: governed sweep and migration claims, bounded value summary (D5, D8d).
    - A-F11: payout rollback (D10, D15b). ADR-042 §14 citation corrected to §1 item 8.
    - B-F8: acyclic derivation and vault addition procedure (D4, D5).
  - **Wallet authorization**
    - B-F12: the signer's credential must equal the account owner; address network id checked
      separately from magic (D13).
    - B-F13, A-F8: byte-exact COSE profile; `kid` ignored; receipts keyed by `IntentId`.
    - A-F7, B-F14: single-use intents, authorizer named in the envelope, grant ids,
      staged-writer limits, consensus clock (D13).
  - **Accounting, gates and scope**
    - B-F15: one canonical ordering, u128 lifetime counters (D3).
    - B-F11, A-F10: per-unit reconciliation with a full scan and signed in-flight terms,
      mandatory pause (D15).
    - B-F16: shard mirror, journal keying, third-party race (D9).
    - B-F17, A-F13: receipt state proofs (D14); I1 and I2 rewritten; second consumer named (D17a);
      owner rotation (D14a); observability and doctor (D15a); milestones mapped to
      invariants and vectors; M2 entry gate fixed; scope reductions listed in §2.
    - A-F12: measured proof cost and throughput caveats (D8).
  - **Inventory corrections**
    - B-F18, A-F14: contribution-kind count, unconstructed-class wording,
      `ProofWithdrawalRelayClient`, `ThresholdCosigner`, `FxKernel` / `EffectsSettings` lines,
      `AppEffectExecutorFactory:17`, `SettlementClaimsView` lines, ADR-041 §7.1, the
      reference-configuration wording, ADR repository labels, the `EutxoL2SessionKey` note,
      and unused required signers removed from the deposit claim.
- **r3** (2026-11-24): Maintainer decision on D8.
  - The default is now co-signed `Settle` batches (rule V9: a threshold of the current anchor
    datum's members) plus a per-claim delayed `Exit` with proofs.
  - `PROOF_ONLY` is a genesis-pinned vault script parameter, `settlement.mode` (new D8e). Changing
    mode requires a new vault and governed `MIGRATE` claims.
  - Rules V4-V8 are shared between the two redeemers, as are the nullifiers.
  - Governed claims are paid only through `Exit`.
  - D9 is split into the co-sign owner and the exit relayer, with a member body check.
  - The threat model now covers common-mode bugs and nullifier burning.
  - Trust labels depend on the mode.
  - D7 derived views updated.
  - New invariants I20-I22. M4 and V6 updated.
  - New open questions Q15 (two-level withdrawal root) and Q16 (exit-delay defaults).
