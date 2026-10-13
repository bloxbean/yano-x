// What the single-host launcher keeps in $YANO_CLUSTER_DIR, as an explorer.
// File and directory names come from cluster.sh; the store roles come from
// the user guide's storage section and Yano's consensus guide.

const CHAIN_STORE = 'One store for one chain: its blocks, certificates, and the authenticated state behind its root. '
  + 'Each chain\'s genesis and history are independent of the others.';

export default {
  id: 'cluster-on-disk',
  type: 'diagram',
  title: 'What the cluster keeps on disk',
  tag: 'Concept',
  hint: 'Select a file or folder to see what it holds.',
  caption: 'The launcher writes everything below `$YANO_CLUSTER_DIR`, which defaults to '
    + '`/tmp/yano-appchain-cluster`. Each node has its own folder, `node0/`, `node1/`, and `node2/`, with the same '
    + 'layout. `stop` keeps all of it; `clean` stops the nodes and deletes the whole directory.',
  zones: [
    { id: 'records', kind: 'core', label: 'Cluster records', contains: ['identity', 'app-identity', 'env', 'pids'] },
    {
      id: 'node', kind: 'ledger', label: 'Inside each node folder',
      contains: ['chainstate', 'genesis', 'appchain', 'orders', 'registry', 'effects', 'indexers', 'log'],
    },
  ],
  blocks: [
    {
      id: 'identity', label: 'cluster-identity.json', sub: 'L1 identity', kind: 'core',
      detail: 'Records the Cardano network and, on devnet, the genesis source. Starting this directory again with a '
        + 'different network or devnet genesis is refused instead of overwriting state.',
    },
    {
      id: 'app-identity', label: 'cluster-appchain-\nidentity.json', sub: 'members, threshold, chains', kind: 'core',
      detail: 'Records the network, member keys, threshold, proposer, chain ids, and anchor settings. Starting again '
        + 'with a different node count or `--threshold` fails with “app-chain identity differs from retained state”. '
        + 'Use a new `YANO_CLUSTER_DIR` for a different cluster.',
    },
    {
      id: 'env', label: 'cluster.env', sub: 'ports and network', kind: 'core',
      detail: 'The network, ports, and anchor settings chosen by `start`. Later commands such as `status` and `submit` '
        + 'read it, so they find a cluster that moved to a free port range.',
    },
    {
      id: 'pids', label: 'nodeN.pid', sub: 'process records', kind: 'core',
      detail: '`node0.pid`, `node0.pid.meta`, and the same for each node. `stop` uses them to stop exactly the '
        + 'processes it started, and `clean` refuses to delete data while a record it cannot trust remains.',
    },
    {
      id: 'chainstate', label: 'chainstate/', sub: 'Cardano L1 state', kind: 'cardano',
      detail: 'The node\'s authoritative Cardano L1 state. Node 0 produces the private devnet; nodes 1 and 2 sync it '
        + 'from node 0.',
    },
    {
      id: 'genesis', label: 'shelley-genesis.json', sub: 'devnet genesis copy', kind: 'cardano',
      detail: 'The devnet genesis. Node 0 writes it on first start and the launcher copies it to each follower. A '
        + 'retained follower whose genesis differs from node 0\'s is refused.',
    },
    {
      id: 'appchain', label: 'appchain-chainstate/', sub: 'one store per chain', kind: 'ledger',
      detail: 'Authoritative app ledger state, kept apart from the L1 state. It holds one store per hosted chain.',
    },
    { id: 'orders', label: 'orders-chain/', kind: 'ledger', detail: `${CHAIN_STORE} This chain runs \`ordered-log\`.` },
    { id: 'registry', label: 'registry-chain/', kind: 'ledger', detail: `${CHAIN_STORE} This chain runs \`kv-registry\`.` },
    { id: 'effects', label: 'effects-chain/', kind: 'ledger', detail: `${CHAIN_STORE} This chain runs \`approvals\`.` },
    {
      id: 'indexers', label: 'appchain-indexers/', sub: 'rebuildable indexes', kind: 'runtime',
      detail: 'Read indexes for indexer plugins, such as the EUTxO indexer. They are derived from finalized blocks: '
        + 'delete and rebuild them, and never restore them as authoritative state.',
    },
    {
      id: 'log', label: 'node.log', sub: 'this node\'s log', kind: 'runtime',
      detail: 'Everything the node logs. `./yano.sh appchain cluster logs 1` prints the last 60 lines of node 1\'s '
        + 'log; add `-f` to follow it.',
    },
  ],
  edges: [
    { from: 'appchain', to: 'orders' },
    { from: 'appchain', to: 'registry' },
    { from: 'appchain', to: 'effects' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 376,
      zones: {
        records: [16, 16, 240, 344],
        node: [272, 16, 472, 344],
      },
      blocks: {
        identity: [32, 56, 208, 56],
        'app-identity': [32, 128, 208, 76],
        env: [32, 220, 208, 56],
        pids: [32, 292, 208, 56],
        chainstate: [288, 56, 208, 56],
        genesis: [520, 56, 208, 56],
        appchain: [288, 128, 208, 124],
        orders: [520, 128, 208, 36],
        registry: [520, 172, 208, 36],
        effects: [520, 216, 208, 36],
        indexers: [288, 268, 208, 56],
        log: [520, 268, 208, 56],
      },
      edges: {
        'appchain->orders': { fromSide: 'r', toSide: 'l', fromAt: [496, 146] },
        'appchain->registry': { fromSide: 'r', toSide: 'l', fromAt: [496, 190] },
        'appchain->effects': { fromSide: 'r', toSide: 'l', fromAt: [496, 234] },
      },
    },
    narrow: {
      width: 380,
      height: 584,
      labels: {
        identity: { label: 'cluster-\nidentity.json' },
        genesis: { label: 'shelley-\ngenesis.json' },
        appchain: { label: 'appchain-\nchainstate/' },
        'app-identity': { sub: 'members, chains' },
      },
      zones: {
        records: [8, 8, 364, 204],
        node: [8, 228, 364, 344],
      },
      blocks: {
        identity: [20, 44, 166, 76],
        'app-identity': [194, 44, 166, 76],
        env: [20, 136, 166, 56],
        pids: [194, 136, 166, 56],
        chainstate: [20, 264, 166, 76],
        genesis: [194, 264, 166, 76],
        appchain: [20, 352, 166, 124],
        orders: [194, 352, 166, 36],
        registry: [194, 396, 166, 36],
        effects: [194, 440, 166, 36],
        indexers: [20, 492, 166, 56],
        log: [194, 492, 166, 56],
      },
      edges: {
        'appchain->orders': { fromSide: 'r', toSide: 'l', fromAt: [186, 370] },
        'appchain->registry': { fromSide: 'r', toSide: 'l', fromAt: [186, 414] },
        'appchain->effects': { fromSide: 'r', toSide: 'l', fromAt: [186, 458] },
      },
    },
  },
  legend: [
    ['core', 'cluster record'],
    ['cardano', 'Cardano L1'],
    ['ledger', 'app ledger state'],
    ['runtime', 'node-local, rebuildable'],
  ],
  sources: [
    { repo: 'yano-x', path: 'scripts/appchain-cluster/cluster.sh',
      anchors: ['CLUSTER_DIR="${YANO_CLUSTER_DIR:-/tmp/yano-appchain-cluster}"',
        '"$CLUSTER_DIR/cluster-identity.json"', '"$CLUSTER_DIR/cluster-appchain-identity.json"',
        '"$CLUSTER_DIR/cluster.env"', 'pid_file()    { echo "$CLUSTER_DIR/node$1.pid"; }',
        'pid_meta_file() { echo "$CLUSTER_DIR/node$1.pid.meta"; }', 'node_dir()    { echo "$CLUSTER_DIR/node$1"; }',
        '"-Dyano.storage.path=$dir/chainstate"', '"-Dyano.app-chain.storage.path=$dir/appchain-chainstate"',
        'storage-path=$dir/appchain-indexers', 'log_file()    { echo "$(node_dir "$1")/node.log"; }',
        'target="$(node_dir 0)/shelley-genesis.json"', 'differs from node 0; refusing to start',
        '"threshold": int(threshold),', '"chainIds": chains,', 'refusing to wipe data while PID records remain',
        'rm -rf "$CLUSTER_DIR"', 'tail $follow -n 60 "$f"', 'clean)             cmd_stop wipe;;'] },
    { repo: 'yano-x', path: 'config/application-appchain.yml',
      anchors: ['chain-id: "orders-chain"', 'chain-id: "registry-chain"', 'chain-id: "effects-chain"',
        'state-machine: kv-registry', 'state-machine: approvals'] },
    { repo: 'yano-x', path: 'docs/APP_CHAIN_USER_GUIDE.md',
      anchors: ['Never restore this derived index root', 'It can be deleted and rebuilt from finalized app-chain blocks.'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['(`<resolved yano.app-chain.storage.path>/<chain-id>/`)'] },
  ],
};
