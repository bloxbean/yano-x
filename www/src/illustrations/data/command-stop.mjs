// "Where did my command stop?" A diagnosis tree from the HTTP answer to the
// receipt code. Ingress codes come from EventBindingWorkflow.validate; HTTP
// outcomes from Yano's submission contract; receipt-code categories from the
// authoring language table the CLI writes into every --report file.

const result = (title, text, recorded, retry, links = []) => ({
  result: { title, text, facts: [['Recorded in', recorded], ['Will a retry help?', retry]], links },
});
const REFERENCE = { label: 'Rejection codes', href: '/reference/declarative-bindings/#why-did-a-binding-not-fire' };
const OPERATIONS = { label: 'Operations and upgrades', href: '/bindings/operations-and-upgrades/' };
const RULES = { label: 'Admission rules', href: '/bindings/admission-rules/' };

export default {
  id: 'command-stop',
  type: 'chooser',
  title: 'Where did my command stop?',
  tag: 'Decision aid',
  intro: 'start from what your submission returned, then follow the receipt. Each answer says where the outcome is '
    + 'recorded and whether sending a new message can help.',
  start: 'response',
  nodes: {
    response: {
      question: 'What did the submission return?',
      options: [
        { label: 'HTTP **400** with a `code`', next: 'ingress' },
        { label: 'HTTP **429**', next: 'pool-full' },
        { label: 'HTTP **503**', next: 'unavailable' },
        { label: 'HTTP **202** and a message id', next: 'receipt' },
      ],
    },
    ingress: {
      question: 'Which code did the 400 carry?',
      help: 'A 400 means the ingress member refused the message before pooling it. There is no receipt.',
      options: [
        { label: '`UNKNOWN_BINDING_SOURCE`', next: 'unknown-source' },
        { label: '`MALFORMED_SOURCE_COMMAND`', next: 'malformed' },
        { label: '`COMMAND_PAYLOAD_TOO_LARGE` or `COMMAND_WORK_EXCEEDED`', next: 'too-big' },
        { label: '`ADMISSION_RULE_*` or `EXPRESSION_CAPACITY_EXCEEDED`', next: 'static-rule' },
        { label: 'Another code', next: 'kernel-admission' },
      ],
    },
    receipt: {
      question: 'What does the query `composite/binding-receipt-v1/<message-id>` return?',
      help: 'Query with the source message id and empty parameters. A 202 only means one member queued the message.',
      options: [
        { label: 'An empty payload', next: 'no-receipt' },
        { label: 'A receipt with status `ACCEPTED`', next: 'accepted' },
        { label: 'A receipt with status `REJECTED`', next: 'rejected' },
      ],
    },
    accepted: {
      question: 'Did the follow-up you expected happen?',
      options: [
        { label: 'Yes. I want to confirm it.', next: 'committed' },
        { label: 'No. Its binding shows a `failedClause` from 0 to 7.', next: 'skipped' },
        { label: 'No. Its binding is not in any step’s `conditions`.', next: 'not-considered' },
        { label: 'No, and the source command changed nothing either.', next: 'no-change' },
      ],
    },
    rejected: {
      question: 'Which kind of code is the receipt’s `code`?',
      help: 'Read `failedStepOrdinal` first: it names the step that failed. Nothing in a rejected cascade committed.',
      options: [
        { label: 'An admission rule: `ADMISSION_RULE_*`', next: 'rule' },
        { label: 'The target refused: `ADMISSION`, `MALFORMED_DERIVED_COMMAND` or the machine’s own code', next: 'target' },
        { label: 'An evaluation error: `EXPRESSION_*`, `MAPPING_*`, `FUNCTION_*`, `LOOKUP_KEY_*`, `EVENT_TYPE_ERROR` or '
          + '`INVALID_UNICODE`', next: 'evaluation' },
        { label: 'A limit or budget: `LIMIT_*`, `*_CAPACITY_EXCEEDED`, `*_WORK_EXCEEDED`, `*_TOO_LARGE`, `EFFECT_*`',
          next: 'resource' },
        { label: 'A conflict: `REPLAY_OR_CONFLICT` or `CONSUMPTION_CONFLICT`', next: 'conflict' },
        { label: 'A plugin contract violation: `RESERVED_*`, `STATE_KEY_LIMIT`, `EVENT_MISSING_FIELD`, '
          + '`UNDECLARED_WORK_REFERENCE`', next: 'contract' },
      ],
    },
    'pool-full': result('The pending pool is full',
      'The ingress member’s pending pool is full, so the message was not kept or relayed.',
      'Nothing: the message was not accepted.', 'Yes, after the backpressure clears.'),
    unavailable: result('Admission is unavailable',
      'The application’s admission check could not run, the chain is stopped or paused, or this node is not yet an '
        + 'active member (a joiner whose membership epoch is scheduled). This is not a business outcome.',
      'The node’s logs and health, and `memberActiveForNextBlock` in its status.',
      'Once the node is healthy again, or once it is an active member.'),
    'unknown-source': result('The topic is not a component’s topic',
      'Every component listens on its own ingress topic, `<id>.command.v1` unless the document sets `topic`. '
        + 'A message on any other topic is refused.',
      'Nothing: the message was not pooled.', 'Yes, on the right topic.'),
    malformed: result('The body did not decode',
      'The component’s codec could not decode the body. Encode commands with the machine’s public codec, such as '
        + '`KvRegistryContract.put`, not with arbitrary JSON.',
      'Nothing: the message was not pooled.', 'Yes, once the encoding is fixed.',
      [{ label: 'Java integration', href: '/bindings/java-integration/' }]),
    'too-big': result('The command cannot fit',
      'Either a subscribed baseline event would exceed the event limit, or the command’s mandatory work exceeds '
        + 'the per-cascade allowance. Both limits are committed in the profile.',
      'Nothing: the message was not pooled.',
      'Only with a smaller command, or after a qualified profile change.', [REFERENCE]),
    'static-rule': result('A static admission rule refused it',
      'A rule that reads only the command, parameters, configuration and write content is checked at ingress too. '
        + 'The 400 body’s `details` names the `rule`, its `deny` code and, for a batch, the deciding `write`. '
        + '`EXPRESSION_CAPACITY_EXCEEDED` means evaluating those rules ran out of work.',
      'The HTTP response only.', 'Only with a command the rule allows.', [RULES]),
    'kernel-admission': result('The machine refused it at admission',
      'The target machine’s own stateless admission check refused the command. Look the code up in that '
        + 'machine’s reference.',
      'Nothing: the message was not pooled.', 'Yes, once the command is corrected.',
      [{ label: 'State machines', href: '/state-machines/' }]),
    'no-receipt': result('No receipt yet',
      'An empty payload means no receipt at the height the node answered from. The message may still be pending. '
        + 'It may also have expired, or been lost when its only holder restarted. Absence is not a rejection.',
      'Nothing yet.',
      'Not blindly. Poll with backoff first. A resubmission is a new message with a new id.', [OPERATIONS]),
    committed: result('Every step committed',
      'In an accepted receipt, steps that read `PLANNED` committed. Prove the receipt with the key from '
        + '`bindings receipt-key`. External effects are delivered later and separately.',
      'The receipt and the component state.', 'Not needed.', [OPERATIONS]),
    skipped: result('A false condition skipped the binding',
      'The clause at that index was false, so the binding derived nothing. That is not an error: the rest of the '
        + 'cascade still committed.',
      '`[bindingId, failedClause]` in the producing step’s `conditions`.',
      'Only if the data changes so the condition holds.'),
    'not-considered': result('No event selected the binding',
      'A binding runs only when its source component emits its event. Check the component and the event id, not '
        + 'just the topic. Bindings that were never considered are not listed.',
      'Its absence from every step’s `conditions`.', 'Not until the source emits that event.'),
    'no-change': result('Accepted, but nothing changed',
      'Accepted means the cascade committed, not that state changed. A machine can approve a no-op, such as a '
        + 'second vote from the same member. Read the component’s state.',
      'The component state, not the receipt.', 'Only with a command that changes something.'),
    rule: result('An admission rule refused a step',
      '`DENIED`: a clause was false. `ERROR`: a clause or read could not be evaluated, for example an unguarded '
        + 'absent read. `INPUT`: a kernel value broke its declaration. The whole cascade rolled back.',
      '`rules` of the failed step: `[heldCount, [ruleId, failedClause, denyCode, writeIndex]]`.',
      'Only once the input satisfies the rule.', [RULES]),
    target: result('The target refused the command',
      'The target machine’s codec, admission or decision refused the command. For a derived command, check what '
        + 'the mapping built.',
      'The failed step’s `code`.', 'Yes, once the input or the mapping is corrected.', [REFERENCE]),
    evaluation: result('A condition or mapping could not be evaluated',
      'Unlike a false condition, an evaluation error rejects the cascade. For a condition, the binding’s '
        + '`failedClause` points at the clause.',
      'The failed step’s `code`, and its `conditions`.',
      'Not with the same data and document.', [REFERENCE]),
    resource: result('A limit or budget ran out',
      'Cascade limits such as `LIMIT_DEPTH` and `LIMIT_FANOUT` fail the same way every time. Block budgets such as '
        + '`CAPACITY_EXCEEDED` and `CRYPTO_WORK_EXCEEDED` reset each block. `EXPRESSION_CAPACITY_EXCEEDED` can be '
        + 'either. Work already spent is not refunded.',
      'The receipt’s `code`.', 'For a block budget, a new message in a later block can succeed.', [REFERENCE]),
    conflict: result('A replay or consumption conflict',
      '`REPLAY_OR_CONFLICT`: the source id could not be claimed. `CONSUMPTION_CONFLICT`: two steps of one cascade '
        + 'used the same one-use key.',
      'The receipt’s `code`.', 'Replaying the same id returns this receipt. A new message is a new attempt.'),
    contract: result('A kernel broke its plugin contract',
      'A kernel wrote a reserved key or event, exceeded the state-key bound, used undeclared work, or emitted an '
        + 'event without a required field.',
      'The failed step’s `code`.', 'No. Report it to the plugin’s maintainer.', [REFERENCE]),
  },
  sources: [
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/bindings/EventBindingWorkflow.java',
      anchors: ['AdmissionResult.reject("UNKNOWN_BINDING_SOURCE")', 'AdmissionResult.reject("COMMAND_WORK_EXCEEDED")',
        'AdmissionResult.reject("MALFORMED_SOURCE_COMMAND")', 'program.rules().advisory', 'work.replayed++'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/BindingAuthoringLanguage.java',
      anchors: ['code(codes, "ADMISSION", "target-rejection"', 'code(codes, "MALFORMED_DERIVED_COMMAND", "target-rejection"',
        'code(codes, "LIMIT_DEPTH", "resource-exhaustion"', 'code(codes, "CRYPTO_WORK_EXCEEDED", "resource-exhaustion"',
        'code(codes, "MAPPING_MISSING_FIELD", "evaluation-error"', 'code(codes, "REPLAY_OR_CONFLICT", "replay-or-conflict"',
        'code(codes, "CONSUMPTION_CONFLICT", "replay-or-conflict"', 'code(codes, "EVENT_MISSING_FIELD", "contract-violation"',
        'code(codes, "RESERVED_EVENT_ID", "contract-violation"', 'code(codes, "ADMISSION_RULE_ERROR", "admission-rule"',
        'code(codes, "INVALID_UNICODE", "evaluation-error"', 'code(codes, "EVENT_TYPE_ERROR", "evaluation-error"',
        'code(codes, "EVENT_PAYLOAD_TOO_LARGE", "resource-exhaustion"',
        'code(codes, "UNDECLARED_WORK_REFERENCE", "contract-violation"'] },
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/bindings/BindingRules.java',
      anchors: ['details.put("write", (long) run.failure.writeIndex())',
        'if (rejected.code().equals(CAPACITY)) return AdmissionResult.reject(CAPACITY);'] },
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/ApprovalsTransitions.java',
      anchors: ['A duplicate/unknown/terminal command produces an empty plan'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/transition/TransitionWorkAccounting.java',
      anchors: ['successful reservation at a newer height starts a fresh count'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['In-memory (lost on restart, by design): the pending pool', 'not expired'] },
    { repo: 'yano', path: 'docs/appchain/submission.md',
      anchors: ['**429**', 'The local pending pool is full; the message was not relayed',
        'Application admission is unavailable, the chain is stopped/paused, or this node is not a member at the next height'] },
    { repo: 'yano-x', path: 'docs/appchain/DECLARATIVE_BINDINGS_CLI.md',
      anchors: ['Component ingress topics default to `<id>.command.v1`'] },
  ],
};
