// A topic is one name that many parts of an app ledger use: routing, reads,
// REST access and encryption on every chain, and, on a chain sealed with a
// topics: block, the registry's writer policy, session keys, payload rules and
// the composite rules that see the verified writer.

export default {
  id: 'topic-touchpoints',
  type: 'diagram',
  title: 'One topic name, used everywhere',
  tag: 'Concept',
  hint: 'Select a box to see what the topic does there.',
  caption: 'On every chain the topic routes, filters and scopes access. On a chain sealed with `topics:`, the topic '
    + 'registry also decides who may write it, and every member enforces that before any state machine sees the '
    + 'message.',
  zones: [
    { id: 'always', kind: 'ledger', label: 'On every chain', contains: ['route', 'read', 'access', 'privacy'] },
    { id: 'registry', kind: 'core', label: 'With a topic registry', contains: ['writers', 'sessions', 'payload',
      'rules'] },
  ],
  blocks: [
    {
      id: 'topic', label: 'freight/acme/shipments', sub: 'one topic name', kind: 'neutral',
      detail: 'Every message names a topic when it is submitted: `POST /messages` with `{"topic": …, "body": …}`. '
        + 'Here, a freight consortium\'s chain carries Acme\'s delivery scans on `freight/acme/shipments`. Topics '
        + 'starting with `~` are reserved for the system.',
    },
    {
      id: 'route', label: 'Route & filter', sub: 'many topics per chain', kind: 'neutral',
      detail: 'The topic is a label for routing and filtering. It does not create a separate log: one chain can '
        + 'carry many topics, and a composite chain gives each component its topic.',
    },
    {
      id: 'read', label: 'Read & stream', sub: 'by-topic queries, SSE', kind: 'neutral',
      detail: '`GET /messages/by-topic/{topic}` lists finalized messages on a topic, `GET /stream?topic=` streams '
        + 'them live, and the Java client\'s `@AppChainListener(topic = …)` receives them.',
    },
    {
      id: 'access', label: 'Submit access', sub: 'topic-scoped API keys', kind: 'neutral',
      detail: 'An API key written as `key=topicA|topicB` may submit only to those topics. This controls who may '
        + 'use a node\'s REST API; the node still signs the envelope with its own member key.',
    },
    {
      id: 'privacy', label: 'Encrypted bodies', sub: 'a key per topic', kind: 'neutral',
      detail: 'Bodies can be encrypted with AES-256-GCM and the topic as associated data, so a key for one topic '
        + 'cannot open another topic\'s messages.',
    },
    {
      id: 'writers', label: 'Who may write', sub: 'writer keys or open', kind: 'core',
      detail: 'The topic\'s policy names its writers: `open`, or a threshold of writer keys. A write on a '
        + 'protected topic carries their signatures, and every member checks them before the message can enter a '
        + 'block.',
    },
    {
      id: 'sessions', label: 'Session keys', sub: 'delegated by a writer', kind: 'core',
      detail: 'A writer listed under `delegate` can sign a short-lived credential for a user\'s session key, so a '
        + 'browser writes without holding the writer key.',
    },
    {
      id: 'payload', label: 'Payload rules', sub: 'size limit and schema', kind: 'core',
      detail: '`maxPayloadBytes` and an optional CBOR schema are checked on every member, even on open topics.',
    },
    {
      id: 'rules', label: 'Composite rules', sub: 'check writer, payload', kind: 'core',
      detail: 'Admission rules of a composite chain can read `writer.kind`, `writer.signers` and `payload.*`: the '
        + 'verified writer and payload, after the topic policy passed.',
    },
  ],
  edges: [
    { from: 'topic', to: 'always', label: 'always' },
    { from: 'topic', to: 'registry', label: 'when sealed with topics:' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 324,
      zones: {
        always: [16, 116, 356, 192],
        registry: [388, 116, 356, 192],
      },
      blocks: {
        topic: [250, 14, 260, 56],
        route: [32, 156, 156, 60],
        read: [200, 156, 156, 60],
        access: [32, 232, 156, 60],
        privacy: [200, 232, 156, 60],
        writers: [404, 156, 156, 60],
        sessions: [572, 156, 156, 60],
        payload: [404, 232, 156, 60],
        rules: [572, 232, 156, 60],
      },
      edges: {
        'topic->always': { fromSide: 'l', toSide: 't', toAt: [194, 116], labelAt: [194, 30] },
        'topic->registry': { fromSide: 'r', toSide: 't', toAt: [566, 116], labelAt: [612, 30] },
      },
    },
    narrow: {
      width: 380,
      height: 528,
      zones: {
        always: [8, 96, 364, 192],
        registry: [8, 324, 364, 192],
      },
      blocks: {
        topic: [40, 12, 300, 56],
        route: [20, 136, 164, 60],
        read: [196, 136, 164, 60],
        access: [20, 212, 164, 60],
        privacy: [196, 212, 164, 60],
        writers: [20, 364, 164, 60],
        sessions: [196, 364, 164, 60],
        payload: [20, 440, 164, 60],
        rules: [196, 440, 164, 60],
      },
      edges: {
        'topic->always': { fromSide: 'b', toSide: 't', fromAt: [120, 68], toAt: [120, 96], label: false },
        'topic->registry': { fromSide: 'r', toSide: 't', fromAt: [340, 40], via: [[364, 40], [364, 306],
          [260, 306]], toAt: [260, 324], label: false },
      },
    },
  },
  legend: [['neutral', 'on every chain'], ['core', 'enforced by the topic registry']],
  sources: [
    { repo: 'yano-x', path: 'docs/appchain/state-machines/ordered-log.md',
      anchors: ['The topic is a label for routing and filtering. It does not create a separate log; one chain can '
        + 'carry many topics.'] },
    { repo: 'yano-x', path: 'docs/APP_CHAIN_USER_GUIDE.md',
      anchors: ['GET /messages/by-topic/{topic}?fromHeight=&limit=', '@AppChainListener(topic = "orders")',
        'restricts *submissions* to the listed', 'AES-256-GCM with the topic as associated data',
        'Names starting with `~` are reserved.'] },
    { repo: 'yano-x', path: 'docs/appchain/bindings/07-admission-rules.md',
      anchors: ['`writer.kind`', '`writer.signers`', 'Derived bytes still obey the target\'s `maxPayloadBytes`'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/topics/TopicWriteRules.java',
      anchors: ['signatures do not meet the writer policy', 'payload exceeds maxPayloadBytes',
        'payload does not satisfy the topic schema'] },
  ],
};
