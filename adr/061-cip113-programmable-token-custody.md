# ADR-061 — Custody of CIP-113 programmable tokens in the asset gateway

- **Status:** Proposed (2026-11-28, r1). Design only; no implementation code. Three decisions were
  taken by the maintainer before drafting (§1.3); the open questions are in §13.
- **Extends:** ADR-059 r23, the Cardano asset gateway and its custody profile
  `anchored-claim-vault-v1` (PR #111).
- **Issues:** [#110](https://github.com/bloxbean/yano-x/issues/110) (generic Cardano asset ingress
  and settlement). CIP-113 custody has no issue of its own yet.
- **Evidence base:**
  - Yano X: PR #111 head `9ff0ae87`. Every Yano X path and line below was read at that commit.
  - CIP-0113 at cardano-foundation/CIPs `e759a4a5` ("Version 3", Status: Proposed).
  - The contracts at cardano-foundation/cip113-programmable-tokens `6b75ba32`: the exact version
    that cardano-client-lib 0.8.0-pre8 vendors.
  - The cardano-client-lib 0.8.0-pre8 sources of `cardano-client-programmable-token`.
- **Related (Yano X):** ADR-059 (all of it), app-layer/011 (plugin architecture), ADR-057
  (framework extraction).
- **Risk:** high. The design adds an on-chain custody script, and the funds it holds sit under
  rules that a third party controls: the token's issuer and the CIP-113 protocol upgrade authority
  can freeze or take them.

**Notation.** ADR-059's decisions, rules and invariants are written with its number: 059-D8,
059-V6, 059-I25. Plain D1, V14 and I34 are this ADR's. Rule and invariant numbers continue
ADR-059's series (V1–V13, I1–I33), so each name means one thing across both ADRs.

## 1. Context

ADR-059 holds each registered asset in its own vault, at the enterprise address of the vault
script (059-D4 step 6). That works for ADA and for ordinary native tokens. It cannot work for
CIP-113 programmable tokens: a registered programmable token can never be paid to the vault's
address at all. This ADR adds a second kind of vault custody for those tokens and states what it
changes in ADR-059's guarantees.

### 1.1 What CIP-113 v0.0.1 does, as verified

Paths without a prefix are in the contracts at `6b75ba32`; `spec l.N` is the CIP README at
`e759a4a5`; CCL paths are under `com/bloxbean/cardano/client/programmabletoken/cip113/`.

| Fact | Where |
|---|---|
| **Tokens stay at one script.** Every registered programmable token lives at an address whose payment credential is `programmable_logic_base` (PLB). It can leave PLB only by being burned. The transfer delegate requires the PLB outputs to contain the PLB inputs' tokens. The third-party delegate conserves the acted-on policy across PLB outputs. Unfracking conserves it exactly. Issuance forbids any minted token at a non-PLB output. | `validators/programmable_logic/transfer.ak:95, 209`; `lib/prog_assets.ak:208-226`; `validators/programmable_logic/third_party.ak:88, 147, 198`; `validators/programmable_logic/unfracking.ak:269`; `validators/issuance_logic.ak:179-211` |
| **Unregistered native assets may leave.** A policy with a `TokenDoesNotExist` registry proof is skipped. | `validators/programmable_logic/transfer.ak:263-272`; spec l.117 |
| **A smart wallet is `(PLB, owner)`.** The owner is the inline stake credential. A contract's smart wallet uses the contract's own credential. | spec l.123-134 |
| **A script owner consents only by its withdrawal.** For a `Script` stake credential, consent is a withdraw-zero of that credential. Required signers and a script input do not count. The transfer and unfracking delegates check every PLB input this way. | `validators/programmable_logic/owner.ak:27-38`; `transfer.ak:96`; `unfracking.ak:168` |
| **The chain of scripts.** PLB runs once per input. It reads the dispatcher (`programmable_logic_global`, PLG) credential from field 0 of the protocol-params datum, and it requires that credential's withdrawal at the index `wdrl_idx`. PLG runs once per transaction and requires the delegate for its one action (`TransferAct`, `ThirdPartyAct`, `UnfrackingAct`). | `validators/programmable_logic_base.ak:42-67`; `validators/programmable_logic_global.ak:143-149` |
| **Shape of PLB outputs.** Every PLB output that a transfer, third-party action, unfracking or issuance creates has an inline stake credential, no datum hash and no reference script. Its inline datum is at most `max_inline_datum_bytes`, measured on `serialise_data`. The public deployments set the bound to 1,024 bytes. | `lib/prog_assets.ak:292-303`; CCL `Cip113Deployments.java:64, 109, 154` |
| **Third-party actions need no holder consent.** The pairing rule keeps each pair's address, datum and reference script. Every other policy stays identical. Lovelace may only rise. The acted-on policy may change, and a re-spend that changes nothing is accepted. | `third_party.ak:28, 204-263` |
| **Retroactive rules.** The minting authority can rewrite a registry node's transfer, third-party and unfracking logic in place, for tokens already issued. | `validators/registry.ak:211-231` |
| **No de-registration.** The registry mints only `RegistryInit` and `RegistryInsert`. | `lib/types.ak` (`RegistryRedeemer`) |
| **Upgrade authority.** The protocol upgrade authority can re-point the dispatcher, the issuance logic and the delegates for every token of a deployment. | spec l.561-602; `validators/issuance_logic.ak:14-16` |
| **No unfracking on public deployments.** Each public dispatcher was compiled with 28 zero bytes for the unfracking hash. | CCL `Cip113Deployments.java:15-17`, `unfrackingEnabled(false)` |
| **Co-located policies.** A transfer must run the transfer logic of every registered policy in its PLB inputs. One frozen policy therefore blocks the whole UTxO, including its lovelace. | `transfer.ak:240-261`; spec l.457-459 |
| **CCL cannot build script-owned spends.** CCL 0.8.0-pre8 cannot build a spend from a script-owned smart wallet. `SmartWalletAddress.ownerSigner` throws: "authorising it needs a withdrawal, which this adapter does not build". | CCL `SmartWalletAddress.java:62-74` |

**Compiled sizes** (`plutus.json` at `6b75ba32`):

| Script | Bytes |
|---|---:|
| PLB | 564 |
| PLG | 334 |
| transfer | 1,993 |
| third_party | 1,773 |
| unfracking | 1,702 |
| issuance_logic | 2,128 |
| registry | 2,573 |
| protocol_params | 2,023 |

### 1.2 What that means for the ADR-059 vault

1. **The vault must be a smart wallet.** It is `(PLB, Script(h))` for a script `h` that the
   gateway controls.
2. **The vault's spend validator never runs.** PLB is the spending script of every vault UTxO.
   The vault's rules must therefore run in a **withdraw-zero** handler of `h`. That handler runs
   once per transaction, and its one withdrawal authorizes **every** PLB input whose stake
   credential is `h`. The handler must enumerate those inputs itself.
3. **The datum bound caps the continuation.** Every PLB output's inline datum is bounded, and the
   bound applies to the vault's continuation. A `VaultContinuationV1` list of `n` 32-byte ids
   serializes to `34n + 6` bytes, so at most 29 ids fit in 1,024 bytes. 059-V7's on-chain cap is
   64.
4. **Anyone can create outputs at the vault's smart wallet.**
   - Lovelace or unregistered assets can arrive in any shape.
   - The asset itself can arrive through an ordinary transfer, with any inline datum within the
     bound.
5. **Third-party re-spends keep each output's datum.** A third-party action can re-spend vault
   UTxOs without the gateway. Each re-created output keeps its datum, but its balance or its
   outref changes. A re-created deposit would look like a new deposit.
6. **The issuer can act on custody.** The issuer can freeze, seize and burn vault custody, and can
   change the rules retroactively. These powers are outside the members' threshold.
7. **Yano X must build the transactions.** CCL pre8 cannot build them.
8. **No Hydra.** An L1 commit of these tokens into a Hydra head would move them off PLB, so it
   fails.

### 1.3 Decisions already taken by the maintainer

1. **One vault per asset**, as in ADR-059. A CIP-113 asset's vault is a smart wallet,
   `(PLB, vault staking script)`. Accounts, transfers, sessions and claims in the ledger are
   unchanged per `AssetId` (D1).
2. **Two interchangeable custody backends**, julc and Aiken. Each deployment selects one, with
   julc as the default. Following 059-D8c r23:
   - the choice is part of the pinned identity;
   - a mismatch fails at startup;
   - a differential test proves that both backends accept and reject the same transactions (D3).
3. **A per-asset `ISSUER_CONTROLLED` trust label.** It covers issuer freeze, seizure and burn,
   retroactive rule changes, and the CIP-113 protocol upgrade authority. The consequences for
   059-§13a are stated, not avoided:
   - a frozen vault blocks `Exit` for that asset;
   - a seizure creates a reserve shortfall. Reconciliation must flag it and pause the asset.
     Liabilities are never silently reduced (D14).

## 2. Goals and non-goals

**Goals**

1. **The same anchored-claim vault for CIP-113 tokens.** Registered CIP-113 tokens are held in
   059's anchored-claim vault, with the same ledger contract:
   - deposits, claims, nullifier shards, `Settle`, `Exit` and the escape hatch;
   - the same records, keyed by `AssetId`.
2. **ADR-059's guarantees are kept** wherever they do not depend on the issuer. Those that do
   depend on the issuer are stated plainly, on every user surface.
3. **Issuer and third-party actions never inflate or silently reduce ledger liabilities.**
4. **Both custody backends, proven equivalent.**
5. **No new contribution kind, host SPI or framework addition** beyond ADR-059 §7.

**Non-goals**

- Protecting holders against the issuer or the CIP-113 upgrade authority. The trust label covers
  this instead.
- Unfracking. It is disabled on the public deployments.
- Smart wallets owned by a script, as recipients (Q1).
- Substandards whose third-party logic is permissionless (D12).
- Issuing or registering tokens, or managing the registry. Issuers use CCL or their own tools.
- CIP-113 tokens in Hydra heads.
- Vaults that hold several assets.
- Deployments other than the pinned ones (D12).
- Changes to ADR-059's native vault, its shard, the EUTxO bridge or any shipped script hash
  (059-I14).
- Production funds before §12 closes.

## 3. Threat model delta

059-§3 stands. This section lists what changes.

**New parties**

| Party | Trust | What it can do to the vault of its asset |
|---|---|---|
| **Issuer** (the minting authority and its substandard's admin) | Trusted for its own asset only, under the `ISSUER_CONTROLLED` label | Freeze the vault, so no transfer out succeeds. Seize or burn custody. Re-spend vault UTxOs without changing their contents, which changes their outrefs. Rewrite the token's transfer and third-party logic. Refuse a recipient. It **cannot** move another asset's custody (I38), change ledger liabilities (I36), credit itself through a re-spend (I35), or confirm a payout (I42). It **can** seize custody and re-deposit it in the same transaction. That credits a new deposit with no new backing, which is a seizure by another route (D6). |
| **Third-party actors** | Whoever the substandard's third-party logic admits. v1 requires that to be the issuer's admin set (D12). | As the issuer, for the actions that logic allows. |
| **CIP-113 protocol upgrade authority** | Trusted, under the label | Re-point the dispatcher and the delegates of the whole deployment. That can move any programmable token of the deployment, so it can drain every CIP-113 vault. Authority handovers are two-phase and visible on chain (spec l.584-596). |

**Changed capabilities of existing parties**

- **Anyone on L1** can create outputs at a CIP-113 vault's smart wallet, as at a native vault:
  - lovelace and unregistered assets in any shape;
  - the vault's asset only through a CIP-113 transfer, with an inline datum within the bound or no
    datum.

  Nobody but the custody script or an admitted third party can spend vault UTxOs.
- **The owner node and relayers** also read the live CIP-113 wiring: the protocol params, the
  registry node and the substandard's state. Like every builder input, that is liveness only.

**New trust boundary**

5. **Issuer and upgrade authority → custody of that asset.** This boundary lies outside the
   members' threshold.

**Trust labels.** A CIP-113 asset carries `ISSUER_CONTROLLED` in addition to the domain label
`FEDERATED_COSIGNED_WITH_ESCAPE_HATCH` (D14).

## 4. Pinned references

| Ref | Version | Used for |
|---|---|---|
| CIP-0113 Programmable token-like assets | cardano-foundation/CIPs `e759a4a56c03c87175f3561b136283398fefccfe`, "Version 3" (l.822), Status: Proposed | Smart-wallet derivation (l.123-134). Transfer and the delegate redeemers (l.295-409). Third-party action (l.411-450). Unfracking (l.452-480). Registry proofs (l.482-504). Reference inputs (l.536-549). Upgradability (l.561-602). Version table (l.604-610). Vaults (l.632-637). Transaction construction (l.645-659). Substandards (l.798-819). |
| cip113-programmable-tokens | cardano-foundation `6b75ba32`, package version 0.0.1, Aiken v1.1.23, Plutus V3; `plutus.json` sha256 `b6c8cb096a15e02f1b9c719fb8c617b624c1aa7719f2258d45faa3e8f144e7b9`, identical to the copy in CCL 0.8.0-pre8's integration tests | The scripts this ADR composes with, and the blueprint of the devnet deployment (D12) |
| cardano-client-lib | 0.8.0-pre8, module `cardano-client-programmable-token` | Deployment records (`Cip113Deployments`), the deployment model (`Cip113Deployment`), smart-wallet derivation (`SmartWalletAddress`), ledger orderings (`LedgerOrdering`, `PolicyOrdering`), and issuer-side registration and issuance in the drill |
| ADR-059 | r23 at `9ff0ae87` | Everything this ADR extends |

**Where the spec and the pinned code differ, the code is normative for this ADR**, because it is
what runs on chain:

1. **No-op re-spends.** The spec requires a third-party action to change the acted-on policy's
   tokens (l.441). The code accepts a re-spend that changes nothing (`third_party.ak:251-259`).
2. **Lovelace on a pair.** The spec requires non-policy assets to stay exactly equal across a pair
   (l.440). The code exempts lovelace, which may only rise (`third_party.ak:218-231`).
3. **Mint-only policies.** The spec asks for a transfer proof for every policy in `tx.mint` (l.398,
   l.486). The code drops policies that are only minted and leaves their custody to issuance
   (`validators/programmable_logic/transfer.ak:30-33, 80-89`).
4. **The Preview deployment.** The spec's version table names Preview bootstrap `61fae36e…`
   (l.610). CCL's Preview record is `8e9668a6…`, re-derived from the pinned blueprint. This ADR
   uses CCL's records (D12).

D5 and D6 rely on items 1 and 2. Nothing relies on the spec's stricter wording.

## 5. Decisions

### D1. One smart-wallet vault per CIP-113 asset (decided by the maintainer)

**Custody.** A registered CIP-113 asset `A` gets exactly one vault, as every asset does (059-I29).
Its custody is every UTxO at the **vault smart wallet** `base(PLB, Script(h))`. Here `h` is that
vault's custody script (D2) and PLB is the pinned deployment's base script hash. The domain's
network id applies.

**What stays the same.** The ledger side is unchanged per `AssetId`:
- the account and accounting layout (059-D12);
- transfers, sessions and intents (059-D13);
- claims and claim leaves (059-D7);
- exit leaves (059-D8g §5);
- the forced-withdrawal inbox and the exit-mode thread, which are domain-level and shared with
  native vaults.

**`VaultId`** keeps its fields (059-D4). The custody kind is not one of them. The custody script
is parameterized by the pinned PLB hash, so its hash and address differ from those of any native
vault.

**Registry entry.** The asset registry entry (059-D14) gains `custody = NATIVE | CIP113(pins)`.
The pins are listed in D12. They are genesis-fixed, like the vault set (059-D5).

**Why one vault per asset matters more here.**
- An issuer's freeze of `A` blocks only `A`'s vault.
- Accepted custody holds only `A` and lovelace (059-V10). Only `A`'s transfer logic ever runs on a
  vault spend (I38).

*Alternatives considered:*
- **One shared custody smart wallet for every CIP-113 asset.** Rejected. One frozen policy blocks
  every UTxO it shares, and a shared wallet would merge `A` and `B` custody under one freeze.

### D2. The custody script: a withdraw-zero validator with a permanently registered credential

**`ProgrammableVaultValidator`.** It is one multi-purpose Plutus V3 script, `h`, with these
handlers:

| Handler | Behaviour |
|---|---|
| `withdraw(redeemer, Script(h))` | Every vault rule (D5). It learns its own hash from its purpose. |
| `publish(cert)` | Accepts only `RegisterCredential`. Rejects deregistration, stake delegation, vote delegation and the combined register-and-delegate forms. |
| `spend` | Always fails. Nothing at `h`'s own payment address is custody, and V15 forbids vault transactions from creating outputs there. Whatever someone else sends there stays there. |
| every other purpose | Fails. |

**Why withdraw-zero.** It is the only consent that CIP-113 accepts from a script owner
(`owner.ak:27-38`; spec l.132). It is the same pattern the CIP-113 delegates themselves use.

**What the registration rule gives.** The credential is registered once, at bootstrap. It can
never be deregistered or delegated, so it earns no rewards, and its withdrawal amount is always 0.

**Parameters.** These extend 059-D4 step 5. The script takes the native vault's parameters:
- the `VaultId` fields;
- the address network id;
- the anchor identity;
- `shardThreadPolicyId`;
- the exit-mode policy id and script hash;
- the claim, deposit and exit key prefixes;
- the status key;
- the profile.

It also takes the pinned deployment's **PLB script hash** and **`maxInlineDatumBytes`**. It does
not take the params policy or the registry: the custody script reads no CIP-113 state, because the
CIP-113 scripts already do.

**Derivation order.** 059-D4 is kept, with steps 6 and 7 changed:
6. the custody script hash `h`, and the vault smart wallet `base(PLB, Script(h))`;
7. the shard validator, in the binding variant of D5, parameterized by `(shardThreadPolicyId, h)`.

No cycle appears. The custody script names the shards only by their thread policy, as the native
vault does.

**Bootstrap.** The operator does three things:
1. registers `Script(h)` as a stake credential. The deposit is the `stakeAddressDeposit` protocol
   parameter, 2 ADA on mainnet today. The operator pays it, and it is never refunded, because
   deregistration is refused;
2. bootstraps the 16 shard threads (059-D8c);
3. has the issuer admit the vault smart wallet as a holder where the substandard requires it (D12).

**A missing registration is a liveness fault, not a safety one.**
- The doctor checks the registration.
- A vault transaction against an unregistered credential fails on L1.
- Anyone can register the credential, because `publish` allows it.

*Alternatives considered:*
- **A native multisig of the member keys as the owner credential.** Rejected. Its withdrawal needs
  the members' signatures, which removes the permissionless `Exit` and the escape hatch (059-D8,
  059-D8g).
- **Adding a withdraw handler to `AssetVaultValidator`.** Rejected. The applied token vault is
  15,452 bytes, 48 under its 15,500-byte guard (`capabilities/asset-gateway-onchain/BUDGETS.md:158-173`).
  Its spend handler would also be dead code for CIP-113 custody.

### D3. Two interchangeable custody backends, julc by default (decided by the maintainer)

**The backends**

| Backend | Script | Verifiers |
|---|---|---|
| julc (the default) | `ProgrammableVaultValidator` | The native vault's Java sources |
| Aiken | `aiken/validators/programmable_vault.ak`, in the same Aiken project as `asset_shard.ak` | A Yano X Aiken port of the state-proof verifier (`MpfOnChainVerifier` inclusion and absence, with the same proof-shape rules) and of `MpfAnchorLib` |

Both take the same parameters, redeemer, datums and rules. Like the shard backends, both read
Plutus Data positionally, so they accept the same encodings.

**Setting.** `programmable-backend: julc | aiken`, with `julc` as the default. It is the same key
in the settings of:
- `asset-vault-deposit-v1`;
- `asset-vault-payout-v1`;
- `asset-shard-bootstrap-v1`;
- the `asset-settlement` executor.

It mirrors `shard-backend` (`capabilities/asset-gateway-cardano/README.md:53`).

**Identity**
- The backend determines `h`, and with it the vault smart wallet, which is pinned in the registry
  entry and in every observer's consensus identity.
- A node whose pinned `vaults.N.script-hash` is not the selected backend's custody script for the
  vault's parameters fails startup.
- `ProgrammableBackend.derive(params)` lives in the support library.

**Relation to `shard-backend`.** The two settings are independent: the custody and the shard
interact only through `h` and the shard thread policy. Using the same backend for both is
recommended.

**Toolchain.** The Aiken backend pins its own compiler, as `asset_shard.ak` does. It need not
match the CIP-113 contracts' Aiken v1.1.23, because the scripts meet only on chain.

**Equivalence.** `ProgrammableVaultBackendDifferentialTest` runs both backends on the same script
contexts and fails on any differing verdict. It covers:
- every action;
- the proof shapes of `AssetShardBackendDifferentialTest`;
- systematic and random mutations of proofs, redeemers, inputs and outputs;
- lenient encodings.

I40 records the result.

**The case for each default**
- **julc (chosen).**
  - It is consistent with the native vault.
  - It shares one Java source for the claim, deposit, exit and anchor verifiers.
  - Its costs are the measured baseline.
- **Aiken.**
  - CIP-113's own contracts are Aiken, so a reviewer of a CIP-113 integration reads one language.
  - The compiled-UPLC composition tests (§10) already mix Aiken scripts.
  - It is the language of the upstream MPF library that the Aiken shard uses.

### D4. Vault state stays on the PLB continuation (question a)

**Decision.** The vault keeps 059's single output per action. `VaultContinuationV1(claimIds)` is
the inline datum of the vault's one continuation at the smart wallet. No separate state output
exists.

**The id cap (V16).** The id list must satisfy `len(ids) ≤ floor((maxInlineDatumBytes − 6) / 34)`,
which is **29** at 1,024 bytes.
- The bound is measured on `serialise_data`: tag 2 bytes, list delimiters 4 bytes, and 34 bytes per
  id.
- The custody script enforces the cap, so it never accepts a continuation that the transfer
  delegate would reject.
- The profile's batch caps must stay within it, or startup fails. `Settle` 16, `Exit` 8 and
  `BalanceExit` 1 all fit.

**What the payout observer reads (059-D10).** It still authenticates a payout by the shard thread
output at the pinned shard address. On such a transaction it reads:
- the claim ids from the one output at the **full vault smart-wallet address** carrying
  `VaultContinuationV1`. It matches the payment **and** the stake credential, because payouts to
  recipients' smart wallets share the PLB payment credential;
- `SweepPaidV1` on output 0 only when there is no vault continuation, as in 059;
- **balance exits** from the custody's own reward redeemer. Each reward redeemer is indexed by its
  withdrawal's position in ledger order, so the observer locates the custody's redeemer exactly,
  through the position of `Script(h)`. The native vault's prefix heuristic is not needed. The
  escape-nullifier binding is still checked as a second test.

**Look-alikes and re-spends.**
- An output at the vault smart wallet on a transaction that is not shard-authenticated is never
  read as a payout (059-I18). That includes a donated look-alike, and an output that a third-party
  action re-created with the old datum.
- A transaction carries exactly one dispatcher action, because PLG runs once with one redeemer. So
  a genuine payout and a third-party re-spend never share a transaction.

*Alternatives considered:*
- **(a2) A separate state output at `h`'s own payment address.** It would let the native shard
  bind unchanged, through a spent vault input, and it would lift the 29-id cap. It costs an extra
  input, output and min-ADA in every action. It also either serializes every action of the vault
  on one marker, or needs one marker per shard plus an authenticated marker thread. The cap is not
  binding (`Settle` 16, `Exit` 8). Rejected.
- **(a3) Ids only in the custody redeemer**, which this vault kind can attribute. Rejected for v1.
  It needs a second D10 code path, and the continuation datum is still needed as the accepted
  custody marker (059-V10).

### D5. ADR-059's rules on a withdraw-zero validator (question b)

**Vault inputs (V14).** The handler takes as vault inputs **every** transaction input whose
payment credential is `Script(PLB)` and whose stake credential is `Inline(Script(h))`.
- It applies its rules to all of them, and it requires at least one.
- It never relies on a redeemer to say which inputs are the vault's, because its one withdrawal
  authorizes them all.

**No lead and no `Follow`.**
- The handler runs once per transaction, with one redeemer, because a withdrawal map holds one
  entry per credential. It is the lead.
- PLB inputs carry PLB's own `BaseSpendRedeemer`.
- 059-V13 therefore holds by construction. Large proofs appear once per transaction without a
  marker.
- Constructor 9 (`Follow`) is rejected as an action.

**Rule mapping**

| 059 rule | In the CIP-113 custody's withdraw handler |
|---|---|
| V1 anchor | Unchanged. |
| V2 claim key and value | Unchanged. A recipient is a smart-wallet base address (D7). |
| V3 exit delay and brake | Unchanged. |
| V4 nullifier bijection | Unchanged on the vault side: exactly one input carries this vault's shard thread token, and the inserted ids equal the paid ids, in order. The shard binds to the vault through the **binding variant** below. |
| V5 payouts | Output `i < k` pays `claim[i].recipient`, a smart wallet, with exactly the claim's `A` plus at least `vaultLovelace`. It has no datum hash and no reference script, which CIP-113 requires of every PLB output anyway. |
| V6 value equation | Over the V14 vault inputs and the one continuation. Unchanged otherwise. |
| V7 output shape | **Extended by V15 and V16.** Output `k` is the only output at the vault smart wallet. It carries the inline `VaultContinuationV1` and no reference script. |
| V8 script inputs | The only script inputs are the V14 vault inputs and, for actions that insert, the shard input. **No other PLB input is allowed, whatever its owner.** **Extended by V17:** certificates and mint must be empty. |
| V9 threshold | Unchanged. |
| V10 accepted custody | Unchanged: a clean `VaultContinuationV1` holding only `A` and lovelace, or a lovelace-only `FloorTopUpV1`. |
| V11 exit-mode thread | Unchanged. |
| V12 | Unchanged. It is a member rule. |
| V13 one action | Holds by construction (above). |

**New rules**

- **V15 — the vault credential appears only on the continuation.**
  - No output has `h` as its payment credential.
  - No output other than the continuation has `h` as its stake credential.
- **V16 — continuation cap.** The id list is at most the cap of D4. The datum is inline.
- **V17 — no certificates and no mint; withdrawals are free on chain.**
  - `certificates` and `mint` must be empty.
  - Withdrawals are **not** restricted by the script. Each further withdrawal is another script
    that must pass, and none can relax the custody's rules. A vault input is authorized only by
    `h`'s own withdrawal, and the third-party path bypasses `h` whatever else the transaction
    holds.
  - The members' body check fixes the exact withdrawal set (D13).

**Actions**

The redeemer encoding is 059's `vault_redeemer`, constructors 0–8. Each action runs under
`TransferAct`. The custody script does not inspect the dispatcher's action: its rules bind every
vault input and output under any action. Members require `TransferAct` (D13).

| Action | CIP-113 deltas |
|---|---|
| `Settle` | Recipients are smart wallets. Otherwise as 059. |
| `Exit` | As 059. In exit mode, against `frozenRoot`. |
| `SweepExit` | It spends one non-accepted vault input. Output 0 pays a **smart wallet**, because registered tokens may not leave PLB. It needs the transfer logic of every registered policy in that input, and it fails while any of them refuses. |
| `AcceptCosigned`, `AcceptProved` | Each input must be deposit-shaped (D6) and hold only `A` and lovelace. `DepositId` is computed from the input's **datum origin**, not its outref (D6). Otherwise as 059-D8f. |
| `RefundAbsent`, `RefundUncredited` | EXIT only. One deposit-shaped vault input. `DepositId` comes from the datum origin. The input's exact value is paid to `refundAddress`, a smart wallet. |
| `BalanceExit` | EXIT only. One output pays `amount` of `A` plus at least `vaultLovelace` to a smart wallet. The continuation lists the escape id. |
| `Consolidate` | EXIT only. Unchanged. It is a vault-to-vault transfer. |

**Shard binding variant.**
- **The problem.** `AssetShardValidator` binds to its vault by requiring an input whose payment
  credential is `vaultScriptHash` (`AssetShardValidator.java:68, 105-112`). No CIP-113 vault
  transaction has such an input.
- **The variant.** `ProgrammableShardValidator`, together with its Aiken twin `programmable_shard.ak`,
  replaces that one predicate. It requires `Script(vaultScriptHash)` among the withdrawals, which
  means the custody handler ran.
- **What stays the same.** The datum, redeemer, fold, proof-shape rules, continuation rules and
  thread policy are unchanged, and the two scripts share source. `shard-backend` selects julc or
  Aiken for the variant, as for the native shard.
- **Unaffected code.** The bootstrap observer and the payout observer read the pinned shard address
  and need no change. The native shard's bytes do not change.

*Alternatives considered (binding):*
- **One shard template that accepts either binding.** It is smaller in code, but it re-baselines
  ADR-059's shard hashes, budgets and differential suite. Rejected.
- **A marker input.** See D4 (a2).

### D6. Deposits: an ordinary CIP-113 transfer, bound to the transaction that creates it (question c)

**What a deposit is.** A deposit is a CIP-113 transfer, or an issuance, whose output at the vault
smart wallet carries the inline datum:

```
ProgrammableDepositDatumV1 = Constr 0 [beneficiary: hash28, refund_address: bytes .size (1..64),
                                       refund_key_hash: hash28,
                                       origin: Constr 0 [origin_tx_id: hash32, origin_index: uint,
                                                         output_index: uint]]
```

**The issuer's transfer logic must accept it.** The deposit is the depositor's own transaction, so
it runs the issuer's transfer logic. If that logic refuses the vault as a recipient, no deposit can
be made (D12, onboarding).

**Origin binding.** A deposit output is creditable only if **both** of these hold:
- `origin_tx_id#origin_index` is one of the **regular inputs** of the transaction that created the
  output;
- `output_index` is the output's own index.

*Why.*
- A third-party action re-creates a vault UTxO with its datum unchanged (§1.1), and the code accepts
  a re-spend that changes nothing.
- With 059's rule (a deposit is any deposit-shaped output, keyed by its outpoint), every such
  re-spend of a pending deposit would be credited again. Any admitted third party could then
  inflate liabilities without new value, against I1.
- A re-created output's origin names an input of the **original** transaction. That input is
  already spent, so it can never be an input of the re-spending transaction.

**`DepositId`.** For a CIP-113 vault it is computed from the origin:

```
blake2b-256("deposit-cip113\0" ‖ CBOR [VaultId, [origin_tx_id, origin_index], output_index])
```

- It identifies the genuine output uniquely: an input is spent at most once, and the index names
  one output of that transaction.
- It survives re-spends. `Accept` and `Refund` compute it from the spent input's datum. So:
  - a re-created output of a credited deposit can still be accepted, because its value is still
    backing;
  - a re-created output of an uncredited deposit can still be refunded in exit mode.
- A donor who copies a datum adds value that can be accepted as surplus, or that the donor's own
  refund key can recover. Neither changes any liability.

**The deposit observer (`asset-vault-deposit-v1`) for a `CIP113` vault**
- It watches the full smart-wallet address.
- It emits `ProgrammableDepositClaimV1`, which carries the `AssetDepositClaimV1` fields plus:
  - the decoded origin;
  - `originSpent`, which is true when the origin input is among the transaction's regular inputs
    and `output_index` equals the event ordinal.
- The parsed block lists each transaction's inputs, so `originSpent` needs no input resolution.
- Native vaults keep `AssetDepositClaimV1` byte for byte.

**`DepositAdmission` (059-D6).** The steps change for a CIP-113 vault:

| Step | Change |
|---|---|
| 2 | Recompute `DepositId` from the origin. |
| 3 | Unchanged: `deposit/<DepositId>` must be absent. |
| 4 | Creditability also requires `originSpent`, and a `refund_address` that is a smart wallet of the pinned deployment with a key-hash owner (D7). A 3-field 059 datum at a CIP-113 vault is `MALFORMED`. |
| 5 | **A record is written under `deposit/<DepositId>` only when `originSpent` holds.** Otherwise admission writes `vault-output/<outpoint> = RECREATED(DepositId)` and changes no counter. |

**Why step 5 matters.**
- A re-created or copied output therefore never credits.
- It also never pre-empts the genuine deposit, whatever the canonical order of observations within
  a block (I35).
- A front-runner cannot claim someone else's deposit id: that needs a transaction spending the
  victim's own input.

**The builder.** The domain API's unsigned deposit builder:
- builds the transfer with CCL 0.8.0-pre8. The depositor's smart wallet is key-owned, which CCL
  supports;
- sets the origin to the depositor's spent PLB input that holds `A`. That input is always spent, so
  the origin is known before balancing.

**Mint deposits.** An issuer may mint directly into the vault with this datum. Its own inputs supply
the origin.

**Limit.** An admitted third party can seize vault custody and pay it back to the vault, in one
transaction, as a fresh output with a new datum and an origin of its own. That is credited. The
credit has no new backing, so it is a seizure by another route. D14 detects it as a shortfall, and
D12's requirement that third-party logic be permissioned keeps it with the issuer.

*Alternatives considered:*
- **Keep the outpoint `DepositId` and refuse deposits from transactions that carry a third-party
  action.** Rejected. The observer cannot identify that action reliably: the dispatcher and
  delegates can be re-pointed, and it cannot tell which inputs are at PLB.
- **A depositor-chosen nonce, first use wins.** Rejected. It can be front-run: anyone who sees a
  pending deposit can spend its nonce on a dust output.
- **Track every vault outpoint in state and refuse deposits whose transaction spends one.**
  Rejected. It needs each transaction's input list in the observations, and unbounded state. It
  also brings back the same-block ordering problem that 059 rejected (A-F1, B-F4).

### D7. Payouts go to smart wallets (question d)

**Recipient rule.** A claim that carries `A` must pay a **smart wallet of the pinned deployment**:
- a base address on the domain network;
- payment credential `Script(PLB)`;
- a **key-hash** stake credential.

`CardanoAddresses.payableSmartWallet(deployment, network)` is the shared check. Pointer,
enterprise and reward forms are rejected. So are script owners (Q1): a smart wallet keyed by a
script can be spent only through that script's withdrawal, which the gateway cannot check. 059-D7
rejects script recipients for the same reason.

**Lovelace-only claims.** A claim with an empty `AssetValue`, which withdraws the min-ADA credit
(059-D11), may also pay an ordinary 059-D7 recipient. Lovelace is not a programmable asset.

**Where the rule applies**

| Place | Rule |
|---|---|
| `REQUEST_WITHDRAWAL` (059-D14) | Feasibility per the vault's custody kind, read from the registry entry. |
| `ForcedWithdrawalV1.recipient` (059-D8g R3) | A non-smart-wallet recipient for `A` is `INFEASIBLE_RECIPIENT`. |
| `refund_address` | D6. |
| `BalanceExit` recipient | Chosen by the holder at exit time. A wrong one fails in the transfer delegate, and the holder rebuilds. |
| Sweep recipients | D5. |
| `MIGRATE` targets | The target vault's smart wallet, with 059-M4b. |

**Script side.** The custody script compares raw bytes rebuilt from the Plutus address, as 059 does.
A smart wallet is a base address with a script payment part and a key stake part. The rebuild
already handles base addresses.

**Issuer refusal**
- **The problem.** The issuer's transfer logic may refuse a recipient: for example a frozen holder,
  or one missing from an allowlist. The ledger cannot read issuer state, so a reservation cannot
  check this.
- **What happens.** Such a claim stays `RESERVED`, and it is payable as soon as the issuer admits
  the recipient. v1 has no release (059-D7, 059-Q7). 059-I19 therefore holds for CIP-113 vaults
  only **while the issuer accepts the recipient and the vault**.
- **Mitigation.** The client runs a preflight: it evaluates a transfer to the recipient against the
  live issuer logic before the user signs `REQUEST_WITHDRAWAL`, and warns on refusal (Q7).

**What the user sees**
- **The smart-wallet address.** By default it is derived from the user's payment key, as the CIP
  suggests (spec l.131) and as CCL's default `SmartWalletKey.PAYMENT` does. The stake-key form
  is used when the user picks it (Q10).
- **Who controls it.** That the wallet is controlled by that key under the issuer's rules.
- **Wallet support.** That wallets without CIP-113 support may not show it.
- **The trust label** (D14).

### D8. Min-ADA and fees (question e)

**Accounting.** 059-D11's accounting is unchanged:
- a deposit's lovelace becomes the depositor's min-ADA credit;
- `vaultLovelace` is fixed at reservation;
- builders may supplement a payout;
- fees and collateral come from the relayer;
- there are no cross-asset charges.

**Larger outputs.** Every vault, payout and deposit output is a base address of 57 bytes, where a
native vault uses a 29-byte enterprise address. The continuation datum can reach 992 bytes.

Estimates at 4,310 lovelace per byte, from `(160 + output bytes) × coinsPerUTxOByte`; C1 measures
them:

| Output | Estimate |
|---|---:|
| A smart-wallet payout of one asset with a 32-byte name | ≈ 1.31 ADA |
| A deposit | ≈ 2.1 ADA |
| A 16-id continuation | ≈ 3.7 ADA |
| A 29-id continuation | ≈ 5.6 ADA |

So a CIP-113 vault pins two values of its own, in its genesis profile:
- **`settlement.min-queued-vault-lovelace`**, from the measured smart-wallet payout. 059's default of
  1,300,000 is just short of it.
- **`continuationFloor`**, at least the min-UTxO of a continuation at the id cap.

**Fees.** They are higher, and the relayer still pays them. They include:
- reference-script fees for PLB, PLG, the transfer delegate, the issuer's transfer logic, the
  custody script and the shard;
- execution of PLB per vault input, PLG, the transfer delegate, the issuer logic, the custody
  script and the shard.

**Third-party lovelace.** A third-party action may add lovelace to vault outputs, never remove it
(`third_party.ak:218-231`). Reconciliation counts the addition as lovelace surplus.

**Frozen lovelace.** Lovelace in a vault UTxO moves only with that UTxO's tokens. While the vault
is frozen, the min-ADA credits are frozen too (D11).

**Fixed costs.** The stake registration deposit is a fixed, non-refundable operator cost (D2).

### D9. Script size and execution budget (question f)

**A separate script.** The custody script cannot be the native vault script (D2). It reuses the
native vault's verifier code at the source level:
- `MpfOnChainVerifier` (inclusion and `verifyAbsence`);
- `MpfAnchorLib`;
- the `GatewayCodec` rebuilds of `ClaimLeafV1`, `ClaimId`, exit leaves and escape ids;
- the V5–V7 output walkers;
- the exit-mode thread read;
- the shard insert-list check.

**What changes against the native vault**
- **Drops:** the per-input lead and `Follow` logic, and the enterprise-address checks.
- **Adds:** V14 enumeration, smart-wallet rebuilds, the origin-keyed `DepositId` and V16.

**Size target.** It should land within about 1 KB of the native vault. The same 15,500-byte guard
applies: `GatewayBudgetTest.scriptSizes` is extended to the applied custody script and shard
variant, per backend.

**Fallback.** If the applied script exceeds the guard, the exit-mode-only actions (`Refund`,
`BalanceExit`, `Consolidate`, and the proved `Accept` in EXIT) move to a second withdraw script,
which the custody requires in EXIT. That split is specified in an ADR revision before any code
(Q9).

**Per-transaction overhead to measure (C1)**
- **Execution:**
  - PLB per vault input;
  - PLG;
  - the transfer delegate, which checks one registry proof and scans inputs and outputs;
  - the issuer's transfer logic, which the issuer defines (freeze-and-seize reads its freeze
    list);
  - the custody script;
  - the shard.
