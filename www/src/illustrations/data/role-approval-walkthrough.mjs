// role-approvals: actor-signed statements relayed by members, evaluated in the
// order of ActorApprovalProcessor. The policy, actors and organizations are
// example data; outcome codes are RoleWorkflowResultCode values, which this
// profile does not store per command.

const COMMON = [
  'The statement names this chain',
  'Its deadline height has not passed',
  'The block’s signature-work budget has room',
  'The actor, its organization and key are current and active',
  'The signature verifies',
];

const VOTE = [
  'The proposal exists',
  'The proposal is PENDING',
  'Policy, payload and deadline match the proposal',
  'This actor has not decided on it yet',
  'The actor has the clause’s role',
  'No accepted decision in this clause from the same organization',
];

const PROPOSE = [
  'No proposal with this id exists',
  'The deadline is above the current height',
  'The policy exists and the revision is current',
  'The policy is ACTIVE',
  'The deadline is within the policy’s lifetime',
  'The actor holds a proposer role',
  'Pending-proposal capacity has room',
];

/** Checks in evaluation order; `fail` is the index of the failing check, or undefined for all passing. */
const run = (list, fail, code) => list.map((label, i) => ({
  label,
  ok: fail === undefined || i < fail ? true : i === fail ? false : null,
  ...(i === fail ? { code } : {}),
}));

const DECISIONS = ['Proposal', 'Status', 'Accepted decisions'];
const pending = (decisions) => ({
  caption: 'role-approvals state',
  columns: DECISIONS,
  rows: [['`order-1001`', 'PENDING', decisions]],
});

const sign = {
  title: 'Sign offline',
  text: 'Actor `buyer-a` hashes the exact order bytes and signs a PROPOSE statement in its own wallet or HSM. The '
    + 'statement binds the chain, proposal id, policy `order-release` revision 1, payload domain and hash, '
    + 'deadline height, actor revision and key.',
  viewText: {
    actor: 'You sign one statement for one payload hash. Your key never reaches a member node.',
    relay: 'Nothing yet: the actor signs on its own system.',
    verifier: 'Nothing to check yet.',
  },
  command: './yano.sh appchain role sign --action propose --chain role-approval-chain --proposal order-1001 '
    + '--policy order-release --policy-revision 1 --payload-domain com.example.order.v1 --payload-hash <hex> '
    + '--deadline-height <tip + 90> --actor buyer-a --actor-revision 1 --key buyer-key-v1 --seed-file <file>',
  cards: { actor: { title: 'Signed PROPOSE', detail: '`buyer-a`, payload hash' } },
};

const relay = {
  title: 'Relay',
  text: 'Any member can relay the bytes on `role-approvals.command.v1`. The member signs the envelope, but it gains '
    + 'no business authority: the actor’s signature inside decides. **202** only means queued.',
  viewText: {
    actor: 'Hand the command bytes to any member’s API.',
    relay: 'You check the command’s shape and relay it. You cannot change it without breaking the signature.',
    verifier: 'Still nothing final.',
  },
  wires: [{ from: 'actor', to: 'relay', label: 'command bytes' }],
  cards: { relay: { title: 'Admitted', detail: '202, envelope signed by the member' } },
};

const propose = {
  title: 'Propose',
  text: 'In the final block every member evaluates the statement. All checks pass, so proposal `order-1001` is '
    + 'created PENDING. The proposer is recorded but is not an approver.',
  viewText: {
    actor: 'Your proposal is open. Reviewers can now sign for it.',
    relay: 'Your relay counted for nothing except transport.',
    verifier: 'A PENDING proposal with no decisions.',
  },
  wires: [{ from: 'relay', to: 'sm', label: 'final block' }],
  checks: [...run(COMMON), ...run(PROPOSE)],
  state: { ...pending('none'), highlight: [0] },
  cards: { sm: { title: 'PENDING', detail: '0 of 2 organizations' } },
};

const reviewA = {
  title: 'First review',
  text: '`reviewer-a` from organization `audit-one` signs APPROVE for clause `reviewers`. The decision is accepted: '
    + 'one organization of the two the clause needs.',
  viewText: {
    actor: 'Your approval counts for `audit-one`.',
    relay: 'Another relay; any member will do.',
    verifier: 'One accepted decision, from `audit-one`.',
  },
  wires: [{ from: 'actor', to: 'sm', label: 'APPROVE, via a member' }],
  checks: [...run(COMMON), ...run(VOTE)],
  state: { ...pending('`reviewer-a` (`audit-one`)'), highlight: [0] },
  cards: { sm: { title: 'PENDING', detail: '1 of 2 organizations' } },
};

