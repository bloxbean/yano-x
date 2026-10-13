// `feed-aggregation-v1` stepped through with the demo feed's policy and the
// vectors of AggregationTest: one disposition per source, quorum, the lower
// median, the outlier tolerance, quorum again, and the aggregate. Values are
// integers at scale 2, so -1825 is -18.25 degC.

const POLICY = 'Demo feed `coldstore-7`: scale 2, quorum 2 sources, tolerance 20,000 ppm or 50, '
  + 'bounds −4000 to 1000.';

// The disposition rules, in the order Aggregation.dispose applies them.
const RULES = [
  'Not revoked (else `REVOKED`)',
  'Present at the closing height (else `ABSENT`)',
  'Written by the source itself (else `FOREIGN_WRITER`)',
  'Revision 1, never rewritten (else `EQUIVOCATED`)',
  'Observed inside the round window (else `WRONG_ROUND`)',
  'Inside the value bounds (else `OUT_OF_RANGE`)',
];
const allPass = RULES.map((label) => ({ label, ok: true }));

const COLUMNS = ['Source', 'Value', 'Disposition'];

const read = {
  title: 'Read the observations',
  text: 'Every verifier reads each configured source\'s observation as the chain held it at the closing height, '
    + 'in the feed\'s source order, and applies the disposition rules below, in order, to each one. Here all three '
    + 'pass and become candidates. ' + POLICY,
  checks: allPass,
  state: {
    caption: 'Observations at the closing height',
    columns: COLUMNS,
    rows: [['source-alpha', '−1825', 'candidate'], ['source-beta', '−1810', 'candidate'],
      ['source-gamma', '−900', 'candidate']],
  },
  cards: {
    alpha: { title: '−1825', detail: '−18.25 °C · candidate' },
    beta: { title: '−1810', detail: '−18.10 °C · candidate' },
    gamma: { title: '−900', detail: '−9.00 °C · candidate' },
  },
  focus: ['alpha', 'beta', 'gamma'],
};

const quorum = {
  title: 'Check the quorum',
  text: 'Three candidates meet the feed\'s quorum of two sources, so the round can continue. With fewer '
    + 'candidates than the quorum the round is `NO_QUORUM` at once.',
  checks: [{ label: 'Candidates ≥ quorum: 3 ≥ 2', ok: true }],
  wires: [{ from: 'beta', to: 'calc', label: '3 candidates' }],
  cards: { calc: { title: 'Quorum ✓', detail: '3 of 3 sources', tone: 'ok' } },
};

const reference = {
  title: 'Find the reference',
  text: 'Sort the candidates by value, then by source id, and take the **lower median**: the value at index '
    + '⌊(n − 1) / 2⌋. For three candidates that is index 1, so the reference is −1810. Nothing is averaged.',
  state: {
    caption: 'Candidates sorted by value, then source id',
    columns: ['Index', 'Source', 'Value'],
    rows: [['0', 'source-alpha', '−1825'], ['1', 'source-beta', '−1810'], ['2', 'source-gamma', '−900']],
    highlight: [1],
  },
  cards: { calc: { title: 'Reference −1810', detail: 'lower median, index 1' } },
  focus: ['calc'],
};

const outliers = {
  title: 'Drop outliers',
  text: 'The permitted deviation is the larger of the absolute tolerance and the relative one: '
    + 'max(50, ⌊1810 × 20,000 / 1,000,000⌋) = max(50, 36) = **50**. A candidate farther than that from the '
    + 'reference is an `OUTLIER`. source-gamma is 910 away.',
  checks: [
    { label: 'source-alpha: |−1825 − (−1810)| = 15 ≤ 50', ok: true },
    { label: 'source-beta: |−1810 − (−1810)| = 0 ≤ 50', ok: true },
    { label: 'source-gamma: |−900 − (−1810)| = 910 ≤ 50', ok: false, code: 'OUTLIER' },
  ],
  state: {
    caption: 'Deviation from the reference −1810, permitted 50',
    columns: ['Source', 'Value', 'Deviation', 'Disposition'],
    rows: [['source-alpha', '−1825', '15', 'candidate'], ['source-beta', '−1810', '0', 'candidate'],
      ['source-gamma', '−900', '910', 'OUTLIER']],
    highlight: [2],
  },
  cards: {
    gamma: { title: 'OUTLIER', detail: '910 from the reference', tone: 'fail' },
    calc: { title: 'Tolerance 50', detail: 'max(50, 36)' },
  },
};

