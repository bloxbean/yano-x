//// The rules ADR-061 adds to the anchored-claim vault for a CIP-113 asset,
//// shared by the custody script `h` (`programmable_vault`) and its exit
//// delegate `e` (`programmable_vault_exit`): the Aiken port of the julc
//// `ProgrammableVaultLib`
//// (`src/main/java/org/yanoproject/x/assets/onchain/ProgrammableVaultLib.java`).
////
//// The vault's custody is every UTxO at the vault smart wallet
//// `(Script(PLB), Inline(Script(h)))` (D1). Redeemers and datums are read
//// from their Plutus Data positionally, as the julc scripts read them.

use aiken/builtin
use cardano/address.{Credential}
use cardano/transaction.{
  DatumHash, Input, Output, Redeemer, ScriptPurpose, Transaction,
}
use yano_x/codec
use yano_x/gateway_tx.{asset_of, contains_policy, inline_datum, lovelace_of}

// ------------------------------------------------------------------
// Data kinds

pub fn is_constr(data: Data) -> Bool {
  builtin.choose_data(data, True, False, False, False, False)
}

pub fn is_list(data: Data) -> Bool {
  builtin.choose_data(data, False, False, True, False, False)
}

pub fn is_int(data: Data) -> Bool {
  builtin.choose_data(data, False, False, False, True, False)
}

pub fn is_bytes(data: Data) -> Bool {
  builtin.choose_data(data, False, False, False, False, True)
}

pub fn length(items: List<Data>) -> Int {
  when items is {
    [] -> 0
    [_, ..rest] -> 1 + length(rest)
  }
}

// ------------------------------------------------------------------
// Addresses

/// The Plutus Data of `base(Script(plb), Script(owner))`: the smart wallet of
/// the script `owner`.
pub fn smart_wallet(plb: ByteArray, owner: ByteArray) -> Data {
  let payment = builtin.constr_data(1, [builtin.b_data(plb)])
  let owner_credential = builtin.constr_data(1, [builtin.b_data(owner)])
  let stake =
    builtin.constr_data(0, [builtin.constr_data(0, [owner_credential])])
  builtin.constr_data(0, [payment, stake])
}

/// True when `address` may receive a payout (D7): a key-owned smart wallet of
/// the pinned deployment; or, when the payout carries no native asset, also
/// an address with a key payment credential.
pub fn payable(address: Data, plb: ByteArray, carries_tokens: Bool) -> Bool {
  let fields = builtin.unconstr_fields(address)
  let payment = builtin.head_list(fields)
  if builtin.unconstr_index(payment) == 0 {
    !carries_tokens
  } else {
    let stake = builtin.head_list(builtin.tail_list(fields))
    if builtin.unconstr_index(stake) != 0 {
      False
    } else {
      let staking = builtin.head_list(builtin.unconstr_fields(stake))
      and {
        builtin.equals_bytearray(
          builtin.un_b_data(builtin.head_list(builtin.unconstr_fields(payment))),
          plb,
        ),
        builtin.unconstr_index(staking) == 0,
        builtin.unconstr_index(
          builtin.head_list(builtin.unconstr_fields(staking)),
        ) == 0,
      }
    }
  }
}

/// True when the raw address `raw` is a key-owned smart wallet of the pinned
/// deployment on network `network_id`: 57 bytes, header `0x10 | network` and
/// `plb` as the payment credential (D6, D7).
pub fn raw_smart_wallet(raw: ByteArray, plb: ByteArray, network_id: Int) -> Bool {
  and {
    builtin.length_of_bytearray(raw) == 57,
    builtin.index_bytearray(raw, 0) == 16 + network_id,
    builtin.equals_bytearray(builtin.slice_bytearray(1, 28, raw), plb),
  }
}

// ------------------------------------------------------------------
// Inputs and outputs

/// V14: the inputs at exactly `vault`, the vault smart wallet, in ledger
/// order.
pub fn vault_inputs(inputs: List<Input>, vault: Data) -> List<Input> {
  when inputs is {
    [] -> []
    [input, ..rest] -> {
      let tail = vault_inputs(rest, vault)
      if builtin.equals_data(input.output.address, vault) {
        [input, ..tail]
      } else {
        tail
      }
    }
  }
}

