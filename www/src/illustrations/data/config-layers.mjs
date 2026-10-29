// Where each setting of a multi-operator application lives (ADR-056): the
// coordinator's draft and the operators' member cards are sealed into one
// file, and `join` turns it into each operator's node configuration. Facts
// come from the appchain-cli seal, join and node-directory code.

const CLI = 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli';

export default {
  id: 'config-layers',
  type: 'diagram',
  title: 'Where each setting lives',
  tag: 'Concept',
  hint: 'Select a file to see what it holds and who may change it.',
  caption: 'The coordinator edits the draft. `seal` combines it with the member cards into one sealed file that '
    + 'every operator receives. `join` writes each node\'s configuration from it; private keys stay in the node '
    + 'directory.',
  zones: [
    { id: 'nodedir', kind: 'core', label: 'Node directory · config/', contains: ['consensus', 'nodeyaml', 'localyaml'] },
    { id: 'private', kind: 'external', label: 'Private · this node', contains: ['secrets'] },
  ],
  blocks: [
    {
      id: 'draft', label: 'supply.draft.yaml', sub: 'coordinator edits', kind: 'actor',
      detail: 'Written by `new` and edited freely until seal: the invite code, network, operators (the first is the '
        + 'lead), threshold, membership, sequencing, and the chains with their recipes, answers or bindings. It '
        + 'holds no keys.',
    },
    {
      id: 'cards', label: '<name>.member', sub: 'one per operator', kind: 'client',
      detail: 'Written by `request` on each operator\'s machine: the name, public key, the node and HTTP addresses '
        + 'other members use, and a signature by that key over the invite code. Public; it carries no private key.',
    },
    {
      id: 'chain', label: 'supply.chain', sub: 'same for everyone', kind: 'ledger',
      detail: 'Written by `seal`. Its **genesis** section holds everything consensus depends on: members, '
        + 'threshold, the exact settings of every chain, the Yano and Yano X versions, and the plugin bundle '
        + 'digests. The chain fingerprint is a hash of that section. Its **peers** section holds addresses, which '
        + 'can change without a new chain.',
    },
    {
      id: 'consensus', label: 'consensus.yaml', sub: 'sealed settings', kind: 'ledger',
      detail: '`config/consensus.yaml`: the sealed settings of every chain, written by `join` as they are. Do not '
        + 'edit it; run `join` again instead.',
    },
    {
      id: 'nodeyaml', label: 'node.yaml', sub: 'ports, peers, paths', kind: 'runtime',
      detail: '`config/node.yaml`: this node\'s ports, its peers from the sealed file (or `join --peer`), and its '
        + 'storage paths under `data/`. It may differ between members. `join` rewrites it.',
    },
    {
      id: 'localyaml', label: 'local.yaml', sub: 'your own settings', kind: 'runtime',
      detail: '`config/local.yaml`: settings for this node alone, such as connector endpoints. `join` creates it '
        + 'and never rewrites it, and its values win over `node.yaml`. `start` refuses it if it sets any sealed '
        + 'consensus setting.',
    },
    {
      id: 'secrets', label: 'secrets/', sub: 'member.key, api.key', kind: 'external',
      detail: '`secrets/member.key` signs this member\'s votes and never leaves the machine. `secrets/api.key` '
        + 'authorizes calls to this node\'s API. `secrets/anchor.key` appears only on an anchor leader. All are '
        + 'readable by their owner only. Back up the node directory.',
    },
    {
      id: 'node', label: 'your node', sub: 'loads all', kind: 'member',
      detail: '`yano.sh appchain start` checks the plugin bundles against the accepted digests and that '
        + '`local.yaml` leaves the sealed settings alone, then starts the node with `local.yaml`, `node.yaml` and '
        + '`consensus.yaml` (the first wins) and the keys from `secrets/`.',
    },
  ],
  edges: [
    { from: 'draft', to: 'chain', label: 'seal' },
    { from: 'cards', to: 'chain' },
    { from: 'chain', to: 'nodedir', label: 'join' },
    { from: 'nodedir', to: 'node' },
    { from: 'private', to: 'node', label: 'keys', style: 'dashed' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 356,
      zones: {
        nodedir: [440, 16, 176, 196],
        private: [440, 228, 176, 112],
      },
      blocks: {
        draft: [16, 40, 184, 56],
        cards: [16, 136, 184, 56],
        chain: [232, 82, 176, 68],
        consensus: [456, 40, 144, 46],
        nodeyaml: [456, 96, 144, 46],
        localyaml: [456, 152, 144, 46],
        secrets: [456, 268, 144, 56],
        node: [648, 118, 100, 76],
      },
      edges: {
        'draft->chain': { fromSide: 'r', toSide: 'l', toAt: [232, 104] },
        'cards->chain': { fromSide: 'r', toSide: 'l', toAt: [232, 128] },
        'chain->nodedir': { fromSide: 'r', toSide: 'l', fromAt: [408, 116], toAt: [440, 116], label: false },
        'nodedir->node': { fromSide: 'r', toSide: 'l', fromAt: [616, 150], toAt: [648, 150] },
        'private->node': { fromSide: 'r', toSide: 'b', fromAt: [616, 296], toAt: [698, 194], labelAt: [668, 280] },
      },
    },
    narrow: {
      width: 380,
      height: 676,
      zones: {
        nodedir: [8, 256, 364, 160],
        private: [8, 580, 364, 84],
      },
      blocks: {
        draft: [16, 12, 168, 56],
        cards: [196, 12, 168, 56],
        chain: [100, 120, 180, 68],
        consensus: [24, 288, 160, 50],
        nodeyaml: [196, 288, 160, 50],
        localyaml: [110, 352, 160, 50],
        node: [120, 456, 140, 76],
        secrets: [100, 612, 180, 44],
      },
      edges: {
        'draft->chain': { fromSide: 'b', toSide: 't', toAt: [160, 120] },
        'cards->chain': { fromSide: 'b', toSide: 't', toAt: [220, 120], label: false },
        'chain->nodedir': { fromSide: 'b', toSide: 't' },
        'nodedir->node': { fromSide: 'b', toSide: 't' },
        'private->node': { fromSide: 't', toSide: 'b', labelAt: [250, 560] },
      },
    },
  },
  legend: [
    ['actor', 'the coordinator edits'],
    ['ledger', 'same on every member'],
    ['runtime', 'node-local'],
    ['external', 'private, this node only'],
  ],
  sources: [
    { repo: 'yano-x', path: `${CLI}/SealCommand.java`,
      anchors: ['listing.filter(path -> path.toString().endsWith(".member"))', 'new SealedApp.Genesis(',
        'distribution.bundleDigests()'] },
    { repo: 'yano-x', path: `${CLI}/NodeConfig.java`,
      anchors: ['# Generated by yano.sh appchain join; do not edit. Re-run join instead.',
        'node.path("config", "consensus.yaml")', 'node.path("config", "node.yaml")',
        'node.path("config", "local.yaml")', 'config/local.yaml changes sealed consensus settings: '] },
    { repo: 'yano-x', path: `${CLI}/NodeDir.java`,
      anchors: ['path("secrets", "member.key")', 'path("secrets", "api.key")'] },
    { repo: 'yano-x', path: `${CLI}/StartCommand.java`,
      anchors: ['Plugin bundles changed since this node joined ', 'environment.put("YANO_APPCHAIN_SIGNING_KEY", node.seed());'] },
  ],
};
