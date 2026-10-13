// Typed views in action: the three rules of examples/bindings/asset-governed-limits.yaml
// read a governed limit and the sender's holder record from an authenticated map
// before their clauses run. Each scenario is one fixture block of that recipe; the
// outcomes are the refusals BindingRecipesIT asserts on three nodes. The genesis
// records come from TypedViewsRecipes: settings/transfer {max: 1000}; holder f553…
// {maxTransfer: 500, acquiredHeight: 0}; holder 12c1… {maxTransfer: 5000,
// acquiredHeight: 1000000}; member 890e… has no holder record.

const LIMIT = '`command.amount <= reads.limits.value.max`';
const ACTIVE = '`reads.holder.present && reads.holder.status == "ACTIVE"`';
const TIER = '`command.amount <= reads.holder.value.maxTransfer`';
const LOCK = '`context.height >= reads.holder.value.acquiredHeight + params.blocks`';

const view = (name, rows) => ({
  caption: `Value view returned for \`${name}\``,
  columns: ['Field', 'Value'],
  rows,
});
const LIMITS_VIEW = view('limits', [
  ['`reads.limits.present`', '`true`'], ['`reads.limits.status`', '`ACTIVE`'], ['`reads.limits.value.max`', '1,000'],
]);
const holderView = (maxTransfer, acquired) => view('holder', [
  ['`reads.holder.present`', '`true`'], ['`reads.holder.status`', '`ACTIVE`'],
  ['`reads.holder.value.maxTransfer`', maxTransfer], ['`reads.holder.value.acquiredHeight`', acquired],
]);
const ABSENT_VIEW = view('holder', [['`reads.holder.present`', '`false`'], ['every other field', 'absent']]);

const transfer = (height, from, amount, note) => ({
  title: 'Submit a transfer',
  text: `Height ${height}. Member \`${from}\` transfers ${amount} tokens. ${note} The \`token\` component has three `
    + 'rules attached, all selecting `transfer`: `governed-transfer-limit`, `tier-limit`, then `lock-up`. Each '
    + 'declares a read, so none is static: they run at block time, in this order.',
  wires: [{ from: 'sender', to: 'token', label: `transfer ${amount}` }],
  cards: { sender: { title: `transfer ${amount}`, detail: `from \`${from}\`` } },
});

const readLimits = {
  title: 'Read the limit',
  text: 'Before its clause runs, `governed-transfer-limit` reads `limits`: key `"transfer"` in the `settings` '
    + 'namespace of `registry`. The map’s kernel turns that key into its own state key, the engine reads it '
    + 'through the state this cascade left, and the kernel decodes the record into typed fields.',
  wires: [
    { from: 'rules', to: 'registry', label: 'read `settings/transfer`' },
    { from: 'registry', to: 'rules', label: '`max` 1,000' },
  ],
  cards: { registry: { title: '`settings/transfer`', detail: '`{max: 1000}`' } },
  state: LIMITS_VIEW,
};

const checkLimit = (amount, holds) => ({
  title: 'Check the limit',
  text: holds
    ? `${amount} is within the governed maximum, so the rule holds. The limit lives in the map, so an approved `
      + 'proposal can change it and the next block reads the new value, with no profile change.'
    : `${amount} is over the governed maximum. The step is refused with \`TRANSFER_LIMIT_EXCEEDED\`. Later rules `
      + 'are neither evaluated nor charged.',
  checks: [{ label: `${LIMIT} (${amount} ≤ 1,000)`, ok: holds, ...(holds ? {} : { code: 'TRANSFER_LIMIT_EXCEEDED' }) }],
  cards: {
    rules: holds
      ? { title: '`governed-transfer-limit` ✓', detail: 'held: 1', tone: 'ok' }
      : { title: 'Denied', detail: '`[0, [governed-transfer-limit, 0, …]]`', tone: 'fail' },
  },
});

const readHolder = (from, rows, present) => ({
  title: 'Read the holder',
  text: `\`tier-limit\` reads \`holder\`, keyed by \`{context: sender}\` in the \`holders\` namespace. `
    + (present ? `Member \`${from}\` has a holder record.`
      : `Member \`${from}\` has none, so the view holds only \`present == false\`.`),
  wires: [{ from: 'rules', to: 'registry', label: `read \`holders/${from}\`` }],
  cards: { registry: present
    ? { title: `\`holders/${from}\``, detail: 'present' }
    : { title: `\`holders/${from}\``, detail: 'absent', tone: 'pending' } },
  state: rows,
});

