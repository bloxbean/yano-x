// One approvals item from proposal to a confirmed effect, with the decision
// order of ApprovalsTransitions.evaluate and the separate effect record that
// the on-approved effect keeps. Item ids and payloads are example data.

const voteChecks = (results) => [
  'The item exists',
  'The item is PENDING',
  'No deadline (0), or the block time is not after it',
  'The sender has not approved this item yet',
].map((label, i) => ({ label, ok: results[i] ?? null }));

const proposed = ['`i/release-7`', 'PENDING · 0 of 2 · approvers: none'];
const staged = ['`ae/p/release-7`', 'staged payload'];

const propose = {
  title: 'Propose',
  text: 'Member A proposes `release-7` with 2 required approvals and no deadline. The item starts PENDING with no '
    + 'approvers: proposing is not approving. With the on-approved effect active, the payload is also staged.',
  wires: [{ from: 'a', to: 'sm', label: 'PROPOSE `release-7`, 2, deadline 0' }],
  checks: [{ label: 'No item with this id exists yet', ok: true }],
  state: { caption: 'approvals state', columns: ['Key', 'Value'], rows: [proposed, staged], highlight: [0, 1] },
  cards: { sm: { title: 'PENDING', detail: '0 of 2' } },
};

const approveB = {
  title: 'Approve',
  text: 'Member B approves. B is a new approver, so the count becomes 1 of 2.',
  wires: [{ from: 'b', to: 'sm', label: 'APPROVE `release-7`' }],
  checks: voteChecks([true, true, true, true]),
  state: {
    caption: 'approvals state',
    columns: ['Key', 'Value'],
    rows: [['`i/release-7`', 'PENDING · 1 of 2 · approvers: B'], staged],
    highlight: [0],
  },
  cards: { sm: { title: 'PENDING', detail: '1 of 2' }, b: { title: 'Counted', tone: 'ok' } },
};

const approveAgain = {
  title: 'Approve again',
  text: 'Member B approves a second time. A member counts once, so this final message changes nothing.',
  wires: [{ from: 'b', to: 'sm', label: 'APPROVE `release-7`' }],
  checks: voteChecks([true, true, true, false]),
  state: {
    caption: 'approvals state (unchanged)',
    columns: ['Key', 'Value'],
    rows: [['`i/release-7`', 'PENDING · 1 of 2 · approvers: B'], staged],
  },
  cards: { b: { title: 'No-op', detail: 'already counted', tone: 'fail' } },
};

const threshold = {
  title: 'Reach the threshold',
  text: 'Member C approves: 2 of 2. The item becomes APPROVED, a terminal status. The staged payload becomes one '
    + 'effect, its record starts PENDING, and the staged copy is deleted.',
  wires: [
    { from: 'c', to: 'sm', label: 'APPROVE `release-7`' },
    { from: 'sm', to: 'exec', label: 'effect, after finality', tone: 'pending' },
  ],
  checks: voteChecks([true, true, true, true]),
  state: {
    caption: 'approvals state',
    columns: ['Key', 'Value'],
    rows: [['`i/release-7`', 'APPROVED · 2 of 2 · approvers: B, C'], ['`ae/s/release-7`', 'effect PENDING']],
    highlight: [0, 1],
  },
  cards: {
    b: null,
    c: { title: 'Counted', tone: 'ok' },
    sm: { title: 'APPROVED', detail: '2 of 2', tone: 'final' },
    exec: { title: 'Claims the effect', detail: 'type from configuration', tone: 'pending' },
  },
};

const lateReject = {
  title: 'Reject too late',
  text: 'Member A now rejects. The item is no longer PENDING, so the rejection is a no-op. A terminal item never '
    + 'changes again.',
  wires: [{ from: 'a', to: 'sm', label: 'REJECT `release-7`' }],
  checks: voteChecks([true, false]),
  state: {
    caption: 'approvals state (unchanged)',
    columns: ['Key', 'Value'],
    rows: [['`i/release-7`', 'APPROVED · 2 of 2 · approvers: B, C'], ['`ae/s/release-7`', 'effect PENDING']],
  },
  cards: { c: null, a: { title: 'No-op', detail: 'item is terminal', tone: 'fail' } },
};

const confirmed = {
  title: 'Record the result',
  text: 'The executor reports success, and the result returns as an ordered message. The effect record becomes '
    + 'CONFIRMED. The decision stays APPROVED whatever the effect’s outcome; a failure would read FAILED.',
  wires: [{ from: 'exec', to: 'sm', label: 'result: success' }],
  state: {
    caption: 'approvals state',
    columns: ['Key', 'Value'],
    rows: [['`i/release-7`', 'APPROVED · 2 of 2 · approvers: B, C'], ['`ae/s/release-7`', 'effect CONFIRMED']],
    highlight: [1],
  },
  cards: {
    a: null,
    exec: { title: 'Reported', detail: 'success', tone: 'ok' },
    sm: { title: 'APPROVED', detail: 'effect CONFIRMED', tone: 'final' },
  },
};

