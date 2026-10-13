// What happens when members compute different results for the same block:
// one follower, the leader, or everyone. Three members, threshold 2. Facts
// follow Yano's AppChainEngine and consensus guide; the locale behaviour is
// standard Java `String.toLowerCase()`.

const LOWER = '`"TITLE".toLowerCase()`';

const code = (turkish) => ({
  title: 'The forbidden line',
  text: 'A state machine stores `name.toLowerCase()` without `Locale.ROOT`. Lowercasing uses the JVM’s default '
    + `locale, and in Turkish an uppercase I becomes a dotless ı. Member ${turkish} runs with a Turkish default locale.`,
  state: {
    caption: 'Each member applies the same message',
    columns: ['Member', 'Default locale', LOWER],
    rows: ['A', 'B', 'C'].map((member) => [member, member === turkish ? 'Turkish' : 'English',
      member === turkish ? '`tıtle`' : '`title`']),
    highlight: [['A', 'B', 'C'].indexOf(turkish)],
  },
  cards: {
    [turkish.toLowerCase()]: { title: 'Turkish locale', detail: 'different bytes', tone: 'fail' },
  },
});

const propose = (rootA) => ({
  title: 'Propose',
  text: 'Member A leads view 0. It applies the block, computes the state root, and sends the block with its '
    + 'PREPARE.',
  wires: [
    { from: 'a', to: 'b', label: 'block h + PREPARE' },
    { from: 'a', to: 'c', label: 'block h + PREPARE' },
  ],
  cards: { a: { title: 'Block h · view 0', detail: `root \`${rootA}\``, tone: 'leader' } },
});

