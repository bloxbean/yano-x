//// ADR-061 custody script `h` of a CIP-113 asset's vault (D2, D5), Aiken
//// backend.
////
//// The same validator as the julc `ProgrammableVaultValidator`
//// (`src/main/java/org/yanoproject/x/assets/onchain/ProgrammableVaultValidator.java`),
//// rule for rule; `ProgrammableVaultBackendDifferentialTest` runs both on the
//// same script contexts (I40). A deployment selects one backend with
//// `programmable-backend`.
////
//// The vault's custody is every UTxO at its smart wallet
//// `(Script(PLB), Inline(Script(h)))`: PLB spends each of them and accepts a
//// script owner's consent only as that script's withdrawal, so the vault
//// rules run here, in the withdraw handler, once per transaction, over every
//// input at the vault smart wallet (V14).
////
//// Parameters, in the julc order: the native vault's (059-D4 step 5), then
//// the pinned deployment's PLB script hash and `max_inline_datum_bytes`, then
//// the exit delegate's hash `e` (derived first, D2).
////
//// Handlers: `withdraw` runs the actions; `publish` accepts only
//// `RegisterCredential`; `spend` and every other purpose fail.
////
//// Actions (059's `vault_redeemer`, constructors 0 to 8): `Settle`, `Exit`,
//// `SweepExit`, `AcceptCosigned` and `AcceptProved` run here.
//// `RefundAbsent`, `RefundUncredited`, `BalanceExit` and `Consolidate` are
//// EXIT-only and delegated: this script requires the exit-mode thread in
//// `EXIT` and `e`'s withdrawal with the redeemer `DelegatedActionV1(h,
//// action)`, and `e` runs their rules. `Follow` (9) and anything else fail.
////
//// The redeemer, datums, proofs, values and addresses are read from their
//// Plutus Data positionally, as the julc script reads them: a constructor tag
//// is checked only where the julc script checks it, and extra fields are
//// ignored, so both backends accept the same encodings.

use aiken/builtin
use cardano/address.{Credential, Script}
use cardano/certificate.{Certificate, RegisterCredential}
use cardano/transaction.{Input, Output, Transaction}
use yano_x/anchor
use yano_x/codec
use yano_x/gateway_tx.{asset_of, inline_datum, lovelace_of}
use yano_x/programmable as lib
use yano_x/state_proof

