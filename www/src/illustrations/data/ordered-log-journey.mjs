// One event through the built-in ordered-log machine, ending at its
// finalized-message record and typed proof. Facts follow Yano's
// OrderedLogStateMachine, FinalizedMessageIndex and the submission path.

const RECORD = '`[1, height, index, topic, sender]`';

const submit = {
  title: 'Submit',
  text: 'Your application posts a topic and a body to one member. The member checks framework limits, signs the '
    + 'envelope with its member key, pools it and answers **202** with a message id.',
  command: './yano.sh appchain cluster submit orders-chain order-created \'{"orderId":"A-1001"}\' --node 1',
  wires: [{ from: 'app', to: 'member', label: 'POST `{topic, body}`' }],
  cards: { member: { title: '202', detail: 'message id, not final yet' } },
};

const certify = {
  title: 'Order and certify',
  text: 'The leader puts the message in a block, every member re-executes it, and a threshold of members certifies '
    + 'the block. Only then is the message final.',
  wires: [{ from: 'member', to: 'log', label: 'final block h' }],
  cards: { member: null, log: { title: 'Block h', detail: 'final' } },
};

const record = {
  title: 'Record the position',
  text: '`ordered-log` writes one record per message, keyed by a hash of a reserved namespace and the message id, '
    + 'and updates a tip record. It never decodes the body and never rejects a message for business reasons.',
  state: {
    caption: 'ordered-log state',
    columns: ['Key', 'Value'],
    rows: [
      ['`sha256("~yano/finalized-message/v1/" ‖ id)`', RECORD],
      ['`sha256("~yano/finalized-message/v1/" ‖ "tip")`', '`height`'],
    ],
    highlight: [0, 1],
  },
  cards: { log: { title: 'Record written', detail: 'height, index, topic, sender', tone: 'final' } },
};

const find = {
  title: 'Find the message',
  text: 'A lookup by id returns **404** until the message is final, then its height, index, topic, sender and body.',
  command: 'curl -sf http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/messages/$MESSAGE_ID',
  wires: [{ from: 'log', to: 'app', label: 'height, index, bodyHex' }],
  cards: { app: { title: 'Final at h', detail: 'position known', tone: 'final' } },
};

const prove = {
  title: 'Prove it',
  text: 'Ask for the typed proof subject `finalized-message-v1` with the message id and the claim `recorded`. The '
    + 'answer proves the record under the returned state root. Check that root against evidence you trust.',
  wires: [{ from: 'log', to: 'verifier', label: 'proof: `PRESENT`' }],
  cards: { verifier: { title: '`recorded` holds', detail: 'height, index, topic, sender', tone: 'ok' } },
};

export default {
  id: 'ordered-log-journey',
  type: 'steps',
  title: 'An event through ordered-log',
  intro: 'one order event on the stock `orders-chain`, from the HTTP call to a proof of where it was finalized. The '
    + 'full consensus round is in “Life of a message”.',
  lanes: [
    { id: 'app', label: 'Your application', kind: 'client' },
    { id: 'member', label: 'Receiving member', note: 'node 1', kind: 'member' },
    { id: 'log', label: 'ordered-log', note: 'on every member', kind: 'core' },
    { id: 'verifier', label: 'Verifier', note: 'reads proofs', kind: 'client' },
  ],
  scenarios: [
    { id: 'event', label: 'One event', steps: [submit, certify, record, find, prove] },
    {
      id: 'twice',
      label: 'The same payload twice',
      summary: 'Identical bytes submitted twice are two messages.',
      steps: [submit, {
        title: 'Submit again',
        text: 'Each envelope carries its own sender sequence and expiry, so the second submission gets a different '
          + 'message id and, once final, its own record. Deduplicating business events is your application’s job.',
        wires: [{ from: 'app', to: 'member', label: 'same `{topic, body}`' }],
        state: {
          caption: 'ordered-log state',
          columns: ['Key', 'Value'],
          rows: [['record for id 1', RECORD], ['record for id 2', RECORD]],
          highlight: [1],
        },
        cards: { member: { title: '202', detail: 'a second message id' } },
      }],
    },
    {
      id: 'reserved',
      label: 'A reserved topic',
      summary: 'Topics that start with `~` belong to the framework.',
      steps: [{
        title: 'Submit',
        text: 'Your application posts to topic `~orders`.',
        wires: [{ from: 'app', to: 'member', label: 'POST `{topic: "~orders"}`' }],
      }, {
        title: 'Refused at ingress',
        text: 'The member refuses the topic before signing, with **400**. This is a framework rule; `ordered-log` '
          + 'itself has no admission rules.',
        wires: [{ from: 'member', to: 'app', label: '400', tone: 'fail' }],
        checks: [
          { label: 'Body is not empty and within `max-message-bytes`', ok: true },
          { label: 'Topic does not start with `~`', ok: false, code: '400' },
        ],
        cards: { member: { title: 'Refused', detail: 'reserved topic', tone: 'fail' } },
      }],
    },
  ],
  legend: [
    ['client', 'your application or verifier'],
    ['member', 'member node'],
    ['core', 'deterministic state machine'],
    ['final', 'final'],
    ['fail', 'refused'],
  ],
  sources: [
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/OrderedLogStateMachine.java',
      anchors: ['ID = "ordered-log"', 'FinalizedMessageIndex.plan(context, FinalizedMessageIndex.Config.allMessages())'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/transition/FinalizedMessageIndex.java',
      anchors: ['LOGICAL_NAMESPACE = "~yano/finalized-message/v1/"', 'TIP_KEY = key("tip".getBytes(StandardCharsets.US_ASCII));',
        'value.add(new UnsignedInteger(SCHEMA_VERSION));', 'value.add(new UnicodeString(record.topic()));',
        'MessageDigest.getInstance("SHA-256")'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/transition/FinalizedMessageProofSubjectProvider.java',
      anchors: ['SUBJECT_ID = "finalized-message-v1"', '"message-id"', '"recorded"'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainSubsystem.java',
      anchors: ['"Topics starting with \'~\' are reserved for the framework"', 'throw new IllegalArgumentException("body must not be empty");'] },
    { repo: 'yano', path: 'app/src/main/java/org/yanoproject/app/api/appchain/AppChainResource.java',
      anchors: ['@Path("proof-subjects/{subjectId}/proof")', '"No finalized message with id "'] },
    { repo: 'yano', path: 'docs/appchain/state-machines/ordered-log.md',
      anchors: ['Submitting identical payload bytes twice normally creates two messages.'] },
  ],
};
