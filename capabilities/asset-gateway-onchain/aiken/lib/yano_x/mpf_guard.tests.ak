//// Tests of `yano_x/mpf_guard`: honest proofs pass the shape checks and the
//// library call behind them unchanged, and each shape rule rejects a proof
//// that breaks it. The fruit fixtures are the aiken-lang/merkle-patricia-forestry
//// v2.1.0 test vectors (`merkle-patricia-forestry.tests.ak`).

use aiken/builtin.{
  blake2b_256, cons_bytearray, index_bytearray, slice_bytearray,
}
use aiken/merkle_patricia_forestry.{
  Branch, Fork, Leaf, MerklePatriciaForestry, Neighbor, Proof,
} as mpf
use aiken/primitive/bytearray
use yano_x/mpf_guard

const trie: MerklePatriciaForestry =
  mpf.from_root(
    #"4acd78f345a686361df77541b2e0b533f53362e36620a1fdd3a13e0b61a3b078",
  )

const apricot = "apricot[uid: 0]"

const proof_apricot: Proof =
  [
    Branch {
      skip: 0,
      neighbors: #"4be28f4839135e1f8f5372a90b54bb7bfaf997a5d13711bb4d7d93f9d4e04fbe280ada5ef30d55433934bbc73c89d550ee916f62822c34645e04bb66540c120f965c07fa815b86794e8703cee7e8f626c88d7da639258d2466aae67d5d041c5a117abf0e19fb78e0535891d82e5ece1310a1cf11674587dbba304c395769a988",
    },
  ]

const without_apricot: MerklePatriciaForestry =
  mpf.from_root(
    #"c08452d768160cd0fcdf5cad3d181cd36055eaf364d0eb7c49a01936bacf7b1f",
  )

const banana = "banana[uid: 218]"

const proof_banana: Proof =
  [
    Branch {
      skip: 0,
      neighbors: #"c7bfa4472f3a98ebe0421e8f3f03adf0f7c4340dec65b4b92b1c9f0bed209eb45fdf82687b1ab133324cebaf46d99d49f92720c5ded08d5b02f57530f2cc5a5fcf22cbaac4ab605dd13dbde57080661b53d8a7e23534c733acf50125cf0e5bcac9431d708d20021f1fa3f4f03468b8de194398072a402e7877376d06f747575a",
    },
    Leaf {
      skip: 1,
      key: #"3ed002d6885ab5d92e1307fccd1d021c32ec429192aea10cb2fd688b92aef3ac",
      value: #"7c3715aba2db74d565a6ce6cc72f20d9cb4652ddb29efe6268be15b105e40911",
    },
  ]

const without_banana: MerklePatriciaForestry =
  mpf.from_root(
    #"557990b1257679f2b8e09c507f2582b0566579a2fc26d0d8a6b59a4a88ef16db",
  )

const kumquat = "kumquat[uid: 0]"

const proof_kumquat: Proof =
  [
    Branch {
      skip: 0,
      neighbors: #"c7bfa4472f3a98ebe0421e8f3f03adf0f7c4340dec65b4b92b1c9f0bed209eb47238ba5d16031b6bace4aee22156f5028b0ca56dc24f7247d6435292e82c039c3490a825d2e8deddf8679ce2f95f7e3a59d9c3e1af4a49b410266d21c9344d6d08434fd717aea47d156185d589f44a59fc2e0158eab7ff035083a2a66cd3e15b",
    },
    Fork {
      skip: 0,
      neighbor: Neighbor {
        nibble: 0,
        prefix: #"07",
        root: #"a1ffbc0e72342b41129e2d01d289809079b002e54b123860077d2d66added281",
      },
    },
  ]

const without_kumquat: MerklePatriciaForestry =
  mpf.from_root(
    #"4dd6d57ca8cb7ac8c3b219366754a392ba9e4e43b6b3ae59d89be3f878ba8fb6",
  )

const coconut = "coconut[uid: 0]"

