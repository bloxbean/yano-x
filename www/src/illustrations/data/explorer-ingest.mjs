// How the Verifiable Explorer ingests one block: it verifies the node's
// evidence before any row exists, and the index adds convenience, never
// trust. Steps follow Follower.index and harvestBundle; levels follow
// VerificationLevel.

const read = {
  title: 'Read the block',
  text: '`yano-explorer` follows a node\'s finalized blocks over the public REST API, one height after its '
    + 'checkpoint at a time. It starts from the node\'s JSON view of the block, which it does not trust yet.',
  wires: [{ from: 'node', to: 'explorer', label: 'block JSON view' }],
  cards: {
    node: { title: 'Block 42', detail: '2 messages' },
    explorer: { title: 'Checkpoint 41', detail: 'next: 42' },
  },
};

const fetch = {
  title: 'Fetch the evidence',
  text: 'For a block with messages, the explorer asks the node for the evidence bundle of its first message: '
    + 'the canonical block bytes, the finality certificate, and the declared members and threshold.',
  wires: [{ from: 'node', to: 'explorer', label: 'evidence bundle' }],
  cards: { explorer: { title: 'Evidence', detail: 'canonical block + certificate' } },
};

const verify = {
  title: 'Verify finality',
  text: 'The finality certificate must verify before any row is written. With `--members`, it is checked '
    + 'against the members and threshold you pinned, and the block is `VERIFIED_PINNED`. Without it, it is '
    + 'checked against the members the bundle declares: `VERIFIED_DECLARED`, internal consistency at ingest.',
  checks: [{ label: 'Certificate verifies under pinned (or declared) members', ok: true }],
  cards: { explorer: { title: 'Finality ✓', detail: '`VERIFIED_PINNED`', tone: 'ok' } },
  focus: ['explorer'],
};

const crossCheck = {
  title: 'Cross-check the block',
  text: 'The node\'s JSON view must describe the certified block exactly. The block must link to the block '
    + 'already indexed below it, its messages root must recompute, and every message id must recompute from '
    + 'its signed body. Any mismatch is an error, and the block is not written.',
  checks: [
    { label: 'JSON view equals the certified block', ok: true },
    { label: 'Previous hash links to the indexed block 41', ok: true },
    { label: 'Messages root recomputes from the messages', ok: true },
    { label: 'Each message id recomputes', ok: true },
  ],
  cards: { explorer: { title: 'Block 42 ✓', detail: 'linked, roots recompute', tone: 'ok' } },
  focus: ['explorer'],
};

const record = {
  title: 'Capture the block record',
  text: 'The explorer fetches the state proof of the block\'s authenticated record (height, messages root, '
    + 'message count) and checks it against the certified state root, so a row can later be proven from the '
    + 'index alone.',
  wires: [{ from: 'node', to: 'explorer', label: 'block record proof' }],
  checks: [{ label: 'Block record proof verifies against the certified root', ok: true }],
  cards: { explorer: { title: 'Record proof ✓', detail: 'captured at ingest', tone: 'ok' } },
};

const write = {
  title: 'Decode and write',
  text: 'Stock modules decode each command into rows: a `doc-trail` append, a registry put, a transfer, an '
    + 'approval, a map mutation. The block, its messages, and their rows are written in one transaction, and '
    + 'every row inherits the block\'s level. Nothing in consensus or proof verification reads this index.',
  wires: [{ from: 'explorer', to: 'index', label: 'one transaction' }],
  cards: { index: { title: 'Block 42', detail: '`VERIFIED_PINNED` · 2 messages · rows' } },
};

const prove = {
  title: 'Prove a row',
  text: 'A reader opens a row in the console and chooses *Verify this row*, or runs `yano-explorer row`. The '
    + 'bundle carries the certified block, the inclusion path, the block record proof, and the message copy. '
    + '`yano-explorer verify --members` checks it offline: exit 5. The reader never has to trust the index.',
  wires: [{ from: 'index', to: 'reader', label: '`explorer-row-proof-v1` bundle' }],
  checks: [
    { label: 'Envelope copy, message id, and sender signature', ok: true },
    { label: 'Inclusion path under the messages root', ok: true },
    { label: 'Block record at the certified root', ok: true },
    { label: 'Finality under your pinned members', ok: true },
  ],
  cards: { reader: { title: 'Row verified', detail: '`CALLER_PINNED_ROOT` · exit 5', tone: 'final' } },
};

