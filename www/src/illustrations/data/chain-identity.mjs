// What makes a ledger "this ledger": the values chosen at creation, where the
// runtime commits them, and where verifiers and Cardano pin them. Facts follow
// StateCommitmentIdentity, ConsensusContext, the anchor CDDL, and the
// capability manifest.

export default {
  id: 'chain-identity',
  type: 'diagram',
  title: 'What makes a ledger this ledger',
  tag: 'Concept',
  hint: 'Select a block to see what it binds and who checks it.',
  caption: 'The values chosen at creation are committed at height 1, bound into every block header and vote, and '
    + 'pinned by proofs and anchors. Change one, and the result is a different ledger. The capability manifest '
    + 'describes the ledger; it is not a trust root.',
  zones: [
    { id: 'genesis', kind: 'ledger', label: 'Chosen when the ledger is created', contains: ['profile', 'fingerprint', 'genesis-id', 'app-id'] },
  ],
  blocks: [
    {
      id: 'profile', label: 'Commitment profile', sub: 'mpf-blake2b256-v1', kind: 'core',
      detail: '`state.commitment-profile`: the authenticated-state structure, `mpf-blake2b256-v1` or '
        + '`jmt-blake2b256-v1`. Only MPF proofs can be checked on Cardano. Profile, fingerprint, and genesis id must be '
        + 'configured together, and none of them can change on a running ledger.',
    },
    {
      id: 'fingerprint', label: 'Format fingerprint', sub: '32-byte hash', kind: 'core',
      detail: '`state.format-fingerprint`: a Blake2b-256 hash of the profile\'s exact commitment and proof format. It '
        + 'must equal the fingerprint the selected profile computes, or the node refuses to start.',
    },
    {
      id: 'genesis-id', label: 'Genesis id', sub: '32 bytes, never reused', kind: 'core',
      detail: '`state.genesis-id`: 32 bytes that name this generation of the ledger. The runtime binds it to the '
        + 'application profile and reports the derived id in status, anchors, and the consensus context. Never '
        + 'regenerate a retained genesis id.',
    },
    {
      id: 'app-id', label: 'Application id', sub: 'state machine id', kind: 'core',
      detail: 'The configured `state-machine`, or the committed composite profile id. The anchor datum carries it, '
        + 'and the capability manifest names it too.',
    },
    {
      id: 'members', label: 'Membership', sub: 'members, threshold, f', kind: 'member',
      detail: 'The member keys, the threshold, and the fault bound at each height. In governed mode they change '
        + 'through finalized membership epochs; every check uses the membership at that height.',
    },
    {
      id: 'markers', label: 'Height-1 markers', sub: 'in the state root', kind: 'core',
      detail: 'At height 1 the runtime writes `~yano/state-commitment/v1`, `~yano/consensus-profile/v2`, and '
        + '`~yano/obs/profile/v1` into the authenticated state, and checks them at every later height. A member '
        + 'configured differently computes a different root at height 1, so it cannot vote for that history.',
    },
    {
      id: 'context', label: 'Consensus context', sub: 'digest in every block', kind: 'core',
      detail: 'A Blake2b-256 digest over the protocol version, chain id, genesis id, height, quorum (members, '
        + 'threshold, fault bound), sorted member keys, and the consensus, observer, and observation profile digests. Every '
        + 'block header carries it, every vote and certificate signs it, and a proposal with another digest is '
        + 'rejected.',
    },
    {
      id: 'proofs', label: 'Proof verifiers', sub: 'pin chain, genesis, root', kind: 'client',
      detail: 'A verifier pins the chain id, genesis id, commitment profile, fingerprint, height, and root, and for '
        + 'finality the consensus-context digest, all obtained independently. Never copy them from the response being '
        + 'verified.',
    },
    {
      id: 'anchor', label: 'Anchor datum', sub: '11 fields on Cardano', kind: 'cardano',
      detail: 'A script anchor\'s inline datum carries version, chain id, genesis id, application id, commitment '
        + 'profile, format fingerprint, height, block hash, state root, sorted member keys, and threshold. An '
        + 'advance must keep the chain, genesis, application, and commitment identity.',
    },
    {
      id: 'manifest', label: 'Capability manifest', sub: 'discovery data', kind: 'external',
      detail: 'Returned as `capabilityManifest` in the chain status, with a `manifestDigest`. It describes '
        + 'components, workflows, and proof subjects so tools can discover them. It is descriptive data, not a trust '
        + 'root: proofs still pin the chain, genesis, profile, and root.',
    },
  ],
  edges: [
    { from: 'genesis', to: 'markers', label: 'height 1' },
    { from: 'genesis', to: 'context' },
    { from: 'members', to: 'context' },
    { from: 'context', to: 'proofs' },
    { from: 'markers', to: 'anchor' },
    { from: 'markers', to: 'manifest', style: 'dashed' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 408,
      zones: { genesis: [16, 16, 728, 116] },
      blocks: {
        profile: [32, 56, 166, 60],
        fingerprint: [210, 56, 166, 60],
        'genesis-id': [388, 56, 166, 60],
        'app-id': [566, 56, 166, 60],
        markers: [126, 184, 220, 60],
        context: [414, 184, 220, 60],
        members: [566, 284, 178, 60],
        anchor: [32, 324, 220, 60],
        manifest: [270, 324, 130, 60],
        proofs: [414, 284, 140, 100],
      },
      labels: {
        proofs: { label: 'Proof\nverifiers', sub: 'pin chain,\ngenesis, root' },
        manifest: { label: 'Capability\nmanifest', sub: 'discovery data' },
      },
      edges: {
        'genesis->markers': { fromSide: 'b', toSide: 't', fromAt: [236, 132], toAt: [236, 184], labelAt: [270, 158] },
        'genesis->context': { fromSide: 'b', toSide: 't', fromAt: [524, 132], toAt: [524, 184] },
        'members->context': { fromSide: 't', toSide: 'r', fromAt: [655, 284], via: [[655, 214]], toAt: [634, 214] },
        'context->proofs': { fromSide: 'b', toSide: 't', fromAt: [484, 244], toAt: [484, 284] },
        'markers->anchor': { fromSide: 'b', toSide: 't', fromAt: [160, 244], via: [[160, 284], [142, 284]], toAt: [142, 324] },
        'markers->manifest': { fromSide: 'b', toSide: 't', fromAt: [310, 244], via: [[310, 284], [335, 284]], toAt: [335, 324] },
      },
    },
    narrow: {
      width: 380,
      height: 524,
      zones: { genesis: [8, 8, 364, 180] },
      blocks: {
        profile: [20, 44, 166, 60],
        fingerprint: [194, 44, 166, 60],
        'genesis-id': [20, 116, 166, 60],
        'app-id': [194, 116, 166, 60],
        markers: [20, 232, 166, 60],
        context: [194, 232, 166, 60],
        members: [194, 340, 166, 60],
        proofs: [194, 448, 166, 60],
        anchor: [20, 340, 166, 60],
        manifest: [20, 448, 166, 60],
      },
      labels: {
        markers: { label: 'Height-1\nmarkers', sub: '' },
        context: { label: 'Consensus\ncontext', sub: '' },
        proofs: { label: 'Proof verifiers', sub: 'pin chain, genesis,\nroot' },
        manifest: { label: 'Capability\nmanifest', sub: 'discovery data' },
      },
      edges: {
        'genesis->markers': { fromSide: 'b', toSide: 't', fromAt: [103, 188], toAt: [103, 232], label: false },
        'genesis->context': { fromSide: 'b', toSide: 't', fromAt: [277, 188], toAt: [277, 232] },
        'members->context': { fromSide: 't', toSide: 'b', fromAt: [277, 340], toAt: [277, 292] },
        'context->proofs': { fromSide: 'r', toSide: 'r', fromAt: [360, 262], via: [[370, 262], [370, 478]], toAt: [360, 478] },
        'markers->anchor': { fromSide: 'b', toSide: 't', fromAt: [103, 292], toAt: [103, 340] },
        'markers->manifest': { fromSide: 'l', toSide: 'l', fromAt: [20, 262], via: [[12, 262], [12, 478]], toAt: [20, 478] },
      },
    },
  },
  legend: [['core', 'identity value'], ['member', 'membership'], ['client', 'verifier'], ['cardano', 'Cardano'],
    ['external', 'discovery only']],
  sources: [
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/state/StateCommitmentIdentity.java',
      anchors: ['"state.commitment-profile"', '"state.format-fingerprint"', '"state.genesis-id"',
        'state commitment profile, fingerprint, and genesis id must be configured together',
        'state commitment format fingerprint does not match selected profile', '"~yano/state-commitment/v1"',
        'L1 state-proof consumption is supported only by MPF'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/consensus/ConsensusContext.java',
      anchors: ['Canonical identity bound into every certified-consensus signature', 'yano-appchain-consensus-context'] },
    { repo: 'yano', path: 'core-api/src/main/cddl/appchain/anchor-v1.cddl',
      anchors: ['chain-genesis-id : bstr .size 32', 'application-id : bstr .size (1..128)',
        'commitment-profile-id : bstr .size (1..128)', 'format-fingerprint : bstr .size 32',
        'member-keys  : [ 1*32 bstr .size 32 ]', 'threshold    : uint'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/AppChainConsensusProfileCommitment.java',
      anchors: ['"~yano/consensus-profile/v2"'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/observation/ObservationKeys.java',
      anchors: ['"~yano/obs/profile/v1"'] },
    { repo: 'yano', path: 'appchain/onchain/appchain-anchor-onchain/src/main/java/org/yanoproject/appchain/anchor/onchain/AnchorValidator.java',
      anchors: ['sameGenesis', 'sameApplication', 'sameCommitment'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/AppCapabilityManifest.java',
      anchors: ['Immutable v1 discovery description for one application profile.'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['commits genesis, membership, quorum, and consensus/observer profiles'] },
    { repo: 'yano-x', path: 'adr/app-layer/033-showcase-reference-catalog-and-capability-discovery.md',
      anchors: ['It is descriptive discovery data, not a new trust root.'] },
    { repo: 'yano-x', path: 'AGENTS.md',
      anchors: ['`(commitment-profile, format-fingerprint, genesis-id)` is a pinned chain identity.'] },
  ],
};
