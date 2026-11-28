//// Tests of `yano_x/state_proof`, the port of the julc `MpfOnChainVerifier`,
//// on proofs the Yano mpf-blake2b256-v1 trie produces (`StateTrie` in the
//// module's Java tests): inclusion proofs in a 1-leaf and a 40-leaf trie, and
//// absence proofs whose terminal is an empty slot, another leaf and a branch
//// whose prefix leaves the key's path. Changed proofs and roots are refused,
//// and so is an absence proof of a present key. The differential suites
//// compare the port with the julc verifier on many more proofs.

use aiken/builtin
use aiken/cbor
use yano_x/state_proof

const root_40: ByteArray =
  #"ef0406273cb72c890fec9a400cdc2be91d1c07fd699538d47f5acd76f1344f9a"

const root_1: ByteArray =
  #"fe1536eafda93d5b8bfa8fc019ccd156c3734a1d93feadf09f483e427bda0f70"

const root_branch: ByteArray =
  #"c4b0410f8d12e2ff90b63add2c7a0acd68bad1071790bb12b263bf6b46be3653"

/// CBOR of the proof.
const proof_incl7: ByteArray =
  #"d8799f4c61702f6163636f756e742f375820be7e832d82ec040ae6f74c3985297eeac3d4543ac4e55a36f1c1a91291ae5da75820000e832d82ec040ae6f74c3985297eeac3d4543ac4e55a36f1c1a91291ae5da79fd8799f0240075820fd7ba2f78cd85bc42d3675ace2e618ec4f03ac704b0110d06c5f7f297bc6e353582085c09af929492a871e4fae32d9d5c36e352471cd659bcdb61de08f1722acc3b158200eb923b0cbd24df54401d998531feead35a47a99f4deed205de4af81120f97615820000000000000000000000000000000000000000000000000000000000000000040ffd8799f01400e5820114dc6f32f10b089f7c7f84edde5103c61d369ac36bae5b1f21b5f2c98719453582030affaeb023a481922b6596ddaac60b448d5823df303756175f4edf6686e1ec558205cbf6cd5df249ce3f88e0aeb09ddad83e5e00dbeb5e3b37f27cc1ea0fd03043e5820000000000000000000000000000000000000000000000000000000000000000040ffd8799f00400b5820485b915b5e9cce1a659894f30fae496288a06f1ff32f71fd55d4632b8f7d968858200c7f33220cf8d614c556a8ec0f087e4a989ac2df02025b7a2e7978dfb0608f3a5820f31c0579d399e7338be09e9446bb5ea574d92e864d7bcaa72379f9d419ab6b4a5820b728b2745585fd798a442f3a5e13d863b75ba0855e3c07c2cdc64ed29b27c0d440ffffff"

/// CBOR of the proof.
const proof_incl31: ByteArray =
  #"d8799f4d61702f6163636f756e742f33315820bcea238f5914b4836ce25eaa727f74adc47f35b7a638f9c26b3c2ecafa530e975820ffea238f5914b4836ce25eaa727f74adc47f35b7a638f9c26b3c2ecafa530e979fd8799f01400c5820114dc6f32f10b089f7c7f84edde5103c61d369ac36bae5b1f21b5f2c98719453582030affaeb023a481922b6596ddaac60b448d5823df303756175f4edf6686e1ec558200e5f8a9e260be7266f3a080bb9bca4cb33849ff0da8ff55813c6c9acc3b18f7f582046a21eabaab8f58ec64c2638d97b6daea8edcaf605630bba0d1df316ee256f2b40ffd8799f00400b5820485b915b5e9cce1a659894f30fae496288a06f1ff32f71fd55d4632b8f7d968858200c7f33220cf8d614c556a8ec0f087e4a989ac2df02025b7a2e7978dfb0608f3a5820f31c0579d399e7338be09e9446bb5ea574d92e864d7bcaa72379f9d419ab6b4a5820b728b2745585fd798a442f3a5e13d863b75ba0855e3c07c2cdc64ed29b27c0d440ffffff"

