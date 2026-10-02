// What an app ledger is, as one block diagram: organizations submit through
// their members, every member applies the same rules, and Cardano and external
// systems sit outside the ledger.

export default {
  id: 'app-ledger-overview',
  type: 'diagram',
  title: 'An app ledger at a glance',
  tag: 'Concept',
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
    { id: 'org-a', label: 'Organization A', kind: 'actor' },
    { id: 'org-b', label: 'Organization B', kind: 'actor' },
    { id: 'org-c', label: 'Organization C', kind: 'actor' },
    { id: 'messages', label: 'Ordered messages', sub: 'signed envelopes', kind: 'core' },
    { id: 'state-machine', label: 'State machine', sub: 'deterministic', kind: 'core' },
    { id: 'final-block', label: 'Final block', sub: 'threshold-signed', kind: 'core' },
    { id: 'state-root', label: 'State root', sub: 'proofs for records', kind: 'core' },
    { id: 'cardano', label: 'Cardano', sub: 'optional anchor', kind: 'cardano' },
    { id: 'effects', label: 'Effect runtime', sub: 'after finality', kind: 'runtime' },
    { id: 'kafka', label: 'Kafka', kind: 'external' },
    { id: 'ipfs', label: 'IPFS', kind: 'external' },
    { id: 'object-storage', label: 'Object storage', kind: 'external' },
    { id: 'erp', label: 'ERP or webhook', kind: 'external' },
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
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/AppChainConfig.java', anchors: ['MAX_MEMBERS = 32'] },
  ],
};
