// One ADR-036 consensus round across four members with a threshold of 3, plus
// a failed leader and a member that computes a different root. Facts follow
// Yano's AppChainEngine and consensus guide §4–§8; see `sources`.

// A follower's checks before it votes PREPARE, in the engine's order.
const CHECKS = [
  'The proposal fits the block byte budget and decodes as a block',
  'Its height is one above the local tip (a later block waits)',
  'The envelope is signed by the block’s proposer',
  'The proposer is the leader for this height and view',
  '`prevHash` equals the local tip hash',
  '`messagesRoot` recomputes from the messages',
  '`consensusContextDigest` equals this member’s own context',
  'A higher view carries a valid new-view certificate and any prepared value',
  'The L1 reference matches this member’s own L1 view',
  'Every message is valid, unexpired, and signed by a member at this height',
  'Effect results come from the designated signer; L1 observations re-derive',
  'Per-sender sequence numbers increase',
  'No conflicting prepare lock',
  'Re-executing the block gives a byte-identical `stateRoot`',
];

const propose = {
  title: 'Propose',
  text: 'Member 0 leads view 0 at height h. It selects pending messages, applies the block itself to compute '
    + 'the state root, and persists a prepare lock for (height, view, block hash). Then it sends the block '
    + 'and its own PREPARE vote to the other members.',
  wires: [
    { from: 'm0', to: 'm1', label: 'block h + PREPARE' },
    { from: 'm0', to: 'm3', label: 'block h + PREPARE (to every member)' },
  ],
  cards: {
    m0: { title: 'Block h · view 0', detail: 'root `7f3a…` · lock persisted', tone: 'leader' },
  },
};

const check = {
  title: 'Check',
  text: 'Each follower runs these checks, in this order, before it votes. The last one is the heart of the system: the '
    + 'follower applies the block itself and requires the same state root, byte for byte. State is never '
    + 'trusted, always recomputed.',
  checks: CHECKS.map((label) => ({ label, ok: true })),
  cards: {
    m1: { title: 'All checks ✓', detail: 'own root `7f3a…`', tone: 'ok' },
    m2: { title: 'All checks ✓', detail: 'own root `7f3a…`', tone: 'ok' },
    m3: { title: 'All checks ✓', detail: 'own root `7f3a…`', tone: 'ok' },
  },
};

const prepare = {
  title: 'Prepare',
  text: 'Each follower persists its own prepare lock, then signs and sends PREPARE. A member never prepares two '
    + 'different blocks in one view, and a lock survives a restart.',
  wires: [
    { from: 'm1', to: 'm0', label: 'PREPARE (to every member)' },
    { from: 'm3', to: 'm2', label: 'PREPARE (to every member)' },
  ],
  cards: {
    m1: { title: 'PREPARE sent', detail: 'lock persisted' },
    m2: { title: 'PREPARE sent', detail: 'lock persisted' },
    m3: { title: 'PREPARE sent', detail: 'lock persisted' },
  },
};

const preparedQc = {
  title: 'PreparedQC',
  text: 'Any member that holds 3 PREPAREs, the threshold, forms a **PreparedQC**: signed proof that a quorum '
    + 'accepted this exact block. It persists the QC with the block and sends it on. The QC must survive any '
    + 'later view change.',
  wires: [{ from: 'm2', to: 'm1', label: 'PreparedQC (to every member)' }],
  cards: {
    m2: { title: 'PreparedQC', detail: '3 of 4 PREPAREs', tone: 'pending' },
  },
};

const commit = {
  title: 'Commit',
  text: 'Holding a PreparedQC, each member signs COMMIT for the same block and sends it to the others.',
  wires: [
    { from: 'm0', to: 'm1', label: 'COMMIT (to every member)' },
    { from: 'm2', to: 'm3', label: 'COMMIT (to every member)' },
  ],
  cards: {
    m0: { title: 'COMMIT sent', detail: 'block h' },
    m1: { title: 'COMMIT sent', detail: 'block h' },
    m2: { title: 'COMMIT sent', detail: 'block h' },
    m3: { title: 'COMMIT sent', detail: 'block h' },
  },
};