/// CBOR of the proof.
const proof_incl1: ByteArray =
  #"d8799f4c61702f6163636f756e742f305820446bce8c325ec3612b10749084b2d074eacb99b42fd10e8e5d812520df3b6d195821ff446bce8c325ec3612b10749084b2d074eacb99b42fd10e8e5d812520df3b6d1980ff"

/// CBOR of the proof.
const proof_abs0: ByteArray =
  #"d8799f4c61702f6465706f7369742f30405821000e24b2ecf2e014ce17a5d2a1c10876ffe7b1a1d75f291b452a0f2ea1f0298dca9fd8799f00400e5820485b915b5e9cce1a659894f30fae496288a06f1ff32f71fd55d4632b8f7d9688582091c9eceb481d3b47414742eace36a1618ecac8769d0426824ca5b2ea9732f8d3582047c9d36e2547165ee8a16d8eef2f9a01fa55e4551fc676d064cf43e0bdd05c585820000000000000000000000000000000000000000000000000000000000000000040ffff015820ee24b2ecf2e014ce17a5d2a1c10876ffe7b1a1d75f291b452a0f2ea1f0298dca5820f72afaf1b42191ea9278dea11d1b4b9580e58ef6cf633146e67fa9662ccb4aa2ff"

/// CBOR of the proof.
const proof_abs1: ByteArray =
  #"d8799f4c61702f6465706f7369742f30405821ff446bce8c325ec3612b10749084b2d074eacb99b42fd10e8e5d812520df3b6d1980005820446bce8c325ec3612b10749084b2d074eacb99b42fd10e8e5d812520df3b6d1958202fc3e593fcac13655d0cd2903e8397dfb6fabeaa11f1f0f6566f610e5b18c1aaff"

/// CBOR of the proof.
const proof_abs2: ByteArray =
  #"d8799f4c61702f6465706f7369742f3240409fd8799f01400c5820b22df1a126b5ba4e33c16fd6157507610e55ffce20dae7ac44cae168a463612a5820f0817d8176acdb66f049d67c2f9f384db12b87d87b02ca12f3b46b559a49702158200eb923b0cbd24df54401d998531feead35a47a99f4deed205de4af81120f97615820000000000000000000000000000000000000000000000000000000000000000040ffd8799f00400c5820485b915b5e9cce1a659894f30fae496288a06f1ff32f71fd55d4632b8f7d9688582091c9eceb481d3b47414742eace36a1618ecac8769d0426824ca5b2ea9732f8d35820e2b17724e9166a4d03b352532af2172c92dd41f2f32793b3f5ae31fb5da1775258209ee4b11c88dae08393e20214fbf11b2e1f41b8ccc472f89083555fece97f8b6740ffff024040ff"

/// CBOR of the proof.
const proof_abs3: ByteArray =
  #"d8799f4c61702f6465706f7369742f3340409fd8799f01400b5820114dc6f32f10b089f7c7f84edde5103c61d369ac36bae5b1f21b5f2c9871945358202db7d5ddce6242eaecbf6e9248e4e566cf49821177e0509acba55e2ab5f9066458200031ad4560e64895b35fcde5a8b13a9909655fa51479c95ca57fb6f9981516855820000000000000000000000000000000000000000000000000000000000000000040ffd8799f00400b5820485b915b5e9cce1a659894f30fae496288a06f1ff32f71fd55d4632b8f7d968858200c7f33220cf8d614c556a8ec0f087e4a989ac2df02025b7a2e7978dfb0608f3a5820f31c0579d399e7338be09e9446bb5ea574d92e864d7bcaa72379f9d419ab6b4a5820b728b2745585fd798a442f3a5e13d863b75ba0855e3c07c2cdc64ed29b27c0d440ffff024040ff"

