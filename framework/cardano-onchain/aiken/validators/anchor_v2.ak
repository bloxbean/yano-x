//// Yano app-chain script anchor v2 (ADR-059 framework addition 5, D8g §1;
//// ABI: framework/cardano-api/src/main/cddl/appchain/anchor-v2.cddl).
////
//// Opt-in successor of `anchor.ak` (v1). The v1 validator and its checked
//// artifact are unchanged; a chain selects v2 with
//// `anchor-profile.version: 2`. The thread NFT policy is the v1
//// `anchor_thread` policy.
////
//// The datum is the v1 datum with version 2 and one appended field,
//// `updated_at_ms` (POSIX milliseconds):
////   Constr(0, [version=2, chain-id, chain-genesis-id, application-id,
////              commitment-profile-id, format-fingerprint, height, block-hash,
////              state-root, member-keys, threshold, updated-at-ms])
////
//// Advance rules (spend): every v1 rule, plus
////   6. a PURE HEARTBEAT is allowed: next.height == current.height requires an
////      identical block hash, state root, member set and threshold (identity
////      is unchanged by rule 2) and next.updated_at_ms > current.updated_at_ms;
////      any other advance requires next.height > current.height
////   7. the validity range has finite bounds lb and ub with
////      next.updated_at_ms == ub and ub - lb <= max_window_ms
//// Rule 7 stops both backdating and future-dating the anchor time.
////
//// Parameters: thread_policy_id (28 bytes) and max_window_ms (the ADR
//// default is 600000 = 10 minutes).

use aiken/builtin
use aiken/collection/dict
use aiken/collection/list
use aiken/crypto.{blake2b_224}
use aiken/interval.{Finite, Interval, IntervalBound}
use cardano/assets.{lovelace_of, tokens}
use cardano/transaction.{InlineDatum, Output, OutputReference, Transaction}

/// anchor-datum per anchor-v2.cddl — field order is the ABI; Aiken encodes
/// this record as Constr(0, fields).
pub type AnchorDatumV2 {
  version: Int,
  chain_id: ByteArray,
  chain_genesis_id: ByteArray,
  application_id: ByteArray,
  commitment_profile_id: ByteArray,
  format_fingerprint: ByteArray,
  height: Int,
  block_hash: ByteArray,
  state_root: ByteArray,
  member_keys: List<ByteArray>,
  threshold: Int,
  updated_at_ms: Int,
}

validator anchor_v2(thread_policy_id: ByteArray, max_window_ms: Int) {
  spend(
    datum: Option<Data>,
    _redeemer: Data,
    own_ref: OutputReference,
    self: Transaction,
  ) {
    // Checked cast from raw Data, as in v1: the exact Constr(0, 12 fields)
    // shape is required for both datums.
    expect Some(current_data) = datum
    expect current: AnchorDatumV2 = current_data
    expect Some(own_input) =
      list.find(self.inputs, fn(input) { input.output_reference == own_ref })
    let own_address = own_input.output.address

    // Rule 1: exactly one continuing output carrying the thread NFT,
    // with an inline datum
    expect [next] =
      list.filter(self.outputs, fn(output) { output.address == own_address })
    let thread_continues = carries_thread_token(next, thread_policy_id)
    expect InlineDatum(next_data) = next.datum
    expect next_datum: AnchorDatumV2 = next_data

    // Rules 2 + 4 (identity, base and member profiles)
    let current_base_profile_valid = base_profile_valid(current)
    let next_base_profile_valid = base_profile_valid(next_datum)
    let same_chain = next_datum.chain_id == current.chain_id
    let same_genesis = next_datum.chain_genesis_id == current.chain_genesis_id
    let same_application = next_datum.application_id == current.application_id
    let same_commitment =
      next_datum.commitment_profile_id == current.commitment_profile_id && next_datum.format_fingerprint == current.format_fingerprint
    let same_version = next_datum.version == current.version
    let current_profile_valid =
      member_profile_valid(current.member_keys, current.threshold)
    let next_profile_valid =
      member_profile_valid(next_datum.member_keys, next_datum.threshold)

    // Rule 6: strictly higher, or a pure heartbeat at the same height
    let progresses =
      if next_datum.height == current.height {
        next_datum.block_hash == current.block_hash && next_datum.state_root == current.state_root && next_datum.member_keys == current.member_keys && next_datum.threshold == current.threshold && next_datum.updated_at_ms > current.updated_at_ms
      } else {
        next_datum.height > current.height
      }

    // Rule 7: updated_at_ms is the validity upper bound, bounded window
    let time_bound =
      validity_window_valid(
        self.validity_range,
        next_datum.updated_at_ms,
        max_window_ms,
      )

    // Rule 3: threshold of the INPUT datum's members signed this tx
    let signers =
      list.count(
        current.member_keys,
        fn(member_key) {
          list.has(self.extra_signatories, blake2b_224(member_key))
        },
      )
    let threshold_signed = signers >= current.threshold

    // Rule 5: no draining
    let value_preserved =
      lovelace_of(next.value) >= lovelace_of(own_input.output.value)

    thread_continues && current_base_profile_valid && next_base_profile_valid && progresses && time_bound && same_chain && same_genesis && same_application && same_commitment && same_version && current_profile_valid && next_profile_valid && threshold_signed && value_preserved
  }

  else(_) {
    fail
  }
}

