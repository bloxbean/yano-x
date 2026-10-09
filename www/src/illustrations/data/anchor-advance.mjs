// One script-anchor advance, with metadata mode, a forged root, and an offline
// leader as alternatives. Facts follow Yano's anchor services, the on-chain
// AnchorValidator, and the anchor-v1 CDDL; see `sources`.

const MEMBER_CHECKS = [
  'The validator script matches the configured artifact',
  'The transaction spends my view of the anchor UTxO',
  'Exactly one continuing output keeps the thread token and the locked ADA',
  'The new inline datum keeps this ledger’s identity',
  'The anchored height is at or below my tip',
  'Its block hash and state root equal my own block',
  'Its member keys and threshold equal my current ones',
  'At least the threshold of members are required signers',
];

// In the order AnchorValidator.validate evaluates them.
const VALIDATOR_CHECKS = [
  'The input datum has the v1 shape: eleven fields, 32-byte hashes',
  'At least the threshold of the input datum’s member keys signed',
  'Exactly one continuing output carries the thread token',
  'The new datum is inline, has the v1 shape, and lists 1 to 32 sorted member keys',
  'The height increases',
  'Chain id, genesis id, application id, profile and fingerprint are unchanged',
  'The locked ADA is not reduced',
];

const trigger = {
  title: 'Trigger',
  text: 'The anchor leader is the node with `anchor.enabled`, node 0 on a local cluster. On its tick, if new '
    + 'blocks exist and `every-blocks` have accumulated since the last anchor, or `max-interval-minutes` have '
    + 'passed, it starts an advance. App ledger finality never waits for this.',
  cards: {
    leader: { title: 'Tip 42 · last anchor 40', detail: '`every-blocks: 2`', tone: 'leader' },
  },
};

const build = {
  title: 'Build',
  text: 'The leader builds a transaction that spends the thread UTxO and writes the next inline datum: chain '
    + 'and genesis identity, height 42, its block hash and state root, member keys, and threshold. It lists '
    + 'the members as required signers, adds its own witness, and sends the request on `~anchor/sign`.',
  wires: [
    { from: 'leader', to: 'b', label: 'sign request' },
    { from: 'leader', to: 'c', label: 'sign request' },
  ],
  cards: {
    leader: { title: 'Advance tx', detail: 'height 42 · root `7f3a…`', tone: 'leader' },
  },
};

const check = {
  title: 'Check',
  text: 'Each member checks the request against its own ledger and its own Cardano view. The leader gets no '
    + 'trust: a member signs only a root it computed itself.',
  checks: MEMBER_CHECKS.map((label) => ({ label, ok: true })),
  cards: {
    b: { title: 'Checked ✓', detail: 'my block 42 matches', tone: 'ok' },
    c: { title: 'Checked ✓', detail: 'my block 42 matches', tone: 'ok' },
  },
};

const cosign = {
  title: 'Co-sign',
  text: 'Each member signs the transaction body hash with its member key and returns the witness on '
    + '`~anchor/sig`. If some members stay silent for 30 seconds, the leader retries with the members that '
    + 'answered, provided they reach the threshold.',
  wires: [
    { from: 'b', to: 'leader', label: 'witness' },
    { from: 'c', to: 'leader', label: 'witness' },
  ],
  cards: {
    leader: { title: '3 of 3 witnesses', detail: 'threshold 2', tone: 'leader' },
  },
};

const submit = {
  title: 'Submit',
  text: 'The leader assembles the witnesses, checks the transaction still hashes to what the members signed, '
    + 'and submits it through the node’s own transaction path. The anchor wallet pays the fee and collateral.',
  wires: [{ from: 'leader', to: 'l1', label: 'advance tx', tone: 'cardano' }],
  cards: {
    l1: { title: 'In mempool', detail: 'spends the thread UTxO', tone: 'pending' },
  },
};

const validate = {
  title: 'Validate',
  text: 'The Plutus V3 validator runs on Cardano. It checks signatures, shape, height and identity. It does '
    + '**not** check that the state root is correct; the members did that before signing.',
  checks: VALIDATOR_CHECKS.map((label) => ({ label, ok: true })),
  cards: {
    l1: { title: 'Validator ✓', detail: 'thread UTxO advanced', tone: 'cardano' },
  },
};

