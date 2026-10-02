// The fields of a v3 app block: what each binds and which check uses it.
// Facts follow Yano's AppBlock, AppBlockCodec, ConsensusContext, and the
// follower checks in AppChainEngine; see `sources`.

const FIELD_W = 162;
const NARROW_W = 164;
const WIDE_COLS = [32, 210, 388, 566];
const WIDE_ROWS = [50, 116, 182];
const NARROW_COLS = [20, 196];
const NARROW_ROWS = [42, 104, 166, 228, 290, 352];

// Header fields in display order: four columns wide, two columns narrow.
const WIDE_ORDER = [
  'version', 'chain-id', 'height', 'context',
  'view', 'prev-hash', 'l1-ref', 'timestamp',
  'messages-root', 'state-root', 'proposer', 'justification',
];
const NARROW_ORDER = [
  'version', 'chain-id', 'height', 'context',
  'view', 'prev-hash', 'l1-ref', 'timestamp',
  'proposer', 'justification', 'messages-root', 'state-root',
];

const grid = (order, cols, rows, width, height) => Object.fromEntries(order.map((id, i) =>
  [id, [cols[i % cols.length], rows[Math.floor(i / cols.length)], width, height]]));

export default {
  id: 'block-anatomy',
  type: 'diagram',
  title: 'Anatomy of an app block',
  tag: 'Concept',
  hint: 'Select a field to see what it binds and which check uses it.',
  caption: 'A version-3 app block. The block hash is blake2b-256 over the header fields, the proposer, and a digest '
    + 'of the justification. Messages are bound through `messagesRoot`; only the finality certificate is outside '
    + 'the hash.',
  zones: [
    {
      id: 'hash', kind: 'ledger', label: 'Header · covered by the block hash',
      contains: WIDE_ORDER,
    },
    { id: 'body', kind: 'member', label: 'Body', contains: ['messages'] },
    { id: 'outside', kind: 'final', label: 'Outside the hash', contains: ['cert'] },
  ],
  blocks: [
    {
      id: 'version', label: 'Format version', sub: 'version = 3', kind: 'core',
      detail: 'The block format version, currently 3. A follower rejects any other version before it checks '
        + 'anything else.',
    },
    {
      id: 'chain-id', label: 'Chain id', sub: 'chainId', kind: 'core',
      detail: 'The ledger’s `chain-id`. A block for another chain is rejected. The chain id is also part of the '
        + 'consensus context that every vote signs.',
    },
    {
      id: 'height', label: 'Height', sub: 'height', kind: 'core',
      detail: 'Starts at 1. A follower votes only for its tip height plus one. A block from further ahead waits and '
        + 'is retried; a height that is already final is ignored.',
    },
    {
      id: 'context', label: 'Consensus context', sub: 'consensusContextDigest', kind: 'core',
      detail: 'A digest of the chain id, genesis id, height, sorted member keys, `n`, `t`, `f`, and the consensus, '
        + 'observer, and observation profile digests. A follower requires it to equal the context it computes '
        + 'itself, and every PREPARE, COMMIT, and TIMEOUT signs it, so a vote cannot be reused for another chain, '
        + 'height, or membership. With a rotating sequencer it also seeds the view-0 leader, together with the '
        + 'previous block hash.',
    },
    {
      id: 'view', label: 'View', sub: 'view', kind: 'core',
      detail: '0 in a normal round, higher after a certified view change. The proposer must be the leader for this '
        + 'height and view.',
    },
    {
      id: 'prev-hash', label: 'Previous block', sub: 'prevHash', kind: 'core',
      detail: 'The hash of the previous block, or 32 zero bytes at height 1. It must equal the follower’s own tip '
        + 'hash, which chains every block to the history before it.',
    },
    {
      id: 'l1-ref', label: 'L1 reference', sub: 'l1Slot, l1BlockHash', kind: 'core',
      detail: 'A stable Cardano slot and block hash. They are 0 and empty unless `l1.stability-depth` is above 0. '
        + 'Then each follower checks them against its own view of Cardano, and slots must not decrease.',
    },
    {
      id: 'timestamp', label: 'Timestamp', sub: 'timestamp (ms)', kind: 'core',
      detail: 'The proposer’s clock in milliseconds when it built the block, identical for every member because it '
        + 'is in the block. It is the only time a state machine may use. Followers do not compare it with their '
        + 'own clocks, so it is only as accurate as the proposer’s.',
    },
    {
      id: 'messages-root', label: 'Messages root', sub: 'messagesRoot', kind: 'core',
      detail: 'A binary blake2b-256 Merkle root over the ordered message ids. A follower recomputes it from the '
        + 'messages. A message inclusion proof checks a message id against it.',
    },
    {
      id: 'state-root', label: 'State root', sub: 'stateRoot', kind: 'core',
      detail: 'The root of the authenticated state after applying this block. A follower re-executes the block and '
        + 'requires the same root, byte for byte. State proofs and Cardano anchors refer to it.',
      link: { label: 'State and proofs', href: '/concepts/state-and-proofs/' },
    },
    {
      id: 'proposer', label: 'Proposer', sub: 'proposer', kind: 'core',
      detail: 'The leader’s Ed25519 public key. It must equal the key that signed the proposal envelope, and the '
        + 'leader for this height and view.',
    },
    {
      id: 'justification', label: 'Justification', sub: 'justification digest', kind: 'core',
      detail: 'Empty in view 0. In a higher view it holds the new-view certificate, a threshold of signed TIMEOUTs. '
        + 'The block hash covers its digest.',
    },
    {
      id: 'messages', label: 'Messages', sub: 'bound by messagesRoot', kind: 'core',
      detail: 'The ordered message envelopes, each signed by the member that accepted it. The block hash covers '
        + '`messagesRoot`, not the messages themselves. Every message must be unexpired and signed by a member at '
        + 'this height.',
    },
    {
      id: 'cert', label: 'Finality certificate', sub: 'cert', kind: 'final',
      detail: 'Ed25519 COMMIT signatures from a threshold of members. Each signs a digest of the height, view, '
        + 'context, block hash, and value hash. The certificate sits outside the block hash so it can be attached '
        + 'after finality. A verifier requires every signer to be a member at that height, no duplicates, and every '
        + 'signature valid: one bad entry voids the whole certificate.',
    },
  ],
  edges: [
    { from: 'messages', to: 'messages-root', label: 'Merkle root of ids' },
    { from: 'cert', to: 'hash', label: 'signs the hash' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 384,
      zones: {
        hash: [16, 16, 728, 236],
        body: [16, 276, 356, 92],
        outside: [388, 276, 356, 92],
      },
      blocks: {
        ...grid(WIDE_ORDER, WIDE_COLS, WIDE_ROWS, FIELD_W, 54),
        messages: [32, 308, 324, 46],
        cert: [404, 308, 324, 46],
      },
      edges: {
        'messages->messages-root': { fromSide: 't', toSide: 'b', fromAt: [113, 308], toAt: [113, 236],
          labelAt: [113, 264] },
        'cert->hash': { fromSide: 't', toSide: 'b', fromAt: [566, 308], toAt: [566, 252], labelAt: [566, 264] },
      },
    },
    narrow: {
      width: 380,
      height: 540,
      labels: {
        messages: { sub: 'via messagesRoot' },
        cert: { label: 'Certificate' },
      },
      zones: {
        hash: [8, 8, 364, 408],
        body: [8, 440, 176, 92],
        outside: [196, 440, 176, 92],
      },
      blocks: {
        ...grid(NARROW_ORDER, NARROW_COLS, NARROW_ROWS, NARROW_W, 52),
        messages: [20, 472, 152, 46],
        cert: [208, 472, 152, 46],
      },
      edges: {
        'messages->messages-root': { fromSide: 't', toSide: 'b', fromAt: [102, 472], toAt: [102, 404],
          labelAt: [102, 428] },
        'cert->hash': { fromSide: 't', toSide: 'b', fromAt: [284, 472], toAt: [284, 416], labelAt: [284, 428] },
      },
    },
  },
  sources: [
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/AppBlock.java',
      anchors: ['BLOCK_VERSION = 3', 'proposer wall clock, unix millis', 'merkle root over the ordered message ids',
        'state commitment root AFTER applying this block', 'canonical new-view evidence (empty in view zero)',
        'GENESIS_PREV_HASH = new byte[32]'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/codec/AppBlockCodec.java',
      anchors: ['header.add(new ByteString(Blake2bUtil.blake2bHash256(block.justification())));',
        'Binary merkle root (blake2b-256) over the ordered message ids.'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/consensus/ConsensusContext.java',
      anchors: ['out.write(genesisId);', 'out.writeInt(quorum.threshold());', 'out.writeInt(quorum.maxByzantineMembers());',
        'out.write(observationProfileDigest);'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/consensus/ConsensusDigests.java',
      anchors: ['return vote(COMMIT_DOMAIN, block);'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainEngine.java',
      anchors: ['has unsupported app-block version', 'chain identity does not match local app chain',
        'Proposal consensus context mismatch', 'is not from the deterministic leader', 'Proposal prev-hash mismatch',
        'Proposal messages-root mismatch', 'Proposal state-root mismatch', 'seed.put(context).put(parent);',
        '!group.containsAt(signerHex, block.height()) || !seen.add(signerHex)'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['only the cert is outside the hash', 'the ONLY time a state machine may use',
        'With `l1.stability-depth > 0`, every block carries an `(l1Slot, l1BlockHash)`', 'slots must be monotonic'] },
  ],
};