export default {
  id: 'divergence-sim',
  type: 'steps',
  title: 'When members disagree',
  intro: 'three members, any 2 of which make a block final. One line of code gives different bytes on different '
    + 'members. Who runs it differently decides what happens.',
  lanes: [
    { id: 'a', label: 'Member A', note: 'leader, view 0', kind: 'leader' },
    { id: 'b', label: 'Member B', note: 'member', kind: 'member' },
    { id: 'c', label: 'Member C', note: 'member', kind: 'member' },
  ],
  scenarios: [
    {
      id: 'one-follower',
      label: 'One follower differs',
      steps: [code('C'), propose('7f3a…'), {
        title: 'Re-execute',
        text: 'Member B re-executes and gets the same root, so it votes. Member C gets a different root and does '
          + 'not vote: a member never signs a root it did not compute itself.',
        wires: [{ from: 'b', to: 'a', label: 'PREPARE' }],
        cards: {
          b: { title: 'Root matches ✓', detail: 'own root `7f3a…`', tone: 'ok' },
          c: { title: 'Root differs ✗', detail: 'own root `91c4…` · no vote', tone: 'fail' },
        },
      }, {
        title: 'Final without C',
        text: 'Members A and B are the threshold. They commit, and block h is final with root `7f3a…`.',
        wires: [{ from: 'b', to: 'a', label: 'COMMIT' }],
        cards: {
          a: { title: 'Final at h', detail: '2 of 3', tone: 'final' },
          b: { title: 'Final at h', detail: '2 of 3', tone: 'final' },
        },
      }, {
        title: 'C stalls alone',
        text: 'Member C fetches block h through catch-up, but re-executing it still gives a different root, so C '
          + 'never applies it. After a minute without progress its status reports `stalled`. The ledger continues '
          + 'with A and B, and has no margin left: one more failure stops finality.',
        cards: { c: { title: 'Stalled', detail: 'tip stays at h − 1', tone: 'fail' } },
      }, {
        title: 'Fix and rejoin',
        text: 'Run Member C with the same locale and restart it. Catch-up re-executes every block C missed, now '
          + 'with matching roots, and C rejoins. The lasting fix is code that pins `Locale.ROOT`.',
        wires: [{ from: 'a', to: 'c', label: 'certified blocks h…' }],
        cards: { c: { title: 'Caught up', detail: 'root `7f3a…`', tone: 'ok' } },
      }],
    },
    {
      id: 'leader',
      label: 'The leader differs',
      summary: 'The followers refuse the leader’s block, and a view change routes around it.',
      steps: [code('A'), propose('91c4…'), {
        title: 'Followers refuse',
        text: 'Members B and C both compute `7f3a…`, so neither votes for the leader’s block. No PreparedQC forms.',
        cards: {
          b: { title: 'Root differs ✗', detail: 'own root `7f3a…` · no vote', tone: 'fail' },
          c: { title: 'Root differs ✗', detail: 'own root `7f3a…` · no vote', tone: 'fail' },
        },
      }, {
        title: 'View change',
        text: 'The round times out. Members sign TIMEOUTs for view 1, and two of them form a new-view certificate. '
          + 'Member B, next in sorted order, leads view 1.',
        wires: [{ from: 'c', to: 'b', label: 'TIMEOUT · view 1' }],
        cards: { b: { title: 'View 1', detail: 'leader', tone: 'leader' } },
      }, {
        title: 'Final without A',
        text: 'Member B proposes a block with root `7f3a…`, and Member C votes for it. Two of three make it final. '
          + 'Member A re-executes, gets `91c4…`, does not vote, and stalls.',
        wires: [{ from: 'b', to: 'c', label: 'block h · view 1 + PREPARE' }],
        cards: {
          a: { title: 'Stalled', detail: 'cannot apply h', tone: 'fail' },
          b: { title: 'Final at h', detail: 'view 1', tone: 'final' },
          c: { title: 'Final at h', detail: 'view 1', tone: 'final' },
        },
      }, {
        title: 'Slower until fixed',
        text: 'Every new height starts at view 0. With a fixed sequencer, Member A leads view 0 at every height, '
          + 'so each block now waits one round timeout, 10 seconds by default, until A is fixed.',
        cards: { a: { title: 'Stalled', detail: 'view 0 leader at every height', tone: 'fail' } },
      }],
    },
    {
      id: 'nobody-agrees',
      label: 'No two members agree',
      summary: 'Each member reads an environment variable with a different value.',
      steps: [{
        title: 'The forbidden line',
        text: 'A state machine reads `System.getenv("REGION")` and stores it. Every member has a different value.',
        state: {
          caption: 'Each member applies the same message',
          columns: ['Member', '`REGION`', 'State root'],
          rows: [['A', '`eu`', '`7f3a…`'], ['B', '`us`', '`91c4…`'], ['C', '`ap`', '`2be0…`']],
        },
      }, propose('7f3a…'), {
        title: 'Nobody votes',
        text: 'Members B and C each compute a different root, so neither votes for the leader’s block.',
        cards: {
          b: { title: 'Root differs ✗', detail: 'own root `91c4…`', tone: 'fail' },
          c: { title: 'Root differs ✗', detail: 'own root `2be0…`', tone: 'fail' },
        },
      }, {
        title: 'Finality stops',
        text: 'Views change and timeouts grow, but every new leader’s block fails the same way. No block reaches 2 '
          + 'votes, so the ledger stops instead of accepting a disputed state. Each member keeps its last final '
          + 'block; pending messages wait in the pools until they expire.',
        cards: {
          a: { title: 'Halted', detail: 'tip stays at h − 1', tone: 'fail' },
          b: { title: 'Halted', detail: 'tip stays at h − 1', tone: 'fail' },
          c: { title: 'Halted', detail: 'tip stays at h − 1', tone: 'fail' },
        },
      }],
    },
  ],
  legend: [
    ['leader', 'leader for the view'],
    ['member', 'member'],
    ['final', 'final'],
    ['fail', 'refused, stalled, or halted'],
  ],
  sources: [
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['A nondeterministic state machine stalls its chain right here.',
        'no locale-dependent serialization', 'no environment reads', 'AppChainStalledEvent',
        'the next member in canonical member order becomes leader'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainEngine.java',
      anchors: ['Proposal state-root mismatch', 'currentView = 0;',
        'if ("fixed".equals(sequencerMode.id()) && !config.proposerKeyHex().isBlank()) {'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainSubsystem.java',
      anchors: ['Math.max(config.blockIntervalMs() * 5, 10_000)', 'status.put("stalled"', 'STALL_WINDOW_MS = 60_000'] },
  ],
};
