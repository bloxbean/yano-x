//// ADR-061 exit delegate `e` of a CIP-113 asset's vault (D2), Aiken backend:
//// the EXIT-only actions `RefundAbsent`, `RefundUncredited`, `BalanceExit`
//// and `Consolidate`.
////
//// The same validator as the julc `ProgrammableVaultExitDelegate`
//// (`src/main/java/org/yanoproject/x/assets/onchain/ProgrammableVaultExitDelegate.java`),
//// rule for rule; `ProgrammableVaultBackendDifferentialTest` runs both on the
//// same script contexts (I40).
////
//// It is derived first, from the vault's parameters without `h`; the custody
//// script `h` takes this script's hash as a parameter and, for these actions,
//// requires this script's withdrawal with the redeemer
//// `DelegatedActionV1(h, action) = Constr 0 [h, action]`, which mirrors its
//// own. This script learns `h` from that redeemer, requires `Script(h)` among
//// the withdrawals and runs the full rules of the action over the inputs at
//// `h`'s vault smart wallet. It checks V11 (the exit-mode thread is `EXIT`)
//// itself. It only adds constraints: only `h`'s withdrawal authorizes vault
//// inputs, so running it with a foreign `h'` authorizes nothing of this
//// vault. The absence verifier (`RefundAbsent`) lives only here.
////
//// Parameters, in the julc order: the native vault's (059-D4 step 5), then
//// the pinned deployment's PLB script hash and `max_inline_datum_bytes`.
//// Handlers: `withdraw` runs the actions; `publish` accepts only
//// `RegisterCredential`; `spend` and every other purpose fail. Data is read
//// positionally, as the julc script reads it.

use aiken/builtin
use cardano/address.{Credential, Script}
use cardano/certificate.{Certificate, RegisterCredential}
use cardano/transaction.{Input, Transaction}
use yano_x/codec
use yano_x/gateway_tx.{inline_datum}
use yano_x/programmable as lib
use yano_x/state_proof