validator programmable_vault(
  gateway_domain_id: ByteArray,
  asset_policy_id: ByteArray,
  asset_name: ByteArray,
  vault_ordinal: Int,
  address_network_id: Int,
  anchor_thread_policy_id: ByteArray,
  anchor_thread_asset_name: ByteArray,
  anchor_script_hash: ByteArray,
  chain_genesis_id: ByteArray,
  application_id: ByteArray,
  commitment_profile_id: ByteArray,
  format_fingerprint: ByteArray,
  shard_thread_policy_id: ByteArray,
  exit_mode_thread_policy_id: ByteArray,
  exit_mode_script_hash: ByteArray,
  claim_key_prefix: ByteArray,
  deposit_key_prefix: ByteArray,
  _exit_key_prefix: ByteArray,
  domain_status_key: ByteArray,
  max_settle_batch: Int,
  max_exit_batch: Int,
  continuation_floor: Int,
  exit_delay_floor_ms: Int,
  exit_delay_ceiling_ms: Int,
  max_pause_ms: Int,
  plb_hash: ByteArray,
  max_inline_datum_bytes: Int,
  exit_delegate_hash: ByteArray,
) {
  withdraw(redeemer: Data, account: Credential, self: Transaction) {
    when account is {
      Script(own_hash) -> {
        let vault = lib.smart_wallet(plb_hash, own_hash)
        let vaults = lib.vault_inputs(self.inputs, vault)
        let mode =
          gateway_tx.exit_mode_datum(
            self.reference_inputs,
            exit_mode_thread_policy_id,
            exit_mode_script_hash,
          )
        let mode_tag = builtin.unconstr_index(mode)
        let tag = builtin.unconstr_index(redeemer)
        if or {
          builtin.null_list(vaults),
          mode_tag > 1,
          !lib.no_certificates_or_mint(self),
        } {
          False
        } else if tag >= 5 {
          // EXIT-only actions: e runs their rules, under a redeemer that
          // mirrors this one (D2, I46).
          and {
            tag <= 8,
            mode_tag == 1,
            builtin.equals_data(
              lib.reward_redeemer(self.redeemers, exit_delegate_hash),
              lib.delegated_action(own_hash, redeemer),
            ),
          }
        } else {
          let normal = mode_tag == 0
          let vault_id =
            codec.vault_id(
              gateway_domain_id,
              asset_policy_id,
              asset_name,
              vault_ordinal,
            )
          let fields = builtin.unconstr_fields(redeemer)
          // The pinned anchor, read as a v2 datum (V1), where julc reads it.
          let anchor_state =
            fn() {
              when
                anchor.accepted_anchor_state_in(
                  self.reference_inputs,
                  anchor_thread_policy_id,
                  anchor_thread_asset_name,
                  anchor_script_hash,
                  chain_genesis_id,
                  application_id,
                  commitment_profile_id,
                  format_fingerprint,
                )
              is {
                Some(state) ->
                  if state.version == 2 {
                    Some(state)
                  } else {
                    None
                  }
                None -> None
              }
            }
          // Payouts, continuation and inserts (V4-V8, V10, V15, V16).
          let payouts =
            fn(claims: List<Data>, shard_index: Int) {
              let outputs = self.outputs
              let count = lib.length(claims)
              and {
                count < outputs_length(outputs),
                claims_paid(
                  claims,
                  outputs,
                  shard_index,
                  plb_hash,
                  asset_policy_id,
                  asset_name,
                  address_network_id,
                ),
                lib.all_accepted(vaults, asset_policy_id, asset_name),
                lib.foreign_inputs(self.inputs, vault, shard_thread_policy_id) == 1,
                builtin.equals_bytearray(
                  lib.shard_inserts(self, shard_thread_policy_id, shard_index),
                  joined_ids(claims),
                ),
                lib.continuation_valid(
                  outputs,
                  count,
                  vaults,
                  vault,
                  own_hash,
                  exit_delegate_hash,
                  sum_field(claims, 2),
                  sum_field(claims, 3),
                  ids_data(claims),
                  count,
                  lib.max_ids(max_inline_datum_bytes),
                  continuation_floor,
                  asset_policy_id,
                  asset_name,
                ),
              }
            }
          // The proved `DomainStatusV1` within the pinned delay bounds, or
          // status -1.
          let proved_status =
            fn(proof: Data, root: ByteArray) {
              let pf = builtin.unconstr_fields(proof)
              let Pair(status, exit_delay_ms) =
                codec.domain_status(
                  builtin.un_b_data(builtin.head_list(builtin.tail_list(pf))),
                )
              if and {
                builtin.equals_bytearray(
                  builtin.un_b_data(builtin.head_list(pf)),
                  domain_status_key,
                ),
                status >= 0,
                exit_delay_ms >= exit_delay_floor_ms,
                exit_delay_ms <= exit_delay_ceiling_ms,
                exit_delay_floor_ms >= 21600000,
                exit_delay_ceiling_ms <= 2592000000,
                max_pause_ms >= 259200000,
                max_pause_ms <= 2592000000,
                state_proof.verify_inclusion(proof, root),
              } {
                Pair(status, exit_delay_ms)
              } else {
                Pair(-1, 0)
              }
            }
          // Accept (059-D8f, D6): every vault input is deposit-shaped and
          // holds only the vault asset and lovelace, and they merge into one
          // exact `VaultContinuationV1([])`.
          let accept_shape =
            fn() {
              and {
                lib.all_deposit_shaped(
                  vaults,
                  plb_hash,
                  address_network_id,
                  asset_policy_id,
                  asset_name,
                ),
                lib.merge_valid(
                  self,
                  vaults,
                  vault,
                  own_hash,
                  exit_delegate_hash,
                  shard_thread_policy_id,
                  asset_policy_id,
                  asset_name,
                ),
              }
            }
          if tag == 0 {
            // Settle (V9).
            normal && {
              let claims = builtin.un_list_data(builtin.head_list(fields))
              let count = lib.length(claims)
              let state = anchor_state()
              and {
                count >= 1,
                count <= max_settle_batch,
                count <= 64,
                when state is {
                  Some(a) ->
                    anchor.signed_by_threshold(self.extra_signatories, a)
                  None -> False
                },
                user_withdrawals_only(claims),
                payouts(
                  claims,
                  builtin.un_i_data(
                    builtin.head_list(builtin.tail_list(fields)),
                  ),
                ),
              }
            }
          } else if tag == 1 {
            // Exit (V1-V3).
            let exits = builtin.un_list_data(builtin.head_list(fields))
            let count = lib.length(exits)
            let claims = claims_of(exits)
            if or {
              count < 1,
              count > max_exit_batch,
              count > 64,
              !payouts(claims, builtin.un_i_data(field(fields, 2))),
            } {
              False
            } else if mode_tag == 1 {
              claims_proved(
                exits,
                vault_id,
                gateway_tx.frozen_root(mode),
                -1,
                0,
                0,
                max_pause_ms,
                claim_key_prefix,
                asset_policy_id,
                asset_name,
              )
            } else {
              when anchor_state() is {
                None -> False
                Some(state) -> {
                  let root = state.state_root
                  let Pair(status, exit_delay_ms) =
                    proved_status(field(fields, 1), root)
                  status >= 0 && claims_proved(
                    exits,
                    vault_id,
                    root,
                    status,
                    exit_delay_ms,
                    gateway_tx.finite_lower_bound(self.validity_range),
                    max_pause_ms,
                    claim_key_prefix,
                    asset_policy_id,
                    asset_name,
                  )
                }
              }
            }
          } else if tag == 2 {
            // SWEEP_UNATTRIBUTED (059-D8d, D5).
            normal && when anchor_state() is {
              None -> False
              Some(state) ->
                when vaults is {
                  [swept] -> {
                    let root = state.state_root
                    let value: Data = swept.output.value
                    let datum = inline_datum(swept.output)
                    let deposit_shaped =
                      lib.deposit_shaped(datum, plb_hash, address_network_id)
                    let claim_id = builtin.un_b_data(builtin.head_list(fields))
                    let recipient = builtin.un_b_data(field(fields, 1))
                    let assets = builtin.un_list_data(field(fields, 4))
                    let natives =
                      lib.length(assets) - if deposit_shaped {
                        1
                      } else {
                        0
                      }
                    let input_lovelace = lovelace_of(value)
                    let vault_lovelace =
                      if deposit_shaped {
                        committed_lovelace(assets)
                      } else {
                        input_lovelace
                      }
                    let withdrawal_key =
                      if deposit_shaped {
                        lib.deposit_id(vault_id, datum)
                      } else {
                        codec.out_ref_cbor(
                          swept.output_reference.transaction_id,
                          swept.output_reference.output_index,
                        )
                      }
                    let asset_value =
                      builtin.append_bytearray(
                        codec.head(4, natives),
                        swept_assets(assets, natives, value),
                      )
                    let Pair(status, exit_delay_ms) =
                      proved_status(field(fields, 6), root)
                    let proof = field(fields, 5)
                    expect [payout, ..] = self.outputs
                    let payout_address: Data = payout.address
                    let payout_value: Data = payout.value
                    let reserved_at_ms = builtin.un_i_data(field(fields, 3))
                    and {
                      builtin.length_of_bytearray(claim_id) == 32,
                      builtin.index_bytearray(claim_id, 31) % 16 == builtin.un_i_data(
                        field(fields, 8),
                      ),
                      status == 0,
                      reserved_at_ms + exit_delay_ms <= gateway_tx.finite_lower_bound(
                        self.validity_range,
                      ),
                      !lib.accepted(datum, value, asset_policy_id, asset_name),
                      natives >= 0,
                      natives + 1 == value_entries(builtin.un_map_data(value)),
                      vault_lovelace >= 0,
                      vault_lovelace <= input_lovelace,
                      builtin.equals_bytearray(
                        claim_id,
                        codec.claim_id(
                          gateway_domain_id,
                          vault_id,
                          1,
                          withdrawal_key,
                          recipient,
                          asset_value,
                          vault_lovelace,
                          builtin.un_i_data(field(fields, 7)),
                        ),
                      ),
                      builtin.equals_bytearray(
                        builtin.un_b_data(
                          builtin.head_list(builtin.unconstr_fields(proof)),
                        ),
                        builtin.append_bytearray(claim_key_prefix, claim_id),
                      ),
                      builtin.equals_bytearray(
                        builtin.un_b_data(
                          builtin.head_list(
                            builtin.tail_list(builtin.unconstr_fields(proof)),
                          ),
                        ),
                        codec.claim_leaf(
                          claim_id,
                          vault_id,
                          1,
                          asset_value,
                          vault_lovelace,
                          recipient,
                          builtin.un_i_data(field(fields, 2)),
                          reserved_at_ms,
                        ),
                      ),
                      state_proof.verify_inclusion(proof, root),
                      lovelace_of(payout_value) >= input_lovelace,
                      builtin.equals_data(
                        builtin.map_data(
                          builtin.tail_list(builtin.un_map_data(payout_value)),
                        ),
                        builtin.map_data(
                          builtin.tail_list(builtin.un_map_data(value)),
                        ),
                      ),
                      lib.payable(payout_address, plb_hash, natives > 0),
                      builtin.equals_bytearray(
                        codec.address_bytes(payout_address, address_network_id),
                        recipient,
                      ),
                      builtin.equals_data(
                        inline_datum(payout),
                        builtin.constr_data(4, [builtin.b_data(claim_id)]),
                      ),
                      when payout.reference_script is {
                        None -> True
                        _ -> False
                      },
                      lib.foreign_inputs(
                        self.inputs,
                        vault,
                        shard_thread_policy_id,
                      ) == 1,
                      lib.vault_outputs(
                        self.outputs,
                        vault,
                        own_hash,
                        exit_delegate_hash,
                        0,
                      ),
                      builtin.equals_bytearray(
                        lib.shard_inserts(
                          self,
                          shard_thread_policy_id,
                          builtin.un_i_data(field(fields, 8)),
                        ),
                        claim_id,
                      ),
                    }
                  }
                  _ -> False
                }
            }
          } else if tag == 3 {
            // AcceptCosigned.
            and {
              normal,
              accept_shape(),
              when anchor_state() is {
                Some(a) -> anchor.signed_by_threshold(self.extra_signatories, a)
                None -> False
              },
            }
          } else {
            // AcceptProved: proof i shows `deposit/<DepositId of vault input
            // i's origin> = CREDITED` under `frozenRoot` in EXIT, else the
            // anchor's root.
            accept_shape() && deposits_proved(
              builtin.un_list_data(builtin.head_list(fields)),
              vaults,
              vault_id,
              if mode_tag == 1 {
                gateway_tx.frozen_root(mode)
              } else {
                when anchor_state() is {
                  Some(state) -> state.state_root
                  None -> #""
                }
              },
              deposit_key_prefix,
            )
          }
        }
      }
      _ -> False
    }
  }

  publish(_redeemer: Data, certificate: Certificate, _self: Transaction) {
    // Registering this credential is the only certificate it witnesses (D2).
    when certificate is {
      RegisterCredential { .. } -> True
      _ -> False
    }
  }

  else(_) {
    fail
  }
}

