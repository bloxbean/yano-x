// The custom-plugin lifecycle from scaffold to a running node, as an explorable
// block diagram. Commands are the launcher's `appchain plugin`, `init`, `doctor`
// and the bundled `yano-plugins` validator.

export default {
  id: 'plugin-pipeline',
  type: 'diagram',
  title: 'From scaffold to every member',
  tag: 'Concept',
  hint: 'Select a step to see its command and what it checks.',
  caption: 'A publisher builds and signs the bundle; an operator pins it into a project, checks readiness and '
    + 'installs the same JAR on every member. Yano itself is never rebuilt.',
  zones: [
    { id: 'publisher', kind: 'external', label: 'Build and sign · publisher',
      contains: ['scaffold', 'implement', 'sign', 'validate'] },
    { id: 'operator', kind: 'ledger', label: 'Deploy · operator', contains: ['pin', 'doctor', 'install', 'load'] },
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
      id: 'pin', label: 'Pin into a project', sub: 'init --plugin-jar', kind: 'ledger',
      detail: '`appchain init --plugin-jar <jar> --trust-key …` stores the signed snapshot under '
        + '`component-catalogs/`. `appchain.lock` pins the catalog, runtime manifest, configuration metadata and '
        + 'complete JAR digests.',
    },
    {
      id: 'doctor', label: 'Check readiness', sub: 'doctor --distribution', kind: 'ledger',
      detail: '`appchain doctor <project> --distribution /opt/yano-x` re-verifies the snapshot. A pinned JAR that is '
        + 'missing or different fails artifact readiness.',
    },
    {
      id: 'install', label: 'Install on\nevery member', sub: 'copy to plugins/', kind: 'ledger',
      detail: 'Copy the exact pinned JAR into `plugins/` on every member, then run '
        + '`tools/yano-plugins/bin/yano-plugins validate plugins/*.jar`. A member with a different JAR can compute '
        + 'a different root and stall.',
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
    { from: 'validate', to: 'pin', label: 'signed JAR' },
    { from: 'pin', to: 'doctor' },
    { from: 'doctor', to: 'install' },
    { from: 'install', to: 'load' },
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
        pin: [572, 208, 168, 60],
        doctor: [392, 208, 168, 60],
        install: [212, 208, 168, 60],
        load: [32, 208, 168, 60],
      },
      edges: {
        'validate->pin': { fromSide: 'b', toSide: 't', labelAt: [600, 152] },
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
        pin: [40, 404, 300, 60],
        doctor: [40, 476, 300, 60],
        install: [40, 548, 300, 60],
        load: [40, 620, 300, 60],
      },
      edges: {
        'validate->pin': { fromSide: 'b', toSide: 't', labelAt: [250, 352] },
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
        './yano.sh appchain doctor [project-directory] [--distribution <path>]',
        '--plugin-jar <path>', '--trust-key <id=public-key>',
        'sign requires --catalog, --runtime-manifest, --seed-file, ',
        'scaffold requires --mode, --id, --package, and --output'] },
    { repo: 'yano', path: 'plugin-catalog/README.md',
      anchors: ['./tools/yano-plugins/bin/yano-plugins validate plugins/example.jar',
        'it does not load provider classes, run static'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/config/YanoPropertyKeys.java',
      anchors: ['"yano.plugins.directory"'] },
    { repo: 'yano-x', path: 'adr/app-layer/open_item.md',
      anchors: ['Signed manifests/JARs or external deployment attestations and provenance policy.'] },
  ],
};