const quorumAgain = {
  title: 'Check the quorum again',
  text: 'Two candidates remain, which still meets the quorum of two. Removing outliers can lose the quorum, '
    + 'so it is checked before and after.',
  checks: [{ label: 'Retained ≥ quorum: 2 ≥ 2', ok: true }],
  cards: { calc: { title: 'Quorum ✓', detail: '2 retained', tone: 'ok' } },
  focus: ['calc'],
};

const aggregate = {
  title: 'Take the aggregate',
  text: 'The aggregate is the lower median of what remains: for (−1825, −1810) that is index ⌊(2 − 1) / 2⌋ = 0, '
    + 'so **−1825**, or −18.25 °C. The round is `CLOSED` with source-alpha and source-beta accepted. Every client '
    + 'computes with exact integers, so every verifier gets the same answer.',
  state: {
    caption: 'Result',
    columns: ['Status', 'Aggregate', 'Accepted sources'],
    rows: [['CLOSED', '−1825 (−18.25 °C)', 'source-alpha, source-beta']],
  },
  wires: [{ from: 'calc', to: 'record', label: 'CLOSED · −1825' }],
  cards: {
    alpha: { title: 'ACCEPTED', detail: '−1825', tone: 'ok' },
    beta: { title: 'ACCEPTED', detail: '−1810', tone: 'ok' },
    calc: { title: 'Aggregate −1825', detail: 'lower median of 2', tone: 'ok' },
  },
};

const record = {
  title: 'Record it once',
  text: 'A feed operator proposes this result computed at the closing height. Two publishers from distinct '
    + 'organizations each recompute it and approve; the map applies the record once. Anyone holding the round '
    + 'bundle recomputes the round and compares it with the record.',
  checks: [
    { label: 'Two publisher approvals from distinct organizations', ok: true },
    { label: 'Recomputed result equals the record', ok: true },
  ],
  cards: { record: { title: 'Round record', detail: 'CLOSED · −1825 · AGREES', tone: 'final' } },
};

const noQuorumEnd = (detail) => ({
  title: 'No quorum',
  text: 'Fewer candidates than the quorum remain, so the round is recorded as `NO_QUORUM` with no aggregate. '
    + 'The bundle still lists each source with its disposition.',
  checks: [{ label: detail, ok: false, code: 'NO_QUORUM' }],
  wires: [{ from: 'calc', to: 'record', label: 'NO_QUORUM', tone: 'fail' }],
  cards: {
    calc: { title: 'No quorum', detail, tone: 'fail' },
    record: { title: 'Round record', detail: 'NO_QUORUM', tone: 'fail' },
  },
});

