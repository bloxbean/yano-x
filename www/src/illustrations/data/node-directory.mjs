// What one node directory (`--node-dir`, default `./yano-node`) keeps, as an
// explorer. File and directory names come from the setup CLI (NodeDir.java,
// NodeConfig.java, StartCommand.java, Devnet.java); the store roles come from
// the user guide's storage section and Yano's consensus guide.

export default {
  id: 'node-directory',
  type: 'diagram',
  title: 'What a node directory keeps on disk',
  tag: 'Concept',
  hint: 'Select a file or folder to see what it holds.',
  caption: 'Every command that acts on a node takes its directory as `--node-dir`, `./yano-node` by default. '
    + '`./yano.sh appchain try` makes one per operator, for example `appchain-try/alice/yano-node`, with the same '
    + 'layout. `stop` keeps all of it; `try --clean` stops the rehearsal\'s nodes and deletes the whole rehearsal.',
  zones: [
    { id: 'records', kind: 'core', label: 'Who this node is', contains: ['identity', 'secrets', 'apps', 'config'] },
    {
      id: 'node', kind: 'ledger', label: 'What it runs and keeps',
      contains: ['chainstate', 'genesis', 'appchain', 'orders', 'indexers', 'run', 'log'],
    },
  ],
  blocks: [
    {
      id: 'identity', label: 'node.json', sub: 'name, key, ports', kind: 'core',
      detail: 'The operator name, public key, addresses, and ports, fixed by the first `request`. `try` gives each '
        + 'node the first free ports from 8080 (HTTP) and 13337 (node); read one back with '
        + '`jq -r .httpPort appchain-try/alice/yano-node/node.json`.',
    },
    {
      id: 'secrets', label: 'secrets/', sub: 'member and API keys', kind: 'core',
      detail: 'The member key (`member.key`) and the node\'s API key (`api.key`), readable only by their owner. '
        + 'The API key is needed only for privileged and admin endpoints. Keys never leave this directory; a node '
        + 'that leads anchoring also keeps its anchor wallet key here.',
    },
    {
      id: 'apps', label: 'apps/', sub: 'sealed applications', kind: 'core',
      detail: '`<app>.chain` is the sealed file this node joined. `<app>.local.json` is this node\'s own view of it: '
        + 'the fingerprint it accepted, the plugin bundle digests, and members added or removed since the seal.',
    },
    {
      id: 'config', label: 'config/', sub: 'consensus, node, local', kind: 'core',
      detail: '`join` writes `consensus.yaml`, the sealed settings every member runs, and `node.yaml`, this node\'s '
        + 'paths and ports. `local.yaml` is the operator\'s own file: the tool never rewrites it, it wins over '
        + '`node.yaml`, and `start` refuses it if it changes a sealed setting.',
    },
    {
      id: 'chainstate', label: 'chainstate/', sub: 'in data/ · Cardano L1', kind: 'cardano',
      detail: 'The node\'s authoritative Cardano L1 state. On a devnet the lead\'s node produces the private chain '
        + 'and every other node syncs it from the lead.',
    },
    {
      id: 'genesis', label: 'devnet-genesis.json', sub: 'in runtime/ · devnet only', kind: 'cardano',
      detail: 'The devnet\'s Cardano genesis. The lead\'s node creates it on first start and the others download it '
        + 'from the lead. Once a node has run on it, it is pinned for life: `start` refuses a changed one.',
    },
    {
      id: 'appchain', label: 'appchain-chainstate/', sub: 'in data/ · per chain', kind: 'ledger',
      detail: 'Authoritative app ledger state, kept apart from the L1 state. It holds one store per chain the node '
        + 'hosts.',
    },
    {
      id: 'orders', label: 'orders/', sub: 'one chain\'s store', kind: 'ledger',
      detail: 'One store for one chain: its blocks, certificates, and the authenticated state behind its root. '
        + '`try` makes one chain, `orders`, running the `audit-log` recipe. Each chain\'s genesis and history are '
        + 'independent of the others.',
    },
    {
      id: 'indexers', label: 'appchain-indexers/', sub: 'in data/ · rebuildable', kind: 'runtime',
      detail: 'Read indexes for indexer plugins, such as the EUTxO indexer. They are derived from finalized blocks: '
        + 'delete and rebuild them, and never restore them as authoritative state.',
    },
    {
      id: 'run', label: 'run/node.pid', sub: 'the running process', kind: 'runtime',
      detail: 'The process `start` launched. `stop` uses it to stop exactly that process, then removes it.',
    },
    {
      id: 'log', label: 'logs/node.log', sub: 'this node\'s log', kind: 'runtime',
      detail: 'Everything the node prints. Each `start` keeps the previous run\'s log as `node.log.previous`. '
        + 'Follow it with `tail -f appchain-try/alice/yano-node/logs/node.log`.',
    },
  ],
  edges: [
    { from: 'appchain', to: 'orders' },
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
        secrets: [32, 128, 208, 56],
        apps: [32, 200, 208, 56],
        config: [32, 272, 208, 56],
        chainstate: [288, 56, 208, 56],
        genesis: [520, 56, 208, 56],
        appchain: [288, 128, 208, 56],
        orders: [520, 128, 208, 56],
        indexers: [288, 200, 208, 56],
        run: [520, 200, 208, 56],
        log: [288, 272, 440, 56],
      },
      edges: {
        'appchain->orders': { fromSide: 'r', toSide: 'l', fromAt: [496, 156] },
      },
    },
    narrow: {
      width: 380,
      height: 532,
      labels: {
        genesis: { label: 'devnet-\ngenesis.json', sub: 'runtime/ · devnet' },
        appchain: { label: 'appchain-\nchainstate/' },
      },
      zones: {
        records: [8, 8, 364, 176],
        node: [8, 200, 364, 320],
      },
      blocks: {
        identity: [20, 44, 166, 56],
        secrets: [194, 44, 166, 56],
        apps: [20, 112, 166, 56],
        config: [194, 112, 166, 56],
        chainstate: [20, 236, 166, 60],
        genesis: [194, 236, 166, 60],
        appchain: [20, 308, 166, 60],
        orders: [194, 308, 166, 60],
        indexers: [20, 380, 166, 56],
        run: [194, 380, 166, 56],
        log: [20, 452, 340, 56],
      },
      edges: {
        'appchain->orders': { fromSide: 'r', toSide: 'l', fromAt: [186, 338] },
      },
    },
  },
  legend: [
    ['core', 'identity, keys, settings'],
    ['cardano', 'Cardano L1'],
    ['ledger', 'app ledger state'],
    ['runtime', 'node-local, rebuildable'],
  ],
  sources: [
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/NodeDir.java',
      anchors: ['static final String DEFAULT = "yano-node";', 'path("node.json")', 'path("secrets", "member.key")',
        'Path key = path("secrets", "api.key");', 'path("apps", app + ".local.json")',
        'return path("apps", app + ".chain");'] },
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/NodeConfig.java',
      anchors: ['local.put("yano.storage.path", data(node, "chainstate"));',
        'local.put("yano.app-chain.storage.path", data(node, "appchain-chainstate"));',
        'local.put("yano.app-chain.indexer.storage.path", data(node, "appchain-indexers"));',
        'NodeDir.write(node.path("config", "consensus.yaml")', 'NodeDir.write(node.path("config", "node.yaml")',
        'yano.sh appchain never rewrites this file, and settings here win over config/node.yaml.',
        'config/local.yaml changes sealed consensus settings'] },
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/StartCommand.java',
      anchors: ['node.path("logs", "node.log.previous")', 'Path log = node.path("logs", "node.log");'] },
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/Devnet.java',
      anchors: ['return node.path("runtime", "devnet-genesis.json");', 'it is pinned for life',
        'client.download("/api/v1/devnet/genesis/download")'] },
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/StopCommand.java',
      anchors: ['Files.deleteIfExists(node.path("run", "node.pid"));'] },
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/TryCommand.java',
      anchors: ['Ports.firstFree(13337, used)', 'Ports.firstFree(8080, used)',
        'root.resolve(operator).resolve("yano-node")', '"orders=" + LOG_RECIPE'] },
    { repo: 'yano-x', path: 'docs/APP_CHAIN_USER_GUIDE.md',
      anchors: ['Never restore this derived index root', 'It can be deleted and rebuilt from finalized app-chain blocks.'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['(`<resolved yano.app-chain.storage.path>/<chain-id>/`)'] },
  ],
};
