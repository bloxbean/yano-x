// One governed composite profile change, from deployment to activation, with
// the ways a proposal is voided. Status names are the runtime's own
// (STAGING, SEALED, SCHEDULED); activation and voiding are events, not states.
// Rules follow CompositeProfileGovernanceRuntime and CompositeGovernanceConfig.

const COLUMNS = ['Proposal status', 'Approvals', 'Ready'];
const table = (status, approvals, ready, highlight = [0]) => ({
  caption: 'Proposal record (threshold 2 of 3)',
  columns: COLUMNS,
  rows: [[status, approvals, ready]],
  highlight,
});

const deploy = {
  title: 'Deploy the target bundle',
  text: 'Before any command, every member deploys the same reviewed composite bundle. Its catalog holds the active '
    + 'profile and the dormant target profile. Members restart one at a time. Nothing about the ledger changes yet.',
  cards: {
    a: { title: 'Catalog', detail: 'active + target' },
    b: { title: 'Catalog', detail: 'active + target' },
    c: { title: 'Catalog', detail: 'active + target' },
    state: { title: 'No proposal', detail: 'active profile only' },
  },
  focus: ['a', 'b', 'c'],
};

const begin = {
  title: 'Begin',
  text: 'Member A submits `BEGIN` with the proposal id, the active profile\'s digest, the membership digest, the '
    + 'target digest, the activation height H, and an expiry. A `BEGIN` that breaks any rule below is ignored.',
  command: './profile-governance.py --chain evidence-chain begin --encode-only …',
  wires: [{ from: 'a', to: 'state', label: '`BEGIN`' }],
  checks: [
    { label: 'No other proposal is open', ok: true },
    { label: 'Membership digest is current, and still current at H', ok: true },
    { label: 'Base digest is the active profile', ok: true },
    { label: 'H ≥ height + `min-activation-lag` (default 20)', ok: true },
    { label: 'Expiry ≤ height + `proposal-ttl-blocks` (default 600)', ok: true },
  ],
  state: table('STAGING', '—', '—'),
  cards: { state: { title: 'STAGING', detail: 'author: Member A' } },
};

const chunks = {
  title: 'Stage the chunks',
  text: 'Member A submits the exact canonical bytes of the target profile in chunks, waiting for each to finalize. '
    + 'Only the author can stage. Replaying an identical chunk does nothing; a conflicting one voids the proposal.',
  command: './profile-governance.py --chain evidence-chain chunk --index 0 …',
  wires: [{ from: 'a', to: 'state', label: '`CHUNK` 0 … n−1' }],
  state: table('STAGING', '—', '—'),
  cards: { state: { title: 'STAGING', detail: 'all chunks staged' } },
};

const seal = {
  title: 'Seal',
  text: 'Member A seals the proposal. The staged bytes must match the declared length and target digest, the '
    + 'transition from the active profile must pass the compatibility and quota rules, and H must still be far '
    + 'enough ahead. The proposal gets its proposal hash.',
  wires: [{ from: 'a', to: 'state', label: '`SEAL`' }],
  checks: [
    { label: 'Bytes match the declared length and target digest', ok: true },
    { label: 'Transition passes compatibility and quota rules', ok: true },
    { label: 'Activation lag still holds', ok: true },
  ],
  state: table('SEALED', '0 of 2', '0 of 3'),
  cards: { state: { title: 'SEALED', detail: 'proposal hash fixed' } },
};

const approve = {
  title: 'Approve',
  text: 'Approval authorizes the exact intent. Each approving member submits `APPROVE` with the proposal hash, '
    + 'through its own node and member key. A threshold of members must approve: here 2 of 3.',
  wires: [
    { from: 'a', to: 'state', label: '`APPROVE` hash' },
    { from: 'b', to: 'state', label: '`APPROVE` hash' },
  ],
  state: table('SEALED', '2 of 2', '0 of 3'),
  cards: {
    a: { title: 'Approved', tone: 'ok' },
    b: { title: 'Approved', tone: 'ok' },
    state: { title: 'SEALED', detail: 'threshold approved' },
  },
};

const ready = {
  title: 'Attest readiness',
  text: 'Readiness is separate from approval: **every** member in the bound membership must attest, with `READY`, '
    + 'that the target digest is in its own executable catalog. `READY --dry-run` fails on a node that lacks it.',
  wires: [
    { from: 'b', to: 'state', label: '`READY` digest' },
    { from: 'c', to: 'state', label: '`READY` digest' },
  ],
  state: table('SEALED', '2 of 2', '3 of 3'),
  cards: {
    a: { title: 'Ready', tone: 'ok' },
    b: { title: 'Ready', tone: 'ok' },
    c: { title: 'Ready', tone: 'ok' },
  },
};

const scheduled = {
  title: 'Scheduled',
  text: 'With threshold approvals and readiness from all members finalized before H, the proposal becomes '
    + '`SCHEDULED`. Status and the `proposal.state` metric show it. A threshold of `CANCEL` commands can still void it.',
  checks: [
    { label: 'Approvals ≥ threshold', ok: true },
    { label: 'Every member is ready', ok: true },
    { label: 'Reached before the activation height', ok: true },
  ],
  state: table('SCHEDULED', '2 of 2', '3 of 3'),
  cards: { state: { title: 'SCHEDULED', detail: 'activates at H', tone: 'pending' } },
  focus: ['state'],
};

