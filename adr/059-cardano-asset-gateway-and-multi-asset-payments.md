# ADR-059 — Cardano asset gateway, with a multi-asset payment ledger as its first consumer

- **Status:** Proposed (2026-11-24). Design only; nothing here is implemented.
- **Issues:** [#110](https://github.com/bloxbean/yano-x/issues/110) (generic Cardano asset ingress
  and settlement framework) and [#109](https://github.com/bloxbean/yano-x/issues/109) (multi-asset
  account-based payment app ledger backed by Cardano L1). Both are milestone 1, "ADR and
  inventory / threat model".
- **Evidence base:** `origin/main` at `00274933` (2026-11-17). Every path and line below was
  read at that commit.
- **Related:** ADR-041 (trust-preserving L1 observation delivery), ADR-042 (L1 observation and
  EUTxO settlement lifecycle), ADR app-layer/utxo/001 (EUTxO ledger and Cardano bridge),
  ADR app-layer/utxo/009 (claim settlement and vault conditions), ADR app-layer/011 (plugin
  architecture), ADR app-layer/031 and 031.1 (composable state machines, event bindings),
  ADR app-layer/038 (topic policies and session keys), ADR-057 (framework extraction),
  ADR-058 (execution runtime SPI, open PR #107).
- **Fulfils:** ADR app-layer/utxo/001 l.368-370: "A future generic L1 transaction co-signing SPI
  is permitted only through a separate ADR after the bridge implementation demonstrates a
  reusable need." This is that ADR, for settlement as a whole.
- **Risk:** high. The design moves real Cardano value, adds on-chain validators, and adds a
  consensus path that verifies end-user wallet signatures.

## 1. Context

Issue #110 asks for one reusable layer that brings Cardano assets into an app ledger and pays
them back out, so that application authors write business logic instead of another bridge.
Issue #109 asks for the first account-based application on that layer: users deposit ADA or
registered native tokens, transfer them with wallet-signed or session-signed intents, and
withdraw part or all of a balance while the ledger keeps running.

Yano X already has one bridge: the EUTxO Cardano bridge (`ledgers/eutxo/bridge-cardano`,
`ledgers/eutxo/bridge-onchain`). This ADR starts from what that code actually does, not from the
component list in #110.

### 1.1 What exists today

Reusable framework pieces (all in Yano X since ADR-057):

| Piece | Where | Relevance |
|---|---|---|
| Block-scoped L1 observers | `L1ObserverProvider` / `L1Observer` (`framework/api/.../appchain/l1view/`). `observe(slot, blockHash, Block)` returns `List<L1Observation>` (`L1Observer.java:42`) | Ingress and confirmation observation. Every validator recomputes; the proposer injects stability-deep facts as `~l1/<id>` messages; followers verify (`L1ObservationService.java:333-361`). |
| Observation identity | `L1Observation(observerId, anchor, eventOrdinal, slot, blockHash, claim)`; `key()` includes `eventOrdinal` (`L1Observation.java:43, 224-226`) | One transaction may carry several observations. |
| Consensus-pinned observer identity | `L1ObserverProvider.consensusIdentity(...)` (`L1ObserverProvider.java:26`) | Claim schema and ABI changes fail startup instead of forking silently. |
| Durable observation journal | `L1ObservationJournal` (`framework/cardano/.../L1ObservationJournal.java`), wired at `AppChainSubsystem.java:1115` | Delivery metadata. ADR-041 is still marked Proposed although this journal exists. |
| Stability depth | `yano.app-chain.l1.stability-depth`, one per chain; observers refuse to start when it is 0 (`AppChainSubsystem.java:1124-1130`) | No per-observer or per-asset depth. |
| Deterministic effects | `AppEffectEmitter.emit(EffectIntent)`; executors via `AppEffectExecutorFactory` (`framework/cardano-api/.../effects/AppEffectExecutorFactory.java:26`) | Outbox for settlement. Delivery is at-least-once; the executor must be idempotent on `idHash`. |
| Effect results | `~fx/result`, accepted from any one member or a designated signer (`FxKernel.java:288`) | A single-member attestation. It must never confirm value movement. |
| Script anchoring | `ScriptAnchorService` (`framework/cardano/.../ScriptAnchorService.java:80-101`) advances a thread-NFT UTxO under a member-threshold validator (`framework/cardano-onchain` `AnchorValidator`) | An L1-visible, threshold-signed state root. |
| On-chain proof library | `sdk/proof-onchain`: `MpfAnchorLib.acceptedAnchor(...)`, `MpfOnChainVerifier.verifyInclusion(...)` | A script can authenticate the anchored root and verify an MPF inclusion proof against it. |
| Delegated session keys | Topic registry delegations: `TopicWire.Delegation(delegate, scope, notBefore, notAfter, issuer, sig)` (`TopicWire.java:371`); revocation in `TopicRegistryKernel` | Ed25519 sessions scoped to a topic namespace, with expiry and revocation, but no amount limits. |
| Atomic multi-machine transitions | Java `CompositeWorkflow` and declarative `EventBindingWorkflow` (ADR-031.1) | Two machines can commit in one block transaction; the declarative route needs `TransitionKernel` machines. |
| Plugin catalog | Ten contribution kinds (ADR app-layer/011 §6); `effect-executor` comes from `framework/cardano-api` | Activation is always explicit by configuration. |

The EUTxO bridge (inventory in §1.2) is the only ingress and settlement implementation.

### 1.2 The EUTxO bridge, measured

- **Wired at runtime:**
  - three observers: `eutxo-vault-deposit-v1`, `eutxo-withdrawal-confirmation-v1`,
    `eutxo-batch-withdrawal-confirmation-v1`;
  - the `eutxo-settlement` effect executor (`EutxoSettlementExecutorFactory` →
    `QuickTxSettlePipeline` + `SettlementCosignService` + `PipelinedSettlementExecutor`);
  - a domain API.

  The manifest is `ledgers/eutxo/bridge-cardano/src/main/resources/META-INF/yano/plugins/org.yanoproject.x.eutxo.bridge.cardano.json`.
- **Library only, with no production caller:** `WithdrawalCoordinator`, `FileSettlementJournal`,
  `ExternalSettlementSigner`/`HttpExternalSettlementSigner`, `BatchSettlementExecutor`,
  `VaultWithdrawalTransactionBuilder`, `ProofWithdrawalTransactionBuilder`,
  `DepositAcceptanceBuilder`, `BridgeRollbackGuard`, `BridgeDoctor`. Only tests and READMEs
  reference them.
- **ADA only:**
  - the deposit observer rejects any output that carries a native asset
    (`AcceptedVaultDepositObserver.java:210-226`);
  - both confirmation observers reject native assets;
  - the ledger, the custody gate (`SettlementClaimsView.java:124-126`) and every validator
    compare lovelace only.

  ADR-042 §15.2 item 5 makes this a property of the initial settlement profile.
- **The settlement journal on the wired path is in memory** (`EutxoSettlementExecutorFactory.java:85, 96-99`).
  Restart safety comes from re-reading committed state and the on-chain nullifier.
- **Already protocol-neutral:**
  - `SettlementCosignService` / `SettlementCosigner`, which take the custody check as an
    injected `Predicate<byte[]>`;
  - `CardanoSettlementBackend` / `NodeSettlementBackend` / `CclNodeAdapters`;
  - `ValidTransactions`, `BridgeDatumPreflight`, `ObserverConsensusIdentity`, `BridgeRollbackGuard`;
  - the journals, apart from a 32-byte-hex key rule;
  - the on-chain MPF library and the shape of `NullifierShardValidator`.
- **EUTxO-bound:** every claim record and datum in `ledgers/eutxo/contracts`, the claim-id
  derivation, `SettlementClaimsView`, and the ledger's `importDeposit` / `confirmWithdrawal`.
- **Vault (ADR app-layer/utxo/009, implemented):**
  - `SettlementVaultValidator` has a threshold-signed Settle path and a permissionless,
    fallback-delay-armed Exit path that proves claims by MPF inclusion against a bridge-specific
    root thread (`SettlementRootValidator`);
  - nullifiers live in 16 shard threads.
  - Two gaps are recorded in ADR-UTXO-009 §13: the fallback-delay floor is not enforced on chain,
    and with `updatedAtMillis = 0` Exit is armed from genesis.
  - The inventory found no runtime service that advances the bridge root thread.

### 1.3 What does not exist

- No observer for native assets or multi-asset values. `address-deposit` reports lovelace only.
- No payout executor for native assets. `cardano.payment` (`connectors/effects-cardano`) is
  ADA only.
- No multi-asset or account ledger. Stdlib `balances` has a single unit, has `MINT`, has no burn,
  and only chain members can spend (`BalancesContract.java:16-17, 42`).
- No CIP-8 / COSE_Sign1 verification anywhere in Yano X or Yano core.
  cardano-client-lib 0.8.0-pre7 ships `cardano-client-cip8` and
  `cardano-client-cip30` (`CIP30DataSigner.verify(DataSignature)`). They reach some modules
  transitively, but nothing uses them.
- No general end-user account-key SPI. Users who are not members authorize through topic writer
  proofs, ADR-019 actor keys, or application-specific keys, always relayed in a member-signed
  envelope.
- No in-script verification of the app ledger's `FinalityCert`. L1 trust flows through the
  anchored root.
- No public write route for plugins. `DomainApiAccess` is `READ`, `PRIVILEGED` or reserved
  `INTERNAL` (`DomainApiAccess.java`). The message submit route needs `SUBMIT` access
  (`AppChainResource.java:142`).

## 2. Goals and non-goals

**Goals**

1. One optional, plugin-packaged Cardano asset gateway for ADA and native fungible assets: deposit
   observation, settlement execution, payout confirmation, and reconciliation.
2. A small deterministic contract that any state machine can consume, whatever its execution
   model: accounts, virtual UTxOs, pools.
3. A reference custody profile in which any relayer can pay out a certified claim without
   custody keys.
4. Reusable Cardano wallet authorization: CIP-8 through CIP-30 `signData`, plus owner-authorized
   session keys.
5. The #109 payment ledger as the first consumer. A second minimal consumer proves the
   contract. The EUTxO bridge adopts shared helpers with no change in behaviour.

**Non-goals**

These follow #110 §14 and #109's non-goals:
- trustless or unilateral exit;
- a universal vault script;
- shared liquidity across applications;
- swaps, issuance, lending;
- a DEX product;
- changing EUTxO bridge semantics or script hashes;
- a new contribution kind or host SPI in v1;
- production funds before §12's gates close.

## 3. Threat model and trust assumptions

**Parties**
- **Cardano consensus** is assumed honest and final beyond the configured stability depth.
- **App ledger members** (n keys, threshold t) certify blocks and the anchored root.
  - **Custody assumption:** a colluding threshold can anchor a fabricated root, and with it
    authorize any payout from the reference vault. This restates ADR-UTXO-009 §7.2 and §13.3.
    The quorum can also censor users.
  - A minority below threshold cannot move funds or forge facts.
- **The scheduled proposer and the settlement relayer** hold liveness power only (ADR-042 §3,
  ADR-UTXO-009 §11.3).
- **End users** are untrusted. They sign intents with wallet keys or session keys. A stolen
  session key is bounded by its limits until revoked.
- **Anyone on L1** is untrusted and may author arbitrary outputs, datums, tokens, payments to vault
  addresses, and fake "payout" markers.
- **The application gateway** relays user intents into member-signed envelopes. It can delay or
  drop intents. It cannot alter them, because the user's signature covers every field the state
  machine acts on.

**Secrets**
- member signing keys and anchor co-signing keys;
- relayer fee wallets, which hold no custody authority;
- user wallet keys, which never leave the wallet;
- session private keys, which stay in the client.

**Trust boundaries**
1. L1 → observer → finalized `~l1/*` message. ADR-042: operators never author facts.
2. User intent → state machine. Only the state machine decides.
3. Finalized state → anchored root → L1 vault script. Only the script releases value.
4. Local executor → L1 transaction. The executor has liveness only, never authority.

**Trust labels** (D18) state this per profile. The reference profile is labelled
`FEDERATED_CUSTODY_PERMISSIONLESS_RELAY`. Certified claims can be relayed by anyone; exit without
the quorum's cooperation is not provided.

## 4. Pinned references

| Ref | Version | Used for |
|---|---|---|
| CIP-0008 Message Signing | cardano-foundation/CIPs `86b89208` (2026-06-04), Status: Active | COSE_Sign1 layout, `Sig_structure`, the `address` protected header, the `hashed` header (§"Payload encoding") |
| CIP-0030 dApp-Wallet Web Bridge | cardano-foundation/CIPs `695efa05` (2026-10-13), Status: Active | `api.signData(addr, payload)` profile, quoted below |
| RFC 8152 (COSE) | as cited by CIP-0008 | `Sig_structure` construction |
| Conway ledger CDDL | IntersectMBO/cardano-ledger `9ef91412` (2026-07-19), `eras/conway/impl/cddl/data/conway.cddl` | `asset_name = bytes .size (0 .. 32)` (l.52); `value = coin / [coin, multiasset<positive_coin>]` (l.174); `multiasset<a0> = {* policy_id => {+ asset_name => a0}}` (l.178); `positive_coin = 1 .. max_word64` (l.193); `policy_id = script_hash` (hash28) |
| cardano-client-lib | 0.8.0-pre7 (`gradle/libs.versions.toml:4`) | Candidate COSE parser. Not the verifier of record; see D13. |

CIP-0030 `signData`, quoted from l.354-371:
- "The payment key from `addr` will be used for base, enterprise and pointer addresses to
  determine the EdDSA25519 key used. The staking key will be used for reward addresses."
- Headers: `alg` (1) must be `EdDSA` (-8); `kid` is optional; `"address"` must be the raw address
  bytes.
- "The payload is not hashed and no `external_aad` is used."
- The COSE_Key must have `kty` OKP (1), `alg` EdDSA (-8), `crv` Ed25519 (6), and `x` (-2) set to
  the public key.

**Conflict.** CIP-0008 lets a signer set the `hashed: bool` unprotected header and sign the
Blake2b-224 hash of the payload, which hardware wallets may need. CIP-0030 says the payload is not
hashed. See Q5.

## 5. Decisions

### D1. An optional capability built only from existing contribution kinds

The gateway is a set of libraries plus one runtime bundle. The bundle contributes only existing
kinds:
- `l1-observer` (CONSENSUS) for deposit and confirmation observers;
- `effect-executor` (PRIVILEGED_LOCAL) for the settlement relayer;
- `domain-api` (PRIVILEGED_LOCAL, read routes and unsigned-transaction builders).

The consuming application ships its own `app-state-machine`.

No new contribution kind, host SPI or API level is introduced in v1. Nothing activates because a
bundle is installed: observers activate through `observers.<id>.type`, the executor through
`effects.executors.<scheme>.*`, and the machine through the chain's recipe (ADR app-layer/011
P4/P5). A chain that does not select these contributions loads no gateway code paths.

