// The four layers of an app ledger, bottom to top, with what each block does
// and who provides it. Facts follow Yano's consensus guide and Yano X's
// effects, anchoring, and connector documentation; see `sources`.

const ZONE_W = 728;
const COL_X = [32, 269, 506];
const BLOCK_W = 221;
const ZONES_Y = { integration: 16, evidence: 120, execution: 224, consensus: 328 };

const wideRow = (ids, zone) => Object.fromEntries(ids.map((id, i) =>
  [id, [COL_X[i], ZONES_Y[zone] + 34, BLOCK_W, 46]]));

const NARROW_ZONE_H = 170;
const narrowZoneY = (index) => 8 + index * (NARROW_ZONE_H + 10);
const narrowColumn = (ids, index) => Object.fromEntries(ids.map((id, i) =>
  [id, [20, narrowZoneY(index) + 30 + i * 46, 340, 40]]));

const LAYERS = {
  integration: ['clients', 'effects', 'connectors'],
  evidence: ['root', 'proofs', 'anchor'],
  execution: ['machine', 'composite', 'state'],
  consensus: ['members', 'round', 'certificate'],
};

export default {
  id: 'architecture-layers',
  type: 'diagram',
  title: 'The four layers',
  tag: 'Concept',
  hint: 'Select a block to see what it does and who provides it.',
  caption: 'Consensus at the bottom, integration at the top. Members run the execution layer while they vote, so '
    + 'the finality certificate covers its result.',
  zones: [
    { id: 'integration', kind: 'runtime', label: '4 · Integration', contains: LAYERS.integration },
    { id: 'evidence', kind: 'cardano', label: '3 · Evidence', contains: LAYERS.evidence },
    { id: 'execution', kind: 'ledger', label: '2 · Execution', contains: LAYERS.execution },
    { id: 'consensus', kind: 'leader', label: '1 · Consensus', contains: LAYERS.consensus },
  ],
  blocks: [
    {
      id: 'clients', label: 'APIs and clients', sub: 'submit, read, prove', kind: 'client',
      detail: 'Each node serves a REST API and an SSE stream per chain. The CLI, the Java client, App-Chain Studio, '
        + 'and the products use them. Provided by Yano; clients and SDKs by Yano X.',
    },
    {
      id: 'effects', label: 'Effect runtime', sub: 'after finality', kind: 'runtime',
      detail: 'Runs effect records after their block is final, outside consensus, and returns each result as a '
        + 'new message. Provided by Yano.',
      link: { label: 'Effects', href: '/concepts/effects/' },
    },
    {
      id: 'connectors', label: 'Connectors', sub: 'webhook, Kafka, S3, IPFS', kind: 'external',
      detail: 'Executors that perform effects. Yano provides the webhook executor; Yano X adds Kafka, '
        + 'S3-compatible object storage, IPFS, and Cardano payments as optional plugins.',
    },
    {
      id: 'root', label: 'State root', sub: 'one per block', kind: 'core',
      detail: 'The root of the authenticated state after each block, identical on every honest member. Provided '
        + 'by Yano.',
    },
    {
      id: 'proofs', label: 'Proofs', sub: 'inclusion and exclusion', kind: 'core',
      detail: 'A proof shows that a record is, or is not, in the state under a root. Proof subjects let a client '
        + 'ask in application terms. Yano provides proofs; state machines declare their subjects.',
      link: { label: 'State and proofs', href: '/concepts/state-and-proofs/' },
    },
    {
      id: 'anchor', label: 'Cardano anchor', sub: 'optional', kind: 'cardano',
      detail: 'Publishes a certified height, block hash, and state root to Cardano, in transaction metadata or a '
        + 'script-controlled anchor. Finality never waits for it. Provided by Yano.',
      link: { label: 'Cardano anchoring', href: '/concepts/anchoring/' },
    },
    {
      id: 'machine', label: 'State machine', sub: 'deterministic rules', kind: 'core',
      detail: 'The only component that reads message bodies. Every member applies each block with the same code '
        + 'and gets the same result. Yano has `ordered-log` built in; Yano X provides the other stock machines.',
      link: { label: 'Determinism rules', href: '/concepts/determinism-rules/' },
    },
    {
      id: 'composite', label: 'Composite', sub: 'several machines, one root', kind: 'core',
      detail: 'One state machine that hosts several components and the workflows between them under one state '
        + 'root. Provided by Yano X.',
      link: { label: 'Composite state machines', href: '#composite-state-machines' },
    },
    {
      id: 'state', label: 'Authenticated state', sub: 'MPF or JMT', kind: 'core',
      detail: 'Every write a state machine makes is an entry in one authenticated tree. Its type, MPF or JMT, is '
        + 'fixed when the ledger is created. Provided by Yano.',
    },
    {
      id: 'members', label: 'Members', sub: 'signed envelopes', kind: 'member',
      detail: 'Each organization runs a member identified by an Ed25519 key. The member that accepts a message '
        + 'signs its envelope. A ledger has at most 32 members. Provided by Yano.',
    },
    {
      id: 'round', label: 'Two-phase round', sub: 'PREPARE, then COMMIT', kind: 'leader',
      detail: 'A leader proposes each block; members re-execute it and vote twice. A failed leader is replaced '
        + 'through a certified view change. Provided by Yano.',
      link: { label: 'Consensus and finality', href: '/concepts/consensus-and-finality/' },
    },
    {
      id: 'certificate', label: 'Finality certificate', sub: 'threshold signatures', kind: 'final',
      detail: 'COMMIT signatures from a threshold of members. With it, a block is final and is never rolled back. '
        + 'Provided by Yano.',
    },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 436,
      zones: Object.fromEntries(Object.entries(ZONES_Y).map(([id, y]) => [id, [16, y, ZONE_W, 92]])),
      blocks: Object.assign({}, ...Object.entries(LAYERS).map(([zone, ids]) => wideRow(ids, zone))),
    },
    narrow: {
      width: 380,
      height: 4 * NARROW_ZONE_H + 3 * 10 + 16,
      zones: Object.fromEntries(Object.keys(LAYERS).map((id, i) => [id, [8, narrowZoneY(i), 364, NARROW_ZONE_H]])),
      blocks: Object.assign({}, ...Object.entries(LAYERS).map(([, ids], i) => narrowColumn(ids, i))),
    },
  },
  sources: [
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['AppChainEngine (consensus, single-threaded)', 'MemberGroup (static or governed membership)',
        '`ordered-log` | runtime (built-in)', 'Any member holding the round aggregates PREPARE votes'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/AppChainConfig.java',
      anchors: ['MAX_MEMBERS = 32'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/state/StateCommitmentProfiles.java',
      anchors: ['"mpf-blake2b256-v1"', '"jmt-blake2b256-v1"'] },
    { repo: 'yano-x', path: 'docs/core-host.md',
      anchors: ['OrderedLog, consensus, finality, anchoring, the webhook executor and sink'] },
    { repo: 'yano-x', path: 'docs/appchain/OPTIONAL_CONNECTORS.md',
      anchors: ['`executor:kafka`', '`executor:objectstore-s3`', '`executor:ipfs`', '`executor:cardano-payment`'] },
    { repo: 'yano-x', path: 'docs/site/concepts-effects.md',
      anchors: ['**exactly-once incorporation, at-least-once execution**'] },
  ],
};