const reviewB = {
  title: 'Second review',
  text: '`reviewer-b` from `audit-two` approves. Two distinct organizations satisfy the clause, so the proposal '
    + 'becomes APPROVED, a terminal status.',
  viewText: {
    actor: 'The policy is satisfied.',
    relay: 'Relayed like the others.',
    verifier: 'APPROVED, with two decisions from two organizations.',
  },
  wires: [{ from: 'actor', to: 'sm', label: 'APPROVE, via a member' }],
  checks: [...run(COMMON), ...run(VOTE)],
  state: {
    caption: 'role-approvals state',
    columns: DECISIONS,
    rows: [['`order-1001`', 'APPROVED', '`reviewer-a` (`audit-one`), `reviewer-b` (`audit-two`)']],
    highlight: [0],
  },
  cards: { sm: { title: 'APPROVED', detail: 'clause satisfied', tone: 'final' } },
};

const verify = {
  title: 'Verify',
  text: 'A verifier reads the proposal through the domain API and checks its proof. Subject '
    + '`role-approval-outcome-v1` supports the claims `status` = APPROVED and `payload-digest` = the hash of the '
    + 'exact order bytes. The machine stores the hash, not the order.',
  viewText: {
    actor: 'Your application can now act on the approved hash.',
    relay: 'Members serve the proof; they do not vouch for it.',
    verifier: 'Check the proof against a root you trust, then compare the payload hash with the bytes you hold.',
  },
  wires: [{ from: 'sm', to: 'verifier', label: 'proposal + proof' }],
  cards: { verifier: { title: 'APPROVED', detail: 'payload digest matches', tone: 'ok' } },
};

const noOp = (title, text, from, label, checks, code, state, viewText) => ({
  title,
  text,
  viewText,
  wires: [{ from, to: 'sm', label }],
  checks,
  state,
  cards: { sm: { title: 'No-op', detail: `\`${code}\``, tone: 'fail' } },
});

