//// Bounded mpf-blake2b256-v1 inclusion and exclusion verifiers of the Yano
//// app-ledger state trie: the Aiken port of the julc `MpfOnChainVerifier`
//// (`sdk/proof-onchain/src/main/java/org/yanoproject/x/proofs/onchain/MpfOnChainVerifier.java`),
//// with the same proof shapes, bounds and proof-shape rules.
////
//// Proofs are read from their Plutus Data positionally, as the julc verifier
//// reads them (constructor tags and extra fields are ignored):
////
////   - `Proof = Constr _ [key, value, leaf_suffix, folds]`;
////   - `AbsenceProof = Constr _ [key, value, leaf_suffix, folds,
////     terminal_cursor, conflicting_key_hash, conflicting_value_hash]`, whose
////     `value` is never read;
////   - `InclusionFold = Constr _ [cursor, prefix, nibble, neighbor1,
////     neighbor2, neighbor3, neighbor4, branch_value_hash]`.
////
//// Both verifiers return False, or fail, on any malformed proof. The callers
//// use them only positively, so a failure and False are the same verdict.

use aiken/builtin

const path_nibbles = 64

const max_key_bytes = 256

const max_value_bytes = 8192

const max_folds = 32

fn hash(left: ByteArray, right: ByteArray) -> ByteArray {
  builtin.blake2b_256(builtin.append_bytearray(left, right))
}

fn hash_length(value: ByteArray) -> Bool {
  builtin.length_of_bytearray(value) == 32
}

fn path_nibble(path: ByteArray, index: Int) -> Int {
  let value = builtin.index_bytearray(path, index / 2)
  if index % 2 == 0 {
    value / 16
  } else {
    value % 16
  }
}

fn field(fields: List<Data>, index: Int) -> Data {
  if index == 0 {
    builtin.head_list(fields)
  } else {
    field(builtin.tail_list(fields), index - 1)
  }
}

fn count(items: List<Data>) -> Int {
  when items is {
    [] -> 0
    [_, ..rest] -> 1 + count(rest)
  }
}

/// Reconstructs the exact mpf-blake2b256-v1 root under hostile redeemer
/// bounds (julc `verifyInclusion`).
pub fn verify_inclusion(proof: Data, expected_root: ByteArray) -> Bool {
  let fields = builtin.unconstr_fields(proof)
  let key = builtin.un_b_data(builtin.head_list(fields))
  let f1 = builtin.tail_list(fields)
  let value = builtin.un_b_data(builtin.head_list(f1))
  let f2 = builtin.tail_list(f1)
  let leaf_suffix = builtin.un_b_data(builtin.head_list(f2))
  let key_length = builtin.length_of_bytearray(key)
  let value_length = builtin.length_of_bytearray(value)
  let encoded_length = builtin.length_of_bytearray(leaf_suffix)
  if or {
    builtin.length_of_bytearray(expected_root) != 32,
    key_length < 1,
    key_length > max_key_bytes,
    value_length < 1,
    value_length > max_value_bytes,
    encoded_length < 1,
    encoded_length > 33,
    count(builtin.un_list_data(builtin.head_list(builtin.tail_list(f2)))) > max_folds,
  } {
    False
  } else {
    let path = builtin.blake2b_256(key)
    let marker = builtin.index_bytearray(leaf_suffix, 0)
    let odd = marker == 0
    let skip =
      if odd {
        2
      } else {
        1
      }
    let suffix_length =
      if odd {
        1 + ( encoded_length - 2 ) * 2
      } else {
        ( encoded_length - 1 ) * 2
      }
    let cursor_end = path_nibbles - suffix_length
    if or {
      marker != 0 && marker != 255,
      odd && encoded_length < 2,
      cursor_end < 0,
      odd && builtin.index_bytearray(leaf_suffix, 1) != path_nibble(
        path,
        cursor_end,
      ),
      builtin.slice_bytearray(skip, encoded_length - skip, leaf_suffix) != builtin.slice_bytearray(
        if odd {
          ( cursor_end + 1 ) / 2
        } else {
          cursor_end / 2
        },
        encoded_length - skip,
        path,
      ),
    } {
      False
    } else {
      let child = hash(leaf_suffix, builtin.blake2b_256(value))
      builtin.equals_bytearray(
        expected_root,
        fold_up(
          path,
          child,
          cursor_end,
          builtin.un_list_data(builtin.head_list(builtin.tail_list(f2))),
        ),
      )
    }
  }
}

