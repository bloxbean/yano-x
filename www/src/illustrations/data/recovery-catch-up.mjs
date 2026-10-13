// How a member that fell behind catches up, what a restart keeps, and how a
// snapshot restore is verified. Checks and messages follow the consensus
// guide §8 and AppChainEngine's catch-up path; snapshot checks follow
// SnapshotManifest.

const CATCH_UP_CHECKS = [
  'Height is the local tip + 1, and the block links to the local tip hash',
  'The proposer was a member, and the right leader for that height and view',
  'The messages root recomputes, and the consensus context matches',
  'The Cardano reference and observations agree with the member\'s own L1 view',
  'Every message is signed by a member at that height',
  'The finality certificate has a threshold of valid member signatures',
  'Re-executing the block gives a byte-identical state root',
];
const checksUpTo = (passed, failedAt) => CATCH_UP_CHECKS.map((label, i) => ({
  label, ok: i < passed ? true : i === failedAt ? false : null,
}));

const behind = {
  title: 'Fall behind',
  text: 'Member C was offline while A and B, a threshold of 2 of 3, finalized blocks 101 to 140. Gossip never '
    + 'delivers a missed proposal again, so C cannot recover from the live round.',
  cards: {
    peer: { title: 'Tip 140', detail: 'final', tone: 'final' },
    lag: { title: 'Tip 100', detail: 'offline, then back', tone: 'pending' },
  },
};

const request = {
  title: 'Request a batch',
  text: 'Every 5 seconds the lagging member asks one connected peer for the next range of up to 50 blocks, '
    + 'tip + 1 to tip + 50, over the dedicated block-range sync protocol.',
  wires: [{ from: 'lag', to: 'peer', label: 'blocks 101–150?' }, { from: 'peer', to: 'lag', label: 'blocks 101–140' }],
  cards: { lag: { title: 'Batch received', detail: '40 blocks' } },
};

const links = {
  title: 'Check each block',
  text: 'It checks each block in order, with the same rules as live consensus. It does not re-check message expiry: '
    + 'those messages were finalized before they expired. A Cardano reference ahead of its own L1 view pauses the '
    + 'batch rather than failing it.',
  checks: checksUpTo(5, -1),
  cards: { lag: { title: 'Block 101', detail: 'checks pass', tone: 'ok' } },
  focus: ['lag'],
};

const cert = {
  title: 'Verify the certificate',
  text: 'The finality certificate is verified in full: every signer must be a member at that height, and valid '
    + 'signatures must reach that height\'s threshold. The peer is not trusted.',
  checks: checksUpTo(6, -1),
  cards: { lag: { title: 'Certificate ✓', detail: '2 of 3 at height 101', tone: 'ok' } },
  focus: ['lag'],
};

const reexecute = {
  title: 'Re-execute',
  text: 'The member applies the block itself and requires a byte-identical state root, then commits the block, '
    + 'its certificate, and its state in one atomic batch.',
  checks: checksUpTo(7, -1),
  wires: [{ from: 'lag', to: 'store', label: 'commit 101' }],
  cards: { store: { title: 'Height 101', detail: 'own root = certified root', tone: 'final' } },
};

const caughtUp = {
  title: 'Rejoin consensus',
  text: 'It repeats batch by batch until it reaches the peers\' tip, then votes in live rounds again. If a peer stays '
    + 'ahead and the member makes no progress for 60 seconds, the node logs that it appears stalled, raises '
    + '`AppChainStalledEvent`, and status shows `stalled`.',
  wires: [{ from: 'lag', to: 'peer', label: 'PREPARE, COMMIT' }],
  cards: {
    lag: { title: 'Tip 140', detail: 'voting again', tone: 'final' },
    store: { title: 'Height 140', detail: 'same root as peers', tone: 'final' },
  },
};

