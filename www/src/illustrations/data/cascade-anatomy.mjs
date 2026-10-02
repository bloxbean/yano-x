// How one source message runs as a declarative cascade: the composite's ingress
// checks, then, at block time on every member, the breadth-first cascade that
// commits all of its steps or none. Values come from the Studio scenario reports
// produced by a real `bindings dry-run --report` (cascade-report.json for the
// accepted and skipped outcomes, rollback-report.json for the rejected one).

const INGRESS = [
  ['The topic routes to a component', 'UNKNOWN_BINDING_SOURCE'],
  ['Its mandatory work fits, and so does a subscribed baseline event (`COMMAND_PAYLOAD_TOO_LARGE`)',
    'COMMAND_WORK_EXCEEDED'],
  ['The body decodes with the component’s codec', 'MALFORMED_SOURCE_COMMAND'],
  ['The kernel’s stateless admission accepts it', null],
  ['Static admission rules hold (none are attached here)', 'ADMISSION_RULE_DENIED'],
];
const ingressChecks = (failAt = -1) => INGRESS.map(([label, code], i) => ({
  label,
  ...(code ? { code } : {}),
  ok: failAt < 0 || i < failAt ? true : i === failAt ? false : null,
}));

const submit = {
  title: 'Submit',
  text: 'Your application sends one registry `put` to the `records` component’s topic, `records.command.v1`. '
    + 'This is the source message. Bindings decide what else happens; the application submits nothing more.',
  wires: [{ from: 'app', to: 'ingress', label: 'POST `records.command.v1`' }],
  cards: { app: { title: 'put `0102` = `0304`', detail: 'source message `1111…`' } },
};

const ingress = {
  title: 'Ingress checks',
  text: 'The member that receives the message runs the composite’s ingress checks in this order, then answers '
    + '**202**. A failed check is an HTTP **400** and nothing is pooled. 202 means queued, not success: the '
    + 'cascade has not run yet.',
  checks: ingressChecks(),
  wires: [{ from: 'ingress', to: 'app', label: '202 + messageId' }],
  cards: {
    ingress: { title: 'Admitted', detail: 'pooled and gossiped', tone: 'ok' },
    app: { title: '202 Accepted', detail: 'not final, not success', tone: 'pending' },
  },
};

const replay = {
  title: 'Replay check',
  text: 'A leader puts the message in a block, and every member runs the same cascade when it executes that block. '
    + 'First, the engine looks for a receipt already stored under this source message id. If one exists, nothing '
    + 'runs again and the stored receipt stands.',
  cards: { engine: { title: 'No receipt yet', detail: 'one work budget per block' } },
};

const decide = {
  title: 'Decide step 0',
  text: 'The engine decodes the put, runs the kernel’s admission hooks and any admission-slot rules, reserves work, '
    + 'and asks the kernel to decide. The approved plan goes into the cascade’s overlay, where later steps can read '
    + 'it. Nothing is committed yet.',
  cards: {
    engine: { title: 'Step 0 · depth 0', detail: '`records` approved' },
    records: { title: 'Planned put', detail: 'key `0102` in the overlay', tone: 'pending' },
  },
};

const condition = {
  title: 'Match bindings',
  text: 'The plan emits `kv-registry.entry-put.v1`. The engine evaluates the bindings that subscribe to that event, '
    + 'in YAML order. `audit-record` checks `event.valueLength < 100`: the value is 2 bytes, so every clause holds '
    + 'and the receipt records `failedClause -1`.',
  checks: [{ label: '`event.valueLength < 100` (2 < 100)', ok: true }],
  cards: { engine: { title: '`audit-record` matched', detail: 'failedClause -1', tone: 'ok' } },
};

const derive = {
  title: 'Derive a command',
  text: 'The mapping builds an `append` for `audit`: entity id `hex(key)` = text `0102`, the value hash, and the '
    + 'reference `published`. Its id is derived from the source id, the binding id and ordinal 1. The command joins '
    + 'the back of the queue at depth 1: the cascade runs breadth first.',
  wires: [{ from: 'records', to: 'audit', label: 'append · id `1abf2641…`' }],
  cards: { engine: { title: 'Queue', detail: 'step 1 · depth 1' } },
};

