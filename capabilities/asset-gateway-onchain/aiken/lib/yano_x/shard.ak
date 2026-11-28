//// The nullifier shard's rules shared by both Aiken shard validators: the
//// native vault's `asset_shard` (ADR-059 D8c) and the CIP-113 vault's
//// `programmable_shard` (ADR-061 D5), which differ only in how the vault
//// binds the spend. Each function is the julc shard's
//// (`AssetShardValidator`, `ProgrammableShardValidator`) rule of the same
//// name, reading Plutus Data positionally.

use aiken/builtin
use aiken/merkle_patricia_forestry.{
  Branch, Fork, Leaf, Neighbor, Proof, ProofStep,
} as mpf
use cardano/transaction.{Input, Output, OutputReference}
use yano_x/mpf_guard

/// Each id is absent under the running root, which then advances to its
/// post-insert value; a wrong-shard id or a rejected proof fails the script.
pub fn fold(inserts: List<Data>, root: ByteArray, shard_index: Int) -> ByteArray {
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
pub fn decode_proof(steps: List<Data>) -> Proof {
  when steps is {
    [] -> []
    [step, ..rest] -> [decode_step(step), ..decode_proof(rest)]
  }
}

pub fn decode_step(step: Data) -> ProofStep {
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
pub fn own_input(
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
pub fn payment_script_hash(address: Data) -> ByteArray {
  let payment = builtin.head_list(builtin.unconstr_fields(address))
  if builtin.unconstr_index(payment) == 1 {
    builtin.un_b_data(builtin.head_list(builtin.unconstr_fields(payment)))
  } else {
    #""
  }
}

/// Exactly the enterprise script address of `script_hash` (no stake part).
pub fn is_enterprise_script(address: Data, script_hash: ByteArray) -> Bool {
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

/// The number of outputs whose payment credential is the script `script_hash`.
pub fn outputs_with_credential(
  outputs: List<Output>,
  script_hash: ByteArray,
) -> Int {
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
pub fn continuation(outputs: List<Output>, script_hash: ByteArray) -> Output {
  expect [output, ..rest] = outputs
  if builtin.equals_bytearray(payment_script_hash(output.address), script_hash) {
    output
  } else {
    continuation(rest, script_hash)
  }
}

/// The first token amount of a value's first policy (its lovelace).
pub fn lovelace_of(value: Data) -> Int {
  let first = builtin.head_list(builtin.un_map_data(value))
  builtin.un_i_data(
    builtin.snd_pair(
      builtin.head_list(builtin.un_map_data(builtin.snd_pair(first))),
    ),
  )
}

/// The amount of `policy`/`name` in a value; 0 when absent.
pub fn asset_of(value: Data, policy: ByteArray, name: ByteArray) -> Int {
  policy_amount(
    builtin.un_map_data(value),
    builtin.b_data(policy),
    builtin.b_data(name),
  )
}

pub fn policy_amount(
  policies: Pairs<Data, Data>,
  policy: Data,
  name: Data,
) -> Int {
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

pub fn token_amount(tokens: Pairs<Data, Data>, name: Data) -> Int {
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

pub fn contains_policy(value: Data, policy: ByteArray) -> Bool {
  has_key(builtin.un_map_data(value), builtin.b_data(policy))
}

pub fn has_key(pairs: Pairs<Data, Data>, key: Data) -> Bool {
  when pairs is {
    [] -> False
    [Pair(k, _), ..rest] -> builtin.equals_data(k, key) || has_key(rest, key)
  }
}

/// Lovelace plus exactly the one thread token named `name`, and nothing else.
pub fn thread_only(value: Data, policy: ByteArray, name: ByteArray) -> Bool {
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