const proof_coconut: Proof =
  [
    Branch {
      skip: 0,
      neighbors: #"4be28f4839135e1f8f5372a90b54bb7bfaf997a5d13711bb4d7d93f9d4e04fbe280ada5ef30d55433934bbc73c89d550ee916f62822c34645e04bb66540c120f323def78732eace937391fc626efcd062552ebcf5e93f00352b86cb0f89daca0a22a7b4d767ada48673a4a9313a02a35ff47d2f55bcf10ae294127f590a4327c",
    },
    Leaf {
      skip: 0,
      key: #"df779e7f171b7299c2cede28bb898c1ee3456d98657b95e8082cd375704b678a",
      value: #"9e3d695f13a7292b8859d2ba0113e305825a8af8ba886d2ae73e73f2d35c6afe",
    },
  ]

const without_coconut: MerklePatriciaForestry =
  mpf.from_root(
    #"4888f3b72e475510bc0bb78c5f3706c0520a4294a41f8c05b5561776369d9d5d",
  )

// ---- honest proofs ------------------------------------------------------------

test honest_proofs_verify() {
  and {
    mpf_guard.has(trie, apricot, "🤷", proof_apricot),
    mpf_guard.has(trie, banana, "🍌", proof_banana),
    mpf_guard.has(trie, kumquat, "🤷", proof_kumquat),
    mpf_guard.has(trie, coconut, "🥥", proof_coconut),
  }
}

test honest_exclusions_verify() {
  and {
    mpf_guard.miss(without_apricot, apricot, proof_apricot),
    mpf_guard.miss(without_banana, banana, proof_banana),
    mpf_guard.miss(without_kumquat, kumquat, proof_kumquat),
    mpf_guard.miss(without_coconut, coconut, proof_coconut),
  }
}

test honest_inserts_reproduce_the_full_trie() {
  and {
    mpf_guard.insert(without_apricot, apricot, "🤷", proof_apricot) == trie,
    mpf_guard.insert(without_banana, banana, "🍌", proof_banana) == trie,
    mpf_guard.insert(without_kumquat, kumquat, "🤷", proof_kumquat) == trie,
    mpf_guard.insert(without_coconut, coconut, "🥥", proof_coconut) == trie,
  }
}

test an_empty_proof_excludes_any_key_from_the_empty_trie() {
  and {
    mpf_guard.miss(mpf.empty, "", []),
    mpf_guard.miss(mpf.empty, apricot, []),
    mpf_guard.miss(
      mpf.empty,
      #"0000000000000000000000000000000000000000000000000000000000000000",
      [],
    ),
  }
}

test a_present_key_is_not_excluded_by_its_inclusion_proof() {
  !mpf_guard.miss(trie, apricot, proof_apricot)
}

// ---- well_formed ----------------------------------------------------------------

const branch_apricot =
  Branch {
    skip: 0,
    neighbors: #"4be28f4839135e1f8f5372a90b54bb7bfaf997a5d13711bb4d7d93f9d4e04fbe280ada5ef30d55433934bbc73c89d550ee916f62822c34645e04bb66540c120f965c07fa815b86794e8703cee7e8f626c88d7da639258d2466aae67d5d041c5a117abf0e19fb78e0535891d82e5ece1310a1cf11674587dbba304c395769a988",
  }

const some_hash =
  #"a1ffbc0e72342b41129e2d01d289809079b002e54b123860077d2d66added281"

test a_leaf_step_on_the_key_own_path_is_rejected() {
  let proof =
    [
      branch_apricot,
      Leaf { skip: 0, key: blake2b_256(apricot), value: some_hash },
    ]
  and {
    !mpf_guard.well_formed(blake2b_256(apricot), 0, proof),
    !mpf_guard.miss(trie, apricot, proof),
  }
}

test a_fork_prefix_of_raw_bytes_is_rejected() {
  let path = blake2b_256(apricot)
  let prefix =
    cons_bytearray(index_bytearray(path, 0) % 16, slice_bytearray(1, 31, path))
  let proof =
    [
      branch_apricot,
      Fork {
        skip: 0,
        neighbor: Neighbor { nibble: 0, prefix, root: some_hash },
      },
    ]
  and {
    !mpf_guard.well_formed(path, 0, proof),
    !mpf_guard.miss(trie, apricot, proof),
  }
}