/// V8, as `shards + 256 * other_scripts`: inputs that are not vault inputs and
/// carry a shard thread token, and every other script input.
pub fn foreign_inputs(
  inputs: List<Input>,
  vault: Data,
  shard_policy: ByteArray,
) -> Int {
  when inputs is {
    [] -> 0
    [input, ..rest] -> {
      let tail = foreign_inputs(rest, vault, shard_policy)
      let address: Data = input.output.address
      if builtin.equals_data(address, vault) {
        tail
      } else if contains_policy(input.output.value, shard_policy) {
        tail + 1
      } else if builtin.length_of_bytearray(codec.payment_script_hash(address)) > 0 {
        tail + 256
      } else {
        tail
      }
    }
  }
}

/// The number of outputs at exactly `address`.
pub fn outputs_at(outputs: List<Output>, address: Data) -> Int {
  when outputs is {
    [] -> 0
    [output, ..rest] -> {
      let tail = outputs_at(rest, address)
      if builtin.equals_data(output.address, address) {
        tail + 1
      } else {
        tail
      }
    }
  }
}

/// V15: no output has `h` or `e` as its payment credential, and exactly
/// `expected` outputs sit at the full vault smart-wallet address.
pub fn vault_outputs(
  outputs: List<Output>,
  vault: Data,
  custody_hash: ByteArray,
  delegate_hash: ByteArray,
  expected: Int,
) -> Bool {
  and {
    gateway_tx.outputs_with_credential(outputs, custody_hash) == 0,
    gateway_tx.outputs_with_credential(outputs, delegate_hash) == 0,
    outputs_at(outputs, vault) == expected,
  }
}

/// V17: no certificates and no mint. Withdrawals are not restricted.
pub fn no_certificates_or_mint(tx: Transaction) -> Bool {
  let mint: Data = tx.mint
  when tx.certificates is {
    [] -> builtin.null_list(builtin.un_map_data(mint))
    _ -> False
  }
}

/// True when `Script(hash)` is among the withdrawals, at any amount.
pub fn withdrawn(withdrawals: Pairs<Credential, Int>, hash: ByteArray) -> Bool {
  credential_withdrawn(
    withdrawals,
    builtin.constr_data(1, [builtin.b_data(hash)]),
  )
}

fn credential_withdrawn(withdrawals: Pairs<Credential, Int>, key: Data) -> Bool {
  when withdrawals is {
    [] -> False
    [Pair(credential, _), ..rest] ->
      builtin.equals_data(credential, key) || credential_withdrawn(rest, key)
  }
}

/// The redeemer of the reward purpose `Script(hash)`, or `I -1` when it has
/// none.
pub fn reward_redeemer(
  redeemers: Pairs<ScriptPurpose, Redeemer>,
  hash: ByteArray,
) -> Data {
  gateway_tx.lookup(
    redeemers,
    builtin.constr_data(2, [builtin.constr_data(1, [builtin.b_data(hash)])]),
  )
}

/// `DelegatedActionV1 = Constr 0 [custody_hash, action]`.
pub fn delegated_action(custody_hash: ByteArray, action: Data) -> Data {
  builtin.constr_data(0, [builtin.b_data(custody_hash), action])
}

// ------------------------------------------------------------------
// Values and vault shapes

/// The value holds lovelace and at most the one vault asset `policy.name`,
/// and nothing else.
pub fn clean_value(value: Data, policy: ByteArray, name: ByteArray) -> Bool {
  let rest = builtin.tail_list(builtin.un_map_data(value))
  when rest is {
    [] -> True
    [entry, ..others] -> {
      let tokens = builtin.un_map_data(builtin.snd_pair(entry))
      and {
        builtin.null_list(others),
        builtin.equals_bytearray(
          builtin.un_b_data(builtin.fst_pair(entry)),
          policy,
        ),
        builtin.null_list(builtin.tail_list(tokens)),
        builtin.equals_bytearray(
          builtin.un_b_data(builtin.fst_pair(builtin.head_list(tokens))),
          name,
        ),
      }
    }
  }
}