export default {
  id: 'approvals-lifecycle',
  type: 'steps',
  title: 'Life of an approval',
  intro: 'three members and one item that needs 2 approvals, on a chain with the on-approved effect enabled. The '
    + 'checks run in the order the machine evaluates them; a failed check means a final no-op.',
  lanes: [
    { id: 'a', label: 'Member A', note: 'proposer', kind: 'member' },
    { id: 'b', label: 'Member B', kind: 'member' },
    { id: 'c', label: 'Member C', kind: 'member' },
    { id: 'sm', label: 'approvals', note: 'on every member', kind: 'core' },
    { id: 'exec', label: 'Executor', note: 'outside consensus', kind: 'runtime' },
  ],
  scenarios: [
    { id: 'approved', label: 'Approved, then confirmed', steps: [propose, approveB, approveAgain, threshold, lateReject, confirmed] },
    {
      id: 'expired',
      label: 'The deadline passes',
      summary: 'A deadline is Unix milliseconds compared with the block time. Time alone writes nothing.',
      steps: [{
        ...propose,
        text: 'Member A proposes `release-7` with 2 required approvals and a deadline. The item starts PENDING.',
        wires: [{ from: 'a', to: 'sm', label: 'PROPOSE `release-7`, 2, deadline T' }],
        state: { caption: 'approvals state', columns: ['Key', 'Value'], rows: [proposed, staged], highlight: [0] },
      }, {
        title: 'Approve after the deadline',
        text: 'Member B approves in a block whose time is after T. The deadline check runs before the approval '
          + 'counts, so the item becomes EXPIRED instead, and the staged payload is deleted.',
        wires: [{ from: 'b', to: 'sm', label: 'APPROVE `release-7`' }],
        checks: voteChecks([true, true, false]),
        state: {
          caption: 'approvals state',
          columns: ['Key', 'Value'],
          rows: [['`i/release-7`', 'EXPIRED · 0 of 2']],
          highlight: [0],
        },
        cards: { sm: { title: 'EXPIRED', detail: 'set by the first late command', tone: 'fail' } },
      }],
    },
    {
      id: 'rejected',
      label: 'A member rejects',
      summary: 'One rejection from any member ends a pending item.',
      steps: [propose, approveB, {
        title: 'Reject',
        text: 'Member C rejects. The item is PENDING and inside its deadline, so it becomes REJECTED, a terminal '
          + 'status, and the staged payload is deleted. No effect is emitted.',
        wires: [{ from: 'c', to: 'sm', label: 'REJECT `release-7`' }],
        checks: voteChecks([true, true, true]).slice(0, 3),
        state: {
          caption: 'approvals state',
          columns: ['Key', 'Value'],
          rows: [['`i/release-7`', 'REJECTED · rejecter: C']],
          highlight: [0],
        },
        cards: { sm: { title: 'REJECTED', tone: 'fail' }, c: { title: 'Rejecter' } },
      }],
    },
    {
      id: 'repropose',
      label: 'Propose the same id again',
      summary: 'The first proposal for an id wins. A later one cannot replace its payload.',
      steps: [propose, {
        title: 'Propose again',
        text: 'Member B proposes `release-7` with a different payload. An item with this id exists, so the proposal '
          + 'is a no-op and the original payload hash stays.',
        wires: [{ from: 'b', to: 'sm', label: 'PROPOSE `release-7`' }],
        checks: [{ label: 'No item with this id exists yet', ok: false }],
        state: { caption: 'approvals state (unchanged)', columns: ['Key', 'Value'], rows: [proposed, staged] },
        cards: { b: { title: 'No-op', detail: 'id already used', tone: 'fail' } },
      }],
    },
  ],
  legend: [
    ['member', 'member node'],
    ['core', 'deterministic state machine'],
    ['runtime', 'executor, outside consensus'],
    ['final', 'terminal and approved'],
    ['fail', 'no-op or terminal failure'],
  ],
  sources: [
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/ApprovalsTransitions.java',
      anchors: ['if (facts.item().isPresent()) return unchanged();', 'if (facts.item().isEmpty()) return unchanged();',
        'if (item.status() != STATUS_PENDING) return unchanged();',
        'item.deadline() > 0 && context.timestamp() > item.deadline()',
        'item.approvers().stream().anyMatch(sender -> Arrays.equals(sender, context.sender()))',
        'boolean approved = approvers.size() >= item.required();', 'Change.REJECTED, true', 'List.of(), new byte[0]'] },
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/ApprovalsStateMachine.java',
      anchors: ['writer.put(effectStateKey(itemId), ApprovalEffectState.pending(effectId).encode());',
        'writer.delete(stagedEffectPayloadKey(itemId));', 'EFFECT_STATUS_CONFIRMED = 1', 'EFFECT_STATUS_FAILED = 2',
        'The approval decision remains in {@link Item}; this record never changes', '.result(ResultPolicy.CHAIN)'] },
    { repo: 'yano-x', path: 'state-machines/stdlib-contracts/src/main/java/org/yanoproject/x/stdlib/contracts/ApprovalsContract.java',
      anchors: ['key("i/", itemId)', 'key("ae/p/", itemId)', 'key("ae/s/", itemId)'] },
    { repo: 'yano-x', path: 'config/application-appchain.yml',
      anchors: ['chain-id: "effects-chain"', 'type: demo.webhook', 'on-approved-effect: 1'] },
  ],
};
