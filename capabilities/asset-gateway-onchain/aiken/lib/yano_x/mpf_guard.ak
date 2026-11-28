//// Yano X proof-shape checks in front of aiken-lang/merkle-patricia-forestry
//// v2.1.0 (ADR-059 D8c, r23).
////
//// Yano X applies its own proof-shape checks before the library's has, miss
//// and insert. They are the rules of the julc shard backend
//// (`MerklePatriciaForestry.isWellFormed` and
//// `MerklePatriciaForestry.excludedTerminalIsUnambiguous` in
//// `capabilities/asset-gateway-onchain`), so both backends accept exactly the
//// same proofs:
////
////   - `well_formed`: every step's skip stays inside the 64-nibble path; a
////     branch carries 128 bytes of neighbors; a fork neighbor has a nibble
////     0..15 that differs from the path's, a prefix of nibbles that ends above
////     the leaf level, and a 32-byte root; a leaf has a 32-byte key and value,
////     and its key shares the path up to the step's nibble and differs there.
////   - `terminal_unambiguous`: for an exclusion proof, the terminal step's
////     commitment cannot also be read as the other node kind. At an odd
////     cursor a terminal leaf needs a raw key byte above 15 from byte
////     (cursor + 1) / 2 on, and a terminal fork whose reconstructed prefix
////     starts with nibble 0 must not have the odd-leaf length
////     2 + (63 - cursor) / 2.
////
//// Both checks return False on a malformed proof and never fail. The library
//// call that follows them is unchanged.

use aiken/builtin.{blake2b_256}
use aiken/merkle_patricia_forestry.{
  Branch, Fork, Leaf, MerklePatriciaForestry, Proof,
} as mpf
use aiken/primitive/bytearray

/// The nibble at `index` of a 32-byte path.
fn path_nibble(self: ByteArray, index: Int) -> Int {
  if index % 2 == 0 {
    bytearray.at(self, index / 2) / 16
  } else {
    bytearray.at(self, index / 2) % 16
  }
}

/// True when every byte of `bs` from index `i` on is a nibble (0..15).
fn all_nibbles(bs: ByteArray, i: Int) -> Bool {
  if i >= bytearray.length(bs) {
    True
  } else {
    bytearray.at(bs, i) <= 15 && all_nibbles(bs, i + 1)
  }
}

/// True when `key` shares nibbles [0, at) with `path` and differs at `at`.
fn forks_at(key: ByteArray, path: ByteArray, at: Int) -> Bool {
  let half = at / 2
  let same_prefix =
    bytearray.take(key, half) == bytearray.take(path, half) && (
      at % 2 == 0 || path_nibble(key, at - 1) == path_nibble(path, at - 1)
    )
  same_prefix && path_nibble(key, at) != path_nibble(path, at)
}

/// The shape of every step of `proof` for `path`, from `cursor` on
/// (julc `isWellFormed`). A step with skip `s` sits at nibble `cursor + s`,
/// which must be below 64.
pub fn well_formed(path: ByteArray, cursor: Int, proof: Proof) -> Bool {
  when proof is {
    [] -> True

    [Branch { skip, neighbors }, ..steps] -> and {
        skip >= 0,
        skip < 64 - cursor,
        bytearray.length(neighbors) == 128,
        well_formed(path, cursor + skip + 1, steps),
      }

    [Fork { skip, neighbor }, ..steps] -> {
      let at = cursor + skip
      and {
        skip >= 0,
        skip < 64 - cursor,
        neighbor.nibble >= 0,
        neighbor.nibble <= 15,
        neighbor.nibble != path_nibble(path, at),
        at + 1 + bytearray.length(neighbor.prefix) < 64,
        all_nibbles(neighbor.prefix, 0),
        bytearray.length(neighbor.root) == 32,
        well_formed(path, at + 1, steps),
      }
    }

    [Leaf { skip, key, value }, ..steps] -> {
      let at = cursor + skip
      and {
        skip >= 0,
        skip < 64 - cursor,
        bytearray.length(key) == 32,
        bytearray.length(value) == 32,
        forks_at(key, path, at),
        well_formed(path, at + 1, steps),
      }
    }
  }
}

/// For an exclusion proof, the terminal step's commitment reads as one node
/// kind only (julc `excludedTerminalIsUnambiguous`).
pub fn terminal_unambiguous(path: ByteArray, cursor: Int, proof: Proof) -> Bool {
  when proof is {
    [] -> True

    [Branch { .. }] -> True
    [Branch { skip, .. }, ..steps] ->
      terminal_unambiguous(path, cursor + skip + 1, steps)

    [Fork { skip, neighbor }] -> {
      let first =
        if skip == 0 {
          neighbor.nibble
        } else {
          path_nibble(path, cursor)
        }
      or {
        cursor % 2 == 0,
        first != 0,
        skip + 1 + bytearray.length(neighbor.prefix) != 2 + ( 63 - cursor ) / 2,
      }
    }
    [Fork { skip, .. }, ..steps] ->
      terminal_unambiguous(path, cursor + skip + 1, steps)

    [Leaf { key, .. }] ->
      cursor % 2 == 0 || !all_nibbles(key, ( cursor + 1 ) / 2)
    [Leaf { skip, .. }, ..steps] ->
      terminal_unambiguous(path, cursor + skip + 1, steps)
  }
}

/// `mpf.has` behind the shape check (julc `has`).
pub fn has(
  self: MerklePatriciaForestry,
  key: ByteArray,
  value: ByteArray,
  proof: Proof,
) -> Bool {
  well_formed(blake2b_256(key), 0, proof) && mpf.has(self, key, value, proof)
}

/// `mpf.miss` behind both checks (julc `miss`).
pub fn miss(self: MerklePatriciaForestry, key: ByteArray, proof: Proof) -> Bool {
  let path = blake2b_256(key)
  and {
    well_formed(path, 0, proof),
    terminal_unambiguous(path, 0, proof),
    mpf.miss(self, key, proof),
  }
}

/// `mpf.insert` behind both checks: the julc shard's `miss` followed by
/// `including`. Fails on a malformed proof, as `mpf.insert` fails on a proof
/// that does not exclude the key from `self`.
pub fn insert(
  self: MerklePatriciaForestry,
  key: ByteArray,
  value: ByteArray,
  proof: Proof,
) -> MerklePatriciaForestry {
  let path = blake2b_256(key)
  expect well_formed(path, 0, proof)
  expect terminal_unambiguous(path, 0, proof)
  mpf.insert(self, key, value, proof)
}