- **Bytes:**
  - four withdrawals and their redeemers;
  - a spend redeemer per vault input;
  - the reference inputs: protocol params, the registry node, global state if any, the
    substandard's state, and the reference-script UTxOs.

The CIP-113 core is small next to a `Settle`. An indicative one-input, one-policy transfer through
PLB, the dispatcher and the transfer delegate costs about 257,000 memory and 97 million CPU units.
A 16-claim `Settle` costs 9.86 million memory units (`BUDGETS.md`). The caps are therefore expected
to be bound by transaction size and the issuer's logic.

**Caps**
- **On chain:** 29, by V16.
- **Profile defaults:** `Settle` 16 and `Exit` 8.
- **Per asset.** C1 measures every action for each supported asset with its substandard, at full
  transaction size with placeholder witnesses: `Settle` k, `Exit` k, `AcceptCosigned` and
  `AcceptProved` k, `BalanceExit` with 1, 4 and 8 vault inputs, `Consolidate`, `Refund` and
  `SweepExit`. Where the measured caps are lower, the vault's profile parameters set lower batch
  and vault-input caps.

### D10. Yano X assembles the transactions (question g)

**Why Yano X.** CCL 0.8.0-pre8 cannot authorize a script-owned smart wallet (§1.1).

**The assembler.** `ProgrammableVaultTxAssembler`, in `asset-gateway-cardano-support`, builds every
CIP-113 vault transaction: the owner's `Settle` and co-signed `Accept`, the relayers' `Exit` and
proved `Accept`, and the SDK's exit-mode transactions. The client SDK carries the same assembler.

