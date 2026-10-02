// Which stock state machine fits? A decision aid over the seven documented
// machines. Maturity comes from the capability catalog, recipes from the recipe
// catalog, state keys and proof subjects from the machines' contracts.

const ROLE_RESULT_LINKS = [{ label: 'role-approvals reference', href: '/state-machines/role-approvals/' }];

export default {
  id: 'sm-chooser',
  type: 'chooser',
  title: 'Which state machine?',
  tag: 'Decision aid',
  intro: 'two or three questions about what the ledger must keep and who decides. Each answer names a machine, '
    + 'its maturity and recipe, where it keeps state, and what its proofs can and cannot show.',
  start: 'keep',
  nodes: {
    keep: {
      question: 'What must the ledger keep for you?',
      options: [
        { label: 'Only the order of events; my applications read the payloads', next: 'ordered-log' },
        { label: 'Current records that writers can change', next: 'records' },
        { label: 'Quantities that move between accounts', next: 'balances' },
        { label: 'An ordered history of document hashes per product or case', next: 'doc-trail' },
        { label: 'A decision that several approvers make', next: 'approvers' },
      ],
    },
    records: {
      question: 'Who may change a record?',
      options: [
        { label: 'The member that wrote it first; that is enough', next: 'kv-registry' },
        {
          label: 'It depends on the collection: open, owner, member, a role or an approval; with revisions, '
            + 'tombstones and atomic batches',
          next: 'authenticated-map',
        },
      ],
    },
    approvers: {
      question: 'Who approves?',
      help: 'A member is a node that votes on blocks. A business actor is a person, service or device with its own key.',
      options: [
        { label: 'The member nodes themselves', next: 'approvals' },
        { label: 'Business actors with roles and organizations, separate from the members', next: 'role-approvals' },
      ],
    },
    'ordered-log': {
      result: {
        title: 'ordered-log',
        text: 'The built-in machine. It records where each message was finalized and never rejects one for '
          + 'business reasons.',
        facts: [
          ['Maturity', 'stable'],
          ['Recipe', '`audit-log`'],
          ['State key', '`sha256("~yano/finalized-message/v1/" ‖ message id)`'],
          ['Proof subject', '`finalized-message-v1`, claim `recorded`'],
          ['Proves', 'the height, index, topic and sender of a finalized message'],
          ['Cannot prove', 'anything about what the payload means'],
        ],
        links: [{ label: 'ordered-log', href: '/state-machines/ordered-log/' }],
      },
    },
    'kv-registry': {
      result: {
        title: 'kv-registry',
        text: 'The first member to write a key owns it until the key is deleted.',
        facts: [
          ['Maturity', 'stable'],
          ['Recipe', '`owned-registry`'],
          ['State key', 'the key bytes; value `[owner, value]`'],
          ['Proof subject', '`registry-entry-v1`, claims `owner-equals`, `value-digest-equals`'],
          ['Proves', 'the current owner and value, or that the key is absent'],
          ['Cannot prove', 'earlier values, or any transfer of ownership'],
        ],
        links: [{ label: 'kv-registry reference', href: '/state-machines/kv-registry/' }],
      },
    },
    'authenticated-map': {
      result: {
        title: 'authenticated-map',
        text: 'Named collections, each with its own authorization, bounds, encoding and optional validator, '
          + 'all fixed at genesis.',
        facts: [
          ['Maturity', 'preview'],
          ['Recipe', '`authenticated-map`'],
          ['State key', 'collection and key, inside the composite state'],
          ['Proof subject', '`authenticated-map-entry-v1`, claims `status`, `revision-exact`, `value-digest`'],
          ['Proves', 'ACTIVE, REVOKED or ABSENT, the revision, and the value digest'],
          ['Cannot prove', 'rules that relate two entries; it has no secondary indexes'],
        ],
        links: [{ label: 'authenticated-map reference', href: '/state-machines/authenticated-map/' }],
      },
    },
    approvals: {
      result: {
        title: 'approvals',
        text: 'A proposal becomes APPROVED when enough distinct members approve it. One member rejection ends it.',
        facts: [
          ['Maturity', 'stable'],
          ['Recipe', '`approval-workflow`'],
          ['State key', '`i/<itemId>`'],
          ['Proof subject', '`basic-approval-outcome-v1`, claims `status`, `quorum-reached`, `payload-digest`'],
          ['Proves', 'the status, approval count and payload hash of one item'],
          ['Cannot prove', 'which person stands behind a member key'],
        ],
        links: [{ label: 'approvals reference', href: '/state-machines/approvals/' }],
      },
    },
    'role-approvals': {
      result: {
        title: 'role-approvals',
        text: 'Actors sign statements over a payload hash; a governed policy decides. Any member can relay them.',
        facts: [
          ['Maturity', 'preview'],
          ['Recipe', '`role-approval`'],
          ['State key', 'proposal record in the `role-approvals` component'],
          ['Proof subject', '`role-approval-outcome-v1`, claims `status`, `payload-digest`'],
          ['Proves', 'the policy outcome for one payload hash'],
          ['Cannot prove', 'what the payload says; the machine stores and runs no payload'],
        ],
        links: ROLE_RESULT_LINKS,
      },
    },
    balances: {
      result: {
        title: 'balances',
        text: 'A configured minter mints. A member spends only from its own account. Balances never go negative.',
        facts: [
          ['Maturity', 'stable'],
          ['Recipe', 'none; configure `state-machine: balances`'],
          ['State key', '`b/<account>`'],
          ['Proof subject', '`account-balance-v1`, claims `exact`, `minimum`, `maximum`'],
          ['Proves', 'the balance of one account'],
          ['Cannot prove', 'total supply, or anything about Cardano assets'],
        ],
        links: [{ label: 'balances reference', href: '/state-machines/balances/' }],
      },
    },
    'doc-trail': {
      result: {
        title: 'doc-trail',
        text: 'Each append chains an entry hash and its author into a per-entity head. Documents stay off the ledger.',
        facts: [
          ['Maturity', 'stable'],
          ['Recipe', '`document-trail`'],
          ['State key', '`e/<entityId>`'],
          ['Proof subject', '`document-head-v1`, claims `revision-exact`, `revision-minimum`, `digest-equals`'],
          ['Proves', 'the entry count and the chained head hash'],
          ['Cannot prove', 'that a referenced document is still available'],
        ],
        links: [{ label: 'doc-trail reference', href: '/state-machines/doc-trail/' }],
      },
    },
  },
  sources: [
    { repo: 'yano-x', path: 'tooling/devtools/src/main/resources/appchain-dx/v1alpha1/appchain-capability-catalog.json',
      anchors: [
        '"id": "state:ordered-log", "name": "Ordered audit log", "category": "state", "availability": "BUNDLED", "maturity": "stable"',
        '"id": "state:kv-registry", "name": "Owned key/value registry", "category": "state", "availability": "BUNDLED", "maturity": "stable"',
        '"id": "state:authenticated-map", "name": "Authenticated map", "category": "state", "availability": "BUNDLED", "maturity": "preview"',
        '"id": "state:approval-workflow", "name": "Threshold approval workflow", "category": "state", "availability": "BUNDLED", "maturity": "stable"',
        '"id": "state:balances", "name": "Member balances", "category": "state", "availability": "BUNDLED", "maturity": "stable"',
        '"id": "state:doc-trail", "name": "Document trail", "category": "state", "availability": "BUNDLED", "maturity": "stable"',
        '"id": "state:role-approvals", "name": "Role-gated payload approvals", "category": "state", "availability": "BUNDLED", "maturity": "preview"',
      ] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/resources/appchain-dx/v1alpha1/appchain-recipe-catalog.json',
      anchors: ['"id": "audit-log"', '"id": "owned-registry"', '"id": "authenticated-map"', '"id": "approval-workflow"',
        '"id": "document-trail"', '"id": "role-approval"', '"state:kv-registry"', '"state:doc-trail"',
        '"state:role-approvals"'] },
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/StdlibProofSubjectProviders.java',
      anchors: ['"account-balance-v1"', '"registry-entry-v1"', '"document-head-v1"', '"basic-approval-outcome-v1"',
        '"authenticated-map-entry-v1"', 'claim("owner-equals"', 'claim("value-digest-equals"', 'claim("exact"',
        'claim("minimum"', 'claim("maximum"', 'claim("revision-exact"', 'claim("revision-minimum"',
        'claim("digest-equals"', '"quorum-reached"', 'claim("payload-digest"', 'claim("value-digest"'] },
    { repo: 'yano-x', path: 'capabilities/role-workflow/src/main/java/org/yanoproject/x/roles/RoleProofSubjectProviders.java',
      anchors: ['"role-approval-outcome-v1"', 'claim("status"', 'claim("payload-digest"'] },
    { repo: 'yano-x', path: 'state-machines/stdlib-contracts/src/main/java/org/yanoproject/x/stdlib/contracts/ApprovalsContract.java',
      anchors: ['key("i/", itemId)'] },
    { repo: 'yano-x', path: 'state-machines/stdlib-contracts/src/main/java/org/yanoproject/x/stdlib/contracts/BalancesContract.java',
      anchors: ['("b/" + account)'] },
    { repo: 'yano-x', path: 'state-machines/stdlib-contracts/src/main/java/org/yanoproject/x/stdlib/contracts/DocTrailContract.java',
      anchors: ['("e/" + entityId)'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/transition/FinalizedMessageIndex.java',
      anchors: ['LOGICAL_NAMESPACE = "~yano/finalized-message/v1/"', 'MessageDigest.getInstance("SHA-256")'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/transition/FinalizedMessageProofSubjectProvider.java',
      anchors: ['SUBJECT_ID = "finalized-message-v1"', '"recorded"'] },
  ],
};