/// CBOR of the proof.
const proof_abs4: ByteArray =
  #"d8799f4c61702f6465706f7369742f3440409fd8799f014005582099638e71d04bca530aa7500bf7a7c7cc44168d4dea0f195a36e5658d07b0c04d58201a612c1440ce50dc4d8156888130fbaddf676db0d04c051ed9be9ae49d06fb3258201976c4504b85cb34bf0acfc824ce751de24043ee1496a14a256369d8d3e11dba5820000000000000000000000000000000000000000000000000000000000000000040ffd8799f00400d5820485b915b5e9cce1a659894f30fae496288a06f1ff32f71fd55d4632b8f7d9688582091c9eceb481d3b47414742eace36a1618ecac8769d0426824ca5b2ea9732f8d35820e2b17724e9166a4d03b352532af2172c92dd41f2f32793b3f5ae31fb5da17752582069fc9ca119fd68d69a0c6cba0f83d52ede56e35df2be3f7886b64e7fc798021740ffff024040ff"

/// CBOR of the proof.
const proof_absb: ByteArray =
  #"d8799f5161702f6465706f7369742f6272616e63684041079fd8799f00400a5820a3b0fbab29c08ec3d51aff765e30d76c6c0faf343e40ce39042938ed078a485d5820e1bcca12ea411ec47f5d054cd8584dbe9400de0dfa3c925de7541148c4faa77d58203a156094ed738a188b845186836c7575ef666f6223ea72084f1b363e3ca61add5820447d2eff8b7e8109b2e0270fa7d15c6e309e517ca975b1f22c58ac7f7de66e4940ffff01405820252d9bca04e36df99e612829d4cb8e2bc44094d3d63d0a8070728c484453cdbeff"

/// CBOR of the proof.
const proof_present: ByteArray =
  #"d8799f4c61702f6163636f756e742f37405820000e832d82ec040ae6f74c3985297eeac3d4543ac4e55a36f1c1a91291ae5da79fd8799f0240075820fd7ba2f78cd85bc42d3675ace2e618ec4f03ac704b0110d06c5f7f297bc6e353582085c09af929492a871e4fae32d9d5c36e352471cd659bcdb61de08f1722acc3b158200eb923b0cbd24df54401d998531feead35a47a99f4deed205de4af81120f97615820000000000000000000000000000000000000000000000000000000000000000040ffd8799f01400e5820114dc6f32f10b089f7c7f84edde5103c61d369ac36bae5b1f21b5f2c98719453582030affaeb023a481922b6596ddaac60b448d5823df303756175f4edf6686e1ec558205cbf6cd5df249ce3f88e0aeb09ddad83e5e00dbeb5e3b37f27cc1ea0fd03043e5820000000000000000000000000000000000000000000000000000000000000000040ffd8799f00400b5820485b915b5e9cce1a659894f30fae496288a06f1ff32f71fd55d4632b8f7d968858200c7f33220cf8d614c556a8ec0f087e4a989ac2df02025b7a2e7978dfb0608f3a5820f31c0579d399e7338be09e9446bb5ea574d92e864d7bcaa72379f9d419ab6b4a5820b728b2745585fd798a442f3a5e13d863b75ba0855e3c07c2cdc64ed29b27c0d440ffff035820be7f832d82ec040ae6f74c3985297eeac3d4543ac4e55a36f1c1a91291ae5da75820cde925ce656dee5229a112748ada7ee24791f91d61093717afabf91e2d7cd1aaff"

fn proof(bytes: ByteArray) -> Data {
  expect Some(data) = cbor.deserialise(bytes)
  data
}

/// The proof with field `index` replaced.
fn with_field(data: Data, index: Int, value: Data) -> Data {
  builtin.constr_data(
    builtin.unconstr_index(data),
    replace(builtin.unconstr_fields(data), index, value),
  )
}

fn replace(items: List<Data>, index: Int, value: Data) -> List<Data> {
  when items is {
    [] -> []
    [item, ..rest] ->
      if index == 0 {
        [value, ..rest]
      } else {
        [item, ..replace(rest, index - 1, value)]
      }
  }
}

fn field(data: Data, index: Int) -> Data {
  do_field(builtin.unconstr_fields(data), index)
}

fn do_field(items: List<Data>, index: Int) -> Data {
  if index == 0 {
    builtin.head_list(items)
  } else {
    do_field(builtin.tail_list(items), index - 1)
  }
}

