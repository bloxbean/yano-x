//// ADR-061 D5 shard binding variant, Aiken backend: the ADR-059 D8c nullifier
//// shard for a CIP-113 vault.
////
//// The same validator as the julc `ProgrammableShardValidator`
//// (`src/main/java/org/yanoproject/x/assets/onchain/ProgrammableShardValidator.java`),
//// rule for rule; `AssetShardBackendDifferentialTest` runs both on the same
//// transactions. It is `asset_shard` with one predicate replaced: a CIP-113
//// vault transaction spends every vault input with PLB, so instead of an
//// input whose payment credential is the vault script, the shard requires
//// `Script(vault_script_hash)` among the withdrawals, which means the custody
//// script `h` ran. The datum, redeemer, fold, proof-shape rules and
//// continuation rules are `asset_shard`'s, shared through `yano_x/shard`.
////
//// Parameters, in the ADR-061 D2 order: `shard_thread_policy_id`,
//// `vault_script_hash` (the custody script `h`).

use aiken/builtin
use cardano/address.{Credential, Script}
use cardano/transaction.{InlineDatum, Input, OutputReference, Transaction}
use yano_x/shard.{
  asset_of, contains_policy, continuation, fold, is_enterprise_script,
  lovelace_of, outputs_with_credential, own_input, payment_script_hash,
  thread_only,
}

validator programmable_shard(
  shard_thread_policy_id: ByteArray,
  vault_script_hash: ByteArray,
) {
  spend(
    datum: Option<Data>,
    redeemer: Data,
    own_ref: OutputReference,
    self: Transaction,
  ) {
    expect Some(datum) = datum
    expect Some(own) = own_input(self.inputs, own_ref, None)
    let own_hash = payment_script_hash(own.output.address)
    let fields = builtin.unconstr_fields(datum)
    let version = builtin.un_i_data(builtin.head_list(fields))
    let f1 = builtin.tail_list(fields)
    let shard_index = builtin.un_i_data(builtin.head_list(f1))
    let f2 = builtin.tail_list(f1)
    let root = builtin.un_b_data(builtin.head_list(f2))
    let inserts =
      builtin.un_list_data(builtin.head_list(builtin.unconstr_fields(redeemer)))
    let name = builtin.cons_bytearray(shard_index, #"")
    and {
      builtin.unconstr_index(datum) == 0,
      version == 1,
      builtin.null_list(builtin.tail_list(f2)),
      shard_index >= 0,
      shard_index < 16,
      builtin.length_of_bytearray(root) == 32,
      !builtin.null_list(inserts),
      asset_of(own.output.value, shard_thread_policy_id, name) == 1,
      withdrawn(self.withdrawals, vault_script_hash),
      !contains_policy(self.mint, shard_thread_policy_id),
      outputs_with_credential(self.outputs, own_hash) == 1,
      {
        let final_root = fold(inserts, root, shard_index)
        let next = continuation(self.outputs, own_hash)
        let expected: Data =
          builtin.constr_data(
            0,
            [
              builtin.i_data(1),
              builtin.i_data(shard_index),
              builtin.b_data(final_root),
            ],
          )
        and {
          builtin.length_of_bytearray(final_root) == 32,
          is_enterprise_script(next.address, own_hash),
          thread_only(next.value, shard_thread_policy_id, name),
          lovelace_of(next.value) >= lovelace_of(own.output.value),
          when next.reference_script is {
            None -> True
            _ -> False
          },
          when next.datum is {
            InlineDatum(d) -> builtin.equals_data(d, expected)
            _ -> False
          },
        }
      },
    }
  }

  else(_) {
    fail
  }
}

/// True when `Script(vault_script_hash)` is among the withdrawals, at any
/// amount.
fn withdrawn(
  withdrawals: Pairs<Credential, Int>,
  vault_script_hash: ByteArray,
) -> Bool {
  when withdrawals is {
    [] -> False
    [Pair(credential, _), ..rest] ->
      when credential is {
        Script(hash) -> builtin.equals_bytearray(hash, vault_script_hash)
        _ -> False
      } || withdrawn(rest, vault_script_hash)
  }
}
