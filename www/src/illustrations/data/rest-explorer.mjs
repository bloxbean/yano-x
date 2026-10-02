// The chain-scoped REST surface grouped by the access each endpoint needs.
// Paths and access levels come from Yano's AppChainResource (annotations and
// the unannotated defaults documented on AppChainAccess), the API key filter,
// and the plugin domain and operations resources.

const CHAIN = 'Paths are relative to `/api/v1/app-chain/chains/{chainId}/`. ';

export default {
  id: 'rest-explorer',
  type: 'diagram',
  title: 'The REST API by access',
  tag: 'Concept',
  hint: 'Select a group to list its endpoints.',
  caption: 'Reads and submissions are public unless broad API-key authentication is on; then they need a key, and a '
    + 'topic-restricted key may submit only to its topics. Privileged calls always need a full API key, and snapshot '
    + 'lifecycle calls need a key from their own realm.',
  zones: [
    { id: 'reads', kind: 'core', label: 'Read', contains: ['messages', 'blocks', 'proofs', 'stream', 'effects', 'snapshots', 'query'] },
    { id: 'submit-zone', kind: 'client', label: 'Submit', contains: ['submit'] },
    { id: 'snapshot-admin', kind: 'external', label: 'Snapshot admin key', contains: ['lifecycle'] },
    { id: 'privileged', kind: 'runtime', label: 'Privileged · full key', contains: ['admin', 'identity', 'workers', 'observations', 'ledger'] },
    { id: 'plugins', kind: 'member', label: 'Plugins', contains: ['routes', 'operations'] },
  ],
  blocks: [
    {
      id: 'messages', label: 'Messages', sub: 'by id or topic', kind: 'core',
      detail: `${CHAIN}\`GET messages?limit=&topic=\` lists recently accepted messages, local and gossiped. `
        + '`GET messages/{id}` returns one finalized message with its height and index. '
        + '`GET messages/by-topic/{topic}` and `GET messages/by-sender/{senderHex}` list finalized references, '
        + 'ascending, with `fromHeight` and `limit`.',
    },
    {
      id: 'blocks', label: 'Blocks and status', sub: 'tip, roots, blocks', kind: 'core',
      detail: `${CHAIN}\`GET status\`, \`GET tip\`, \`GET blocks?from=&limit=\` (summaries with \`certSignatures\`), `
        + '`GET blocks/{height}` (one block with its messages), `GET state/identity`, `GET state/oldest-provable`, '
        + 'and `GET anchor/commitment`. `GET /api/v1/app-chain/chains` lists the hosted chains.',
    },
    {
      id: 'proofs', label: 'Proofs', sub: 'state and typed', kind: 'core',
      detail: `${CHAIN}\`GET state/proof/{keyHex}\` and \`GET state/entry/{keyHex}\` (add \`?height=\` for a `
        + 'retained height), `GET messages/{id}/proof`, `GET messages/{id}/proof-package`, `GET evidence/{id}`, '
        + '`GET proof-subjects`, `POST proof-subjects/{subjectId}/proof`, `POST proof-subjects/{subjectId}/package`, '
        + 'and `POST proof/verify`. The POST forms are reads: the body only carries parameters.',
    },
    {
      id: 'stream', label: 'Stream', sub: 'server-sent events', kind: 'core',
      detail: `${CHAIN}\`GET stream?fromHeight=&topic=\` streams finalized messages, replayed from \`fromHeight\`, `
        + 'then live.',
    },
    {
      id: 'effects', label: 'Effect records', sub: 'emitted effects', kind: 'core',
      detail: `${CHAIN}\`GET effects?fromHeight=&limit=\`, \`GET effects/{height}/{ordinal}\`, `
        + '`GET effects/{height}/{ordinal}/proof`, and `GET effects/stats`.',
    },
    {
      id: 'snapshots', label: 'Snapshots', sub: 'period datasets', kind: 'core',
      detail: `${CHAIN}\`GET snapshots\`, \`GET snapshots/{series}/{sequence}\`, `
        + '`POST snapshots/{series}/{sequence}/proof`, `POST snapshots/proof/verify`, and `GET snapshots/status`: '
        + 'authenticated snapshots and their proofs.',
    },
    {
      id: 'query', label: 'Query', sub: 'read hooks', kind: 'core',
      detail: `${CHAIN}\`POST query/{path}\` runs the state machine's read hook against one committed snapshot. `
        + 'It is a POST, but a read: no state changes.',
    },
    {
      id: 'submit', label: 'POST messages', sub: 'topic and body', kind: 'client',
      detail: `${CHAIN}\`POST messages\` with \`{topic, body}\` or \`{topic, bodyHex}\`. The receiving node signs the `
        + 'envelope and answers 202 with a `messageId`; 400 is an application rejection, 429 a full pool, and 503 a '
        + 'node that cannot accept. A topic-restricted key may submit only to its topics.',
    },
    {
      id: 'lifecycle', label: 'Snapshot lifecycle', sub: 'archive, restore, evict', kind: 'external',
      detail: `${CHAIN}\`POST admin/snapshots/{series}/{sequence}/archive\`, \`…/restore\`, and \`…/evict\`, plus `
        + '`GET admin/snapshots/jobs` and `GET admin/snapshots/jobs/{jobId}`. They need a key from '
        + '`yano.app-chain.api.snapshot-admin-keys`.',
    },
    {
      id: 'admin', label: 'Admin', sub: 'members, pause', kind: 'runtime',
      detail: `${CHAIN}\`GET admin/members\`; \`POST admin/members/add\`, \`…/remove\`, and \`…/reset\`; `
        + '`POST admin/threshold`; `POST admin/pause`, `admin/resume`, `admin/drain-pool`, `admin/force-anchor`, '
        + 'and `admin/anchor/bootstrap`.',
    },
    {
      id: 'identity', label: 'Identity', sub: 'drift and integrity', kind: 'runtime',
      detail: `${CHAIN}\`GET identity\` returns the redacted identity digests that \`appchain drift\` compares. `
        + '`GET state/integrity` runs the node\'s state integrity check.',
    },
    {
      id: 'workers', label: 'Effect workers', sub: 'claim and report', kind: 'runtime',
      detail: `${CHAIN}\`POST effects/claim\`, \`POST effects/{height}/{ordinal}/report\`, \`…/requeue\`, and `
        + '`…/cancel`. These can move real funds, so a topic-restricted key may never call them.',
    },
    {
      id: 'observations', label: 'Observations', sub: 'reports and wake', kind: 'runtime',
      detail: `${CHAIN}\`POST observations/reports\` and \`POST observations/wake\`.`,
    },
    {
      id: 'ledger', label: 'Ledger snapshot', sub: 'for onboarding', kind: 'runtime',
      detail: `${CHAIN}\`POST snapshot\` with \`{"path": …}\` writes an atomic ledger snapshot to a fresh directory `
        + 'on the node.',
    },
    {
      id: 'routes', label: 'Domain routes', sub: 'declared per route', kind: 'member',
      detail: '`GET` or `POST /api/v1/plugins/{bundleId}/{path}`. Each route declares whether it is a read or '
        + 'privileged; an undeclared or internal route answers 404. A POST body may hold at most 65,536 bytes, and '
        + 'errors are `{code, error}`.',
    },
    {
      id: 'operations', label: 'Plugin operations', sub: 'catalog and status', kind: 'member',
      detail: '`GET /api/v1/plugin-operations`, `GET /api/v1/plugin-operations/bundles`, and '
        + '`GET /api/v1/plugin-operations/bundles/{bundleId}`. Always privileged.',
    },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 452,
      zones: {
        reads: [16, 16, 480, 236],
        'submit-zone': [512, 16, 232, 104],
        'snapshot-admin': [512, 136, 232, 116],
        privileged: [16, 268, 480, 168],
        plugins: [512, 268, 232, 168],
      },
      blocks: {
        messages: [32, 56, 140, 52],
        blocks: [180, 56, 160, 52],
        proofs: [348, 56, 132, 52],
        stream: [32, 120, 140, 52],
        effects: [180, 120, 160, 52],
        snapshots: [348, 120, 132, 52],
        query: [32, 184, 140, 52],
        submit: [528, 56, 200, 52],
        lifecycle: [528, 176, 200, 60],
        admin: [32, 308, 140, 52],
        identity: [180, 308, 160, 52],
        workers: [348, 308, 132, 52],
        observations: [32, 372, 140, 52],
        ledger: [180, 372, 160, 52],
        routes: [528, 308, 200, 52],
        operations: [528, 372, 200, 52],
      },
    },
    narrow: {
      width: 380,
      height: 912,
      labels: { blocks: { label: 'Blocks, status', sub: 'tip, roots' } },
      zones: {
        reads: [8, 8, 364, 300],
        'submit-zone': [8, 324, 364, 100],
        'snapshot-admin': [8, 440, 364, 100],
        privileged: [8, 556, 364, 236],
        plugins: [8, 808, 364, 92],
      },
      blocks: {
        messages: [20, 44, 166, 52],
        blocks: [194, 44, 166, 52],
        proofs: [20, 108, 166, 52],
        stream: [194, 108, 166, 52],
        effects: [20, 172, 166, 52],
        snapshots: [194, 172, 166, 52],
        query: [20, 236, 166, 52],
        submit: [20, 360, 340, 52],
        lifecycle: [20, 476, 340, 52],
        admin: [20, 592, 166, 52],
        identity: [194, 592, 166, 52],
        workers: [20, 656, 166, 52],
        observations: [194, 656, 166, 52],
        ledger: [20, 720, 166, 52],
        routes: [20, 844, 166, 44],
        operations: [194, 844, 166, 44],
      },
    },
  },
  legend: [
    ['core', 'read'],
    ['client', 'submit'],
    ['runtime', 'privileged'],
    ['external', 'snapshot admin'],
    ['member', 'plugin routes'],
  ],
  sources: [
    { repo: 'yano', path: 'app/src/main/java/org/yanoproject/app/api/appchain/AppChainResource.java',
      anchors: ['@Path("chains/{chainId}")', '@Path("messages/by-topic/{topic}")', '@Path("messages/by-sender/{senderHex}")',
        '@Path("blocks/{height}")', '@Path("state/proof/{keyHex}")', '@Path("state/entry/{keyHex}")',
        '@Path("state/oldest-provable")', '@Path("anchor/commitment")', '@Path("evidence/{messageIdHex}")',
        '@Path("messages/{messageIdHex}/proof-package")', '@Path("proof-subjects/{subjectId}/proof")',
        '@Path("proof-subjects/{subjectId}/package")', '@Path("proof/verify")', '@Path("stream")',
        '@Path("effects/{height}/{ordinal}/proof")', '@Path("effects/stats")', '@Path("snapshots/{series}/{sequence}/proof")',
        '@Path("snapshots/proof/verify")', '@Path("snapshots/status")', '@Path("query/{path: .+}")',
        '@Path("admin/snapshots/{series}/{sequence}/{operation:archive|restore|evict}")',
        '@Path("admin/snapshots/jobs/{jobId}")', '@AppChainAccess(AppChainAccess.Level.SNAPSHOT_ADMIN)',
        '@Path("admin/members/add")', '@Path("admin/members/remove")', '@Path("admin/members/reset")',
        '@Path("admin/threshold")', '@Path("admin/drain-pool")', '@Path("admin/force-anchor")',
        '@Path("admin/anchor/bootstrap")', '@Path("identity")', '@Path("state/integrity")', '@Path("effects/claim")',
        '@Path("effects/{height}/{ordinal}/report")', '@Path("effects/{height}/{ordinal}/requeue")',
        '@Path("effects/{height}/{ordinal}/cancel")', '@Path("observations/reports")', '@Path("observations/wake")',
        '@Path("snapshot")', '@AppChainAccess(AppChainAccess.Level.SUBMIT)', 'return Response.status(429)',
        'Response.accepted(result)', 'Response.Status.SERVICE_UNAVAILABLE'] },
    { repo: 'yano', path: 'app/src/main/java/org/yanoproject/app/api/appchain/AppChainAccess.java',
      anchors: ['Unannotated GET/HEAD/OPTIONS methods default to {@link Level#READ}; every',
        'other unannotated method defaults to {@link Level#PRIVILEGED}.'] },
    { repo: 'yano', path: 'app/src/main/java/org/yanoproject/app/api/appchain/AppChainApiKeyFilter.java',
      anchors: ['reads and submissions remain public while privileged operations still', 'snapshot-admin-keys',
        'which can', 'move real funds', 'Plugin operations are always privileged'] },
    { repo: 'yano', path: 'app/src/main/java/org/yanoproject/app/api/appchain/PluginDomainResource.java',
      anchors: ['@Path("plugins/{bundleId}")', '"Domain API request body exceeds 65536 bytes"',
        'No domain API route matches the request', 'Map.of("code", code, "error", message)'] },
    { repo: 'yano', path: 'app/src/main/java/org/yanoproject/app/api/plugin/PluginOperationsResource.java',
      anchors: ['@Path("plugin-operations")', '@Path("bundles/{bundleId}")'] },
  ],
};
