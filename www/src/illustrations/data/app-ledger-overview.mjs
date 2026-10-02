// What an app ledger is, as one block diagram: organizations submit through
// their members, every member applies the same rules, and Cardano and external
// systems sit outside the ledger.

const ORG = 'Each organization runs a member node, identified by an Ed25519 key. Its applications submit messages '
  + 'through that member, and the member votes on every block.';
const CONNECTORS = 'Effects reach systems like these. Yano X ships optional connectors for Kafka, S3-compatible object '
  + 'storage, IPFS/Kubo, and Cardano payments; the Yano host provides HTTP webhooks.';

export default {
  id: 'app-ledger-overview',
  type: 'diagram',
  title: 'An app ledger at a glance',
  tag: 'Concept',
  hint: 'Select any block to see what it does.',
  caption:
    'Each organization runs a member. Members order the same messages, apply them with the same '
    + 'state machine, and certify the resulting state root together. Cardano and external systems '
    + 'stay outside: an anchor publishes a certified root, and effects run only after finality.',
  zones: [
    {
      id: 'ledger', kind: 'ledger', label: 'App ledger · the same rules on every member',
      contains: ['messages', 'state-machine', 'final-block', 'state-root'],
    },
    {
      id: 'external', kind: 'external', label: 'External systems',
      contains: ['kafka', 'ipfs', 'object-storage', 'erp'],
    },
  ],
  blocks: [
    { id: 'org-a', label: 'Organization A', kind: 'actor', detail: ORG },
    { id: 'org-b', label: 'Organization B', kind: 'actor', detail: ORG },
    { id: 'org-c', label: 'Organization C', kind: 'actor', detail: ORG },
    {
      id: 'messages', label: 'Ordered messages', sub: 'signed envelopes', kind: 'core',
      detail: 'An application submits a topic and a body to a member, which signs the envelope with its member key. '
        + 'Members gossip pending messages, and the leader for each height orders them into a block.',
      link: { label: 'Life of a message', href: '#message-lifecycle' },
    },
    {
      id: 'state-machine', label: 'State machine', sub: 'deterministic', kind: 'core',
      detail: 'The only component that reads message bodies. Every member applies the same messages with the same '
        + 'deterministic code, so honest members reach the same state.',
      link: { label: 'State machines', href: '/state-machines/' },
    },
    {
      id: 'final-block', label: 'Final block', sub: 'threshold-signed', kind: 'core',
      detail: 'A block is final once a threshold of members has signed PREPARE and then COMMIT for it, each after '
        + 're-executing it. There is no rollback below finality.',
      link: { label: 'Consensus and finality', href: '/concepts/consensus-and-finality/' },
    },
    {
      id: 'state-root', label: 'State root', sub: 'proofs for records', kind: 'core',
      detail: 'The root of the authenticated state after a block, identical on every member. A proof shows that a '
        + 'record is, or is not, present under this root.',
      link: { label: 'State and proofs', href: '/concepts/state-and-proofs/' },
    },
    {
      id: 'cardano', label: 'Cardano', sub: 'optional anchor', kind: 'cardano',
      detail: 'An anchor publishes a certified state root to Cardano, in transaction metadata or a script-controlled '
        + 'anchor. It is optional, and finality never waits for it.',
      link: { label: 'Cardano anchoring', href: '/concepts/anchoring/' },
    },
    {
      id: 'effects', label: 'Effect runtime', sub: 'after finality', kind: 'runtime',
      detail: 'A transition can emit an effect record. After finality, an executor outside consensus performs the '
        + 'external action, and a tracked result returns to the ledger as a new message.',
      link: { label: 'Effects', href: '/concepts/effects/' },
    },
    { id: 'kafka', label: 'Kafka', kind: 'external', detail: CONNECTORS },
    { id: 'ipfs', label: 'IPFS', kind: 'external', detail: CONNECTORS },
    { id: 'object-storage', label: 'Object storage', kind: 'external', detail: CONNECTORS },
    { id: 'erp', label: 'ERP or webhook', kind: 'external', detail: CONNECTORS },
  ],
  edges: [
    { from: 'org-a', to: 'ledger' },
    { from: 'org-b', to: 'ledger', label: 'messages' },
    { from: 'org-c', to: 'ledger' },
    { from: 'messages', to: 'state-machine' },
    { from: 'state-machine', to: 'final-block' },
    { from: 'final-block', to: 'state-root' },
    { from: 'state-root', to: 'cardano', label: 'anchor', style: 'dashed' },
    { from: 'final-block', to: 'effects', label: 'effects' },
    { from: 'effects', to: 'external' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 380,
      zones: {
        ledger: [16, 96, 728, 152],
        external: [16, 276, 356, 96],
      },
      blocks: {
        'org-a': [105, 14, 150, 44],
        'org-b': [305, 14, 150, 44],
        'org-c': [505, 14, 150, 44],
        messages: [36, 142, 148, 76],
        'state-machine': [216, 142, 148, 76],
        'final-block': [396, 142, 148, 76],
        'state-root': [576, 142, 148, 76],
        effects: [396, 292, 148, 64],
        cardano: [576, 292, 148, 64],
        kafka: [32, 306, 160, 26],
        ipfs: [200, 306, 156, 26],
        'object-storage': [32, 338, 160, 26],
        erp: [200, 338, 156, 26],
      },
      edges: {
        'org-a->ledger': { fromSide: 'b', toSide: 't', toAt: [180, 96] },
        'org-b->ledger': { fromSide: 'b', toSide: 't', toAt: [380, 96], labelAt: [380, 77] },
        'org-c->ledger': { fromSide: 'b', toSide: 't', toAt: [580, 96] },
        'final-block->effects': { labelAt: [470, 266] },
        'state-root->cardano': { labelAt: [650, 266] },
        'effects->external': { fromSide: 'l', toSide: 'r', toAt: [372, 324] },
      },
    },
    narrow: {
      width: 380,
      height: 676,
      labels: {
        'org-a': { label: 'Org A' },
        'org-b': { label: 'Org B' },
        'org-c': { label: 'Org C' },
      },
      zones: {
        ledger: [8, 84, 364, 352],
        external: [8, 564, 364, 100],
      },
      blocks: {
        'org-a': [8, 12, 116, 44],
        'org-b': [132, 12, 116, 44],
        'org-c': [256, 12, 116, 44],
        messages: [40, 128, 300, 56],
        'state-machine': [40, 208, 300, 56],
        'final-block': [40, 288, 300, 56],
        'state-root': [40, 368, 300, 56],
        effects: [8, 476, 176, 60],
        cardano: [196, 476, 176, 60],
        kafka: [20, 596, 164, 26],
        ipfs: [196, 596, 164, 26],
        'object-storage': [20, 628, 164, 26],
        erp: [196, 628, 164, 26],
      },
      edges: {
        'org-a->ledger': { fromSide: 'b', toSide: 't', toAt: [66, 84] },
        'org-b->ledger': { fromSide: 'b', toSide: 't', toAt: [190, 84], label: false },
        'org-c->ledger': { fromSide: 'b', toSide: 't', toAt: [314, 84] },
        'final-block->effects': { fromSide: 'l', toSide: 't', via: [[24, 316]], toAt: [24, 476], labelAt: [66, 456] },
        'state-root->cardano': { fromSide: 'b', toSide: 't', via: [[190, 450], [284, 450]], toAt: [284, 476], labelAt: [238, 450] },
        'effects->external': { fromSide: 'b', toSide: 't', toAt: [96, 564] },
      },
    },
  },
  sources: [
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md', anchors: ['APP_FINAL', 'stateRoot', 'messagesRoot'] },
    { repo: 'yano-x', path: 'docs/appchain/OPTIONAL_CONNECTORS.md',
      anchors: ['`executor:kafka`', '`executor:objectstore-s3`', '`executor:ipfs`', '`executor:cardano-payment`'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/AppChainConfig.java', anchors: ['MAX_MEMBERS = 32'] },
  ],
};
