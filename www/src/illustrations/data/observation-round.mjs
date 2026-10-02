// One generic observation round in the shipment reference workflow: a stable
// Cardano payment opens a watch, members certify a signed Merkle receipt, and the
// result releases a payment. Facts follow ADR-037, Yano's observation guide, and
// ShipmentWorkflowReferenceStateMachine; see `sources`.

const REPORT_CHECKS = [
  'The root is signed by an attestor key authorized for this definition',
  'The attestation names this definition, round, and source',
  'The leaf binds this round’s parameters, the source id, and the value',
  'The path has at most 20 siblings and reaches the signed root',
];

const payment = {
  title: 'Payment lands',
  text: 'A buyer pays the order’s Cardano address. Each member’s `address-deposit` observer sees it, and once it '
    + 'is `l1.stability-depth` blocks deep it is sequenced into a block. Followers re-derive it from their own '
    + 'Cardano view. `apply()` receives it in `context.l1Observations()`.',
  wires: [{ from: 'l1', to: 'sm', label: 'stable deposit', tone: 'cardano' }],
  cards: {
    l1: { title: 'Payment tx', detail: 'to the order address', tone: 'cardano' },
    sm: { title: 'Payment recorded', detail: 'height h' },
  },
};

const watch = {
  title: 'Watch',
  text: 'The state machine calls `observations.watch(ObservationIntent.oneShot("shipment-delivery", …))`, with '
    + 'the payment transaction hash as parameters. The round is due at height h+1 and takes reports until h+3: '
    + 'logical heights, not seconds. The subscription commits with the block; no network call happens yet.',
  cards: {
    sm: { title: 'WAITING_SHIPMENT', detail: 'subscription committed' },
  },
};

const open = {
  title: 'Open a round',
  text: 'At height h+1 every member opens the same round. The round record pins the members, the report '
    + 'threshold, and the deadline. Workers start only after that record is committed.',
  cards: {
    a: { title: 'Round open', detail: 'reports until h+3' },
    b: { title: 'Round open', detail: 'reports until h+3' },
  },
};

const fetchEvidence = {
  title: 'Fetch',
  text: 'Outside block execution, each member’s `https-attested-merkle-v1` adapter fetches the attestor’s '
    + 'signed Merkle root and the leaf for this payment. A timeout or network error is retried locally, and '
    + 'nothing about it is signed.',
  wires: [
    { from: 'src', to: 'a', label: 'signed root + leaf' },
    { from: 'src', to: 'b', label: 'signed root + leaf' },
  ],
  cards: {
    src: { title: 'Root signed', detail: 'leaf `DELIVERED`' },
  },
};

const report = {
  title: 'Check and report',
  text: 'Each member checks the evidence with `ed25519-merkle-inclusion-v1`, saves a signing lock, and gossips '
    + 'a signed report on `~obs-diffusion/report/v1`. A report is a member’s statement about the evidence, not '
    + 'part of any block.',
  checks: REPORT_CHECKS.map((label) => ({ label, ok: true })),
  wires: [
    { from: 'a', to: 'b', label: 'report: `DELIVERED`' },
    { from: 'b', to: 'a', label: 'report: `DELIVERED`' },
  ],
  cards: {
    a: { title: 'Report signed', detail: '`DELIVERED`', tone: 'ok' },
    b: { title: 'Report signed', detail: '`DELIVERED`', tone: 'ok' },
  },
};

const certify = {
  title: 'Certify',
  text: 'A member holding exactly the round’s report threshold of reports, with the same value, source, and '
    + 'version, assembles a certificate and gossips it on `~obs-diffusion/certificate/v1`. Any member can do '
    + 'this; here the threshold is 2.',
  cards: {
    a: { title: 'Certificate', detail: '2 matching reports', tone: 'leader' },
  },
};

const include = {
  title: 'Include',
  text: 'The proposer adds the certified result to a block as a `~obs/result/v1` input. Followers verify the '
    + 'certificate before they vote PREPARE; an invalid certificate for an open round rejects the block.',
  wires: [{ from: 'a', to: 'b', label: 'block with `~obs/result/v1`' }],
  cards: {
    b: { title: 'Certificate ✓', detail: 'votes PREPARE', tone: 'ok' },
  },
};

