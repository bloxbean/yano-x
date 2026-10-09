// A role-gated release in the Evidence Desk on the light showcase's
// document-review-chain: the issuer proposes, two auditors from distinct
// organizations approve with keys held in their browsers, and the approved
// release is applied once. Codes follow RoleWorkflowResultCode; the desk's
// predictions follow decisionObstacle in the UI.

const load = {
  title: 'Load an actor key',
  text: '`issuer-a` imports its 32-byte seed into the browser as a non-extractable WebCrypto key. The desk reads '
    + 'the actor record with its proof and refuses to sign unless the key is the actor\'s `ACTIVE` key, here '
    + '`issuer-a-k1`. The seed never leaves the tab.',
  cards: { issuer: { title: 'issuer-a-k1', detail: 'acme-manufacturing · issuer', tone: 'ok' } },
};

const propose = {
  title: 'Propose',
  text: 'The release inputs are fixed now: the document entity id, its digest (hashed in the browser), and a '
    + 'reference. They are encoded into the release command, hashed with blake2b-256, and that payload hash is '
    + 'signed into the statement under the `document-release` policy.',
  wires: [{ from: 'issuer', to: 'ledger', label: 'signed `PROPOSE`' }],
  state: {
    caption: 'Proposal record',
    columns: ['Status', 'independent-auditors'],
    rows: [['PENDING', '0 of 2']],
  },
  cards: { ledger: { title: 'ACCEPTED', detail: 'proposal PENDING', tone: 'ok' } },
};

const firstApproval = {
  title: 'First approval',
  text: '`auditor-a` loads its key, opens the proposal, and chooses the `independent-auditors` clause. Every bound '
    + 'field is copied from the proposal record. Before signing, the desk predicts the chain\'s answer; after '
    + 'finality it reads the actual result record with its proof.',
  wires: [{ from: 'auditorA', to: 'ledger', label: 'signed `APPROVE`' }],
  checks: [
    { label: 'Proposal is still pending', ok: true },
    { label: 'Deadline has not passed', ok: true },
    { label: 'The actor has not decided this proposal yet', ok: true },
    { label: 'The actor holds the clause\'s role', ok: true },
    { label: 'No other actor from the same organization decided this clause', ok: true },
  ],
  state: { caption: 'Proposal record', columns: ['Status', 'independent-auditors'], rows: [['PENDING', '1 of 2']] },
  cards: {
    auditorA: { title: 'Approved', detail: 'auditor-guild-a', tone: 'ok' },
    ledger: { title: 'ACCEPTED', detail: '1 of 2 approvals', tone: 'ok' },
  },
};

const secondApproval = {
  title: 'Second approval',
  text: '`auditor-b`, from a different organization, approves the same clause. Two distinct organizations have now '
    + 'approved, so the proposal becomes `APPROVED`.',
  wires: [{ from: 'auditorB', to: 'ledger', label: 'signed `APPROVE`' }],
  state: { caption: 'Proposal record', columns: ['Status', 'independent-auditors'], rows: [['APPROVED', '2 of 2']] },
  cards: {
    auditorB: { title: 'Approved', detail: 'auditor-guild-b', tone: 'ok' },
    ledger: { title: 'APPROVED', detail: '2 organizations', tone: 'final' },
  },
};

const release = {
  title: 'Release',
  text: 'Back as `issuer-a`, the desk submits the release only after checking that blake2b-256 of the command '
    + 'equals the proposal\'s payload hash. After finality it shows the consumption receipt and the document '
    + 'head at revision 1, both proven under the same root. The approval is used once.',
  wires: [{ from: 'issuer', to: 'ledger', label: 'release command' }],
  checks: [{ label: 'Command re-derives the proposal\'s payload hash', ok: true }],
  cards: { ledger: { title: 'Released', detail: 'receipt + document head r1', tone: 'final' } },
};

const exportBundle = {
  title: 'Export',
  text: 'The desk downloads a bundle of every record it showed, with its state proof. The JVM verifier checks the '
    + 'MPF paths and threshold finality offline; the browser itself only checks that each proof names the same '
    + 'key, height, and root as the finalized block (`BOUND`).',
  wires: [{ from: 'ledger', to: 'issuer', label: 'records + proofs' }],
  cards: { issuer: { title: 'Export bundle', detail: 'records + state proofs', tone: 'final' } },
};

const outcome = (title, text, actor, code, cardTitle) => ({
  title,
  text,
  wires: [{ from: actor, to: 'ledger', label: `result: \`${code}\``, tone: 'fail' }],
  cards: { ledger: { title: code, detail: 'finalized no-op', tone: 'fail' }, [actor]: { title: cardTitle, tone: 'fail' } },
});

