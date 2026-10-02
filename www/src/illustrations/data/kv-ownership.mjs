// First-writer ownership in kv-registry, step by step, with the exact decision
// rule (KvRegistryTransitions.decide) and what a verifier can prove at each step.

const OWNER_RULE = 'The key has no entry, or the sender owns it';

const aPuts = {
  title: 'A puts',
  text: 'Member A submits PUT `supplier-42` = `active`. In the final block the key has no entry, so the machine '
    + 'writes `[A, "active"]`. A owns the key.',
  viewText: { verifier: 'A proof of `supplier-42` now shows owner A and the digest of `active`.' },
  wires: [{ from: 'a', to: 'sm', label: 'PUT `supplier-42` = `active`' }],
  checks: [{ label: OWNER_RULE, ok: true }],
  state: {
    caption: 'kv-registry state',
    columns: ['Key', 'Owner', 'Value'],
    rows: [['`supplier-42`', 'A', '`active`']],
    highlight: [0],
  },
  cards: {
    a: { title: 'Owner', detail: 'of `supplier-42`', tone: 'ok' },
    sm: { title: 'Entry created', detail: '`[A, "active"]`', tone: 'final' },
    verifier: { title: 'Owner = A', detail: '`owner-equals` A holds' },
  },
};

const bPuts = {
  title: 'B puts',
  text: 'Member B submits PUT `supplier-42` = `suspended`. Admission does not check ownership, so B gets **202** '
    + 'and the message is finalized. In the block, the entry is owned by A, so the decision is `KV_NOT_OWNER`: '
    + 'nothing is written.',
  viewText: { verifier: 'The proof is unchanged: owner A, value `active`. A final message is not a change.' },
  wires: [{ from: 'b', to: 'sm', label: 'PUT `supplier-42` = `suspended`' }],
  checks: [{ label: OWNER_RULE, ok: false, code: 'KV_NOT_OWNER' }],
  state: {
    caption: 'kv-registry state (unchanged)',
    columns: ['Key', 'Owner', 'Value'],
    rows: [['`supplier-42`', 'A', '`active`']],
  },
  cards: {
    b: { title: 'Final, no effect', detail: '`KV_NOT_OWNER`', tone: 'fail' },
    sm: { title: 'No change', detail: '`[A, "active"]`' },
  },
};

const aUpdates = {
  title: 'A updates',
  text: 'Member A submits PUT `supplier-42` = `suspended`. A owns the entry, so the value is replaced.',
  viewText: { verifier: 'The proof now shows owner A and the digest of `suspended`.' },
  wires: [{ from: 'a', to: 'sm', label: 'PUT `supplier-42` = `suspended`' }],
  checks: [{ label: OWNER_RULE, ok: true }],
  state: {
    caption: 'kv-registry state',
    columns: ['Key', 'Owner', 'Value'],
    rows: [['`supplier-42`', 'A', '`suspended`']],
    highlight: [0],
  },
  cards: {
    b: null,
    sm: { title: 'Value replaced', detail: '`[A, "suspended"]`', tone: 'final' },
    verifier: { title: 'Value changed', detail: '`value-digest-equals` the new digest' },
  },
};

const aDeletes = {
  title: 'A deletes',
  text: 'Member A submits DELETE `supplier-42`. The owner may delete, so the entry is removed. A proof of the key '
    + 'is now an exclusion proof with no value.',
  viewText: { verifier: 'The proof reports the key as absent: an exclusion proof against the root.' },
  wires: [{ from: 'a', to: 'sm', label: 'DELETE `supplier-42`' }],
  checks: [{ label: OWNER_RULE, ok: true }],
  state: { caption: 'kv-registry state', columns: ['Key', 'Owner', 'Value'], rows: [] },
  cards: {
    a: null,
    sm: { title: 'Entry deleted', detail: 'key absent', tone: 'final' },
    verifier: { title: 'Absent', detail: 'exclusion proof' },
  },
};

const bTakes = {
  title: 'B puts again',
  text: 'Member B submits PUT `supplier-42` = `active`. The key has no entry, so B becomes the owner. This is not '
    + 'a transfer: the key was released and B created a new entry.',
  viewText: { verifier: 'The proof now shows owner B. Nothing in the entry links it to A’s earlier entry.' },
  wires: [{ from: 'b', to: 'sm', label: 'PUT `supplier-42` = `active`' }],
  checks: [{ label: OWNER_RULE, ok: true }],
  state: {
    caption: 'kv-registry state',
    columns: ['Key', 'Owner', 'Value'],
    rows: [['`supplier-42`', 'B', '`active`']],
    highlight: [0],
  },
  cards: {
    b: { title: 'New owner', detail: 'of `supplier-42`', tone: 'ok' },
    sm: { title: 'Entry created', detail: '`[B, "active"]`', tone: 'final' },
    verifier: { title: 'Owner = B', detail: 'a new entry, not a transfer' },
  },
};

