// One `webhook.post` effect from emission to an incorporated outcome, with the
// result policy, receiver answers, retries, expiry, and a crash after the POST.
// Facts follow Yano's effect kernel, runtime, and webhook executor; see `sources`.

const emit = {
  title: 'Emit',
  text: 'Inside `apply()`, the state machine calls `effects.emit(...)` with type `webhook.post`, a scope, '
    + '`ResultPolicy.CHAIN`, and an expiry of 1,000 blocks. Nothing is sent. The emitter returns '
    + '`EffectId(chain, height, ordinal)`, the effect’s position in history: the ordinal counts emissions in '
    + 'the block. The scope is not part of the identity.',
  viewText: { receiver: 'Nothing reaches you yet. The effect is only a record in a block that is not final.' },
  cards: {
    sm: { title: 'emit `webhook.post`', detail: 'id `orders-chain/120/0`' },
  },
};

const finalize = {
  title: 'Finalize',
  text: 'The block becomes final like any other. Every member stores the same effect record, and the block’s '
    + 'effects root, written under `~fx/root/120`, commits it in the state root. The effect is provable from '
    + 'now on. What a transition emits is consensus data.',
  viewText: { receiver: 'Still nothing. The authorization is now final and provable.' },
  wires: [{ from: 'sm', to: 'ledger', label: 'effect record' }],
  cards: {
    ledger: { title: 'Final at 120', detail: '`~fx/root/120`', tone: 'final' },
  },
};

const pickUp = {
  title: 'Pick up',
  text: 'The effect runtime is off by default; you enable it on one node. It reads finalized records, checks '
    + 'the gate (`app-final` is eligible at once), and never dispatches an effect within 2 blocks of its expiry. '
    + 'Its progress, such as PENDING, RETRY, DONE, or PARKED, is node-local and never part of a root.',
  viewText: { receiver: 'The executor node has the effect queued for you.' },
  wires: [{ from: 'ledger', to: 'rt', label: 'finalized record' }],
  cards: {
    rt: { title: 'PENDING', detail: 'node-local status' },
  },
};

const deliver = {
  title: 'Deliver',
  text: 'The `webhook.post` executor POSTs the payload to the URL in its own configuration; a URL inside the '
    + 'payload is refused unless `allow-payload-url` is set. The `Idempotency-Key` header is the hex '
    + 'Blake2b-256 hash of the effect id, the same on every attempt. `X-Effect-Id` carries `orders-chain/120/0`.',
  viewText: { receiver: 'You receive a POST. Store the `Idempotency-Key` with the work you do.' },
  wires: [{ from: 'rt', to: 'rx', label: 'POST + `Idempotency-Key`' }],
  cards: {
    rx: { title: 'POST received', detail: 'key `9c1e…`' },
  },
};

const answer = {
  title: 'Answer',
  text: 'The receiver answers **2xx**. The executor records the effect as done and confirmed, with the '
    + '`Location` header, or `HTTP 200`, as the external reference. A **4xx** means FAILED with no retry; a '
    + '**5xx** or a network error is retried.',
  viewText: { receiver: 'Answer 2xx only after you have durably done the work.' },
  wires: [{ from: 'rx', to: 'rt', label: '200 + `Location`' }],
  cards: {
    rx: { title: 'Done once', detail: 'key stored', tone: 'ok' },
    rt: { title: 'DONE', detail: 'CONFIRMED · `Location`', tone: 'ok' },
  },
};

const report = {
  title: 'Report',
  text: 'For a `CHAIN` effect, the executor node signs a `~fx/result` message with its member key and submits '
    + 'it like any other message. It repeats this every 60 seconds until the ledger closes the effect. '
    + '`effects.result.signers` can limit which members may report.',
  viewText: { receiver: 'Your answer is on its way back into the ledger.' },
  wires: [{ from: 'rt', to: 'ledger', label: '`~fx/result` CONFIRMED' }],
  cards: {
    ledger: { title: 'Result queued', detail: 'sequenced like any message' },
  },
};

const incorporate = {
  title: 'Incorporate',
  text: 'In the block that contains it, the framework accepts the first valid result before the state machine '
    + 'runs, records the outcome, and calls `onEffectResult` on every member. A duplicate, late, or unknown '
    + 'result is a no-op, so a result can never stall the ledger. A result is a member’s attestation, not '
    + 'proof of what the receiver did.',
  viewText: { receiver: 'The ledger now records CONFIRMED and the reference you returned.' },
  wires: [{ from: 'ledger', to: 'sm', label: '`onEffectResult` CONFIRMED' }],
  cards: {
    ledger: { title: 'Closed', detail: 'first result wins', tone: 'final' },
    sm: { title: '`onEffectResult`', detail: 'CONFIRMED · ref stored', tone: 'final' },
  },
};