const checkTier = (amount, max, tierHolds, present = true) => ({
  title: 'Check the tier',
  text: !present
    ? 'Clause 0 is false, so the step is refused with `TIER_LIMIT_EXCEEDED`. The clause checks `present` before '
      + 'any other field, so the absent record never causes an error. `lock-up` never runs.'
    : tierHolds
      ? `Both clauses hold: the record is active and ${amount} is within the holder’s ${max}.`
      : `The record is active, but ${amount} is over the holder’s tier of ${max}. The step is refused at clause 1.`,
  checks: [
    { label: ACTIVE, ok: present, ...(present ? {} : { code: 'TIER_LIMIT_EXCEEDED' }) },
    present
      ? { label: `${TIER} (${amount} ≤ ${max})`, ok: tierHolds, ...(tierHolds ? {} : { code: 'TIER_LIMIT_EXCEEDED' }) }
      : { label: TIER, ok: null },
  ],
  cards: {
    rules: present && tierHolds
      ? { title: '`tier-limit` ✓', detail: 'held: 2', tone: 'ok' }
      : { title: 'Denied', detail: `\`[1, [tier-limit, ${present ? 1 : 0}, …]]\``, tone: 'fail' },
  },
});

const checkLock = (height, acquired, holds) => ({
  title: 'Check the lock-up',
  text: '`lock-up` declares its own `holder` read: each rule evaluates its reads before its clauses. The holder '
    + 'must have held the asset for `params.blocks` (2) blocks. '
    + (holds ? `At height ${height} that holds.` : `Acquired at ${acquired}, it is still locked at height ${height}.`),
  checks: [{ label: `${LOCK} (${height} ≥ ${acquired} + 2)`, ok: holds, ...(holds ? {} : { code: 'LOCKED' }) }],
  cards: {
    rules: holds
      ? { title: '`lock-up` ✓', detail: 'held: 3', tone: 'ok' }
      : { title: 'Denied', detail: '`[2, [lock-up, 0, LOCKED, null]]`', tone: 'fail' },
  },
});

const refused = (code, trace) => ({
  title: 'Refused',
  text: `The receipt is **REJECTED** with \`ADMISSION_RULE_DENIED\` and the trace \`${trace}\`. No balance changes. `
    + 'The receipt names the rule, clause and deny code, but never the values the rule read.',
  cards: {
    token: { title: 'Nothing written', tone: 'fail' },
    sender: { title: 'Receipt REJECTED', detail: `\`${code}\``, tone: 'fail' },
  },
});