/// V10, the native vault's acceptance rule: `FloorTopUpV1` holding lovelace
/// only, or `VaultContinuationV1(ids)` (one field: a list of at most 64
/// 32-byte ids) holding only lovelace and the vault asset. Total over every
/// datum. (The julc twin reads the same shapes from the datum's canonical
/// serialization.)
pub fn accepted(
  datum: Data,
  value: Data,
  policy: ByteArray,
  name: ByteArray,
) -> Bool {
  if builtin.equals_data(datum, builtin.constr_data(2, [])) {
    builtin.null_list(builtin.tail_list(builtin.un_map_data(value)))
  } else {
    clean_value(value, policy, name) && continuation_shaped(datum)
  }
}

fn continuation_shaped(datum: Data) -> Bool {
  is_constr(datum) && builtin.unconstr_index(datum) == 1 && when
    builtin.unconstr_fields(datum)
  is {
    [ids] -> is_list(ids) && ids_shaped(builtin.un_list_data(ids), 0)
    _ -> False
  }
}

fn ids_shaped(ids: List<Data>, index: Int) -> Bool {
  when ids is {
    [] -> True
    [id, ..rest] -> and {
        index < 64,
        bytes_of_length(id, 32),
        ids_shaped(rest, index + 1),
      }
  }
}

/// V10 over every vault input.
pub fn all_accepted(
  vaults: List<Input>,
  policy: ByteArray,
  name: ByteArray,
) -> Bool {
  when vaults is {
    [] -> True
    [input, ..rest] ->
      accepted(inline_datum(input.output), input.output.value, policy, name) && all_accepted(
        rest,
        policy,
        name,
      )
  }
}

pub fn input_lovelace(vaults: List<Input>) -> Int {
  when vaults is {
    [] -> 0
    [input, ..rest] -> lovelace_of(input.output.value) + input_lovelace(rest)
  }
}

pub fn input_asset(
  vaults: List<Input>,
  policy: ByteArray,
  name: ByteArray,
) -> Int {
  when vaults is {
    [] -> 0
    [input, ..rest] ->
      asset_of(input.output.value, policy, name) + input_asset(
        rest,
        policy,
        name,
      )
  }
}

// ------------------------------------------------------------------
// ProgrammableDepositDatumV1 (D6)

/// The total deposit-shaped predicate (D6, r4): a `Constr 0` datum of exactly
/// four fields, a 28-byte beneficiary, a refund address that is a key-owned
/// smart wallet of the pinned deployment, a 28-byte refund key hash, and an
/// origin `Constr 0 [32-byte tx id, uint, uint]` with no other field, each
/// uint below 2^64. It never fails: every field's kind is checked before it
/// is decoded.
pub fn deposit_shaped(datum: Data, plb: ByteArray, network_id: Int) -> Bool {
  is_constr(datum) && builtin.unconstr_index(datum) == 0 && when
    builtin.unconstr_fields(datum)
  is {
    [beneficiary, refund_address, refund_key_hash, origin] -> and {
        bytes_of_length(beneficiary, 28),
        is_bytes(refund_address),
        raw_smart_wallet(builtin.un_b_data(refund_address), plb, network_id),
        bytes_of_length(refund_key_hash, 28),
        origin_shaped(origin),
      }
    _ -> False
  }
}

fn origin_shaped(origin: Data) -> Bool {
  is_constr(origin) && builtin.unconstr_index(origin) == 0 && when
    builtin.unconstr_fields(origin)
  is {
    [tx_id, origin_index, output_index] -> and {
        bytes_of_length(tx_id, 32),
        is_uint64(origin_index),
        is_uint64(output_index),
      }
    _ -> False
  }
}

fn bytes_of_length(data: Data, length: Int) -> Bool {
  is_bytes(data) && builtin.length_of_bytearray(builtin.un_b_data(data)) == length
}

