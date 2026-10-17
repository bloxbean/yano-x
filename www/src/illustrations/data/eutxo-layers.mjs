// The eUTxO family as layers of capabilities, each optional on top of the
// one below. Recipe ids and capability dependencies come from the recipe and
// capability catalogs; replaces the former Mermaid flowchart.

export default {
  id: 'eutxo-layers',
  type: 'diagram',
  title: 'The eUTxO layers',
  tag: 'Concept',
  hint: 'Select a layer to see which recipe selects it and what it adds.',
  caption: 'The Cardano bridge is an optional layer on the deterministic eUTxO ledger. Both are experimental, and '
    + 'neither is for real funds.',
  zones: [
    { id: 'ledger-zone', kind: 'ledger', label: 'App ledger · eUTxO state machine', contains: ['ledger', 'bridge'] },
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
      id: 'cardano', label: 'Cardano', sub: 'devnet or testnet', kind: 'cardano',
      detail: 'The bridge deposits to and withdraws from Cardano. Use a devnet or a Cardano test network with '
        + 'disposable keys.',
    },
  ],
  edges: [
    { from: 'ledger', to: 'bridge' },
    { from: 'bridge', to: 'cardano', label: 'deposits, withdrawals', style: 'dashed' },
  ],
  layouts: {
    wide: {
      width: 600,
      height: 224,
      zones: { 'ledger-zone': [204, 16, 380, 192] },
      blocks: {
        bridge: [284, 56, 220, 56],
        ledger: [284, 140, 220, 56],
        cardano: [16, 56, 148, 56],
      },
      edges: {
        'ledger->bridge': { fromSide: 't', toSide: 'b' },
        'bridge->cardano': { fromSide: 'l', toSide: 'r', labelAt: [90, 134] },
      },
    },
    narrow: {
      width: 380,
      height: 392,
      zones: { 'ledger-zone': [8, 8, 364, 284] },
      blocks: {
        bridge: [80, 64, 220, 56],
        ledger: [80, 208, 220, 56],
        cardano: [110, 320, 160, 56],
      },
      edges: {
        'ledger->bridge': { fromSide: 't', toSide: 'b' },
        'bridge->cardano': { fromSide: 'l', toSide: 'l', via: [[16, 92], [16, 348]], fromAt: [80, 92], toAt: [110, 348], label: false },
      },
    },
  },
  legend: [['core', 'capability layer'], ['cardano', 'Cardano']],
  sources: [
    { repo: 'yano-x', path: 'tooling/devtools/src/main/resources/appchain-dx/v1alpha1/appchain-recipe-catalog.json',
      anchors: ['"id": "eutxo-ledger"', '"id": "eutxo-cardano-bridge"'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/resources/appchain-dx/v1alpha1/appchain-capability-catalog.json',
      anchors: ['"id": "state:eutxo-ledger"', '"id": "bridge:cardano-federated"', '"id": "funding:eutxo-genesis"',
        '"id": "profile:eutxo-plutus-v3"',
        'Deterministic Cardano-shaped EUTxO state, root-fixed receipts, address indexes, and MPF-proven outputs'] },
  ],
};
