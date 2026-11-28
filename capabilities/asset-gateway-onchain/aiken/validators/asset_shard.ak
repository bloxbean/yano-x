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
use aiken/merkle_patricia_forestry.{
  Branch, Fork, Leaf, Neighbor, Proof, ProofStep,
} as mpf
use cardano/transaction.{
  InlineDatum, Input, Output, OutputReference, Transaction,
}
use yano_x/mpf_guard

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

/// Each id is absent under the running root, which then advances to its
/// post-insert value; a wrong-shard id or a rejected proof fails the script.
fn fold(inserts: List<Data>, root: ByteArray, shard_index: Int) -> ByteArray {
  when inserts is {
    [] -> root
    [insert, ..rest] -> {
      let fields = builtin.unconstr_fields(insert)
      let id = builtin.un_b_data(builtin.head_list(fields))
      expect builtin.length_of_bytearray(id) == 32
      expect builtin.index_bytearray(id, 31) % 16 == shard_index
      let proof =
        decode_proof(
          builtin.un_list_data(builtin.head_list(builtin.tail_list(fields))),
        )
      let next = mpf_guard.insert(mpf.from_root(root), id, id, proof)
      fold(rest, mpf.root(next), shard_index)
    }
  }
}

/// A proof step: constructor 0 is a branch, 1 a fork, any other a leaf.
fn decode_proof(steps: List<Data>) -> Proof {
  when steps is {
    [] -> []
    [step, ..rest] -> [decode_step(step), ..decode_proof(rest)]
  }
}

fn decode_step(step: Data) -> ProofStep {
  let tag = builtin.unconstr_index(step)
  let fields = builtin.unconstr_fields(step)
  let skip = builtin.un_i_data(builtin.head_list(fields))
  let second = builtin.head_list(builtin.tail_list(fields))
  if tag == 0 {
    Branch { skip, neighbors: builtin.un_b_data(second) }
  } else if tag == 1 {
    let n = builtin.unconstr_fields(second)
    Fork {
      skip,
      neighbor: Neighbor {
        nibble: builtin.un_i_data(builtin.head_list(n)),
        prefix: builtin.un_b_data(builtin.head_list(builtin.tail_list(n))),
        root: builtin.un_b_data(
          builtin.head_list(builtin.tail_list(builtin.tail_list(n))),
        ),
      },
    }
  } else {
    Leaf {
      skip,
      key: builtin.un_b_data(second),
      value: builtin.un_b_data(
        builtin.head_list(builtin.tail_list(builtin.tail_list(fields))),
      ),
    }
  }
}

/// The last input whose reference is `own_ref`.
fn own_input(
  inputs: List<Input>,
  own_ref: OutputReference,
  found: Option<Input>,
) -> Option<Input> {
  when inputs is {
    [] -> found
    [input, ..rest] -> {
      let a: Data = input.output_reference
      let b: Data = own_ref
      own_input(
        rest,
        own_ref,
        if builtin.equals_data(a, b) {
          Some(input)
        } else {
          found
        },
      )
    }
  }
}

/// The script hash of an address's payment credential; empty for a key.
fn payment_script_hash(address: Data) -> ByteArray {
  let payment = builtin.head_list(builtin.unconstr_fields(address))
  if builtin.unconstr_index(payment) == 1 {
    builtin.un_b_data(builtin.head_list(builtin.unconstr_fields(payment)))
  } else {
    #""
  }
}

/// Exactly the enterprise script address of `script_hash` (no stake part).
fn is_enterprise_script(address: Data, script_hash: ByteArray) -> Bool {
  let fields = builtin.unconstr_fields(address)
  let payment = builtin.head_list(fields)
  let stake = builtin.head_list(builtin.tail_list(fields))
  and {
    builtin.unconstr_index(payment) == 1,
    builtin.equals_bytearray(
      builtin.un_b_data(builtin.head_list(builtin.unconstr_fields(payment))),
      script_hash,
    ),
    builtin.unconstr_index(stake) == 1,
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

/// The number of outputs whose payment credential is the script `script_hash`.
fn outputs_with_credential(outputs: List<Output>, script_hash: ByteArray) -> Int {
  when outputs is {
    [] -> 0
    [output, ..rest] -> {
      let count = outputs_with_credential(rest, script_hash)
      if builtin.equals_bytearray(
        payment_script_hash(output.address),
        script_hash,
      ) {
        count + 1
      } else {
        count
      }
    }
  }
}

/// The first output whose payment credential is the script `script_hash`.
fn continuation(outputs: List<Output>, script_hash: ByteArray) -> Output {
  expect [output, ..rest] = outputs
  if builtin.equals_bytearray(payment_script_hash(output.address), script_hash) {
    output
  } else {
    continuation(rest, script_hash)
  }
}

/// The first token amount of a value's first policy (its lovelace).
fn lovelace_of(value: Data) -> Int {
  let first = builtin.head_list(builtin.un_map_data(value))
  builtin.un_i_data(
    builtin.snd_pair(
      builtin.head_list(builtin.un_map_data(builtin.snd_pair(first))),
    ),
  )
}

/// The amount of `policy`/`name` in a value; 0 when absent.
fn asset_of(value: Data, policy: ByteArray, name: ByteArray) -> Int {
  policy_amount(
    builtin.un_map_data(value),
    builtin.b_data(policy),
    builtin.b_data(name),
  )
}

fn policy_amount(policies: Pairs<Data, Data>, policy: Data, name: Data) -> Int {
  when policies is {
    [] -> 0
    [Pair(key, tokens), ..rest] ->
      if builtin.equals_data(key, policy) {
        token_amount(builtin.un_map_data(tokens), name)
      } else {
        policy_amount(rest, policy, name)
      }
  }
}

fn token_amount(tokens: Pairs<Data, Data>, name: Data) -> Int {
  when tokens is {
    [] -> 0
    [Pair(key, amount), ..rest] ->
      if builtin.equals_data(key, name) {
        builtin.un_i_data(amount)
      } else {
        token_amount(rest, name)
      }
  }
}

fn contains_policy(value: Data, policy: ByteArray) -> Bool {
  has_key(builtin.un_map_data(value), builtin.b_data(policy))
}

fn has_key(pairs: Pairs<Data, Data>, key: Data) -> Bool {
  when pairs is {
    [] -> False
    [Pair(k, _), ..rest] -> builtin.equals_data(k, key) || has_key(rest, key)
  }
}

/// Lovelace plus exactly the one thread token named `name`, and nothing else.
fn thread_only(value: Data, policy: ByteArray, name: ByteArray) -> Bool {
  let rest = builtin.tail_list(builtin.un_map_data(value))
  let entry = builtin.head_list(rest)
  let tokens = builtin.un_map_data(builtin.snd_pair(entry))
  let token = builtin.head_list(tokens)
  and {
    builtin.null_list(builtin.tail_list(rest)),
    builtin.equals_bytearray(builtin.un_b_data(builtin.fst_pair(entry)), policy),
    builtin.null_list(builtin.tail_list(tokens)),
    builtin.equals_bytearray(builtin.un_b_data(builtin.fst_pair(token)), name),
    builtin.un_i_data(builtin.snd_pair(token)) == 1,
  }
}