const finalize = {
  title: 'Final',
  text: 'Any member that holds 3 COMMITs assembles the **finality certificate**, writes the block, its '
    + 'certificate, and the new state in one atomic batch, and sends the certificate on. Each receiver checks '
    + 'every signature itself. The block is **APP_FINAL**; there is no rollback below finality.',
  wires: [{ from: 'm1', to: 'm3', label: 'finality certificate (to every member)', tone: 'final' }],
  cards: {
    m0: { title: 'Final at h', detail: 'root `7f3a…`', tone: 'final' },
    m1: { title: 'Final at h', detail: '3 of 4 COMMITs', tone: 'final' },
    m2: { title: 'Final at h', detail: 'root `7f3a…`', tone: 'final' },
    m3: { title: 'Final at h', detail: 'root `7f3a…`', tone: 'final' },
  },
};

export default {
  id: 'consensus-round',
  type: 'steps',
  title: 'Inside a round',
  intro: 'four members, any 3 of which make a block final. Members are numbered in their sorted key order. '
    + 'Pick a step, press Play, or try a “What if”.',
  lanes: [
    { id: 'm0', label: 'Member 0', note: 'leader, view 0', kind: 'leader' },
    { id: 'm1', label: 'Member 1', note: 'member', kind: 'member' },
    { id: 'm2', label: 'Member 2', note: 'member', kind: 'member' },
    { id: 'm3', label: 'Member 3', note: 'member', kind: 'member' },
  ],
  scenarios: [
    {
      id: 'normal',
      label: 'Normal round',
      steps: [propose, check, prepare, preparedQc, commit, finalize],
    },
    {
      id: 'leader-offline',
      label: 'The leader is offline',
      summary: 'Signed timeouts move the height to view 1, and the next member in sorted order leads.',
      steps: [{
        title: 'No proposal',
        text: 'Member 0, the leader for view 0, is offline. Nothing arrives, and the round clock runs. By default '
          + 'the round timeout is 5 block intervals or 10 seconds, whichever is longer, and it doubles with each '
          + 'higher view, up to a limit.',
        cards: {
          m0: { title: 'Offline', detail: 'no proposal', tone: 'fail' },
          m1: { title: 'Waiting', detail: 'view 0' },
          m2: { title: 'Waiting', detail: 'view 0' },
          m3: { title: 'Waiting', detail: 'view 0' },
        },
      }, {
        title: 'Time out',
        text: 'Members 1, 2, and 3 each sign a TIMEOUT for view 1. A TIMEOUT carries the highest PreparedQC its '
          + 'signer holds for this height, if any. A timeout never deletes a prepare lock.',
        wires: [
          { from: 'm1', to: 'm2', label: 'TIMEOUT · view 1' },
          { from: 'm3', to: 'm2', label: 'TIMEOUT · view 1' },
        ],
        cards: {
          m1: { title: 'TIMEOUT', detail: 'view 1' },
          m2: { title: 'TIMEOUT', detail: 'view 1' },
          m3: { title: 'TIMEOUT', detail: 'view 1' },
        },
      }, {
        title: 'New view',
        text: 'Three signed TIMEOUTs, the threshold, form a **new-view certificate**. Every member that holds it '
          + 'moves to view 1. The leader of view 1 is the next member in sorted order: Member 1.',
        wires: [{ from: 'm2', to: 'm1', label: 'new-view certificate' }],
        cards: {
          m1: { title: 'View 1', detail: 'leader', tone: 'leader' },
          m2: { title: 'View 1', detail: 'certificate: 3 TIMEOUTs' },
          m3: { title: 'View 1', detail: 'certificate: 3 TIMEOUTs' },
        },
      }, {
        title: 'Propose in view 1',
        text: 'Member 1 proposes with the new-view certificate attached. If any TIMEOUT carried a PreparedQC, '
          + 'Member 1 must re-propose the block contents that the highest one certified; otherwise it builds a fresh '
          + 'block. Followers check this rule.',
        wires: [
          { from: 'm1', to: 'm2', label: 'block h · view 1 + PREPARE' },
          { from: 'm1', to: 'm3', label: 'block h · view 1 + PREPARE' },
        ],
        cards: {
          m1: { title: 'Block h · view 1', detail: 'root `7f3a…`', tone: 'leader' },
        },
      }, {
        title: 'Final without Member 0',
        text: 'Members 1, 2, and 3 are exactly the threshold. They prepare, form a PreparedQC, commit, and '
          + 'finalize block h. If one more member were offline, no block could become final. Each new height '
          + 'starts at view 0 again; with a fixed sequencer, Member 0 leads view 0, so every block waits for a '
          + 'timeout until it returns.',
        wires: [{ from: 'm2', to: 'm1', label: 'PREPARE, then COMMIT' }],
        cards: {
          m1: { title: 'Final at h', detail: 'view 1', tone: 'final' },
          m2: { title: 'Final at h', detail: 'view 1', tone: 'final' },
          m3: { title: 'Final at h', detail: 'view 1', tone: 'final' },
        },
      }, {
        title: 'Member 0 catches up',
        text: 'When Member 0 returns, it asks a peer for the blocks it missed and verifies each one: hash links, '
          + 'messages root, leader, every message signature, the full finality certificate, and a re-executed '
          + 'state root. Then it applies them.',
        wires: [{ from: 'm1', to: 'm0', label: 'certified blocks h…' }],
        cards: {
          m0: { title: 'Caught up', detail: 'verified, not trusted', tone: 'ok' },
        },
      }],
    },
    {
      id: 'different-root',
      label: 'A member computes a different root',
      summary: 'One member’s state machine is not deterministic. It refuses to vote and stalls alone.',
      steps: [propose, {
        title: 'Check fails on Member 3',
        text: 'Member 3 runs with a Turkish default locale, and the state machine lowercases text without '
          + '`Locale.ROOT`. Its re-executed root differs, so it does not vote. A member never signs a root it did '
          + 'not compute itself.',
        checks: CHECKS.map((label, i, list) => ({ label, ok: i < list.length - 1 })),
        cards: {
          m1: { title: 'All checks ✓', detail: 'own root `7f3a…`', tone: 'ok' },
          m2: { title: 'All checks ✓', detail: 'own root `7f3a…`', tone: 'ok' },
          m3: { title: 'Root differs ✗', detail: 'own root `91c4…` · no vote', tone: 'fail' },
        },
      }, {
        title: 'The quorum finalizes',
        text: 'Members 0, 1, and 2 are the threshold. They prepare, commit, and finalize block h with root '
          + '`7f3a…`.',
        wires: [{ from: 'm1', to: 'm2', label: 'PREPARE, then COMMIT' }],
        cards: {
          m0: { title: 'Final at h', detail: '3 of 4', tone: 'final' },
          m1: { title: 'Final at h', detail: '3 of 4', tone: 'final' },
          m2: { title: 'Final at h', detail: '3 of 4', tone: 'final' },
        },
      }, {
        title: 'Member 3 stalls',
        text: 'Member 3 tries to catch up, but re-executing certified block h still gives a different root, so it '
          + 'never applies it. After a minute without progress its status reports `stalled`. The others continue, '
          + 'but one more failure would now stop finality.',
        cards: {
          m3: { title: 'Stalled', detail: 'cannot apply h', tone: 'fail' },
        },
      }],
    },
  ],
  legend: [
    ['leader', 'leader for the view'],
    ['member', 'member'],
    ['final', 'final'],
    ['fail', 'offline, refused, or stalled'],
  ],
  sources: [
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['persist the `(height, view, blockHash)` prepare lock, broadcast the proposal and PREPARE',
        'height is exactly `tip+1`', '`block.proposer == envelope sender`', '`prevHash` equals the local tip hash',
        '`messagesRoot` recomputes from the message list', 'no conflicting prepare lock in this view',
        'requires byte-identical `stateRoot`', 'observation message re-derives from the node\'s own L1', 'it persists and broadcasts a `PreparedQC`; members then sign COMMIT',
        'At a COMMIT quorum, the holder assembles `FinalityCert`', 'there is no rollback path below finality',
        'A timeout never deletes a lock', 'The next leader must carry the highest valid PreparedQC',
        'Timeouts back off exponentially', 'every 5s it asks one connected peer for `tip+1 .. tip+50`'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainEngine.java',
      anchors: ['Proposal exceeds the v3 proposal byte budget', 'Proposal envelope sender does not match the block proposer',
        'is not from the deterministic leader', 'Proposal prev-hash mismatch', 'Proposal messages-root mismatch',
        'Proposal consensus context mismatch', 'Proposal carries invalid new-view evidence',
        'violates the prepared-value rule', 'Proposal contains an invalid message', 'from a non-designated signer', 'due to durable lock',
        'Proposal state-root mismatch', 'Persist before signing: at most one prepare for this height/view',
        'List<String> members = group.membersAt(height).stream().sorted().toList();',
        'return members.get((initial + viewOffset) % members.size());', 'currentView = 0;',
        'CertifiedConsensusCodec.NewViewCertificate certificate', 'long multiplier = 1L << exponent;'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainSubsystem.java',
      anchors: ['Math.max(config.blockIntervalMs() * 5, 10_000)', 'requestCatchUp(config.chainId(), tip + 1, tip + 50)',
        'status.put("stalled"', 'STALL_WINDOW_MS = 60_000'] },
  ],
};