**What it reuses from CCL:**
- `Cip113Deployment` and `Cip113Deployments`;
- `LedgerOrdering` and `PolicyOrdering`;
- the registry-node and datum codecs.

It does not depend on CCL's transfer builder.

**Ordering rules.** Every index is computed against the ledger's final order, never against
insertion order:

1. **Inputs** are sorted by `(tx id, index)`.
   - Each vault input gets `BaseSpendRedeemer{params_idx, wdrl_idx}`.
   - The shard input gets its `InsertBatch`.
   - Spend redeemer indexes are input positions.
2. **Reference inputs** are sorted the same way.
   - `params_idx` is the position of the UTxO holding the pinned params policy's `ProtocolParams`
     token.
   - `TokenExists{node_idx}` is the position of `A`'s registry node.
   - The anchor and the exit-mode thread are found by their tokens, not by index.
3. **Withdrawals** are ordered as the ledger orders them: script credentials before key
   credentials, bytewise within each kind. The four entries are:
   - PLG, with `TransferAct`;
   - the transfer delegate, with `TransferRedeemer{proofs}`: one proof per distinct non-ADA policy
     in the vault inputs, in ascending order, which is `[A]` for accepted custody;
   - `A`'s `transfer_logic_script`, with the substandard adapter's redeemer;
   - `Script(h)`, with the action.

   Each amount is 0. `wdrl_idx` is the position of PLG, computed **after all four are present**.
   Reward redeemer indexes are positions in the same order.
