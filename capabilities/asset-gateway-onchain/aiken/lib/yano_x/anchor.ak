//// The unique SCRIPT anchor reference input and its commitment identity: the
//// Aiken port of the julc `MpfAnchorLib.acceptedAnchorStateIn` and
//// `signedByThreshold`
//// (`sdk/proof-onchain/src/main/java/org/yanoproject/x/proofs/onchain/MpfAnchorLib.java`).
////
//// The anchor datum is `Constr 0 [version, chain_id, genesis, application,
//// profile, fingerprint, height, block_hash, state_root, member_keys,
//// threshold]` (v1), with `updated_at_ms` appended as field 12 in v2. It is
//// accepted when the version is 1 or 2, there is no trailing field, the
//// chain id is 1 to 128 bytes, the height is not negative, the block hash and
//// state root are 32 bytes, a v2 `updated_at_ms` is not negative, the member
//// keys are 32 bytes each and strictly ascending, `1 <= threshold <= count`,
//// and the genesis, application, profile and fingerprint are the expected
//// ones.

use aiken/builtin
use cardano/transaction.{InlineDatum, Input, Output}
use yano_x/gateway_tx

/// An accepted anchor datum: its version, state root, member keys (a Plutus
/// Data list of 32-byte keys, already validated) and threshold.
pub type AnchorState {
  version: Int,
  state_root: ByteArray,
  member_keys: Data,
  threshold: Int,
}

/// The accepted anchor state of the single reference input that carries the
/// thread token at the anchor script address, for a v1 or v2 datum.
pub fn accepted_anchor_state_in(
  reference_inputs: List<Input>,
  anchor_thread_policy_id: ByteArray,
  anchor_thread_asset_name: ByteArray,
  anchor_script_hash: ByteArray,
  expected_chain_genesis_id: ByteArray,
  expected_application_id: ByteArray,
  expected_commitment_profile_id: ByteArray,
  expected_format_fingerprint: ByteArray,
) -> Option<AnchorState> {
  when
    anchor_inputs(
      reference_inputs,
      anchor_thread_policy_id,
      anchor_thread_asset_name,
      anchor_script_hash,
    )
  is {
    [anchor] ->
      when anchor.output.datum is {
        InlineDatum(datum) ->
          anchor_state_data(
            datum,
            expected_chain_genesis_id,
            expected_application_id,
            expected_commitment_profile_id,
            expected_format_fingerprint,
          )
        _ -> None
      }
    _ -> None
  }
}

fn anchor_inputs(
  inputs: List<Input>,
  policy: ByteArray,
  name: ByteArray,
  script_hash: ByteArray,
) -> List<Input> {
  when inputs is {
    [] -> []
    [input, ..rest] -> {
      let tail = anchor_inputs(rest, policy, name, script_hash)
      if and {
        gateway_tx.asset_of(input.output.value, policy, name) == 1,
        at_script_address(input.output, script_hash),
      } {
        [input, ..tail]
      } else {
        tail
      }
    }
  }
}

fn at_script_address(output: Output, script_hash: ByteArray) -> Bool {
  let credential: Data = output.address.payment_credential
  let fields = builtin.unconstr_fields(credential)
  and {
    builtin.unconstr_index(credential) == 1,
    builtin.null_list(builtin.tail_list(fields)),
    builtin.equals_bytearray(
      builtin.un_b_data(builtin.head_list(fields)),
      script_hash,
    ),
  }
}

/// The v1 field list, or the v2 list with `updated_at_ms` appended.
fn anchor_state_data(
  value: Data,
  expected_chain_genesis_id: ByteArray,
  expected_application_id: ByteArray,
  expected_commitment_profile_id: ByteArray,
  expected_format_fingerprint: ByteArray,
) -> Option<AnchorState> {
  if builtin.unconstr_index(value) != 0 {
    None
  } else {
    let fields = builtin.unconstr_fields(value)
    let version = builtin.un_i_data(builtin.head_list(fields))
    let f1 = builtin.tail_list(fields)
    let chain_id = builtin.un_b_data(builtin.head_list(f1))
    let f2 = builtin.tail_list(f1)
    let genesis = builtin.un_b_data(builtin.head_list(f2))
    let f3 = builtin.tail_list(f2)
    let application = builtin.un_b_data(builtin.head_list(f3))
    let f4 = builtin.tail_list(f3)
    let profile = builtin.un_b_data(builtin.head_list(f4))
    let f5 = builtin.tail_list(f4)
    let fingerprint = builtin.un_b_data(builtin.head_list(f5))
    let f6 = builtin.tail_list(f5)
    let height = builtin.un_i_data(builtin.head_list(f6))
    let f7 = builtin.tail_list(f6)
    let block_hash = builtin.un_b_data(builtin.head_list(f7))
    let f8 = builtin.tail_list(f7)
    let state_root = builtin.un_b_data(builtin.head_list(f8))
    let f9 = builtin.tail_list(f8)
    let members_data = builtin.head_list(f9)
    let f10 = builtin.tail_list(f9)
    let threshold = builtin.un_i_data(builtin.head_list(f10))
    let f11 = builtin.tail_list(f10)
    let v2 = version == 2
    let member_count = sorted_member_count(builtin.un_list_data(members_data))
    if and {
      version == 1 || v2,
      if v2 {
        and {
          builtin.un_i_data(builtin.head_list(f11)) >= 0,
          builtin.null_list(builtin.tail_list(f11)),
        }
      } else {
        builtin.null_list(f11)
      },
      builtin.length_of_bytearray(chain_id) >= 1,
      builtin.length_of_bytearray(chain_id) <= 128,
      height >= 0,
      builtin.length_of_bytearray(block_hash) == 32,
      builtin.length_of_bytearray(state_root) == 32,
      member_count > 0,
      threshold > 0,
      threshold <= member_count,
      builtin.equals_bytearray(genesis, expected_chain_genesis_id),
      builtin.equals_bytearray(application, expected_application_id),
      builtin.equals_bytearray(profile, expected_commitment_profile_id),
      builtin.equals_bytearray(fingerprint, expected_format_fingerprint),
    } {
      Some(
        AnchorState {
          version,
          state_root,
          member_keys: members_data,
          threshold,
        },
      )
    } else {
      None
    }
  }
}

/// The number of member keys when every key is 32 bytes and the list is
/// strictly ascending; -1 otherwise.
fn sorted_member_count(members: List<Data>) -> Int {
  when members is {
    [] -> 0
    [first, ..rest] -> {
      let key = builtin.un_b_data(first)
      let tail = sorted_member_count(rest)
      if and {
        builtin.length_of_bytearray(key) == 32,
        tail >= 0,
        when rest is {
          [] -> True
          [next, ..] ->
            builtin.less_than_bytearray(key, builtin.un_b_data(next))
        },
      } {
        tail + 1
      } else {
        -1
      }
    }
  }
}

/// True when at least `threshold` of the anchor's member keys sign the
/// transaction: for each key, `blake2b-224(key)` is a required signer. Each
/// key counts once, because the keys were checked strictly ascending.
pub fn signed_by_threshold(
  signers: List<ByteArray>,
  anchor: AnchorState,
) -> Bool {
  signed_count(signers, builtin.un_list_data(anchor.member_keys)) >= anchor.threshold
}

fn signed_count(signers: List<ByteArray>, keys: List<Data>) -> Int {
  when keys is {
    [] -> 0
    [key, ..rest] -> {
      let count = signed_count(signers, rest)
      if gateway_tx.signed_by(
        signers,
        builtin.blake2b_224(builtin.un_b_data(key)),
      ) {
        count + 1
      } else {
        count
      }
    }
  }
}