/// Both bounds finite; the upper bound equals `updated_at_ms` and the window
/// `ub - lb` is within `max_window_ms`. Bound inclusiveness is not inspected:
/// the ledger always translates a transaction's validity interval to an
/// inclusive lower and exclusive upper POSIX bound.
fn validity_window_valid(
  range: Interval<Int>,
  updated_at_ms: Int,
  max_window_ms: Int,
) -> Bool {
  when range is {
    Interval {
      lower_bound: IntervalBound { bound_type: Finite(lower), .. },
      upper_bound: IntervalBound { bound_type: Finite(upper), .. },
    } -> updated_at_ms == upper && upper - lower <= max_window_ms
    _ -> False
  }
}

/// Complete v2 scalar/byte-string profile: the v1 profile with version 2 and
/// a non-negative `updated_at_ms`.
fn base_profile_valid(datum: AnchorDatumV2) -> Bool {
  let chain_id_length = builtin.length_of_bytearray(datum.chain_id)
  datum.version == 2 && chain_id_length >= 1 && chain_id_length <= 128 && datum.height >= 0 && datum.updated_at_ms >= 0 && builtin.length_of_bytearray(
    datum.block_hash,
  ) == 32 && builtin.length_of_bytearray(datum.state_root) == 32 && builtin.length_of_bytearray(
    datum.chain_genesis_id,
  ) == 32 && builtin.length_of_bytearray(datum.application_id) >= 1 && builtin.length_of_bytearray(
    datum.application_id,
  ) <= 128 && builtin.length_of_bytearray(datum.commitment_profile_id) >= 1 && builtin.length_of_bytearray(
    datum.commitment_profile_id,
  ) <= 128 && builtin.length_of_bytearray(datum.format_fingerprint) == 32
}

/// 1..32 strictly ascending 32-byte keys and a threshold within the count.
fn member_profile_valid(member_keys: List<ByteArray>, threshold: Int) -> Bool {
  when valid_member_count(member_keys, None, 0) is {
    Some(member_count) -> threshold >= 1 && threshold <= member_count
    None -> False
  }
}

fn valid_member_count(
  member_keys: List<ByteArray>,
  previous: Option<ByteArray>,
  count: Int,
) -> Option<Int> {
  when member_keys is {
    [] ->
      if count >= 1 {
        Some(count)
      } else {
        None
      }
    [member_key, ..rest] ->
      if count >= 32 || builtin.length_of_bytearray(member_key) != 32 {
        None
      } else {
        when previous is {
          None -> valid_member_count(rest, Some(member_key), count + 1)
          Some(previous_key) ->
            if builtin.less_than_bytearray(previous_key, member_key) {
              valid_member_count(rest, Some(member_key), count + 1)
            } else {
              None
            }
        }
      }
  }
}

/// Exactly one token with quantity exactly 1 under the thread policy.
fn carries_thread_token(output: Output, thread_policy_id: ByteArray) -> Bool {
  let qty_one_tokens =
    tokens(output.value, thread_policy_id)
      |> dict.to_pairs()
      |> list.count(fn(pair) { pair.2nd == 1 })
  qty_one_tokens == 1
}