4. **Outputs.** Payouts come at positions `0..k−1` (V5), then the continuation at `k` (V7), then the
   shard continuation, then change at the fee wallet.
5. **Live wiring.**
   - The dispatcher credential is read at build time from the live protocol-params datum
     (field 0).
   - The delegate hashes come from the deployment record for that dispatcher.
   - `transfer_logic_script` and `global_state_cs` come from the live registry node.
   - A dispatcher that the pinned record does not describe stops assembly for the deployment,
     fails closed, and raises the D14 drift flag.
6. **The substandard adapter** is a support-library interface. From the draft transaction and the
   registry node, it returns the transfer-logic redeemer and the extra reference inputs (global
   state, user state).
7. **Execution units** are evaluated on the final body. The size is measured with the final witness
   count (059-D9).
8. **Rebuilds.** A transaction is rebuilt when any of these is spent: the anchor, the protocol
   params, the registry node, or a vault input that a third-party re-spend moved. 059-D9's input
   holds and pending-insert rules apply unchanged. A missing vault input counts as contention.

### D11. The escape hatch under issuer control (question h)

**Unchanged.** 059-D8g is unchanged at the domain level: the forced inbox, triggers (a) to (c), the
exit-mode thread and data availability. Exit mode freezes the domain for every vault, as before.

