// One message from an HTTP call to a final, provable result, across three
// members with a threshold of 2. Facts follow Yano's consensus guide (§2, §4,
// §8) and submission contract; see `sources`.

const submit = {
  title: 'Submit',
  text: 'Your application sends a topic and a body to Member A, over REST or the Java client. It signs '
    + 'nothing: Member A wraps the body in an envelope signed with its own member key. When a decision needs '
    + 'a person or organization to approve it, their signatures travel inside the body.',
  viewText: { app: 'You send a topic and a body to one member. You do not sign the envelope.' },
  wires: [{ from: 'app', to: 'a', label: 'POST `{topic, body}`' }],
  cards: {
    app: { title: 'POST …/messages', detail: 'topic + body' },
    a: { title: 'Envelope', detail: "signed with A's member key" },
  },
};

const admit = {
  title: 'Admit',
  text: 'Member A asks the state machine whether the command is acceptable at the next height. If it is, A '
    + 'keeps the message in its pending pool and answers **202** with a message id. A **400** means the '
    + 'application refused it; **429** means the pool is full. 202 only means "queued on Member A".',
  viewText: { app: 'You receive 202 and a message id. Nothing is final yet, and nothing has succeeded yet.' },
  wires: [{ from: 'a', to: 'app', label: '202 + messageId' }],
  cards: {
    a: { title: 'Admission ✓', detail: 'pending pool: 1', tone: 'ok' },
    app: { title: '202 Accepted', detail: 'not final yet', tone: 'pending' },
  },
};

const gossip = {
  title: 'Gossip',
  text: 'Member A gossips the envelope to the other members. Each checks the signature and that A is a member, '
    + 'drops duplicates by message id, and holds the message in memory. Pending pools are not saved: a message '
    + 'no other member has received is lost if A restarts. An envelope expires after 10 minutes by default.',
  viewText: { app: 'Still pending. The message is waiting in the members’ pools.' },
  wires: [
    { from: 'a', to: 'b', label: 'gossip envelope' },
    { from: 'a', to: 'c', label: 'gossip envelope' },
  ],
  cards: {
    b: { title: 'Pending pool', detail: '1 message' },
    c: { title: 'Pending pool', detail: '1 message' },
  },
};

const propose = {
  title: 'Propose',
  text: 'Member B leads this height. On its next tick (every 2 seconds by default) it selects pending messages, '
    + 'checks them again against the current state, applies the block itself to compute the new state root, '
    + 'records a prepare lock, and sends the block with its PREPARE vote. No pending messages, no block.',
  viewText: { app: 'Still pending. A block containing your message is being proposed.' },
  wires: [
    { from: 'b', to: 'a', label: 'block h + PREPARE' },
    { from: 'b', to: 'c', label: 'block h + PREPARE' },
  ],
  cards: {
    b: { title: 'Block h · view 0', detail: 'own root `7f3a…`', tone: 'leader' },
  },
};

const reexecute = {
  title: 'Re-execute',
  text: 'Members A and C do not trust the root Member B sent. Each checks the leader, the link to the previous '
    + 'block, every message signature, and the block size, then applies the block itself and compares the '
    + 'state root byte for byte. A member votes PREPARE only for a root it computed itself.',
  viewText: { app: 'Still pending. Every member is re-running the block.' },
  cards: {
    a: { title: 'Re-executed ✓', detail: 'own root `7f3a…`', tone: 'ok' },
    c: { title: 'Re-executed ✓', detail: 'own root `7f3a…`', tone: 'ok' },
  },
};

const prepare = {
  title: 'Prepare',
  text: 'PREPARE votes reach the threshold, 2 of 3 here. A member holding them forms a **PreparedQC**: '
    + 'signed evidence that a quorum accepted this exact block. Members then sign COMMIT.',
  viewText: { app: 'Still pending.' },
  wires: [
    { from: 'a', to: 'b', label: 'PREPARE' },
    { from: 'c', to: 'b', label: 'PREPARE' },
  ],
  cards: {
    b: { title: 'PreparedQC', detail: '2 of 3 PREPAREs', tone: 'leader' },
  },
};

