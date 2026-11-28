# ADR-061 — Custody of CIP-113 programmable tokens in the asset gateway

- **Status:** Proposed (2026-11-28, r4). Design only; no implementation code. Three decisions were
  taken by the maintainer before drafting (§1.3); the open questions are in §13. r2 to r4 answer
  three review rounds by Fable and Codex (revision history).
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
| **The chain of scripts.** PLB runs once per input. It reads the dispatcher (`programmable_logic_global`, PLG) credential from field 0 of the protocol-params datum, and it requires that credential's withdrawal at the index `wdrl_idx`. PLG runs once per transaction and requires the delegate for its one action (`TransferAct`, `ThirdPartyAct`, `UnfrackingAct`). | `validators/programmable_logic_base.ak:42-67`; `validators/programmable_logic_global.ak:71-77` |
| **Freeze domains are per policy.** The registry is keyed by policy id, so every asset name under one policy shares one registry node: one transfer logic, one third-party logic, one freeze. | `lib/registry_node.ak` (`RegistryNode.key`); `transfer.ak:249-255` |
| **Reward balances are not the script's to refuse.** A stake credential's reward account can be credited without its consent: a stake pool may name it as its reward account, a treasury withdrawal or a returned governance deposit may pay it. The Conway ledger then requires any withdrawal from it to take the full balance. | Conway ledger rules (pool parameters, treasury withdrawals, proposal deposits; withdrawal amount must equal the balance) |
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
| **Issuer** (the minting authority and its substandard's admin) | Trusted for its own asset only, under the `ISSUER_CONTROLLED` label | Freeze the vault, so no transfer out succeeds. Seize or burn custody. Re-spend vault UTxOs without changing their contents, which changes their outrefs. Rewrite the token's transfer and third-party logic. Refuse a recipient. Its powers cover every asset name under its policy. It **cannot** move the custody of another policy's vault (I38), change ledger liabilities (I36), have a third-party action credited as a deposit (I35, I44), or confirm a payout (I42). |
| **Third-party actors** | Whoever the substandard's third-party logic admits. v1 requires that to be the issuer's admin set (D12). | As the issuer, for the actions that logic allows. |
| **CIP-113 protocol upgrade authority** | Trusted, under the label | Re-point the dispatcher and the delegates of the whole deployment. That can move any programmable token of the deployment, so it can drain every CIP-113 vault. Authority handovers are two-phase and visible on chain (spec l.584-596). |

**Changed capabilities of existing parties**

- **Anyone on L1** can create outputs at a CIP-113 vault's smart wallet, as at a native vault:
  - lovelace and unregistered assets in any shape;
  - the vault's asset only through a CIP-113 transfer, with an inline datum within the bound or no
    datum.

  Nobody but the custody script or an admitted third party can spend vault UTxOs.
- **Anyone on L1** can also credit the custody credential's reward account: a stake pool may name
  it as its reward account, and a treasury withdrawal or a returned governance deposit may pay it.
  That forces every later vault transaction to withdraw the full balance (D10 rule 4). The
  balance is not custody (D2).
- **The owner node and relayers** also read the live CIP-113 wiring: the protocol params, the
  registry node, the reward balances and the substandard's state. Like every builder input, that
  is liveness only.

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

**Budget figures.** r1 quoted an indicative cost for a one-input CIP-113 transfer. No source in the
pinned tree states that figure, so r2 drops it. C1 measures the CIP-113 core's cost in the
composition tests (§10 P2, P6).

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
- Accepted custody holds only `A` and lovelace (059-V10). Only the transfer logic of `A`'s policy
  ever runs on a spend of accepted custody (I38).
- **The freeze boundary is the policy, not the asset.** The registry is keyed by policy id (§1.1),
  so assets `P.A` and `P.B` share one transfer logic, one third-party logic and one freeze. Their
  vaults are separate custody, but one issuer action on `P` can block or seize both. An issuer's
  action on policy `P` never blocks a vault of another policy `Q` (I38).
- **Onboarding.** When a deployment needs independent freeze domains for two assets, governance
  admits only assets with distinct policy ids. The trust label (D14) lists every vault that shares
  a policy.

*Alternatives considered:*
- **One shared custody smart wallet for every CIP-113 asset.** Rejected. One frozen policy blocks
  every UTxO it shares, and a shared wallet would merge `A` and `B` custody under one freeze.

### D2. The custody scripts: withdraw validators with permanently registered credentials

**Two scripts per vault (r2, finding 12; resolves Q9).** The native vault already sits 48 bytes
under its size guard (below), and the CIP-113 custody adds rules. r2 therefore fixes the split now
instead of deciding it after measurement, because a later split would change `h` and with it the
vault address:

| Script | Actions | Why there |
|---|---|---|
| **`ProgrammableVaultValidator`** (`h`, the custody script) | `Settle`, `Exit` (both modes), `SweepExit`, `AcceptCosigned`, `AcceptProved` (both modes) | The everyday path and the proof exit. |
| **`ProgrammableVaultExitDelegate`** (`e`) | `RefundAbsent`, `RefundUncredited`, `BalanceExit`, `Consolidate` | EXIT-only actions. `RefundAbsent` is the only custody user of `verifyAbsence`, so moving it takes the absence verifier out of `h`. |

**Delegation rule.**
- For an action in `e`'s list, `h` requires `e`'s withdrawal. It reads `e`'s redeemer from
  `txInfo.redeemers` under `Withdraw(Script(e))` and requires it to equal
  `DelegatedActionV1(h, its own redeemer)`. Then `h` checks only V11 (the exit-mode thread is
  `EXIT`). `e` runs the full rules of the action over the vault inputs of `h`, and *(r3)* checks
  V11 itself as well, so it never relies on `h` for the mode.
- `e` learns `h` from that redeemer and requires `Script(h)` among the withdrawals.
- **Why this is sound.** Only `h`'s withdrawal authorizes vault inputs. `e` only adds constraints,
  so running `e` with a foreign `h'` authorizes nothing of this vault.
- **No cycle.** `e` is derived first, from the vault's parameters without `h`. `h` takes
  `e`'s hash as a parameter (D2 derivation order).
- **One action per transaction** still holds: `h` has one redeemer, and `e`'s must mirror it.

Both scripts are multi-purpose Plutus V3 scripts with these handlers:

| Handler | Behaviour |
|---|---|
| `withdraw(redeemer, Script(self))` | The script's actions (D5). It learns its own hash from its purpose. |
| `publish(cert)` | Accepts only `RegisterCredential`. Rejects deregistration, stake delegation, vote delegation and the combined register-and-delegate forms. |
| `spend` | Always fails. Nothing at either script's own payment address is custody, and V15 forbids vault transactions from creating outputs there. Whatever someone else sends there stays there. |
| every other purpose | Fails. |

**Why withdraw-zero.** It is the only consent that CIP-113 accepts from a script owner
(`owner.ak:27-38`; spec l.132). It is the same pattern the CIP-113 delegates themselves use.

**What the registration rule gives.** Each credential is registered once, at bootstrap. It can
never be deregistered or delegated.

**Reward balances (r2 finding 1; simplified in r3, maintainer direction).** Refusing delegation
does not keep the balance at 0. Others can credit the account without the script's consent (§1.1),
and the ledger then requires every withdrawal from it to take the full balance. So:
- the scripts constrain **neither the amount of a withdrawal nor where it goes**;
- the assembler withdraws the live balance (D10 rule 4), and balancing sends it to the fee or the
  change, like any other input;
- the balance is **not custody and not backing**. It is never counted in liabilities, custody or
  reconciliation.

*Why.* Such a balance exists only because someone chose to credit the account. That donor gave it
away; it was never deposited, credited or owed to any holder. A permissionless `Exit` builder who
takes it harms no holder. r2 bound it to the continuation (V18); r3 drops that rule, which also
removes the cases it created: a last deposit that could no longer be refunded or swept without a
continuation, and an unauthenticated destination for `e`'s balance.

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
not take the params policy or the registry: the custody scripts read no CIP-113 state, because the
CIP-113 scripts already do. `e` takes the same parameters; `h` also takes `e`'s hash.

**Derivation order.** 059-D4 is kept, with steps 6 and 7 changed:
6. the exit delegate `e`; then the custody script hash `h`, parameterized by `e`'s hash; then the
   vault smart wallet `base(PLB, Script(h))`;
7. the shard validator, in the binding variant of D5, parameterized by `(shardThreadPolicyId, h)`.

No cycle appears. The custody scripts name the shards only by their thread policy, as the native
vault does, and `e` never takes `h` as a parameter.

**Bootstrap.** The operator does three things:
1. registers `Script(h)` and `Script(e)` as stake credentials. Each deposit is the
   `stakeAddressDeposit` protocol parameter, 2 ADA on mainnet today. The operator pays them, and
   they are never refunded, because deregistration is refused;
2. bootstraps the 16 shard threads (059-D8c);
3. has the issuer admit the vault smart wallet as a holder where the substandard requires it (D12).

**A missing registration is a liveness fault, not a safety one.**
- The doctor checks both registrations.
- A vault transaction against an unregistered credential fails on L1.
- Anyone can register either credential, because `publish` allows it.

*Alternatives considered:*
- **A native multisig of the member keys as the owner credential.** Rejected. Its withdrawal needs
  the members' signatures, which removes the permissionless `Exit` and the escape hatch (059-D8,
  059-D8g).
- **Adding a withdraw handler to `AssetVaultValidator`.** Rejected. The applied token vault is
  15,452 bytes, 48 under its 15,500-byte guard (`capabilities/asset-gateway-onchain/BUDGETS.md:158-173`).
  Its spend handler would also be dead code for CIP-113 custody.
- **One custody script, split only if C1 measures it too large (r1).** Rejected in r2. A late split
  changes `h`, the vault address and every pin, so the structure is fixed before code.
- **A size spike now.** Not done. It needs a julc skeleton of the custody rules, which is
  implementation. The split makes the size target reachable whatever the spike would show; C1
  measures both scripts against the guard.

### D3. Two interchangeable custody backends, julc by default (decided by the maintainer)

**The backends**

| Backend | Script | Verifiers |
|---|---|---|
| julc (the default) | `ProgrammableVaultValidator` and `ProgrammableVaultExitDelegate` | The native vault's Java sources |
| Aiken | `aiken/validators/programmable_vault.ak` and `programmable_vault_exit.ak`, in the same Aiken project as `asset_shard.ak` | A Yano X Aiken port of the state-proof verifier (`MpfOnChainVerifier` inclusion and absence, with the same proof-shape rules) and of `MpfAnchorLib` |

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
- The backend determines `e` and `h` (one setting selects both), and with them the vault smart wallet, which is pinned in the registry
  entry and in every observer's consensus identity.