*Alternatives.*
- A new `asset-gateway` contribution kind: rejected. It needs an API level bump and a second
  activation path for behaviour the existing kinds already express.
- Building the gateway into the framework: rejected by #110 §3.1 and the AGENTS.md rule that
  optional runtime behaviour crosses the catalog boundary.

### D2. Modules and dependency direction

The names are provisional and are settled in M1 (Q11).

| Module | Kind | Contents |
|---|---|---|
| `capabilities/asset-gateway-contracts` | pure library | Asset and value types, identifiers, canonical CBOR codecs, deposit/claim/confirmation records, the deterministic accounting helper (D12), invariant checkers, test vectors. No Cardano client dependency. |
| `capabilities/asset-gateway-cardano-support` | pure library (no manifest) | Protocol-neutral helpers lifted from the EUTxO bridge (D16): valid-transaction filter, datum preflight, observer identity builder, value extraction, journals, cosigner, settlement backend, CCL node adapters, rollback guard. |
| `capabilities/asset-gateway-cardano` | runtime plugin bundle | The reference observers (D5, D10), the settlement relayer executor (D9), and the domain API (deposit instructions, unsigned deposit builder, claim and proof lookup, reconciliation report). |
| `capabilities/asset-gateway-onchain` | on-chain artifacts | Julc sources and checked `plutus.json` for the reference vault and nullifier validators (D8). |
| `capabilities/cardano-wallet-auth` | pure library + JS SDK piece | CIP-8 verifier, account credentials, canonical intent envelope, session grants (D13). |
| `ledgers/asset-payments/{contracts,ledger,client}` | contracts lib, state-machine bundle, client | The #109 payment ledger (D14). |
| `examples/asset-payments-*` | examples | Reference web client and recipe; the second minimal consumer (M5). |

