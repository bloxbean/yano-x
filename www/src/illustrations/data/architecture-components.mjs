// The components inside one Yano node that hosts app ledgers, following the
// architecture map in Yano's consensus guide (§1) and the plugin ADR-011.

const SUBSYSTEM = ['pool', 'engine', 'group', 'machine', 'ledger', 'services'];

export default {
  id: 'architecture-components',
  type: 'diagram',
  title: 'Inside a Yano node',
  tag: 'Concept',
  hint: 'Select a component to see what it does.',
  caption: 'One node can host several ledgers. Each hosted chain has its own subsystem, ledger, state machine, '
    + 'and members; chains share only the node’s networking and its view of Cardano.',
  zones: [
    {
      id: 'node', kind: 'ledger', label: 'One Yano node',
      contains: ['l1', 'manager', 'rest', 'plugins'],
    },
    {
      id: 'chain', kind: 'member', label: 'AppChainSubsystem · one per hosted chain',
      contains: SUBSYSTEM,
    },
  ],
  blocks: [
    {
      id: 'l1', label: 'Cardano node', sub: 'sync, relay, L1 events', kind: 'cardano',
      detail: 'Yano’s Cardano node: chain sync, relay, and block production on a devnet. Hosted ledgers use its view '
        + 'of Cardano for the optional L1 reference, L1 observations, and anchoring.',
    },
    {
      id: 'manager', label: 'AppChainManager', sub: 'one per node: shared transport and catch-up', kind: 'core',
      detail: 'One per node. It runs one shared inbound transport for app messages (protocol 100) and one catch-up '
        + 'server (protocol 103), and dispatches each verified envelope to the chain it belongs to.',
    },
    {
      id: 'pool', label: 'Message pool', sub: 'admission, backpressure', kind: 'core',
      detail: 'Admitted messages wait here until a block includes them. A full pool answers 429. The pool lives in '
        + 'memory and is lost on restart.',
    },
    {
      id: 'engine', label: 'Consensus engine', sub: 'AppChainEngine', kind: 'leader',
      detail: 'The two-phase round, view changes, and catch-up, on one serial event loop per chain. It commits each '
        + 'final block, its certificate, and the new state in one atomic RocksDB batch.',
      link: { label: 'Consensus and finality', href: '/concepts/consensus-and-finality/' },
    },
    {
      id: 'group', label: 'Member group', sub: 'static or governed', kind: 'core',
      detail: '`MemberGroup`: the members and threshold at every height, from configuration (static) or from '
        + 'finalized governance commands (governed).',
    },
    {
      id: 'machine', label: 'State machine', sub: 'your rules', kind: 'core',
      detail: '`AppStateMachine`: the deterministic application rules for this chain. `ordered-log` is built in; '
        + 'Yano X plugins provide the others.',
      link: { label: 'State machines', href: '/state-machines/' },
    },
    {
      id: 'ledger', label: 'Ledger store', sub: 'RocksDB, MPF or JMT', kind: 'core',
      detail: '`AppLedgerStore`: one RocksDB instance per chain holding blocks, certificates, the authenticated '
        + 'state, indexes, and vote locks.',
    },
    {
      id: 'services', label: 'Anchor and effects', sub: 'optional services', kind: 'runtime',
      detail: 'Optional per-chain services: anchoring on the anchor leader, L1 observers, finalized-block sinks, and '
        + 'the effect runtime with its executors.',
    },
    {
      id: 'rest', label: 'REST, SSE, clients', sub: '/api/v1/app-chain/…', kind: 'client',
      detail: 'The node’s REST API and server-sent event stream, scoped per chain under '
        + '`/api/v1/app-chain/chains/{chainId}/`. The CLI, the Java client, App-Chain Studio, and the products use '
        + 'them.',
      link: { label: 'REST API', href: '/reference/rest-api/' },
    },
    {
      id: 'plugins', label: 'Plugin catalog', sub: 'manifested bundles', kind: 'runtime',
      detail: 'Loads manifested bundles from the plugin directory and activates their contributions: state '
        + 'machines, effect executors, sinks, APIs, and queries. Plugins run in the node’s process as trusted '
        + 'code; the catalog checks manifests and compatibility, but it is not a sandbox.',
      link: { label: 'Plugin framework', href: '/plugins/' },
    },
  ],
  edges: [
    { from: 'l1', to: 'manager', label: 'L1 events' },
    { from: 'manager', to: 'chain', label: 'verified envelopes' },
    { from: 'rest', to: 'chain', label: 'submit, read, prove' },
    { from: 'plugins', to: 'chain', label: 'contributions' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 502,
      zones: {
        node: [16, 16, 728, 470],
        chain: [32, 198, 696, 196],
      },
      blocks: {
        l1: [32, 50, 696, 48],
        manager: [32, 122, 696, 52],
        pool: [48, 232, 216, 60],
        engine: [272, 232, 216, 60],
        group: [496, 232, 216, 60],
        machine: [48, 312, 216, 60],
        ledger: [272, 312, 216, 60],
        services: [496, 312, 216, 60],
        rest: [32, 418, 336, 52],
        plugins: [392, 418, 336, 52],
      },
      edges: {
        'l1->manager': { fromSide: 'b', toSide: 't', labelAt: [380, 110] },
        'manager->chain': { fromSide: 'b', toSide: 't', fromAt: [380, 174], toAt: [380, 198], labelAt: [470, 186] },
        'rest->chain': { fromSide: 't', toSide: 'b', fromAt: [200, 418], toAt: [200, 394], labelAt: [200, 406] },
        'plugins->chain': { fromSide: 't', toSide: 'b', fromAt: [560, 418], toAt: [560, 394], labelAt: [560, 406] },
      },
    },
    narrow: {
      width: 380,
      height: 556,
      labels: {
        l1: { sub: 'sync and relay' },
        manager: { sub: 'transport, catch-up' },
        pool: { sub: 'admission' },
        services: { label: 'Anchor, effects', sub: 'optional' },
        rest: { sub: 'per chain' },
      },
      zones: {
        node: [8, 8, 364, 540],
        chain: [20, 192, 340, 268],
      },
      blocks: {
        l1: [20, 40, 340, 52],
        manager: [20, 112, 340, 60],
        pool: [32, 226, 152, 60],
        engine: [196, 226, 152, 60],
        group: [32, 302, 152, 60],
        machine: [196, 302, 152, 60],
        ledger: [32, 378, 152, 60],
        services: [196, 378, 152, 60],
        rest: [20, 480, 164, 56],
        plugins: [196, 480, 164, 56],
      },
      edges: {
        'l1->manager': { fromSide: 'b', toSide: 't', label: false },
        'manager->chain': { fromSide: 'b', toSide: 't', fromAt: [190, 172], toAt: [190, 192], label: false },
        'rest->chain': { fromSide: 't', toSide: 'b', fromAt: [102, 480], toAt: [102, 460], label: false },
        'plugins->chain': { fromSide: 't', toSide: 'b', fromAt: [278, 480], toAt: [278, 460], label: false },
      },
    },
  },
  sources: [
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['AppChainManager  ── one per node', 'shared INBOUND transport (appmsg, protocol 100)',
        'shared catch-up server  (protocol 103)', 'message pool (admission, backpressure)',
        'AppChainEngine (consensus, single-threaded)', 'AppLedgerStore (RocksDB: blocks, MPF, indexes)',
        'MemberGroup (static or governed membership)', 'anchor services, observers, sinks (optional)',
        'Chains share only the node\'s networking and its L1 view.', 'In-memory (lost on restart, by design): the pending pool'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainEngine.java',
      anchors: ['The engine is a serial', 'commits each finalized block, state root, and framework'] },
    { repo: 'yano', path: 'app/src/main/java/org/yanoproject/app/api/appchain/AppChainResource.java',
      anchors: ['@Produces(MediaType.SERVER_SENT_EVENTS)'] },
    { repo: 'yano', path: 'adr/app-layer/011-plugin-architecture.md',
      anchors: ['Sandboxing untrusted Java code. In-process plugins are fully trusted code.'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/ClassicJmtAuthenticatedStateBackend.java',
      anchors: ['class ClassicJmtAuthenticatedStateBackend'] },
  ],
};
