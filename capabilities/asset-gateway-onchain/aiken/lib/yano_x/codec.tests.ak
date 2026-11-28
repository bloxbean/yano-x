//// Tests of `yano_x/codec`: the deterministic CBOR heads at every width
//// boundary, the strict unsigned-integer reader, the `DomainStatusV1` and
//// `DepositRecordV1` readers and the CIP-19 variable-length naturals.

use aiken/builtin
use yano_x/codec

test uint_heads_at_every_width_boundary() {
  and {
    codec.uint(0) == #"00",
    codec.uint(23) == #"17",
    codec.uint(24) == #"1818",
    codec.uint(255) == #"18ff",
    codec.uint(256) == #"190100",
    codec.uint(65535) == #"19ffff",
    codec.uint(65536) == #"1a00010000",
    codec.uint(4294967295) == #"1affffffff",
    codec.uint(4294967296) == #"1b0000000100000000",
    codec.uint(18446744073709551615) == #"1bffffffffffffffff",
  }
}

test uint_of_2_pow_64_fails() fail {
  codec.uint(18446744073709551616) == #""
}

test uint_of_a_negative_fails() fail {
  codec.uint(-1) == #""
}

test byte_string_heads() {
  and {
    codec.bytes(#"") == #"40",
    codec.bytes(#"0102") == #"420102",
    builtin.slice_bytearray(
      0,
      2,
      codec.bytes(
        #"0000000000000000000000000000000000000000000000000000000000000000",
      ),
    ) == #"5820",
  }
}

test read_uint_round_trips() {
  and {
    codec.read_uint(#"17", 0) == Pair(23, 1),
    codec.read_uint(#"001818", 1) == Pair(24, 3),
    codec.read_uint(#"190100", 0) == Pair(256, 3),
    codec.read_uint(#"1a00010000", 0) == Pair(65536, 5),
    codec.read_uint(#"1b0000000100000000", 0) == Pair(4294967296, 9),
  }
}

test read_uint_rejects_non_shortest_truncated_and_other_kinds() {
  and {
    codec.read_uint(#"1817", 0) == Pair(-1, 3),
    codec.read_uint(#"1900ff", 0) == Pair(-1, 4),
    codec.read_uint(#"1b00000000ffffffff", 0) == Pair(-1, 10),
    codec.read_uint(#"19ff", 0) == Pair(-1, 3),
    codec.read_uint(#"20", 0) == Pair(-1, 2),
    codec.read_uint(#"1c", 0) == Pair(-1, 2),
    codec.read_uint(#"00", 1) == Pair(-1, 2),
    codec.read_uint(#"00", -1) == Pair(-1, 2),
  }
}

test domain_status_reads_exactly_one_leaf() {
  // [1, ACTIVE, 50, 0, 86_400_000]
  and {
    codec.domain_status(#"8501001832001a05265c00") == Pair(0, 86400000),
    codec.domain_status(#"8501021832001a05265c00") == Pair(2, 86400000),
    codec.domain_status(#"8501031832001a05265c00") == Pair(-1, 0),
    codec.domain_status(#"8501001832001a05265c0000") == Pair(-1, 0),
    codec.domain_status(#"8502001832001a05265c00") == Pair(-1, 0),
    codec.domain_status(#"850100183200") == Pair(-1, 0),
    codec.domain_status(#"85010018") == Pair(-1, 0),
  }
}

test var_nat_shortest_forms() {
  and {
    codec.var_nat(0) == #"00",
    codec.var_nat(127) == #"7f",
    codec.var_nat(128) == #"8100",
    codec.var_nat(16383) == #"ff7f",
    codec.var_nat(16384) == #"818000",
  }
}

test out_ref_cbor_is_a_transaction_input() {
  codec.out_ref_cbor(
    #"0000000000000000000000000000000000000000000000000000000000000001",
    256,
  ) == #"8258200000000000000000000000000000000000000000000000000000000000000001190100"
}

test deposit_status_rejects_other_records() {
  let id = #"1111111111111111111111111111111111111111111111111111111111111111"
  let vault =
    #"2222222222222222222222222222222222222222222222222222222222222222"
  // [1, deposit_id, vault_id, tx_id, 0, 9_000, 77, 0, 1, ...]: 17 elements, status UNCREDITED.
  let record =
    builtin.append_bytearray(
      builtin.append_bytearray(
        builtin.append_bytearray(#"9101", codec.bytes(id)),
        codec.bytes(vault),
      ),
      builtin.append_bytearray(
        codec.bytes(
          #"3333333333333333333333333333333333333333333333333333333333333333",
        ),
        #"00192328184d00014040404040404040",
      ),
    )
  and {
    codec.deposit_status(record, id, vault) == 1,
    codec.deposit_status(record, vault, id) == -1,
    codec.deposit_status(builtin.slice_bytearray(0, 109, record), id, vault) == -1,
    codec.deposit_status(#"", id, vault) == -1,
  }
}
