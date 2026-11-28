//// Transaction-level helpers shared by the gateway validators: the Aiken port
//// of the julc `GatewayTx`
//// (`src/main/java/org/yanoproject/x/assets/onchain/GatewayTx.java`) and of
//// the stdlib value readers the julc scripts use (`ValuesLib.lovelaceOf`,
//// `assetOf`, `containsPolicy`).
////
//// The exit-mode thread datum (`ExitModeV1`) is Plutus Data:
//// `NORMAL = Constr 0 []` and
//// `EXIT = Constr 1 [frozenRoot: bytes .size 32, frozenHeight: int, frozenAtMs: int]`.

use aiken/builtin
use aiken/interval.{Finite}
use cardano/transaction.{
  InlineDatum, Input, Output, OutputReference, Redeemer, ScriptPurpose, Spend,
  ValidityRange,
}
use yano_x/codec

/// `Constr 9 []`: an exit-mode datum that is neither `NORMAL` nor a
/// well-formed `EXIT`, and the inline datum of an output that has none.
pub fn invalid_mode() -> Data {
  builtin.constr_data(9, [])
}

/// V11: the datum of the single reference input that carries the exit-mode
/// thread token (policy `thread_policy_id`, empty name) at exactly the
/// enterprise address of `thread_script_hash`, when that datum is a
/// well-formed `ExitModeV1`; otherwise `Constr 9 []`.
pub fn exit_mode_datum(
  references: List<Input>,
  thread_policy_id: ByteArray,
  thread_script_hash: ByteArray,
) -> Data {
  when thread_inputs(references, thread_policy_id, thread_script_hash) is {
    [thread] -> exit_mode_shape(inline_datum(thread.output))
    _ -> invalid_mode()
  }
}

fn thread_inputs(
  inputs: List<Input>,
  thread_policy_id: ByteArray,
  thread_script_hash: ByteArray,
) -> List<Input> {
  when inputs is {
    [] -> []
    [input, ..rest] -> {
      let tail = thread_inputs(rest, thread_policy_id, thread_script_hash)
      if and {
        asset_of(input.output.value, thread_policy_id, #"") == 1,
        codec.is_enterprise_script(input.output.address, thread_script_hash),
      } {
        [input, ..tail]
      } else {
        tail
      }
    }
  }
}

/// The datum itself when it is `NORMAL` or a well-formed `EXIT`;
/// `Constr 9 []` otherwise.
pub fn exit_mode_shape(datum: Data) -> Data {
  let tag = builtin.unconstr_index(datum)
  let fields = builtin.unconstr_fields(datum)
  if tag == 0 {
    if builtin.null_list(fields) {
      datum
    } else {
      invalid_mode()
    }
  } else if tag != 1 || builtin.null_list(fields) {
    invalid_mode()
  } else {
    let root = builtin.un_b_data(builtin.head_list(fields))
    let f1 = builtin.tail_list(fields)
    let height = builtin.un_i_data(builtin.head_list(f1))
    let f2 = builtin.tail_list(f1)
    let at_ms = builtin.un_i_data(builtin.head_list(f2))
    if and {
      builtin.length_of_bytearray(root) == 32,
      height >= 0,
      at_ms >= 0,
      builtin.null_list(builtin.tail_list(f2)),
    } {
      datum
    } else {
      invalid_mode()
    }
  }
}

/// `frozenRoot` of an `EXIT` datum.
pub fn frozen_root(exit_datum: Data) -> ByteArray {
  builtin.un_b_data(builtin.head_list(builtin.unconstr_fields(exit_datum)))
}

/// An output's inline datum, or `Constr 9 []` for no datum or a datum hash.
pub fn inline_datum(output: Output) -> Data {
  when output.datum is {
    InlineDatum(datum) -> datum
    _ -> invalid_mode()
  }
}

/// The spend redeemer of `ref`, or `I -1` when it has none.
pub fn spend_redeemer(
  redeemers: Pairs<ScriptPurpose, Redeemer>,
  ref: OutputReference,
) -> Data {
  let purpose: Data = Spend(ref)
  lookup(redeemers, purpose)
}

/// The redeemer of `purpose`, or `I -1` when it has none.
pub fn lookup(redeemers: Pairs<ScriptPurpose, Redeemer>, purpose: Data) -> Data {
  when redeemers is {
    [] -> builtin.i_data(-1)
    [Pair(key, redeemer), ..rest] ->
      if builtin.equals_data(key, purpose) {
        redeemer
      } else {
        lookup(rest, purpose)
      }
  }
}

/// The number of outputs whose payment credential is the script
/// `script_hash`, at any stake part.
pub fn outputs_with_credential(
  outputs: List<Output>,
  script_hash: ByteArray,
) -> Int {
  when outputs is {
    [] -> 0
    [output, ..rest] -> {
      let count = outputs_with_credential(rest, script_hash)
      if builtin.equals_bytearray(
        codec.payment_script_hash(output.address),
        script_hash,
      ) {
        count + 1
      } else {
        count
      }
    }
  }
}

/// True when `key_hash` is among the required signers.
pub fn signed_by(signers: List<ByteArray>, key_hash: ByteArray) -> Bool {
  when signers is {
    [] -> False
    [signer, ..rest] ->
      builtin.equals_bytearray(signer, key_hash) || signed_by(rest, key_hash)
  }
}

/// The finite lower bound of the validity range, or -1 when it is infinite.
pub fn finite_lower_bound(range: ValidityRange) -> Int {
  when range.lower_bound.bound_type is {
    Finite(time) -> time
    _ -> -1
  }
}

// ------------------------------------------------------------------
// Values, as the julc stdlib reads them

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

pub fn contains_policy(value: Data, policy: ByteArray) -> Bool {
  has_key(builtin.un_map_data(value), builtin.b_data(policy))
}

pub fn has_key(pairs: Pairs<Data, Data>, key: Data) -> Bool {
  when pairs is {
    [] -> False
    [Pair(k, _), ..rest] -> builtin.equals_data(k, key) || has_key(rest, key)
  }
}