const confirm = {
  title: 'Confirm',
  text: 'Every member reads the new thread UTxO from its own Cardano view and updates its anchored height and '
    + 'transaction, so `lagBlocks` drops. The `anchor/commitment` endpoint then reports the root as “L1-confirmed '
    + 'by this node”: a verifier should still check the Cardano output itself.',
  wires: [
    { from: 'l1', to: 'b', label: 'thread UTxO', tone: 'cardano' },
    { from: 'l1', to: 'c', label: 'thread UTxO', tone: 'cardano' },
  ],
  cards: {
    leader: { title: 'Anchored 42', detail: '`lagBlocks: 0`', tone: 'final' },
    b: { title: 'Anchored 42', detail: 'own L1 view', tone: 'final' },
    c: { title: 'Anchored 42', detail: 'own L1 view', tone: 'final' },
    l1: { title: 'Datum at 42', detail: 'root `7f3a…`', tone: 'cardano' },
  },
};

export default {
  id: 'anchor-advance',
  type: 'steps',
  title: 'Anchor a state root',
  intro: 'a three-member ledger with threshold 2, anchoring in script mode every 2 blocks. Step through one '
    + 'advance, then try metadata mode, a forged root, or an offline leader.',
  lanes: [
    { id: 'leader', label: 'Anchor leader', note: 'node 0 · pays fees', kind: 'leader' },
    { id: 'b', label: 'Member B', note: 'co-signer', kind: 'member' },
    { id: 'c', label: 'Member C', note: 'co-signer', kind: 'member' },
    { id: 'l1', label: 'Cardano', note: 'thread UTxO + validator', kind: 'cardano' },
  ],
  scenarios: [
    {
      id: 'script',
      label: 'Script mode',
      steps: [trigger, build, check, cosign, submit, validate, confirm],
    },
    {
      id: 'metadata',
      label: 'Metadata mode',
      summary: 'A plain transaction carries the commitment. Cardano enforces nothing about it.',
      steps: [trigger, {
        title: 'Build a metadata tx',
        text: 'The leader builds a plain transaction whose metadata, under label 7014 by default, holds '
          + '`[chain-id, from-height, to-height, block-hash, state-root]`. Only the anchor wallet signs it. '
          + 'There is no bootstrap, thread token, or co-signing.',
        cards: {
          leader: { title: 'Metadata tx', detail: 'blocks 41..42 · root `7f3a…`', tone: 'leader' },
        },
      }, {
        title: 'Submit and confirm',
        text: 'The leader submits it and watches its own L1 sync. An anchor not seen within 2 minutes is '
          + 'resubmitted, and an L1 rollback that removes it puts it back in pending. Cardano stores the data '
          + 'but checks nothing about it, so the trust statement is weaker than in script mode.',
        wires: [{ from: 'leader', to: 'l1', label: 'metadata tx', tone: 'cardano' }],
        cards: {
          leader: { title: 'Anchored 42', detail: 'leader signed alone', tone: 'final' },
          l1: { title: 'Metadata 7014', detail: 'not validated', tone: 'cardano' },
        },
      }],
    },
    {
      id: 'forged-root',
      label: 'The leader forges a root',
      summary: 'A compromised leader writes a state root no member computed.',
      steps: [trigger, {
        ...build,
        title: 'Build with a forged root',
        text: 'The leader writes a datum whose state root is `91c4…`, not the root of block 42, adds its own '
          + 'witness, and sends the request.',
        cards: {
          leader: { title: 'Advance tx', detail: 'height 42 · root `91c4…`', tone: 'fail' },
        },
      }, {
        title: 'Refuse',
        text: 'Members B and C compare the datum with their own block 42. The state root differs, so neither '
          + 'signs, and the remaining checks are not reached.',
        checks: MEMBER_CHECKS.map((label, i) => ({ label, ok: i < 5 ? true : i === 5 ? false : null })),
        cards: {
          b: { title: 'Refused', detail: 'root differs from my block', tone: 'fail' },
          c: { title: 'Refused', detail: 'root differs from my block', tone: 'fail' },
        },
      }, {
        title: 'Stuck below threshold',
        text: 'After 30 seconds the leader has 1 witness, its own, and the threshold is 2. The round stops and '
          + 'is retried on a later tick. If the leader submitted the transaction anyway, the validator would '
          + 'reject it: fewer than the threshold of member keys signed.',
        checks: VALIDATOR_CHECKS.map((label, i) => ({ label, ok: i < 1 ? true : i === 1 ? false : null })),
        cards: {
          leader: { title: '1 of 2 witnesses', detail: 'round timed out', tone: 'fail' },
          l1: { title: 'Unchanged', detail: 'datum still at 40', tone: 'cardano' },
        },
      }],
    },
    {
      id: 'leader-offline',
      label: 'The leader is offline',
      summary: 'Anchoring stops; app ledger finality does not.',
      steps: [{
        title: 'No anchors',
        text: 'Node 0 is down. Members B and C are 2 of 3, so they keep finalizing blocks: finality never waits '
          + 'for Cardano. But nobody builds an advance, and `lagBlocks` climbs on every member.',
        cards: {
          leader: { title: 'Offline', detail: 'no ticks', tone: 'fail' },
          b: { title: 'Tip 60', detail: '`lagBlocks: 20`', tone: 'pending' },
          c: { title: 'Tip 60', detail: '`lagBlocks: 20`', tone: 'pending' },
          l1: { title: 'Datum at 40', detail: 'unchanged', tone: 'cardano' },
        },
      }, {
        title: 'Move the leader',
        text: 'Recovery is an operator action: configure `anchor.*`, with the wallet key, on another member and '
          + 'restart it. The anchor identity lives on Cardano, so the new leader resumes from the current '
          + 'thread UTxO.',
        wires: [{ from: 'b', to: 'l1', label: 'advance tx', tone: 'cardano' }],
        cards: {
          b: { title: 'New leader', detail: 'anchors height 60', tone: 'leader' },
          l1: { title: 'Datum at 60', detail: 'same thread', tone: 'cardano' },
        },
      }],
    },
  ],
  legend: [
    ['leader', 'anchor leader'],
    ['member', 'co-signing member'],
    ['cardano', 'Cardano'],
    ['final', 'anchored'],
    ['fail', 'refused or stalled'],
  ],
  sources: [
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/ScriptAnchorService.java',
      anchors: ['boolean dueByCount = tip - lastAnchored >= anchorConfig.everyBlocks();',
        'anchorConfig.maxIntervalMinutes() * 60_000', 'COSIGN_ROUND_TIMEOUT_MS = 30_000',
        'static final String TOPIC_SIGN = "~anchor/sign";', 'static final String TOPIC_SIG = "~anchor/sig";',
        'Leader is a member too — its witness comes first',
        'Script-anchor: sign request validator does not match configured artifact — refused',
        'Script-anchor: sign request does not spend my anchor UTxO — refused',
        'Script-anchor: continuing output drops the thread token or value — refused',
        'Script-anchor: datum identity mismatch — refused',
        'Script-anchor: datum block-hash/state-root differ from MY block {} — refused',
        'Script-anchor: datum membership/threshold differ from my current epoch — refused',
        'Co-sign round timed out below threshold (', 'retrying with',
        'refuse to submit if the', 'status.put("walletAddress", walletAddress.getAddress());'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AnchorService.java',
      anchors: ['{@code [chain-id, from-height, to-height, block-hash, state-root]}',
        'RESUBMIT_AFTER_MS = 120_000', 'An L1 rollback of the anchor tx simply puts the',
        'first anchor: fire as soon as there is anything to anchor'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainSubsystem.java',
      anchors: ['anchorStatus.put("lagBlocks",'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/AppChainConfig.java',
      anchors: ['everyBlocks = 10;', 'maxIntervalMinutes = 60;', 'tx metadata label (default 7014, metadata mode)'] },
    { repo: 'yano',
      path: 'appchain/onchain/appchain-anchor-onchain/src/main/java/org/yanoproject/appchain/anchor/onchain/AnchorValidator.java',
      anchors: ['boolean oneContinuing = continuing.size() == 1;',
        'boolean monotonic = nextHeight.compareTo(current.height()) > 0;',
        'boolean sameGenesis = nextChainGenesisId.equals(current.chainGenesisId());',
        'signerCount.compareTo(threshold) >= 0', 'boolean valuePreserved',
        'Require the v1 datum\'s exact Constr(0, eleven fields) wire shape.',
        'all safety comes from the datum'] },
    { repo: 'yano', path: 'core-api/src/main/cddl/appchain/anchor-v1.cddl',
      anchors: ['chain-genesis-id : bstr .size 32', 'state-root   : bstr .size 32',
        'member-keys  : [ 1*32 bstr .size 32 ]'] },
    { repo: 'yano-x', path: 'docs/APP_CHAIN_USER_GUIDE.md',
      anchors: ['enable `anchor.*` (with the wallet key) on another', '`lagBlocks` climbs visibly'] },
  ],
};