export default {
  id: 'effect-lifecycle',
  type: 'steps',
  title: 'Life of an effect',
  intro: 'an order approval that calls a webhook, with the default `app-final` gate and a `CHAIN` result. '
    + 'Try the “What if” scenarios, or switch to your receiver’s view.',
  lanes: [
    { id: 'sm', label: 'State machine', note: 'on every member', kind: 'core' },
    { id: 'ledger', label: 'App ledger', note: 'members finalize', kind: 'member' },
    { id: 'rt', label: 'Effect runtime', note: 'one executor node', kind: 'runtime' },
    { id: 'rx', label: 'Receiver', note: 'your HTTP endpoint', kind: 'external' },
  ],
  views: [
    { id: 'default', label: 'Whole flow' },
    { id: 'receiver', label: 'Your receiver', focus: ['rx'] },
  ],
  scenarios: [
    {
      id: 'chain-2xx',
      label: 'CHAIN result, 2xx',
      steps: [emit, finalize, pickUp, deliver, answer, report, incorporate],
    },
    {
      id: 'none',
      label: 'Result policy NONE',
      summary: 'Without `.result(ResultPolicy.CHAIN)`, the policy is the default, NONE.',
      steps: [{
        ...emit,
        title: 'Emit without a result',
        text: 'The intent does not call `.result(...)`, so its policy is the default `ResultPolicy.NONE`. A NONE '
          + 'effect cannot take an expiry, and its outcome never returns to the ledger.',
      }, finalize, pickUp, deliver, {
        title: 'Done locally',
        text: 'The receiver answers 2xx and the executor marks the effect done. That is the end: no `~fx/result`, '
          + 'no `onEffectResult`, no expiry. Only this node’s REST status and metrics show the outcome. A NONE '
          + 'effect that fails for good is PARKED for an operator.',
        viewText: { receiver: 'You answered 2xx. The ledger never learns the result.' },
        wires: [{ from: 'rx', to: 'rt', label: '200' }],
        cards: {
          rx: { title: 'Done once', detail: 'key stored', tone: 'ok' },
          rt: { title: 'DONE', detail: 'operator view only', tone: 'ok' },
        },
      }],
    },
    {
      id: 'rejected',
      label: 'Receiver answers 4xx',
      summary: 'A definitive “no” from the target is recorded as FAILED.',
      steps: [emit, finalize, pickUp, deliver, {
        title: 'Rejected',
        text: 'The receiver answers **400**. Retrying the same bytes cannot succeed, so the executor does not '
          + 'retry. For a `CHAIN` effect it records FAILED with the reason `HTTP 400`.',
        viewText: { receiver: 'You rejected the request. You will not get it again.' },
        wires: [{ from: 'rx', to: 'rt', label: '400 Bad Request', tone: 'fail' }],
        cards: {
          rx: { title: 'Rejected', detail: 'nothing done', tone: 'fail' },
          rt: { title: 'DONE', detail: 'FAILED · `HTTP 400`', tone: 'fail' },
        },
      }, {
        ...report,
        wires: [{ from: 'rt', to: 'ledger', label: '`~fx/result` FAILED', tone: 'fail' }],
      }, {
        title: 'Incorporate FAILED',
        text: 'Every member calls `onEffectResult` with outcome FAILED: the target answered no. The state '
          + 'machine decides what that means for the order.',
        viewText: { receiver: 'The ledger records that you said no.' },
        wires: [{ from: 'ledger', to: 'sm', label: '`onEffectResult` FAILED', tone: 'fail' }],
        cards: {
          ledger: { title: 'Closed', detail: 'FAILED', tone: 'final' },
          sm: { title: '`onEffectResult`', detail: 'FAILED', tone: 'fail' },
        },
      }],
    },
    {
      id: 'unavailable',
      label: 'Receiver answers 5xx',
      summary: 'Retries are local and bounded; the expiry closes the effect deterministically.',
      steps: [emit, finalize, pickUp, deliver, {
        title: 'Retry',
        text: 'The receiver answers **503**. The executor retries after 2 seconds, doubling the wait up to '
          + '5 minutes. After 8 attempts the effect is PARKED, and an operator can requeue or cancel it. It is '
          + 'never dispatched within 2 blocks of its expiry.',
        viewText: { receiver: 'You keep receiving the same POST, with the same key.' },
        wires: [{ from: 'rx', to: 'rt', label: '503', tone: 'fail' }],
        cards: {
          rx: { title: 'Unavailable', detail: 'same key each time', tone: 'pending' },
          rt: { title: 'RETRY → PARKED', detail: 'node-local', tone: 'pending' },
        },
      }, {
        title: 'Expire',
        text: 'No result arrives before height 1120, the emission height plus the expiry. At that height every '
          + 'member closes the effect as EXPIRED, without any message, and calls `onEffectResult`. EXPIRED means '
          + '“nobody answered in time”, which is different from FAILED.',
        viewText: { receiver: 'The ledger stopped waiting for you.' },
        wires: [{ from: 'ledger', to: 'sm', label: '`onEffectResult` EXPIRED', tone: 'fail' }],
        cards: {
          ledger: { title: 'Closed at 1120', detail: 'EXPIRED', tone: 'final' },
          sm: { title: '`onEffectResult`', detail: 'EXPIRED', tone: 'fail' },
        },
      }],
    },
    {
      id: 'crash',
      label: 'Executor crashes after the POST',
      summary: 'Execution is at least once, so the receiver must deduplicate.',
      steps: [emit, finalize, pickUp, deliver, {
        title: 'Crash',
        text: 'The receiver does the work and answers 200, but the executor node stops before it records the '
          + 'outcome. Its local status still says the effect needs to run.',
        viewText: { receiver: 'You did the work and answered 200. The executor never heard it.' },
        wires: [{ from: 'rx', to: 'rt', label: '200 (lost)', tone: 'fail' }],
        cards: {
          rx: { title: 'Done once', detail: 'key stored', tone: 'ok' },
          rt: { title: 'Stopped', detail: 'outcome not recorded', tone: 'fail' },
        },
      }, {
        title: 'Deliver again',
        text: 'After the restart, the executor POSTs the same payload again with the same `Idempotency-Key`. '
          + 'The receiver finds the key, does nothing new, and answers 200 again.',
        viewText: { receiver: 'The same key arrives twice. Answer 200 without doing the work again.' },
        wires: [
          { from: 'rt', to: 'rx', label: 'POST, same key' },
          { from: 'rx', to: 'rt', label: '200 (duplicate)' },
        ],
        cards: {
          rx: { title: 'Duplicate', detail: 'work not repeated', tone: 'ok' },
          rt: { title: 'DONE', detail: 'CONFIRMED', tone: 'ok' },
        },
      }, report, incorporate],
    },
  ],
  legend: [
    ['core', 'deterministic, on every member'],
    ['member', 'app ledger members'],
    ['runtime', 'node-local execution'],
    ['external', 'your system'],
    ['final', 'final'],
    ['fail', 'failed or refused'],
  ],
  sources: [
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/effects/EffectId.java',
      anchors: ['public record EffectId(String chainId, long height, int ordinal)', '"yano-fx-v1"',
        'zero-based emission ordinal within the block'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/effects/EffectIntent.java',
      anchors: ['private ResultPolicy result = ResultPolicy.NONE;',
        'expiryBlocks applies only to ResultPolicy.CHAIN effects'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/effects/ResultPolicy.java',
      anchors: ['NONE(0)', 'CHAIN(1)', 'zero chain footprint'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/effects/EffectOutcome.java',
      anchors: ['CONFIRMED(1)', 'FAILED(2)', 'EXPIRED(4)'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/effects/FxKeys.java',
      anchors: ['"~fx/root/"'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/effects/FxResultBody.java',
      anchors: ['public static final String TOPIC = "~fx/result";'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/FxKernel.java',
      anchors: ['return null; // first result won', 'EffectOutcome.EXPIRED',
        'machine.onEffectResult(context, result, machineWriter, emitter, observations);',
        'a result message can NEVER stall the chain'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/EffectRuntime.java',
      anchors: ['EXPIRY_SAFETY_BLOCKS = 2', '"effects.executor.max-attempts", 8',
        '"effects.executor.backoff-initial-ms", 2000', '"effects.executor.backoff-max-ms", 300_000',
        'settings.backoffInitialMs() << Math.min(20, before.attempts())',
        'the at-least-once window'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/FxStatusRecord.java',
      anchors: ['static final int PENDING = 0;', 'static final int RETRY = 2;', 'static final int DONE = 4;',
        'static final int PARKED = 5;', 'Never replicated, never in any root'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/WebhookEffectExecutor.java',
      anchors: ['.header("Idempotency-Key", HexUtil.encodeHexString(effect.idHash()))',
        '.header("X-Effect-Id", effect.effectId().canonical())', 'firstValue("Location")',
        'return EffectExecution.failed("HTTP " + code, false);', 'allow-payload-url=false'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainSubsystem.java',
      anchors: ['currentFx.pendingInjections(32, 60_000)'] },
    { repo: 'yano-x', path: 'docs/APP_CHAIN_USER_GUIDE.md',
      anchors: ['yano.app-chain.effects.result.signers', 'The Effect Runtime is **off by default**'] },
  ],
};