validator programmable_vault_exit(
  gateway_domain_id: ByteArray,
  asset_policy_id: ByteArray,
  asset_name: ByteArray,
  vault_ordinal: Int,
  address_network_id: Int,
  _anchor_thread_policy_id: ByteArray,
  _anchor_thread_asset_name: ByteArray,
  _anchor_script_hash: ByteArray,
  _chain_genesis_id: ByteArray,
  _application_id: ByteArray,
  _commitment_profile_id: ByteArray,
  _format_fingerprint: ByteArray,
  shard_thread_policy_id: ByteArray,
  exit_mode_thread_policy_id: ByteArray,
  exit_mode_script_hash: ByteArray,
  _claim_key_prefix: ByteArray,
  deposit_key_prefix: ByteArray,
  exit_key_prefix: ByteArray,
  _domain_status_key: ByteArray,
  _max_settle_batch: Int,
  _max_exit_batch: Int,
  continuation_floor: Int,
  _exit_delay_floor_ms: Int,
  _exit_delay_ceiling_ms: Int,
  _max_pause_ms: Int,
  plb_hash: ByteArray,
  max_inline_datum_bytes: Int,
) {
  withdraw(redeemer: Data, account: Credential, self: Transaction) {
    when account is {
      Script(own_hash) -> {
        let delegated = builtin.unconstr_fields(redeemer)
        let custody_hash = builtin.un_b_data(builtin.head_list(delegated))
        let action = builtin.head_list(builtin.tail_list(delegated))
        let vault = lib.smart_wallet(plb_hash, custody_hash)
        let vaults = lib.vault_inputs(self.inputs, vault)
        let mode =
          gateway_tx.exit_mode_datum(
            self.reference_inputs,
            exit_mode_thread_policy_id,
            exit_mode_script_hash,
          )
        let tag = builtin.unconstr_index(action)
        if or {
          builtin.unconstr_index(mode) != 1,
          tag < 5,
          tag > 8,
          builtin.null_list(vaults),
          builtin.length_of_bytearray(custody_hash) != 28,
          !lib.withdrawn(self.withdrawals, custody_hash),
          !lib.no_certificates_or_mint(self),
        } {
          False
        } else {
          let vault_id =
            codec.vault_id(
              gateway_domain_id,
              asset_policy_id,
              asset_name,
              vault_ordinal,
            )
          let frozen_root = gateway_tx.frozen_root(mode)
          let fields = builtin.unconstr_fields(action)
          if tag == 5 {
            // RefundAbsent: the deposit's origin-keyed id is absent under
            // frozenRoot.
            let proof = builtin.head_list(fields)
            and {
              refund_shape_valid(
                self,
                vault,
                custody_hash,
                own_hash,
                vaults,
                plb_hash,
                address_network_id,
                shard_thread_policy_id,
              ),
              builtin.equals_bytearray(
                builtin.un_b_data(
                  builtin.head_list(builtin.unconstr_fields(proof)),
                ),
                builtin.append_bytearray(
                  deposit_key_prefix,
                  deposit_id_of(vaults, vault_id),
                ),
              ),
              state_proof.verify_absence(proof, frozen_root),
            }
          } else if tag == 6 {
            // RefundUncredited: the deposit's UNCREDITED record under
            // frozenRoot.
            and {
              refund_shape_valid(
                self,
                vault,
                custody_hash,
                own_hash,
                vaults,
                plb_hash,
                address_network_id,
                shard_thread_policy_id,
              ),
              deposit_record_proved(
                builtin.head_list(fields),
                deposit_id_of(vaults, vault_id),
                vault_id,
                frozen_root,
                1,
                deposit_key_prefix,
              ),
            }
          } else if tag == 7 {
            // BalanceExit (059-D8g §4): one exit leaf under frozenRoot, one
            // singleton escape-id insert.
            let holder_id = builtin.un_b_data(builtin.head_list(fields))
            let holder_length = builtin.length_of_bytearray(holder_id)
            let escape_id = codec.escape_id(vault_id, holder_id)
            let proof = field(fields, 5)
            let amount = builtin.un_i_data(field(fields, 2))
            let lovelace = builtin.un_i_data(field(fields, 3))
            let holder_key_hash = builtin.un_b_data(field(fields, 1))
            let shard_index = builtin.un_i_data(field(fields, 6))
            let outputs = self.outputs
            let pf = builtin.unconstr_fields(proof)
            and {
              holder_length >= 1,
              holder_length <= 64,
              builtin.length_of_bytearray(holder_key_hash) == 28,
              amount >= 0,
              lovelace >= 0,
              amount + lovelace > 0,
              gateway_tx.signed_by(self.extra_signatories, holder_key_hash),
              builtin.equals_bytearray(
                builtin.un_b_data(builtin.head_list(pf)),
                builtin.append_bytearray(
                  builtin.append_bytearray(exit_key_prefix, vault_id),
                  holder_id,
                ),
              ),
              builtin.equals_bytearray(
                builtin.un_b_data(builtin.head_list(builtin.tail_list(pf))),
                codec.exit_leaf(holder_key_hash, vault_id, amount, lovelace),
              ),
              state_proof.verify_inclusion(proof, frozen_root),
              builtin.index_bytearray(escape_id, 31) % 16 == shard_index,
              builtin.equals_bytearray(
                lib.shard_inserts(self, shard_thread_policy_id, shard_index),
                escape_id,
              ),
              lib.all_accepted(vaults, asset_policy_id, asset_name),
              lib.foreign_inputs(self.inputs, vault, shard_thread_policy_id) == 1,
              when outputs is {
                [payout, _, ..] -> and {
                    lib.pays_to(
                      payout,
                      builtin.un_b_data(field(fields, 4)),
                      amount,
                      lovelace,
                      plb_hash,
                      asset_policy_id,
                      asset_name,
                      address_network_id,
                    ),
                    lib.continuation_valid(
                      outputs,
                      1,
                      vaults,
                      vault,
                      custody_hash,
                      own_hash,
                      amount,
                      lovelace,
                      [builtin.b_data(escape_id)],
                      1,
                      lib.max_ids(max_inline_datum_bytes),
                      continuation_floor,
                      asset_policy_id,
                      asset_name,
                    ),
                  }
                _ -> False
              },
            }
          } else {
            // Consolidate.
            and {
              !builtin.null_list(builtin.tail_list(vaults)),
              lib.all_accepted(vaults, asset_policy_id, asset_name),
              lib.merge_valid(
                self,
                vaults,
                vault,
                custody_hash,
                own_hash,
                shard_thread_policy_id,
                asset_policy_id,
                asset_name,
              ),
            }
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

/// Field `index` of a constructor's field list.
fn field(fields: List<Data>, index: Int) -> Data {
  if index == 0 {
    builtin.head_list(fields)
  } else {
    field(builtin.tail_list(fields), index - 1)
  }
}

/// The origin-keyed `DepositId` of the first vault input's datum.
fn deposit_id_of(vaults: List<Input>, vault_id: ByteArray) -> ByteArray {
  expect [input, ..] = vaults
  lib.deposit_id(vault_id, inline_datum(input.output))
}

/// Refund (D5): the one vault input is deposit-shaped (so its refund address
/// is a key-owned smart wallet), its `refund_key_hash` signs, and output 0
/// pays its exact value, foreign tokens included, to its `refund_address`;
/// no other script input and no output at the vault smart wallet or the
/// custody scripts' addresses.
fn refund_shape_valid(
  tx: Transaction,
  vault: Data,
  custody_hash: ByteArray,
  own_hash: ByteArray,
  vaults: List<Input>,
  plb_hash: ByteArray,
  network_id: Int,
  shard_policy: ByteArray,
) -> Bool {
  expect [deposit, ..others] = vaults
  let datum = inline_datum(deposit.output)
  expect [refund, ..] = tx.outputs
  let refund_value: Data = refund.value
  let deposit_value: Data = deposit.output.value
  and {
    builtin.null_list(others),
    lib.deposit_shaped(datum, plb_hash, network_id),
    lib.foreign_inputs(tx.inputs, vault, shard_policy) == 0,
    gateway_tx.signed_by(tx.extra_signatories, lib.refund_key_hash(datum)),
    builtin.equals_data(refund_value, deposit_value),
    when refund.reference_script is {
      None -> True
      _ -> False
    },
    builtin.equals_bytearray(
      codec.address_bytes(refund.address, network_id),
      lib.refund_address(datum),
    ),
    lib.vault_outputs(tx.outputs, vault, custody_hash, own_hash, 0),
  }
}

/// The proof shows `deposit/<deposit_id>` with `status` under `root`.
fn deposit_record_proved(
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
