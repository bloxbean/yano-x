// A user writes to a protected topic through a delegated session key, and a
// member checks the write in TopicWriteRules.check order before any state
// machine sees it (ADR app-layer/039 D7, D8, D9, D11). The "What if"
// scenarios end at the exact check that refuses, with its TopicRefusal code.

const TOPIC = '`freight/acme/shipments`';

// TopicWriteRules.check order for a delegated write; first failure wins.
const CHECKS = [
  'An entry covers the topic (or unregistered topics are open)',
  'The entry is not retired',
  'The body is a canonical frame',
  'The write proof\'s `notAfter` is inside its window',
  'The credential: allowed issuer, scope, time, not revoked',
  'The session key signed this write',
  'This write statement was not used before',
  'Payload size and schema',
];
const checks = (failAt = -1, code) => CHECKS.map((label, i) => ({
  label,
  ok: failAt < 0 || i < failAt ? true : i === failAt ? false : null,
  ...(i === failAt && code ? { code } : {}),
}));

const POLICY = {
  caption: `Effective policy of ${TOPIC}`,
  columns: ['Field', 'Value', 'From entry'],
  rows: [
    ['`writers`', '1 of [login, warehouse]', '`freight/acme/`'],
    ['`delegate`', '[login]', '`freight/acme/`'],
    ['`maxDelegationMs`', '3,600,000 (1 h)', '`freight/acme/`'],
    ['`maxPayloadBytes`', '4,096', '`freight/` (inherited)'],
    ['schema', '`tempC: -30..30`', '`freight/acme/`'],
    ['owners', '2 of [ops, security]', '`freight/acme/`'],
  ],
};

const login = {
  title: 'Log in',
  text: 'A driver signs in to Acme\'s delivery app through the company\'s SSO; any login the application likes '
    + 'works, including OAuth or a wallet. The app makes a session key pair. The private key is a non-extractable '
    + 'WebCrypto key that never leaves the device.',
  wires: [{ from: 'browser', to: 'issuer', label: 'login + session public key' }],
  cards: { browser: { title: 'Session key', detail: 'made on the device' } },
};

const issue = {
  title: 'Issue a credential',
  text: 'Acme\'s login service checks the sign-in and signs a delegation: the session public key, the scope '
    + '`freight/acme/`, `notBefore` and `notAfter`, bound to this chain and its generation. Its key is a writer of '
    + 'the topic and is listed under `delegate`.',
  wires: [{ from: 'issuer', to: 'browser', label: 'credential, one hour' }],
  cards: {
    issuer: { title: 'Delegation signed', detail: 'scope `freight/acme/`', tone: 'ok' },
    browser: { title: 'Session ready', detail: 'key + credential', tone: 'ok' },
  },
};

const write = {
  title: 'Write',
  text: `At the customer's dock the app builds a frame for ${TOPIC}: the payload `
    + '`{shipment: "SHP-1042", tempC: 4}` and a proof with a fresh nonce, a `notAfter`, the credential and the '
    + 'session key\'s signature over the write statement. The statement binds the chain, generation, topic, payload '
    + 'hash, nonce and expiry. It posts the frame to any member.',
  wires: [{ from: 'browser', to: 'member', label: 'POST /messages, frame' }],
  cards: { member: { title: 'Received', detail: 'not checked yet' } },
};

const check = {
  title: 'Check the policy',
  text: 'The member resolves the topic\'s effective policy from committed state: the longest matching entry, with '
    + 'omitted fields inherited. It then runs the checks in order; the first failure refuses the message with '
    + '`400` and a code.',
  checks: checks(),
  state: POLICY,
  cards: { member: { title: 'Admitted', detail: '`202` + message id', tone: 'ok' } },
};