- A node whose pinned `vaults.N.script-hash` is not the selected backend's custody script for the
  vault's parameters, or whose pinned `vaults.N.exit-delegate-hash` is not that backend's `e`,
  fails startup.
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
| V6 value equation | Over the V14 vault inputs and the one continuation. Unchanged otherwise. Withdrawal amounts are not part of it (D2). |
| V7 output shape | **Extended by V15 and V16.** Output `k` is the only output at the vault smart wallet. It carries the inline `VaultContinuationV1` and no reference script. |
| V8 script inputs | The only script inputs are the V14 vault inputs and, for actions that insert, the shard input. **No other PLB input is allowed, whatever its owner.** **Extended by V17:** certificates and mint must be empty. |
| V9 threshold | Unchanged. |
| V10 accepted custody | Unchanged: a clean `VaultContinuationV1` holding only `A` and lovelace, or a lovelace-only `FloorTopUpV1`. |
| V11 exit-mode thread | Unchanged. |
| V12 | Unchanged. It is a member rule. |
| V13 one action | Holds by construction (above). |

**New rules**

- **V15 — the vault address appears only on the continuation** (narrowed in r2, finding 4).
  - No output has `h` or `e` as its payment credential.
  - No output other than the continuation is at the full vault smart-wallet address
    `(Script(PLB), Inline(Script(h)))`.
  - An output with another payment credential and `Script(h)` as its stake part is not custody and
    is allowed. r1 forbade it, which made a lawful lovelace-only recipient (a key payment address
    with that stake part) pass `payableRecipient` and then be unpayable. Narrowing the rule is
    simpler than a matching reservation check, and it stays sound: custody, the transfer delegate's
    owner check and the payout observer all key on the full smart-wallet address.
- **V16 — continuation cap.** The id list is at most the cap of D4. The datum is inline.
- **V17 — no certificates and no mint; withdrawals are free on chain.**
  - `certificates` and `mint` must be empty.
  - Withdrawals are **not** restricted by the script. Each further withdrawal is another script
    that must pass, and none can relax the custody's rules. A vault input is authorized only by
    `h`'s own withdrawal, and the third-party path bypasses `h` whatever else the transaction
    holds.
  - The members' body check fixes the exact withdrawal set (D13).
- *(r3)* r2's V18, which bound withdrawn reward balances to the continuation, is withdrawn (D2).

**Actions**

The redeemer encoding is 059's `vault_redeemer`, constructors 0–8. Each action runs under
`TransferAct`. The custody script does not inspect the dispatcher's action: its rules bind every
vault input and output under any action. Members require `TransferAct` (D13).