Dependency rules:
- Deterministic modules (`*-contracts`, `cardano-wallet-auth`, the payment ledger) never depend
  on `asset-gateway-cardano` or on network code.
- The EUTxO bridge embeds `asset-gateway-cardano-support`, never the gateway bundle.
- All modules follow `yano-x → yano` and are registered in `config/artifacts-v1.json`.

### D3. Canonical assets and amounts

- `AssetId = (policyId: 0 or 28 bytes, assetName: 0..32 bytes)`. ADA is the unique id with an
  empty policy and an empty name. Names are raw bytes, never tickers or hex strings.
- Ordering is lexicographic over `policyId ‖ assetName`, with policy length first so that ADA
  sorts lowest.
- `Quantity` is an unsigned 64-bit integer, which matches `positive_coin` on L1. Ledger-internal
  sums (supply, liabilities, reserves) are checked u64 arithmetic: overflow rejects the
  transition and never wraps.
- `AssetValue` is a canonical sorted map from `AssetId` to a positive `Quantity`, with zero
  entries removed. It has a profile-declared bound on entries (default 16).
- The encoding is canonical CBOR as defined by RFC 8949 §4.2.1 (core deterministic encoding),
  with definite lengths only. Decoders reject non-canonical bytes.
- Decimals and tickers are display metadata in the asset registry. They never take part in
  arithmetic or identity.

### D4. Domains and identifiers

Every identifier is `blake2b-256(tag ‖ canonical CBOR of its fields)`. Each tag is a distinct
ASCII string ending in `\0`.

| Id | Fields |
|---|---|
| `GatewayDomain` | Cardano network magic, app ledger chain id, genesis id, application id, vault profile id, profile version |
| `VaultId` | `GatewayDomain`, `AssetId`, vault script hash |
| `DepositId` | `VaultId`, accepted L1 tx id, output index |
| `ClaimId` | `GatewayDomain`, application-chosen withdrawal key, recipient address bytes, `AssetValue`, sequence |

`ClaimId` is derived from fields fixed when funds are reserved. Finality evidence and the anchored
root are attached later and never feed into the id. This avoids the circular commitment #110 §5
warns about.