const machine = {
  title: 'Run the machine',
  text: 'Only now do the state machine\'s own admission and any composite rules run. The machine receives the '
    + 'verified payload as the body; composite rules see `writer.kind` = `delegated`, the session key and its issuer. '
    + 'The message id still refers to the submitted frame.',
  wires: [{ from: 'member', to: 'machine', label: 'verified payload' }],
  cards: { machine: { title: 'Admitted', detail: '`writer.kind` = delegated', tone: 'ok' } },
};

const finalize = {
  title: 'Finalize',
  text: 'The proposer and every follower repeat the same checks against the block\'s parent state. A follower '
    + 'rejects a block that breaks them, which makes the policy a consensus rule. The write statement is recorded as '
    + 'used, and the delivery scan is now evidence every company in the consortium can verify.',
  cards: {
    member: { title: 'Final', detail: 'same checks on every member', tone: 'final' },
    machine: { title: 'Applied', tone: 'final' },
  },
};

const refused = (code, detail) => ({ member: { title: `\`${code}\``, detail, tone: 'fail' } });

export default {
  id: 'topic-write-check',
  type: 'steps',
  title: 'A write on a protected topic',
  intro: `a driver's app records a delivery scan on ${TOPIC} with a session key. Step through it, then try a `
    + '“What if” to see which check stops a bad write.',
  lanes: [
    { id: 'browser', label: 'Driver\'s app', note: 'session key', kind: 'client' },
    { id: 'issuer', label: 'Login service', note: 'a writer in delegate', kind: 'actor' },
    { id: 'member', label: 'Any member', note: 'topic policy check', kind: 'member' },
    { id: 'machine', label: 'State machine', note: 'and composite rules', kind: 'core' },
  ],
  scenarios: [
    { id: 'session', label: 'A session-key write', steps: [login, issue, write, check, machine, finalize] },
    {
      id: 'plain',
      label: 'A legacy integration posts JSON',
      summary: 'An old script posts `{"shipment": "SHP-1042"}` as the body, with no frame and no signature.',
      steps: [{
        ...write,
        text: `The script posts the payload to ${TOPIC} as an ordinary body, the way it would on an unprotected topic.`,
        wires: [{ from: 'browser', to: 'member', label: 'POST /messages, plain body' }],
      }, {
        ...check,
        text: 'The topic has writer keys, so the body must be a canonical frame carrying a write proof. It is not.',
        checks: checks(2, 'TOPIC_FRAME_INVALID'),
        cards: refused('TOPIC_FRAME_INVALID', 'not a frame'),
      }],
    },
    {
      id: 'expired',
      label: 'The credential expired',
      summary: 'The app was offline for two hours and queued a scan without refreshing its one-hour credential.',
      steps: [login, issue, {
        ...write,
        text: 'Back online, the app signs the queued scan with the old credential. The SDK normally refreshes the '
          + 'credential before it expires; this client did not.',
      }, {
        ...check,
        text: 'The block time is past the credential\'s `notAfter`, so the credential no longer stands in for the '
          + 'login service. Get a fresh credential and sign the scan again.',
        checks: checks(4, 'TOPIC_DELEGATION_INVALID'),
        cards: refused('TOPIC_DELEGATION_INVALID', 'credential expired'),
      }],
    },
    {
      id: 'scope',
      label: 'A write for another company',
      summary: 'The same session tries to write `freight/globex/shipments`, a competitor\'s topic.',
      steps: [login, issue, {
        ...write,
        text: 'The app signs a write for `freight/globex/shipments` with the credential for `freight/acme/`.',
        wires: [{ from: 'browser', to: 'member', label: 'frame for Globex' }],
      }, {
        ...check,
        text: 'The credential\'s scope, `freight/acme/`, does not cover the topic. A session key can write only '
          + 'inside the scope it was issued for, so one company cannot write another\'s records.',
        checks: checks(4, 'TOPIC_DELEGATION_INVALID'),
        cards: refused('TOPIC_DELEGATION_INVALID', 'outside the scope'),
      }],
    },
    {
      id: 'replay',
      label: 'The scan is sent twice',
      summary: 'A retry on a flaky network, or a proxy, posts the same final frame again.',
      steps: [login, issue, write, check, machine, finalize, {
        title: 'Resend',
        text: 'Anyone who has the frame can post the same bytes again, to any member.',
        wires: [{ from: 'browser', to: 'member', label: 'the same frame again' }],
        cards: { member: { title: 'Received', detail: 'a copy' }, machine: null },
      }, {
        ...check,
        text: 'Every signature is still valid, but the write statement is already recorded as used. A copy never '
          + 'counts twice, so a retry cannot record a delivery twice.',
        checks: checks(6, 'TOPIC_PROOF_REPLAYED'),
        cards: refused('TOPIC_PROOF_REPLAYED', 'already used'),
      }],
    },
    {
      id: 'schema',
      label: 'The payload breaks the schema',
      summary: 'A faulty app build reports the temperature in Fahrenheit.',
      steps: [login, issue, {
        ...write,
        text: 'The app signs a correct frame whose payload is `{shipment: "SHP-1042", tempC: 39}`.',
      }, {
        ...check,
        text: 'The signatures and credential are fine, but the topic\'s schema allows `tempC` only from -30 to 30. '
          + 'Signatures prove who wrote a message, not that its content makes sense; that is the schema\'s job.',
        checks: checks(7, 'TOPIC_SCHEMA_REJECTED'),
        cards: refused('TOPIC_SCHEMA_REJECTED', 'tempC out of range'),
      }],
    },
    {
      id: 'unregistered',
      label: 'Nobody registered the topic',
      summary: 'A client writes `analytics/clicks`, which no entry covers, on a chain sealed with '
        + '`unregistered: reject`.',
      steps: [{
        ...write,
        text: 'The client posts a plain body to `analytics/clicks`.',
        wires: [{ from: 'browser', to: 'member', label: 'POST /messages' }],
      }, {
        ...check,
        text: 'No entry covers the topic and the chain refuses unregistered topics. With `unregistered: open`, which '
          + '`appchain new` writes by default, the message would be accepted as on a chain without topics.',
        checks: checks(0, 'TOPIC_UNREGISTERED'),
        state: undefined,
        cards: refused('TOPIC_UNREGISTERED', 'no entry covers it'),
      }],
    },
  ],
  legend: [['client', 'driver\'s app'], ['actor', 'application service'], ['member', 'member node'],
    ['core', 'state machine'], ['fail', 'refused']],
  sources: [
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/topics/TopicWriteRules.java',
      anchors: ['no registry entry covers', 'is retired', 'write proof notAfter outside its window',
        'delegation is not valid for this write', 'session key signature does not verify',
        'write statement already used', 'payload exceeds maxPayloadBytes', 'payload does not satisfy the topic schema',
        'TopicNames.covers(delegation.scope(), topic)', '!state.revoked(delegationHash)'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/topics/TopicRefusal.java',
      anchors: ['TOPIC_UNREGISTERED', 'TOPIC_FRAME_INVALID', 'TOPIC_DELEGATION_INVALID', 'TOPIC_PROOF_REPLAYED',
        'TOPIC_SCHEMA_REJECTED'] },
    { repo: 'yano', path: 'adr/app-layer/039-topic-registry-namespaces-and-writer-policies.md',
      anchors: ['An application authenticates its user by any means it likes (app login, wallet, OAuth)',
        'The follower-side rejection is what makes the policy a consensus rule'] },
    { repo: 'yano-x', path: 'docs/appchain/bindings/07-admission-rules.md',
      anchors: ['| Delegated write | `delegated` | The session public key | Issuer public key |'] },
    { repo: 'yano-x', path: 'docs/appchain/TOPIC_SESSION_KEYS.md',
      anchors: ['The SDK verifies returned credentials and refreshes before expiry.'] },
    { repo: 'yano-x', path: 'sdk/javascript/topics.mjs',
      anchors: ['it stores a non-extractable CryptoKey, never a seed.'] },
  ],
};