export default {
  id: 'desk-approvals',
  type: 'steps',
  title: 'A role-gated release',
  intro: 'the light showcase\'s `document-review-chain`: `issuer-a` proposes, and two auditors from distinct '
    + 'organizations must approve. Every key stays in its own browser tab. Try a “What if” to see the chain refuse.',
  lanes: [
    { id: 'issuer', label: 'issuer-a', note: 'acme-manufacturing', kind: 'actor' },
    { id: 'auditorA', label: 'auditor-a', note: 'auditor-guild-a', kind: 'actor' },
    { id: 'auditorB', label: 'auditor-b', note: 'auditor-guild-b', kind: 'actor' },
    { id: 'ledger', label: 'document-review-chain', note: 'role workflow', kind: 'ledger' },
  ],
  scenarios: [
    {
      id: 'release',
      label: 'Approved release',
      steps: [load, propose, firstApproval, secondApproval, release, exportBundle],
    },
    {
      id: 'wrong-role',
      label: 'The issuer approves',
      summary: '`issuer-a` tries to approve its own proposal.',
      steps: [load, propose, {
        ...outcome('Approval refused', 'The `independent-auditors` clause needs the `auditor` role, which `issuer-a` '
          + 'does not hold. The desk predicts the refusal before signing; if the statement is submitted anyway, the '
          + 'chain records `ROLE_MISMATCH` and changes nothing.', 'issuer', 'ROLE_MISMATCH', 'Not an auditor'),
        checks: [
          { label: 'Proposal is still pending', ok: true },
          { label: 'Deadline has not passed', ok: true },
          { label: 'The actor has not decided this proposal yet', ok: true },
          { label: 'The actor holds the clause\'s role', ok: false, code: 'ROLE_MISMATCH' },
          { label: 'No other actor from the same organization decided this clause', ok: null },
        ],
      }],
    },
    {
      id: 'replay',
      label: 'The same approval twice',
      summary: '`auditor-a`\'s signed approval is submitted a second time.',
      steps: [load, propose, firstApproval, outcome('Replay recorded', 'The chain has already applied this exact '
        + 'statement. The second copy is finalized as `EXACT_REPLAY`: the outcome is recorded, and the count stays at '
        + '1 of 2.', 'auditorA', 'EXACT_REPLAY', 'Already applied')],
    },
    {
      id: 'altered-release',
      label: 'Different release inputs',
      summary: 'Someone tries to release a different document under the approved proposal.',
      steps: [load, propose, firstApproval, secondApproval, {
        title: 'Release refused',
        text: 'The payload hash commits to the entity id, the digest, and the reference. A release with any other '
          + 'input does not re-derive it, so the desk refuses to submit it. The approval cannot be moved to other '
          + 'bytes.',
        checks: [{ label: 'Command re-derives the proposal\'s payload hash', ok: false }],
        cards: { issuer: { title: 'Not submitted', detail: 'payload hash differs', tone: 'fail' } },
        focus: ['issuer'],
      }],
    },
  ],
  legend: [
    ['actor', 'actor with a browser-held key'],
    ['ledger', 'app ledger'],
    ['final', 'approved and released'],
    ['fail', 'refused'],
  ],
  sources: [
    { repo: 'yano-x', path: 'capabilities/role-workflow-contracts/src/main/java/org/yanoproject/x/roles/contracts/RoleWorkflowResultCode.java',
      anchors: ['ACCEPTED(0)', 'EXACT_REPLAY(8)', 'ROLE_MISMATCH(11)', 'DISTINCTNESS_DUPLICATE(12)'] },
    { repo: 'yano-x', path: 'capabilities/role-workflow-contracts/src/main/java/org/yanoproject/x/roles/contracts/ApprovalProposalV1.java',
      anchors: ['PENDING(0), APPROVED(1), REJECTED(2), CANCELLED(3), EXPIRED(4)'] },
    { repo: 'yano-x', path: 'products/evidence/ui/src/lib/roles.ts',
      anchors: ["if (proposal.status !== 'PENDING') return 'TERMINAL';", "return 'EXPIRED';", "return 'CONFLICT';",
        "return 'ROLE_MISMATCH';", "return 'DISTINCTNESS_DUPLICATE';"] },
    { repo: 'yano-x', path: 'docs/appchain/EVIDENCE_DESK.md',
      anchors: ['`issuer-a` | `acme-manufacturing` | `issuer`', '`auditor-a` | `auditor-guild-a` | `auditor`',
        '`auditor-b` | `auditor-guild-b` | `auditor`', 'choose the `independent-auditors` clause',
        'submitting the same auditor statement twice answers `EXACT_REPLAY`',
        'equals the proposal\'s payload hash before submitting', 'never leaves the tab'] },
  ],
};