**What changes for a CIP-113 vault.** Every exit-mode spend runs the issuer's transfer logic,
because each is a `TransferAct`: `Exit`, `BalanceExit`, proved `Accept`, `Refund` and `Consolidate`.

**If the issuer freezes the vault credential**, or changes the token's logic to refuse it:
- **no holder can exit that asset**, by `Exit`, by `BalanceExit` or by any other path;
- the min-ADA credits in those UTxOs are frozen with it. The public deployments cannot unfrack
  (§1.1), and an unfracking action would need its own custody redeemer anyway, which v1 does not
  have.

Every other vault of the domain exits normally (I38).

**If the issuer seizes custody**, the frozen root obliges more than the vault holds. Exits are
paid in the order they land until the asset's custody runs out. v1 has no pro-rata allocation (Q2).

**This is the stated limit.** The escape hatch protects against operators, never against the
issuer. Every user surface says so (D14).

### D12. Supported deployments and substandards (question i)

**Contracts.** cip113-programmable-tokens v0.0.1 at `6b75ba32` (§4).

**Deployments**
- **Preview, Preprod and Mainnet**, as CCL 0.8.0-pre8's `Cip113Deployments` records. Each record
  pins the bootstrap transaction, the params policy, the PLB hash, the registry node policy, the
  reference scripts and `maxInlineDatumBytes = 1024`, and has `unfrackingEnabled = false`.