fn outputs_length(outputs: List<Output>) -> Int {
  when outputs is {
    [] -> 0
    [_, ..rest] -> 1 + outputs_length(rest)
  }
}

/// Field `index` of a constructor's field list.
fn field(fields: List<Data>, index: Int) -> Data {
  if index == 0 {
    builtin.head_list(fields)
  } else {
    field(builtin.tail_list(fields), index - 1)
  }
}

// ------------------------------------------------------------------
// Claims: `ClaimLeafV1` fields `[claim_id, kind, quantity, vault_lovelace,
// recipient, reserved_height, reserved_at_ms]`, `recipient` raw Shelley
// address bytes.

fn user_withdrawals_only(claims: List<Data>) -> Bool {
  when claims is {
    [] -> True
    [claim, ..rest] ->
      builtin.un_i_data(field(builtin.unconstr_fields(claim), 1)) == 0 && user_withdrawals_only(
        rest,
      )
  }
}

fn claims_of(exits: List<Data>) -> List<Data> {
  when exits is {
    [] -> []
    [exit, ..rest] ->
      [builtin.head_list(builtin.unconstr_fields(exit)), ..claims_of(rest)]
  }
}

/// V5, positionally: output `i` pays `claim[i]`.
fn claims_paid(
  claims: List<Data>,
  outputs: List<Output>,
  shard_index: Int,
  plb_hash: ByteArray,
  policy: ByteArray,
  name: ByteArray,
  network_id: Int,
) -> Bool {
  when claims is {
    [] -> True
    [claim, ..rest] -> {
      let cf = builtin.unconstr_fields(claim)
      expect [output, ..others] = outputs
      and {
        claim_shape_valid(cf, shard_index),
        lib.pays_to(
          output,
          builtin.un_b_data(field(cf, 4)),
          builtin.un_i_data(field(cf, 2)),
          builtin.un_i_data(field(cf, 3)),
          plb_hash,
          policy,
          name,
          network_id,
        ),
        claims_paid(
          rest,
          others,
          shard_index,
          plb_hash,
          policy,
          name,
          network_id,
        ),
      }
    }
  }
}

