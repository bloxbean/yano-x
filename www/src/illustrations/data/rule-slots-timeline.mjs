// When admission rules run: at local ingress (static rules only), in the
// admission slot before any work is reserved, and in the verified-fact slot after
// the kernel approved. Outcomes are the expected refusals that BindingRecipesIT
// asserts on three nodes for the shipped recipes (feed-slot-rules,
// balances-transfer-limit, procurement-admission) and the Studio quorum report.

const RANGE = '`reads.feed.present && reads.feed.value.status == "OPEN"`';
const IN_RANGE = '`writes.all(w, …price between reads.feed.value.min and max)`';
const OWN_SLOT = '`writes.all(w, …PUT_IF_ABSENT, coverage direct, key starts with actorId + "/")`';

const submitObservation = (key, price) => ({
  title: 'Submit',
  text: `Actor \`logistics-a\` signs one map write: \`PUT_IF_ABSENT observations/${key}\` with price ${price}. A `
    + 'member submits it to the `registry` component. Two rules are attached, in this order: '
    + '`own-insert-only-slot`, then `feed-open-and-in-range`.',
  wires: [{ from: 'app', to: 'ingress', label: `put \`${key}\` · price ${price}` }],
  cards: { app: { title: `\`${key}\` = ${price}`, detail: 'signed by `logistics-a`' } },
});

const ingressNoStatic = {
  title: 'Ingress',
  text: 'Neither rule is static: one declares a read, the other reads write coverage. So the ingress member runs '
    + 'only the codec and the kernel’s stateless admission, and answers **202**. Both rules wait for the block.',
  checks: [{ label: 'Static rules attached to `registry`: none', ok: true }],
  wires: [{ from: 'ingress', to: 'app', label: '202' }],
  cards: { ingress: { title: 'Admitted', detail: 'no static rules', tone: 'ok' } },
};

const admissionSlot = (price, holds) => ({
  title: 'Admission slot',
  text: 'At block time, the admission slot runs after the kernel’s admit hooks and before any work is reserved. '
    + '`feed-open-and-in-range` runs first even though it is attached second, because it reads neither facts nor coverage. Its read `feed` '
    + `finds \`feeds/main\`: OPEN, min 100, max 200. Price ${price} ${holds ? 'is in range' : 'is not'}.`,
  checks: [
    { label: RANGE, ok: true },
    { label: IN_RANGE, ok: holds, ...(holds ? {} : { code: 'OBSERVATION_OUT_OF_RANGE' }) },
  ],
  cards: {
    admission: holds
      ? { title: '`feed-open-and-in-range` ✓', detail: 'held: 1', tone: 'ok' }
      : { title: 'Denied at clause 1', detail: 'write 0', tone: 'fail' },
  },
});

const kernelDecides = {
  title: 'Kernel decides',
  text: 'The map reserves crypto work, verifies `logistics-a`’s signature against the collection’s policy, and '
    + 'approves. That approval’s facts give the write its coverage: `direct`, actor `logistics-a`, organization '
    + '`swift-logistics`.',
  cards: { kernel: { title: 'Approved', detail: 'coverage `direct`', tone: 'ok' } },
};

const factSlot = (key, holds) => ({
  title: 'Fact slot',
  text: '`own-insert-only-slot` reads coverage, so it runs only now, with the exact facts of that approval. A '
    + `forged or unverified signature never reaches it. The key \`${key}\` `
    + `${holds ? 'starts with `logistics-a/`.' : 'does not start with `logistics-a/`.'}`,
  checks: [{ label: OWN_SLOT, ok: holds, ...(holds ? {} : { code: 'OBSERVATION_REJECTED' }) }],
  cards: {
    facts: holds
      ? { title: '`own-insert-only-slot` ✓', detail: 'held: 2', tone: 'ok' }
      : { title: 'Denied at clause 0', detail: 'write 0', tone: 'fail' },
  },
});