- **Devnet.** A fixture deployment of the same blueprint on Yaci DevKit, with the same bound and no
  unfracking, described by a `Cip113Deployment` that the fixture writes. CCL pre8 has no public
  bootstrap API. The fixture bootstraps the protocol params, the registry origin, the issuance
  template and the reference scripts as the deployer records describe, and then uses CCL for
  registration and issuance.
- The spec's own Preview version (`61fae36e…`) is not supported (§4 item 4).

**What a CIP-113 vault pins, in its registry entry, genesis-fixed**
- **The deployment identity:** the bootstrap transaction hash, params policy, PLB hash, registry
  node policy and `maxInlineDatumBytes`.
- **The token and its custody:** the policy id and asset name, the custody backend and script hash,
  and the smart wallet address.
- **The substandard:** its id and version.
- **A drift baseline:** the registry node's logic credentials and the protocol-params wiring at
  registration. The baseline is informational only and is used by D14.

**Substandards in v1.**
- **Freeze-and-seize**, the CIP-113 reference stablecoin substandard, pinned at a commit chosen in C2,
  through a Yano X adapter.
- **A minimal Yano X test substandard** with the same powers, for the devnet drill if the reference
  substandard cannot be used there (Q5).

**Requirements for any substandard**
1. Its third-party logic is permissioned to a designated admin set. A permissionless one would let
   anyone perform D6's seize-and-redeposit.
2. Its transfer logic needs no signer beyond the owner's authorization. Members cannot obtain
   issuer signatures.
3. Its adapter builds the transfer-logic redeemer and reference inputs deterministically from L1
   data.
4. Its powers (freeze, seize, burn, the admin set and who triggers each) are written into the trust
   label.

**Issuer onboarding.** Before deposits open, the issuer admits the vault smart wallet as a holder
and as a sender wherever the substandard keeps allowlists. The doctor checks this by preflight.

### D13. Members' body checks (question j)

**Base check.** 059-D8h §1 applies to every co-signed CIP-113 vault transaction (`Settle` and
co-signed `Accept`).

**Changed steps**

| Step | For a CIP-113 vault |
|---|---|
| 1 | Resolve every input **and every reference input** against the member's own `l1UtxoView()`. Refuse on any unknown. |
| 2 | The vault inputs are exactly the inputs at the vault smart wallet. No other PLB input. Script inputs are only those and, for `Settle`, the shard input. |
| 3 | `mint` and certificates are empty, and there are no votes, proposals or treasury donation. The withdrawals are **exactly** the set below, each of amount 0. No key-credential withdrawal is allowed. |
| 3a | The redeemers are **exactly**:<br>- one `BaseSpendRedeemer` per vault input;<br>- the shard's `InsertBatch` (`Settle`);<br>- `TransferAct`;<br>- `TransferRedeemer[TokenExists{node_idx of A}]`;<br>- the transfer-logic redeemer, byte-equal to what the member's own adapter builds;<br>- the custody action.<br>Every index matches the sorted lists. A wrong index would only fail on L1, but checking it keeps the request closed. |
| 3b | The reference inputs are **exactly**:<br>- the pinned anchor and the exit-mode thread;<br>- the protocol params, authenticated by the pinned params policy's `ProtocolParams` token;<br>- `A`'s registry node, authenticated by the registry node policy's token named after `A`'s policy;<br>- global state, if the node names it;<br>- the adapter's state references;<br>- reference-script UTxOs, each holding a script that the transaction runs. |
| 5 | Unchanged (`Settle`). |
| 6 | Unchanged. |
| 7 (new) | The only PLB outputs are the payouts (V5, D7) and the continuation (V7, V15, V16). Change goes to the fee wallet's non-PLB address. The only required signers are members. |
| 8 (new) | **Drift.** If the live wiring differs from the registration baseline, the member still signs: a further script only adds constraints, and the body fixes every value movement. It raises the drift flag (D14, Q4). |

The step-3 withdrawal set is:
- the dispatcher named by the live protocol params;
- that dispatcher's transfer delegate;
- `A`'s live `transfer_logic_script`;
- `Script(h)`.

**Co-signed `Accept`.** It uses the same checks, plus 059-D8h step 4, with deposit records looked up
by the origin-keyed `DepositId` (D6).

**Domain status.** A vault in `ISSUER_HOLD` is refused for `Settle` (D14).

### D14. The `ISSUER_CONTROLLED` trust label, reconciliation and `ISSUER_HOLD` (decided by the maintainer)

**The label.** `ISSUER_CONTROLLED` is data in the asset's registry entry and in the profile
descriptor. It is shown on every 059-D18 surface next to the domain label. It states:
1. The issuer, or its admin set, can **freeze** the vault. No payout, exit or refund of the asset is
   then possible, including the min-ADA lovelace held with it.
2. It can **seize or burn** the vault's tokens without anyone's consent. Balances in the ledger
   stay, and the vault falls short.
3. The minting authority can **change** the token's transfer, third-party and unfracking logic at
   any time, for tokens already deposited.
4. The deployment's **CIP-113 upgrade authority** can re-point the dispatcher and the delegates for
   every programmable token, and could move them all.
5. A token can never be de-registered. Payouts go only to smart wallets, and the issuer may refuse
   a recipient.
6. The escape hatch does not override the issuer.

The substandard's own powers (D12 item 4) are listed with the issuer's name.

**Detection.** It is local, and extends 059-D15. For each CIP-113 vault, reconciliation reports:
- **(a) Shortfall per unit.** The 059 equation is unchanged.
- **(b) Attribution.** It lists vault UTxOs spent by a transaction without `h`'s withdrawal, which
  are third-party actions, and burns of `A`. Reconciliation has L1 data, so it can resolve inputs.
- **(c) Freeze.** The owner and the relayers classify a failed evaluation of `A`'s transfer logic as
  `ISSUER_REFUSED`, a new failure reason in 059-D15a.
- **(d) Drift.** The live registry node or protocol-params wiring differs from the baseline.

**Response.** Any of (a) to (d) raises an alert. Under the runbook, it obliges members to submit a
governed **`ASSET_HOLD(VaultId, reason)`**, which sets the new registry status **`ISSUER_HOLD`**:

| Activity | While `ISSUER_HOLD` |
|---|---|
| Deposits | Not credited. They are recorded `UNCREDITED(ASSET_HELD)`. |
| Transfers and voluntary withdrawals of `A` | Refused. |
| Co-signed `Settle` for the vault | Refused by members. |
| `Accept` | Allowed. It pays nobody. |
| Forced withdrawals | Still reserved (059-D8g R3: no registry status rejects them). |
| Proof `Exit` | Unchanged. |

`ASSET_RESUME` lifts the status.

**Liabilities never change.** No code path reduces balances to match custody (059-D15). A governed
loss allocation is out of v1 (Q2).

**Not automatic.** A stateless block observer cannot see issuer events reliably, because it cannot
resolve inputs. Q3 records an observer of the registry-node and protocol-params token outputs that
would set the hold automatically on drift.

**Consequences for 059-§13a.** These are summarized in §13a below:
- a frozen vault blocks `Exit`, `BalanceExit` and `Refund` for that asset;
- a seizure is a reserve shortfall that the ledger keeps visible and does not absorb;
- every guarantee that does not depend on the issuer still holds for that asset, for as long as the
  issuer permits transfers.

## 6. Invariants

These continue 059-§6. 059's invariants hold for CIP-113 vaults as stated there, except 059-I19,
which holds only while the issuer accepts the recipient and the vault (D7).

| ID | Invariant | Decisions |
|---|---|---|
| I34 | Every vault input spent under the vault's authority is checked by the custody's withdraw handler. Vault inputs are the inputs at `(PLB, Script(h))`. The credential `Script(h)` authorizes nothing else, and it can never be deregistered or delegated. | D2, D5 |
| I35 | A CIP-113 deposit is credited only for an output whose datum origin names one of its own transaction's regular inputs and its own output index. Each `DepositId` is credited at most once. Re-created and copied outputs never credit, and never pre-empt the genuine deposit, whatever the order within a block. | D6 |
| I36 | No action of the issuer, a third party or the upgrade authority changes ledger liabilities. Seizures and burns of custody appear as a reconciliation shortfall and lead to a governed `ISSUER_HOLD`. No code path writes balances to match custody. | D14 |
| I37 | Payouts that carry the asset go only to smart wallets of the pinned deployment with a key-hash owner. Lovelace-only payouts may also go to an ordinary 059-D7 recipient. | D7 |
| I38 | Accepted custody of a CIP-113 vault holds only its asset and lovelace, and one vault serves one asset. So only that asset's transfer logic runs on vault spends, and an issuer's action on one asset never blocks another asset's vault. | D1, D5 |
| I39 | A continuation's id list never exceeds the cap derived from the pinned datum bound (29 at 1,024 bytes). So every transaction the custody script accepts also meets the CIP-113 delegates' output bound. | D4 |
| I40 | The julc and Aiken custody backends accept and reject exactly the same transactions, and so do the two backends of the shard binding variant. The selected backend is part of the pinned identity, and a mismatch fails startup. | D3, D5 |
| I41 | A member signs a CIP-113 vault transaction only after resolving every input and reference input, and only when it finds exactly the withdrawals, redeemers and reference inputs that D13 lists. | D13 |
| I42 | A payout confirmation or a balance exit of a CIP-113 vault is accepted only from a shard-authenticated transaction. Outputs re-created by third-party actions never confirm a claim or redeem an exit leaf. | D4, 059-D10 |

