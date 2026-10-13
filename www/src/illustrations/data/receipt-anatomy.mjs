// The binding receipt's positional layout, position by position. Example values
// are the accepted registry-to-audit receipt from a real `bindings dry-run
// --report` (tooling/studio/src/test/fixtures/scenarios/cascade-report.json);
// the step row shows its derived step, ordinal 1.

const env = (id, label, sub, detail) => ({ id, label, sub, kind: 'ledger', detail });
const step = (id, label, sub, detail) => ({ id, label, sub, kind: 'core', detail });

export default {
  id: 'receipt-anatomy',
  type: 'diagram',
  title: 'Anatomy of a binding receipt',
  hint: 'Select a position to see what it records.',
  intro: 'the receipt of chapter 1’s accepted cascade. `bindings dry-run` prints it as this positional array '
    + 'under `.receipts[].receipt`; a `--report` file and Studio show the same fields by name. The lower zone is '
    + 'one element of `steps`, the derived audit append.',
  caption: 'Every source message that reaches a block gets exactly one receipt, stored under its id, whether the '
    + 'cascade was accepted or rejected. Read `status` first: it decides whether any step committed.',
  zones: [
    {
      id: 'envelope', kind: 'ledger', label: 'Receipt · 7 positions',
      contains: ['version', 'source', 'height', 'r-status', 'failed', 'r-code', 'steps'],
    },
    {
      id: 'step', kind: 'core', label: 'One step · 11 positions',
      contains: ['ordinal', 'depth', 'binding', 'target', 'message', 'events', 'conditions', 'rules',
        's-status', 's-code', 'raw'],
    },
  ],
  blocks: [
    env('version', 'version', '[0] 1',
      'Receipt layout version, always `1`. ADR-031.3 and ADR-031.4 changed the step and rule-failure arrays in '
      + 'place, so a receipt written by an older runtime fails to decode with a “predates” error instead of '
      + 'being misread.'),
    env('source', 'sourceMessageId', '[1] 1111…1111',
      'The 32-byte id of the message your application submitted. The receipt is stored under it; '
      + '`bindings receipt-key <id>` prints the state key you need for a proof.'),
    env('height', 'height', '[2] 1', 'The block height of the source message.'),
    env('r-status', 'status', '[3] ACCEPTED',
      '`ACCEPTED`: every planned write and effect intent committed. `REJECTED`: none did, including the source '
      + 'command. Only the receipt and the work charge remain.'),
    env('failed', 'failedStepOrdinal', '[4] null',
      'The ordinal of the step that failed, or `null` when the cascade was accepted.'),
    env('r-code', 'code', '[5] ""',
      'Empty when accepted. Otherwise a stable rejection code such as `MAPPING_MISSING_FIELD` or '
      + '`ADMISSION_RULE_DENIED`. The reference lists every code.'),
    env('steps', 'steps', '[6] 2 steps',
      'One array per visited step, ordered by ordinal: the source first, then derived steps. At most 257 '
      + 'steps, and the encoded receipt at most 65,536 bytes.'),
    step('ordinal', 'ordinal', '[0] 1',
      '`0` for the source command. Derived steps count from `1`, in the order the bindings derived them.'),
    step('depth', 'depth', '[1] 1',
      'Distance from the source. The cascade runs breadth first, so all depth-1 steps run before any at depth 2.'),
    step('binding', 'bindingId', '[2] audit-record',
      'The binding that derived this step, or `null` for the source command.'),
    step('target', 'targetComponentId', '[3] audit',
      'The component that ran the command, or the component that owns an effect intent.'),
    step('message', 'messageId', '[4] 1abf2641…',
      'The source id for step 0. A derived step’s id is Blake2b-256 over `yano-x-derived-command-v1`, the '
      + 'source id, the binding id and the ordinal, so every member derives the same id.'),
    step('events', 'eventsProduced', '[5] 2 event ids',
      'Event ids in production order, here `doc-trail.entry-appended.v1` and the baseline '
      + '`composite.command-accepted.v1`. Ids only: a receipt never stores event payloads.'),
    step('conditions', 'conditions', '[6] []',
      'One `[bindingId, failedClause]` pair per binding the step’s events selected. `-1`: every clause held. '
      + '`0`–`7`: the first clause that was false or failed. Bindings that were never considered are not listed.'),
    step('rules', 'rules', '[7] [0, null]',
      '`[heldCount, failure]`: how many attached admission rules held, and the first that did not, as '
      + '`[ruleId, failedClause, denyCode, writeIndex]`. Rule inputs and read values are never recorded.'),
    step('s-status', 'status', '[8] PLANNED',
      '`PLANNED`, `EFFECT_PLANNED` or `REJECTED`. In an accepted receipt, `PLANNED` steps committed. In a '
      + 'rejected receipt they did not.'),
    step('s-code', 'code', '[9] ""', 'The step’s failure code. Empty for a step that did not fail.'),
    step('raw', 'rawBody', '[10] false',
      '`true` when a `rawBody` mapping forwarded opaque command bytes instead of mapped fields.'),
  ],
  edges: [
    { from: 'steps', to: 'step', label: 'one array per step', style: 'dashed' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 480,
      zones: {
        envelope: [16, 16, 728, 168],
        step: [16, 228, 728, 236],
      },
      blocks: {
        version: [32, 52, 168, 52],
        source: [212, 52, 168, 52],
        height: [392, 52, 168, 52],
        'r-status': [572, 52, 168, 52],
        failed: [32, 116, 168, 52],
        'r-code': [212, 116, 168, 52],
        steps: [392, 116, 168, 52],
        ordinal: [32, 264, 168, 52],
        depth: [212, 264, 168, 52],
        binding: [392, 264, 168, 52],
        target: [572, 264, 168, 52],
        message: [32, 328, 168, 52],
        events: [212, 328, 168, 52],
        conditions: [392, 328, 168, 52],
        rules: [572, 328, 168, 52],
        's-status': [32, 392, 168, 52],
        's-code': [212, 392, 168, 52],
        raw: [392, 392, 168, 52],
      },
      edges: {
        'steps->step': { fromSide: 'b', toSide: 't', toAt: [476, 228], labelAt: [560, 206] },
      },
    },
    narrow: {
      width: 380,
      height: 746,
      zones: {
        envelope: [8, 12, 364, 278],
        step: [8, 330, 364, 400],
      },
      blocks: {
        version: [16, 46, 166, 52],
        source: [198, 46, 166, 52],
        height: [16, 106, 166, 52],
        'r-status': [198, 106, 166, 52],
        failed: [16, 166, 166, 52],
        'r-code': [198, 166, 166, 52],
        steps: [16, 226, 166, 52],
        ordinal: [16, 366, 166, 52],
        depth: [198, 366, 166, 52],
        binding: [16, 426, 166, 52],
        target: [198, 426, 166, 52],
        message: [16, 486, 166, 52],
        events: [198, 486, 166, 52],
        conditions: [16, 546, 166, 52],
        rules: [198, 546, 166, 52],
        's-status': [16, 606, 166, 52],
        's-code': [198, 606, 166, 52],
        raw: [16, 666, 166, 52],
      },
      edges: {
        'steps->step': { fromSide: 'b', toSide: 't', toAt: [99, 330], labelAt: [240, 306] },
      },
    },
  },
  legend: [
    ['ledger', 'receipt envelope'],
    ['core', 'step'],
  ],
  sources: [
    { repo: 'yano-x', path: 'composition/contracts/src/main/java/org/yanoproject/x/composite/contracts/BindingReceiptV1.java',
      anchors: ['Arrays.asList(1, sourceMessageId, height, accepted ? "ACCEPTED" : "REJECTED"',
        'Arrays.asList(ordinal, depth, bindingId, targetComponentId, messageId, eventsProduced',
        'rules.wire(), status, code, rawBody', 'Arrays.asList(ruleId, failedClause, denyCode, writeIndex)',
        'Arrays.asList(heldCount, failure == null ? null : failure.wire())', 'List.of(bindingId, failedClause)',
        'MAX_BYTES = 65_536', 'steps.size() > 257', 'PLANNED, EFFECT_PLANNED, or REJECTED',
        'binding receipt predates ADR-031.3', 'binding receipt predates ADR-031.4'] },
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/bindings/EventBindingWorkflow.java',
      anchors: ['yano-x-derived-command-v1', 'context.workflowState().put(source.getMessageId(), encoded)'] },
    { repo: 'yano-x', path: 'tooling/studio/src/test/fixtures/scenarios/cascade-report.json',
      anchors: ['"status" : "ACCEPTED"', '"bindingId" : "audit-record"', '"targetComponentId" : "audit"',
        '1abf2641e409d9098e32b325fa2d910ebe4591f7428bcd2b0e731235b4ce6f3d', '"heldCount" : 0',
        'composite.command-accepted.v1'] },
  ],
};