/// True when the key is absent from the trie with root `expected_root` (julc
/// `verifyAbsence`): the folds authenticate the terminal node at depth
/// `terminal_cursor` on the key's own path, and that node is an empty slot, a
/// leaf whose authenticated suffix differs from the key's own at that depth,
/// or a branch whose authenticated prefix leaves the key's path, under the
/// julc rules that keep the leaf and branch readings apart.
pub fn verify_absence(proof: Data, expected_root: ByteArray) -> Bool {
  let fields = builtin.unconstr_fields(proof)
  let key = builtin.un_b_data(field(fields, 0))
  let leaf_suffix = builtin.un_b_data(field(fields, 2))
  let folds_data = field(fields, 3)
  let cursor_end = builtin.un_i_data(field(fields, 4))
  let conflicting_key_hash = builtin.un_b_data(field(fields, 5))
  let conflicting_value_hash = builtin.un_b_data(field(fields, 6))
  let key_length = builtin.length_of_bytearray(key)
  let suffix_length = builtin.length_of_bytearray(leaf_suffix)
  let key_hash_length = builtin.length_of_bytearray(conflicting_key_hash)
  let value_hash_length = builtin.length_of_bytearray(conflicting_value_hash)
  if or {
    builtin.length_of_bytearray(expected_root) != 32,
    key_length < 1,
    key_length > max_key_bytes,
    cursor_end < 0,
    cursor_end > path_nibbles,
    count(builtin.un_list_data(folds_data)) > max_folds,
  } {
    False
  } else {
    let query_path = builtin.blake2b_256(key)
    let missing = suffix_length == 0
    let terminal_valid =
      if missing {
        key_hash_length == 0 && value_hash_length == 0
      } else {
        value_hash_length == 32 && if key_hash_length == 0 {
          and {
            cursor_end + suffix_length < path_nibbles,
            divergent_prefix(query_path, cursor_end, leaf_suffix, 0, False),
            or {
              cursor_end % 2 == 0,
              suffix_length != 2 + ( 63 - cursor_end ) / 2,
              builtin.index_bytearray(leaf_suffix, 0) != 0,
            },
          }
        } else {
          and {
            key_hash_length == 32,
            suffix_length <= 33,
            valid_leaf_suffix(conflicting_key_hash, cursor_end, leaf_suffix),
            !valid_leaf_suffix(query_path, cursor_end, leaf_suffix),
            builtin.index_bytearray(leaf_suffix, 0) != 0 || raw_byte_above_15(
              leaf_suffix,
              2,
            ),
          }
        }
      }
    terminal_valid && {
      let child =
        if missing {
          #"0000000000000000000000000000000000000000000000000000000000000000"
        } else {
          hash(leaf_suffix, conflicting_value_hash)
        }
      builtin.equals_bytearray(
        expected_root,
        fold_up(query_path, child, cursor_end, builtin.un_list_data(folds_data)),
      )
    }
  }
}

/// True when some byte of `encoded` from `index` on is above 15: not a branch
/// prefix.
fn raw_byte_above_15(encoded: ByteArray, index: Int) -> Bool {
  if index >= builtin.length_of_bytearray(encoded) {
    False
  } else {
    builtin.index_bytearray(encoded, index) > 15 || raw_byte_above_15(
      encoded,
      index + 1,
    )
  }
}

/// True when every byte of `prefix` is a nibble and at least one differs from
/// the path nibble at `cursor + index`.
fn divergent_prefix(
  path: ByteArray,
  cursor: Int,
  prefix: ByteArray,
  index: Int,
  diverged: Bool,
) -> Bool {
  if index == builtin.length_of_bytearray(prefix) {
    diverged
  } else {
    let nibble = builtin.index_bytearray(prefix, index)
    nibble <= 15 && divergent_prefix(
      path,
      cursor,
      prefix,
      index + 1,
      diverged || nibble != path_nibble(path, cursor + index),
    )
  }
}

fn valid_leaf_suffix(
  path: ByteArray,
  cursor_end: Int,
  encoded: ByteArray,
) -> Bool {
  let encoded_length = builtin.length_of_bytearray(encoded)
  let marker = builtin.index_bytearray(encoded, 0)
  let odd = marker == 0
  let skip =
    if odd {
      2
    } else {
      1
    }
  let suffix_nibbles =
    if odd {
      1 + ( encoded_length - 2 ) * 2
    } else {
      ( encoded_length - 1 ) * 2
    }
  and {
    marker == 0 || marker == 255,
    !odd || encoded_length >= 2,
    cursor_end + suffix_nibbles == path_nibbles,
    !odd || builtin.index_bytearray(encoded, 1) == path_nibble(path, cursor_end),
    builtin.slice_bytearray(skip, encoded_length - skip, encoded) == builtin.slice_bytearray(
      if odd {
        ( cursor_end + 1 ) / 2
      } else {
        cursor_end / 2
      },
      encoded_length - skip,
      path,
    ),
  }
}