fn claim_shape_valid(cf: List<Data>, shard_index: Int) -> Bool {
  let recipient_length =
    builtin.length_of_bytearray(builtin.un_b_data(field(cf, 4)))
  let claim_id = builtin.un_b_data(builtin.head_list(cf))
  let quantity = builtin.un_i_data(field(cf, 2))
  let lovelace = builtin.un_i_data(field(cf, 3))
  and {
    builtin.length_of_bytearray(claim_id) == 32,
    builtin.index_bytearray(claim_id, 31) % 16 == shard_index,
    builtin.un_i_data(field(cf, 1)) >= 0,
    quantity >= 0,
    lovelace >= 0,
    quantity + lovelace > 0,
    recipient_length >= 1,
    recipient_length <= 128,
    builtin.un_i_data(field(cf, 5)) >= 0,
    builtin.un_i_data(field(cf, 6)) >= 0,
  }
}

fn sum_field(claims: List<Data>, index: Int) -> Int {
  when claims is {
    [] -> 0
    [claim, ..rest] ->
      builtin.un_i_data(field(builtin.unconstr_fields(claim), index)) + sum_field(
        rest,
        index,
      )
  }
}

fn joined_ids(claims: List<Data>) -> ByteArray {
  when claims is {
    [] -> #""
    [claim, ..rest] ->
      builtin.append_bytearray(
        builtin.un_b_data(builtin.head_list(builtin.unconstr_fields(claim))),
        joined_ids(rest),
      )
  }
}

