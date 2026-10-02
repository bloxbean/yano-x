// The consensus plane (identical on every member) and the execution plane
// (outside consensus) of an app ledger, and the only two ways they connect.
// Facts follow Yano X's effects documentation and Yano's state machine SPI.

export default {
  id: 'two-planes',
  type: 'diagram',
  title: 'The two planes',
  tag: 'Concept',
  hint: 'Select a block to see what it does.',
  caption: 'The state machine records what is authorized; it never performs it. An effect record crosses to the '
    + 'execution plane only after its block is final, and the outcome crosses back only as a new message.',
  zones: [
    {
      id: 'consensus', kind: 'ledger', label: 'Consensus plane · identical on every member',
      contains: ['messages', 'apply', 'state', 'record', 'root'],
    },
    {
      id: 'execution', kind: 'runtime', label: 'Execution plane · outside consensus',
      contains: ['result', 'gate', 'run'],
    },
  ],
  blocks: [
    {
      id: 'messages', label: 'Messages', sub: 'in a final block', kind: 'core',
      detail: 'The ordered messages of a block. Every member runs the same block through the same state machine.',
    },
    {
      id: 'apply', label: 'State machine', sub: 'apply()', kind: 'core',
      detail: 'Deterministic code that reads message bodies, writes state, and emits effect records. It never calls '
        + 'the network, reads a clock, or touches files.',
      link: { label: 'Determinism rules', href: '/concepts/determinism-rules/' },
    },
    {
      id: 'state', label: 'Application state', kind: 'core',
      detail: 'The application’s records, written through `AppStateWriter`. Every write is an entry in the '
        + 'authenticated state.',
    },
    {
      id: 'record', label: 'Effect record', sub: 'authorized, not run', kind: 'core',
      detail: 'An immutable description of an external action, emitted through `AppEffectEmitter`. It is committed '
        + 'to the state root through an `effectsRoot`, so an authorized action is provable before it runs.',
    },
    {
      id: 'root', label: 'State root', sub: 'covers both', kind: 'final',
      detail: 'One root commits to the application state and the effect records. Members sign it, and proofs and '
        + 'anchors refer to it.',
    },
    {
      id: 'gate', label: 'Finality gate', sub: 'block is final', kind: 'runtime',
      detail: 'An effect becomes eligible only after its block is final and its gate is satisfied. The default '
        + 'gate is `app-final`.',
    },
    {
      id: 'run', label: 'Executor', sub: 'runs the action', kind: 'runtime',
      detail: 'An executor outside consensus performs the action: a webhook, Kafka, object storage, IPFS, or a '
        + 'Cardano payment. It may run more than once, so executors and receivers must be idempotent.',
      link: { label: 'Effects', href: '/concepts/effects/' },
    },
    {
      id: 'external', label: 'External system', kind: 'external',
      detail: 'The system that receives the action. It is not part of the ledger and cannot change state directly.',
    },
    {
      id: 'result', label: 'Result message', sub: '~fx/result, signed', kind: 'runtime',
      detail: 'The outcome returns as an ordinary member-signed message on `~fx/result`. It is ordered and finalized '
        + 'like any other message, and `apply()` records it exactly once.',
    },
  ],
  edges: [
    { from: 'messages', to: 'apply' },
    { from: 'apply', to: 'state' },
    { from: 'apply', to: 'record' },
    { from: 'state', to: 'root' },
    { from: 'record', to: 'root' },
    { from: 'record', to: 'gate', label: 'after finality' },
    { from: 'gate', to: 'run' },
    { from: 'run', to: 'external', label: 'at least once' },
    { from: 'run', to: 'result', label: 'outcome' },
    { from: 'result', to: 'messages', label: 'in a later block' },
  ],
  legend: [
    ['core', 'deterministic, on every member'],
    ['runtime', 'node-local execution'],
    ['external', 'outside the ledger'],
  ],
  layouts: {
    wide: {
      width: 760,
      height: 420,
      zones: {
        consensus: [16, 16, 728, 170],
        execution: [16, 230, 728, 110],
      },
      blocks: {
        messages: [32, 64, 140, 60],
        apply: [204, 64, 140, 60],
        state: [372, 46, 160, 44],
        record: [372, 108, 160, 54],
        root: [568, 64, 160, 60],
        result: [32, 268, 140, 56],
        gate: [372, 268, 160, 56],
        run: [568, 268, 160, 56],
        external: [568, 364, 160, 44],
      },
      edges: {
        'record->gate': { fromSide: 'b', toSide: 't', labelAt: [452, 208] },
        'run->external': { fromSide: 'b', toSide: 't', fromAt: [690, 324], toAt: [690, 364], labelAt: [690, 352] },
        'run->result': { fromSide: 'b', toSide: 'b', fromAt: [600, 324], toAt: [102, 324],
          via: [[600, 352], [102, 352]], labelAt: [350, 352] },
        'result->messages': { fromSide: 't', toSide: 'b', labelAt: [102, 208] },
      },
    },
    narrow: {
      width: 380,
      height: 556,
      zones: {
        consensus: [8, 8, 364, 256],
        execution: [8, 300, 364, 180],
      },
      blocks: {
        messages: [20, 40, 160, 56],
        apply: [200, 40, 160, 56],
        state: [20, 116, 160, 48],
        record: [200, 116, 160, 56],
        root: [110, 192, 160, 56],
        gate: [200, 336, 160, 56],
        run: [200, 412, 160, 56],
        result: [20, 412, 160, 56],
        external: [200, 500, 160, 44],
      },
      edges: {
        'apply->state': { fromSide: 'b', toSide: 't' },
        'apply->record': { fromSide: 'b', toSide: 't' },
        'state->root': { fromSide: 'b', toSide: 't', toAt: [170, 192] },
        'record->root': { fromSide: 'b', toSide: 't', fromAt: [260, 172], toAt: [230, 192] },
        'record->gate': { fromSide: 'b', toSide: 't', fromAt: [300, 172], toAt: [300, 336], labelAt: [300, 284] },
        'gate->run': { fromSide: 'b', toSide: 't', fromAt: [300, 392], toAt: [300, 412] },
        'run->external': { fromSide: 'b', toSide: 't', fromAt: [300, 468], toAt: [300, 500], label: false },
        'run->result': { fromSide: 'l', toSide: 'r', label: false },
        'result->messages': { fromSide: 'l', toSide: 'l', fromAt: [20, 440], toAt: [20, 68],
          via: [[13, 440], [13, 68]], label: false },
      },
    },
  },
  sources: [
    { repo: 'yano-x', path: 'docs/site/concepts-effects.md',
      anchors: ['A state machine never performs the action. It emits a record describing it.',
        'committed transitively through a count-bound `effectsRoot`', '~fx/result (member-signed, sequenced)',
        '**exactly-once incorporation, at-least-once execution**', 'must be idempotent',
        'yano.app-chain.effects.default-gate: app-final'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['`void apply(AppBlockExecutionContext, AppStateWriter, AppEffectEmitter)`',
        'effects only through the `AppEffectEmitter`', 'no wall clock', 'no I/O'] },
  ],
};