/// Folds a path up to the root under the per-fold rules of both verifiers; a
/// malformed fold, or a path that does not end at cursor 0, yields an empty
/// (never matching) root.
fn fold_up(
  path: ByteArray,
  child: ByteArray,
  cursor_end: Int,
  folds: List<Data>,
) -> ByteArray {
  when folds is {
    [] ->
      if cursor_end == 0 {
        child
      } else {
        #""
      }
    [fold, ..rest] -> {
      let f = builtin.unconstr_fields(fold)
      let cursor = builtin.un_i_data(builtin.head_list(f))
      let f1 = builtin.tail_list(f)
      let prefix = builtin.un_b_data(builtin.head_list(f1))
      let f2 = builtin.tail_list(f1)
      let nibble = builtin.un_i_data(builtin.head_list(f2))
      let f3 = builtin.tail_list(f2)
      let neighbor1 = builtin.un_b_data(builtin.head_list(f3))
      let f4 = builtin.tail_list(f3)
      let neighbor2 = builtin.un_b_data(builtin.head_list(f4))
      let f5 = builtin.tail_list(f4)
      let neighbor3 = builtin.un_b_data(builtin.head_list(f5))
      let f6 = builtin.tail_list(f5)
      let neighbor4 = builtin.un_b_data(builtin.head_list(f6))
      let branch_value_hash =
        builtin.un_b_data(builtin.head_list(builtin.tail_list(f6)))
      let prefix_length = builtin.length_of_bytearray(prefix)
      let value_hash_length = builtin.length_of_bytearray(branch_value_hash)
      if or {
        cursor < 0,
        nibble < 0,
        nibble > 15,
        cursor + prefix_length + 1 != cursor_end,
        !valid_prefix(path, cursor, prefix, 0, prefix_length),
        path_nibble(path, cursor + prefix_length) != nibble,
        !hash_length(neighbor1),
        !hash_length(neighbor2),
        !hash_length(neighbor3),
        !hash_length(neighbor4),
        value_hash_length != 0 && value_hash_length != 32,
      } {
        #""
      } else {
        let branch =
          aggregate(nibble, child, neighbor1, neighbor2, neighbor3, neighbor4)
        let merkle =
          if value_hash_length == 32 {
            hash(branch, hash(#"ff", branch_value_hash))
          } else {
            branch
          }
        fold_up(path, hash(prefix, merkle), cursor, rest)
      }
    }
  }
}

fn valid_prefix(
  path: ByteArray,
  cursor: Int,
  prefix: ByteArray,
  index: Int,
  length: Int,
) -> Bool {
  if index >= length {
    True
  } else {
    let nibble = builtin.index_bytearray(prefix, index)
    nibble <= 15 && nibble == path_nibble(path, cursor + index) && valid_prefix(
      path,
      cursor,
      prefix,
      index + 1,
      length,
    )
  }
}

fn aggregate(
  nibble: Int,
  me: ByteArray,
  lvl1: ByteArray,
  lvl2: ByteArray,
  lvl3: ByteArray,
  lvl4: ByteArray,
) -> ByteArray {
  if nibble < 8 {
    let inner =
      if nibble < 4 {
        let low =
          if nibble < 2 {
            if nibble == 0 {
              hash(me, lvl4)
            } else {
              hash(lvl4, me)
            }
          } else if nibble == 2 {
            hash(lvl3, hash(me, lvl4))
          } else {
            hash(lvl3, hash(lvl4, me))
          }
        if nibble < 2 {
          hash(hash(low, lvl3), lvl2)
        } else {
          hash(low, lvl2)
        }
      } else {
        let low =
          if nibble < 6 {
            if nibble == 4 {
              hash(hash(me, lvl4), lvl3)
            } else {
              hash(hash(lvl4, me), lvl3)
            }
          } else if nibble == 6 {
            hash(lvl3, hash(me, lvl4))
          } else {
            hash(lvl3, hash(lvl4, me))
          }
        hash(lvl2, low)
      }
    hash(inner, lvl1)
  } else {
    let inner =
      if nibble < 12 {
        let low =
          if nibble < 10 {
            if nibble == 8 {
              hash(hash(me, lvl4), lvl3)
            } else {
              hash(hash(lvl4, me), lvl3)
            }
          } else if nibble == 10 {
            hash(lvl3, hash(me, lvl4))
          } else {
            hash(lvl3, hash(lvl4, me))
          }
        hash(low, lvl2)
      } else {
        let low =
          if nibble < 14 {
            if nibble == 12 {
              hash(hash(me, lvl4), lvl3)
            } else {
              hash(hash(lvl4, me), lvl3)
            }
          } else if nibble == 14 {
            hash(lvl3, hash(me, lvl4))
          } else {
            hash(lvl3, hash(lvl4, me))
          }
        hash(lvl2, low)
      }
    hash(lvl1, inner)
  }
}
