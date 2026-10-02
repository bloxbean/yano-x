// The three sibling stores of one node, what each holds, and which data the
// state root covers. Paths follow cluster.sh and the host's storage settings;
// the authenticated/local split follows the consensus guide §7.

export default {
  id: 'where-data-lives',
  type: 'diagram',
  title: 'Where a node keeps its data',
  tag: 'Concept',
  hint: 'Select a store to see what it holds and whether it can be rebuilt.',
  caption: 'Each node keeps three sibling stores. Cardano state and app ledger state are authoritative; read '
    + 'indexes are derived from finalized blocks, can be deleted and rebuilt, and must never be restored as '
    + 'authoritative.',
  zones: [
    { id: 'l1', kind: 'cardano', label: 'Cardano store', contains: ['cardano'] },
    { id: 'app', kind: 'ledger', label: 'App ledger store', contains: ['ledgerdb', 'trie', 'blocks', 'framework'] },
    { id: 'idx', kind: 'runtime', label: 'Derived store', contains: ['indexes'] },
  ],
  blocks: [
    {
      id: 'cardano', label: 'chainstate/', sub: 'Cardano L1 state', kind: 'cardano',
      detail: 'The node\'s own Cardano data: blocks, ledger state, and the stable L1 view members use to check each '
        + 'block\'s Cardano reference and observations. Set by `yano.storage.path` (default `./chainstate`). App '
        + 'ledger data never goes below it.',
    },
    {
      id: 'ledgerdb', label: 'appchain-chainstate/', sub: 'one database per chain', kind: 'ledger',
      detail: 'Every hosted app ledger, one RocksDB database per chain under `<path>/<chain-id>/`, set by '
        + '`yano.app-chain.storage.path` (default `appchain-chainstate`). Authoritative: back it up, and never '
        + 'delete it to fix an error.',
    },
    {
      id: 'trie', label: 'Authenticated state', sub: 'under the state root', kind: 'core',
      detail: 'Every key the state machine writes, plus reserved records such as the height-1 identity markers and '
        + 'the finalized block-message record (`[height, messagesRoot, messageCount]`, on by default). This is what '
        + 'the state root commits to, what members re-execute and compare, and what proofs cover.',
    },
    {
      id: 'blocks', label: 'Blocks + certificates', sub: 'hash-linked history', kind: 'core',
      detail: 'Every finalized block with its finality certificate and tip metadata. Written with the state changes '
        + 'in one atomic batch per block, so a crash mid-block leaves the previous height intact.',
    },
    {
      id: 'framework', label: 'Framework records', sub: 'outside the root', kind: 'core',
      detail: 'Message, topic, and sender indexes, per-sender sequence floors, vote locks, and membership epochs. '
        + 'They are part of the authoritative ledger store and persist across restarts, but they sit outside the '
        + 'state commitment, so they are not provable by themselves.',
    },
    {
      id: 'indexes', label: 'appchain-indexers/', sub: 'derived, rebuildable', kind: 'runtime',
      detail: 'Node-local read models, such as the eUTxO lifecycle index. They never take part in consensus, state '
        + 'roots, finality, or proof verification, never advance beyond the authoritative ledger\'s tip, and rebuild '
        + 'from retained finalized blocks after you delete them. Never restore them as authoritative state.',
    },
  ],
  edges: [
    { from: 'l1', to: 'app', label: 'L1 view', style: 'dashed' },
    { from: 'app', to: 'idx', label: 'finalized blocks' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 300,
      zones: {
        l1: [16, 16, 188, 268],
        app: [236, 16, 276, 268],
        idx: [544, 16, 200, 268],
      },
      blocks: {
        cardano: [32, 120, 156, 64],
        ledgerdb: [252, 48, 244, 44],
        trie: [252, 100, 244, 52],
        blocks: [252, 160, 244, 52],
        framework: [252, 220, 244, 52],
        indexes: [560, 120, 168, 64],
      },
      edges: {
        'l1->app': { fromSide: 'r', toSide: 'l', fromAt: [204, 152], toAt: [236, 152], label: false },
        'app->idx': { fromSide: 'r', toSide: 'l', fromAt: [512, 152], toAt: [544, 152], label: false },
      },
    },
    narrow: {
      width: 380,
      height: 536,
      zones: {
        l1: [8, 8, 364, 96],
        app: [8, 136, 364, 256],
        idx: [8, 424, 364, 104],
      },
      blocks: {
        cardano: [40, 36, 300, 56],
        ledgerdb: [40, 164, 300, 44],
        trie: [40, 216, 300, 52],
        blocks: [40, 276, 300, 52],
        framework: [40, 336, 300, 48],
        indexes: [40, 456, 300, 56],
      },
      edges: {
        'l1->app': { fromSide: 'b', toSide: 't', fromAt: [190, 104], toAt: [190, 136], labelAt: [250, 120] },
        'app->idx': { fromSide: 'b', toSide: 't', fromAt: [190, 392], toAt: [190, 424], labelAt: [270, 408] },
      },
    },
  },
  legend: [['cardano', 'Cardano L1 store'], ['ledger', 'app ledger store (authoritative)'],
    ['runtime', 'read indexes (rebuildable)']],
  sources: [
    { repo: 'yano-x', path: 'AGENTS.md',
      anchors: ['`chainstate/`: authoritative Cardano L1 state', '`appchain-chainstate/`: authoritative app-chain state',
        '`appchain-indexers/`: rebuildable local read indexes', 'Never restore `appchain-indexers` as authoritative state',
        'Derived indexes must never advance beyond authoritative app-chain state'] },
    { repo: 'yano-x', path: 'scripts/appchain-cluster/cluster.sh',
      anchors: ['-Dyano.storage.path=$dir/chainstate', '-Dyano.app-chain.storage.path=$dir/appchain-chainstate',
        'storage-path=$dir/appchain-indexers'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['The state machine and framework-authenticated keys share the trie.',
        'all in RocksDB CFs *outside* the state commitment', 'One atomic batch per block.'] },
    { repo: 'yano-x', path: 'ledgers/eutxo/indexer-core/src/main/java/org/yanoproject/x/eutxo/indexer/EutxoIndexCoordinator.java',
      anchors: ['index checkpoint is ahead of authoritative app-chain tip'] },
    { repo: 'yano-x', path: 'docs/appchain/PROOF_LAB.md',
      anchors: ['`[height, messagesRoot, messageCount]`'] },
  ],
};