`DepositId` is the permanent exactly-once key. This matches ADR-041 §10 ("the accepted L1 outpoint
is the permanent exactly-once key"), made domain-separated.

### D5. Ingress: one generic multi-asset deposit observer, with custom observers allowed

The bundle ships the observer type `asset-vault-deposit-v1`.

**Configuration.** Its settings bind one `GatewayDomain` and a set of vaults. Each vault entry
gives the asset id, vault address and script hash. The datum codec id comes from a closed list
shipped in the bundle; v1 has one codec, `account-beneficiary-v1`. Bounds cover the datum
(16 KiB, depth 16, as in ADR-042) and the quantity. All of these feed `consensusIdentity`.

**Per block.**
- The observer reads only phase-2-valid transactions.
- For each output paying a configured vault address, it emits one observation with
  `eventOrdinal = output index`, carrying an `AssetDepositClaimV1`:
  - `DepositId`, `VaultId`, accepted outpoint;
  - the output's full `AssetValue`;
  - the decoded beneficiary, or a `MALFORMED` reason;
  - the required signers of the transaction.
- The observer does not decide whether the deposit is creditable. It reports facts. Admission
  belongs to the consumer (D6), so every validator applies the same rule to the same bytes.
- It never throws on L1 content (ADR-042 amendment of 2026-10-10).

**Deposit shape for profile v1** (#109 §2):
- An output is creditable when it carries exactly one registered asset of its vault and at least
  the lovelace that the min-ADA rule forces on it.
- For a native-token vault, that lovelace is `incidentalLovelace`, not a credit. Q1 decides what
  happens to it.
- For the ADA vault, all lovelace is the credit.
- Any other asset makes the output uncreditable.

**Custom observers.** An application with its own datum or staging protocol writes its own
`L1ObserverProvider`. It reuses the support helpers and must emit an `AssetDepositClaimV1`
through the same observation path, so consumers stay unchanged. No callback or webhook can
create a deposit (ADR-041 §7).

*Alternatives.*
- Decoding beneficiaries through a pluggable codec SPI inside the observer: rejected for v1.
  A consensus-tier codec chosen at runtime enlarges what consensus depends on. The closed list
  plus custom observers covers both consumers.
- One observation per transaction, as the EUTxO bridge does: unnecessary now that `eventOrdinal`
  exists.

### D6. Exactly-once credit belongs to the consumer; the library makes it hard to get wrong

The consuming state machine credits in its own `apply()`. The contracts library provides
`DepositAdmission.admit(reader, writer, claim, registry)`. In one writer pass it:
1. checks the observation envelope: observer id, anchor equals claim tx id, slot and block hash;
2. checks that `deposit/<DepositId>` is absent;
3. applies the profile admission rule and the asset registry status;
4. writes the deposit record (CREDITED, or UNCREDITED with a reason);
5. returns a credit instruction that the caller must apply in the same transition.

The record and the credit commit together or not at all. A replayed observation is a no-op.

Uncreditable inputs are never errors. These include unregistered or paused assets, malformed
datums, a wrong domain, mixed assets, and zero amounts. Each is recorded as an `UNCREDITED`
deposit notice with its value. It counts toward reconciliation as unattributed custody (D15).
Recovering such funds needs a governed procedure (Q3). It is never automatic.

A deep L1 rollback below a credited deposit is handled by the existing journal quarantine
(`DEEP_L1_ROLLBACK_BELOW_FINALIZED_OBSERVATION`) and the ADR-042 §14 halt. The gateway adds no
automatic compensation.

### D7. Settlement starts with an effect at the reserving transition; effect results never confirm

When a consumer authorizes a withdrawal, the same `apply()` does all of the following:
- moves the amount from available to reserved;
- writes `claim/<ClaimId>` with status `RESERVED`;
- writes the claim commitment leaf at a key the vault script can prove;
- emits an `EffectIntent` of type `asset.settlement` with payload
  `SettlementIntentV1(ClaimId, GatewayDomain, VaultId, AssetValue, recipient, profileVersion)`,
  `FinalityGate.APP_FINAL` and `ResultPolicy.NONE`.

Every field of the intent comes from finalized state. A relayer cannot choose any of them.

Confirmation comes only from the confirmation observer (D10). `~fx/result` is a single-member
attestation (`FxKernel.java:288`), so it may drive local telemetry but never changes claim
status (ADR app-layer/011 §12).

**Claim lifecycle in consensus:** `RESERVED → PAID`, or `PAID_MISMATCH`, which halts (D15).

**Derived views, not consensus state:**
- `CERTIFIED`: the claim's leaf is under an anchored root that L1 has seen.
- `SUBMITTED`: local relayer telemetry.

v1 has no cancellation. A reserved claim is never returned to the user's balance, because an
anchored claim stays redeemable (#109 §4, I8). Expiry with enforceable invalidation is Q7.

### D8. Reference custody profile: `anchored-claim-vault-v1`

**Lean, needs maintainer decision (P1).**

**Vault layout.**
- Each registered asset has its own vault script. The script is parameterized by `VaultId`
  fields, so each asset has a distinct script hash and address. #109 §2 asks for this isolation.
- A vault may hold many UTxOs, which limits contention.
- Each vault UTxO holds its asset plus lovelace. For a native-token vault that lovelace is
  operational ADA (D11).

**Spending rule (a single redeemer, `Pay`).** A spend of vault UTxOs is valid only when:
1. the transaction references the chain's script-anchor UTxO, authenticated by
   `MpfAnchorLib.acceptedAnchor` with the pinned thread policy, genesis id, application id,
   commitment profile and format fingerprint;
2. for each paid claim, an MPF inclusion proof shows the claim commitment leaf under the anchored
   state root. The leaf binds `ClaimId`, `VaultId`, `AssetValue` and recipient;
3. each claim id is inserted into its nullifier shard in the same transaction. This reuses the
   `NullifierShardValidator` design: 16 MPF shard threads, insert only if absent;
4. outputs `[0, k)` pay exactly the claimed asset quantity to exactly the claimed recipient, in
   claim order;
5. a continuing vault output at index `k`, at the same script, keeps every input asset minus the
   payouts. Lovelace may decrease only by the min-ADA allowance of D11;
6. no other asset leaves, and no vault UTxO of another `VaultId` is spent;
7. fees and collateral come from non-vault inputs.

**Why this profile.** The anchored root is already threshold co-signed by the members
(`ScriptAnchorService`, `AnchorValidator`). That signature is the certification #109 §4 asks for:
"an inclusion proof against an appropriately certified withdrawal commitment". As a result:
- any relayer, including the user, can pay out a certified claim without custody keys and
  without a co-signing round;
- the nullifier makes payout at most once;
- custody trust is exactly the member threshold, the same as A2 in ADR-UTXO-009.

**Cost.** Withdrawal latency is at least one anchor interval (`anchor.every-blocks`,
`anchor.max-interval-minutes`). Each claim costs one MPF proof on chain. M4 must measure the
batch size that fits the execution budget.

*Alternatives.*
- **ADR-UTXO-009's dual path** (threshold-co-signed fast Settle plus delay-armed Exit): deferred.
  The co-signed path can be added later as a second redeemer that reuses `SettlementCosignService`.
  v1 needs only one path, and the co-signed path adds a per-payout signing round.
- **On-chain Ed25519 verification of a per-claim threshold certificate**
  (`verifyEd25519Signature`, t signatures per claim): rejected for v1. Certificate bytes and
  signer-epoch rotation would have to be verified on chain, which duplicates the anchor's
  rotation logic.
- **Reusing the EUTxO bridge's root thread:** rejected. It has no runtime advancer (§1.2), and the
  generic anchor already exists and is advanced.

**Signer rotation and history.** Membership changes go through governed membership
(`GovernedMembership`, ADR 008.3). The anchor's current members authorize the next datum's member
set. A claim anchored under an earlier root stays payable after rotation, because only the latest
anchor is the reference input and the claim leaf persists in state until paid (I9). Rotation
cannot bypass custody: the vault trusts only the anchor thread and the nullifier threads.

### D9. Settlement relayer: a replaceable local executor

The bundle's `asset-settlement` executor:
1. takes pending `asset.settlement` effects;
2. waits until the claim's leaf is under an anchored root that L1 has seen;
3. fetches proofs from the local node;
4. selects vault UTxOs (canonical outpoint order; on contention, re-selects);
5. batches up to a profile cap;
6. builds and submits the transaction with its own fee wallet;
7. polls for inclusion.

The executor is liveness only. A wrong or malicious executor can fail to pay. It cannot pay the
wrong amount, pay the wrong person, or pay twice: the script enforces all three (I5-I7).

**Journal.** The executor journal is durable. It reuses `FileSettlementJournal`, generalized to
`ClaimId` keys. The journal is a cache, not a trust root: after a restart, committed claim status
plus the on-chain nullifiers decide what is still payable.

Any external party can run the same builder from the client SDK. The domain API serves claim
proofs. The executor is selected per node, so operators can replace it (D17).

### D10. Confirmation: a custody-gated observer, as in ADR-042

`asset-vault-payout-v1` emits one observation per vault-spending transaction. The observation
records:
- the spent outpoints;
- the continuing vault outpoint and its value;
- the nullifier inserts;
- the positional payouts.

The consumer applies it through `PayoutConfirmation.apply(...)`.

**Custody gate.** At least one spent outpoint must be tracked vault custody, and the custody set
rotates to the continuing output. Without that, the observation is ignored with reason
`CUSTODY_UNPROVEN`.

**Matching.** Each entry must equal its claim exactly: `ClaimId`, `AssetValue` and recipient.

**Outcomes.**
- A custody-proven mismatch halts the vault domain (D15).
- An unauthenticated look-alike is ignored and never halts (ADR-042 §15).

**Deposit tracking.** Vault-change and consolidation outputs are recognized as continuations of
tracked custody, never as new deposits. A deposit must be a new output at a vault address that
does not spend tracked custody.

### D11. Fees, min-ADA and operational ADA

These rules follow #109's "Operational ADA must not silently consume customer ADA backing":
- Transaction fees and collateral always come from the relayer's own inputs.
- Each vault domain keeps an operational-ADA account in consensus state, separate from customer
  ADA liabilities. Its credits are:
  - the `incidentalLovelace` of native-token deposits, if Q1 adopts the lean;
  - explicit governed top-ups, which are observed deposits to the vault with a `TOP_UP` datum.
- A native-token payout output needs min-ADA. The script lets that vault's lovelace decrease by at
  most `minAdaAllowance × k`, where `minAdaAllowance` is a profile parameter, bounded and
  governed. The ledger debits the operational-ADA account by the same amount when it confirms.
  Script parameters cannot read protocol parameters, so this allowance is the bound; Q2 records
  the alternatives.
- In the ADA vault, customer ADA is the payout itself. Operational ADA is never mixed with it.
- An asset's fee or bounty is never taken from another asset. v1 has no relayer bounty (Q10).

### D12. Optional accounting: a deterministic library, not a state machine

`AssetAccounts`, in the contracts module, gives:
- per `(AccountId, AssetId)`: `available` and `reserved` amounts;
- per `AssetId`: liability totals;
- the primitives `credit(depositId)`, `transfer`, `reserve(claimId)`, `consumeReserved(claimId)`
  and `release(claimId)`. `release` is unusable in v1 (D7);
- checked arithmetic, an invariant check, and a fixed key layout under the consumer's own
  namespace.

`credit` requires a `DepositAdmission` credit instruction. `transfer` does not change the total of
any asset. No primitive can increase total liabilities except a deposit credit (I3).

**Why a library.** Business state and balances then commit in one machine's `apply()`, which is
atomic by construction. A separate accounting state machine would need an ADR-031.1 cascade for
every business action, and its kernel would have to expose debit and credit commands that other
machines could invoke.

**Later path.** A `TransitionKernel` wrapper can come when a second consumer needs cross-machine
composition. A DEX-like application uses the same primitives for pool compartments, or ignores
the library.

### D13. Cardano wallet authorization library

The library has three parts.

**1. CIP-8 verifier (CIP-30 `signData` profile).** It accepts a `COSE_Sign1` and `COSE_Key` and
accepts the signature only if all of the following hold:
- the protected header has `alg = -8` and an `address`;
- `kid`, if present, is equal in both structures;
- `hashed` is absent or false (Q5);
- the payload is attached;
- `external_aad` is empty;
- the COSE_Key has `kty = 1`, `alg = -8`, `crv = 6` and a 32-byte `x`;
- the Ed25519 signature over `Sig_structure = ["Signature1", protected, h'', payload]` verifies;
- `blake2b-224(x)` equals the key credential implied by the address:
  - the payment credential for base, enterprise and pointer addresses;
  - the stake credential for reward addresses;
  - script credentials and Byron addresses are rejected;
- the address's network id equals the domain's network;
- the payload decodes as a canonical `IntentEnvelopeV1` (below).

The verifier of record is written in the library as a small, exact implementation. CCL's
`CIP30DataSigner.verify` and an independent non-Java implementation serve as differential oracles
in tests (§10). This is a lean; maintainers may prefer a thin wrapper over CCL.

**2. `AccountId` and intents.**
- `AccountId = (credentialKind ∈ {PAYMENT_KEY, STAKE_KEY}, keyHash28)`. A payment-key signature
  never authorizes a stake-key account, and the reverse is also true.
- `IntentEnvelopeV1` binds:
  - the domain tag `yano-intent-v1`, network magic, chain id, genesis id and application id;
  - the action type and `AccountId`;
  - a per-account nonce, which must be strictly increasing;
  - `notAfter`;
  - the action body (canonical CBOR).
- Wallets show `signData` payloads as raw bytes or text. The reference client therefore shows the
  decoded intent, and the docs state that wallets do not render these intents (#109 §3.1).

**3. Session grants.**
- An owner-signed `AUTHORIZE_SESSION` intent registers a session record with:
  - an Ed25519 session public key;
  - allowed actions, which by default exclude withdrawal and further delegation;
  - an asset allowlist;
  - a per-payment limit and a cumulative limit per asset;
  - an optional recipient allowlist;
  - `notAfter`.
- Session-signed intents use the same envelope, with a plain Ed25519 signature by the session
  key.
- Limits are enforced, and cumulative usage is written, in the same transition as the payment.
  Blocks are applied sequentially, so concurrent intents cannot exceed a limit.
- `REVOKE_SESSION` takes effect when it is finalized.

*Alternative.* Reusing topic-registry delegations: rejected for v1. Their scope is a topic
namespace with expiry. They have no per-asset amount limits, their issuer must be a policy writer
rather than a wallet credential, and ADR app-layer/039 lists CIP-8 as a non-goal. The session
record is shaped so that it could become a topic delegation profile later.

**Ingress of user intents.** Users are not members. A user intent travels as the body of a
member-signed message submitted through the existing `SUBMIT` route by an application gateway.
The gateway pattern is the one the topic-session delegation service already uses. The user's
signature is the authority; the gateway can only censor or delay. A public write class for domain
APIs is not needed in v1 (Q6).

### D14. The #109 payment ledger: `asset-payments`

**State:** chain domain, asset registry, accounts, sessions, nonces, balances (D12), deposit
records (D6), claims (D7), custody set and operational ADA (D10, D11), per-asset counters, and the
halt and pause flags. All of it lives in the authenticated state root.

**Asset registry.**
- Each entry holds `AssetId`, `VaultId`, display metadata, status and `addedAtHeight`.
- Status is one of `ACTIVE`, `DEPOSITS_PAUSED`, `TRANSFERS_PAUSED`, `WIND_DOWN` (withdraw only)
  and `RETIRED`.
- Genesis provides the initial set. Later changes require a governed command by threshold-identical
  member submission, the same pattern as governed membership.
- `AssetId` and `VaultId` are immutable while liabilities, unresolved deposits or unpaid claims
  exist.
- `RETIRED` requires all three to be zero.

**Commands (user-signed unless noted).**

| Command | Effect |
|---|---|
| `REGISTER_ACCOUNT` | CIP-8 proof of the credential. Creates the account. Idempotent. |
| `AUTHORIZE_SESSION` / `REVOKE_SESSION` | D13. Owner only. |
| `TRANSFER` | One asset. Checks the signature or session, nonce, `notAfter`, asset status, positive amount, sufficient available balance and policy. Debit and credit are atomic. |
| `REQUEST_WITHDRAWAL` | Owner only by default. One asset, one exact recipient address. Reserves the amount, creates the claim and emits the effect (D7). |
| deposit credit | Not a command. Only a finalized `asset-vault-deposit-v1` observation can credit (D6). |
| payout confirmation | Not a command. Only a finalized `asset-vault-payout-v1` observation (D10). |
| `ASSET_*` | Governed registry changes. |

There is no administrative mint, no balance override, and no withdrawal bypass (I3).

**Receipts.** Each accepted command writes a receipt under its message id. The existing message
inclusion proofs and the finalized commitment (`FINALIZED_COMMITMENT.md`) make receipts and
balances independently checkable under the trust labels.

### D15. Reconciliation and halts

Consensus counters are kept per vault domain and asset `A`:
- `credited(A)`;
- `paid(A)`;
- `available(A)`;
- `reserved(A)`;
- `unattributed(A)`;
- for the ADA vault, `operationalAda`.

**Invariant.** `credited(A) − paid(A) = available(A) + reserved(A)` holds at every finalized
height (I4).

**L1 reconciliation** is a local report, not consensus. The domain API compares the sum of
tracked vault custody on L1 with:

`available(A) + reserved(A) + unattributed(A) + operational(A) + in-flight`

where `in-flight` covers observed-but-unfinalized deposits and payouts. A surplus of one asset
never covers a deficit of another.

**Halts.**
- A discrepancy raises an alert, and members may submit a governed `PAUSE` for the affected asset
  or the whole domain.
- A custody-proven payout mismatch (D10) halts the domain deterministically in consensus.
- No code path "fixes" a discrepancy by writing balances.

### D16. The EUTxO bridge adopts shared helpers incrementally, byte for byte

Only the protocol-neutral classes in §1.2 move to `asset-gateway-cardano-support`. The bridge then
depends on them. Each move is gated by golden tests showing no change to:
- observer claim bytes;
- `consensusIdentity` bytes;
- ledger state roots over the existing E2E fixtures;
- compiled script hashes. No bridge validator changes.

EUTxO claim records, datums, the ledger, and the lovelace-only profile stay where they are.
Moving the bridge to multi-asset would be a new EUTxO profile under its own ADR.

**Cleanup.** The nine unwired classes in §1.2 are kept or deleted by the bridge owner in a
separate PR. AGENTS.md prefers removing prerelease debt. This ADR only notes them.

### D17. Replaceable providers and conformance

Each issue role maps to an existing mechanism:

| #110 role | Mechanism | Replaceable how | Consensus-relevant? |
|---|---|---|---|
| L1 observation | `l1-observer` type | A custom provider emitting `AssetDepositClaimV1` | Yes: pinned by `consensusIdentity` and genesis config |
| Vault/deposit protocol | profile id + codec id + script hashes | A new profile version | Yes: genesis/governed |
| Settlement executor | `effect-executor` scheme | Any executor or external relayer | No: liveness only |
| L1 tx construction | builder in support lib / SDK | Any builder, because the script is the check | No |
| Signing provider | none in the reference profile (no custody keys); `signer-provider` only for anchor co-signing, as today | Existing SPI | No |
| Settlement journal | journal interface | Any durable implementation | No |
| Action authorization | `cardano-wallet-auth` library inside the machine | A different library in a different machine | Yes: it is part of the machine |
| Accounting | `AssetAccounts` library | Custom state or none | Yes: part of the machine |

Consensus-relevant choices are pinned at genesis or changed by governed activation. Startup
fails closed on any mismatch, and nothing falls back to a weaker profile.

**Conformance kit** (M5), modelled on ADR-058's TCK. It runs:
- the canonical vectors;
- an observer replay against recorded blocks;
- adversarial L1 fixtures;
- the validator negative suite;
- an executor crash/restart and contention script.

A replacement provider must pass the kit to be called compatible.

### D18. Trust labels are data

The profile descriptor carries a machine-readable trust label: `FEDERATED_CUSTODY_PERMISSIONLESS_RELAY`
for D8. The capability catalog, `appchain` CLI, domain API `info` route and reference client all
display it. The wording follows ADR app-layer/utxo/001 l.350-352: the threshold can censor
withdrawals and can authorize an invalid root.

## 6. Invariants

| ID | Invariant | Decisions |
|---|---|---|
| I1 | No balance increases without a finalized deposit observation whose `DepositId` was never credited before. Re-delivery, rescan and restart are no-ops. | D5, D6 |
| I2 | An observer or `apply()` never throws, halts or credits on content that anyone on L1 can author. Only custody-proven mismatches halt. | D5, D6, D10 |
| I3 | Total liabilities of an asset change only by a deposit credit (+) or a payout confirmation (−). `TRANSFER` preserves per-asset totals. No mint, override or cross-asset effect. | D12, D14 |
| I4 | `credited(A) − paid(A) = available(A) + reserved(A)` at every finalized height. All amounts are non-negative; arithmetic is checked u64. | D12, D15 |
| I5 | The vault pays a claim only if its commitment leaf is under the anchored root, with the exact asset, quantity and recipient. | D8 |
| I6 | Each `ClaimId` is paid at most once: it is a nullifier insert in the same transaction. | D8 |
| I7 | A payout cannot move another asset or another `VaultId`'s UTxOs. The continuing output keeps every non-paid asset. Lovelace decreases at most by the min-ADA allowance. | D8, D11 |
| I8 | Reserved funds are never released while the claim may be redeemable. v1 has no release. | D7 |
| I9 | Rotation of membership or the anchor never invalidates a claim that is reserved but unpaid, and never adds a spend path that bypasses I5-I7. | D8 |
| I10 | A user intent takes effect only if its signature verifies under D13 and it binds the network, chain, genesis, application, action, account, nonce, expiry and every amount, asset and recipient field. Replay across any of these fails. | D13 |
| I11 | A session never exceeds its per-payment or cumulative limits, its asset or recipient allowlist, or its expiry, and never withdraws or delegates unless granted explicitly. | D13 |
| I12 | Effect results, operator commands, the executor and the domain API never change deposit or claim status. | D7, D9, D10 |
| I13 | Identical genesis and observed L1 produce identical state roots on every member. Nothing consensus-relevant depends on local configuration. | D1, D5, D17 |
| I14 | A chain that does not select gateway contributions behaves exactly as today. EUTxO bridge bytes and script hashes are unchanged. | D1, D16 |
| I15 | Operational ADA and customer ADA are separate accounts. Fees never come from a vault. | D11 |

## 7. Compatibility

- **New formats.** All are versioned with explicit tags: `AssetId`, `AssetValue`, `GatewayDomain`,
  `VaultId`, `DepositId`, `ClaimId`, `AssetDepositClaimV1`, `SettlementIntentV1`,
  `PayoutObservationV1`, `IntentEnvelopeV1`, session records and claim leaves. CDDL lives beside
  the contracts, following the existing `src/main/cddl` convention. A change needs a new version
  and a governed activation height or a new profile id.
- **Existing formats.** Nothing changes. The EUTxO bridge, `balances` and `cardano.payment` keep
  their bytes and behaviour (I14).
- **API level.** No new host or framework API. If M3 finds that an observer needs resolved inputs
  (who paid), that is a framework API addition. It would need an API level bump and its own
  review. The v1 deposit rules avoid needing it: beneficiaries come from the datum, and required
  signers are in the transaction body.
- **Distribution.** The bundles are added to the JVM ZIP like other optional bundles and are off
  until selected. The ZIP and Central size budget is checked when the bundles are added.

## 8. Milestones

| ID | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| M0 | This ADR | — | Accepted. Q1, Q2, Q3, Q5 and Q6 decided. |
| M1 | Contracts: types, ids, codecs, CDDL, accounting library, invariant checker | M0 | Vectors V1-V2 published. Property tests for I3 and I4. Module names fixed. |
| M2 | `cardano-wallet-auth` | M0 | V3 vectors pass. Recorded signatures from at least two real wallets verify, and the negative corpus is rejected. Differential agreement with two oracles. |
| M3 | Ingress: `asset-vault-deposit-v1`, `DepositAdmission`, payment ledger without withdrawals | M1, M2 | Devnet: ADA and two native tokens credit exactly once across restart and rescan. Adversarial fixtures A1-A8 (§10) never credit or halt. I1, I2, I13. |
| M4 | Settlement: vault and nullifier validators, payout observer, relayer, operational ADA | M3; Q2 decided | Validator negative suite V6. Batch budget measured. Devnet withdrawals, partial and full, in each asset while transfers continue. A third-party relayer pays a claim. I5-I9, I12, I15. |
| M5 | Second consumer, conformance kit, EUTxO helper adoption | M4 | A minimal non-account consumer passes the kit. EUTxO golden bytes are unchanged (I14). A substituted executor passes. |
| M6 | Hardening and qualification | M5 | Multi-node failure drills. Deep-rollback, outage and contention drills. Wallet interoperability report. Throughput and p50/p95/p99 latency and L1 costs reported. Trust and operations docs. |
| M7 | Production gate | M6 | Independent review of validators, observers and the auth library (§12). Not part of this experiment's acceptance. |

## 9. Consequences

**Positive**
- One audited path for deposits, payouts and confirmation instead of one per application.
- Withdrawals need no per-payout signing round, and anyone can relay.
- The payment ledger and a DEX-like application share ingress and custody code but not custody,
  state or liquidity.

**Costs**
- Withdrawal latency is at least one anchor interval.
- Each claim needs an MPF proof on chain, which bounds batch size.
- Five or six new modules.
- The custody assumption is unchanged: a colluding threshold can steal. This is stated, not
  solved.
- An application gateway is needed to relay user intents, and it can censor.

## 10. Verification and test vectors

Evidence must be independent of the code under test.

- **V1 — Asset and value encoding.** Vectors are built from real Cardano transaction outputs
  (preprod, with native tokens), decoded by an independent tool (cardano-cli or
  `cardano-multiplatform-lib`). The test checks that `AssetValue` extraction equals the ledger's
  view, including empty asset names and 32-byte names.
- **V2 — Identifiers.** Each tagged hash is computed by a script outside the JVM, with Python
  `hashlib.blake2b` and `cbor2` in canonical mode. Non-canonical encodings must be rejected.
- **V3 — CIP-8.** Positive vectors come from real wallets through CIP-30 `signData` (Lace, Eternl,
  Yoroi), covering base, enterprise and reward addresses. A second oracle verifies them:
  `@emurgo/cardano-message-signing` or `pycose`. The negative corpus covers:
  - a payload altered by one byte;
  - a swapped `address` header;
  - a key with a wrong `crv` or `alg`;
  - `hashed = true`;
  - non-empty `external_aad`;
  - a script address;
  - a stake key used for a payment account, and the reverse;
  - wrong network;
  - wrong chain, application or action;
  - an expired intent;
  - a reused nonce.
- **V4 — Accounting.** Model-based property tests compare a reference model with the library over
  random sequences of credit, transfer, reserve and confirm, including overflow at `2^64 − 1`.
- **V5 — Observer adversarial fixtures A1-A8:**
  - A1: unsolicited ADA to a vault;
  - A2: mixed assets in one output;
  - A3: unregistered token;
  - A4: malformed or oversized datum;
  - A5: wrong network or chain in the datum;
  - A6: phase-2-invalid transaction;
  - A7: a fake payout marker without a custody spend;
  - A8: vault change or consolidation.
- **V6 — Validators.** Conformance tests in the style of the bridge-onchain suites. Each rule in D8
  gets at least one negative test:
  - forged root and stale anchor reference;
  - missing proof, and a proof for a different `ClaimId`;
  - double pay in one transaction and across transactions;
  - wrong recipient, quantity or asset;
  - another vault's UTxO;
  - lovelace drain above the allowance;
  - fee taken from the vault;
  - a missing nullifier insert.
  Budgets are measured, not estimated.
- **V7 — End to end.** On devnet, the payout observer independently re-derives every paid claim
  from L1 and compares it with the ledger. Restart and replay must yield identical state roots
  (I13).

## 11. Risks

| Risk | Mitigation |
|---|---|
| A colluding member threshold steals | Stated trust label (D18). The validity-proof profile is future work. |
| Users sign an intent they cannot read in the wallet (blind signing) | The client shows the decoded intent. Intents are short-lived with nonces. Sessions have limits. Docs say so. |
| The MPF proof budget limits batches | Measured in M4. Batches are bounded per profile. Nullifiers are sharded. |
| Anchor stalls block withdrawals | Liveness only. Anchor monitoring and alerting. The co-signed fast path is a possible later profile. |
| Min-ADA allowance mis-set | Governed and bounded. Reconciliation alerts. |
| Extraction changes EUTxO behaviour | Byte-identical golden gates (D16). |
| Scope creep toward a DEX or general accounts | Non-goals. Two-consumer rule. |

## 12. Production and audit gates

No real-value deployment until:
1. independent review of the D8 validators and the nullifier design;
2. independent review of the observers and `DepositAdmission`;
3. independent review of the CIP-8 verifier;
4. custody, rotation, deep-rollback and incident drills;
5. reconciliation running in production shape;
6. this ADR's trust labels are visible in every user surface.

Passing tests and benchmarks is not evidence of security. These gates are in addition to FX-002
in `adr/app-layer/open_item.md` l.74.

## 13. Open questions

**Points needing a maintainer decision:**

| ID | Question | Options | Lean |
|---|---|---|---|
| Q1 | Token deposit outputs must carry min-ADA. Who owns that lovelace? | (a) It becomes operational ADA (#109: "incidental ADA must not create credits"). (b) It is credited to the depositor's ADA balance when ADA is registered. (c) It is refunded on withdrawal. | (a). It funds payout min-ADA (D11). Clients show it as a deposit cost. |
| Q2 | How does a native-token payout get its min-ADA? | (a) Bounded allowance from the vault's operational ADA (D11). (b) The relayer funds it. (c) The user pays from their ADA balance. | (a). (b) breaks the "relayer needs nothing" story; (c) couples assets. |
| Q3 | Direct deposits or staging and acceptance? | (a) Direct to the vault. Uncreditable deposits become unattributed custody, recoverable only by a governed refund claim. (b) Staging with a depositor refund after a deadline (`DepositStagingValidator` exists but is unwired). | (a) for v1, using the client builder. (b) as a profile in M6. |
| Q4 | Should the proof path have a delay as in ADR-UTXO-009 Exit? | (a) Always armed. (b) Delay-armed. | (a). It is the normal path, and the nullifier prevents replay. The reviewer should confirm that the UTXO-009 delay rationale does not apply when no co-signed path exists. |
| Q5 | Should `hashed: true` CIP-8 signatures be accepted (hardware wallets, CIP-8 §Payload encoding)? | (a) Reject in v1, per CIP-30. (b) Accept with Blake2b-224 of the canonical envelope. | (a). Revisit after the M2 interoperability tests. |
| Q6 | How do public users submit intents? | (a) An application gateway with a `SUBMIT`-scoped credential (D13). (b) A new public write class for the domain API. | (a). (b) is a framework change for later. |
| Q7 | Should withdrawals expire or be cancellable? | (a) No, in v1. (b) Expiry plus a cancellation that inserts the nullifier on L1 first. | (a). |

**Open, not blocking M1:**

| ID | Question | Lean |
|---|---|---|
| Q8 | How many vault UTxOs per asset, and who consolidates them? | Many. Consolidation is a script redeemer that preserves value under the custody gate. Designed in M4. |
| Q9 | Account owner key rotation | Later. It needs a dual-signed `ROTATE_OWNER`. |
| Q10 | Relayer incentives (bounty) | None in v1. The operator relays. |
| Q11 | Module names and placement (`capabilities/` or a new top-level `assets/`) | Decided in M1 with the artifact inventory. |
| Q12 | ADR-041 says Proposed, but the journal exists. | Not this ADR's job. Flagged for an ADR-041 status update. |
| Q13 | Is the anchored state key for a payment-ledger claim provable with `MpfAnchorLib` when the machine runs inside a composite? | Verify in M4 against the commitment profile's key layout. |

## 14. Alternatives considered (overall)

- **A separate payment-specific bridge for #109:** rejected by both issues.
- **Generalizing the EUTxO bridge in place to multi-asset and accounts:** rejected. It would
  change a shipped profile's semantics and script hashes, which ADR-042 and D16 forbid.
- **Reusing stdlib `balances` with an asset dimension:** rejected. It has `MINT` and
  member-only spending, so retrofitting it would mean removing issuance from a stock machine.
  The new ledger reuses its patterns, not its commands.
- **Writing every application on ADR-058 guest runtimes:** possible later. Guests cannot yet read
  L1 observations, verify Ed25519 or join cascades (ADR-058 Q7), so v1 consumers are Java
  machines. The contracts stay runtime-neutral so that a guest can use them once host functions
  exist.

## 15. Related findings (out of scope)

1. Nine EUTxO bridge classes have no production caller (§1.2).
2. `EutxoBatchWithdrawalConfirmation.java:13-21` Javadoc says observations are keyed by
   `observerId/txHash/slot`. `L1Observation.key()` now includes `eventOrdinal`.
3. `docs/site/reference-configuration.md:80-81` lists only `eutxo-withdrawal-confirmation-v1` for
   the bridge confirmation observer. The v3 showcase uses `eutxo-batch-withdrawal-confirmation-v1`.
4. No runtime service advances the EUTxO bridge root thread. Combined with ADR-UTXO-009 §13.1/§13.2
   (Exit armed from genesis; fallback floor not enforced on chain), the bridge's Exit path is
   armed from genesis.
5. `adr/README.md` has no rows for ADR-057 or `047-cohesive-application-deployment.md`.
6. ADR app-layer/utxo/006 and 007 are cited by 008 and 009 but are not in the repository.

## Revision history

- **r1** (2026-11-24): initial proposal.