export default {
  id: 'kv-ownership',
  type: 'steps',
  title: 'Who owns a key',
  intro: 'two members write the same key. Every message here is final; only the ones that pass the ownership rule '
    + 'change state. Switch to the verifier’s view to see what a proof shows.',
  lanes: [
    { id: 'a', label: 'Member A', note: 'node 1', kind: 'member' },
    { id: 'b', label: 'Member B', note: 'node 2', kind: 'member' },
    { id: 'sm', label: 'kv-registry', note: 'on every member', kind: 'core' },
    { id: 'verifier', label: 'Verifier', note: 'reads proofs', kind: 'client' },
  ],
  views: [
    { id: 'default', label: 'Writers' },
    { id: 'verifier', label: 'Verifier', focus: ['verifier', 'sm'] },
  ],
  scenarios: [
    { id: 'ownership', label: 'First writer owns', steps: [aPuts, bPuts, aUpdates, aDeletes, bTakes] },
    {
      id: 'b-deletes',
      label: 'B deletes A’s key',
      summary: 'The ownership rule applies to DELETE as well as PUT.',
      steps: [aPuts, {
        title: 'B deletes',
        text: 'Member B submits DELETE `supplier-42`. The entry exists and A owns it, so the decision is '
          + '`KV_NOT_OWNER`. The entry stays.',
        viewText: { verifier: 'The proof still shows owner A and value `active`.' },
        wires: [{ from: 'b', to: 'sm', label: 'DELETE `supplier-42`' }],
        checks: [{ label: OWNER_RULE, ok: false, code: 'KV_NOT_OWNER' }],
        state: {
          caption: 'kv-registry state (unchanged)',
          columns: ['Key', 'Owner', 'Value'],
          rows: [['`supplier-42`', 'A', '`active`']],
        },
        cards: { b: { title: 'Final, no effect', detail: '`KV_NOT_OWNER`', tone: 'fail' } },
      }],
    },
    {
      id: 'absent-delete',
      label: 'Delete a missing key',
      summary: 'Deleting a key that has no entry is allowed and does nothing.',
      steps: [{
        title: 'Nothing stored',
        text: 'The registry has no entry for `supplier-42`.',
        state: { caption: 'kv-registry state', columns: ['Key', 'Owner', 'Value'], rows: [] },
        cards: { sm: { title: 'Empty', detail: 'no entry' } },
      }, {
        title: 'B deletes',
        text: 'Member B submits DELETE `supplier-42`. No entry means no owner to check, and nothing to remove: the '
          + 'plan is empty and no event is produced.',
        wires: [{ from: 'b', to: 'sm', label: 'DELETE `supplier-42`' }],
        checks: [{ label: OWNER_RULE, ok: true }],
        state: { caption: 'kv-registry state', columns: ['Key', 'Owner', 'Value'], rows: [] },
        cards: { b: { title: 'Final, no change', detail: 'empty plan' } },
      }],
    },
    {
      id: 'composite',
      label: 'Inside a composite',
      summary: 'In a declarative composite the same rule rejects the whole cascade instead of recording a no-op.',
      steps: [aPuts, {
        title: 'B’s cascade fails',
        text: 'A binding derives a PUT on `supplier-42` from a command that member B sent. The derived command keeps '
          + 'B’s authority, so the registry returns `KV_NOT_OWNER` and the cascade is rejected: none of its business '
          + 'writes are kept.',
        wires: [{ from: 'b', to: 'sm', label: 'derived PUT (B’s authority)' }],
        checks: [{ label: OWNER_RULE, ok: false, code: 'KV_NOT_OWNER' }],
        state: {
          caption: 'kv-registry state (unchanged)',
          columns: ['Key', 'Owner', 'Value'],
          rows: [['`supplier-42`', 'A', '`active`']],
        },
        cards: { b: { title: 'Cascade rejected', detail: '`KV_NOT_OWNER`', tone: 'fail' } },
      }],
    },
  ],
  legend: [
    ['member', 'member node'],
    ['core', 'deterministic state machine'],
    ['client', 'verifier'],
    ['final', 'state changed'],
    ['fail', 'no effect'],
  ],
  sources: [
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/KvRegistryTransitions.java',
      anchors: ['"KV_NOT_OWNER"', '"sender does not own the key"',
        'encodeEntry(context.sender(), command.value())',
        'command.operation() == OP_DELETE && facts.currentEntry().isPresent()',
        'TransitionDecision.approve(TransitionPlan.empty())'] },
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/KvRegistryStateMachine.java',
      anchors: ['"kv-registry: transition rejected at block {} ({})"', 'transitions.decodeCommand(message.getBody());'] },
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/StockTransitionKernels.java',
      anchors: ['"kv-registry.entry-put.v1"', '"kv-registry.entry-deleted.v1"',
        'approved.plan().mutations().isEmpty()) return decision;'] },
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/StdlibProofSubjectProviders.java',
      anchors: ['"registry-entry-v1"', 'claim("owner-equals"', 'claim("value-digest-equals"'] },
    { repo: 'yano-x', path: 'docs/appchain/DECLARATIVE_BINDINGS.md',
      anchors: ['Targets can also return their existing domain rejection codes'] },
    { repo: 'yano-x', path: 'adr/app-layer/031.1-declarative-event-bindings-for-composite-workflows.md',
      anchors: ['Derived commands carry the originator\'s sender'] },
  ],
};
