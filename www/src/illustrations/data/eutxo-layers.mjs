// The eUTxO family as layers of capabilities, each optional on top of the
// one below. Recipe ids and capability dependencies come from the recipe and
// capability catalogs; replaces the former Mermaid flowchart.

export default {
  id: 'eutxo-layers',
  type: 'diagram',
  title: 'The eUTxO and ZK layers',
  tag: 'Concept',
  hint: 'Select a layer to see which recipe selects it and what it adds.',
  caption: 'Every layer runs on the deterministic eUTxO ledger. The Cardano bridge and the ZeroJ validity path '
    + 'are independent options; the preview lifecycle combines both. All but the base ledger are experimental, '
    + 'and none is for real funds.',
  zones: [
    { id: 'ledger-zone', kind: 'ledger', label: 'App ledger · eUTxO state machine', contains: ['ledger', 'bridge', 'validity', 'preview'] },
  ],
  blocks: [
    {
      id: 'ledger', label: 'eUTxO ledger', sub: 'eutxo-ledger recipe', kind: 'core',
      detail: 'The reusable `state:eutxo-ledger` engine: deterministic Cardano-shaped UTxO state, root-fixed receipts, '
        + 'and MPF-proven outputs under one immutable ledger profile. The `eutxo-ledger` recipe adds '
        + '`profile:eutxo-plutus-v3` and a virtual genesis allocation (`funding:eutxo-genesis`) for no-real-funds '
        + 'testing. Availability `FIRST_PARTY_OPTIONAL`, maturity experimental.',
    },
    {
      id: 'bridge', label: 'Cardano bridge', sub: 'eutxo-cardano-bridge', kind: 'core',
      detail: '`bridge:cardano-federated` observes accepted deposits to a Cardano vault and settles bounded claims. '
        + 'It needs `l1:slot-feed` and replaces the virtual genesis allocation: the two conflict. Availability '
        + '`EXPERIMENTAL`.',
    },
    {
      id: 'validity', label: 'ZeroJ validity', sub: 'eutxo-zeroj-validity', kind: 'core',
      detail: '`settlement:zeroj-validity` adds Groth16 validity proofs over batches and proof-bound root advancement. '
        + 'It runs on the restricted `profile:eutxo-key-payments` ledger profile instead of the Plutus V3 profile. '
        + 'Availability `EXPERIMENTAL`; its development setup keys are test-only.',
    },
    {
      id: 'preview', label: 'ZK preview lifecycle', sub: 'eutxo-zeroj-preview', kind: 'core',
      detail: 'Combines the bridge, ZeroJ validity, and the slot feed: Cardano deposit, eUTxO transaction, proof, '
        + 'root settlement, Cardano withdrawal. Availability `EXPERIMENTAL`.',
    },
    {
      id: 'cardano', label: 'Cardano', sub: 'devnet or testnet', kind: 'cardano',
      detail: 'The bridge and the preview lifecycle deposit to and withdraw from Cardano. Use a devnet or a Cardano '
        + 'test network with disposable keys.',
    },
  ],
  edges: [
    { from: 'ledger', to: 'bridge' },
    { from: 'ledger', to: 'validity' },
    { from: 'bridge', to: 'preview' },
    { from: 'validity', to: 'preview' },
    { from: 'bridge', to: 'cardano', label: 'deposits, withdrawals', style: 'dashed' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 336,
      zones: { 'ledger-zone': [204, 16, 540, 304] },
      blocks: {
        preview: [364, 56, 220, 56],
        bridge: [224, 150, 220, 56],
        validity: [504, 150, 220, 56],
        ledger: [364, 244, 220, 56],
        cardano: [16, 150, 148, 56],
      },
      edges: {
        'ledger->bridge': { fromSide: 'l', toSide: 'b', via: [[334, 272]], toAt: [334, 206] },
        'ledger->validity': { fromSide: 'r', toSide: 'b', via: [[614, 272]], toAt: [614, 206] },
        'bridge->preview': { fromSide: 't', toSide: 'l', via: [[334, 84]], fromAt: [334, 150] },
        'validity->preview': { fromSide: 't', toSide: 'r', via: [[614, 84]], fromAt: [614, 150] },
        'bridge->cardano': { fromSide: 'l', toSide: 'r', labelAt: [90, 228] },
      },
    },
    narrow: {
      width: 380,
      height: 520,
      zones: { 'ledger-zone': [8, 8, 364, 412] },
      blocks: {
        preview: [80, 48, 220, 56],
        bridge: [24, 164, 160, 56],
        validity: [196, 164, 160, 56],
        ledger: [80, 344, 220, 56],
        cardano: [110, 448, 160, 56],
      },
      edges: {
        'ledger->bridge': { fromSide: 't', toSide: 'b', via: [[150, 300], [104, 300]], fromAt: [150, 344], toAt: [104, 220] },
        'ledger->validity': { fromSide: 't', toSide: 'b', via: [[230, 300], [276, 300]], fromAt: [230, 344], toAt: [276, 220] },
        'bridge->preview': { fromSide: 't', toSide: 'b', via: [[104, 134], [150, 134]], fromAt: [104, 164], toAt: [150, 104] },
        'validity->preview': { fromSide: 't', toSide: 'b', via: [[276, 134], [230, 134]], fromAt: [276, 164], toAt: [230, 104] },
        'bridge->cardano': { fromSide: 'l', toSide: 'l', via: [[16, 192], [16, 476]], fromAt: [24, 192], toAt: [110, 476], label: false },
      },
    },
  },
  legend: [['core', 'capability layer'], ['cardano', 'Cardano']],
  sources: [
    { repo: 'yano-x', path: 'tooling/devtools/src/main/resources/appchain-dx/v1alpha1/appchain-recipe-catalog.json',
      anchors: ['"id": "eutxo-ledger"', '"id": "eutxo-cardano-bridge"', '"id": "eutxo-zeroj-validity"',
        '"id": "eutxo-zeroj-preview"'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/resources/appchain-dx/v1alpha1/appchain-capability-catalog.json',
      anchors: ['"id": "state:eutxo-ledger"', '"id": "bridge:cardano-federated"', '"id": "settlement:zeroj-validity"',
        '"id": "funding:eutxo-genesis"', '"id": "profile:eutxo-key-payments"',
        'Deterministic Cardano-shaped EUTxO state, root-fixed receipts, address indexes, and MPF-proven outputs'] },
  ],
};