fn flipped(bytes: ByteArray) -> ByteArray {
  builtin.cons_bytearray(
    ( builtin.index_bytearray(bytes, 0) + 1 ) % 256,
    builtin.slice_bytearray(1, builtin.length_of_bytearray(bytes) - 1, bytes),
  )
}

test honest_inclusion_proofs_verify() {
  and {
    state_proof.verify_inclusion(proof(proof_incl7), root_40),
    state_proof.verify_inclusion(proof(proof_incl31), root_40),
    state_proof.verify_inclusion(proof(proof_incl1), root_1),
  }
}

test inclusion_refuses_another_root_value_or_key() {
  let p = proof(proof_incl7)
  and {
    !state_proof.verify_inclusion(p, root_1),
    !state_proof.verify_inclusion(p, flipped(root_40)),
    !state_proof.verify_inclusion(p, #""),
    !state_proof.verify_inclusion(
      with_field(p, 1, builtin.b_data("x")),
      root_40,
    ),
    !state_proof.verify_inclusion(
      with_field(p, 0, builtin.b_data("ap/account/8")),
      root_40,
    ),
    !state_proof.verify_inclusion(
      with_field(p, 3, builtin.list_data([])),
      root_40,
    ),
    !state_proof.verify_inclusion(
      with_field(p, 2, builtin.b_data(#"")),
      root_40,
    ),
  }
}

test inclusion_reads_records_positionally() {
  // Constructor tags and extra fields are not read, as in the julc verifier.
  let p = proof(proof_incl7)
  state_proof.verify_inclusion(
    builtin.constr_data(
      5,
      [field(p, 0), field(p, 1), field(p, 2), field(p, 3), builtin.i_data(0)],
    ),
    root_40,
  )
}

test inclusion_fails_on_a_wrong_data_kind() fail {
  state_proof.verify_inclusion(
    with_field(proof(proof_incl7), 1, builtin.i_data(1)),
    root_40,
  )
}

test honest_absence_proofs_verify() {
  and {
    // Another leaf.
    state_proof.verify_absence(proof(proof_abs0), root_40),
    state_proof.verify_absence(proof(proof_abs1), root_1),
    // Empty slots.
    state_proof.verify_absence(proof(proof_abs2), root_40),
    state_proof.verify_absence(proof(proof_abs3), root_40),
    state_proof.verify_absence(proof(proof_abs4), root_40),
    // A branch whose prefix leaves the key's path.
    state_proof.verify_absence(proof(proof_absb), root_branch),
  }
}

test absence_does_not_read_the_value_field() {
  state_proof.verify_absence(
    with_field(proof(proof_abs2), 1, builtin.list_data([])),
    root_40,
  )
}

test absence_refuses_a_present_key() {
  !state_proof.verify_absence(proof(proof_present), root_40)
}

test absence_refuses_changed_proofs() {
  let leaf = proof(proof_abs0)
  let slot = proof(proof_abs2)
  let branch = proof(proof_absb)
  let cursor = builtin.un_i_data(field(slot, 4))
  and {
    !state_proof.verify_absence(leaf, flipped(root_40)),
    !state_proof.verify_absence(slot, root_1),
    !state_proof.verify_absence(
      with_field(slot, 4, builtin.i_data(cursor + 1)),
      root_40,
    ),
    !state_proof.verify_absence(
      with_field(
        leaf,
        6,
        builtin.b_data(flipped(builtin.un_b_data(field(leaf, 6)))),
      ),
      root_40,
    ),
    !state_proof.verify_absence(
      with_field(leaf, 5, builtin.b_data(#"")),
      root_40,
    ),
    !state_proof.verify_absence(
      with_field(
        branch,
        6,
        builtin.b_data(flipped(builtin.un_b_data(field(branch, 6)))),
      ),
      root_branch,
    ),
    !state_proof.verify_absence(
      with_field(slot, 0, builtin.b_data("ap/account/7")),
      root_40,
    ),
  }
}
