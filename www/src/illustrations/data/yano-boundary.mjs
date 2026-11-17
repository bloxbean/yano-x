// What Yano (the host) owns and what Yano X adds on top. Facts follow the
// repository boundary in docs/core-host.md, Yano's consensus guide, and the
// Yano X artifact inventory; see `sources`.

const X_BLOCKS = ['consensus', 'state', 'ordered-log', 'anchoring', 'effects', 'machines',
  'composition', 'connectors', 'products', 'tools', 'distribution'];
const HOST_BLOCKS = ['node', 'spi'];

export default {
  id: 'yano-boundary',
  type: 'diagram',
  title: 'Yano and Yano X',
  tag: 'Concept',
  hint: 'Select a block to see what it covers.',
  caption: 'The Yano X framework uses Yano node contracts and the Host API. Integrated Yano bundles a compatible '
    + 'framework; optional behavior reaches the running node through the plugin catalog.',
  zones: [
    { id: 'x', kind: 'runtime', label: 'Yano X · framework and extensions', contains: X_BLOCKS },
    { id: 'host', kind: 'ledger', label: 'Yano · the host', contains: HOST_BLOCKS },
  ],
  blocks: [
    {
      id: 'machines', label: 'State machines', sub: 'optional stock plugins', kind: 'runtime',
      detail: 'Beyond the framework’s `ordered-log`, stock JVM plugins include `kv-registry`, `approvals`, '
        + '`balances`, `doc-trail`, `authenticated-map`, `role-approvals`, and more.',
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
      id: 'products', label: 'Products', sub: 'Evidence, History, …', kind: 'runtime',
      detail: 'Evidence, Cardano History, and the eUTxO ledger.',
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
      detail: 'Yano owns Cardano chain sync, relay, and L1 chainstate. Its standalone app-ledger mode hosts the '
        + 'framework without starting Cardano services.',
    },
    {
      id: 'consensus', label: 'Consensus', sub: 'ordering, finality', kind: 'runtime',
      detail: 'Message ordering, membership, the two-phase round, and threshold finality certificates.',
      link: { label: 'Consensus and finality', href: '/concepts/consensus-and-finality/' },
    },
    {
      id: 'state', label: 'State and proofs', sub: 'MPF or JMT, catch-up', kind: 'runtime',
      detail: 'Authenticated state with MPF or JMT profiles, proofs and proof subjects, catch-up, and replay.',
      link: { label: 'State and proofs', href: '/concepts/state-and-proofs/' },
    },
    {
      id: 'anchoring', label: 'Anchoring', sub: 'optional Cardano', kind: 'runtime',
      detail: 'The optional framework Cardano adapter anchors certified roots in transaction metadata or a '
        + 'threshold-signed script anchor, using Yano’s Cardano services.',
      link: { label: 'Cardano anchoring', href: '/concepts/anchoring/' },
    },
    {
      id: 'effects', label: 'Effect runtime', sub: 'gates, retries', kind: 'runtime',
      detail: 'The effect runtime: gates, retries, receipts, and result incorporation, plus the webhook executor '
        + 'and sink.',
      link: { label: 'Effects', href: '/concepts/effects/' },
    },
    {
      id: 'spi', label: 'Plugin SPI', sub: 'manifest, catalog', kind: 'core',
      detail: 'The node contracts, versioned Host API, schema-v1 manifest, catalog validation, and plugin lifecycle. '
        + 'Plugins run in the node’s process as trusted code; the catalog is not a sandbox.',
      link: { label: 'Plugin framework', href: '/plugins/' },
    },
    {
      id: 'ordered-log', label: 'ordered-log', sub: 'framework default', kind: 'runtime',
      detail: 'The framework’s default state machine: an append-only log of opaque messages with per-message proofs.',
    },
  ],
  edges: [
    { from: 'x', to: 'host', label: 'Host API and plugin catalog' },
  ],
  legend: [
    ['runtime', 'Yano X'],
    ['core', 'Yano'],
  ],
  layouts: {
    wide: {
      width: 760,
      height: 456,
      zones: { x: [16, 16, 728, 298], host: [16, 354, 728, 86] },
      blocks: {
        ...Object.fromEntries(X_BLOCKS.map((id, i) =>
          [id, [32 + (i % 3) * 237, 50 + Math.floor(i / 3) * 64, 221, 54]])),
        node: [148, 386, 221, 40],
        spi: [391, 386, 221, 40],
      },
      edges: {
        'x->host': { fromSide: 'b', toSide: 't', fromAt: [380, 314], toAt: [380, 354], labelAt: [380, 334] },
      },
    },
    narrow: {
      width: 380,
      height: 580,
      zones: { x: [8, 8, 364, 424], host: [8, 472, 364, 96] },
      blocks: {
        ...Object.fromEntries(X_BLOCKS.map((id, i) =>
          [id, [20 + (i % 2) * 176, 40 + Math.floor(i / 2) * 64, 164, 56]])),
        node: [20, 504, 164, 48],
        spi: [196, 504, 164, 48],
      },
      edges: {
        'x->host': { fromSide: 'b', toSide: 't', fromAt: [190, 432], toAt: [190, 472], label: false },
      },
    },
  },
  sources: [
    { repo: 'yano-x', path: 'docs/core-host.md',
      anchors: ['Yano owns the Cardano node runtime, Cardano synchronization and ledger services',
        'consensus/finality engine, storage, OrderedLog, query and effect execution',
        'Yano X also composes the integrated JVM product'] },
    { repo: 'yano-x', path: 'AGENTS.md',
      anchors: ['Every optional behavior added to a running Yano node crosses the plugin catalog boundary.'] },
    { repo: 'yano-x', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['append-only log of opaque messages; per-message inclusion proofs'] },
    { repo: 'yano', path: 'adr/app-layer/011-plugin-architecture.md',
      anchors: ['In-process plugins are fully trusted code.'] },
    { repo: 'yano-x', path: 'framework/api/src/main/java/org/yanoproject/x/framework/api/appchain/state/StateCommitmentProfiles.java',
      anchors: ['"mpf-blake2b256-v1"', '"jmt-blake2b256-v1"'] },
    { repo: 'yano-x', path: 'docs/appchain/OPTIONAL_CONNECTORS.md',
      anchors: ['`executor:kafka`, `sink:kafka`', '`executor:objectstore-s3`', '`executor:ipfs`', '`executor:cardano-payment`'] },
    { repo: 'yano-x', path: 'config/artifacts-v1.json',
      anchors: ['"artifactId": "yano-x-client"', '"artifactId": "yano-x-spring-boot-starter"',
        '"artifactId": "yano-x-studio"', '"artifactId": "yano-x-deployment"', '"artifactId": "yano-x-composite"'] },
  ],
};