const commit = {
  title: 'Commit',
  text: 'When COMMIT votes reach the threshold, a member assembles the **finality certificate** and shares it. '
    + 'Every member writes block h and its new state in one atomic batch. The block is final, and there is '
    + 'no rollback below finality.',
  viewText: { app: 'Your message is final at height h.' },
  wires: [
    { from: 'b', to: 'a', label: 'finality certificate' },
    { from: 'b', to: 'c', label: 'finality certificate' },
  ],
  cards: {
    a: { title: 'Final at h', detail: 'root `7f3a…`', tone: 'final' },
    b: { title: 'Final at h', detail: '2 of 3 COMMITs', tone: 'final' },
    c: { title: 'Final at h', detail: 'root `7f3a…`', tone: 'final' },
  },
};

const prove = {
  title: 'Read and prove',
  text: 'Your application finds the message at its height and index, reads the application’s result, and can '
    + 'request a proof that a record is, or is not, in the state under the certified root. Always check the '
    + 'result: a final command that breaks a business rule is recorded as a no-op.',
  viewText: { app: 'Read the result and fetch a proof. Do not treat 202 as success.' },
  wires: [{ from: 'a', to: 'app', label: 'result + proof' }],
  cards: {
    app: { title: 'Final at h', detail: 'result + proof', tone: 'final' },
  },
};

const anchor = {
  title: 'Anchor',
  text: 'Optionally, an anchor later publishes a certified state root to Cardano, giving auditors a public '
    + 'reference that does not depend on any member. Finality never waits for Cardano.',
  viewText: { app: 'Optional: a later anchor covers your message on Cardano.' },
  wires: [{ from: 'b', to: 'l1', label: 'anchor root (optional)', tone: 'cardano' }],
  cards: {
    l1: { title: 'Anchored', detail: 'height · block hash · root', tone: 'cardano' },
  },
};