const refuse = (title, text, detail) => ({
  title,
  text,
  cards: { lag: { title: 'Rejected', detail, tone: 'fail' }, store: { title: 'Height 100', detail: 'unchanged' } },
  focus: ['lag', 'store'],
});

export default {
  id: 'recovery-catch-up',
  type: 'steps',
  title: 'Catching up, restarting, restoring',
  intro: 'Member C rejoins a three-member ledger with a threshold of 2. Every block it receives is checked, never '
    + 'trusted. Try a “What if” for a lying peer, a restart, and a snapshot restore.',
  lanes: [
    { id: 'peer', label: 'Members A and B', note: 'at the tip', kind: 'member' },
    { id: 'lag', label: 'Member C', note: 'catching up', kind: 'member' },
    { id: 'store', label: 'C\'s ledger store', note: 'appchain-chainstate', kind: 'ledger' },
  ],
  scenarios: [
    {
      id: 'catch-up',
      label: 'Catch up',
      steps: [behind, request, links, cert, reexecute, caughtUp],
    },
    {
      id: 'forged-root',
      label: 'A peer sends a forged root',
      summary: 'A peer serves block 101 with a state root the block does not produce.',
      steps: [behind, request, {
        ...refuse('Root mismatch', 'Even if every earlier check passes, for example because faulty members signed the '
          + 'block, re-executing it gives a different root. The block is rejected and the batch stops; nothing is '
          + 'applied. A member never adopts a root it did not compute itself.', 'state-root mismatch'),
        checks: checksUpTo(6, 6),
      }],
    },
    {
      id: 'no-cert',
      label: 'A block without a valid certificate',
      summary: 'A peer serves a block whose certificate does not reach the threshold.',
      steps: [behind, request, {
        ...refuse('Certificate rejected', 'Too few valid member signatures at that height: the certificate check fails, '
          + 'and the block is rejected before it is executed.', 'cert verification failed'),
        checks: checksUpTo(5, 5),
      }],
    },
    {
      id: 'restart',
      label: 'Restart',
      summary: 'Member C is stopped and started again.',
      steps: [{
        title: 'What survives',
        text: 'Persisted with each block: blocks and certificates, tip metadata, the state trie and root, message and '
          + 'query indexes, per-sender floors, per-view prepare locks, prepared certificates with their proposals, and '
          + 'membership epochs. Lost by design: the pending message pool, gossip state, and pending anchor rounds.',
        state: {
          caption: 'On restart',
          columns: ['Kept', 'Lost'],
          rows: [['blocks, certificates, state, indexes', 'pending messages'], ['vote locks, prepared certificates', 'gossip state'],
            ['membership epochs, sender floors', 'pending anchor rounds']],
        },
        cards: { store: { title: 'On disk', detail: 'committed per block' }, lag: { title: 'Stopped' } },
      }, {
        title: 'Startup checks',
        text: 'Startup re-verifies rather than trusts: the committed root must equal the tip block\'s root, the tip '
          + 'block must re-hash to the stored hash, and its certificate must carry a threshold of valid member '
          + 'signatures. Then catch-up fetches what was missed.',
        checks: [
          { label: 'Committed root equals the tip block\'s root', ok: true },
          { label: 'Persisted membership epochs override static configuration', ok: true },
          { label: 'Tip block re-hashes, and its certificate meets the threshold', ok: true },
          { label: 'Own sender sequence floor restored', ok: true },
        ],
        wires: [{ from: 'store', to: 'lag', label: 'verified tip' }],
        cards: { lag: { title: 'Started', detail: 'catching up', tone: 'ok' } },
      }],
    },
    {
      id: 'snapshot',
      label: 'Restore a snapshot',
      summary: 'Member C starts from a member-signed snapshot instead of from height 1.',
      steps: [{
        title: 'Take a snapshot',
        text: 'Member A writes a RocksDB checkpoint with a manifest it signs: chain id, tip height and block hash, state '
          + 'root, state identity, a hash of the membership epochs, and a SHA-256 of every file.',
        command: './yano.sh appchain state snapshot --url <api-base> --chain <id> --path <fresh server directory>',
        wires: [{ from: 'peer', to: 'lag', label: 'copy checkpoint' }],
        cards: { peer: { title: 'Snapshot', detail: 'manifest signed by A' } },
      }, {
        title: 'Verify before opening',
        text: 'Before the database is opened, C checks that the manifest was signed by a member and that every file '
          + 'hash matches. After opening, the tip, block hash, state root, identity, and membership must match the '
          + 'manifest, and the tip certificate must reach the threshold. Then C catches up the rest.',
        checks: [
          { label: 'Manifest signed by a configured member', ok: true },
          { label: 'Every file hash matches the manifest', ok: true },
          { label: 'Tip, root, identity, and membership match', ok: true },
          { label: 'Tip certificate meets the threshold', ok: true },
        ],
        wires: [{ from: 'store', to: 'lag', label: 'verified checkpoint' }],
        cards: { lag: { title: 'Restored', detail: 'catching up the rest', tone: 'ok' } },
      }],
    },
    {
      id: 'edited-snapshot',
      label: 'An edited snapshot',
      summary: 'Someone changed a file in the checkpoint after it was signed.',
      steps: [{
        title: 'Take a snapshot',
        text: 'Member A writes and signs a snapshot as before, and the copy is changed in transit.',
        wires: [{ from: 'peer', to: 'lag', label: 'altered copy', tone: 'fail' }],
        cards: { peer: { title: 'Snapshot', detail: 'manifest signed by A' } },
      }, {
        title: 'Refuse to start',
        text: 'The file hashes no longer match the signed manifest, so the node refuses to start before it opens the '
          + 'database. A snapshot whose contents merely agree with themselves is also refused: the tip certificate '
          + 'must carry a threshold of member signatures.',
        checks: [
          { label: 'Manifest signed by a configured member', ok: true },
          { label: 'Every file hash matches the manifest', ok: false },
          { label: 'Tip, root, identity, and membership match', ok: null },
          { label: 'Tip certificate meets the threshold', ok: null },
        ],
        cards: { lag: { title: 'Not started', detail: 'corrupt or tampered snapshot', tone: 'fail' } },
        focus: ['lag'],
      }],
    },
  ],
  legend: [
    ['member', 'member node'],
    ['ledger', 'authoritative store'],
    ['final', 'verified and committed'],
    ['fail', 'rejected'],
  ],
  sources: [
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['every 5s it asks one connected peer for `tip+1 .. tip+50`',
        'expiry is NOT re-checked — the messages were finalized before expiry',
        '**the threshold cert is fully verified**, and the block is re-applied with',
        'a required byte-identical state root', 'the pending pool, active round aggregation',
        'per-view prepare locks, prepared certificates + canonical proposal envelopes',
        'persisted membership epochs override static config', 'a snapshot whose contents merely self-agree is rejected',
        'own sender-seq floor restored'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainEngine.java',
      anchors: ['Catch-up block state-root mismatch at height {} — rejecting',
        'Catch-up block cert verification FAILED at height {} — rejecting',
        'Catch-up consensus context mismatch at height {} — rejecting', 'pausing catch-up at height {}'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainSubsystem.java',
      anchors: ['STALL_WINDOW_MS = 60_000', 'appears stalled: local tip {}, best peer tip {}',
        'requestCatchUp(config.chainId(), tip + 1, tip + 50)', 'tip block hash does not recompute — corrupt or tampered ledger'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/SnapshotManifest.java',
      anchors: [' is not a trusted member key', 'Snapshot manifest signature verification FAILED',
        'Snapshot file hashes do not match the manifest', 'Restored state root does not match the manifest',
        'Restored membership epochs do not match the manifest'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainStateCli.java',
      anchors: ['./yano.sh appchain state snapshot --url <api-base> --chain <id>'] },
  ],
};