export default {
  id: 'feed-aggregation-sim',
  type: 'steps',
  title: 'Aggregating one round',
  intro: 'the demo cold-store feed with three sources, using the test vectors of the aggregation rule. '
    + 'Values are integers at scale 2. Try a “What if” to see each way a round loses sources.',
  lanes: [
    { id: 'alpha', label: 'source-alpha', note: 'coldchain-alpha', kind: 'actor' },
    { id: 'beta', label: 'source-beta', note: 'coldchain-beta', kind: 'actor' },
    { id: 'gamma', label: 'source-gamma', note: 'coldchain-gamma', kind: 'actor' },
    { id: 'calc', label: 'Recompute', note: 'every verifier', kind: 'client' },
    { id: 'record', label: 'Round record', note: 'on the ledger', kind: 'ledger' },
  ],
  scenarios: [
    {
      id: 'outlier',
      label: 'One outlier',
      steps: [read, quorum, reference, outliers, quorumAgain, aggregate, record],
    },
    {
      id: 'quorum-after',
      label: 'Quorum lost after outliers',
      summary: 'The same readings with a quorum of three sources.',
      steps: [read, {
        ...quorum,
        text: 'Three candidates meet a quorum of three.',
        checks: [{ label: 'Candidates ≥ quorum: 3 ≥ 3', ok: true }],
      }, reference, outliers, noQuorumEnd('Retained ≥ quorum: 2 ≥ 3')],
    },
    {
      id: 'bad-writers',
      label: 'Wrong writer, rewrite, missing',
      summary: 'Another actor wrote source-alpha\'s key, source-beta rewrote its reading, and source-gamma never reported.',
      steps: [{
        title: 'Read the observations',
        text: 'source-alpha\'s key was written by source-beta, so it is `FOREIGN_WRITER`. source-beta\'s entry is '
          + 'at revision 2, so it is `EQUIVOCATED`. source-gamma has no observation: `ABSENT`. The ledger cannot stop '
          + 'these writes, but it records the writer and the revision, and the rule excludes them.',
        checks: [
          { label: 'source-alpha: written by the source itself', ok: false, code: 'FOREIGN_WRITER' },
          { label: 'source-beta: revision 1, never rewritten', ok: false, code: 'EQUIVOCATED' },
          { label: 'source-gamma: present at the closing height', ok: false, code: 'ABSENT' },
        ],
        state: {
          caption: 'Observations at the closing height',
          columns: COLUMNS,
          rows: [['source-alpha', '−1825', 'FOREIGN_WRITER'], ['source-beta', '−1825', 'EQUIVOCATED'],
            ['source-gamma', '—', 'ABSENT']],
        },
        cards: {
          alpha: { title: 'FOREIGN_WRITER', detail: 'written by source-beta', tone: 'fail' },
          beta: { title: 'EQUIVOCATED', detail: 'revision 2', tone: 'fail' },
          gamma: { title: 'ABSENT', detail: 'no observation', tone: 'fail' },
        },
        focus: ['alpha', 'beta', 'gamma'],
      }, noQuorumEnd('Candidates ≥ quorum: 0 ≥ 2')],
    },
    {
      id: 'late-range-revoked',
      label: 'Late, out of range, revoked',
      summary: 'One reading is outside the round window, one is below the feed\'s bounds, and one was revoked.',
      steps: [{
        title: 'Read the observations',
        text: 'source-alpha\'s signed time is one second after the round ended: `WRONG_ROUND`. source-beta reports '
          + '−4001, below the minimum of −4000: `OUT_OF_RANGE`. source-gamma\'s entry is a tombstone: `REVOKED`. '
          + 'Round boundaries are inclusive, so a reading at the last second of the round would count.',
        checks: [
          { label: 'source-alpha: observed inside the round window', ok: false, code: 'WRONG_ROUND' },
          { label: 'source-beta: inside the value bounds', ok: false, code: 'OUT_OF_RANGE' },
          { label: 'source-gamma: not revoked', ok: false, code: 'REVOKED' },
        ],
        state: {
          caption: 'Observations at the closing height',
          columns: COLUMNS,
          rows: [['source-alpha', '−1825', 'WRONG_ROUND'], ['source-beta', '−4001', 'OUT_OF_RANGE'],
            ['source-gamma', '—', 'REVOKED']],
        },
        cards: {
          alpha: { title: 'WRONG_ROUND', detail: 'after the round ended', tone: 'fail' },
          beta: { title: 'OUT_OF_RANGE', detail: '−4001 < −4000', tone: 'fail' },
          gamma: { title: 'REVOKED', detail: 'tombstone', tone: 'fail' },
        },
        focus: ['alpha', 'beta', 'gamma'],
      }, noQuorumEnd('Candidates ≥ quorum: 0 ≥ 2')],
    },
    {
      id: 'same-org',
      label: 'Approvals from one organization',
      summary: 'Two publishers from the same organization approve the close.',
      steps: [read, quorum, reference, outliers, quorumAgain, aggregate, {
        title: 'Apply is refused',
        text: 'publisher-a and publisher-c both belong to `feed-ops`. The round-close policy needs two publishers '
          + 'from distinct organizations, so applying the record is refused and nothing is written.',
        checks: [{ label: 'Two publisher approvals from distinct organizations', ok: false, code: 'APPROVAL_NOT_APPROVED' }],
        wires: [{ from: 'calc', to: 'record', label: 'apply refused', tone: 'fail' }],
        cards: { record: { title: 'No record yet', detail: 'APPROVAL_NOT_APPROVED', tone: 'fail' } },
      }, {
        title: 'A second organization approves',
        text: 'publisher-b, from `audit-guild`, recomputes the round and approves. Now the approvals come from two '
          + 'organizations, and the record is applied once.',
        checks: [{ label: 'Two publisher approvals from distinct organizations', ok: true }],
        wires: [{ from: 'calc', to: 'record', label: 'CLOSED · −1825' }],
        cards: { record: { title: 'Round record', detail: 'CLOSED · −1825', tone: 'final' } },
      }],
    },
  ],
  legend: [
    ['actor', 'source (its own actor key)'],
    ['client', 'any verifier, recomputing'],
    ['ledger', 'round record'],
    ['fail', 'excluded or refused'],
  ],
  sources: [
    { repo: 'yano-x', path: 'products/attestation-feed/profile/src/main/java/org/yanoproject/x/feed/profile/Aggregation.java',
      anchors: ['ACCEPTED, OUTLIER, ABSENT, REVOKED, FOREIGN_WRITER, EQUIVOCATED, WRONG_ROUND, OUT_OF_RANGE',
        'lowerMedian(candidates)', 'if (retained.size() < feed.minimumSources())',
        '{@code max(maximumDeviationAbsolute, floor(|m| × maximumDeviationPpm / 1,000,000))}',
        'The value at index {@code floor((n − 1) / 2)} of a list sorted by (value, sourceId)',
        'Disposition.REVOKED', 'Disposition.ABSENT', 'Disposition.FOREIGN_WRITER', 'Disposition.EQUIVOCATED',
        'Disposition.WRONG_ROUND', 'Disposition.OUT_OF_RANGE'] },
    { repo: 'yano-x', path: 'products/attestation-feed/profile/src/test/java/org/yanoproject/x/feed/profile/AggregationTest.java',
      anchors: ['max(50, floor(1810 * 20000 / 1e6) = 36) = 50', '-900 deviates 910 → OUTLIER',
        'assertThat(result.aggregate()).isEqualTo(-1_825);', 'quorumLostAfterOutliersIsNoQuorum',
        'observation(-4_001, IN_ROUND)', 'the round boundaries are inclusive'] },
    { repo: 'yano-x', path: 'products/attestation-feed/profile/src/main/java/org/yanoproject/x/feed/profile/FeedGenesis.java',
      anchors: ['DEMO_SOURCE_IDS, 2, 20_000, 50, -4_000, 1_000'] },
    { repo: 'yano-x', path: 'products/attestation-feed/profile/src/main/java/org/yanoproject/x/feed/profile/FeedStarterProfile.java',
      anchors: ['A round close: a feed operator proposes, two publishers from distinct organizations approve.'] },
    { repo: 'yano-x', path: 'products/attestation-feed/harness/feed.sh',
      anchors: ['publisher-a and publisher-c (the same organization) approve; apply is refused'] },
    { repo: 'yano-x', path: 'state-machines/stdlib-contracts/src/main/java/org/yanoproject/x/stdlib/contracts/AuthenticatedMapContract.java',
      anchors: ['ERROR_APPROVAL_NOT_APPROVED = 20'] },
  ],
};
