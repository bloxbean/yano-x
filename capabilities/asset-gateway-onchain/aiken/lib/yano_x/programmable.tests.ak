//// Tests of `yano_x/programmable`: the total deposit-shaped predicate and the
//// V10 acceptance rule read every Data kind without failing, and the
//// acceptance rule matches the julc twin's reading of the canonical
//// serialization (`ProgrammableVaultLib.accepted`).

use aiken/builtin
use yano_x/programmable as lib

const plb = #"00000000000000000000000000000000000000000000000000000001"

const policy = #"7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a"

const name = "USDM"

fn smart_wallet(key: ByteArray) -> ByteArray {
  builtin.append_bytearray(builtin.cons_bytearray(16, plb), key)
}

fn key() -> ByteArray {
  #"a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2"
}

fn origin(tx_id: Data, origin_index: Data, output_index: Data) -> Data {
  builtin.constr_data(0, [tx_id, origin_index, output_index])
}

fn tx_id() -> Data {
  builtin.b_data(
    #"4040404040404040404040404040404040404040404040404040404040404040",
  )
}

fn deposit(fields: List<Data>) -> Data {
  builtin.constr_data(0, fields)
}

fn good() -> List<Data> {
  [
    builtin.b_data(#"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"),
    builtin.b_data(smart_wallet(key())),
    builtin.b_data(key()),
    origin(tx_id(), builtin.i_data(0), builtin.i_data(1)),
  ]
}

test a_deposit_datum_is_deposit_shaped() {
  and {
    lib.deposit_shaped(deposit(good()), plb, 0),
    lib.deposit_shaped(
      deposit(
        [
          builtin.b_data(
            #"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1",
          ),
          builtin.b_data(smart_wallet(key())),
          builtin.b_data(key()),
          origin(
            tx_id(),
            builtin.i_data(18446744073709551615),
            builtin.i_data(18446744073709551615),
          ),
        ],
      ),
      plb,
      0,
    ),
  }
}

test every_other_datum_is_not_deposit_shaped_and_never_fails() {
  expect [beneficiary, refund, refund_key, _] = good()
  let others =
    [
      builtin.i_data(5),
      builtin.b_data(#"01020304"),
      builtin.list_data(good()),
      builtin.map_data([]),
      builtin.constr_data(1, good()),
      deposit([beneficiary, refund, refund_key]),
      deposit(
        [
          beneficiary,
          refund,
          refund_key,
          origin(tx_id(), builtin.i_data(0), builtin.i_data(1)),
          builtin.i_data(0),
        ],
      ),
      deposit(
        [
          builtin.i_data(1),
          refund,
          refund_key,
          origin(tx_id(), builtin.i_data(0), builtin.i_data(1)),
        ],
      ),
      deposit(
        [
          beneficiary,
          builtin.i_data(1),
          refund_key,
          origin(tx_id(), builtin.i_data(0), builtin.i_data(1)),
        ],
      ),
      deposit(
        [
          beneficiary,
          refund,
          builtin.list_data([]),
          origin(tx_id(), builtin.i_data(0), builtin.i_data(1)),
        ],
      ),
      deposit([beneficiary, refund, refund_key, builtin.list_data([tx_id()])]),
      deposit([beneficiary, refund, refund_key, builtin.i_data(0)]),
      deposit(
        [
          beneficiary,
          refund,
          refund_key,
          origin(builtin.b_data(#"40"), builtin.i_data(0), builtin.i_data(1)),
        ],
      ),
      deposit(
        [
          beneficiary,
          refund,
          refund_key,
          origin(tx_id(), builtin.i_data(-1), builtin.i_data(1)),
        ],
      ),
      deposit(
        [
          beneficiary,
          refund,
          refund_key,
          origin(
            tx_id(),
            builtin.i_data(0),
            builtin.i_data(18446744073709551616),
          ),
        ],
      ),
      deposit(
        [
          beneficiary,
          refund,
          refund_key,
          origin(tx_id(), builtin.b_data(#""), builtin.i_data(1)),
        ],
      ),
      deposit(
        [
          beneficiary,
          refund,
          refund_key,
          builtin.constr_data(
            1,
            [tx_id(), builtin.i_data(0), builtin.i_data(1)],
          ),
        ],
      ),
      deposit(
        [
          beneficiary,
          refund,
          refund_key,
          builtin.constr_data(0, [tx_id(), builtin.i_data(0)]),
        ],
      ),
      deposit(
        [
          beneficiary,
          builtin.b_data(
            builtin.cons_bytearray(
              48,
              builtin.slice_bytearray(1, 56, smart_wallet(key())),
            ),
          ),
          refund_key,
          origin(tx_id(), builtin.i_data(0), builtin.i_data(1)),
        ],
      ),
      deposit(
        [
          beneficiary,
          builtin.b_data(smart_wallet(#"a2")),
          refund_key,
          origin(tx_id(), builtin.i_data(0), builtin.i_data(1)),
        ],
      ),
    ]
  !any_shaped(others)
}

fn any_shaped(datums: List<Data>) -> Bool {
  when datums is {
    [] -> False
    [datum, ..rest] -> lib.deposit_shaped(datum, plb, 0) || any_shaped(rest)
  }
}

// ------------------------------------------------------------------
// V10 acceptance

fn value(lovelace: Int, quantity: Int) -> Data {
  let ada =
    Pair(
      builtin.b_data(#""),
      builtin.map_data([Pair(builtin.b_data(#""), builtin.i_data(lovelace))]),
    )
  if quantity == 0 {
    builtin.map_data([ada])
  } else {
    builtin.map_data(
      [
        ada,
        Pair(
          builtin.b_data(policy),
          builtin.map_data(
            [Pair(builtin.b_data(name), builtin.i_data(quantity))],
          ),
        ),
      ],
    )
  }
}

fn ids(count: Int) -> List<Data> {
  if count == 0 {
    []
  } else {
    [
      builtin.b_data(
        #"9999999999999999999999999999999999999999999999999999999999999999",
      ),
      ..ids(count - 1)
    ]
  }
}

/// The julc twin's rule, on the datum's canonical serialization.
fn serialized_rule(datum: Data, value: Data) -> Bool {
  if builtin.equals_data(datum, builtin.constr_data(2, [])) {
    builtin.null_list(builtin.tail_list(builtin.un_map_data(value)))
  } else {
    let encoded = builtin.serialise_data(datum)
    let length = builtin.length_of_bytearray(encoded)
    and {
      lib.clean_value(value, policy, name),
      or {
        length == 5 && encoded == #"d87a9f80ff",
        and {
          builtin.slice_bytearray(0, 4, encoded) == #"d87a9f9f",
          builtin.slice_bytearray(length - 2, 2, encoded) == #"ffff",
          ( length - 6 ) % 34 == 0,
          length - 6 <= 64 * 34,
          chunks(encoded, 4, length - 2),
        },
      },
    }
  }
}

fn chunks(encoded: ByteArray, at: Int, end: Int) -> Bool {
  if at >= end {
    at == end
  } else {
    builtin.index_bytearray(encoded, at) == 88 && builtin.index_bytearray(
      encoded,
      at + 1,
    ) == 32 && chunks(encoded, at + 34, end)
  }
}

fn agree(cases: List<Pair<Data, Data>>, accepted: Int) -> Int {
  when cases is {
    [] -> accepted
    [Pair(datum, v), ..rest] -> {
      let verdict = lib.accepted(datum, v, policy, name)
      expect verdict == serialized_rule(datum, v)
      agree(
        rest,
        if verdict {
          accepted + 1
        } else {
          accepted
        },
      )
    }
  }
}

test acceptance_matches_the_serialized_rule() {
  let continuation =
    fn(items: List<Data>) { builtin.constr_data(1, [builtin.list_data(items)]) }
  let lovelace_only = value(2000000, 0)
  let with_asset = value(2000000, 500)
  let foreign =
    builtin.map_data(
      [
        Pair(
          builtin.b_data(#""),
          builtin.map_data([Pair(builtin.b_data(#""), builtin.i_data(2))]),
        ),
        Pair(
          builtin.b_data(#"7b"),
          builtin.map_data([Pair(builtin.b_data(name), builtin.i_data(1))]),
        ),
      ],
    )
  let cases =
    [
      Pair(builtin.constr_data(2, []), lovelace_only),
      Pair(builtin.constr_data(2, []), with_asset),
      Pair(continuation([]), lovelace_only),
      Pair(continuation([]), with_asset),
      Pair(continuation([]), foreign),
      Pair(continuation(ids(1)), with_asset),
      Pair(continuation(ids(29)), with_asset),
      Pair(continuation(ids(64)), with_asset),
      Pair(continuation(ids(65)), with_asset),
      Pair(continuation([builtin.b_data(#"99")]), with_asset),
      Pair(continuation([builtin.i_data(1)]), with_asset),
      Pair(continuation([builtin.list_data([])]), with_asset),
      Pair(builtin.constr_data(1, []), with_asset),
      Pair(
        builtin.constr_data(1, [builtin.list_data(ids(1)), builtin.i_data(0)]),
        with_asset,
      ),
      Pair(builtin.constr_data(1, [builtin.b_data(#"")]), with_asset),
      Pair(builtin.constr_data(0, [builtin.list_data(ids(1))]), with_asset),
      Pair(builtin.constr_data(2, [builtin.i_data(0)]), lovelace_only),
      Pair(builtin.constr_data(9, []), with_asset),
      Pair(builtin.i_data(0), with_asset),
      Pair(builtin.b_data(#"d87a9f80ff"), with_asset),
      Pair(builtin.list_data([builtin.list_data([])]), with_asset),
      Pair(builtin.map_data([]), with_asset),
    ]
  agree(cases, 0) == 6
}

test the_continuation_id_cap() {
  and {
    lib.max_ids(1024) == 29,
    lib.max_ids(1023) == 29,
    lib.max_ids(1001) == 29,
    lib.max_ids(1000) == 29,
    lib.max_ids(999) == 29,
    lib.max_ids(991) == 28,
    lib.max_ids(100000) == 64,
  }
}

test raw_smart_wallets() {
  and {
    lib.raw_smart_wallet(smart_wallet(key()), plb, 0),
    !lib.raw_smart_wallet(smart_wallet(key()), plb, 1),
    !lib.raw_smart_wallet(smart_wallet(#"a2"), plb, 0),
    !lib.raw_smart_wallet(#"", plb, 0),
  }
}