test a_branch_needs_128_bytes_of_neighbors() {
  let proof = [Branch { skip: 0, neighbors: #"00" }]
  !mpf_guard.well_formed(blake2b_256(apricot), 0, proof)
}

test a_skip_past_the_path_is_rejected() {
  let path = blake2b_256(apricot)
  and {
    !mpf_guard.well_formed(
      path,
      0,
      [Branch { skip: 64, neighbors: bytearray.from_int_big_endian(0, 128) }],
    ),
    !mpf_guard.well_formed(
      path,
      0,
      [Branch { skip: -1, neighbors: bytearray.from_int_big_endian(0, 128) }],
    ),
  }
}

test a_fork_neighbor_must_end_above_the_leaf_level() {
  let path = blake2b_256(apricot)
  // A fork at nibble 62: its neighbor branch ends at 62 + 1 + length(prefix).
  let other = ( index_bytearray(path, 31) / 16 + 1 ) % 16
  let at_62 =
    fn(prefix) {
      [
        Fork {
          skip: 62,
          neighbor: Neighbor { nibble: other, prefix, root: some_hash },
        },
      ]
    }
  and {
    mpf_guard.well_formed(path, 0, at_62(#"")),
    !mpf_guard.well_formed(path, 0, at_62(#"00")),
  }
}

test a_fork_neighbor_on_the_path_own_nibble_is_rejected() {
  let path = blake2b_256(apricot)
  let own = index_bytearray(path, 0) / 16
  !mpf_guard.well_formed(
    path,
    0,
    [
      Fork {
        skip: 0,
        neighbor: Neighbor { nibble: own, prefix: #"", root: some_hash },
      },
    ],
  )
}

test a_leaf_value_must_be_32_bytes() {
  let path = blake2b_256(apricot)
  let key =
    cons_bytearray(
      ( index_bytearray(path, 0) + 16 ) % 256,
      slice_bytearray(1, 31, path),
    )
  and {
    mpf_guard.well_formed(path, 0, [Leaf { skip: 0, key, value: some_hash }]),
    !mpf_guard.well_formed(path, 0, [Leaf { skip: 0, key, value: #"00" }]),
  }
}

// ---- terminal_unambiguous -------------------------------------------------------

test a_terminal_leaf_at_an_odd_cursor_needs_a_raw_byte_above_15() {
  let path = blake2b_256(apricot)
  let low = bytearray.from_int_big_endian(1, 31)
  let high = bytearray.push(bytearray.from_int_big_endian(0, 30), 16)
  and {
    !mpf_guard.terminal_unambiguous(
      path,
      1,
      [Leaf { skip: 0, key: cons_bytearray(0, low), value: some_hash }],
    ),
    mpf_guard.terminal_unambiguous(
      path,
      1,
      [Leaf { skip: 0, key: cons_bytearray(0, high), value: some_hash }],
    ),
    // At an even cursor the leaf and branch encodings cannot meet.
    mpf_guard.terminal_unambiguous(
      path,
      2,
      [Leaf { skip: 0, key: cons_bytearray(0, low), value: some_hash }],
    ),
  }
}

test a_terminal_fork_at_an_odd_cursor_must_not_have_the_odd_leaf_length() {
  let path = blake2b_256(apricot)
  // At cursor 1 the odd-leaf length is 2 + 62 / 2 = 33 nibbles: skip + 1 + prefix.
  let fork =
    fn(nibble, prefix) {
      [Fork { skip: 0, neighbor: Neighbor { nibble, prefix, root: some_hash } }]
    }
  let prefix32 = bytearray.from_int_big_endian(1, 32)
  let prefix31 = bytearray.from_int_big_endian(1, 31)
  and {
    !mpf_guard.terminal_unambiguous(path, 1, fork(0, prefix32)),
    mpf_guard.terminal_unambiguous(path, 1, fork(1, prefix32)),
    mpf_guard.terminal_unambiguous(path, 1, fork(0, prefix31)),
    mpf_guard.terminal_unambiguous(path, 2, fork(0, prefix32)),
  }
}

test a_shape_check_failure_fails_the_insert() fail {
  let proof =
    [
      branch_apricot,
      Leaf { skip: 0, key: blake2b_256(apricot), value: some_hash },
    ]
  mpf.root(mpf_guard.insert(without_apricot, apricot, "🤷", proof)) == #""
}
