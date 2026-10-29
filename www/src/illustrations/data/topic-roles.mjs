// Who creates, manages and writes topics on a chain sealed with a topics:
// block: the three ways to create a top-level namespace (TopicCommandRules
// admitCreate and the draft's create: setting), owners below it, inheritance
// down the name tree (TopicPolicyResolver), and direct or delegated writers
// (TopicWriteRules).

export default {
  id: 'topic-roles',
  type: 'diagram',
  title: 'Who registers, manages and writes',
  tag: 'Concept',
  hint: 'Select a box to see who it is and what it may do.',
  caption: 'Names form a tree. Whoever the draft allows creates a top-level namespace, its owners run everything '
    + 'below it, and each topic\'s policy says who may write: writer keys directly, or users through session keys '
    + 'that a writer delegated.',
  zones: [
    { id: 'create', kind: 'actor', label: 'Create a namespace', contains: ['members', 'registrars', 'anyone'] },
    { id: 'registry', kind: 'ledger', label: 'Topic registry', contains: ['ns-freight', 'ns-acme', 'shipments'] },
  ],
  blocks: [
    {
      id: 'members', label: 'Members vote', sub: 'create: members', kind: 'member',
      detail: 'Each operator runs `topics approve <plan>` on their own node. The namespace is created once the '
        + 'chain\'s block threshold of members has voted and the activation lag has passed. The members can always '
        + 'create or recover a namespace by vote, whatever else the draft allows.',
    },
    {
      id: 'registrars', label: 'Registrars sign', sub: 'create: registrars', kind: 'actor',
      detail: 'Keys named in the draft sign a creation with authority `~registrars`, without a vote. A registrar '
        + 'named by operator signs with that operator\'s member key; name a separate public key to keep them apart. '
        + 'Registrars create top-level names only.',
    },
    {
      id: 'anyone', label: 'Anyone claims', sub: 'create: anyone', kind: 'actor',
      detail: 'The first claim for an unused single-segment name wins. The claim is signed by the owners it names '
        + '(authority `~claim`).',
    },
    {
      id: 'owners', label: 'Namespace owners', sub: 'k-of-n owner keys', kind: 'actor',
      detail: 'The owners of a namespace create entries below it, change their policies, rotate the owner set, '
        + 'retire entries and revoke credentials, with `topics plan`, `sign` and `submit`. Here the consortium owns '
        + '`freight/` and creates `freight/acme/` with Acme\'s operations and security keys as its owners; from then '
        + 'on Acme runs its own topics. A change takes effect at a later height, never retroactively.',
    },
    {
      id: 'other', label: 'Uncovered topics', sub: 'open or reject', kind: 'neutral',
      detail: 'A topic that no entry covers follows the draft\'s `unregistered:` setting. `open`, which `appchain '
        + 'new` writes, accepts it as on a chain without topics; `reject` refuses it with `TOPIC_UNREGISTERED`. '
        + 'Topics starting with `~` are system topics with their own rules.',
    },
    {
      id: 'ns-freight', label: 'freight/', sub: 'top-level namespace', kind: 'ledger',
      detail: 'A namespace ends in `/` and covers every topic below it. Its entry holds the owners and a policy: '
        + '`writers`, `delegate`, `maxDelegationMs`, `maxPayloadBytes` and a schema. The consortium sets defaults '
        + 'here, such as a 4 KB payload limit, that every member company inherits.',
    },
    {
      id: 'ns-acme', label: 'freight/acme/', sub: 'child: overrides fields', kind: 'ledger',
      detail: 'The longest matching entry wins. A child entry can override single policy fields; an omitted field '
        + 'inherits from `freight/`. `writers: open`, an empty `delegate` list and `schema: none` are explicit '
        + 'overrides. Retirement is inherited, and a child cannot undo it.',
    },
    {
      id: 'shipments', label: 'freight/acme/shipments', sub: 'a topic: messages go here', kind: 'final',
      detail: 'Acme\'s delivery scans. Every message on this topic is checked against its effective policy, on '
        + 'every member, before it can enter a block.',
    },
    {
      id: 'issuer', label: 'Login service', sub: 'a writer in delegate', kind: 'client',
      detail: 'An application service whose key is a writer and is listed under `delegate`, such as Acme\'s driver '
        + 'login behind its corporate SSO. After it signs a driver in, it signs a short-lived credential for the '
        + 'driver\'s session key and one scope. Delegation works only with a 1-of-n writer set: a credential stands '
        + 'in for one writer.',
    },
    {
      id: 'session', label: 'Session key', sub: 'in the driver\'s app', kind: 'client',
      detail: 'A non-extractable WebCrypto key that the driver\'s web app makes. It signs each scan, and the '
        + 'write carries the issuer\'s credential. The credential lasts at most `maxDelegationMs` and the chain\'s limit (one hour by default), '
        + 'and the owners or the issuer can revoke it.',
    },
    {
      id: 'writers', label: 'Writers', sub: 'k-of-n writer keys', kind: 'client',
      detail: 'A service holding writer keys signs each write itself, such as Acme\'s warehouse system. The write '
        + 'needs the policy\'s threshold of valid signatures from the listed keys. With `writers: open` anyone may '
        + 'write; size and schema still apply.',
    },
  ],
  edges: [
    { from: 'create', to: 'ns-freight', label: 'create' },
    { from: 'owners', to: 'ns-acme', label: 'manage' },
    { from: 'ns-freight', to: 'ns-acme', style: 'dashed' },
    { from: 'ns-acme', to: 'shipments', style: 'dashed' },
    { from: 'issuer', to: 'session', label: 'credential' },
    { from: 'session', to: 'shipments' },
    { from: 'writers', to: 'shipments' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 420,
      zones: {
        create: [16, 16, 196, 188],
        registry: [268, 16, 240, 388],
      },
      blocks: {
        members: [28, 48, 172, 44],
        registrars: [28, 100, 172, 44],
        anyone: [28, 152, 172, 44],
        owners: [28, 228, 172, 52],
        other: [28, 336, 172, 52],
        'ns-freight': [284, 82, 208, 52],
        'ns-acme': [284, 228, 208, 52],
        shipments: [284, 336, 208, 52],
        issuer: [560, 160, 184, 52],
        session: [560, 250, 184, 52],
        writers: [560, 340, 184, 52],
      },
      edges: {
        'create->ns-freight': { fromSide: 'r', toSide: 'l', fromAt: [212, 108], toAt: [284, 108], labelAt: [240, 96] },
        'owners->ns-acme': { fromSide: 'r', toSide: 'l', labelAt: [242, 242] },
        'issuer->session': { fromSide: 'b', toSide: 't', labelAt: [692, 231] },
        'session->shipments': { fromSide: 'l', toSide: 'r', via: [[528, 276], [528, 352]], toAt: [492, 352] },
        'writers->shipments': { fromSide: 'l', toSide: 'r', fromAt: [560, 374], toAt: [492, 374] },
      },
    },
    narrow: {
      width: 380,
      height: 640,
      zones: {
        create: [8, 8, 364, 156],
        registry: [196, 180, 176, 340],
      },
      blocks: {
        members: [20, 40, 340, 34],
        registrars: [20, 82, 340, 34],
        anyone: [20, 124, 340, 34],
        'ns-freight': [204, 212, 160, 48],
        owners: [8, 276, 168, 52],
        'ns-acme': [204, 276, 160, 52],
        issuer: [8, 356, 168, 52],
        session: [8, 436, 168, 52],
        shipments: [204, 436, 160, 76],
        writers: [8, 516, 168, 52],
        other: [8, 584, 364, 44],
      },
      labels: {
        shipments: { label: 'freight/acme/\nshipments', sub: 'a topic' },
        'ns-acme': { label: 'freight/acme/', sub: 'child: overrides' },
      },
      edges: {
        'create->ns-freight': { fromSide: 'b', toSide: 't', fromAt: [330, 164], toAt: [330, 212], label: false },
        'owners->ns-acme': { fromSide: 'r', toSide: 'l', label: false },
        'issuer->session': { fromSide: 'b', toSide: 't', label: false },
        'session->shipments': { fromSide: 'r', toSide: 'l', fromAt: [176, 462], toAt: [204, 462] },
        'writers->shipments': { fromSide: 'r', toSide: 'l', fromAt: [176, 542], via: [[190, 542], [190, 498]],
          toAt: [204, 498] },
      },
    },
  },
  legend: [['member', 'app ledger member'], ['actor', 'key holders'], ['ledger', 'registry entry'],
    ['client', 'writers'], ['final', 'topic']],
  sources: [
    { repo: 'yano-x', path: 'docs/appchain/deployment/configure.md',
      anchors: ['# who creates namespaces: members (vote) | registrars | anyone',
        '# messages on topics no namespace covers: open | reject'] },
    { repo: 'yano-x', path: 'docs/appchain/TOPIC_SESSION_KEYS.md',
      anchors: ['./yano.sh appchain topics approve topics.plan.json', 'which is also the node\'s consensus key',
        'With `create: anyone`, the new owners sign an `authority: ~claim` creation.'] },
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/NewCommand.java',
      anchors: ['"refuse them; the chain then makes blocks only from registered topics"), "open",'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/topics/TopicCommandRules.java',
      anchors: ['registrars create top-level names', 'open claims are single-segment',
        'a deeper entry is created by an ancestor namespace'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/topics/TopicPolicyResolver.java',
      anchors: ['it applies only to a 1-of-n writer set'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/topics/TopicChainSettings.java',
      anchors: ['positive(values, MAX_DELEGATION_MS, 3_600_000)'] },
    { repo: 'yano-x', path: 'sdk/javascript/topics.mjs',
      anchors: ['export class DelegatedSession', 'it stores a non-extractable CryptoKey, never a seed.'] },
  ],
};