export default {
  id: 'typed-view-explorer',
  type: 'steps',
  title: 'Rules that read state',
  intro: 'the `asset-governed-limits` recipe. A `token` component’s rules read a governed limit and the sender’s '
    + 'holder record from a `registry` map. Each scenario is one of the recipe’s fixture blocks.',
  lanes: [
    { id: 'sender', label: 'Sending member', note: 'submits the transfer', kind: 'member' },
    { id: 'token', label: 'token', note: 'balances component', kind: 'core' },
    { id: 'rules', label: 'Admission slot', note: 'rules in order', kind: 'runtime' },
    { id: 'registry', label: 'registry', note: 'authenticated map', kind: 'ledger' },
  ],
  scenarios: [
    {
      id: 'accepted',
      label: 'Within every limit',
      steps: [
        transfer(4, 'f553…', 100, 'Its holder tier is 500, and it acquired its holding at height 0.'),
        readLimits, checkLimit(100, true), readHolder('f553…', holderView('500', '0'), true),
        checkTier(100, '500', true), checkLock(4, 0, true), {
          title: 'Transfer commits',
          text: 'All three rules held, so the rule trace is `[3, null]`. The balances kernel then decides the transfer '
            + 'with its own checks, and the receipt is **ACCEPTED**.',
          cards: {
            token: { title: 'Transfer committed', tone: 'final' },
            sender: { title: 'Receipt ACCEPTED', detail: 'rules `[3, null]`', tone: 'final' },
          },
        },
      ],
    },
    {
      id: 'governed-limit',
      label: 'Over the governed limit',
      steps: [transfer(5, 'f553…', '2,000', 'The governed maximum is 1,000.'), readLimits, checkLimit('2,000', false),
        refused('TRANSFER_LIMIT_EXCEEDED', '[0, [governed-transfer-limit, 0, TRANSFER_LIMIT_EXCEEDED, null]]')],
    },
    {
      id: 'tier',
      label: 'Over the holder’s tier',
      steps: [transfer(6, 'f553…', 700, 'That is within 1,000 but over its tier of 500.'), readLimits,
        checkLimit(700, true), readHolder('f553…', holderView('500', '0'), true), checkTier(700, '500', false),
        refused('TIER_LIMIT_EXCEEDED', '[1, [tier-limit, 1, TIER_LIMIT_EXCEEDED, null]]')],
    },
    {
      id: 'locked',
      label: 'Still locked up',
      steps: [transfer(7, '12c1…', 10, 'Its tier is 5,000, but it acquired its holding at height 1,000,000.'),
        readLimits, checkLimit(10, true), readHolder('12c1…', holderView('5,000', '1,000,000'), true),
        checkTier(10, '5,000', true), checkLock(7, '1,000,000', false),
        refused('LOCKED', '[2, [lock-up, 0, LOCKED, null]]')],
    },
    {
      id: 'not-holder',
      label: 'Not a holder',
      steps: [transfer(8, '890e…', 10, 'It has no holder record.'), readLimits, checkLimit(10, true),
        readHolder('890e…', ABSENT_VIEW, false), checkTier(10, '–', false, false),
        refused('TIER_LIMIT_EXCEEDED', '[1, [tier-limit, 0, TIER_LIMIT_EXCEEDED, null]]')],
    },
    {
      id: 'no-guard',
      label: 'Without the present guard',
      summary: 'A hypothetical edit: `tier-limit`’s first clause reads `status` without checking `present`.',
      steps: [transfer(8, '890e…', 10, 'It has no holder record.'), readLimits, checkLimit(10, true),
        readHolder('890e…', ABSENT_VIEW, false), {
          title: 'Read an absent field',
          text: 'The clause `reads.holder.status == "ACTIVE"` reads a field the absent record does not have. That is '
            + 'an evaluation error, not a false clause: the step fails closed with `ADMISSION_RULE_ERROR` at clause '
            + '0, and the receipt carries no deny code. Guard every read with `present` first.',
          checks: [{ label: '`reads.holder.status == "ACTIVE"`', ok: false, code: 'ADMISSION_RULE_ERROR' }],
          cards: {
            rules: { title: 'Error at clause 0', detail: '`[1, [tier-limit, 0, null, null]]`', tone: 'fail' },
            sender: { title: 'Receipt REJECTED', detail: '`ADMISSION_RULE_ERROR`', tone: 'fail' },
          },
        }],
    },
  ],
  legend: [
    ['member', 'member'],
    ['core', 'component'],
    ['runtime', 'rule evaluation'],
    ['ledger', 'state that rules read'],
    ['fail', 'refused'],
  ],
  sources: [
    { repo: 'yano-x', path: 'examples/bindings/asset-governed-limits.yaml',
      anchors: ['id: governed-transfer-limit', 'limits: { component: registry, namespace: settings, key: { literal: "transfer" } }',
        'command.amount <= reads.limits.value.max', 'holder: { component: registry, namespace: holders, key: { context: sender } }',
        'reads.holder.present && reads.holder.status == "ACTIVE"', 'command.amount <= reads.holder.value.maxTransfer',
        'context.height >= reads.holder.value.acquiredHeight + params.blocks', 'params: { blocks: 2 }',
        'deny: TIER_LIMIT_EXCEEDED', 'deny: LOCKED',
        'the first member (tier 500, acquired at 0) and the second (tier 5000, acquired at'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/integrationTest/java/org/yanoproject/x/devtools/BindingRecipesIT.java',
      anchors: ['transfer(senders.get(2), 2_000)', '"governed-transfer-limit", 0, "TRANSFER_LIMIT_EXCEEDED", null',
        'Refusal.denied(1, "tier-limit", 1, "TIER_LIMIT_EXCEEDED", null, Set.of())',
        'Refusal.denied(2, "lock-up", 0, "LOCKED", null, Set.of())',
        'Refusal.denied(1, "tier-limit", 0, "TIER_LIMIT_EXCEEDED", null, Set.of())',
        'The second member acquired at height 1,000,000 and is locked; the third holds no record.'] },
    { repo: 'yano-x', path: 'state-machines/stdlib/src/test/java/org/yanoproject/x/stdlib/TypedViewsRecipes.java',
      anchors: ['value(Map.of("max", new UnsignedInteger(1_000)))'] },
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/bindings/BindingRules.java',
      anchors: ['Reads are evaluated once per rule, in declaration order', 'kernel.ruleValueKey(read.namespace(), key.clone())',
        'kernel.ruleValueFields(read.namespace(), key.clone(), stored.get())', 'values.put(read.name() + ".present", stored.isPresent())'] },
    { repo: 'yano-x', path: 'composition/runtime/src/test/java/org/yanoproject/x/composite/bindings/TypedViewsEngineTest.java',
      anchors: ['assertThat(failing.receipt(message).code()).isEqualTo("ADMISSION_RULE_ERROR")'] },
    { repo: 'yano-x', path: 'examples/bindings/fixtures/asset-governed-limits/fixture-7.json',
      anchors: ['"height" : 7', '"senderHex" : "12c1a89644d1e06f5f6aab065ed3735e2b86a830aaab42116b0a3c6b953b4add"'] },
  ],
};