## 7. Compatibility

**New formats.** Each is versioned, and its CDDL lives beside 059's:
- `ProgrammableDepositDatumV1`, and the CIP-113 `DepositId` derivation tag `deposit-cip113\0`;
- `ProgrammableDepositClaimV1`;
- `vault-output/<outpoint>` records (`RECREATED`);
- the registry entry's `custody` field with its CIP-113 pins, the `ISSUER_HOLD` status and the
  `ASSET_HOLD` and `ASSET_RESUME` commands;
- the `ISSUER_CONTROLLED` label;
- the on-chain ABI `asset-gateway-onchain-cip113-v1.cddl`: the custody redeemers (059's
  `vault_redeemer` without `Follow`), the datum and the shard variant;
- the observer settings: per vault `custody: native | cip113` plus the pins of D12, and
  `programmable-backend`. They enter each observer's consensus identity.

**Existing formats.** None change:
- native vaults keep their scripts, claim bytes and observer identity bytes;
- the ADR-059 shard and its hashes are unchanged;
- the EUTxO bridge is unchanged (059-I14).

**ADR-059 sections this ADR amends, additively.** ADR-059 gets a pointer revision when this ADR is
accepted:

| Section | Amendment |
|---|---|
| D5 | The custody kind per vault. |
| D6 | `DepositId` per custody kind. |
| D7 | `payableRecipient` per custody kind. |
| D10 | The reward-redeemer read. |
| D14 | Statuses and `custody`. |
| D15 | Attribution and `ISSUER_REFUSED`. |
| D18 | The second label. |
| I19 | Its scope. |

**Framework.** No addition beyond 059-§7.

**Modules.** No new module is added. Each existing module gains:

| Module | Additions |
|---|---|
| `asset-gateway-onchain` | Custody and shard-variant scripts in both backends. |
| `asset-gateway-contracts` | The datum and the ids. |
| `asset-gateway-cardano-support` | The assembler, the adapter, `payableSmartWallet` and the body-check extension. |
| `asset-gateway-cardano` | The observer settings, reconciliation, the doctor and the deposit builder. |
| `ledgers/asset-payments` | The statuses and the feasibility rules. |

**Dependency.** `com.bloxbean.cardano:cardano-client-programmable-token:0.8.0-pre8` enters the
support library and the gateway bundle. At `9ff0ae87` the repository pins CCL 0.8.0-pre7
(`gradle/libs.versions.toml:4`), so the CCL 0.8.0-pre8 upgrade is an entry gate of C2. The bundle,
ZIP and Central size budgets are checked when the dependency is added.

## 8. Milestones

| ID | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| C0 | This ADR | — | Accepted. Q1–Q12 decided or explicitly deferred. |
| C1 | **On-chain:** the custody script and the shard binding variant, each in julc and Aiken; the CIP-113 CDDL; artifacts and drift tests, as for the Aiken shard | C0; 059-M4 on-chain code | The differential suites pass (I40). The compiled-UPLC composition tests pass for every action, and every negative fails in the intended script (§10 P2, P3). Applied sizes are within the guard, or the Q9 split is decided. Budgets and caps are measured per action with the test substandard (D9). I34, I38, I39. |
| C2 | **Runtime:** contracts (datum, `DepositId`, claim); observers; `DepositAdmission`; the assembler; the freeze-and-seize adapter; the body check; `payableSmartWallet`; `ISSUER_HOLD` and the label; reconciliation and doctor extensions; the CIP-113 deposit builder | C1; the repository on CCL 0.8.0-pre8 | Observer fixtures P4 pass. Assembler vectors P5 pass. Admission is property-tested for I35 under every same-block order. The body-check negatives pass (I41). I36, I37, I42. |
| C3 | **Devnet drill** (§10 P7) | C2; 059-M4a for its exit-mode steps | Every step of P7 passes. Budgets are re-measured on devnet transactions. |
| C4 | **Preprod rehearsal and production gate** | C3; explicit authorization for preprod operations (AGENTS.md) | §12, plus 059-§12. Not part of this ADR's acceptance. |

## 9. Consequences

**Positive**
- CIP-113 stablecoins and other regulated tokens can use the anchored-claim vault, the co-signed
  fast path and the proof exit, with no change to accounts, intents or claims.
- Issuer actions cannot inflate or silently deflate liabilities, and they cannot forge
  confirmations.
- Freezes are isolated per asset.
- The custody script is simpler than the native vault in one respect: one redeemer per transaction,
  so there is no `Follow` marker.

**Costs**
- Two more scripts in two backends each: the custody script and the shard variant. Two more
  differential suites.
- The transaction assembler is Yano X code, not CCL's.
- Larger outputs and higher fees for every action.
- A substandard adapter for each supported substandard.
- For these assets, the escape hatch and exit-mode solvency hold only at the issuer's discretion.
  This is stated, not solved.
- A claim to a recipient the issuer refuses waits indefinitely in v1.

## 10. Verification

Evidence must be independent of the code under test (059-§10).

**P1 — differential.** `ProgrammableVaultBackendDifferentialTest` and the extended
`AssetShardBackendDifferentialTest` run both backends on the same script contexts. That covers
every action, the proof shapes, systematic and random mutations and lenient encodings. Any
differing verdict fails.

**P2 — compiled-UPLC composition.** Every script of a vault transaction is evaluated in one script
context:
- the pinned PLB, PLG and transfer delegate, taken from `plutus.json` at `6b75ba32` (sha256 checked)
  and applied with the devnet deployment's parameters;
- the test substandard's transfer logic;
- the custody script and the shard variant, compiled and applied;
- julc's VM, which already evaluates the Aiken shard.

An honest transaction of each action passes every script. This shows that Yano X's assembled
transactions satisfy the real CIP-113 scripts, not a model of them.

**P3 — validator negatives.** Each rule V14–V17 has at least one negative test, and every
059-§10 V6 case is re-run against the custody script. They add these cases:
- a vault input the handler does not count, such as a second vault smart-wallet input left out of
  V6;
- another owner's PLB input in a vault transaction;
- a second output carrying `h` as its stake credential;
- an output at `h`'s payment address;
- a continuation with 30 ids, and one with 29;
- a certificate or a mint in a vault transaction;
- the `Follow` constructor used as an action;
- `Accept` of a re-created deposit output: passes when its origin's record is `CREDITED`;
- `Accept` of a copied datum carrying a foreign token: fails;
- `Refund` of a credited origin;
- a payout to a pointer, enterprise or script-owned smart wallet, and to a plain address for a
  token claim;
- the shard variant spent without the custody's withdrawal;
- the custody's `publish` handler with deregistration, delegation and register-and-delegate;
- the `spend` handler.

**P4 — observer fixtures**, for a CIP-113 vault:
- a deposit with a valid origin;
- a no-op third-party re-spend of a pending deposit in a later block, and in the same block in both
  hash orders: credited once, and `RECREATED` recorded;
- a partial seizure of a pending deposit in the same block, in both orders;
- a donated copy of a credited deposit's datum;
- a 3-field 059 datum;
- a refund address that is not a smart wallet;
- an issuer mint into the vault;
- a payout to smart wallets;
- a look-alike continuation from a third-party transaction: ignored;
- a balance exit read from the custody's reward redeemer;
- outputs at a recipient smart wallet that are never mistaken for the vault's.

**P5 — assembler vectors.**
- `params_idx`, `wdrl_idx`, `node_idx` and every redeemer index are computed by an independent
  Python implementation of the ledger orderings and compared with the assembler's.
- Then P2 checks them against the real scripts.
- The vectors include a fee wallet and reference-script UTxOs whose sort positions move each index.

**P6 — budgets.** All budgets are measured, never estimated (D9). The results go into a CIP-113
section of `BUDGETS.md`.

**P7 — devnet drill** (C3), on Yaci DevKit:
1. **Deployment.** Bootstrap the fixture CIP-113 deployment from the pinned blueprint.
2. **Issuance.** Register and issue a test stablecoin, a freeze-and-seize substandard or the Yano X
   test substandard, with CCL 0.8.0-pre8. Mint to Alice's smart wallet.
3. **Vault bootstrap.** Register the custody stake credential, bootstrap the 16 shard threads, and
   have the issuer admit the vault where required. Start the chain with the CIP-113 vault in
   genesis.