const activate = {
  title: 'Activate at H',
  text: 'Every block before H runs the old profile. Block H runs the target profile for results, expiries, '
    + 'workflows, and messages. The runtime appends a new profile epoch, moves the marker, and deletes the '
    + 'proposal. There is no local override.',
  state: { caption: 'After block H', columns: ['Active profile', 'Epoch', 'Proposal'], rows: [['target', 'n + 1', 'none']] },
  cards: {
    a: { title: 'Target profile', detail: 'from block H', tone: 'final' },
    b: { title: 'Target profile', detail: 'from block H', tone: 'final' },
    c: { title: 'Target profile', detail: 'from block H', tone: 'final' },
    state: { title: 'Epoch n + 1', detail: 'marker = target', tone: 'final' },
  },
};

const voided = (text, detail) => ({
  title: 'Proposal voided',
  text,
  state: { caption: 'Proposal record', columns: COLUMNS, rows: [['none (deleted)', '—', '—']] },
  cards: { state: { title: 'Void', detail, tone: 'fail' } },
  focus: ['state'],
});

export default {
  id: 'profile-governance',
  type: 'steps',
  title: 'Changing a live profile',
  intro: 'three members with a threshold of 2 move a governed composite ledger to a new profile. The status names '
    + 'are the runtime\'s own. Try a “What if” for the ways a proposal is voided.',
  lanes: [
    { id: 'a', label: 'Member A', note: 'proposal author', kind: 'member' },
    { id: 'b', label: 'Member B', note: 'member', kind: 'member' },
    { id: 'c', label: 'Member C', note: 'member', kind: 'member' },
    { id: 'state', label: 'Governance state', note: 'in the state root', kind: 'ledger' },
  ],
  scenarios: [
    {
      id: 'activation',
      label: 'Activation',
      steps: [deploy, begin, chunks, seal, approve, ready, scheduled, activate],
    },
    {
      id: 'not-ready',
      label: 'A member is not ready',
      summary: 'Member C never deployed the target bundle.',
      steps: [deploy, begin, chunks, seal, approve, {
        ...ready,
        text: 'Members A and B attest readiness, but Member C\'s catalog lacks the target, so it cannot honestly send '
          + '`READY`. Without readiness from every member the proposal cannot be scheduled.',
        wires: [{ from: 'b', to: 'state', label: '`READY` digest' }],
        state: table('SEALED', '2 of 2', '2 of 3'),
        cards: {
          a: { title: 'Ready', tone: 'ok' },
          b: { title: 'Ready', tone: 'ok' },
          c: { title: 'Not ready', detail: 'target missing', tone: 'fail' },
        },
      }, voided('Once the expiry height passes, or if the conditions are only met at or after H, the proposal is '
        + 'deleted. Deploy the bundle on Member C and propose again.', 'expired')],
    },
    {
      id: 'cancel',
      label: 'A threshold cancels',
      summary: 'The members change their minds after sealing.',
      steps: [deploy, begin, chunks, seal, {
        title: 'Cancel',
        text: 'Members A and C each submit `CANCEL` with the proposal hash. Two cancellations meet the threshold.',
        wires: [
          { from: 'a', to: 'state', label: '`CANCEL` hash' },
          { from: 'c', to: 'state', label: '`CANCEL` hash' },
        ],
        state: table('SEALED', '0 of 2', '0 of 3'),
      }, voided('A threshold of cancellations voids the proposal before activation. The active profile is unchanged.',
        'threshold cancelled')],
    },
    {
      id: 'membership',
      label: 'Membership changes',
      summary: 'A membership change takes effect before H.',
      steps: [deploy, begin, chunks, seal, approve, ready, scheduled, voided('The proposal is bound to one membership '
        + 'epoch, now and at H. When the effective membership changes, the proposal is deleted, even when scheduled. '
        + 'Propose again against the new membership.', 'membership changed')],
    },
    {
      id: 'conflict',
      label: 'A conflicting chunk',
      summary: 'Two different byte strings are staged for the same chunk index.',
      steps: [deploy, begin, {
        ...chunks,
        text: 'Chunk 0 is staged, then a chunk 0 with different bytes arrives.',
        wires: [{ from: 'a', to: 'state', label: '`CHUNK` 0 (different bytes)', tone: 'fail' }],
      }, voided('Conflicting staging voids the proposal. So do staged bytes that exceed the declared size, and a seal '
        + 'whose bytes, digest, or transition rules do not check out.', 'conflicting chunk')],
    },
  ],
  legend: [
    ['member', 'member node'],
    ['ledger', 'governance state'],
    ['final', 'activated'],
    ['fail', 'void or not ready'],
  ],
  sources: [
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/CompositeProfileGovernanceRuntime.java',
      anchors: ['private enum Status { STAGING, SEALED, SCHEDULED }', 'proposal.status = Status.SEALED;',
        'proposal.status = Status.SCHEDULED;', 'proposal.readiness.containsAll(epoch.members())',
        'proposal.cancellations.size() >= epoch.threshold()', 'if (height >= proposal.begin.activationHeight())',
        'existing != null && !Arrays.equals(existing, chunk.bytes())',
        '!Arrays.equals(begin.baseProfileDigest(), activeDigest(state))',
        'state.delete(CompositeStateKeys.activeProposalKey());', 'scheduled composite activation height was skipped'] },
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/CompositeGovernanceConfig.java',
      anchors: ['DEFAULT_MINIMUM_ACTIVATION_LAG = 20', 'DEFAULT_PROPOSAL_TTL_BLOCKS = 600'] },
    { repo: 'yano-x', path: 'docs/APP_CHAIN_PROFILE_GOVERNANCE.md',
      anchors: ['threshold member approvals authorize the exact intent',
        'every member in the bound membership epoch must attest that the exact target',
        'Every block before `H` uses the old', 'There is no local break-glass override.'] },
  ],
};