/// An unsigned integer below 2^64.
fn is_uint64(data: Data) -> Bool {
  is_int(data) && {
    let value = builtin.un_i_data(data)
    value >= 0 && value < 18446744073709551616
  }
}

/// Every vault input is deposit-shaped and holds only the vault asset and
/// lovelace.
pub fn all_deposit_shaped(
  vaults: List<Input>,
  plb: ByteArray,
  network_id: Int,
  policy: ByteArray,
  name: ByteArray,
) -> Bool {
  when vaults is {
    [] -> True
    [input, ..rest] -> and {
        deposit_shaped(inline_datum(input.output), plb, network_id),
        clean_value(input.output.value, policy, name),
        all_deposit_shaped(rest, plb, network_id, policy, name),
      }
  }
}

/// The origin-keyed `DepositId` of a deposit-shaped datum:
/// `blake2b-256("deposit-cip113\0" ‖ [vault_id, [origin_tx_id, origin_index], output_index])`.
pub fn deposit_id(vault_id: ByteArray, datum: Data) -> ByteArray {
  let origin =
    builtin.unconstr_fields(
      builtin.head_list(
        builtin.tail_list(
          builtin.tail_list(builtin.tail_list(builtin.unconstr_fields(datum))),
        ),
      ),
    )
  let tx_id = builtin.un_b_data(builtin.head_list(origin))
  let rest = builtin.tail_list(origin)
  let origin_index = builtin.un_i_data(builtin.head_list(rest))
  let output_index =
    builtin.un_i_data(builtin.head_list(builtin.tail_list(rest)))
  let body =
    builtin.append_bytearray(
      builtin.append_bytearray(
        builtin.cons_bytearray(131, codec.bytes(vault_id)),
        codec.out_ref_cbor(tx_id, origin_index),
      ),
      codec.uint(output_index),
    )
  builtin.blake2b_256(
    builtin.append_bytearray(#"6465706f7369742d63697031313300", body),
  )
}

/// `refund_address` (field 1) of a deposit-shaped datum.
pub fn refund_address(datum: Data) -> ByteArray {
  builtin.un_b_data(
    builtin.head_list(builtin.tail_list(builtin.unconstr_fields(datum))),
  )
}

/// `refund_key_hash` (field 2) of a deposit-shaped datum.
pub fn refund_key_hash(datum: Data) -> ByteArray {
  builtin.un_b_data(
    builtin.head_list(
      builtin.tail_list(builtin.tail_list(builtin.unconstr_fields(datum))),
    ),
  )
}

// ------------------------------------------------------------------
// Payouts, the continuation and the shard inserts

/// V5: the output pays the raw `recipient` exactly `quantity` of the vault
/// asset plus at least `vault_lovelace`, at a payable address (D7), with no
/// datum hash and no reference script.
pub fn pays_to(
  output: Output,
  recipient: ByteArray,
  quantity: Int,
  vault_lovelace: Int,
  plb: ByteArray,
  policy: ByteArray,
  name: ByteArray,
  network_id: Int,
) -> Bool {
  let address: Data = output.address
  and {
    asset_of(output.value, policy, name) == quantity,
    lovelace_of(output.value) >= vault_lovelace,
    payable(address, plb, quantity > 0),
    when output.datum is {
      DatumHash(_) -> False
      _ -> True
    },
    when output.reference_script is {
      None -> True
      _ -> False
    },
    builtin.equals_bytearray(
      codec.address_bytes(address, network_id),
      recipient,
    ),
  }
}

/// The output at `index`; fails past the end.
pub fn output_at(outputs: List<Output>, index: Int) -> Output {
  expect [output, ..rest] = outputs
  if index == 0 {
    output
  } else {
    output_at(rest, index - 1)
  }
}

/// V6, V7, V15 and V16: output `index` is the one output at the vault smart
/// wallet `vault`, holds only the vault asset and lovelace, carries exactly
/// `in - debit` of each and at least `floor` lovelace, no reference script
/// and the inline `VaultContinuationV1(ids)` with at most `max_ids` ids; no
/// output pays `h` or `e`.
pub fn continuation_valid(
  outputs: List<Output>,
  index: Int,
  vaults: List<Input>,
  vault: Data,
  custody_hash: ByteArray,
  delegate_hash: ByteArray,
  quantity: Int,
  lovelace: Int,
  ids: List<Data>,
  id_count: Int,
  max_ids: Int,
  floor: Int,
  policy: ByteArray,
  name: ByteArray,
) -> Bool {
  let out = output_at(outputs, index)
  let value: Data = out.value
  let out_lovelace = lovelace_of(value)
  and {
    id_count <= max_ids,
    out_lovelace == input_lovelace(vaults) - lovelace,
    asset_of(value, policy, name) == input_asset(vaults, policy, name) - quantity,
    out_lovelace >= floor,
    clean_value(value, policy, name),
    when out.reference_script is {
      None -> True
      _ -> False
    },
    builtin.equals_data(out.address, vault),
    vault_outputs(outputs, vault, custody_hash, delegate_hash, 1),
    builtin.equals_data(
      inline_datum(out),
      builtin.constr_data(1, [builtin.list_data(ids)]),
    ),
  }
}

/// `Accept` and `Consolidate`: the vault inputs merge into one exact
/// `VaultContinuationV1([])` wherever it sits among the outputs; no shard and
/// no other script input. The floor does not apply.
pub fn merge_valid(
  tx: Transaction,
  vaults: List<Input>,
  vault: Data,
  custody_hash: ByteArray,
  delegate_hash: ByteArray,
  shard_policy: ByteArray,
  policy: ByteArray,
  name: ByteArray,
) -> Bool {
  let index = output_index(tx.outputs, vault, 0)
  and {
    foreign_inputs(tx.inputs, vault, shard_policy) == 0,
    index >= 0,
    continuation_valid(
      tx.outputs,
      index,
      vaults,
      vault,
      custody_hash,
      delegate_hash,
      0,
      0,
      [],
      0,
      0,
      0,
      policy,
      name,
    ),
  }
}

fn output_index(outputs: List<Output>, address: Data, index: Int) -> Int {
  when outputs is {
    [] -> -1
    [output, ..rest] ->
      if builtin.equals_data(output.address, address) {
        index
      } else {
        output_index(rest, address, index + 1)
      }
  }
}

/// The ids that inputs carrying the shard thread token `[shard_index]`
/// insert, concatenated in redeemer order; with exactly one shard input that
/// is that shard's insert list.
pub fn shard_inserts(
  tx: Transaction,
  shard_policy: ByteArray,
  shard_index: Int,
) -> ByteArray {
  let name = builtin.cons_bytearray(shard_index, #"")
  shard_inserts_in(tx.redeemers, tx.inputs, shard_policy, name)
}

fn shard_inserts_in(
  redeemers: Pairs<ScriptPurpose, Redeemer>,
  inputs: List<Input>,
  shard_policy: ByteArray,
  name: ByteArray,
) -> ByteArray {
  when inputs is {
    [] -> #""
    [input, ..rest] -> {
      let tail = shard_inserts_in(redeemers, rest, shard_policy, name)
      if asset_of(input.output.value, shard_policy, name) == 1 {
        builtin.append_bytearray(
          insert_ids(
            builtin.un_list_data(
              builtin.head_list(
                builtin.unconstr_fields(
                  gateway_tx.spend_redeemer(redeemers, input.output_reference),
                ),
              ),
            ),
          ),
          tail,
        )
      } else {
        tail
      }
    }
  }
}

fn insert_ids(inserts: List<Data>) -> ByteArray {
  when inserts is {
    [] -> #""
    [insert, ..rest] ->
      builtin.append_bytearray(
        builtin.un_b_data(builtin.head_list(builtin.unconstr_fields(insert))),
        insert_ids(rest),
      )
  }
}

/// The id cap of V16: `floor((max_inline_datum_bytes - 6) / 34)`, and at most
/// 64.
pub fn max_ids(max_inline_datum_bytes: Int) -> Int {
  let cap = ( max_inline_datum_bytes - 6 ) / 34
  if cap < 64 {
    cap
  } else {
    64
  }
}
