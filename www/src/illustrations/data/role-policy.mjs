// The evidence-release policy of the role-evidence demo (tutorial 5, Track B),
// replayed in the order the demo runner submits its statements, including its
// three negative controls. Outcomes follow ActorApprovalProcessor.

const VOTE = [
  'The proposal exists and is PENDING',
  'Policy, payload and deadline match the proposal',
  'This actor has not decided on it yet',
  'The actor has the clause’s role',
  'No accepted decision in this clause from the same organization',
];

const run = (fail, code) => VOTE.map((label, i) => ({
  label,
  ok: fail === undefined || i < fail ? true : i === fail ? false : null,
  ...(i === fail ? { code } : {}),
}));

const COLUMNS = ['Clause', 'Needs', 'Accepted decisions'];
const table = (auditors, regulator, status, highlight = []) => ({
  caption: `Proposal: ${status}`,
  columns: COLUMNS,
  rows: [
    ['`auditors`', '2 `auditor`, distinct organizations', auditors],
    ['`regulator`', '1 `regulator`', regulator],
  ],
  highlight,
});

export default {
  id: 'role-policy',
  type: 'steps',
  title: 'The evidence-release policy',
  intro: 'the statements the demo submits for one release, in order. A manufacturer proposes; two auditors from '
    + 'different organizations and one regulator must approve. Three statements are deliberate mistakes: each is '
    + 'final but changes nothing.',
  lanes: [
    { id: 'maker', label: 'Manufacturer', note: 'manufacturer-a', kind: 'actor' },
    { id: 'auditors', label: 'Auditors', note: 'two organizations', kind: 'actor' },
    { id: 'regulator', label: 'Regulator', note: 'regulator-a', kind: 'actor' },
    { id: 'sm', label: 'role-approvals', note: 'policy evidence-release', kind: 'core' },
  ],
  scenarios: [{
    id: 'demo',
    label: 'The demo’s statements',
    steps: [{
      title: 'Manufacturer proposes',
      text: '`manufacturer-a` proposes the release. Its role `manufacturer` is a proposer role, so the proposal opens '
        + 'PENDING. Proposing does not count as approving.',
      wires: [{ from: 'maker', to: 'sm', label: 'PROPOSE' }],
      state: table('none', 'none', 'PENDING'),
      cards: { sm: { title: 'PENDING', detail: 'no decisions' } },
    }, {
      title: 'Wrong role',
      text: '`manufacturer-a` also tries to approve in the `auditors` clause. It does not hold the `auditor` role.',
      wires: [{ from: 'maker', to: 'sm', label: 'APPROVE as auditor' }],
      checks: run(3, 'ROLE_MISMATCH'),
      state: table('none', 'none', 'PENDING'),
      cards: { maker: { title: 'No-op', detail: '`ROLE_MISMATCH`', tone: 'fail' } },
    }, {
      title: 'Wrong payload',
      text: '`auditor-a1` signs an approval over a hash with one bit flipped. It does not match the proposal’s hash.',
      wires: [{ from: 'auditors', to: 'sm', label: 'APPROVE, other hash' }],
      checks: run(1, 'CONFLICT'),
      state: table('none', 'none', 'PENDING'),
      cards: { maker: null, auditors: { title: 'No-op', detail: '`CONFLICT`', tone: 'fail' } },
    }, {
      title: 'First auditor',
      text: '`auditor-a1` from `audit-org-a` signs the correct hash. The decision is accepted.',
      wires: [{ from: 'auditors', to: 'sm', label: 'APPROVE `auditor-a1`' }],
      checks: run(),
      state: table('`auditor-a1` (`audit-org-a`)', 'none', 'PENDING', [0]),
      cards: { auditors: { title: 'Accepted', detail: '`audit-org-a`', tone: 'ok' } },
    }, {
      title: 'Same organization',
      text: '`auditor-a2` also works for `audit-org-a`. The clause counts organizations, not people, so this '
        + 'approval does not count.',
      wires: [{ from: 'auditors', to: 'sm', label: 'APPROVE `auditor-a2`' }],
      checks: run(4, 'DISTINCTNESS_DUPLICATE'),
      state: table('`auditor-a1` (`audit-org-a`)', 'none', 'PENDING'),
      cards: { auditors: { title: 'No-op', detail: '`DISTINCTNESS_DUPLICATE`', tone: 'fail' } },
    }, {
      title: 'Second auditor',
      text: '`auditor-b` from `audit-org-b` approves. The `auditors` clause is satisfied, but the `regulator` clause '
        + 'is not, so the proposal stays PENDING.',
      wires: [{ from: 'auditors', to: 'sm', label: 'APPROVE `auditor-b`' }],
      checks: run(),
      state: table('`auditor-a1` (`audit-org-a`), `auditor-b` (`audit-org-b`)', 'none', 'PENDING', [0]),
      cards: { auditors: { title: 'Accepted', detail: '`audit-org-b`', tone: 'ok' } },
    }, {
      title: 'Regulator approves',
      text: '`regulator-a` approves in the `regulator` clause. Every clause is satisfied, so the proposal becomes '
        + 'APPROVED with exactly three accepted decisions, the ones the demo then verifies.',
      wires: [{ from: 'regulator', to: 'sm', label: 'APPROVE `regulator-a`' }],
      checks: run(),
      state: table('`auditor-a1` (`audit-org-a`), `auditor-b` (`audit-org-b`)', '`regulator-a`', 'APPROVED', [1]),
      cards: { auditors: null, sm: { title: 'APPROVED', detail: '3 decisions', tone: 'final' } },
    }],
  }],
  legend: [
    ['actor', 'business actor'],
    ['core', 'deterministic state machine'],
    ['final', 'approved'],
    ['fail', 'no-op'],
  ],
  sources: [
    { repo: 'yano-x', path: 'products/evidence/demo-runner/src/main/java/org/yanoproject/x/examples/evidence/demo/RoleDemoWorkflow.java',
      anchors: ['List.of("manufacturer")', '"auditors", "auditor", 2, ApprovalPolicyV1.DistinctBy.ORGANIZATION',
        '"regulator", "regulator", 1, ApprovalPolicyV1.DistinctBy.ACTOR', 'ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE',
        'wrongHash[0] ^= 1;', 'identity("auditor-a1", "audit-org-a", "auditor"',
        'identity("auditor-a2", "audit-org-a", "auditor"', 'identity("auditor-b", "audit-org-b", "auditor"',
        'identity("regulator-a", "regulator-org", "regulator"',
        '.equals(List.of("auditor-a1", "auditor-b", "regulator-a"))', 'POLICY_ID = "evidence-release"'] },
    { repo: 'yano-x', path: 'capabilities/role-workflow/src/main/java/org/yanoproject/x/roles/internal/ActorApprovalProcessor.java',
      anchors: ['unchanged(RoleWorkflowResultCode.ROLE_MISMATCH, proposal)', 'unchanged(RoleWorkflowResultCode.CONFLICT, proposal)',
        'unchanged(RoleWorkflowResultCode.DISTINCTNESS_DUPLICATE, proposal)',
        'decision.action() == ActorStatementV1.Action.APPROVE'] },
    { repo: 'yano-x', path: 'products/evidence/harness/README.md',
      anchors: ['finalizes wrong-role, wrong-payload, and same-organization controls'] },
  ],
};