export default {
  id: 'explorer-ingest',
  type: 'steps',
  title: 'Verify on ingest',
  intro: 'how the Verifiable Explorer indexes one block of a stock app ledger. Pick a step, or try a '
    + '“What if” to see the levels a block can get.',
  lanes: [
    { id: 'node', label: 'Member node', note: 'public REST API', kind: 'member' },
    { id: 'explorer', label: 'yano-explorer', note: 'follower', kind: 'runtime' },
    { id: 'index', label: 'Index', note: 'derived, rebuildable', kind: 'runtime' },
    { id: 'reader', label: 'Reader', note: 'console or CLI', kind: 'client' },
  ],
  scenarios: [
    {
      id: 'verified',
      label: 'A block with messages',
      steps: [read, fetch, verify, crossCheck, record, write, prove],
    },
    {
      id: 'empty',
      label: 'A block without messages',
      summary: 'Members finalize a block that carries no messages.',
      steps: [read, {
        title: 'Header only',
        text: 'A block with no messages has no evidence bundle to fetch, so the explorer stores the node\'s JSON '
          + 'header as `HEADER_ONLY`. There is no row to prove.',
        wires: [{ from: 'explorer', to: 'index', label: 'header' }],
        cards: { index: { title: 'Block 43', detail: '`HEADER_ONLY`', tone: 'pending' } },
      }],
    },
    {
      id: 'pruned',
      label: 'The node pruned the evidence',
      summary: 'The node no longer retains evidence for an old height.',
      steps: [read, {
        title: 'JSON only',
        text: 'The node returns no evidence for the block\'s first message, so the rows come from its JSON view '
          + 'and are stored as `JSON_ONLY`. Such a row cannot be proven from the index, and the level never '
          + 'upgrades by itself: run `yano-explorer rebuild` against a node that retains the height.',
        wires: [{ from: 'explorer', to: 'index', label: 'JSON view' }],
        cards: { index: { title: 'Block 42', detail: '`JSON_ONLY` · not provable', tone: 'fail' } },
      }],
    },
    {
      id: 'bad-evidence',
      label: 'The evidence does not verify',
      summary: 'The pinned members file is wrong, or the node serves inconsistent evidence.',
      steps: [read, fetch, {
        title: 'Indexing stops',
        text: 'The certificate does not verify under the trust input, so no row is written and the chain\'s '
          + 'checkpoint does not move. With `--members`, check the pinned set and threshold; without it, the node\'s '
          + 'own evidence is inconsistent, which is worth reporting.',
        checks: [{ label: 'Certificate verifies under pinned (or declared) members', ok: false,
          code: 'evidence bundle … does not verify' }],
        cards: { explorer: { title: 'Stopped', detail: 'checkpoint stays at 41', tone: 'fail' } },
        focus: ['explorer'],
      }],
    },
  ],
  legend: [
    ['member', 'member node'],
    ['runtime', 'explorer service and its index'],
    ['client', 'reader'],
    ['final', 'verified offline'],
  ],
  sources: [
    { repo: 'yano-x', path: 'products/explorer/core/src/main/java/org/yanoproject/x/explorer/Follower.java',
      anchors: ['source.evidenceJson(firstId)', 'differs from its certified block', 'does not link to the indexed block',
        'messages root does not recompute from its messages', 'Captures the authenticated block record proof at ingest',
        'VerificationLevel.HEADER_ONLY : VerificationLevel.JSON_ONLY', 'does not verify: ',
        'trust != null ? VerificationLevel.VERIFIED_PINNED : VerificationLevel.VERIFIED_DECLARED'] },
    { repo: 'yano-x', path: 'products/explorer/core/src/main/java/org/yanoproject/x/explorer/VerificationLevel.java',
      anchors: ['VERIFIED_PINNED', 'VERIFIED_DECLARED', 'HEADER_ONLY', 'JSON_ONLY'] },
    { repo: 'yano-x', path: 'docs/appchain/EXPLORER.md',
      anchors: ['`explorer-row-proof-v1`', 'A `JSON_ONLY` block never upgrades on its own',
        'nothing in consensus, proof verification, or accounting reads it'] },
  ],
};