const apply = {
  title: 'Apply',
  text: 'When the block is final, every member calls `onObservationResult` with status `VALUE` and value '
    + '`DELIVERED`. The state machine emits a `cardano.payment` effect with a `CHAIN` result to pay the '
    + 'merchant, and moves to `RELEASE_PENDING`.',
  wires: [{ from: 'a', to: 'sm', label: '`VALUE`: `DELIVERED`' }],
  cards: {
    a: { title: 'Final', detail: 'round closed', tone: 'final' },
    b: { title: 'Final', detail: 'round closed', tone: 'final' },
    sm: { title: 'RELEASE_PENDING', detail: 'effect `cardano.payment`', tone: 'final' },
  },
};

const settle = {
  title: 'Release and settle',
  text: 'The effect executor pays the merchant and reports the transaction hash, so the workflow waits for '
    + 'settlement. A second stable deposit to the merchant address, with exactly that hash, completes it. An '
    + 'executor receipt alone never does.',
  wires: [{ from: 'l1', to: 'sm', label: 'settlement deposit', tone: 'cardano' }],
  cards: {
    l1: { title: 'Release tx', detail: 'merchant paid', tone: 'cardano' },
    sm: { title: 'COMPLETE', detail: 'four authorities agree', tone: 'final' },
  },
};

const unresolved = (text, wireLabel) => ({
  title: 'Shipment unresolved',
  text,
  wires: [{ from: 'a', to: 'sm', label: wireLabel, tone: 'fail' }],
  cards: {
    sm: { title: 'SHIPMENT_UNRESOLVED', detail: 'no funds released', tone: 'fail' },
  },
});

