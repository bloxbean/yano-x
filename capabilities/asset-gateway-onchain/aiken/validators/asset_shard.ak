//// ADR-059 D8c nullifier shard, Aiken backend (r23).
////
//// The same validator as the julc `AssetShardValidator`
//// (`src/main/java/org/yanoproject/x/assets/onchain/AssetShardValidator.java`),
//// rule for rule; `AssetShardBackendDifferentialTest` runs both on the same
//// transactions. A deployment selects one backend with `shard-backend`.
////
//// Parameters, in the D4 order: `shard_thread_policy_id`, `vault_script_hash`.
//// Datum `ShardDatumV1 = Constr 0 [1, shard_index, nullifier_root]`. Redeemer
//// `InsertBatch = Constr 0 [[Insert = Constr 0 [id, proof]]]`, the proof in
//// the aiken-lang/merkle-patricia-forestry v2.1.0 format.
////
//// A spend requires: the own input holds the thread token `[shard_index]`;
//// the vault is spent in the same transaction; every id is 32 bytes, routes to
//// this shard by its last nibble and is absent under the running root, which
//// then advances to the post-insert root (`mpf_guard.insert`); exactly one
//// output has this script's payment credential, at the exact enterprise
//// address, with no reference script, holding only lovelace (at least the
//// spent shard's) and the one thread token, with
//// `ShardDatumV1(1, shard_index, final_root)`; and the mint never touches the
//// thread policy. The MPF value of an id is the id itself.
////
//// The redeemer, values and addresses are read from their Plutus Data
//// positionally, as the julc script reads them: a constructor tag is checked
//// only where the julc script checks it (the datum and the proof step kind),
//// and extra fields are ignored, so both backends accept the same encodings.

use aiken/builtin
use cardano/transaction.{InlineDatum, Input, OutputReference, Transaction}
use yano_x/shard.{
  asset_of, contains_policy, continuation, fold, is_enterprise_script,
  lovelace_of, outputs_with_credential, own_input, payment_script_hash,
  thread_only,
}

validator asset_shard(
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
      vault_spent(self.inputs, vault_script_hash),
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

fn vault_spent(inputs: List<Input>, vault_script_hash: ByteArray) -> Bool {
  when inputs is {
    [] -> False
    [input, ..rest] ->
      builtin.equals_bytearray(
        payment_script_hash(input.output.address),
        vault_script_hash,
      ) || vault_spent(rest, vault_script_hash)
  }
}