4. **Deposit.** Alice deposits through the CCL-built transfer. She is credited exactly once across a
   restart and a rescan.
5. **Pay.**
   - Alice transfers to Bob in the ledger.
   - Bob withdraws to his smart wallet by co-signed `Settle`.
   - The payout observer confirms it, and Bob's smart wallet holds the tokens.
   - `Accept` runs on its timer.
6. **Proof exit.** With the owner stopped, a third-party relayer pays a matured claim by `Exit`.
7. **Exit to a smart wallet.**
   - Stop every operator. After the heartbeat timeout, anyone enters exit mode.
   - Alice's `BalanceExit` lands in her smart wallet.
   - A second `BalanceExit` fails.
8. **Issuer actions.**
   - A no-op re-spend of a pending deposit: no double credit, and the re-created output is accepted
     by its origin.
   - A seizure from the vault: reconciliation reports the shortfall and attributes it, governed
     `ASSET_HOLD` follows, and liabilities are unchanged.
   - A freeze of the vault: `Settle` and `Exit` fail with `ISSUER_REFUSED`. After unfreezing they
     succeed.
   - A frozen recipient: the claim stays `RESERVED`, and after unfreezing it is paid.
9. **Upgrade drift.** A re-pointing on the fixture deployment through its upgrade multisig raises
   the drift flag.

The drill also confirms the withdraw-zero against the registered, undelegated custody credential
under the devnet's protocol version.

## 11. Risks

| Risk | Mitigation |
|---|---|
| The issuer freezes, seizes or re-points | Stated label (D14). Per-asset isolation (I38). Detection and `ISSUER_HOLD`. |
| The upgrade authority of a public deployment is compromised | The label names the authority. Drift detection. Only pinned deployments are supported. |
| A substandard with permissionless third-party logic is registered | D12 requirement 1; governance review at `ASSET_REGISTER`. |
| The custody script exceeds the size guard | Measured in C1. The Q9 split. |
| The issuer's transfer logic makes payouts expensive or big | Caps measured per asset (D9). Per-vault caps. |
| CIP-113 changes (the CIP is Proposed; the contracts are v0.0.1) | Exact pins (§4). A new deployment or contracts version is a new vault plus `MIGRATE`, with 059-M4b (Q11). |
| Index mistakes in self-built transactions | They are self-validating on chain (a wrong index only fails). P2 and P5. |
| Users send tokens to a plain-address mental model | Smart wallets only. Client preflight and display (D7). |

## 12. Production and audit gates (in addition to 059-§12)

1. Independent review of the custody script in both backends (every action and its `publish` and
   `spend` handlers) and of the shard binding variant.
2. Independent review of the assembler, the origin-binding admission and the CIP-113 body-check
   steps.
3. Review of each supported substandard and its adapter, with its powers written into the label.
4. A CIP-113 deployment whose contracts and upgrade authority have been assessed, and whose
   assessment is shown with the label.
5. The issuer onboarding and incident runbook (`ASSET_HOLD`) drilled on preprod.

## 13. Open questions

**Decided by the maintainer before drafting:**

| ID | Decision |
|---|---|
| — | One vault per asset; the vault is a smart wallet (D1). |
| — | julc and Aiken custody backends, julc by default, the choice pinned and differentially tested (D3). |
| — | The `ISSUER_CONTROLLED` label, with the stated consequences: a frozen vault blocks `Exit`, and a seizure is a shortfall that pauses the asset, with liabilities unchanged (D14). |

**Open:**

| ID | Question | Lean |
|---|---|---|
| Q1 | Allow smart wallets owned by a script as recipients? | No in v1. Revisit with a concrete DeFi integration. |
| Q2 | Loss allocation after a seizure: a governed pro-rata reduction, or none? | None in v1. Liabilities stay, and the shortfall is visible. A loss-allocation transition needs its own ADR. |
| Q3 | Detect issuer events in consensus: an observer of the registry-node and protocol-params token outputs that sets `ISSUER_HOLD` automatically on drift? | Not in v1. Local detection plus a governed hold. Revisit after C3. |
| Q4 | On wiring drift, should members sign or refuse? | Sign and flag (D13 step 8). Refusing would freeze users' payouts for an issuer's change. |
| Q5 | Which substandards in v1, and who reviews them? | Freeze-and-seize at a pinned commit. A Yano X test substandard only for devnet. |
| Q6 | May lovelace-only claims from a CIP-113 vault pay ordinary addresses? | Yes (D7). |
| Q7 | Re-target or release a claim whose recipient the issuer refuses? | Not in v1, consistent with 059-Q7. A client preflight is required. |
| Q8 | Should `ISSUER_HOLD` also stop co-signed `Settle`? | Yes (D14). Proof `Exit` stays open, as 059's brake rules require. |
| Q9 | If the custody script exceeds the guard, split the exit-mode actions into a delegate withdraw script? | Yes, through an ADR revision before code. |
| Q10 | The smart-wallet key convention shown to users | Payment key by default (CIP, CCL). The stake-key form is accepted. Both are key-hash owners. |
| Q11 | Moving to a new CIP-113 deployment or contracts version, which means a new PLB hash | A new vault plus `MIGRATE` (059-M4b), with a fresh trust assessment. |
| Q12 | Should the drift baseline also pin the issuer's transfer-logic script hash for the members' check, instead of only for the flag? | No. Pinning it would freeze the vault on every legitimate issuer upgrade. The flag is enough. |

## 13a. Guarantees delta (to 059-§13a)

| Threat | Guarantee for a CIP-113 asset | How |
|---|---|---|
| The issuer freezes the vault, or changes the token's logic to refuse it | **Not prevented.** The asset's custody, and the min-ADA lovelace with it, is locked, through the escape hatch too. | Label (D14). Other assets are unaffected (I38). Detected, then `ISSUER_HOLD`. |
| The issuer seizes or burns vault custody, or re-deposits seized custody | **Not prevented.** It becomes a reserve shortfall. Liabilities are unchanged and visible. | Reconciliation attribution, governed `ISSUER_HOLD` (I36). |
| The upgrade authority re-points the deployment | **Not prevented** | Label, drift flag |
| The issuer refuses a recipient | **Not prevented.** The claim waits. | Client preflight (D7) |
| A third party re-creates vault outputs (including no-op re-spends) | **Prevented:** no double credit, no false confirmation | I35, I42 |
| Anyone creates look-alikes at the vault smart wallet | **Prevented**, as in 059 | V10, V15, I42 |
| A relayer, the owner or anyone pays the wrong amount or recipient, or pays twice | **Prevented on chain**, as in 059 | V4–V8, V14–V17 |
| Operators go offline, censor or keep the domain paused | **Funds are not locked**, as in 059, **while the issuer permits the transfers** | 059-D8g; D11 |
| A colluding threshold | Unchanged from 059 | — |

## 14. Alternatives considered (overall)

- **Wrapping CIP-113 tokens in a native representation.** Rejected. The tokens cannot leave PLB
  except by burning, and a wrapper would bypass the issuer's rules, which is the point of the
  standard.
- **Holding the tokens in the native vault through unfracking or third-party actions.** Impossible:
  every path keeps the tokens at PLB.
- **A members' multisig smart wallet.** Rejected (D2): no permissionless exit.
- **A shared smart wallet for all CIP-113 assets.** Rejected (D1).
- **Extending `AssetVaultValidator`.** Rejected (D2, D9): size, and its spend handler would be dead
  code.
- **Waiting for CCL to support script-owned smart wallets.** Not needed. The assembler is small,
  self-validating on chain and tested against the real scripts. A later CCL feature could shrink it
  (§15).

## 15. Related findings (out of scope)

1. **CCL.** CCL 0.8.0-pre8 cannot build spends from a script-owned smart wallet
   (`SmartWalletAddress.java:62-74`). An upstream withdrawal-based authorizer would let Yano X drop
   part of its assembler. That is a feature request, not a dependency.
2. **The spec.** Its Preview version table and three rules differ from the pinned contracts (§4).
   That is for the CIP's authors, not for this ADR.
3. **The ADR index.** `adr/README.md` has no row for ADR-058, whose file lives on its own branch
   (PR #107).

## Revision history

- **r1** (2026-11-28): Initial proposal. It records the maintainer's three decisions (one
  smart-wallet vault per asset, julc and Aiken custody backends with julc as the default, and the
  `ISSUER_CONTROLLED` label) and resolves design questions (a) to (j):
  - (a) The continuation stays on PLB, with a 29-id cap, and D10 reads it at the full smart-wallet
    address.
  - (b) A withdraw-zero handler enumerates the vault inputs, with no lead or `Follow`, rules
    V14–V17, and a shard binding variant.
  - (c) Origin-bound deposits with an origin-keyed `DepositId`.
  - (d) Payouts go to key-owned smart wallets.
  - (e) Min-ADA and fees, re-measured.
  - (f) Code reuse and per-asset measured caps.
  - (g) Ledger-order assembly by Yano X.
  - (h) The escape hatch under issuer control.
  - (i) The pinned v0.0.1 deployments and freeze-and-seize.
  - (j) Exact CIP-113 body checks.
