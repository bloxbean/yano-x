// How a generated project splits configuration: one edited blueprint, three
// generated layers, private per-member values, and the node that combines
// them. Facts come from the devtools renderer, resolver, and operations.

export default {
  id: 'config-layers',
  type: 'diagram',
  title: 'Where each setting lives',
  tag: 'Concept',
  hint: 'Select a file to see what it holds and who may change it.',
  caption: 'You edit `appchain.yaml`. `render` generates the shared and per-node files and the lock from it. '
    + 'Each member adds its own private values, and its node loads all three.',
  zones: [
    { id: 'generated', kind: 'core', label: 'Generated · do not edit', contains: ['shared', 'nodes', 'lock'] },
    { id: 'private', kind: 'external', label: 'Private · per member', contains: ['env'] },
  ],
  blocks: [
    {
      id: 'blueprint', label: 'appchain.yaml', sub: 'public intent', kind: 'actor',
      detail: 'Recipe, network, chains, member public keys, hosts, and ports. It holds no private keys. This is the '
        + 'only file you edit; Studio downloads it, and `init` and `chain add` write it.',
    },
    {
      id: 'render', label: 'render', sub: 'checks, then writes', kind: 'client',
      detail: '`./yano.sh appchain render` regenerates every derived file and the lock from `appchain.yaml`. It first '
        + 'compares each generated file with its digest in the lock and refuses a hand edit. Once a deployment has '
        + 'started, it also refuses changed consensus values: “Retained deployment changed. Run appchain plan…”.',
    },
    {
      id: 'shared', label: 'shared-consensus.yaml', sub: 'same on every member', kind: 'ledger',
      detail: '`config/shared-consensus.yaml`: values every member must share, such as the chain id, the member '
        + 'keys, the threshold, the state machine, and the genesis-selected state identity. A member with a different '
        + 'value cannot agree with the others.',
    },
    {
      id: 'nodes', label: 'nodes/nodeN.yaml', sub: 'one per member', kind: 'runtime',
      detail: '`config/nodes/nodeN.yaml`: one member\'s HTTP and node ports, peers, storage paths, and secret '
        + 'references such as `${YANO_APPCHAIN_API_KEYS:}`. These values may differ between members.',
    },
    {
      id: 'lock', label: 'appchain.lock', sub: 'values and file digests', kind: 'core',
      detail: 'The exact resolved values and the digest of every generated file. `render` and `config validate` use '
        + 'the digests to find hand edits, and the start script refuses a lock that differs from the applied one: '
        + '“Deployment lock changed; apply the reviewed plan before starting”.',
    },
    {
      id: 'env', label: 'secrets/nodeN.env', sub: 'never committed', kind: 'external',
      detail: 'One member\'s private values: `YANO_APPCHAIN_SIGNING_KEY` and `YANO_APPCHAIN_API_KEYS`. On a local '
        + 'devnet, `prepare` creates them with owner-only permissions; on real machines each operator supplies the '
        + 'file. The project\'s `.gitignore` excludes `secrets/*.env`.',
    },
    {
      id: 'node', label: 'member node', sub: 'shared + node file + secrets', kind: 'member',
      detail: '`scripts/start-node` runs `appchain start-check`, loads that member\'s `.env` file, and starts Yano '
        + 'with `shared-consensus.yaml` plus the member\'s node file.',
    },
  ],
  edges: [
    { from: 'blueprint', to: 'render' },
    { from: 'render', to: 'generated', label: 'writes' },
    { from: 'generated', to: 'node' },
    { from: 'private', to: 'node', label: 'private values', style: 'dashed' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 408,
      zones: {
        generated: [208, 16, 288, 260],
        private: [208, 292, 288, 100],
      },
      blocks: {
        blueprint: [16, 40, 168, 64],
        render: [16, 150, 168, 64],
        shared: [224, 56, 256, 52],
        nodes: [224, 124, 256, 52],
        lock: [224, 192, 256, 52],
        env: [224, 330, 256, 52],
        node: [536, 150, 208, 76],
      },
      edges: {
        'blueprint->render': { fromSide: 'b', toSide: 't' },
        'render->generated': { fromSide: 'r', toSide: 'l', toAt: [208, 182], label: false },
        'generated->node': { fromSide: 'r', toSide: 'l', fromAt: [496, 188], toAt: [536, 188] },
        'private->node': { fromSide: 'r', toSide: 'b', fromAt: [496, 342], toAt: [640, 226], labelAt: [584, 330] },
      },
    },
    narrow: {
      width: 380,
      height: 640,
      labels: { node: { sub: 'loads all three' } },
      zones: {
        generated: [8, 172, 364, 236],
        private: [8, 532, 364, 92],
      },
      blocks: {
        blueprint: [100, 12, 180, 56],
        render: [100, 92, 180, 56],
        shared: [24, 208, 332, 52],
        nodes: [24, 272, 332, 52],
        lock: [24, 336, 332, 52],
        node: [100, 432, 180, 76],
        env: [24, 568, 332, 44],
      },
      edges: {
        'blueprint->render': { fromSide: 'b', toSide: 't' },
        'render->generated': { fromSide: 'b', toSide: 't', label: false },
        'generated->node': { fromSide: 'b', toSide: 't' },
        'private->node': { fromSide: 't', toSide: 'b', labelAt: [250, 520] },
      },
    },
  },
  legend: [
    ['actor', 'you edit'],
    ['ledger', 'shared by every member'],
    ['runtime', 'node-local'],
    ['external', 'private, per member'],
  ],
  sources: [
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectRenderer.java',
      anchors: ['"config/shared-consensus.yaml"', '"config/nodes/node" + index + ".yaml"',
        'export QUARKUS_CONFIG_LOCATIONS="$root/config/shared-consensus.yaml,$config"',
        '"$YANO_HOME/yano.sh" appchain start-check "$root"', 'values.put("yano.app-chain.api.keys", "${YANO_APPCHAIN_API_KEYS:}");',
        'values.put("quarkus.http.port", Integer.toString(httpPort));', 'Generated file has manual edits: ',
        'Retained deployment changed. Run appchain plan, stop the nodes, ', 'secrets/*.env'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectResolver.java',
      anchors: ['consensus.put(prefix + "chain-id", chain.chainId());', 'consensus.put(prefix + "members", members);',
        'consensus.put(prefix + "threshold", Integer.toString(threshold));',
        'nodeTemplate.put(prefix + "signing-key", "${YANO_APPCHAIN_SIGNING_KEY}");',
        'materializeStateCommitmentIdentity('] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectOperations.java',
      anchors: ['atomicWrite(file, ("YANO_APPCHAIN_SIGNING_KEY=" + seed', 'PosixFilePermissions.fromString("rw-------")',
        'Deployment lock changed; apply the reviewed plan before starting',
        'prepare generates local JVM devnet identities only'] },
  ],
};