export default {
  id: 'message-lifecycle',
  type: 'steps',
  title: 'Life of a message',
  intro: 'three members, any 2 of which make a block final. Pick a step, press Play, or try a “What if”. '
    + 'Switch to your application’s view to see only what an HTTP caller sees.',
  lanes: [
    { id: 'app', label: 'Your application', note: 'REST or Java client', kind: 'client' },
    { id: 'a', label: 'Member A', note: 'receives the message', kind: 'member' },
    { id: 'b', label: 'Member B', note: 'leader, view 0', kind: 'leader' },
    { id: 'c', label: 'Member C', note: 'member', kind: 'member' },
    { id: 'l1', label: 'Cardano', note: 'optional', kind: 'cardano' },
  ],
  views: [
    { id: 'default', label: 'Network' },
    { id: 'app', label: 'Your application', focus: ['app'] },
  ],
  scenarios: [
    {
      id: 'normal',
      label: 'Normal round',
      steps: [submit, admit, gossip, propose, reexecute, prepare, commit, prove, anchor],
    },
    {
      id: 'rule-fails',
      label: 'A rule fails after 202',
      summary: 'Admission said yes, but by the time the block ran, the state had changed.',
      steps: [submit, admit, gossip, propose, reexecute, prepare, commit, {
        title: 'Final, as a no-op',
        text: 'The block is final, but the command broke a business rule against the state it actually ran on: '
          + 'another message changed that state first. Every member records the same no-op. The message id is '
          + 'now used: correct the cause and submit a new message to retry.',
        viewText: { app: 'The message is final, but your command had no effect. Submit a new message to retry.' },
        wires: [{ from: 'a', to: 'app', label: 'result: rejected', tone: 'fail' }],
        cards: { app: { title: 'Final · no-op', detail: 'message id used', tone: 'fail' } },
      }],
    },
    {
      id: 'leader-down',
      label: 'The leader is offline',
      summary: 'A quorum moves the round to the next member. No configuration change is needed.',
      steps: [submit, admit, gossip, {
        title: 'No proposal',
        text: 'Member B, the leader for view 0, is down. No block arrives, and A and C keep the message in '
          + 'their pools.',
        viewText: { app: 'Still pending.' },
        cards: {
          b: { title: 'Offline', detail: 'no proposal', tone: 'fail' },
          c: { title: 'Pending pool', detail: '1 message' },
        },
      }, {
        title: 'Time out',
        text: 'The round times out, after 10 seconds by default. A and C each sign a TIMEOUT for view 1. Two '
          + 'of three is the threshold, so together they form a new-view certificate.',
        viewText: { app: 'Still pending; the round is being retried.' },
        wires: [
          { from: 'a', to: 'c', label: 'TIMEOUT · view 1' },
          { from: 'c', to: 'a', label: 'TIMEOUT · view 1' },
        ],
        cards: {
          a: { title: 'TIMEOUT', detail: 'view 1' },
          c: { title: 'TIMEOUT', detail: 'view 1' },
        },
      }, {
        title: 'Next leader',
        text: 'The next member in the fixed member order, here Member C, leads view 1. It must re-propose any '
          + 'block that already has a PreparedQC; otherwise it builds a new block from its pool and sends '
          + 'its PREPARE.',
        viewText: { app: 'Still pending.' },
        wires: [{ from: 'c', to: 'a', label: 'block h · view 1 + PREPARE' }],
        cards: {
          c: { title: 'Block h · view 1', detail: 'own root `7f3a…`', tone: 'leader' },
        },
      }, {
        title: 'Final without B',
        text: 'Member A re-executes the block and votes. Two PREPAREs and two COMMITs make block h final. When B '
          + 'returns, it catches up by verifying each block and its certificate before applying it.',
        viewText: { app: 'Your message is final at height h.' },
        wires: [{ from: 'a', to: 'c', label: 'PREPARE, then COMMIT' }],
        cards: {
          a: { title: 'Final at h', detail: 'view 1', tone: 'final' },
          c: { title: 'Final at h', detail: 'view 1', tone: 'final' },
        },
      }],
    },
    {
      id: 'member-disagrees',
      label: 'One member disagrees',
      summary: 'A member that computes a different root refuses to vote.',
      steps: [submit, admit, gossip, propose, {
        title: 'A different root',
        text: 'Suppose Member C runs code that is not deterministic. For example, it reads the system clock. '
          + 'C computes a different state root, so it does not vote: a member never accepts a root it did not '
          + 'compute itself.',
        viewText: { app: 'Still pending.' },
        cards: {
          a: { title: 'Re-executed ✓', detail: 'own root `7f3a…`', tone: 'ok' },
          c: { title: 'Root differs ✗', detail: 'own root `91c4…` · no vote', tone: 'fail' },
        },
      }, {
        title: 'The quorum finalizes',
        text: 'Members A and B are two of three, so block h is still final. Member C cannot apply the certified '
          + 'block, because its result differs, so C stalls until its operator fixes the cause. If a quorum '
          + 'disagreed, no block would become final: the ledger stops rather than accept a disputed state.',
        viewText: { app: 'Your message is final at height h.' },
        wires: [{ from: 'a', to: 'b', label: 'PREPARE, then COMMIT' }],
        cards: {
          a: { title: 'Final at h', detail: '2 of 3', tone: 'final' },
          b: { title: 'Final at h', detail: '2 of 3', tone: 'final' },
          c: { title: 'Stalled', detail: 'cannot apply h', tone: 'fail' },
        },
      }],
    },
  ],
  legend: [
    ['client', 'your application'],
    ['member', 'member node'],
    ['leader', 'leader for the round'],
    ['final', 'final'],
    ['fail', 'refused or stalled'],
  ],
  sources: [
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['PreparedQC', 'FinalityCert', 'NewViewCertificate', 'No pending messages → no block',
        'the pending pool', 'deterministic no-op', 'AppChainStalledEvent'] },
    { repo: 'yano', path: 'docs/appchain/submission.md',
      anchors: ['**202** with `messageId`', '**429**', 'Accepted is not finalized', 'message ID is already consumed'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/AppChainConfig.java',
      anchors: ['DEFAULT_DEFAULT_TTL_SECONDS = 600', 'DEFAULT_BLOCK_INTERVAL_MS = 2000'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainSubsystem.java',
      anchors: ['Math.max(config.blockIntervalMs() * 5, 10_000)', '.sender(signer.publicKey())'] },
  ],
};