| Action | CIP-113 deltas |
|---|---|
| `Settle` | Recipients are smart wallets. Otherwise as 059. |
| `Exit` | As 059. In exit mode, against `frozenRoot`. |
| `SweepExit` | It spends one non-accepted vault input. Output 0 pays a **smart wallet**, because registered tokens may not leave PLB. It needs the transfer hook of every registered policy in that input (D10), and it fails while any of them refuses. *(r3)* **For a deposit-shaped input** (D6's total predicate) the withdrawal key is the `DepositId` of its datum origin, not the outpoint; the input's native assets must equal the committed ones and its lovelace must be at least the committed amount. Governance reserves such a sweep only for an `UNCREDITED` id, so no deposit proof is needed on chain and the redeemer is unchanged. `RECREATED` outputs whose id is absent are **not sweepable in NORMAL**: they are donor dust and leave in exit mode by `RefundAbsent` (D6). |
| `AcceptCosigned`, `AcceptProved` | Each input must be deposit-shaped (D6's total predicate) and hold only `A` and lovelace. `DepositId` is computed from the input's **datum origin**, not its outref (D6). Otherwise as 059-D8f. |
| `RefundAbsent`, `RefundUncredited` | Delegated to `e`. EXIT only. One deposit-shaped vault input. `DepositId` comes from the datum origin. The input's exact value is paid to `refundAddress`, a smart wallet, with a hook for every registered policy it holds. |
| `BalanceExit` | Delegated to `e`. EXIT only. One output pays `amount` of `A` plus at least `vaultLovelace` to a smart wallet. The continuation lists the escape id. |
| `Consolidate` | Delegated to `e`. EXIT only. Unchanged. It is a vault-to-vault transfer. |

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

**Deposit-shaped, on chain and off (r3).** An output is **deposit-shaped** exactly when its datum
is inline, has constructor 0 and four fields, and the fields decode as `ProgrammableDepositDatumV1`
(a 28-byte hash, bytes of 1–64, a 28-byte hash, and `Constr 0` of a 32-byte hash and two
naturals), **and** *(r4)* its `refund_address` is a key-owned smart wallet of the pinned deployment
(D7's check: 57 bytes, a base-address header with a script payment part and a key stake part on the
domain network, and the PLB hash as payment credential). The predicate is total: any other datum
is simply not deposit-shaped, and decoding never fails a script or throws in the observer. It runs
on decoded Plutus Data, so it holds for every encoding the ledger accepts (definite or indefinite
lists, any constructor form). `Accept`, the sweep and the refunds use this one predicate on chain,
and admission uses the same predicate off chain. A constructor-0 datum that fails it is
`MALFORMED`: not deposit-shaped on chain either, so it leaves only through the outpoint-keyed
governed `SWEEP_UNATTRIBUTED` of 059-D8d, to a valid recipient chosen by governance. A bad refund
address therefore never strands a deposit, and an exit-mode refund never fails on it.

**Creditability.** A deposit output is creditable only if all of these hold:
1. **Origin binding.** `origin_tx_id#origin_index` is one of the **regular inputs** of the
   transaction that created the output, and `output_index` is the output's own index
   (`originSpent`).
2. **Eligible creation action** (r2, finding 7). The creating transaction is an ordinary transfer
   or a direct issuance (below), never a third-party or unfracking action.
3. **Approved wiring** (r3; r4). The protocol-params UTxO that the creating transaction referenced is
   a recorded wiring version whose fields are an approved tuple (below).
4. The 059-D5 rules: deposit-shaped (which includes the refund address, above), only the vault's
   asset, a positive quantity, an active asset.

**What the origin binding is for.** Its purpose is to prevent **accidental double credit from
benign re-spends**.
- A third-party action re-creates a vault UTxO with its datum unchanged (§1.1), and the code
  accepts a re-spend that changes nothing. An issuer's routine freeze-enforcement tooling may do
  exactly that.
- With 059's rule (a deposit is any deposit-shaped output, keyed by its outpoint), every such
  re-spend of a pending deposit would be credited again, against I1.
- A re-created output's origin names an input of the **original** transaction. That input is
  already spent, so it can never be an input of the re-spending transaction.

It is not a defence against a deliberate issuer: an admitted third party can seize custody and pay
it back to the vault through an ordinary transfer in a later transaction. That is credited without
new backing, which is a seizure by another route. D14 detects it as a shortfall, and D12's
requirement that third-party logic be permissioned keeps it with the issuer.

**Why the action condition is also needed (finding 7).** An output `R` can name as its origin an
input `J` that is still unspent; `R` is then `RECREATED`. A later third-party re-spend that spends
both `J` and `R` can re-create `R` at the same output index. The copy satisfies the origin binding,
and it may hold tokens moved from other vault UTxOs. Condition 2 refuses it: third-party dispatch
never creates a deposit.

**Raw facts from the observer (r3).** The observer does not classify; it reports bounded facts that
it reads from the block alone, and admission classifies them. For each transaction that creates an
output at a CIP-113 vault, `ProgrammableDepositClaimV1` carries:
- the script credentials among the withdrawals, **always**, each with an **optional** outer
  constructor index of its reward redeemer: present when the redeemer is a constructor, absent
  otherwise (the issuance logic's redeemer, for example, is a map). Redeemers are attributable
  exactly, because each reward redeemer is indexed by its withdrawal's ledger position. At most 16
  entries; above that the list is reported as `OVERFLOW`. Admission requires an index only to
  classify the dispatcher's action;
- whether the transaction **mints a positive quantity** under the vault asset's policy;
- the transaction's **reference-input outpoints**, at most 32, or `OVERFLOW`;
- per output, the decoded origin and `originSpent`.

The observer's consensus identity pins only the vault addresses and the deployment's **params
policy**, never a dispatcher or delegate credential: those move with the wiring and live in ledger
state.

**Versioned wiring (r3; r4).** The `asset-cip113-wiring-v1` observer reports every output that
carries the params policy's one-shot `ProtocolParams` token: its **outpoint**, its **L1 position**
(slot and transaction index in the block), and the dispatcher, issuance-logic, transfer and
third-party credentials of its datum (fields 0–3), or `UNDECODABLE`. It never throws on a
malformed datum.
- **Approval is a set of field tuples, not outpoints.** The ledger keeps the deployment's set of
  approved tuples `(dispatcher, issuance logic, transfer credential, third-party credential)`, each
  with the delegate hashes (transfer, third party, unfracking) that the reviewed deployment record
  pairs with its dispatcher. Genesis seeds the set with the tuple live at registration.
- The ledger records each report as a **wiring version**, keyed by its outpoint with its fields.
  The **head** is the version at the highest L1 position. A version is approved exactly when its
  fields are in the approved set; an `UNDECODABLE` version never is.
- A change of only the upgrade authority (nomination or promotion) re-creates the params UTxO with
  identical fields, so the new version is approved **automatically**. A harmless rollover never
  stalls admission, assembly or an offline exit.
- **`ASSET_REWIRE(deployment, versionOutpoint, delegateHashes)`** is governed. It must name the
  **head**, and it adds the head's fields, with the reviewed delegate hashes, to the approved set.
  An approval that names an older version is rejected, so a stale approval can never clear a newer
  suspension.
- "Latest recorded" is used only by admission and `ASSET_REWIRE`. Builders, members and the SDK use
  the live params UTxO instead (D10 rule 6).

**Classifying against the referenced version.** Admission finds, among the reported reference
inputs, the one outpoint `X` that is a recorded wiring version; that is the params UTxO the
creating transaction used. It classifies with `X`'s recorded fields, and the deposit is creditable
only if those fields are in the approved set:

| Class | Condition | Creditable |
|---|---|---|
| `THIRD_PARTY` or `UNFRACKING` | The version's third-party or unfracking delegate is among the withdrawals, or the version's dispatcher redeemer has constructor 1 (`ThirdPartyAct`) or 2 (`UnfrackingAct`) | No |
| `TRANSFER` | The version's dispatcher, with constructor 0 (`TransferAct`), and neither of the above | Yes |
| `ISSUANCE` | No dispatcher withdrawal, the version's issuance logic among the withdrawals, a **positive mint** under the vault asset's policy, and neither of the above | Yes |
| `UNKNOWN` | Anything else: an `OVERFLOW`, no recorded version among the reference inputs, a version that is not approved, or no matching credentials | No |

**Same-block determinism (r4).**
- **Wiring before deposits.** 059's canonical order sorts the observations of a block by observer
  id after slot and block hash (`L1Observation.java:228-256`), and `asset-cip113-wiring-v1` sorts
  before `asset-vault-deposit-v1` (`c` before `v`). Admission relies on this explicitly: every
  wiring observation of a block is recorded before any deposit observation of that block is
  admitted. A deposit that references a params outpoint created earlier in the same block therefore
  finds it recorded.
- **Each deposit uses its own `X`.** A transaction can reference only a live params UTxO, so a
  deposit that references the old outpoint lands on L1 before the upgrade that spends it, and one
  that references the new outpoint lands after it. Classifying each deposit with the fields of the
  outpoint it referenced gives one result whatever the order of the deposit observations: a
  same-block wiring change never retroactively suspends an earlier deposit, and an earlier version
  never admits a later one.

**`DepositId`.** For a CIP-113 vault it is computed from the origin:

```
blake2b-256("deposit-cip113\0" ‖ CBOR [VaultId, [origin_tx_id, origin_index], output_index])
```

- It identifies the genuine output uniquely: an input is spent at most once, and the index names
  one output of that transaction. **At most one output per `DepositId` can ever have
  `originSpent`.**
- It survives re-spends. `Accept` and `Refund` compute it from the spent input's datum. So:
  - a re-created output of a credited deposit can still be accepted, because its value is still
    backing;
  - a re-created output of an uncredited deposit can still be refunded in exit mode.
- **Identity everywhere (finding 3).** For a CIP-113 vault, every component identifies a deposit by
  this origin-based id, never by its live outpoint: the members' `Accept` check (D13), the owner's
  acceptance scanner and the proved `Accept` builder (D10), unattributed accounting and sweep
  reservations (below). The deposit record keeps the original outpoint as history; the live
  outpoint is only the input that a transaction spends.
- A donor who copies a datum adds value that can be accepted as surplus, or that the donor's own
  refund key can recover in exit mode. Neither changes any liability.

Native vaults keep `AssetDepositClaimV1` byte for byte.

**Outpoint-first records (r2, finding 8; simplified in r3).** Admission (059-D6) first writes one
write-once record per output at the vault smart wallet, keyed by its **outpoint**, whatever its
shape:

| Disposition of `vault-output/<outpoint>` | When | `deposit/<DepositId>` |
|---|---|---|
| `DEPOSIT(DepositId)` | Deposit-shaped and `originSpent`: the one origin-valid attempt of its id | Written once: `CREDITED` if creditable, otherwise `UNCREDITED(reason)`, with reasons such as `INELIGIBLE_ACTION`, `UNKNOWN_ACTION`, `WIRING_UNPINNED`, `ASSET_HELD`, `MIXED_ASSETS`, `ZERO_AMOUNT` |
| `RECREATED(DepositId)` | Deposit-shaped without `originSpent` | **Never written.** A front-runner could name a victim's future origin, so a record here would block the genuine deposit. |
| `MALFORMED(reason)` | Constructor 0 that is not deposit-shaped: a 3-field 059 datum, wrong arity or types, or *(r4)* a refund address that is not a key-owned smart wallet. Swept by outpoint (059-D8d). | Not written |
| `CONTINUATION`, `TOP_UP`, `UNATTRIBUTED` | The other 059-D5 shapes | Not written |

**Suspension is a reason, not a state (r3).** A deposit that references an unapproved wiring
version is a `DEPOSIT` with `UNCREDITED(WIRING_UNPINNED)`. It is never credited later, and it
leaves like any other uncredited deposit (below).

Every output's own record is keyed by its own outpoint, only the one origin-valid attempt of a
`DepositId` writes `deposit/<DepositId>`, and the class depends only on the version that the
transaction referenced. With wiring observations applied first, the result never depends on the
order of the deposit observations within a block (I48).
A re-created or copied output never credits and never pre-empts the genuine deposit (I35, I45).

**Unattributed accounting, keyed by `DepositId` (r3).** For deposit-shaped value, the unit of
accounting is the `DepositId`, never the outpoint:
- an `UNCREDITED` id is unattributed custody. The consensus `unattributed` counter (059-D15)
  counts it **once**, at its **recorded** value, whatever outpoint its UTxO now has; re-created
  copies of the same id are counted under it, not again;
- a `CREDITED` id is backing for its liability, as in 059;
- a `RECREATED` output whose id is **absent** is reported as **stranded donor dust**. It is not
  backing, not a liability and not sweepable unattributed custody (below).
- **Only spendable outputs count on L1 (r4).** Reconciliation counts as custody (059-D15's L1 side)
  only live outputs that a vault action can spend: accepted custody, and deposit-shaped outputs
  holding only the asset and lovelace. A deposit-shaped copy that also holds a foreign token cannot
  be accepted, swept or refunded while its id is credited. It is reported in a separate
  **unavailable** amount that never counts as backing, even when its id is `CREDITED`. A shortfall
  hidden in such outputs therefore surfaces.

**How deposit-shaped value leaves (r3).**

| Deposit-shaped UTxO | NORMAL | EXIT |
|---|---|---|
| Its id is `CREDITED` | Origin-keyed `Accept` only. **Never swept.** | Proved `Accept`, then exits |
| Its id is `UNCREDITED(_)` (including `WIRING_UNPINNED` and ineligible actions) | A governed `SWEEP_UNATTRIBUTED`, reserved by `DepositId` | `RefundUncredited` (inclusion proof) |
| Its id is absent (`RECREATED` donor dust, or a deposit observed after the freeze) | **Not sweepable in v1** | `RefundAbsent` through `e` |

- **A sweep reservation is keyed by `DepositId`**, so a no-op re-spend that moves the UTxO to a new
  outpoint does not invalidate it. The ledger allows **at most one** sweep reservation per
  `DepositId`, and it commits **at most the recorded value**, so a donor copy carrying more can
  never drive `unattributed` below zero. The claim commits the id, the native assets exactly and a
  lovelace amount. The script rebuilds the withdrawal key as the `DepositId` from the spent input's
  datum, requires the input's native assets to equal the committed ones and its lovelace to be at
  least the committed lovelace, and pays them to output 0 (D5).
- **No deposit proof on chain.** `deposit/<DepositId>` is write-once and its `UNCREDITED` value
  never changes, so the ledger's check at reservation is final: governance can reserve a sweep only
  for an `UNCREDITED` id. The `SweepExit` redeemer is unchanged, and `h` keeps only the inclusion
  verifier. The absence verifier stays in `e`, for `RefundAbsent`.
- Governance normally sweeps an uncredited deposit to its own refund address.

**The builder (r3, finding G).** The domain API's unsigned deposit builder uses **Yano X's own
assembler** (D10), not CCL's transfer builder:
- *Why.* Every withdrawal must take its live reward balance (D10 rule 4), and CCL pre8 builds
  withdraw-zero amounts. One assembler for every CIP-113 transaction avoids a second path that only
  a positive balance would exercise, which would rarely be tested. The assembler builds a key-owned
  smart-wallet spend by naming the owner as a required signer, which is simpler than the custody's
  withdrawal. CCL stays the reference for issuer-side registration and issuance.
- It sets the origin to the depositor's spent PLB input that holds `A`. That input is always spent,
  so the origin is known before balancing.
- After balancing, it re-checks that the deposit output sits at the datum's `output_index` and that
  the origin input is still spent, and rebuilds otherwise.
- It references the live params UTxO from its own L1 view, authenticated by the params token, and
  refuses to build unless that UTxO's fields are an approved tuple, so a user never makes a deposit
  that would be `UNCREDITED(WIRING_UNPINNED)`.

**Mint deposits.** An issuer may mint directly into the vault with this datum (class `ISSUANCE`).
Its own inputs supply the origin.

*Alternatives considered:*
- **Action classification alone, keeping the outpoint `DepositId`.** Rejected as the only rule. A
  re-created output of a credited deposit would then be unacceptable backing, and without the
  wiring observer an unknown dispatcher would defeat the classification. r2 uses the class as a
  second condition next to the origin binding.
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
| `REQUEST_WITHDRAWAL` (059-D14) | Feasibility per the vault's custody kind, read from the registry entry. No V15 check is needed: V15 forbids only outputs at the full vault address and at `h`'s or `e`'s payment address, and neither is a payable recipient (a smart wallet keyed by `Script(h)` is not key-owned). |
| `ForcedWithdrawalV1.recipient` (059-D8g R3) | A non-smart-wallet recipient for `A` is `INFEASIBLE_RECIPIENT`. |
| `refund_address` | D6. |
| `BalanceExit` recipient | Chosen by the holder at exit time. A wrong one fails in the transfer delegate, and the holder rebuilds. |
| Sweep recipients | D5. |
| `MIGRATE` targets | With 059-M4b, only a vault on the **same PLB** (r2, finding 10): the old transfer delegate counts only outputs at its own PLB, so a migration across PLB deployments cannot pass. |

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
- **No re-targeting, even in exit mode.** The recipient is part of the immutable claim leaf, and
  `Exit` pays exactly that recipient, in either mode. A refused claim cannot be redirected. (A
  holder's `BalanceExit` names its recipient at exit time, but it covers exit leaves, not claims.)
- **A refused recipient is not a vault freeze (r2, finding 6).** The owner and relayers reuse
  059-D9's splitting: a batch that fails is halved, and a claim that fails **alone** is left out.
  When such a claim fails because the issuer's hook refuses its payout, the executor marks it
  `RECIPIENT_REFUSED` (local telemetry; its consensus status stays `RESERVED`), leaves it for
  `Exit`, retries it on later passes, and raises **no** alert. It never poisons the batch it came
  from.
- **A vault-level refusal is different.** Only a failed **vault probe** counts as `ISSUER_REFUSED`
  (D14): an off-chain evaluation of a vault-to-vault transfer (the `AcceptCosigned` shape, which
  pays nobody) against the live hook. A probe fails only when the issuer refuses the vault itself,
  whatever the recipient.
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
- reference-script fees for PLB, PLG, the transfer delegate, the transfer hooks of the policies
  present, the custody script (and `e` for EXIT-only actions) and the shard;
- execution of PLB per vault input, PLG, the transfer delegate, the hooks, the custody scripts and
  the shard.

**Reward balances.** A positive reward balance withdrawn in a vault transaction goes wherever
balancing puts it, to the fee or the change. It is not custody and is never counted (D2).

**Third-party lovelace.** A third-party action may add lovelace to vault outputs, never remove it
(`third_party.ak:218-231`). Reconciliation counts the addition as lovelace surplus.

**Frozen lovelace.** Lovelace in a vault UTxO moves only with that UTxO's tokens. While the vault
is frozen, the min-ADA credits are frozen too (D11).

**Fixed costs.** The two stake registration deposits are a fixed, non-refundable operator cost
(D2).

### D9. Script size and execution budget (question f)

**Separate scripts.** The custody scripts cannot be the native vault script (D2). They reuse the
native vault's verifier code at the source level:
- `MpfOnChainVerifier` (inclusion in `h`; inclusion and `verifyAbsence` in `e`);
- `MpfAnchorLib`;
- the `GatewayCodec` rebuilds of `ClaimLeafV1`, `ClaimId`, exit leaves and escape ids;
- the V5–V7 output walkers;
- the exit-mode thread read;
- the shard insert-list check.

**What changes against the native vault**
- **Drops:** the per-input lead and `Follow` logic, and the enterprise-address checks. In `h`, the
  four EXIT-only actions and the absence verifier move to `e` (D2).
- **Adds:** V14 enumeration, smart-wallet rebuilds, the origin-keyed `DepositId`, the total
  deposit-shaped predicate, V16 and, in `h`, the delegation check. `h` keeps only the inclusion
  verifier (D6).

**Size target.** Both scripts stay under the same 15,500-byte guard: `GatewayBudgetTest.scriptSizes`
is extended to the applied `h`, `e` and the shard variant, per backend. The split (D2) is what
makes the target reachable: the native vault that carries all of these actions sits 48 bytes under
the guard. No size spike was run for r2 (D2, alternatives); C1 measures both scripts.

**Per-transaction overhead to measure (C1)**
- **Execution:**
  - PLB per vault input;
  - PLG;
  - the transfer delegate, which checks one registry proof per policy present and scans inputs and
    outputs;
  - one transfer hook per registered policy present, which the issuer defines (freeze-and-seize
    reads its freeze list);
  - `h`, and `e` for EXIT-only actions;
  - the shard.
- **Bytes:**
  - the withdrawals (dispatcher, delegate, one per hook credential, `h`, maybe `e`) and their
    redeemers, including *(r3)* `e`'s mirrored `DelegatedActionV1`, which repeats `h`'s action
    bytes and so counts against the EXIT-only actions' size;
  - a spend redeemer per vault input;
  - the reference inputs: protocol params, one registry node per policy present, global state if
    any, the substandard's state, and the reference-script UTxOs.

r1 quoted an indicative cost for the CIP-113 core. It had no source in the pinned tree and is
dropped (§4). The composition tests measure it.

**Caps are provisional (r2, finding 12).**
- **On chain:** 29, by V16.
- **Profile defaults, provisional until C1:** `Settle` 16, `Exit` 8, `AcceptCosigned` 16 and
  `AcceptProved` 12, as for the native vault. Expect **lower `Exit` and `Accept` caps**: those
  transactions carry the largest redeemers, and every vault input adds a PLB spend redeemer and its
  script run, which the native vault's `Follow` marker avoided.
- **Per asset.** C1 measures every action for each supported asset with its substandard, at full
  transaction size with placeholder witnesses: `Settle` k, `Exit` k, `AcceptCosigned` and
  `AcceptProved` k, `BalanceExit` with 1, 4 and 8 vault inputs, `Consolidate`, `Refund` and
  `SweepExit`. The vault's profile parameters then fix the measured caps, and C1's exit criteria
  require them.

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

**Derived from the actual inputs (r2, finding 2).** The proofs, transfer hooks and state references
are never a fixed template. They are derived from the **policies present in the transaction's PLB
inputs**, by one function, `Cip113Requirements.of(inputs, liveWiring, registry)`, that the members'
check (D13) also runs:
- Each distinct non-ADA policy in the vault inputs, in ascending order, gets one registry proof:
  `TokenExists{node_idx}` with its registry node as a reference input if it is registered,
  `TokenDoesNotExist{node_idx}` with the covering node if it is not.
- Each registered policy present gets its transfer hook as a withdrawal, with its adapter's
  redeemer, plus the global state and user state that the adapter names.
- *(r3, finding F)* **Merged by credential.** Two policies whose registry nodes name the same hook
  credential share **one** withdrawal. Their adapters must produce one compatible redeemer for it
  (byte-equal); otherwise the transaction cannot be assembled. Reference outpoints are de-duplicated
  (a global state shared by two policies appears once). This is what CCL's materializer does
  (one withdrawal per credential: `Cip113TransactionMaterializer.java:811`, `invokeLogicScript`
  at `:2605`; conflicting redeemers refused: `rejectConflictingRedeemer` at `:617`).
- **Lovelace-only inputs** (floor top-ups, a lovelace-only payout from them) contribute nothing:
  such a transaction has zero proofs and no hook.
- **Accepted custody** holds only `A`, so a `Settle` or `Exit` over it needs exactly `[A]` and `A`'s
  hook. That is a consequence of V10, not a rule of the assembler.
- **Other policies** appear only on non-accepted inputs. For example, an exit-mode refund of an
  uncredited deposit that holds policies `A` and `B` needs proofs `[A, B]` (in policy order), both
  hooks and both policies' state.
- A registered policy with no supported adapter cannot be assembled by Yano X. Such a transaction
  (for example a sweep of a junk UTxO) waits for a governed decision or a third-party builder.

**Ordering rules.** Every index is computed against the ledger's final order, never against
insertion order:

1. **Inputs** are sorted by `(tx id, index)`.
   - Each vault input gets `BaseSpendRedeemer{params_idx, wdrl_idx}`.
   - The shard input gets its `InsertBatch`.
   - Spend redeemer indexes are input positions.
2. **Reference inputs** are sorted the same way.
   - `params_idx` is the position of the **live** params UTxO in the builder's own L1 view,
     authenticated by the params policy's `ProtocolParams` token (r4).
   - Each `node_idx` is the position of that policy's registry node (or covering node).
   - The anchor and the exit-mode thread are found by their tokens, not by index.
3. **Withdrawals** are ordered as the ledger orders them: script credentials before key
   credentials, bytewise within each kind. The entries are:
   - PLG, with `TransferAct`;
   - the transfer delegate, with `TransferRedeemer{proofs}` from the derivation above;
   - one transfer hook per distinct hook credential of the registered policies present, with its
     merged redeemer;
   - `Script(h)`, with the action, and `Script(e)` with `DelegatedActionV1` for EXIT-only actions.

   `wdrl_idx` is the position of PLG, computed **after every entry is present**. Reward redeemer
   indexes are positions in the same order.
4. **Withdrawal amounts (r2, finding 1).** Each entry withdraws the **live reward balance** of its
   credential, read at build time, never a hard-coded 0. Balancing sends the amount to the fee or
   the change; it is not custody (D2, r3). A balance that changes before inclusion makes the
   transaction fail on L1, and it is rebuilt. The same rule applies to every withdrawal the
   assembler sets, including the user-side deposit builder (D6).
5. **Outputs.** Payouts come at positions `0..k−1` (V5), then the continuation at `k` (V7), then the
   shard continuation, then change at the fee wallet.
6. **Live wiring, fail closed on the fields used (r2, finding 11; scoped in r3, finding E).**
   - *(r4)* The builder, the members and the SDK reference the **live** params UTxO from their own
     L1 view, authenticated by the params token, never "the latest recorded version". The
     dispatcher is its field 0; the delegate hashes come from the approved tuple with that
     dispatcher.
   - **A vault transaction uses only the dispatcher and its transfer delegate** from the wiring
     (plus each asset's hook, from the registry node). Assembly requires **only those fields** to
     equal those of an approved tuple. An authority-only rollover keeps them, so it never stalls
     anything. A change to them is protocol-level drift: assembly stops for the
     deployment, raises the D14 flag, and resumes after `ASSET_REWIRE` (D6).
   - A change to fields a vault transaction does not use, such as the issuance logic or the
     third-party delegate, does **not** block assembly. It suspends deposit admission (D6) and
     raises the flag. In particular, **the SDK never needs `ASSET_REWIRE` to build a transfer-only
     `Exit` or `BalanceExit`** while the dispatcher and transfer delegate are unchanged.
   - **The SDK offline, in exit mode (r4).** With operators gone, the SDK compares the live params
     UTxO's dispatcher and transfer delegate with the approved tuples it saved with the holder's
     proofs (D8g §7 data). If they match, it builds. If the dispatcher really moved, it builds only
     after an **explicit user override**: the holder accepts the new wiring, which the label says
     the upgrade authority controls.
   - **Registry-node drift** (a hook or `global_state_cs` that differs from the registration
     baseline) does not stop assembly: the assembler uses the live node and raises the flag. A hook
     only adds constraints, and the body fixes every value movement.
7. **The substandard adapter** is a support-library interface, one per supported substandard. From
   the draft transaction and a policy's registry node, it returns that policy's hook redeemer and
   its extra reference inputs (global state, user state).
8. **Origin identity (r2, finding 3).** The acceptance scanner and the proved `Accept` builder select
   deposit-shaped UTxOs by the origin-keyed `DepositId` of their datum, look up `deposit/<DepositId>`
   and its proof by that id, and spend whichever live outpoint carries it. A credited deposit that a
   no-op third-party action moved to a new outpoint is therefore still accepted.
9. **Execution units** are evaluated on the final body. The size is measured with the final witness
   count (059-D9).
10. **Rebuilds.** A transaction is rebuilt when any of these is spent: the anchor, the protocol
    params, a registry node, or a vault input that a third-party re-spend moved; or when a reward
    balance changed. 059-D9's input holds and pending-insert rules apply unchanged. A missing vault
    input counts as contention.
11. **Splitting.** 059-D9's halving applies, with the refused-recipient rule of D7.

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
paid in the order they land until the asset's custody runs out, so **the loss falls on the last
holders to exit**. v1 has no pro-rata allocation (Q2).

**Exit mode is terminal (059-Q17).** A vault frozen in exit mode stays locked for as long as the
issuer keeps the freeze: no operator path remains, and the domain never returns to `NORMAL`. If the
issuer lifts the freeze later, the exit-mode spends work again, against the same frozen root.

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
- **Wiring version 0** *(reworded in r3)*: the params outpoint and the wiring fields (dispatcher,
  issuance logic, transfer and third-party credentials) live at registration, with the delegate
  hashes of that dispatcher. Later versions are recorded by the wiring observer and approved only by
  `ASSET_REWIRE` (D6). The observers pin only the params policy, never the wiring.
- **A registry-node baseline:** the node's logic credentials at registration. It is informational
  and feeds the D14 drift flag only.

**Substandards in v1.**
- **Freeze-and-seize**, the CIP-113 reference stablecoin substandard, pinned at a commit chosen in C2,
  through a Yano X adapter.
- **A minimal Yano X test substandard** with the same powers, for the devnet drill if the reference
  substandard cannot be used there (Q5).

**Requirements for any substandard**
1. Its third-party logic is permissioned to a designated admin set. A permissionless one would let
   anyone perform D6's seize-and-redeposit. The minting authority can later rewrite the
   third-party logic (§1.1), so this requirement holds only at registration; a rewrite that makes
   it permissionless is registry-node drift (D14), and §13a states the consequence.
2. Its transfer logic needs no signer beyond the owner's authorization. Members cannot obtain
   issuer signatures.
3. Its adapter builds the transfer-logic redeemer and reference inputs deterministically from L1
   data.
4. Its powers (freeze, seize, burn, the admin set and who triggers each) are written into the trust
   label.

**Issuer onboarding.**
- Before deposits open, the issuer admits the vault smart wallet as a holder and as a sender
  wherever the substandard keeps allowlists. The doctor checks this with the vault probe (D7).
- Freeze domains are per policy (D1). Governance admits two assets of one policy only when one
  freeze domain is acceptable for both, and requires distinct policy ids otherwise.

### D13. Members' body checks (question j)

**Base check.** 059-D8h §1 applies to every co-signed CIP-113 vault transaction (`Settle` and
co-signed `Accept`).

**Changed steps**

| Step | For a CIP-113 vault |
|---|---|
| 1 | Resolve every input **and every reference input** against the member's own `l1UtxoView()`. Refuse on any unknown. |
| 2 | The vault inputs are exactly the inputs at the vault smart wallet. No other PLB input. Script inputs are only those and, for `Settle`, the shard input. |
| 3 | `mint` and certificates are empty, and there are no votes, proposals or treasury donation. The withdrawals are **exactly** the set below. No key-credential withdrawal is allowed. **Amounts (r2):** each equals the live reward balance in the member's own view where its node can read reward balances. A wrong amount only fails on L1. Where the amount goes is not checked: it is not custody (D2, r3). |
| 3a | The redeemers are **exactly**:<br>- one `BaseSpendRedeemer` per vault input;<br>- the shard's `InsertBatch` (`Settle`);<br>- `TransferAct`;<br>- `TransferRedeemer{proofs}`, equal to what the member's own `Cip113Requirements.of` derives from the resolved vault inputs (D10);<br>- one hook redeemer per distinct hook credential, byte-equal to what the member's own adapters build and merge (D10);<br>- the custody action.<br>Every index matches the sorted lists. A wrong index would only fail on L1, but checking it keeps the request closed. |
| 3b | The reference inputs are **exactly**:<br>- the pinned anchor and the exit-mode thread;<br>- the **live** params UTxO in the member's own L1 view, authenticated by the params policy's `ProtocolParams` token (r4);<br>- one registry node (or covering node) per policy present, authenticated by the registry node policy's token;<br>- global state, for each node that names it;<br>- the adapters' state references;<br>- reference-script UTxOs, each holding a script that the transaction runs. |
| 5 | Unchanged (`Settle`). |
| 6 | Unchanged. |
| 7 (new) | The only PLB outputs are the payouts (V5, D7) and the continuation (V7, V15, V16). Change goes to the fee wallet's non-PLB address. The only required signers are members. |
| 8 (new) | **Drift (r2, finding 11; scoped in r3).** **Protocol-level drift in the fields the transaction uses**, meaning a dispatcher or transfer delegate that no approved wiring describes: the member **refuses**, as the assembler fails closed (D10 rule 6). A change to fields the transaction does not use (issuance logic, third-party delegate) does not block signing. **Registry-node drift**, meaning a hook or `global_state_cs` that differs from the registration baseline: the member signs, because a hook only adds constraints and the body fixes every value movement, and it raises the flag (D14, Q4). |

The step-3 withdrawal set is:
- the dispatcher that the live params UTxO names, which must belong to an approved tuple (r4);
- that tuple's transfer delegate;
- the live hook credentials of the registered policies present, merged by credential (D10);
- `Script(h)`.

**Co-signed `Accept` (r2, finding 3).** It uses the same checks, plus 059-D8h step 4, with origin
identity: for each input the member computes `DepositId` from the input's datum origin, and checks
that its own `deposit/<DepositId>` record is `CREDITED` at or below the referenced anchor's height.
It never compares the input's live outpoint with the outpoint the record keeps as history.

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
   a recipient; a refused claim cannot be re-targeted, even in exit mode.
6. The escape hatch does not override the issuer.
7. After a seizure the gateway pauses only the co-signed path. Proof exits continue, so the loss
   falls on the holders who exit last.
8. Every asset under the same policy shares one freeze (D1).

The substandard's own powers (D12 item 4) are listed with the issuer's name.

**Detection.** Protocol-level drift is a consensus fact; everything else is local and extends
059-D15. For each CIP-113 vault:
- **(a) Shortfall per unit.** Reconciliation's 059 equation is unchanged, except that its L1 side
  counts only **spendable** live outputs: accepted custody, and deposit-shaped outputs holding only
  the asset and lovelace (D6, r4). Deposit-shaped copies that also hold a foreign token are reported
  as **unavailable** and never count as backing, even when their id is `CREDITED`, so a shortfall
  hidden in them surfaces. Deposit-shaped value is counted per `DepositId`. Reward balances are not
  custody and are never counted.
- **(b) Attribution.** Reconciliation lists vault UTxOs spent by a transaction without `h`'s
  withdrawal, which are third-party actions, and burns of `A`. It has L1 data, so it can resolve
  inputs. A shortfall that these explain is **attributed**.
- **(c) Freeze.** A failed **vault probe** (D7) is `ISSUER_REFUSED`, a new failure reason in
  059-D15a. A claim whose recipient alone is refused is `RECIPIENT_REFUSED` and raises no alert
  (D7).
- **(d) Drift.**
  - **Protocol level.** The `asset-cip113-wiring-v1` observer records every new wiring version
    in consensus. A deposit that references an unapproved version is `UNCREDITED(WIRING_UNPINNED)`
    (D6). Assembly and the members' check fail closed when a field they use moves (D10, D13),
    without waiting for a governed step.
  - **Registry node.** The local doctor compares the live registry node with the baseline and raises
    the flag. This includes a rewrite that makes the third-party logic permissionless, which voids
    D12 requirement 1.

**Response (r2, finding 5).**
- **Attributed events go to the asset, never to the domain.** An attributed shortfall, a freeze or
  drift of a CIP-113 vault obliges members, under the runbook, to submit a governed
  **`ASSET_HOLD(VaultId, reason)`** only. They **never** submit the domain `PAUSE` of 059-D15 for
  it. A domain `PAUSE` would brake every asset's `Exit` and, after `maxPauseMs`, start trigger (c)
  of 059-D8g for the whole domain because of one issuer.
- **Unattributed discrepancies keep 059-D15.** A discrepancy that (b) does not explain may be a
  gateway bug, so it keeps 059-D15's domain `PAUSE` and 059-D8a's brake.

`ASSET_HOLD` sets the new registry status **`ISSUER_HOLD`**:

| Activity | While `ISSUER_HOLD` |
|---|---|
| Deposits | Not credited. They are recorded `UNCREDITED(ASSET_HELD)`. |
| Transfers and voluntary withdrawals of `A` in the ledger | Refused. |
| Co-signed `Settle` for the vault | Refused by members. |
| `Accept` | Allowed. It pays nobody. |
| Forced withdrawals | Still reserved (059-D8g R3: no registry status rejects them). |
| Proof `Exit` | Unchanged: no brake, because the domain status leaf stays `ACTIVE`. |

`ASSET_RESUME` lifts the status.

**The hold stops only the co-signed path on L1.** Proof `Exit` and forced withdrawals continue, so
holders are never locked out by the operators. Under a shortfall, holders who leave by `Exit` are
paid in full while custody lasts, and **the losses fall on the last to exit**. That is stated on the
label, not prevented.

**Liabilities never change.** No code path reduces balances to match custody (059-D15). A governed
loss allocation is out of v1 (Q2).

**Consequences for 059-§13a.** These are summarized in §13a below:
- a frozen vault blocks `Exit`, `BalanceExit` and `Refund` for that asset;
- a seizure is a reserve shortfall that the ledger keeps visible and does not absorb, and whose loss
  falls on the last to exit;
- every guarantee that does not depend on the issuer still holds for that asset, for as long as the
  issuer permits transfers.

## 6. Invariants

These continue 059-§6. 059's invariants hold for CIP-113 vaults as stated there, except 059-I19,
which holds only while the issuer accepts the recipient and the vault (D7).

| ID | Invariant | Decisions |
|---|---|---|
| I34 | Every vault input spent under the vault's authority is checked by the custody scripts. Vault inputs are the inputs at `(PLB, Script(h))`. The credentials `Script(h)` and `Script(e)` authorize nothing else, and they can never be deregistered or delegated. | D2, D5 |
| I35 | A CIP-113 deposit is credited only for an output whose datum origin names one of its own transaction's regular inputs and its own output index. Each `DepositId` is credited at most once. Re-created and copied outputs never credit, and never pre-empt the genuine deposit, whatever the order within a block. | D6 |
| I36 | No action of the issuer, a third party or the upgrade authority changes ledger liabilities. Seizures and burns of custody appear as a reconciliation shortfall and lead to a governed `ISSUER_HOLD`. No code path writes balances to match custody. | D14 |
| I37 | Payouts that carry the asset go only to smart wallets of the pinned deployment with a key-hash owner. Lovelace-only payouts may also go to an ordinary 059-D7 recipient. | D7 |
| I38 | *(r2: per policy)* Accepted custody of a CIP-113 vault holds only its asset and lovelace, and one vault serves one asset. So only the transfer logic of that asset's policy runs on spends of accepted custody, and an issuer's action on one **policy** never blocks a vault of another policy. Vaults of assets under one policy share one freeze domain. | D1, D5, D12 |
| I39 | A continuation's id list never exceeds the cap derived from the pinned datum bound (29 at 1,024 bytes). So every transaction the custody script accepts also meets the **pinned** CIP-113 delegates' output bound. The bound is a delegate parameter: under re-pointed delegates the invariant does not hold, which is why assembly and signing fail closed on protocol-level drift (D10, D13). | D4 |
| I40 | The julc and Aiken backends of `h` and of `e` accept and reject exactly the same transactions, and so do the two backends of the shard binding variant. The selected backend is part of the pinned identity, and a mismatch fails startup. | D3, D5 |
| I41 | A member signs a CIP-113 vault transaction only after resolving every input and reference input, and only when it finds exactly the withdrawals, redeemers and reference inputs that D13 lists, derived from the resolved inputs (D10). | D10, D13 |
| I42 | A payout confirmation or a balance exit of a CIP-113 vault is accepted only from a shard-authenticated transaction. Outputs re-created by third-party actions never confirm a claim or redeem an exit leaf. | D4, 059-D10 |
| I43 | *(r2; reworded in r3)* Every withdrawal amount in a vault or deposit transaction equals its credential's live reward balance, so a positive balance never halts a vault. Reward balances are not custody: they are never counted in liabilities, custody or reconciliation, and no script constrains where they go. | D2, D10 |
| I44 | *(r2; r3; r4)* Only an ordinary transfer, or a direct issuance with a positive mint of the asset's policy, that referenced a params outpoint whose recorded fields are an **approved tuple**, creates a deposit credit. Third-party, unfracking and unclassifiable transactions never do. `ASSET_REWIRE` names the head version, so a stale approval never clears a newer suspension. Builders, members and the SDK check the live params UTxO, and fail closed only when a field they use (the dispatcher or transfer delegate) is in no approved tuple; an authority-only rollover never stalls them. | D6, D10, D13 |
| I45 | *(r2; r3)* Every output at the vault smart wallet gets exactly one record, keyed by its outpoint. Only the single origin-valid attempt of a `DepositId` writes `deposit/<DepositId>`, once; a `RECREATED` output never writes it. Value of a `CREDITED` id is never swept, and a sweep is reserved only for an `UNCREDITED` id, by `DepositId`. Deposit-shaped value is counted once per `DepositId`. | D5, D6 |
| I46 | *(new in r2)* Exactly one action applies to a vault transaction across `h` and `e`: `e`'s redeemer mirrors `h`'s, and `e` alone authorizes nothing. | D2 |
| I47 | *(new in r2)* A recipient refused by the issuer never marks the vault frozen and never blocks the other claims of its batch. Attributed issuer events lead to `ASSET_HOLD` only, never to the domain `PAUSE`. | D7, D14 |
| I48 | *(r3; reworded in r4)* Admission's result for a block is deterministic. Its wiring observations are recorded before its deposit observations, which 059's canonical order by observer id already guarantees. Each deposit is classified with the recorded fields of the params outpoint its own transaction referenced, against the approved set, and every record is keyed by its own outpoint or its unique origin-valid attempt, so the order of the deposit observations does not matter. | D6 |

## 7. Compatibility

**New formats.** Each is versioned, and its CDDL lives beside 059's:
- `ProgrammableDepositDatumV1`, and the CIP-113 `DepositId` derivation tag `deposit-cip113\0`;
- `ProgrammableDepositClaimV1`'s raw facts (script withdrawals with redeemer constructors, the
  positive-mint flag, reference-input outpoints, each capped);
- `Cip113WiringClaimV1`, the claim of the new `asset-cip113-wiring-v1` observer, and the ledger's
  wiring versions per deployment, keyed by params outpoint, with their approvals;
- `vault-output/<outpoint>` records with their dispositions (`DEPOSIT`, `RECREATED`, `MALFORMED`,
  `CONTINUATION`, `TOP_UP`, `UNATTRIBUTED`);
- the registry entry's `custody` field with its CIP-113 pins, the `ISSUER_HOLD` status and the
  `ASSET_HOLD`, `ASSET_RESUME` and `ASSET_REWIRE` commands;
- the `ISSUER_CONTROLLED` label;
- the on-chain ABI `asset-gateway-onchain-cip113-v1.cddl`: the custody redeemers (059's
  `vault_redeemer` without `Follow`), `DelegatedActionV1`, the datum and the shard variant;
- the observer settings: per vault `custody: native | cip113` plus the pins of D12, the exit
  delegate hash, and `programmable-backend`. They enter each observer's consensus identity.

`RECIPIENT_REFUSED` is executor telemetry, not a consensus format.

**Existing formats.** None change:
- native vaults keep their scripts, claim bytes and observer identity bytes;
- the ADR-059 shard and its hashes are unchanged;
- the EUTxO bridge is unchanged (059-I14).

**ADR-059 sections this ADR amends, additively.** ADR-059 gets a pointer revision when this ADR is
accepted:

| Section | Amendment |
|---|---|
| D5 | The custody kind per vault. |
| D6 | `DepositId`, creditability and outpoint-first records per custody kind. |
| D7 | `payableRecipient` per custody kind. |
| D8d | Sweeps of uncredited deposit-shaped value, keyed by `DepositId`; `MIGRATE` only within one PLB. |
| D9 | `RECIPIENT_REFUSED` in splitting. |
| D10 | The reward-redeemer read. |
| D14 | Statuses and `custody`. |
| D15 | Attributed events go to `ASSET_HOLD`; unattributed ones keep the domain `PAUSE`; `ISSUER_REFUSED`. |
| D18 | The second label. |
| I19 | Its scope. |

**Framework.** No addition beyond 059-§7.

**Modules.** No new module is added. Each existing module gains:

| Module | Additions |
|---|---|
| `asset-gateway-onchain` | `h`, `e` and the shard variant, each in both backends. |
| `asset-gateway-contracts` | The datum, the ids, the dispositions and `Cip113Requirements`. |
| `asset-gateway-cardano-support` | The assembler, the adapters, `payableSmartWallet` and the body-check extension. |
| `asset-gateway-cardano` | The observer settings, the wiring observer, reconciliation, the doctor and the deposit builder. |
| `ledgers/asset-payments` | The statuses, the wiring state and the feasibility rules. |

**Dependency.** `com.bloxbean.cardano:cardano-client-programmable-token:0.8.0-pre8` enters the
support library and the gateway bundle. At `9ff0ae87` the repository pins CCL 0.8.0-pre7
(`gradle/libs.versions.toml:4`), so the CCL 0.8.0-pre8 upgrade is an entry gate of C2. The bundle,
ZIP and Central size budgets are checked when the dependency is added.

## 8. Milestones

| ID | Scope | Entry gate | Exit criteria |
|---|---|---|---|
| C0 | This ADR | — | Accepted. The pending questions decided or explicitly deferred. |
| C1 | **On-chain:** `h`, `e` and the shard binding variant, each in julc and Aiken; the CIP-113 CDDL; artifacts and drift tests, as for the Aiken shard | C0; 059-M4 on-chain code | The differential suites pass (I40). The compiled-UPLC composition tests pass for every action, and every negative fails in the intended script (§10 P2, P3). The applied sizes of `h`, `e` and the shard variant are within the guard. The provisional caps are replaced by measured caps per action with the test substandard (D9). I34, I38, I39, I43, I46. |
| C2 | **Runtime:** contracts (datum, `DepositId`, claim, dispositions); observers, including the wiring observer; `DepositAdmission`; the assembler; the freeze-and-seize adapter; the body check; `payableSmartWallet`; `ISSUER_HOLD`, `ASSET_REWIRE` and the label; reconciliation and doctor extensions; the CIP-113 deposit builder | C1; the repository on CCL 0.8.0-pre8 | Observer fixtures P4 pass. Assembler vectors P5 pass. Admission is property-tested for I35, I44 and I45 under every same-block order. The body-check negatives pass (I41). I36, I37, I42, I47. |
| C3 | **Devnet drill** (§10 P7) | C2; 059-M4a for its exit-mode steps | Every step of P7 passes. Budgets are re-measured on devnet transactions. |
| C4 | **Preprod rehearsal and production gate** | C3; explicit authorization for preprod operations (AGENTS.md) | §12, plus 059-§12. Not part of this ADR's acceptance. |

## 9. Consequences

**Positive**
- CIP-113 stablecoins and other regulated tokens can use the anchored-claim vault, the co-signed
  fast path and the proof exit, with no change to accounts, intents or claims.
- Issuer actions cannot inflate or silently deflate liabilities, and they cannot forge
  confirmations.
- Freezes are isolated per policy.
- The custody script is simpler than the native vault in one respect: one redeemer per transaction,
  so there is no `Follow` marker.

**Costs**
- Three more scripts in two backends each: `h`, `e` and the shard variant. Their differential
  suites.
- The transaction assembler is Yano X code, not CCL's.
- Larger outputs and higher fees for every action. Batch caps, especially for `Exit` and `Accept`,
  are expected to be lower than the native vault's.
- A substandard adapter for each supported substandard.
- For these assets, the escape hatch and exit-mode solvency hold only at the issuer's discretion,
  and a shortfall falls on the last to exit. This is stated, not solved.
- A claim to a recipient the issuer refuses waits indefinitely in v1, and cannot be re-targeted.

## 10. Verification

Evidence must be independent of the code under test (059-§10).

**P1 — differential.** `ProgrammableVaultBackendDifferentialTest` (for `h` and `e`) and the
extended `AssetShardBackendDifferentialTest` run both backends on the same script contexts. That
covers every action, the proof shapes, systematic and random mutations and lenient encodings. Any
differing verdict fails.

**P2 — compiled-UPLC composition.** Every script of a vault transaction is evaluated in one script
context:
- the pinned PLB, PLG and transfer delegate, taken from `plutus.json` at `6b75ba32` (sha256 checked)
  and applied with the devnet deployment's parameters;
- the test substandard's transfer logic, for one and for two registered policies;
- `h`, `e` and the shard variant, compiled and applied;
- julc's VM, which already evaluates the Aiken shard.

An honest transaction of each action passes every script. This shows that Yano X's assembled
transactions satisfy the real CIP-113 scripts, not a model of them. The same runs measure the
CIP-113 core's cost.

**P3 — validator negatives.** Each rule V14–V17 has at least one negative test, and every
059-§10 V6 case is re-run against the custody scripts. They add these cases:
- a vault input the handler does not count, such as a second vault smart-wallet input left out of
  V6;
- another owner's PLB input in a vault transaction;
- a second output at the full vault address, and an output at `h`'s or `e`'s payment address;
- *(r2)* an output with a key payment part and `Script(h)` as its stake part: **allowed**;
- a continuation with 30 ids, and one with 29;
- a certificate or a mint in a vault transaction;
- *(r3)* positive withdrawal amounts on `h`, `e` and the dispatcher, sent to change: **pass**;
  a one-input `SweepExit` and a `RefundUncredited` with a positive amount: **pass**;
- *(r3; r4)* the total deposit-shaped predicate: a constructor-0 datum with 3 or 5 fields, wrong
  field types, or a refund address that is not a key-owned smart wallet of the pinned deployment is
  not deposit-shaped, in `Accept`, the sweep and both refunds, and never fails decoding;
- *(r4)* **encoding parity (positive):** the same datum with definite and with indefinite lists is
  deposit-shaped in both forms, on chain and in admission;
- *(r3)* `e` run in `NORMAL` with a correct mirror from `h`: fails on `e`'s own V11 check;
- *(r3)* a deposit-shaped `SweepExit` whose input's native assets differ from the commitment, or
  whose lovelace is below it: fails; one after a no-op re-spend: passes;
- *(r2)* an EXIT-only action without `e`, with `e` whose `DelegatedActionV1` names another `h` or
  another action, and `e` run without `h`;
- the `Follow` constructor used as an action;
- `Accept` of a re-created deposit output: passes when its origin's record is `CREDITED`;
- `Accept` of a copied datum carrying a foreign token: fails;
- `Refund` of a credited origin;
- a payout to a pointer, enterprise or script-owned smart wallet, and to a plain address for a
  token claim;
- the shard variant spent without the custody's withdrawal;
- the `publish` handlers with deregistration, delegation and register-and-delegate;
- the `spend` handlers.

**P4 — observer and admission fixtures**, for a CIP-113 vault:
- a deposit with a valid origin;
- a no-op third-party re-spend of a pending deposit in a later block, and in the same block in both
  hash orders: credited once, and `RECREATED` recorded;
- a partial seizure of a pending deposit in the same block, in both orders;
- *(r2)* finding 7's case: an output naming an unspent input `J`, then a third-party re-spend
  that spends `J` and re-creates the output at the same index: `RECREATED`, never credited;
- *(r2)* each creating-action class; an issuer mint into the vault (`ISSUANCE`); *(r3)* an
  issuance without a positive mint of the policy (`UNKNOWN`); a deposit that references an
  unapproved version (`UNCREDITED(WIRING_UNPINNED)`); withdrawal and reference-input lists over
  their caps (`UNKNOWN`);
- *(r2)* the wiring observer: an authentic protocol-params output with changed fields, an
  authority-only change (same fields, approved at once), *(r3)* an undecodable datum (unapproved,
  no exception), and a look-alike without the token;
- *(r3; r4)* a wiring change in the same block as deposits, **both orderings on L1**: a deposit that
  referenced the old outpoint (before the upgrade) is credited, and one that referenced the new
  outpoint (after it) is classified with the new fields, which requires the wiring observation to
  be recorded first. Each result holds in every order of the deposit observations;
- *(r4)* two wiring changes in one block: the head is the one at the higher transaction index;
- *(r4)* an authority-only rollover: the new version is approved automatically, and deposits,
  assembly and an offline SDK exit continue;
- an `ASSET_REWIRE` naming an older version: rejected;
- *(r3)* a sweep reservation for a `CREDITED` or an absent id: rejected; one for an `UNCREDITED`
  id, then a no-op re-spend of its UTxO: still payable; *(r4)* a second reservation for the same id,
  or one committing more than the recorded value: rejected;
- *(r4)* reconciliation: a copy of a credited datum carrying a foreign token is reported as
  unavailable, not backing, and the resulting shortfall is flagged;
- *(r4)* an issuance transaction whose issuance-logic redeemer is a map: reported with no
  constructor index, and classified `ISSUANCE`;
- a donated copy of a credited deposit's datum;
- a 3-field 059 datum;
- *(r4)* a refund address that is not a key-owned smart wallet: `MALFORMED`, not deposit-shaped
  on chain, swept by outpoint to a valid recipient;
- a payout to smart wallets;
- a look-alike continuation from a third-party transaction: ignored;
- a balance exit read from the custody's reward redeemer;
- outputs at a recipient smart wallet that are never mistaken for the vault's;
- *(r2)* all of these in every same-block order give the same records (I45).

**P5 — assembler vectors.**
- `params_idx`, `wdrl_idx`, `node_idx` and every redeemer index are computed by an independent
  Python implementation of the ledger orderings and compared with the assembler's.
- *(r2)* `Cip113Requirements.of` is compared with an independent derivation: lovelace-only inputs
  (zero proofs, no hook), accepted custody (`[A]`), and a refund input holding `A`, `B` and an
  unregistered token (`[A, B, X]` in policy order, two hooks, one `TokenDoesNotExist`).
- *(r2)* withdrawal amounts equal live balances, including positive ones.
- *(r3)* two registered policies sharing one hook credential: one withdrawal, one merged redeemer,
  de-duplicated reference outpoints; adapters that disagree: refused.
- Then P2 checks them against the real scripts.
- The vectors include a fee wallet and reference-script UTxOs whose sort positions move each index.

**P6 — budgets.** All budgets are measured, never estimated (D9). The results go into a CIP-113
section of `BUDGETS.md`.

**P7 — devnet drill** (C3), on Yaci DevKit:
1. **Deployment.** Bootstrap the fixture CIP-113 deployment from the pinned blueprint.
2. **Issuance.** Register and issue a test stablecoin, a freeze-and-seize substandard or the Yano X
   test substandard, with CCL 0.8.0-pre8. Mint to Alice's smart wallet.
3. **Vault bootstrap.** Register the `h` and `e` stake credentials, bootstrap the 16 shard threads,
   and have the issuer admit the vault where required. Start the chain with the CIP-113 vault in
   genesis.
4. **Deposit.** Alice deposits through the D6 deposit builder (Yano X's assembler). She is credited
   exactly once across a
   restart and a rescan.
5. **Pay.**
   - Alice transfers to Bob in the ledger.
   - Bob withdraws to his smart wallet by co-signed `Settle`.
   - The payout observer confirms it, and Bob's smart wallet holds the tokens.
   - `Accept` runs on its timer.
6. **Proof exit.** With the owner stopped, a third-party relayer pays a matured claim by `Exit`.
7. *(r2; r3)* **Reward balances.** Credit the reward accounts of `Script(h)`, `Script(e)` and the
   dispatcher (a devnet pool whose reward account is one of them, or a governance deposit returned
   to it). The next `Settle`, `Exit`, `Accept`, a user deposit and, in step 8, `BalanceExit` and
   `RefundUncredited` all still work: each withdraws the exact balance, which goes to change.
   Reconciliation is unchanged by it. A build that withdraws 0 fails on L1.
8. **Exit to a smart wallet.**
   - Stop every operator. After the heartbeat timeout, anyone enters exit mode.
   - Alice's `BalanceExit` lands in her smart wallet, through `e`.
   - A second `BalanceExit` fails.
9. **Issuer actions.**
   - A no-op re-spend of a pending deposit: no double credit. After it is credited, the moved
     output is accepted by its origin, by the owner's scanner and the members' co-signed check.
   - A seizure from the vault: reconciliation reports the shortfall and attributes it. Governed
     `ASSET_HOLD` follows, and the domain status stays `ACTIVE`. Liabilities are unchanged, and a
     proof `Exit` still pays.
   - A freeze of the vault: the vault probe and every `Settle` and `Exit` fail, reported
     `ISSUER_REFUSED`. After unfreezing they succeed.
   - A frozen recipient in a batch of four: the batch is halved, the other three are paid, and the
     refused claim stays `RESERVED` as `RECIPIENT_REFUSED`, with no alert. After unfreezing it is
     paid.
   - A copied datum naming an uncredited id: recorded `RECREATED`, swept by governance through its
     outpoint.
10. **Upgrade drift.**
    - Change only the issuance logic through the fixture's upgrade multisig. A deposit that
      references the new version is `UNCREDITED(WIRING_UNPINNED)`; a deposit in the same block that
      referenced the old version is credited. `Exit` and `BalanceExit` still build and pay, with no
      `ASSET_REWIRE`.
    - Re-point the dispatcher. Assembly and member signing refuse until `ASSET_REWIRE` approves the
      latest version; an approval of the previous version is rejected.
    - The uncredited deposit is swept by `DepositId` to its refund address.

The drill also confirms the withdraw-zero against the registered, undelegated custody credentials
under the devnet's protocol version.

## 11. Risks

| Risk | Mitigation |
|---|---|
| The issuer freezes, seizes or re-points | Stated label (D14). Per-policy isolation (I38). Detection and `ISSUER_HOLD`. |
| The upgrade authority of a public deployment is compromised | The label names the authority. The wiring observer suspends admission, and assembly fails closed. Only pinned deployments are supported. |
| A substandard with permissionless third-party logic is registered, or becomes one by a rewrite | D12 requirement 1; governance review at `ASSET_REGISTER`; registry-node drift flag; §13a. |
| The custody scripts exceed the size guard | The split into `h` and `e` (D2); measured in C1. |
| Lower batch caps than the native vault | Provisional caps; measured per asset (D9). |
| Someone credits the vault's reward account to churn builds | A changed balance only forces a rebuild. The balance is not custody (D2). |
| The issuer's transfer logic makes payouts expensive or big | Caps measured per asset (D9). Per-vault caps. |
| CIP-113 changes (the CIP is Proposed; the contracts are v0.0.1) | Exact pins (§4). A new contracts version on the same PLB is a new vault plus `MIGRATE`. A new PLB is a new asset (Q11). |
| Index mistakes in self-built transactions | They are self-validating on chain (a wrong index only fails). P2 and P5. |
| Users send tokens to a plain-address mental model | Smart wallets only. Client preflight and display (D7). |

## 12. Production and audit gates (in addition to 059-§12)

1. Independent review of `h` and `e` in both backends (every action and their `publish` and
   `spend` handlers) and of the shard binding variant.
2. Independent review of the assembler, the deposit admission (origin binding, action classes,
   wiring state, outpoint-first records) and the CIP-113 body-check steps.
3. Review of each supported substandard and its adapter, with its powers written into the label.
4. A CIP-113 deployment whose contracts and upgrade authority have been assessed, and whose
   assessment is shown with the label.
5. The issuer onboarding and incident runbook (`ASSET_HOLD`, `ASSET_REWIRE`) drilled on preprod.

## 13. Open questions

**Decided by the maintainer before drafting:**

| ID | Decision |
|---|---|
| — | One vault per asset; the vault is a smart wallet (D1). |
| — | julc and Aiken custody backends, julc by default, the choice pinned and differentially tested (D3). |
| — | The `ISSUER_CONTROLLED` label, with the stated consequences: a frozen vault blocks `Exit`, and a seizure is a shortfall that pauses the asset, with liabilities unchanged (D14). |

**Resolved in r2 by the reviews:**

| ID | Question | Resolution |
|---|---|---|
| Q4 | On wiring drift, should members sign or refuse? | Refuse on protocol-level drift; sign and flag on registry-node drift (finding 11; D10, D13). |
| Q9 | If the custody script exceeds the guard, split the exit-mode actions into a delegate? | The split is the v1 design (finding 12; D2). |
| Q13 | *(new)* What happens to a positive reward balance on a vault credential? | The assembler withdraws the live balance and balancing puts it in the fee or change. It is not custody and is never counted (finding 1; maintainer direction in r3, which withdrew r2's V18; I43). |
| Q14 | *(new)* Does an issuer event lead to `ASSET_HOLD` or to the domain `PAUSE`? | Attributed events lead to `ASSET_HOLD` only; unattributed discrepancies keep 059-D15 (finding 5; D14, I47). |
| Q15 | *(new)* How does uncredited deposit-shaped value leave the vault? | *(r3)* An `UNCREDITED` id: a governed sweep reserved by `DepositId`, with no on-chain deposit proof, because the record is write-once. A `RECREATED` output whose id is absent: not sweepable in NORMAL; it leaves in exit mode by `RefundAbsent` (finding 8 and r3 C; D6, I45). |

**Open (lean, pending maintainer):**

| ID | Question | Lean, pending maintainer |
|---|---|---|
| Q1 | Allow smart wallets owned by a script as recipients? | No in v1. Revisit with a concrete DeFi integration. |
| Q2 | Loss allocation after a seizure: a governed pro-rata reduction, or none? | None in v1. Liabilities stay, the shortfall is visible, and it falls on the last to exit. A loss-allocation transition needs its own ADR. |
| Q3 | Detect **registry-node** drift in consensus too, with an observer of `A`'s registry-node token outputs that sets `ISSUER_HOLD` automatically? (Protocol-level drift is a consensus fact since r2.) | Not in v1. Local detection plus a governed hold. Revisit after C3. |
| Q5 | Which substandards in v1, and who reviews them? | Freeze-and-seize at a pinned commit. A Yano X test substandard only for devnet. |
| Q6 | May lovelace-only claims from a CIP-113 vault pay ordinary addresses? | Yes (D7). |
| Q7 | Re-target or release a claim whose recipient the issuer refuses? | Not in v1, consistent with 059-Q7, and not in exit mode either. A client preflight is required. |
| Q8 | Should `ISSUER_HOLD` also stop co-signed `Settle`? | Yes (D14). Proof `Exit` stays open, as 059's brake rules require. |
| Q10 | The smart-wallet key convention shown to users | Payment key by default (CIP, CCL). The stake-key form is accepted. Both are key-hash owners. |
| Q11 | *(r2)* Moving to a new contracts version or a new CIP-113 deployment | On the **same PLB**: a new vault plus `MIGRATE` (059-M4b). A **new PLB** cannot be reached by `MIGRATE`: the old transfer delegate counts only outputs at its own PLB, and a token's policy is bound to its deployment's params policy (spec l.89), so the new deployment's token is a different asset. That needs an issuer-assisted conversion (burn and re-issue) under its own specification. |
| Q12 | Should the registry-node baseline also pin the hook's script hash for the members' check, instead of only for the flag? | No. Pinning it would freeze the vault on every legitimate issuer upgrade. The flag is enough. |
| Q16 | *(new in r3)* Should absent-id `RECREATED` donor dust become sweepable in NORMAL later, for example by an absence proof? | No in v1. It is not backing, and an absence check in `h` would undo the size split. |

## 13a. Guarantees delta (to 059-§13a)

| Threat | Guarantee for a CIP-113 asset | How |
|---|---|---|
| The issuer freezes the vault, or changes the token's logic to refuse it | **Not prevented.** The asset's custody, and the min-ADA lovelace with it, is locked, through the escape hatch too. Vaults of other assets under the same policy are locked with it. | Label (D14). Vaults of other policies are unaffected (I38). Vault probe, then `ISSUER_HOLD`. |
| The vault is frozen while the domain is in exit mode | **Not prevented.** Exit mode is terminal, so no operator path remains; the asset stays locked for as long as the issuer keeps the freeze, and exits resume against the frozen root if it is lifted. | D11 |
| The issuer seizes or burns vault custody, or re-deposits seized custody | **Not prevented.** It becomes a reserve shortfall. Liabilities are unchanged and visible. | Reconciliation attribution, governed `ISSUER_HOLD` (I36), never the domain `PAUSE` (I47). |
| Who bears a shortfall | **First come, first paid.** The hold stops only the co-signed path; proof `Exit` and forced withdrawals continue, so holders who exit first are paid in full and the loss falls on the last to exit. | D14; Q2 |
| A retroactive rule change makes the third-party logic permissionless | **Not prevented.** D12 requirement 1 is void from then on: anyone can seize, and seize-and-redeposit becomes open to all. | Registry-node drift flag, governed `ISSUER_HOLD` |
| The upgrade authority re-points the deployment | **Not prevented** on chain; contained in the gateway | Wiring versions: deposits against an unapproved version are not credited; assembly and signing fail closed only when a field they use moves, so an issuance-only change never blocks exits (I44) |
| The issuer refuses a recipient | **Not prevented.** The claim waits and cannot be re-targeted, even in exit mode. It never blocks other claims or marks the vault frozen. | D7; I47 |
| A third party re-creates vault outputs (including no-op re-spends) | **Prevented:** no double credit, no false confirmation. The origin binding exists to prevent accidental double credit from benign re-spends. | I35, I42, I44, I45 |
| Someone credits a vault credential's reward account | **Prevented** from halting the vault. The balance is not custody, and a builder may take it without harming any holder. | I43 |
| Donor dust: a `RECREATED` output whose id is absent | **Stranded in NORMAL** by design. It is not backing; it leaves in exit mode by `RefundAbsent`. | D6, Q16 |
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

- **r2** (2026-11-28): Responds to the reviews of r1 by Fable and by Codex (both CHANGES
  REQUESTED, no P0; Fable confirmed that V14 is complete and that the origin binding is sound).
  Overlapping findings are merged.
  - **F1** (both, P1): reward balances can become positive without consent. V18: the assembler
    withdraws the live balance of every credential it invokes, members check it, the scripts never
    constrain the amount, and the continuation carries the sum. I43; drill step 7. Q13 resolved.
  - **F2** (Codex P1, Fable P2): proofs, hooks and state references are derived from the actual PLB
    inputs by `Cip113Requirements.of`, in the assembler and the members' check (D10, D13).
  - **F3** (Codex P1): origin identity in the members' `Accept` check, the acceptance scanner and the
    proved builder (D6, D10, D13).
  - **F4** (Codex P1): V15 narrowed to the full vault address and the custody payment addresses.
  - **F5** (Fable P1): attributed issuer events lead to `ASSET_HOLD` only, never to the domain
    `PAUSE`. The hold stops only the co-signed path, and losses fall on the last to exit (D14, the
    label, §13a). I47; Q14 resolved.
  - **F6** (Fable P1): a refused recipient is `RECIPIENT_REFUSED`, found by 059-D9 halving, with no
    alert. Only a failed vault probe is `ISSUER_REFUSED`. No re-targeting, even in exit mode (D7).
  - **F7** (Codex P2): creditability also needs an eligible creating action (`TRANSFER` or
    `ISSUANCE`) under pinned wiring. A new `asset-cip113-wiring-v1` observer suspends admission
    when the wiring moves; `ASSET_REWIRE` resumes it. I44.
  - **F8** (Codex P2, Fable P2): outpoint-first records with dispositions; the write-once guard only
    for origin-valid attempts; a governed sweep of `RECREATED` and `SUSPENDED` outputs, refused on
    chain for `CREDITED` ids; the builder re-checks `output_index`. I45; Q15 resolved.
  - **F9** (Codex P2): freeze isolation is per policy (D1, D12, I38).
  - **F10** (Codex P2): `MIGRATE` only within one PLB; a new PLB needs an issuer-assisted
    conversion (Q11).
  - **F11** (Fable P2): fail closed on protocol-level drift in assembly and signing; sign and flag
    only on registry-node drift. I39 is scoped to the pinned delegates. Q4 resolved.
  - **F12** (Fable P2): the custody is split into `h` and an exit delegate `e` now (D2), instead of
    a spike; caps are provisional, with lower `Exit` and `Accept` caps expected (D9). I46; Q9
    resolved.
  - **F13** (Fable P3): §13a rows for a permissionless rewrite of the third-party logic, who bears a
    shortfall, and a frozen vault in terminal exit mode; the purpose of the origin binding is
    stated (D6).
  - **F14** (Fable P3): the dispatcher citation is `programmable_logic_global.ak:71-77`; the
    unsourced budget figure is dropped.
  - Remaining open questions are marked "lean, pending maintainer".
- **r3** (2026-11-28): Responds to the re-reviews of r2 by Fable (r1 findings resolved; the split,
  the classes, the outpoint-first records, `Cip113Requirements.of` and the narrowed V15 confirmed)
  and by Codex (7 of 8 earlier findings resolved). The maintainer asked to prefer simplification
  over more machinery.
  - **A** (maintainer; Codex 1 and 2, Fable P2-4): V18 is withdrawn. Reward balances on `h`, `e` or
    any CIP-113 script are not custody and not backing. The scripts constrain neither the amount nor
    its destination; the assembler withdraws the live balance and balancing sends it to fee or
    change; it is never counted. I43 reworded; drill step 7 kept.
  - **B** (Fable P1-2, Codex 3 and 4): the deposit observer reports bounded raw facts (script
    withdrawals with redeemer constructors, the positive-mint flag, reference-input outpoints), and
    pins only the params policy. The wiring observer records versions keyed by params outpoint, and
    admission classifies each deposit against the version its transaction referenced.
    `ASSET_REWIRE` must name the latest version. Undecodable wiring is unapproved and never throws.
    D12 reworded. Same-block determinism stated (I48).
  - **C** (Fable P1-1, Codex 5): every origin-valid attempt writes `deposit/<DepositId>`, including
    `UNCREDITED(WIRING_UNPINNED)`, so sweeps and `RefundUncredited` need only inclusion, and the
    `SweepExit` redeemer is unchanged. `RECREATED` outputs never write a record; absent-id ones are
    donor dust, not sweepable in NORMAL, leaving in exit mode by `RefundAbsent` (D5, D6, §13a, Q16).
    `h` keeps only the inclusion verifier.
  - **D** (Fable P2-3, Codex 6): deposit-shaped value is counted once per `DepositId`, and a sweep
    is reserved by `DepositId`, so a no-op re-spend does not invalidate it.
  - **E** (Codex 7): assembly and signing check only the wiring fields a transaction uses (the
    dispatcher and transfer delegate; hooks come from the registry node). An issuance-only change
    never blocks `Exit` or `BalanceExit`, and the SDK never needs `ASSET_REWIRE` for them.
  - **F** (Fable P3-8, Codex P2-8): hook withdrawals are merged by credential with one compatible
    redeemer, and reference outpoints de-duplicated, as CCL's materializer does.
  - **G** (Codex P2-9): user deposits always use Yano X's assembler, which withdraws live balances;
    CCL stays for issuer-side registration and issuance.
  - **H** (Fable P3s): `e` checks V11 itself; the mirrored redeemer bytes are measured; a total
    on-chain deposit-shaped predicate shared by `Accept`, the sweep and the refunds; undecodable
    wiring is unapproved; the `ISSUANCE` class requires a positive mint of the asset's policy.
- **r4** (2026-11-28): Responds to the confirmation passes on r3: Fable (APPROVED, with two P2 and
  four P3 text fixes) and Codex (6 of 9 r2 findings resolved, 3 partial, and new findings N1–N6).
  - **Wiring versions** (Fable P2-1; Codex N1, N2, partials 4 and 7): approval is a set of field
    tuples. Builders, members and the SDK reference the live params UTxO and require only the
    fields they use (the dispatcher and transfer delegate) to match an approved tuple; authority-only
    rollovers are approved automatically; the offline SDK compares against saved tuples, with an
    explicit user override if the dispatcher moved. "Latest recorded" remains only for admission and
    `ASSET_REWIRE`: wiring reports carry their L1 position, the head is chosen by position, wiring
    observations are applied before deposits (059's canonical order by observer id), and a deposit
    is classified with the recorded fields of the outpoint it referenced. I44 and I48 reworded
    (Fable P3-3); P4 tests both orderings.
  - **Issuance raw fact** (Codex N3): withdrawal credentials always reported; the redeemer
    constructor index is optional and needed only for the dispatcher.
  - **Refund address** (Codex N4, Fable P3-5): it is part of the shared on-chain deposit-shaped
    predicate; a bad one is `MALFORMED`, swept by outpoint to a valid recipient; admission uses the
    same predicate.
  - **Spendable reserves** (Codex N5): reconciliation counts only spendable live outputs; mixed
    copies are reported as unavailable and never count as backing (D6, D14(a), P4).
  - **Encoding test** (Fable P2-2, Codex N6): the indefinite-encoding negative is replaced by a
    positive definite/indefinite parity test.
  - **Drill** (Codex partial 9): step 4 uses the D6 deposit builder.
  - **Fable P3s:** one sweep reservation per `DepositId`, committing at most the recorded value; the
    materializer citations corrected (`:811` and `invokeLogicScript` `:2605` for the dedupe,
    `rejectConflictingRedeemer` `:617`).