export default {
  id: 'observation-round',
  type: 'steps',
  title: 'Certify an external fact',
  intro: 'the shipment reference workflow. Members A and B stand for the reporting members, and this round '
    + 'needs 2 matching reports. Try a missed deadline or a different value.',
  lanes: [
    { id: 'sm', label: 'State machine', note: 'on every member', kind: 'core' },
    { id: 'a', label: 'Member A', note: 'reporter', kind: 'member' },
    { id: 'b', label: 'Member B', note: 'reporter', kind: 'member' },
    { id: 'src', label: 'Shipment attestor', note: 'signs a Merkle root', kind: 'external' },
    { id: 'l1', label: 'Cardano', note: 'payments', kind: 'cardano' },
  ],
  scenarios: [
    {
      id: 'delivered',
      label: 'Delivered',
      steps: [payment, watch, open, fetchEvidence, report, certify, include, apply, settle],
    },
    {
      id: 'expired',
      label: 'No quorum by the deadline',
      summary: 'Missing evidence is never turned into an answer.',
      steps: [payment, watch, open, {
        ...fetchEvidence,
        text: 'Member A fetches valid evidence. Member B receives a path that does not reach the signed root.',
        wires: [
          { from: 'src', to: 'a', label: 'signed root + leaf' },
          { from: 'src', to: 'b', label: 'broken path', tone: 'fail' },
        ],
      }, {
        title: 'Reject before signing',
        text: 'Member B rejects the evidence before it signs anything. It does not report “not delivered”: bad '
          + 'or missing evidence is never converted into a domain answer. No second valid report arrives.',
        checks: REPORT_CHECKS.map((label, i) => ({ label, ok: i < 3 ? true : i === 3 ? false : null })),
        cards: {
          a: { title: 'Report signed', detail: '`DELIVERED`', tone: 'ok' },
          b: { title: 'No report', detail: 'evidence rejected', tone: 'fail' },
        },
      }, {
        title: 'Deadline passes',
        text: 'The round stays open until its report deadline. After that and the inclusion grace, every member '
          + 'derives `EXPIRED` from committed state, without a certificate.',
        cards: {
          a: { title: 'EXPIRED', detail: '1 of 2 reports', tone: 'fail' },
          b: { title: 'EXPIRED', detail: '1 of 2 reports', tone: 'fail' },
        },
      }, unresolved('`onObservationResult` receives status `EXPIRED`. The workflow stops at `SHIPMENT_UNRESOLVED`, '
        + 'and no payment effect is emitted.', '`EXPIRED`')],
    },
    {
      id: 'other-value',
      label: 'The attestor reports another value',
      summary: 'A certified value is still only what the attestor committed.',
      steps: [payment, watch, open, {
        ...fetchEvidence,
        cards: { src: { title: 'Root signed', detail: 'leaf is not `DELIVERED`' } },
      }, {
        ...report,
        text: 'The evidence is valid, so both members report the value the attestor committed, which is not '
          + '`DELIVERED`.',
        wires: [
          { from: 'a', to: 'b', label: 'report: other value' },
          { from: 'b', to: 'a', label: 'report: other value' },
        ],
        cards: {
          a: { title: 'Report signed', detail: 'other value', tone: 'ok' },
          b: { title: 'Report signed', detail: 'other value', tone: 'ok' },
        },
      }, certify, include, unresolved('The result is `VALUE`, but the value is not `DELIVERED`. The state machine '
        + 'moves to `SHIPMENT_UNRESOLVED` and releases nothing.', '`VALUE`: other')],
    },
  ],
  legend: [
    ['core', 'deterministic, on every member'],
    ['member', 'reporting member'],
    ['external', 'external source'],
    ['cardano', 'Cardano'],
    ['final', 'final'],
    ['fail', 'rejected or unresolved'],
  ],
  sources: [
    { repo: 'yano-x',
      path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/ShipmentWorkflowReferenceStateMachine.java',
      anchors: ['for (var sequenced : context.l1Observations())',
        'observations.watch(ObservationIntent.oneShot(DEFINITION_ID, ID,',
        'Math.addExact(height, 1), Math.addExact(height, 3), Math.addExact(height, 3)',
        'public static final String DEFINITION_ID = "shipment-delivery";',
        'private static final String RELEASE_TYPE = "cardano.payment";',
        'result.status() != ObservationResultStatus.VALUE || !Arrays.equals(result.value(), DELIVERED)',
        'setPhase(writer, Phase.SHIPMENT_UNRESOLVED);', 'setPhase(writer, Phase.RELEASE_PENDING);',
        'setPhase(writer, Phase.COMPLETE);', '.result(ResultPolicy.CHAIN)'] },
    { repo: 'yano-x', path: 'docs/appchain/shipment-observation-reference.md',
      anchors: ['**not seconds**', 'An executor receipt alone never completes the workflow.',
        'An expired/non-`DELIVERED` observation never releases funds.'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/observation/ObservationTopics.java',
      anchors: ['"~obs-diffusion/report/v1"', '"~obs-diffusion/certificate/v1"', '"~obs/result/v1"'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/observation/ObservationResultStatus.java',
      anchors: ['VALUE(0)', 'EXPIRED(2)'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/observation/ExactValueQuorumPolicy.java',
      anchors: ['requiring exactly r reports for one identical value'] },
    { repo: 'yano', path: 'adr/app-layer/037-certified-generic-observation-framework.md',
      anchors: ['Local workers may begin only after seeing that record committed.',
        'Any validator can collect reports and assemble a certificate.',
        'followers verify the certificate before signing', 'Retry locally; Phase 1 signs no failure report',
        'Reject locally; never sign or convert it to domain `false`',
        'the kernel derives `EXPIRED` from committed round state without a certificate'] },
    { repo: 'yano', path: 'docs/appchain/observations.md',
      anchors: ['`ed25519-merkle-inclusion-v1`', '`https-attested-merkle-v1`',
        'leaf-to-root list of at most 20 sibling hashes', 'A malformed proof is rejected before local signing'] },
    { repo: 'yano-x', path: 'docs/APP_CHAIN_USER_GUIDE.md',
      anchors: ['only after it is `l1.stability-depth` blocks deep'] },
  ],
};