export default {
  id: 'rule-slots-timeline',
  type: 'steps',
  title: 'When admission rules run',
  intro: 'the `feed-slot-rules` recipe on a governed map. Step through an accepted observation, then try the '
    + '“What if” refusals, including a static rule at ingress and a refusal at depth 1 from other recipes.',
  lanes: [
    { id: 'app', label: 'Submitter', note: 'actor and member', kind: 'client' },
    { id: 'ingress', label: 'Ingress member', note: 'static rules only', kind: 'member' },
    { id: 'admission', label: 'Admission slot', note: 'before work is reserved', kind: 'runtime' },
    { id: 'kernel', label: 'Kernel', note: 'verifies and decides', kind: 'core' },
    { id: 'facts', label: 'Fact slot', note: 'after approval', kind: 'runtime' },
  ],
  scenarios: [
    {
      id: 'held',
      label: 'Both rules hold',
      steps: [submitObservation('logistics-a/1', 150), ingressNoStatic, admissionSlot(150, true), kernelDecides,
        factSlot('logistics-a/1', true), {
          title: 'Commit',
          text: 'Both rules held, so the step’s rule trace is `[2, null]`. The observation commits and the receipt '
            + 'is **ACCEPTED**.',
          cards: { app: { title: 'Receipt ACCEPTED', detail: 'rules `[2, null]`', tone: 'final' } },
        }],
    },
    {
      id: 'out-of-range',
      label: 'Price out of range',
      summary: 'An admission-slot refusal: nothing was verified, so no crypto work was spent.',
      steps: [submitObservation('logistics-a/3', 250), ingressNoStatic, admissionSlot(250, false), {
        title: 'Refused before work',
        text: 'The step fails with `ADMISSION_RULE_DENIED`. The kernel never verified the signature and no work was '
          + 'reserved. Only the receipt is written, with trace `[0, [feed-open-and-in-range, 1, '
          + 'OBSERVATION_OUT_OF_RANGE, 0]]`: the rule, the clause, the deny code and the deciding write.',
        cards: {
          kernel: { title: 'Not reached', tone: 'pending' },
          app: { title: 'Receipt REJECTED', detail: '`OBSERVATION_OUT_OF_RANGE`', tone: 'fail' },
        },
      }],
    },
    {
      id: 'foreign-slot',
      label: 'Someone else’s slot',
      summary: 'A fact-slot refusal: the signature was verified first, and that work stays charged.',
      steps: [submitObservation('maker-a/1', 150), ingressNoStatic, admissionSlot(150, true), kernelDecides,
        factSlot('maker-a/1', false), {
          title: 'Work stays charged',
          text: 'The step fails with `ADMISSION_RULE_DENIED`, trace `[1, [own-insert-only-slot, 0, '
            + 'OBSERVATION_REJECTED, 0]]`. The write is rolled back, but the crypto work the kernel reserved to '
            + 'verify the signature is not refunded. Only fact-slot refusals keep that charge.',
          cards: {
            kernel: { title: 'Work kept', detail: 'actor work counter', tone: 'pending' },
            app: { title: 'Receipt REJECTED', detail: '`OBSERVATION_REJECTED`', tone: 'fail' },
          },
        }],
    },
    {
      id: 'static',
      label: 'A static rule at ingress',
      summary: 'From `balances-transfer-limit.yaml`: a rule over `command.*` and `params.*` only.',
      steps: [{
        title: 'Submit a transfer',
        text: 'A member submits a `transfer` of 20,000 points. The `points` component has `transfer-limit` attached '
          + 'with `maxAmount: 10000`. The rule reads only `command.amount` and `params.maxAmount`, so it is static.',
        wires: [{ from: 'app', to: 'ingress', label: 'transfer 20,000' }],
        cards: { app: { title: 'transfer 20,000', detail: '`points.command.v1`' } },
      }, {
        title: 'Refused at ingress',
        text: 'The ingress member evaluates static rules before pooling, so the over-limit transfer is refused at '
          + 'once with **400**. The REST body names the rule and its deny code. Nothing is pooled, and there is no '
          + 'block and no receipt.',
        checks: [{ label: '`command.amount <= params.maxAmount` (20,000 ≤ 10,000)', ok: false,
          code: 'TRANSFER_LIMIT_EXCEEDED' }],
        wires: [{ from: 'ingress', to: 'app', label: '400 `{rule, deny}`', tone: 'fail' }],
        cards: {
          ingress: { title: 'Refused', detail: '`ADMISSION_RULE_DENIED`', tone: 'fail' },
          app: { title: '400', detail: '`transfer-limit` · `TRANSFER_LIMIT_EXCEEDED`', tone: 'fail' },
        },
      }, {
        title: 'Block time still decides',
        text: 'The ingress check is advisory: block-time evaluation is authoritative, and a message that reaches a '
          + 'block is judged again there. `bindings dry-run` reproduces the ingress refusal as '
          + '`ADMISSION_RULE_DENIED/transfer-limit/TRANSFER_LIMIT_EXCEEDED`.',
        cards: { admission: { title: 'Authoritative', detail: 'at block time' } },
      }],
    },
    {
      id: 'depth-one',
      label: 'Refused at depth 1',
      summary: 'From the procurement rules with the binding’s `required` lowered to 1.',
      steps: [{
        title: 'Submit an order',
        text: 'A registered supplier puts an order into `orders`. Its rule `registered-supplier` holds, and the '
          + 'binding `order-to-approval` derives `approvals.propose` with `required: 1`.',
        wires: [{ from: 'app', to: 'ingress', label: 'put order' }],
        cards: { app: { title: 'Order put', detail: '`orders.command.v1`' } },
      }, {
        title: 'Refuse the derived step',
        text: 'Rules run for derived commands too, at every depth. On `approvals`, `minimum-quorum` requires '
          + '`command.required >= params.minimum` with `minimum: 2`, so the derived proposal is refused at step 1.',
        checks: [{ label: '`command.required >= params.minimum` (1 ≥ 2)', ok: false, code: 'QUORUM_TOO_LOW' }],
        cards: { admission: { title: 'Denied at step 1', detail: '`minimum-quorum`', tone: 'fail' } },
      }, {
        title: 'Roll back the cascade',
        text: 'A refused step rejects its source message. The order put at depth 0 is rolled back with the '
          + 'proposal, and the receipt is **REJECTED** with failed step 1. A false binding condition would only '
          + 'have skipped the proposal.',
        cards: { app: { title: 'Receipt REJECTED', detail: 'failed step 1 · no order', tone: 'fail' } },
      }],
    },
  ],
  legend: [
    ['member', 'ingress member'],
    ['runtime', 'rule slot'],
    ['core', 'kernel'],
    ['final', 'committed'],
    ['fail', 'refused'],
  ],
  sources: [
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/bindings/BindingRules.java',
      anchors: ['evaluated after the kernel\'s admit hooks and before any work is reserved',
        'every admission-slot rule precedes every fact rule', 'Advisory ingress evaluation of the static rules',
        'details.put("rule", run.failure.ruleId())', 'details.put("deny", run.failure.denyCode())',
        'ADMISSION_RULE_DENIED'] },
    { repo: 'yano-x', path: 'composition/contracts/src/main/java/org/yanoproject/x/composite/contracts/BindingIrV1.java',
      anchors: ['A static rule has no reads and only expression clauses over {@code command}, {@code params}'] },
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/bindings/EventBindingWorkflow.java',
      anchors: ['program.rules().admissionSlot', 'TransitionWorkAccounting.reserve', 'program.rules().factSlot'] },
    { repo: 'yano-x', path: 'examples/bindings/feed-slot-rules.yaml',
      anchors: ['id: own-insert-only-slot', 'id: feed-open-and-in-range', 'deny: OBSERVATION_REJECTED',
        'deny: OBSERVATION_OUT_OF_RANGE', 'feed: { component: registry, namespace: feeds, key: { literal: "main" } }',
        'reads.feed.present && reads.feed.value.status == "OPEN"'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/integrationTest/java/org/yanoproject/x/devtools/BindingRecipesIT.java',
      anchors: ['"own-insert-only-slot", 0, "OBSERVATION_REJECTED", 0, Set.of(ACTOR_WORK_KEY)',
        '"feed-open-and-in-range", 1, "OBSERVATION_OUT_OF_RANGE", 0, Set.of()',
        'The range rule reads the feed record in the admission slot, before any work is reserved'] },
    { repo: 'yano-x', path: 'state-machines/stdlib/src/test/java/org/yanoproject/x/stdlib/TypedViewsRecipes.java',
      anchors: ['"status", new UnicodeString("OPEN"), "min", new UnsignedInteger(100)', '"max", new UnsignedInteger(200)'] },
    { repo: 'yano-x', path: 'examples/bindings/balances-transfer-limit.yaml',
      anchors: ['params: {maxAmount: 10000}', 'command.amount <= params.maxAmount', 'deny: TRANSFER_LIMIT_EXCEEDED'] },
    { repo: 'yano-x', path: 'tooling/studio/src/test/fixtures/scenarios/transfer-limit-failure-report.json',
      anchors: ['FIXTURE_ADMISSION_REJECTED', 'ADMISSION_RULE_DENIED/transfer-limit/TRANSFER_LIMIT_EXCEEDED'] },
    { repo: 'yano-x', path: 'tooling/studio/src/test/fixtures/scenarios/quorum-report-2.json',
      anchors: ['"ruleId" : "minimum-quorum"', '"denyCode" : "QUORUM_TOO_LOW"', '"failedStepOrdinal" : 1'] },
    { repo: 'yano', path: 'docs/appchain/submission.md',
      anchors: ['"details": {"rule": "transfer-limit", "deny": "TRANSFER_LIMIT_EXCEEDED"}'] },
  ],
};