export default {
  id: 'role-approval-walkthrough',
  type: 'steps',
  title: 'A role-approved decision',
  intro: 'policy `order-release`: a `buyer` proposes; two `reviewer` actors from distinct organizations must '
    + 'approve. The checks run in evaluation order. A failed check makes the command a final no-op; this profile '
    + 'keeps no per-command result, so read the proposal to see what happened.',
  lanes: [
    { id: 'actor', label: 'Actors', note: 'sign offline', kind: 'actor' },
    { id: 'relay', label: 'Relay member', note: 'any member', kind: 'member' },
    { id: 'sm', label: 'role-approvals', note: 'on every member', kind: 'core' },
    { id: 'verifier', label: 'Verifier', note: 'reads proofs', kind: 'client' },
  ],
  views: [
    { id: 'default', label: 'Everyone' },
    { id: 'actor', label: 'Actor', focus: ['actor'] },
    { id: 'relay', label: 'Relay member', focus: ['relay'] },
    { id: 'verifier', label: 'Verifier', focus: ['verifier', 'sm'] },
  ],
  scenarios: [
    { id: 'approved', label: 'Approved by two organizations', steps: [sign, relay, propose, reviewA, reviewB, verify] },
    {
      id: 'same-org',
      label: 'Same organization twice',
      summary: '`reviewer-a2` also works for `audit-one`.',
      steps: [propose, reviewA, noOp('Second review', '`reviewer-a2` approves. Its organization already has an '
        + 'accepted decision in this clause, so it does not count.', 'actor', 'APPROVE `reviewer-a2`',
      [...run(COMMON), ...run(VOTE, 5, 'DISTINCTNESS_DUPLICATE')], 'DISTINCTNESS_DUPLICATE',
      pending('`reviewer-a` (`audit-one`)'), { verifier: 'Still one decision. The second organization is missing.' })],
    },
    {
      id: 'conflict',
      label: 'A different payload',
      summary: '`reviewer-b` signs a hash that is not the proposal’s.',
      steps: [propose, reviewA, noOp('Second review', '`reviewer-b`’s statement carries another payload hash. It '
        + 'does not match the proposal, so it cannot count toward it.', 'actor', 'APPROVE, other hash',
      [...run(COMMON), ...run(VOTE, 2, 'CONFLICT')], 'CONFLICT', pending('`reviewer-a` (`audit-one`)'),
      { verifier: 'Still one decision.' })],
    },
    {
      id: 'revoked',
      label: 'A revoked actor',
      summary: '`reviewer-b`’s actor record was revoked through governance before it signed.',
      steps: [propose, reviewA, noOp('Second review', 'The current revision of `reviewer-b` is not ACTIVE, so the '
        + 'actor is not eligible. The signature is never checked.', 'actor', 'APPROVE `reviewer-b`',
      run(COMMON, 3, 'UNAUTHORIZED_ACTOR'), 'UNAUTHORIZED_ACTOR', pending('`reviewer-a` (`audit-one`)'),
      { verifier: 'Still one decision.' })],
    },
    {
      id: 'tampered',
      label: 'The relay edits the bytes',
      summary: 'A relay member changes the clause in the statement before relaying it.',
      steps: [propose, reviewA, noOp('Second review', 'The edited statement no longer matches `reviewer-b`’s '
        + 'signature. A relay cannot change a signed decision.', 'relay', 'edited APPROVE',
      run(COMMON, 4, 'INVALID_SIGNATURE'), 'INVALID_SIGNATURE', pending('`reviewer-a` (`audit-one`)'),
      { relay: 'Editing a statement only makes it worthless.' })],
    },
    {
      id: 'wrong-chain',
      label: 'Signed for another chain',
      summary: 'A statement signed for a staging chain is replayed here.',
      steps: [propose, noOp('Replay', 'The statement names a different chain id, so it is refused before any other '
        + 'check. A signature cannot be reused across chains.', 'actor', 'APPROVE for `staging-chain`',
      run(COMMON, 0, 'WRONG_GENESIS'), 'WRONG_GENESIS', pending('none'), { verifier: 'No decisions.' })],
    },
    {
      id: 'lifetime',
      label: 'A deadline too far away',
      summary: '`order-release` allows proposals to stay open for at most 100 blocks.',
      steps: [noOp('Propose', '`buyer-a` proposes with a deadline 500 blocks ahead. That exceeds the policy’s '
        + 'lifetime, so no proposal is created.', 'actor', 'PROPOSE, deadline +500',
      [...run(COMMON), ...run(PROPOSE, 4, 'LIMIT_EXCEEDED')], 'LIMIT_EXCEEDED',
      { caption: 'role-approvals state', columns: DECISIONS, rows: [] }, { verifier: 'No proposal exists.' }), {
        title: 'Propose again',
        text: 'A new statement with a deadline inside the lifetime creates the proposal.',
        wires: [{ from: 'actor', to: 'sm', label: 'PROPOSE, deadline +90' }],
        checks: [...run(COMMON), ...run(PROPOSE)],
        state: pending('none'),
        cards: { sm: { title: 'PENDING', detail: '0 of 2 organizations' } },
      }],
    },
    {
      id: 'expiry',
      label: 'Nobody approves in time',
      summary: 'Expiry runs at the start of every block, not when someone touches the proposal.',
      steps: [propose, reviewA, {
        title: 'The deadline passes',
        text: 'The next block above the deadline height starts with a maintenance pass. It marks every pending '
          + 'proposal whose deadline is below the block height EXPIRED, whatever messages the block carries.',
        viewText: { verifier: 'EXPIRED, with the one decision it collected.' },
        state: {
          caption: 'role-approvals state',
          columns: DECISIONS,
          rows: [['`order-1001`', 'EXPIRED', '`reviewer-a` (`audit-one`)']],
          highlight: [0],
        },
        cards: { sm: { title: 'EXPIRED', detail: 'set by the block, not a command', tone: 'fail' } },
      }, noOp('A late review', '`reviewer-b` approves after the deadline. The statement’s own deadline has passed, '
        + 'so it is refused at the second check.', 'actor', 'APPROVE `reviewer-b`',
      run(COMMON, 1, 'EXPIRED'), 'EXPIRED', {
        caption: 'role-approvals state', columns: DECISIONS,
        rows: [['`order-1001`', 'EXPIRED', '`reviewer-a` (`audit-one`)']],
      }, { verifier: 'Still EXPIRED.' })],
    },
    {
      id: 'cancel',
      label: 'Cancel',
      summary: 'Only the proposer can cancel its pending proposal.',
      steps: [propose, noOp('A reviewer cancels', '`reviewer-a` sends CANCEL. Only the proposer may cancel.',
        'actor', 'CANCEL `reviewer-a`', [...run(COMMON), 'The proposal exists', 'The proposal is PENDING',
        'Policy, payload and deadline match the proposal', 'The sender is the proposer']
        .map((check, i) => (typeof check === 'string'
          ? { label: check, ok: i < 8, ...(i === 8 ? { code: 'UNAUTHORIZED_ACTOR' } : {}) } : check)),
      'UNAUTHORIZED_ACTOR', pending('none'), { verifier: 'Still PENDING.' }), {
        title: 'The proposer cancels',
        text: '`buyer-a` sends CANCEL for its own proposal. It becomes CANCELLED, a terminal status.',
        wires: [{ from: 'actor', to: 'sm', label: 'CANCEL `buyer-a`' }],
        checks: [...run(COMMON), ...['The proposal exists', 'The proposal is PENDING',
          'Policy, payload and deadline match the proposal', 'The sender is the proposer']
          .map((label) => ({ label, ok: true }))],
        state: {
          caption: 'role-approvals state', columns: DECISIONS,
          rows: [['`order-1001`', 'CANCELLED', 'none']], highlight: [0],
        },
        cards: { sm: { title: 'CANCELLED', tone: 'fail' } },
      }],
    },
  ],
  legend: [
    ['actor', 'business actor'],
    ['member', 'relay member'],
    ['core', 'deterministic state machine'],
    ['client', 'verifier'],
    ['final', 'approved'],
    ['fail', 'no-op or terminal'],
  ],
  sources: [
    { repo: 'yano-x', path: 'capabilities/role-workflow/src/main/java/org/yanoproject/x/roles/internal/ActorApprovalProcessor.java',
      anchors: ['return RoleWorkflowResultCode.WRONG_GENESIS;', 'statement.deadlineHeight() < height ? RoleWorkflowResultCode.EXPIRED : null',
        'return RoleWorkflowResultCode.CRYPTO_WORK_EXCEEDED;', 'unchanged(RoleWorkflowResultCode.UNAUTHORIZED_ACTOR, facts.proposal())',
        'unchanged(RoleWorkflowResultCode.INVALID_SIGNATURE, facts.proposal())',
        'unchanged(RoleWorkflowResultCode.UNKNOWN_RECORD, null)', 'unchanged(RoleWorkflowResultCode.TERMINAL, proposal)',
        'unchanged(RoleWorkflowResultCode.CONFLICT, proposal)', 'RoleWorkflowResultCode.EXACT_REPLAY',
        'unchanged(RoleWorkflowResultCode.ROLE_MISMATCH, proposal)', 'unchanged(RoleWorkflowResultCode.DISTINCTNESS_DUPLICATE, proposal)',
        'unchanged(RoleWorkflowResultCode.WRONG_REVISION, null)', 'unchanged(RoleWorkflowResultCode.LIMIT_EXCEEDED, null)',
        'unchanged(RoleWorkflowResultCode.CAPACITY_EXCEEDED, null)',
        '!proposal.proposerActorId().equals(statement.actorId())', 'entry.deadlineHeight() < height',
        'Plans bounded expiry maintenance, including blocks with no approval commands.',
        'ApprovalProposalV1.ProposalStatus.CANCELLED'] },
    { repo: 'yano-x', path: 'capabilities/role-workflow/src/main/java/org/yanoproject/x/roles/RoleApprovalWorkflow.java',
      anchors: ['actorApprovals.prepareHeight(block.height(), approvalState);', 'TOPIC = SignedActorCommandV1.DEFAULT_TOPIC'] },
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/CompositeStateMachine.java',
      anchors: ['for (WorkflowBinding workflow : runtime.workflows()) {'] },
    { repo: 'yano-x', path: 'capabilities/role-workflow-contracts/src/main/java/org/yanoproject/x/roles/contracts/SignedActorCommandV1.java',
      anchors: ['DEFAULT_TOPIC = "role-approvals.command.v1"'] },
    { repo: 'yano-x', path: 'capabilities/role-workflow/src/main/java/org/yanoproject/x/roles/RoleProofSubjectProviders.java',
      anchors: ['"role-approval-outcome-v1"', 'claim("payload-digest"'] },
    { repo: 'yano-x', path: 'capabilities/role-workflow-contracts/src/main/java/org/yanoproject/x/roles/contracts/RoleWorkflowCli.java',
      anchors: ['"--action", "--chain", "--proposal", "--policy"'] },
  ],
};
