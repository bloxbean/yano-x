// What Yano (the host) owns and what Yano X adds on top. Facts follow the
// repository boundary in docs/core-host.md, Yano's consensus guide, and the
// Yano X artifact inventory; see `sources`.

const X_BLOCKS = ['machines', 'composition', 'connectors', 'products', 'tools', 'distribution'];
const HOST_BLOCKS = ['node', 'consensus', 'state', 'anchoring', 'effects', 'spi', 'ordered-log'];

export default {
  id: 'yano-boundary',
  type: 'diagram',
  title: 'Yano and Yano X',
  tag: 'Concept',
  hint: 'Select a block to see what it covers.',
  caption: 'The dependency runs one way: Yano X depends on a released Yano, never the reverse. Everything optional '
    + 'that a running node selects reaches it through the plugin catalog.',
  zones: [
    { id: 'x', kind: 'runtime', label: 'Yano X · application behavior', contains: X_BLOCKS },
    { id: 'host', kind: 'ledger', label: 'Yano · the host', contains: HOST_BLOCKS },
  ],
  blocks: [
    {
      id: 'machines', label: 'State machines', sub: 'all but ordered-log', kind: 'runtime',
      detail: 'Every stock state machine except `ordered-log`: `kv-registry`, `approvals`, `balances`, `doc-trail`, '
        + '`authenticated-map`, `role-approvals`, and more, as JVM plugins.',
      link: { label: 'State machines', href: '/state-machines/' },
    },
    {
      id: 'composition', label: 'Composition', sub: 'composites, bindings', kind: 'runtime',
      detail: 'Composite state machines, governed profile evolution, and declarative bindings, which connect '
        + 'existing machines with YAML instead of Java.',
      link: { label: 'Declarative bindings', href: '/bindings/' },
    },
    {
      id: 'connectors', label: 'Connectors', sub: 'Kafka, S3, IPFS, …', kind: 'runtime',
      detail: 'Optional effect executors for Kafka, S3-compatible object storage, IPFS, and Cardano payments, and a '
        + 'Kafka sink for finalized blocks.',
    },
    {
      id: 'products', label: 'Products', sub: 'Evidence, Attest, …', kind: 'runtime',
      detail: 'Evidence, Cardano History, Attest, Trust Registry, Verifiable Explorer, DPP Starter, Attestation '
        + 'Feed, and eUTxO and ZK.',
      link: { label: 'Products', href: '/products/' },
    },
    {
      id: 'tools', label: 'SDKs and tools', sub: 'client, Studio, CLIs', kind: 'runtime',
      detail: 'The Java client SDK, a Spring Boot starter, testkits, the `appchain` command-line tooling, the '
        + 'deployment tool, and App-Chain Studio. These are libraries and tools, not runtime plugins.',
    },
    {
      id: 'distribution', label: 'JVM distribution', sub: 'one ZIP, pre-installed', kind: 'runtime',
      detail: '`yano-x-jvm-<version>.zip`: the Yano JVM distribution with the Yano X bundles, tools, App-Chain '
        + 'Studio, and the local showcase already in place.',
      link: { label: 'Release downloads', href: '/start-here/release-downloads/' },
    },
    {
      id: 'node', label: 'Cardano node', sub: 'sync, chainstate', kind: 'cardano',
      detail: 'Yano is first a Cardano data node: chain sync, relay, and the L1 `chainstate`.',
    },
    {
      id: 'consensus', label: 'Consensus', sub: 'ordering, finality', kind: 'core',
      detail: 'Message ordering, membership, the two-phase round, and threshold finality certificates.',
      link: { label: 'Consensus and finality', href: '/concepts/consensus-and-finality/' },
    },
    {
      id: 'state', label: 'State and proofs', sub: 'MPF or JMT, catch-up', kind: 'core',
      detail: 'Authenticated state with MPF or JMT profiles, proofs and proof subjects, catch-up, and replay.',
      link: { label: 'State and proofs', href: '/concepts/state-and-proofs/' },
    },
    {
      id: 'anchoring', label: 'Anchoring', sub: 'metadata or script', kind: 'core',
      detail: 'Anchoring certified roots to Cardano, in transaction metadata or a threshold-signed script anchor.',
      link: { label: 'Cardano anchoring', href: '/concepts/anchoring/' },
    },
    {
      id: 'effects', label: 'Effect runtime', sub: 'gates, retries', kind: 'core',
      detail: 'The effect runtime: gates, retries, receipts, and result incorporation, plus the webhook executor '
        + 'and sink.',
      link: { label: 'Effects', href: '/concepts/effects/' },
    },
    {
      id: 'spi', label: 'Plugin SPI', sub: 'manifest, catalog', kind: 'core',
      detail: 'The plugin SPI, the schema-v1 manifest, catalog validation, and plugin lifecycle. Plugins run in the '
        + 'node’s process as trusted code; the catalog is not a sandbox.',
      link: { label: 'Plugin framework', href: '/plugins/' },
    },
    {
      id: 'ordered-log', label: 'ordered-log', sub: 'built-in machine', kind: 'core',
      detail: 'The only built-in state machine: an append-only log of opaque messages with per-message proofs.',
    },
  ],
  edges: [
    { from: 'x', to: 'host', label: 'plugins, through the catalog' },
  ],
  legend: [
    ['runtime', 'Yano X'],
    ['core', 'Yano'],
  ],
  layouts: {
    wide: {
      width: 760,
      height: 416,
      zones: {
        x: [16, 16, 728, 170],
        host: [16, 226, 728, 174],
      },
      blocks: {
        machines: [32, 50, 221, 54],
        composition: [269, 50, 221, 54],
        connectors: [506, 50, 221, 54],
        products: [32, 116, 221, 54],
        tools: [269, 116, 221, 54],
        distribution: [506, 116, 221, 54],
        node: [32, 260, 164, 56],
        consensus: [208, 260, 164, 56],
        state: [384, 260, 164, 56],
        anchoring: [560, 260, 164, 56],
        effects: [120, 330, 164, 56],
        spi: [296, 330, 164, 56],
        'ordered-log': [472, 330, 164, 56],
      },
      edges: {
        'x->host': { fromSide: 'b', toSide: 't', fromAt: [380, 186], toAt: [380, 226], labelAt: [380, 206] },
      },
    },
    narrow: {
      width: 380,
      height: 580,
      zones: {
        x: [8, 8, 364, 232],
        host: [8, 272, 364, 296],
      },
      blocks: {
        machines: [20, 40, 164, 56],
        composition: [196, 40, 164, 56],
        connectors: [20, 104, 164, 56],
        products: [196, 104, 164, 56],
        tools: [20, 168, 164, 56],
        distribution: [196, 168, 164, 56],
        node: [20, 304, 164, 56],
        consensus: [196, 304, 164, 56],
        state: [20, 368, 164, 56],
        anchoring: [196, 368, 164, 56],
        effects: [20, 432, 164, 56],
        spi: [196, 432, 164, 56],
        'ordered-log': [20, 496, 164, 56],
      },
      edges: {
        'x->host': { fromSide: 'b', toSide: 't', fromAt: [190, 240], toAt: [190, 272], label: false },
      },
    },
  },
  sources: [
    { repo: 'yano-x', path: 'docs/core-host.md',
      anchors: ['OrderedLog, consensus, finality, anchoring, the webhook executor and sink, the operations API, and the console UI remain in'] },
    { repo: 'yano-x', path: 'AGENTS.md',
      anchors: ['Dependency direction is strictly `yano-x -> yano`.',
        'Every optional behavior added to a running Yano node crosses the plugin catalog boundary.'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['append-only log of opaque messages; per-message inclusion proofs'] },
    { repo: 'yano', path: 'adr/app-layer/011-plugin-architecture.md',
      anchors: ['In-process plugins are fully trusted code.'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/state/StateCommitmentProfiles.java',
      anchors: ['"mpf-blake2b256-v1"', '"jmt-blake2b256-v1"'] },
    { repo: 'yano-x', path: 'docs/appchain/OPTIONAL_CONNECTORS.md',
      anchors: ['`executor:kafka`, `sink:kafka`', '`executor:objectstore-s3`', '`executor:ipfs`', '`executor:cardano-payment`'] },
    { repo: 'yano-x', path: 'config/artifacts-v1.json',
      anchors: ['"artifactId": "yano-x-client"', '"artifactId": "yano-x-spring-boot-starter"',
        '"artifactId": "yano-x-studio"', '"artifactId": "yano-x-deployment"', '"artifactId": "yano-x-composite"'] },
  ],
};
