// The custom-plugin lifecycle from scaffold to a running node, as an explorable
// block diagram. Commands are the launcher's `appchain plugin`, `seal`, `join`
// and `start`, and the bundled `yano-plugins` validator.

export default {
  id: 'plugin-pipeline',
  type: 'diagram',
  title: 'From scaffold to every member',
  tag: 'Concept',
  hint: 'Select a step to see its command and what it checks.',
  caption: 'A publisher builds and signs the bundle; the operators install the same JAR on every member, the '
    + 'sealed application pins its digest, and each node checks it before it starts. Yano itself is never rebuilt.',
  zones: [
    { id: 'publisher', kind: 'external', label: 'Build and sign · publisher',
      contains: ['scaffold', 'implement', 'sign', 'validate'] },
    { id: 'operator', kind: 'ledger', label: 'Deploy · operators', contains: ['install', 'seal', 'join', 'load'] },
  ],
  blocks: [
    {
      id: 'scaffold', label: 'Scaffold', sub: 'plugin scaffold', kind: 'core',
      detail: '`./yano.sh appchain plugin scaffold --mode state-machine --id shipment --package com.example.shipment '
        + '--output shipment-plugin` writes a small buildable project: a provider, its ServiceLoader entry, a runtime '
        + 'manifest and a product catalog. Modes are `state-machine`, `composite-role`, `effect-executor` and `sink`.',
    },
    {
      id: 'implement', label: 'Implement + test', sub: 'codec, rules, tests', kind: 'core',
      detail: 'Write the codec, the admission check and the deterministic transitions, then climb the testing ladder. '
        + 'The generated provider does no business work until you implement it.',
      link: { label: 'Testing and deployment', href: '/plugins/testing-and-deployment/' },
    },
    {
      id: 'sign', label: 'Sign', sub: 'plugin sign', kind: 'core',
      detail: '`plugin sign` signs the exact catalog, runtime manifest and optional configuration metadata with a '
        + 'publisher seed passed by file. The signature authenticates those bytes. It does not approve the code.',
      link: { label: 'SPI and manifest', href: '/plugins/spi-and-manifest/' },
    },
    {
      id: 'validate', label: 'Build + validate', sub: 'plugin validate', kind: 'core',
      detail: 'Build the JAR, then `plugin validate <jar> --trust-key <key-id>=<public-key>` verifies the signature '
        + 'and exports a data-only catalog snapshot. It loads no provider class.',
    },
    {
      id: 'install', label: 'Install on\nevery member', sub: 'copy to plugins/', kind: 'ledger',
      detail: 'Every operator, the coordinator included, copies the exact same JAR into the distribution\'s '
        + '`plugins/`, then runs `tools/yano-plugins/bin/yano-plugins validate plugins/*.jar`. Each operator also '
        + 'verifies the publisher signature with `plugin validate`: `seal` does not read a signed catalog.',
    },
    {
      id: 'seal', label: 'Seal pins digests', sub: 'appchain seal', kind: 'ledger',
      detail: '`./yano.sh appchain seal <app>.draft.yaml` records the SHA-256 digest of every bundle in the '
        + 'coordinator\'s `plugins/` in the sealed `<app>.chain`, so the chain fingerprint covers the plugin set.',
    },
    {
      id: 'join', label: 'Join + start check', sub: 'join, start', kind: 'ledger',
      detail: '`appchain join <app>.chain` refuses when this node\'s bundles differ from the sealed ones, and '
        + '`appchain start` checks them again at every start. A changed bundle refuses to start until the operators '
        + 'agree and each runs `join <app>.chain --accept-bundles`.',
    },
    {
      id: 'load', label: 'Node loads it', sub: 'start-up checks', kind: 'member',
      detail: 'At start-up the node snapshots, scans and correlates the JAR, checks its API range, and builds one '
        + 'shared loader before any plugin code runs. It does not verify the publisher signature.',
      link: { label: 'How plugins load', href: '/plugins/how-plugins-load/' },
    },
  ],
  edges: [
    { from: 'scaffold', to: 'implement' },
    { from: 'implement', to: 'sign' },
    { from: 'sign', to: 'validate' },
    { from: 'validate', to: 'install', label: 'signed JAR' },
    { from: 'install', to: 'seal' },
    { from: 'seal', to: 'join' },
    { from: 'join', to: 'load' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 304,
      zones: {
        publisher: [16, 16, 728, 120],
        operator: [16, 168, 728, 120],
      },
      blocks: {
        scaffold: [32, 56, 168, 60],
        implement: [212, 56, 168, 60],
        sign: [392, 56, 168, 60],
        validate: [572, 56, 168, 60],
        install: [572, 208, 168, 60],
        seal: [392, 208, 168, 60],
        join: [212, 208, 168, 60],
        load: [32, 208, 168, 60],
      },
      edges: {
        'validate->install': { fromSide: 'b', toSide: 't', labelAt: [600, 152] },
      },
    },
    narrow: {
      width: 380,
      height: 708,
      labels: {
        install: { label: 'Install on every member' },
      },
      zones: {
        publisher: [8, 12, 364, 324],
        operator: [8, 368, 364, 324],
      },
      blocks: {
        scaffold: [40, 48, 300, 60],
        implement: [40, 120, 300, 60],
        sign: [40, 192, 300, 60],
        validate: [40, 264, 300, 60],
        install: [40, 404, 300, 60],
        seal: [40, 476, 300, 60],
        join: [40, 548, 300, 60],
        load: [40, 620, 300, 60],
      },
      edges: {
        'validate->install': { fromSide: 'b', toSide: 't', labelAt: [250, 352] },
      },
    },
  },
  legend: [
    ['external', 'publisher'],
    ['ledger', 'operator'],
    ['member', 'node'],
  ],
  sources: [
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainPluginScaffolder.java',
      anchors: ['"state-machine", "composite-role", "effect-executor", "sink"',
        'plugin scaffold output directory is not empty'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectCli.java',
      anchors: ['./yano.sh appchain plugin inspect|validate|sign|scaffold [options]',
        '--trust-key <id=public-key>',
        'sign requires --catalog, --runtime-manifest, --seed-file, ',
        'scaffold requires --mode, --id, --package, and --output'] },
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/SealCommand.java',
      anchors: ['distribution.bundleDigests()'] },
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/JoinCommand.java',
      anchors: ['This distribution\'s plugin bundles differ from the sealed ones: ', '[--accept-bundles]'] },
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/StartCommand.java',
      anchors: ['Plugin bundles changed since this node joined '] },
    { repo: 'yano', path: 'plugin-catalog/README.md',
      anchors: ['./tools/yano-plugins/bin/yano-plugins validate plugins/example.jar',
        'it does not load provider classes, run static'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/config/YanoPropertyKeys.java',
      anchors: ['"yano.plugins.directory"'] },
    { repo: 'yano-x', path: 'adr/app-layer/open_item.md',
      anchors: ['Signed manifests/JARs or external deployment attestations and provenance policy.'] },
  ],
};
