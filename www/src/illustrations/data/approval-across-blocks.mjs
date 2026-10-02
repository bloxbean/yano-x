// An approval workflow across blocks: chapter 3's proposal and two votes, each a
// separate source message with its own cascade. Outcomes match the packaged
// rehearsal (DECLARATIVE_BINDINGS_CLI.md) and the Studio approval scenario reports.

const item = (status, votes) => ({
  caption: 'State after this block',
  columns: ['Item', 'Status', 'Approvals', 'Audit entries for `a`'],
  rows: [['`a`', status, `${votes} of 2`, votes === 2 && status === 'APPROVED' ? '1' : '0']],
  highlight: [0],
});

const propose = {
  title: 'Propose',
  text: 'Height 1. Member A submits `propose("a", 01, 2, 0)` to `reviews.command.v1`: item `a`, two approvals '
    + 'required, no deadline. The cascade has one step. It emits `approvals.item-proposed.v1`, and no binding '
    + 'subscribes to that event. Proposing is not a vote.',
  wires: [{ from: 'a', to: 'reviews', label: 'propose `a`' }],
  cards: {
    reviews: { title: 'Item `a` PENDING', detail: '0 of 2 approvals', tone: 'pending' },
    a: { title: 'Receipt ACCEPTED', detail: '1 step' },
  },
  state: item('PENDING', 0),
};

const firstVote = {
  title: 'First vote',
  text: 'Height 2. Member A approves `a`. The vote commits in its own cascade. Below the threshold the machine '
    + 'emits no `item-approved` event, so nothing is derived. Nothing waits in memory between blocks: the '
    + 'workflow’s progress is the item’s state.',
  wires: [{ from: 'a', to: 'reviews', label: 'approve `a`' }],
  cards: {
    reviews: { title: 'Item `a` PENDING', detail: '1 of 2 approvals', tone: 'pending' },
    a: { title: 'Receipt ACCEPTED', detail: '1 step' },
  },
  state: item('PENDING', 1),
};

const secondVote = {
  title: 'Second vote',
  text: 'Height 3. Member B approves. Its vote reaches the threshold, so the machine emits '
    + '`approvals.item-approved.v1`. The `record-approved` binding has no conditions; it maps the item id and '
    + 'payload hash into an `append` for `audit`, in the same cascade.',
  wires: [
    { from: 'b', to: 'reviews', label: 'approve `a`' },
    { from: 'reviews', to: 'audit', label: 'derived append' },
  ],
  cards: {
    reviews: { title: 'Threshold reached', detail: 'event `item-approved`', tone: 'ok' },
    audit: { title: 'Planned append', detail: 'entity `a` · `approved`', tone: 'pending' },
  },
};

const commitTogether = {
  title: 'Commit together',
  text: 'The vote and the append commit together. The receipt for Member B’s message is **ACCEPTED** with two '
    + 'steps. Only this last vote and its follow-up share one atomic cascade, not the whole conversation.',
  cards: {
    reviews: { title: 'Item `a` APPROVED', detail: '2 of 2 approvals', tone: 'final' },
    audit: { title: 'Entry appended', detail: 'entity `a`', tone: 'final' },
    b: { title: 'Receipt ACCEPTED', detail: '2 steps', tone: 'final' },
  },
  state: item('APPROVED', 2),
};

export default {
  id: 'approval-across-blocks',
  type: 'steps',
  title: 'An approval across blocks',
  intro: 'two consensus members approve item `a`, then a binding appends an audit entry. Each message is its own '
    + 'cascade in its own block. Try the “What if” scenarios to see what one cascade can and cannot roll back.',
  lanes: [
    { id: 'a', label: 'Member A', note: 'proposes, then votes', kind: 'member' },
    { id: 'b', label: 'Member B', note: 'votes', kind: 'member' },
    { id: 'reviews', label: 'reviews', note: 'approvals component', kind: 'core' },
    { id: 'audit', label: 'audit', note: 'doc-trail component', kind: 'core' },
  ],
  scenarios: [
    { id: 'approved', label: 'Approved', steps: [propose, firstVote, secondVote, commitTogether] },
    {
      id: 'append-rejected',
      label: 'The append is rejected',
      summary: 'Suppose the derived append fails, for example because a rule attached to `audit` refuses it.',
      steps: [propose, firstVote, secondVote, {
        title: 'Roll back the vote',
        text: 'The append is the failed step, ordinal 1, so the whole cascade rejects. Member B’s vote is rolled '
          + 'back with it. The proposal and Member A’s vote were committed in earlier blocks and stay. The '
          + 'receipt for Member B’s message is **REJECTED**.',
        wires: [{ from: 'reviews', to: 'b', label: 'receipt REJECTED · step 1', tone: 'fail' }],
        cards: {
          reviews: { title: 'Item `a` PENDING', detail: '1 of 2 approvals', tone: 'pending' },
          audit: { title: 'Nothing appended', tone: 'fail' },
          b: { title: 'Receipt REJECTED', detail: 'failed step 1', tone: 'fail' },
        },
        state: item('PENDING', 1),
      }, {
        title: 'Vote again',
        text: 'The rejected receipt stays under that message id; replaying the same id returns it. Correct the '
          + 'cause, then Member B submits a new vote, which is a new message with a new id.',
        cards: { b: { title: 'New vote', detail: 'new message id' } },
      }],
    },
    {
      id: 'same-voter',
      label: 'Member A votes twice',
      summary: 'Two votes from one member are one approval.',
      steps: [propose, firstVote, {
        title: 'A repeated vote',
        text: 'Height 3. Member A approves again. The machine counts distinct members, so the vote changes nothing: '
          + 'an empty plan, no event, nothing derived. The receipt is still **ACCEPTED**, because accepted means '
          + 'the cascade committed, not that state changed. Read the item, not just the receipt.',
        wires: [{ from: 'a', to: 'reviews', label: 'approve `a` again' }],
        cards: {
          reviews: { title: 'Item `a` PENDING', detail: 'still 1 of 2', tone: 'pending' },
          a: { title: 'Receipt ACCEPTED', detail: 'no change' },
        },
        state: item('PENDING', 1),
      }],
    },
  ],
  legend: [
    ['member', 'consensus member'],
    ['core', 'component'],
    ['final', 'committed'],
    ['fail', 'rolled back'],
  ],
  sources: [
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/ApprovalsTransitions.java',
      anchors: ['distinct-member voting', 'A duplicate/unknown/terminal command produces an empty plan',
        'Only the threshold-crossing vote produces'] },
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/StockTransitionKernels.java',
      anchors: ['approvals.item-proposed.v1', 'approvals.item-approved.v1'] },
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/bindings/EventBindingWorkflow.java',
      anchors: ['cascade retains its receipt and work accounting, but does not commit its component plans'] },
    { repo: 'yano-x', path: 'docs/appchain/DECLARATIVE_BINDINGS_CLI.md',
      anchors: ['"bodyHex": "8500616141010200"', 'The approval body `82016161` is', 'id: record-approved',
        'reference: {literal: approved}'] },
    { repo: 'yano-x', path: 'tooling/studio/src/test/fixtures/scenarios/approval-report-4.json',
      anchors: ['"bindingId" : "record-approved"', 'approvals.item-approved.v1', 'doc-trail.entry-appended.v1'] },
  ],
};