const decideDerived = {
  title: 'Decide step 1',
  text: 'The `audit` component decodes, admits and decides the derived append exactly as it would a submitted one. '
    + 'It emits `doc-trail.entry-appended.v1`. No binding subscribes to that event, so the queue is now empty.',
  cards: {
    engine: { title: 'Step 1 · depth 1', detail: '`audit` approved' },
    audit: { title: 'Planned append', detail: 'entity `0102`', tone: 'pending' },
  },
};

const preflight = {
  title: 'Preflight and claim',
  text: 'Before anything commits, the engine checks the planned effects against the block’s effect capacity '
    + '(there are none here), claims the source message id, and confirms the receipt fits its 65,536-byte cap.',
  checks: [
    { label: 'Effect intents fit the remaining capacity', code: 'EFFECT_CAPACITY_EXCEEDED', ok: true },
    { label: 'The source id is claimed once', code: 'REPLAY_OR_CONFLICT', ok: true },
    { label: 'The receipt encodes within its cap', code: 'RECEIPT_CAPACITY_EXCEEDED', ok: true },
  ],
  cards: { engine: { title: 'Preflight ✓', detail: 'claim the source id', tone: 'ok' } },
};

const commit = {
  title: 'Commit all',
  text: 'Every planned write commits together, and the receipt is stored under the source id with status '
    + '**ACCEPTED**. Both steps still read `PLANNED`: in an accepted receipt that means committed.',
  cards: {
    engine: { title: 'Receipt ACCEPTED', detail: '2 steps, both `PLANNED`', tone: 'final' },
    records: { title: 'Put committed', detail: 'key `0102`', tone: 'final' },
    audit: { title: 'Append committed', detail: 'entity `0102`', tone: 'final' },
  },
};