fn ids_data(claims: List<Data>) -> List<Data> {
  when claims is {
    [] -> []
    [claim, ..rest] ->
      [
        builtin.b_data(
          builtin.un_b_data(builtin.head_list(builtin.unconstr_fields(claim))),
        ),
        ..ids_data(rest)
      ]
  }
}

/// Each claim's script-computed leaf is proved at its script-computed key
/// (V2). `status < 0` means exit mode: user claims only, no delay and no
/// status. Otherwise V3: maturity, and `ACTIVE` or (user claims only) past the
/// maximum claim age; the only governed kind here is `SWEEP_OPERATIONAL`.
fn claims_proved(
  exits: List<Data>,
  vault_id: ByteArray,
  root: ByteArray,
  status: Int,
  exit_delay_ms: Int,
  lower_bound: Int,
  max_pause_ms: Int,
  claim_key_prefix: ByteArray,
  policy: ByteArray,
  name: ByteArray,
) -> Bool {
  when exits is {
    [] -> True
    [exit, ..rest] -> {
      let ef = builtin.unconstr_fields(exit)
      let cf = builtin.unconstr_fields(builtin.head_list(ef))
      let kind = builtin.un_i_data(field(cf, 1))
      let matured = builtin.un_i_data(field(cf, 6)) + exit_delay_ms
      let timing =
        if status < 0 {
          kind == 0
        } else {
          and {
            kind == 0 || kind == 2,
            matured <= lower_bound,
            or {
              status == 0,
              kind == 0 && matured + max_pause_ms <= lower_bound,
            },
          }
        }
      and {
        timing,
        leaf_proved(ef, cf, vault_id, root, claim_key_prefix, policy, name),
        claims_proved(
          rest,
          vault_id,
          root,
          status,
          exit_delay_ms,
          lower_bound,
          max_pause_ms,
          claim_key_prefix,
          policy,
          name,
        ),
      }
    }
  }
}

fn leaf_proved(
  ef: List<Data>,
  cf: List<Data>,
  vault_id: ByteArray,
  root: ByteArray,
  claim_key_prefix: ByteArray,
  policy: ByteArray,
  name: ByteArray,
) -> Bool {
  let proof = builtin.head_list(builtin.tail_list(ef))
  let pf = builtin.unconstr_fields(proof)
  let claim_id = builtin.un_b_data(builtin.head_list(cf))
  and {
    builtin.equals_bytearray(
      builtin.un_b_data(builtin.head_list(pf)),
      builtin.append_bytearray(claim_key_prefix, claim_id),
    ),
    builtin.equals_bytearray(
      builtin.un_b_data(builtin.head_list(builtin.tail_list(pf))),
      codec.claim_leaf(
        claim_id,
        vault_id,
        builtin.un_i_data(field(cf, 1)),
        codec.asset_value(policy, name, builtin.un_i_data(field(cf, 2))),
        builtin.un_i_data(field(cf, 3)),
        builtin.un_b_data(field(cf, 4)),
        builtin.un_i_data(field(cf, 5)),
        builtin.un_i_data(field(cf, 6)),
      ),
    ),
    state_proof.verify_inclusion(proof, root),
  }
}

