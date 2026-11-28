//// The ADR-059 contract encodings, reproduced on chain byte for byte: the
//// Aiken port of the julc `GatewayCodec`
//// (`src/main/java/org/yanoproject/x/assets/onchain/GatewayCodec.java`).
////
//// App-ledger leaves and identifiers use the strict deterministic CBOR subset
//// of `asset-gateway-v1.cddl`: definite arrays, byte strings and unsigned
//// integers in shortest form. Every function builds the same bytes from the
//// same builtins as its julc twin, so both fail on the same inputs (for
//// example a negative or a 2^64 unsigned integer).

use aiken/builtin

// ------------------------------------------------------------------
// Deterministic CBOR

/// The shortest-form head of major type `major` with argument `n`.
pub fn head(major: Int, n: Int) -> ByteArray {
  let base = major * 32
  if n < 24 {
    builtin.cons_bytearray(base + n, #"")
  } else if n < 256 {
    builtin.cons_bytearray(base + 24, builtin.integer_to_bytearray(True, 1, n))
  } else if n < 65536 {
    builtin.cons_bytearray(base + 25, builtin.integer_to_bytearray(True, 2, n))
  } else if n < 4294967296 {
    builtin.cons_bytearray(base + 26, builtin.integer_to_bytearray(True, 4, n))
  } else {
    builtin.cons_bytearray(base + 27, builtin.integer_to_bytearray(True, 8, n))
  }
}

pub fn uint(n: Int) -> ByteArray {
  head(0, n)
}

pub fn bytes(value: ByteArray) -> ByteArray {
  builtin.append_bytearray(head(2, builtin.length_of_bytearray(value)), value)
}

/// `asset_value` of one vault asset: `[]` for zero, else
/// `[[policy, name, quantity]]`.
pub fn asset_value(
  policy_id: ByteArray,
  asset_name: ByteArray,
  quantity: Int,
) -> ByteArray {
  if quantity == 0 {
    #"80"
  } else {
    builtin.cons_bytearray(
      129,
      builtin.append_bytearray(
        builtin.append_bytearray(
          builtin.cons_bytearray(131, bytes(policy_id)),
          bytes(asset_name),
        ),
        uint(quantity),
      ),
    )
  }
}

// ------------------------------------------------------------------
// Identifiers (GatewayIds)

/// `blake2b-256("yano-asset-gateway-vault-v1\0" ‖ [domain_id, [policy, name], ordinal])`.
pub fn vault_id(
  domain_id: ByteArray,
  policy_id: ByteArray,
  asset_name: ByteArray,
  ordinal: Int,
) -> ByteArray {
  let asset =
    builtin.append_bytearray(
      builtin.cons_bytearray(130, bytes(policy_id)),
      bytes(asset_name),
    )
  let body =
    builtin.append_bytearray(
      builtin.append_bytearray(
        builtin.cons_bytearray(131, bytes(domain_id)),
        asset,
      ),
      uint(ordinal),
    )
  builtin.blake2b_256(
    builtin.append_bytearray(
      #"79616e6f2d61737365742d676174657761792d7661756c742d763100",
      body,
    ),
  )
}

/// `blake2b-256("yano-asset-gateway-claim-v1\0" ‖ [domain_id, vault_id, kind,
/// withdrawal_key, recipient, asset_value, vault_lovelace, sequence])`;
/// `asset_value` is the encoded `asset_value`.
pub fn claim_id(
  domain_id: ByteArray,
  vault_id: ByteArray,
  kind: Int,
  withdrawal_key: ByteArray,
  recipient: ByteArray,
  asset_value: ByteArray,
  vault_lovelace: Int,
  sequence: Int,
) -> ByteArray {
  let a =
    builtin.append_bytearray(
      builtin.cons_bytearray(136, bytes(domain_id)),
      bytes(vault_id),
    )
  let b =
    builtin.append_bytearray(
      builtin.append_bytearray(a, uint(kind)),
      bytes(withdrawal_key),
    )
  let c =
    builtin.append_bytearray(
      builtin.append_bytearray(b, bytes(recipient)),
      asset_value,
    )
  let body =
    builtin.append_bytearray(
      builtin.append_bytearray(c, uint(vault_lovelace)),
      uint(sequence),
    )
  builtin.blake2b_256(
    builtin.append_bytearray(
      #"79616e6f2d61737365742d676174657761792d636c61696d2d763100",
      body,
    ),
  )
}

/// The escape nullifier `blake2b-256("escape\0" ‖ vault_id ‖ holder_id)`.
pub fn escape_id(vault_id: ByteArray, holder_id: ByteArray) -> ByteArray {
  builtin.blake2b_256(
    builtin.append_bytearray(
      builtin.append_bytearray(#"65736361706500", vault_id),
      holder_id,
    ),
  )
}

/// The CBOR of a Cardano `transaction_input`, `[transaction_id, index]`.
pub fn out_ref_cbor(tx_id: ByteArray, index: Int) -> ByteArray {
  builtin.append_bytearray(
    builtin.cons_bytearray(130, bytes(tx_id)),
    uint(index),
  )
}

// ------------------------------------------------------------------
// Leaves

/// `ClaimLeafV1`: `[1, claim_id, vault_id, kind, asset_value, vault_lovelace,
/// recipient, reserved_height, reserved_at_ms]`.
pub fn claim_leaf(
  claim_id: ByteArray,
  vault_id: ByteArray,
  kind: Int,
  asset_value: ByteArray,
  vault_lovelace: Int,
  recipient: ByteArray,
  reserved_height: Int,
  reserved_at_ms: Int,
) -> ByteArray {
  let a =
    builtin.append_bytearray(
      builtin.cons_bytearray(137, builtin.cons_bytearray(1, bytes(claim_id))),
      bytes(vault_id),
    )
  let b =
    builtin.append_bytearray(
      builtin.append_bytearray(a, uint(kind)),
      asset_value,
    )
  let c =
    builtin.append_bytearray(
      builtin.append_bytearray(b, uint(vault_lovelace)),
      bytes(recipient),
    )
  builtin.append_bytearray(
    builtin.append_bytearray(c, uint(reserved_height)),
    uint(reserved_at_ms),
  )
}

/// `ExitLeafV1`: `[1, holder_key_hash, vault_id, amount, vault_lovelace]`.
pub fn exit_leaf(
  holder_key_hash: ByteArray,
  vault_id: ByteArray,
  amount: Int,
  vault_lovelace: Int,
) -> ByteArray {
  let a =
    builtin.append_bytearray(
      builtin.cons_bytearray(
        133,
        builtin.cons_bytearray(1, bytes(holder_key_hash)),
      ),
      bytes(vault_id),
    )
  builtin.append_bytearray(
    builtin.append_bytearray(a, uint(amount)),
    uint(vault_lovelace),
  )
}

/// The status of a `DepositRecordV1` whose `deposit_id` and `vault_id` are the
/// expected ones; -1 otherwise. Total over every byte string.
pub fn deposit_status(
  record: ByteArray,
  deposit_id: ByteArray,
  vault_id: ByteArray,
) -> Int {
  if or {
    builtin.length_of_bytearray(record) < 110,
    builtin.index_bytearray(record, 0) != 145,
    builtin.index_bytearray(record, 1) != 1,
    builtin.slice_bytearray(2, 34, record) != bytes(deposit_id),
    builtin.slice_bytearray(36, 34, record) != bytes(vault_id),
    builtin.index_bytearray(record, 70) != 88,
    builtin.index_bytearray(record, 71) != 32,
  } {
    -1
  } else {
    let Pair(output_index, at_slot) = read_uint(record, 104)
    let Pair(slot, at_height) = read_uint(record, at_slot)
    let Pair(height, at_shape) = read_uint(record, at_height)
    let Pair(shape, at_status) = read_uint(record, at_shape)
    let Pair(status, _) = read_uint(record, at_status)
    if or {
      output_index < 0,
      slot < 0,
      height < 0,
      shape < 0,
    } {
      -1
    } else {
      status
    }
  }
}

/// The `status` and `exit_delay_ms` of a `DomainStatusV1` leaf
/// `[1, status, since_height, since_ms, exit_delay_ms]`, exactly; status -1
/// when the bytes are not one. Total over every byte string.
pub fn domain_status(leaf: ByteArray) -> Pair<Int, Int> {
  let length = builtin.length_of_bytearray(leaf)
  if or {
    length < 6,
    builtin.index_bytearray(leaf, 0) != 133,
    builtin.index_bytearray(leaf, 1) != 1,
  } {
    Pair(-1, 0)
  } else {
    let Pair(status, at_height) = read_uint(leaf, 2)
    let Pair(since_height, at_ms) = read_uint(leaf, at_height)
    let Pair(since_ms, at_delay) = read_uint(leaf, at_ms)
    let Pair(exit_delay, end) = read_uint(leaf, at_delay)
    if and {
      end == length,
      status >= 0,
      status <= 2,
      since_height >= 0,
      since_ms >= 0,
      exit_delay >= 0,
    } {
      Pair(status, exit_delay)
    } else {
      Pair(-1, 0)
    }
  }
}

/// A shortest-form CBOR unsigned integer at `offset` and the offset after it;
/// value -1 (and an offset past the end) on anything else.
pub fn read_uint(encoded: ByteArray, offset: Int) -> Pair<Int, Int> {
  let length = builtin.length_of_bytearray(encoded)
  if offset < 0 || offset >= length {
    Pair(-1, length + 1)
  } else {
    let first = builtin.index_bytearray(encoded, offset)
    if first < 24 {
      Pair(first, offset + 1)
    } else {
      let width =
        if first == 24 {
          1
        } else if first == 25 {
          2
        } else if first == 26 {
          4
        } else if first == 27 {
          8
        } else {
          0
        }
      if width == 0 || offset + 1 + width > length {
        Pair(-1, length + 1)
      } else {
        let value =
          builtin.bytearray_to_integer(
            True,
            builtin.slice_bytearray(offset + 1, width, encoded),
          )
        let minimum =
          if width == 1 {
            24
          } else if width == 2 {
            256
          } else if width == 4 {
            65536
          } else {
            4294967296
          }
        if value >= minimum {
          Pair(value, offset + 1 + width)
        } else {
          Pair(-1, length + 1)
        }
      }
    }
  }
}

// ------------------------------------------------------------------
// Addresses

/// The raw Shelley bytes of a Plutus address with network id `network_id`:
/// base, pointer (CIP-19 variable-length naturals) or enterprise. Empty for a
/// malformed credential.
pub fn address_bytes(address: Data, network_id: Int) -> ByteArray {
  let fields = builtin.unconstr_fields(address)
  let payment = builtin.head_list(fields)
  let stake = builtin.head_list(builtin.tail_list(fields))
  let payment_tag = builtin.unconstr_index(payment)
  let payment_hash =
    builtin.un_b_data(builtin.head_list(builtin.unconstr_fields(payment)))
  if builtin.length_of_bytearray(payment_hash) != 28 || payment_tag > 1 {
    #""
  } else if builtin.unconstr_index(stake) == 1 {
    builtin.cons_bytearray(( 6 + payment_tag ) * 16 + network_id, payment_hash)
  } else {
    let staking = builtin.head_list(builtin.unconstr_fields(stake))
    let staking_fields = builtin.unconstr_fields(staking)
    if builtin.unconstr_index(staking) != 0 {
      let slot = builtin.un_i_data(builtin.head_list(staking_fields))
      let f1 = builtin.tail_list(staking_fields)
      let tx_index = builtin.un_i_data(builtin.head_list(f1))
      let cert_index =
        builtin.un_i_data(builtin.head_list(builtin.tail_list(f1)))
      if slot < 0 || tx_index < 0 || cert_index < 0 {
        #""
      } else {
        builtin.cons_bytearray(
          ( 4 + payment_tag ) * 16 + network_id,
          builtin.append_bytearray(
            payment_hash,
            builtin.append_bytearray(
              var_nat(slot),
              builtin.append_bytearray(var_nat(tx_index), var_nat(cert_index)),
            ),
          ),
        )
      }
    } else {
      let credential = builtin.head_list(staking_fields)
      let stake_tag = builtin.unconstr_index(credential)
      let stake_hash =
        builtin.un_b_data(
          builtin.head_list(builtin.unconstr_fields(credential)),
        )
      if builtin.length_of_bytearray(stake_hash) != 28 || stake_tag > 1 {
        #""
      } else {
        builtin.cons_bytearray(
          ( payment_tag + 2 * stake_tag ) * 16 + network_id,
          builtin.append_bytearray(payment_hash, stake_hash),
        )
      }
    }
  }
}

/// A CIP-19 variable-length natural, in its shortest form.
pub fn var_nat(n: Int) -> ByteArray {
  var_nat_high(n / 128, builtin.cons_bytearray(n % 128, #""))
}

fn var_nat_high(n: Int, tail: ByteArray) -> ByteArray {
  if n == 0 {
    tail
  } else {
    var_nat_high(n / 128, builtin.cons_bytearray(n % 128 + 128, tail))
  }
}

/// The script hash of an address whose payment credential is a script; empty
/// otherwise.
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