export default {
  id: 'cascade-anatomy',
  type: 'steps',
  title: 'How a cascade runs',
  intro: 'the registry-to-audit workflow from chapter 1. One put derives one audit append. Step through the '
    + 'accepted run, then try a “What if”: a false condition skips a binding, but an error rejects everything.',
  lanes: [
    { id: 'app', label: 'Your application', note: 'REST or Java client', kind: 'client' },
    { id: 'ingress', label: 'Ingress member', note: 'admission checks', kind: 'member' },
    { id: 'engine', label: 'Cascade engine', note: 'every member, at block time', kind: 'runtime' },
    { id: 'records', label: 'records', note: 'kv-registry component', kind: 'core' },
    { id: 'audit', label: 'audit', note: 'doc-trail component', kind: 'core' },
  ],
  scenarios: [
    {
      id: 'accepted',
      label: 'Condition true',
      steps: [submit, ingress, replay, decide, condition, derive, decideDerived, preflight, commit],
    },
    {
      id: 'skipped',
      label: 'Condition false',
      summary: 'A value of 100 bytes or more. The binding is skipped, and the put still commits.',
      steps: [submit, ingress, replay, decide, {
        title: 'Skip the binding',
        text: 'The condition is false at clause 0, so `audit-record` derives nothing. A false condition is not an '
          + 'error: the receipt records `failedClause 0` and the cascade goes on.',
        checks: [{ label: '`event.valueLength < 100`', ok: false }],
        cards: { engine: { title: '`audit-record` skipped', detail: 'failedClause 0', tone: 'pending' } },
      }, {
        title: 'Commit the source',
        text: 'The queue is empty. The put commits alone, and the receipt is **ACCEPTED** with one step. To refuse '
          + 'a command rather than skip a follow-up, use an admission rule.',
        cards: {
          engine: { title: 'Receipt ACCEPTED', detail: '1 step', tone: 'final' },
          records: { title: 'Put committed', detail: 'no audit entry', tone: 'final' },
        },
      }],
    },
    {
      id: 'rejected',
      label: 'Mapping fails',
      summary: 'The mapping copies `previousValueHash`, which a first insertion does not have.',
      steps: [submit, ingress, replay, decide, condition, {
        title: 'Mapping error',
        text: 'Building the append fails: the event has no `previousValueHash`. An evaluation error is not a false '
          + 'condition. It rejects the whole cascade with `MAPPING_MISSING_FIELD` at step 1.',
        checks: [{ label: 'Map `entryHash` from `previousValueHash`', code: 'MAPPING_MISSING_FIELD', ok: false }],
        cards: { engine: { title: 'Step 1 REJECTED', detail: '`MAPPING_MISSING_FIELD`', tone: 'fail' } },
      }, {
        title: 'Roll back',
        text: 'No business state is written: not the append, and not the put that started it. Only the receipt is '
          + 'stored, **REJECTED** with failed step 1, and the work it used stays charged. Step 0 reads `PLANNED`, '
          + 'which in a rejected receipt means not committed.',
        cards: {
          engine: { title: 'Receipt REJECTED', detail: 'failed step 1', tone: 'fail' },
          records: { title: 'Nothing written', detail: 'put rolled back', tone: 'fail' },
        },
      }],
    },
    {
      id: 'ingress-refused',
      label: 'Body does not decode',
      summary: 'A malformed body never reaches a block.',
      steps: [{ ...submit, cards: { app: { title: 'Malformed body', detail: 'not a registry command' } } }, {
        title: 'Refused at ingress',
        text: 'The registry codec cannot decode the body, so the member answers **400** with '
          + '`MALFORMED_SOURCE_COMMAND`. The message is not pooled or gossiped, and no receipt exists. Fix the '
          + 'encoding and submit again.',
        checks: ingressChecks(2),
        wires: [{ from: 'ingress', to: 'app', label: '400 `MALFORMED_SOURCE_COMMAND`', tone: 'fail' }],
        cards: {
          ingress: { title: 'Refused', detail: 'nothing pooled', tone: 'fail' },
          app: { title: '400', detail: 'no receipt', tone: 'fail' },
        },
      }],
    },
  ],
  legend: [
    ['client', 'your application'],
    ['member', 'member node'],
    ['runtime', 'cascade engine'],
    ['core', 'component'],
    ['final', 'committed'],
    ['fail', 'refused or rolled back'],
  ],
  sources: [
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/bindings/EventBindingWorkflow.java',
      anchors: ['UNKNOWN_BINDING_SOURCE', 'COMMAND_WORK_EXCEEDED', 'MALFORMED_SOURCE_COMMAND',
        'kernel.admit(command)', 'program.rules().advisory', 'work.replayed++', 'queue.addLast',
        'derivedId(source.getMessageId(), binding.id(), produced)', 'preflightEffects', 'REPLAY_OR_CONFLICT',
        'RECEIPT_CAPACITY_EXCEEDED', 'TransitionPlans.commit(plan.plan', 'Executes declarative cascades in breadth-first order'] },
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/bindings/BindingProgram.java',
      anchors: ['False conditions skip a binding, whereas evaluation errors'] },
    { repo: 'yano-x', path: 'composition/contracts/src/main/java/org/yanoproject/x/composite/contracts/BindingReceiptV1.java',
      anchors: ['MAX_BYTES = 65_536', 'A planned step in a rejected receipt is diagnostic only'] },
    { repo: 'yano-x', path: 'tooling/studio/src/test/fixtures/scenarios/cascade-report.json',
      anchors: ['1abf2641e409d9098e32b325fa2d910ebe4591f7428bcd2b0e731235b4ce6f3d', '"failedClause" : 0',
        '"failedClause" : -1', 'kv-registry.entry-put.v1', 'doc-trail.entry-appended.v1'] },
    { repo: 'yano-x', path: 'tooling/studio/src/test/fixtures/scenarios/rollback-report.json',
      anchors: ['"code" : "MAPPING_MISSING_FIELD"', '"failedStepOrdinal" : 1'] },
    { repo: 'yano-x', path: 'tooling/studio/src/test/fixtures/scenarios/rollback.yaml',
      anchors: ['entryHash: {field: previousValueHash}'] },
  ],
};