// ------------------------------------------------------------------
// SWEEP_UNATTRIBUTED

/// The quantity of the last entry, which must be `[h'', h'', lovelace]`; -1
/// otherwise.
fn committed_lovelace(assets: List<Data>) -> Int {
  when assets is {
    [] -> -1
    [asset] -> {
      let af = builtin.unconstr_fields(asset)
      if and {
        builtin.length_of_bytearray(builtin.un_b_data(builtin.head_list(af))) == 0,
        builtin.length_of_bytearray(builtin.un_b_data(field(af, 1))) == 0,
      } {
        builtin.un_i_data(field(af, 2))
      } else {
        -1
      }
    }
    [_, ..rest] -> committed_lovelace(rest)
  }
}

/// The first `count` entries of the `asset_value` array, `[policy, name,
/// quantity]` each, concatenated, when each has a 28-byte policy and a
/// positive quantity equal to the swept value's; a mismatched entry is
/// prefixed with a stray `ff`, which no canonical leaf contains.
fn swept_assets(assets: List<Data>, count: Int, value: Data) -> ByteArray {
  if count == 0 {
    #""
  } else {
    let af = builtin.unconstr_fields(builtin.head_list(assets))
    let policy = builtin.un_b_data(builtin.head_list(af))
    let name = builtin.un_b_data(field(af, 1))
    let quantity = builtin.un_i_data(field(af, 2))
    let entry =
      builtin.append_bytearray(
        builtin.append_bytearray(
          builtin.cons_bytearray(131, codec.bytes(policy)),
          codec.bytes(name),
        ),
        codec.uint(quantity),
      )
    let exact = and {
        quantity > 0,
        builtin.length_of_bytearray(policy) == 28,
        asset_of(value, policy, name) == quantity,
      }
    builtin.append_bytearray(
      if exact {
        entry
      } else {
        builtin.cons_bytearray(255, entry)
      },
      swept_assets(builtin.tail_list(assets), count - 1, value),
    )
  }
}

/// The number of (policy, name) entries of a value map, the lovelace entry
/// included.
fn value_entries(policies: Pairs<Data, Data>) -> Int {
  when policies is {
    [] -> 0
    [Pair(_, tokens), ..rest] ->
      pairs_length(builtin.un_map_data(tokens)) + value_entries(rest)
  }
}

fn pairs_length(pairs: Pairs<Data, Data>) -> Int {
  when pairs is {
    [] -> 0
    [_, ..rest] -> 1 + pairs_length(rest)
  }
}

// ------------------------------------------------------------------
// Accept

/// Proof `i` shows `deposit/<DepositId of vault input i's origin> = CREDITED`
/// under `root`.
fn deposits_proved(
  proofs: List<Data>,
  vaults: List<Input>,
  vault_id: ByteArray,
  root: ByteArray,
  deposit_key_prefix: ByteArray,
) -> Bool {
  when vaults is {
    [] -> builtin.null_list(proofs)
    [input, ..rest] ->
      when proofs is {
        [] -> False
        [proof, ..more] -> and {
            deposit_record_proved(
              proof,
              lib.deposit_id(vault_id, inline_datum(input.output)),
              vault_id,
              root,
              0,
              deposit_key_prefix,
            ),
            deposits_proved(more, rest, vault_id, root, deposit_key_prefix),
          }
      }
  }
}

/// The proof shows `deposit/<deposit_id>` with `status` under `root`.
pub fn deposit_record_proved(
  proof: Data,
  deposit_id: ByteArray,
  vault_id: ByteArray,
  root: ByteArray,
  status: Int,
  deposit_key_prefix: ByteArray,
) -> Bool {
  let pf = builtin.unconstr_fields(proof)
  and {
    builtin.equals_bytearray(
      builtin.un_b_data(builtin.head_list(pf)),
      builtin.append_bytearray(deposit_key_prefix, deposit_id),
    ),
    codec.deposit_status(
      builtin.un_b_data(builtin.head_list(builtin.tail_list(pf))),
      deposit_id,
      vault_id,
    ) == status,
    state_proof.verify_inclusion(proof, root),
  }
}
