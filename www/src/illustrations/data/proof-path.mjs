// What a state proof contains, on a deliberately simplified trie: the record,
// the sibling hashes along its path, and the root a verifier recomputes. Also
// what an exclusion proof shows and does not show. Real MPF and JMT proofs
// have their own node layouts and wire formats.

const PATH = 'On the path from your record to the root. The verifier recomputes this node’s hash from the hash '
  + 'below it and the sibling hashes in the proof.';
const SIBLING = 'Not on your path. The proof carries only this node’s hash, not its contents; the verifier needs it '
  + 'to recompute the parent.';

export default {
  id: 'proof-path',
  type: 'diagram',
  title: 'What a state proof contains',
  tag: 'Simplified',
  hint: 'Select a node to see what the proof carries for it.',
  caption: 'Simplified. Real MPF and JMT proofs use their own node layouts and wire formats, but the idea is the '
    + 'same: the record, plus the sibling hashes on its path, recompute one root. Key prefixes are invented.',
  blocks: [
    {
      id: 'root', label: 'State root', sub: 'after block h', kind: 'core',
      detail: 'The root the verifier recomputes. If it equals the root in the same response, the proof is '
        + '**internally consistent**, and that is all it shows. It proves your record only against a root you '
        + 'obtained some other way.',
    },
    {
      id: 'trusted', label: 'Root you trust', sub: 'pinned or anchored', kind: 'actor',
      detail: 'A root obtained independently of the node that served the proof: one you pinned, one from a block '
        + 'whose finality certificate you checked against member keys you pinned, or one bound by a Cardano anchor. '
        + 'Where it came from sets the trust level of the result.',
      link: { label: 'Trust levels', href: '#trust-ladder' },
    },
    { id: 'n3', label: 'Node 3…', sub: 'on the path', kind: 'core', detail: PATH },
    { id: 'na', label: 'Node a…', sub: 'sibling: hash only', kind: 'external', detail: SIBLING },
    { id: 'n3c', label: 'Node 3c…', sub: 'on the path', kind: 'core', detail: PATH },
    {
      id: 'absent', label: 'No child 3e…', sub: 'exclusion', kind: 'fail',
      detail: 'An exclusion proof for key 3e…: the path ends at node 3…, which has no child for it. This shows the '
        + 'key is absent from this state. It does not show that the business fact never happened, and a height '
        + 'whose proof material was pruned gives no proof at all: unavailable, not absent.',
    },
    { id: 'n3f', label: 'Node 3f…', sub: 'sibling: hash only', kind: 'external', detail: SIBLING },
    {
      id: 'leaf', label: 'Leaf 3c1…', sub: 'your record', kind: 'leader',
      detail: 'Your record: a canonical state key and its value. The proof carries the value, and the verifier '
        + 'hashes it itself, so a changed value changes every hash above it and the root no longer matches.',
    },
    { id: 'leaf-sibling', label: 'Leaf 3c9…', sub: 'sibling: hash only', kind: 'external', detail: SIBLING },
  ],
  edges: [
    { from: 'root', to: 'trusted', label: 'must equal', style: 'dashed' },
    { from: 'root', to: 'n3' },
    { from: 'root', to: 'na' },
    { from: 'n3', to: 'n3c' },
    { from: 'n3', to: 'absent', style: 'dashed' },
    { from: 'n3', to: 'n3f' },
    { from: 'n3c', to: 'leaf' },
    { from: 'n3c', to: 'leaf-sibling' },
  ],
  legend: [
    ['core', 'on the path'],
    ['leader', 'your record'],
    ['external', 'sibling: only its hash is in the proof'],
    ['fail', 'absent key'],
    ['actor', 'the root you trust'],
  ],
  layouts: {
    wide: {
      width: 760,
      height: 352,
      blocks: {
        root: [300, 16, 160, 52],
        trusted: [560, 16, 184, 52],
        n3: [160, 104, 160, 52],
        na: [440, 104, 160, 52],
        n3c: [24, 192, 150, 52],
        absent: [190, 192, 150, 52],
        n3f: [356, 192, 150, 52],
        leaf: [24, 280, 150, 56],
        'leaf-sibling': [190, 280, 150, 56],
      },
      edges: {
        'root->trusted': { labelAt: [510, 30] },
        'root->n3': { fromSide: 'b', toSide: 't' },
        'root->na': { fromSide: 'b', toSide: 't' },
        'n3->n3c': { fromSide: 'b', toSide: 't' },
        'n3->absent': { fromSide: 'b', toSide: 't' },
        'n3->n3f': { fromSide: 'b', toSide: 't' },
        'n3c->leaf': { fromSide: 'b', toSide: 't' },
        'n3c->leaf-sibling': { fromSide: 'b', toSide: 't' },
      },
    },
    narrow: {
      width: 380,
      height: 352,
      labels: {
        n3f: { sub: 'sibling hash' },
        'leaf-sibling': { sub: 'sibling hash' },
      },
      blocks: {
        root: [20, 12, 160, 52],
        trusted: [200, 12, 160, 52],
        n3: [20, 100, 160, 52],
        na: [200, 100, 160, 52],
        n3c: [8, 188, 116, 52],
        absent: [132, 188, 116, 52],
        n3f: [256, 188, 116, 52],
        leaf: [8, 276, 116, 60],
        'leaf-sibling': [132, 276, 116, 60],
      },
      edges: {
        'root->trusted': { label: false },
        'root->n3': { fromSide: 'b', toSide: 't' },
        'root->na': { fromSide: 'b', toSide: 't', fromAt: [100, 64], toAt: [280, 100] },
        'n3->n3c': { fromSide: 'b', toSide: 't' },
        'n3->absent': { fromSide: 'b', toSide: 't' },
        'n3->n3f': { fromSide: 'b', toSide: 't' },
        'n3c->leaf': { fromSide: 'b', toSide: 't' },
        'n3c->leaf-sibling': { fromSide: 'b', toSide: 't' },
      },
    },
  },
  sources: [
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/state/StateCommitmentProfiles.java',
      anchors: ['MPF_BLAKE2B256_V1 = "mpf-blake2b256-v1"', 'JMT_BLAKE2B256_V1 = "jmt-blake2b256-v1"'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['individually provable against the resulting root'] },
    { repo: 'yano-x', path: 'docs/appchain/COMPOSABLE_STATE_AND_PROOFS.md',
      anchors: ['`ProofVerifier.verifyInternalConsistency(proof)` checks only proof math against the root carried by the same response',
        'It does not authenticate that root.'] },
    { repo: 'yano-x', path: 'docs/appchain/PROOF_LAB.md',
      anchors: ['a pruned proof is unavailable, not evidence that the fact was absent',
        'Do not expose a business-level absence predicate unless a marker, paired subject, or authenticated snapshot descriptor proves dataset completeness.'] },
  ],
};
