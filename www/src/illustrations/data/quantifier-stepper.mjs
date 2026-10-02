// `writes.all` stepping through an authenticated-map write view, from
// examples/bindings/dpp-namespace-isolation.yaml. Each scenario is one fixture
// block of that recipe; the outcomes are the refusals BindingRecipesIT asserts
// on three nodes. Demo actors come from DppGenesis: logistics-a is an operator at
// swift-logistics; maker-a is a manufacturer and operator at acme-manufacturing.

const OWNS = '`writes.all(w, (w.collection != "product-versions" && w.collection != "events") || '
  + '(w.coverage == "direct" && startsWith(w.keyText, w.actorOrganizationId + "/")))`';

const COLUMNS = ['`index`', '`collection`', '`keyText`', '`op`', '`coverage`', '`actorOrganizationId`'];
const BATCH = [
  ['0', '`events`', '`swift-logistics/p1/e3`', '`PUT`'],
  ['1', '`events`', '`green-labs/p1/e4`', '`PUT`'],
];
const covered = (rows, org) => rows.map((row) => [...row, '`direct`', org]);
const writeView = (rows, highlight, caption) => ({ caption, columns: COLUMNS, rows, highlight });
const contentOnly = (rows) => rows.map((row) => [...row, '–', '–']);

export default {
  id: 'quantifier-stepper',
  type: 'steps',
  title: 'Stepping through writes.all',
  intro: 'the `dpp-namespace-isolation` recipe. A direct write to `product-versions` or `events` must be keyed '
    + 'under the covering actor’s organization. Watch `writes.all` visit a batch one write at a time.',
  lanes: [
    { id: 'actor', label: 'Actor', note: 'signs the map command', kind: 'actor' },
    { id: 'map', label: 'registry', note: 'authenticated map', kind: 'core' },
    { id: 'rule', label: 'Fact slot', note: 'manufacturer-owns-product', kind: 'runtime' },
  ],
  scenarios: [
    {
      id: 'foreign',
      label: 'One write is foreign',
      steps: [{
        title: 'One batch, two writes',
        text: '`logistics-a` signs one map command with two puts to `events`. One direct authorization covers both. '
          + 'The map’s write view has one element per write, in command order, with the engine’s `index`.',
        wires: [{ from: 'actor', to: 'map', label: 'batch of 2 writes' }],
        cards: { actor: { title: '`logistics-a`', detail: 'swift-logistics · operator' } },
        state: writeView(contentOnly(BATCH), [], 'Write view before verification: content only'),
      }, {
        title: 'Verify, then cover',
        text: 'The map verifies the signature and approves. Only that approval supplies coverage: `direct`, with the '
          + 'actor’s organization and roles. A rule that reads coverage is a fact rule, so it runs only now, never '
          + 'on unverified evidence.',
        cards: { map: { title: 'Approved', detail: 'both writes `direct`', tone: 'ok' } },
        state: writeView(covered(BATCH, '`swift-logistics`'), [], 'Write view with coverage'),
      }, {
        title: 'Element 0',
        text: '`writes.all` visits index 0. The collection is `events`, so the second part decides: the coverage is '
          + '`direct` and `swift-logistics/p1/e3` starts with `swift-logistics/`. True, so it moves on.',
        checks: [{ label: 'index 0: key under `swift-logistics/`', ok: true }],
        cards: { rule: { title: 'Element 0 ✓', detail: 'continue' } },
        state: writeView(covered(BATCH, '`swift-logistics`'), [0], 'Visiting index 0'),
      }, {
        title: 'Element 1',
        text: '`green-labs/p1/e4` does not start with `swift-logistics/`. The body is false, so `writes.all` stops '
          + 'here and returns false. The index where it stopped, 1, is the deciding write.',
        checks: [
          { label: 'index 0: key under `swift-logistics/`', ok: true },
          { label: 'index 1: key under `swift-logistics/`', ok: false, code: 'FOREIGN_PRODUCT' },
        ],
        cards: { rule: { title: 'Stopped at 1', detail: '`writeIndex` 1', tone: 'fail' } },
        state: writeView(covered(BATCH, '`swift-logistics`'), [1], 'Visiting index 1'),
      }, {
        title: 'Refuse the batch',
        text: 'The step fails with `ADMISSION_RULE_DENIED` and the trace `[0, [manufacturer-owns-product, 0, '
          + 'FOREIGN_PRODUCT, 1]]`. Write 0 is refused too: a command commits whole or not at all. The signature work '
          + 'stays charged, because the rule ran after verification. `bindings dry-run` reports “at write 1”.',
        checks: [{ label: OWNS, ok: false, code: 'FOREIGN_PRODUCT' }],
        cards: {
          map: { title: 'Nothing written', detail: 'both writes refused', tone: 'fail' },
          actor: { title: 'Receipt REJECTED', detail: 'write 1 decided', tone: 'fail' },
        },
      }],
    },
    {
      id: 'all-hold',
      label: 'Every write holds',
      summary: '`maker-a` of `acme-manufacturing` writes under its own organization.',
      steps: [{
        title: 'One write',
        text: '`maker-a` signs one put to `events` at `acme-manufacturing/p2/e1`, covered by its own direct '
          + 'authorization.',
        wires: [{ from: 'actor', to: 'map', label: 'put `acme-manufacturing/p2/e1`' }],
        cards: { actor: { title: '`maker-a`', detail: 'acme-manufacturing · manufacturer' } },
        state: writeView([['0', '`events`', '`acme-manufacturing/p2/e1`', '`PUT`', '`direct`', '`acme-manufacturing`']],
          [0], 'Write view with coverage'),
      }, {
        title: 'Run to completion',
        text: 'Every element holds, so `writes.all` visits them all and returns true. A quantifier that runs to the '
          + 'end decides at no particular write, so a failure trace would carry no write index. Both attached rules '
          + 'hold: the trace is `[2, null]` and the write commits.',
        checks: [{ label: OWNS, ok: true }],
        cards: {
          rule: { title: 'Held', detail: 'no deciding write', tone: 'ok' },
          map: { title: 'Committed', tone: 'final' },
        },
      }],
    },
    {
      id: 'revoke',
      label: 'Revoke without the role',
      summary: 'The second attached rule, `admin-only-lifecycle-ops`, needs the `manufacturer` role to revoke.',
      steps: [{
        title: 'A revoke',
        text: '`logistics-a` revokes its own `events` entry `swift-logistics/p1/e1`. The key is under its '
          + 'organization, so `manufacturer-owns-product` holds.',
        wires: [{ from: 'actor', to: 'map', label: 'revoke `swift-logistics/p1/e1`' }],
        checks: [{ label: OWNS, ok: true }],
        cards: { rule: { title: '`manufacturer-owns-product` ✓', detail: 'held: 1', tone: 'ok' } },
        state: writeView([['0', '`events`', '`swift-logistics/p1/e1`', '`REVOKE`', '`direct`', '`swift-logistics`']],
          [0], 'Write view with coverage'),
      }, {
        title: 'Role required',
        text: '`admin-only-lifecycle-ops` checks `writes.all(w, (w.op != "REVOKE" && w.op != "RESTORE") || '
          + '(w.coverage == "direct" && params.role in w.actorRoles))` with `role: manufacturer`. `logistics-a` '
          + 'holds only `operator`, so element 0 is false.',
        checks: [{ label: 'index 0: `"manufacturer" in w.actorRoles`', ok: false, code: 'ADMIN_ROLE_REQUIRED' }],
        cards: {
          rule: { title: 'Denied', detail: '`[1, [admin-only-lifecycle-ops, 0, ADMIN_ROLE_REQUIRED, 0]]`', tone: 'fail' },
          actor: { title: 'Receipt REJECTED', detail: '`ADMIN_ROLE_REQUIRED`', tone: 'fail' },
        },
      }],
    },
  ],
  legend: [
    ['actor', 'business actor'],
    ['core', 'component'],
    ['runtime', 'rule evaluation'],
    ['fail', 'refused'],
  ],
  sources: [
    { repo: 'yano-x', path: 'examples/bindings/dpp-namespace-isolation.yaml',
      anchors: ['id: manufacturer-owns-product', 'deny: FOREIGN_PRODUCT', 'id: admin-only-lifecycle-ops',
        'deny: ADMIN_ROLE_REQUIRED', 'params: { role: "manufacturer" }',
        'writes.all(w, (w.collection != "product-versions" && w.collection != "events") || (w.coverage == "direct" && startsWith(w.keyText, w.actorOrganizationId + "/")))',
        'writes.all(w, (w.op != "REVOKE" && w.op != "RESTORE") || (w.coverage == "direct" && params.role in w.actorRoles))'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/integrationTest/java/org/yanoproject/x/devtools/BindingRecipesIT.java',
      anchors: ['put(events, "swift-logistics/p1/e3", swift), put(events, "green-labs/p1/e4", swift)',
        '"FOREIGN_PRODUCT", 1, Set.of(ACTOR_WORK_KEY)', 'In a batch the receipt names the write that decided.',
        'Refusal.denied(1, "admin-only-lifecycle-ops", 0, "ADMIN_ROLE_REQUIRED", 0,',
        '"acme-manufacturing/p2/e1", acme)), policy, "maker-a", 0x35), null)'] },
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/bindings/BindingExpressionEvaluator.java',
      anchors: ['evaluates its body per element in index order', 'the first {@code false} ({@code all}), the first {@code true} ({@code exists})',
        'a quantifier that runs to completion clears it'] },
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/AuthenticatedMapTransitionKernel.java',
      anchors: ['one element per mutation of a command, in command order', '"coverage", "direct", "actorId"',
        '"actorOrganizationId", verified.organization().organizationId()', '"PUT", "PUT_IF_ABSENT"', '"REVOKE", "RESTORE"'] },
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/bindings/BindingRules.java',
      anchors: ['coverage once, in the fact slot, only when an applicable fact rule reads'] },
    { repo: 'yano-x', path: 'products/dpp/profile/src/main/java/org/yanoproject/x/dpp/profile/DppGenesis.java',
      anchors: ['demoActor(chainId, "logistics-a", "swift-logistics", List.of(DppStarterProfile.OPERATOR_ROLE))',
        'demoActor(chainId, "maker-a", "acme-manufacturing", List.of(DppStarterProfile.MANUFACTURER_ROLE, DppStarterProfile.OPERATOR_ROLE))'] },
  ],
};
