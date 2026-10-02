// `yano-attest verify`, check by check: C1 digest through C6 trail head, then
// the trust level and exit code each trust input reaches. Check order and
// failure messages follow AttestVerifier; exit codes follow AttestCli.

const VERDICTS = {
  caption: 'Trust input → trust level → exit code',
  columns: ['Trust input', 'Trust level', 'Exit'],
  rows: [
    ['none', '`INTERNAL_CONSISTENCY_ONLY`', '6 (not accepted)'],
    ['`--members keys.json`', '`CALLER_PINNED_ROOT`', '5'],
    ['`--anchor-datum-hex`', '`INDEPENDENTLY_VERIFIED_L1_ANCHOR`', '0'],
  ],
};

const digest = {
  title: 'Digest',
  text: 'C1. The verifier hashes your copy of the file with SHA-256 and compares it with the digest in the '
    + 'certificate. Without the file this check is skipped, and the certificate is still checked.',
  wires: [{ from: 'you', to: 'verify', label: 'certificate + file' }],
  checks: [{ label: 'SHA-256 of the file equals the attested digest', ok: true, code: 'MATCH' }],
  cards: {
    you: { title: 'contract.pdf', detail: '+ contract.pdf.attest.json' },
    verify: { title: 'C1 Digest', detail: 'MATCH', tone: 'ok' },
  },
};

const binding = {
  title: 'Command binding',
  text: 'C2. The certificate carries the complete signed envelope. The verifier recomputes the message id, checks '
    + 'the member\'s Ed25519 signature, and decodes the body as a `doc-trail` append carrying exactly this '
    + 'digest, series id, and reference. The signer is a member key, not the person behind the file.',
  checks: [
    { label: 'Envelope equals the message in the evidence block', ok: true },
    { label: 'Message id recomputes from the signed body', ok: true },
    { label: 'Signature verifies under the sender\'s member key', ok: true },
    { label: 'Body is a canonical `doc-trail` append', ok: true },
    { label: 'Append carries the certificate\'s digest, series, and reference', ok: true },
  ],
  cards: { verify: { title: 'C2 Binding', detail: 'BOUND', tone: 'ok' } },
  focus: ['verify'],
};

const inclusion = {
  title: 'Inclusion',
  text: 'C3. The message proof shows that this message id sits at a fixed position under the block\'s '
    + 'messages root.',
  checks: [{ label: 'Message proof binds the message to the evidence block', ok: true, code: 'INCLUDED' }],
  cards: { verify: { title: 'C3 Inclusion', detail: 'INCLUDED', tone: 'ok' } },
  focus: ['verify'],
};

const finality = {
  title: 'Finality',
  text: 'C4. The verifier checks the block\'s finality certificate against the member keys and threshold in '
    + 'your members file. Get that file from the deployment record or the operators, never from the '
    + 'certificate itself.',
  wires: [{ from: 'trust', to: 'verify', label: '`--members keys.json`' }],
  checks: [{ label: 'Certificate signatures meet the pinned threshold', ok: true, code: 'VALID' }],
  cards: {
    trust: { title: 'keys.json', detail: 'members + threshold' },
    verify: { title: 'C4 Finality', detail: 'VALID · pinned members', tone: 'ok' },
  },
};

const anchorNone = {
  title: 'Anchor linkage',
  text: 'C5. You supplied no anchor datum, so the anchor is not checked independently. This does not fail '
    + 'the certificate; it limits how far the verdict can go.',
  checks: [{ label: 'Anchor datum binds the evidence (not supplied)', ok: null, code: 'NONE' }],
  cards: { verify: { title: 'C5 Anchor', detail: 'not checked' } },
  focus: ['verify'],
};

const trailHead = {
  title: 'Trail head',
  text: 'C6. A state proof pinned to the message\'s block shows the series\' revision count and head digest at '
    + 'that height, verified against the certified state root. If the node had already pruned that proof, '
    + 'the certificate carries none and remains valid without C6.',
  checks: [{ label: 'Head proof verifies against the certified state root', ok: true, code: 'VERIFIED' }],
  cards: { verify: { title: 'C6 Trail head', detail: 'revision 1 · VERIFIED', tone: 'ok' } },
  focus: ['verify'],
};

const verdict = (row, title, detail, exit, tone = 'final') => ({
  title: 'Verdict',
  text: `Every check passed, so the result is accepted at **${title}**: exit ${exit}. Scripts read the same `
    + 'verdict from `verify --json`. Treat only exits 0 and 5 as verified.',
  state: { ...VERDICTS, highlight: [row] },
  cards: { verify: { title, detail, tone } },
  focus: ['verify'],
});

const fails = (title, text, label, message, code = 'INVALID') => ({
  title,
  text,
  checks: [{ label: `${label}: “${message}”`, ok: false, code }],
  cards: { verify: { title: 'Invalid', detail: 'exit 4', tone: 'fail' } },
  focus: ['verify'],
});

export default {
  id: 'attest-lab',
  type: 'steps',
  title: 'Verifying an Attest certificate',
  intro: 'the six checks `yano-attest verify` runs, in order, and the verdict each trust input can reach. '
    + 'Try a “What if” to break one check at a time.',
  lanes: [
    { id: 'you', label: 'You', note: 'certificate + file', kind: 'actor' },
    { id: 'verify', label: 'yano-attest verify', note: 'offline', kind: 'client' },
    { id: 'trust', label: 'Your trust input', note: 'obtained independently', kind: 'external' },
    { id: 'l1', label: 'Cardano', note: 'anchor datum', kind: 'cardano' },
  ],
  scenarios: [
    {
      id: 'members',
      label: 'Pinned members',
      steps: [digest, binding, inclusion, finality, anchorNone, trailHead,
        verdict(1, '`CALLER_PINNED_ROOT`', 'accepted · exit 5', 5)],
    },
    {
      id: 'no-trust',
      label: 'No trust input',
      summary: 'Verify with neither a members file nor an anchor datum.',
      steps: [digest, binding, inclusion, {
        ...finality,
        text: 'C4. Without a members file, the certificate is checked only against the members its own evidence '
          + 'declares. That shows internal consistency, not that the right members signed.',
        wires: [],
        checks: [{ label: 'Certificate is consistent with the members it declares', ok: true }],
        cards: { verify: { title: 'C4 Finality', detail: 'declared members only', tone: 'pending' } },
        focus: ['verify'],
      }, anchorNone, trailHead, {
        title: 'Verdict',
        text: 'The checks pass, but nothing ties the certificate to members you trust. The result is consistent '
          + 'only and **not accepted**: exit 6. Supply `--members` or `--anchor-datum-hex`.',
        state: { ...VERDICTS, highlight: [0] },
        cards: { verify: { title: '`INTERNAL_CONSISTENCY_ONLY`', detail: 'not accepted · exit 6', tone: 'pending' } },
        focus: ['verify'],
      }],
    },
    {
      id: 'anchor',
      label: 'Anchor datum from Cardano',
      summary: 'The certificate is `ANCHORED`, and you read the anchor datum from a Cardano source you trust.',
      steps: [digest, binding, inclusion, {
        ...finality,
        text: 'C4. The members and threshold come from the anchor datum you read from Cardano, together with the '
          + 'genesis id and commitment profile.',
        wires: [{ from: 'l1', to: 'trust', label: 'datum you read' }, { from: 'trust', to: 'verify', label: '`--anchor-datum-hex`' }],
        cards: {
          l1: { title: 'State-thread output', detail: 'inline datum', tone: 'cardano' },
          trust: { title: 'Anchor datum', detail: 'canonical CBOR hex' },
          verify: { title: 'C4 Finality', detail: 'VALID · datum members', tone: 'ok' },
        },
      }, {
        title: 'Anchor linkage',
        text: 'C5. The datum must name the same chain id, height, block hash, state root, threshold, member set, '
          + 'genesis id, commitment profile, format fingerprint, and application id as the evidence.',
        checks: [{ label: 'Anchor datum binds the evidence segment', ok: true, code: 'INDEPENDENTLY_VERIFIED' }],
        cards: { verify: { title: 'C5 Anchor', detail: 'INDEPENDENTLY_VERIFIED', tone: 'ok' } },
        focus: ['verify'],
      }, trailHead, verdict(2, '`INDEPENDENTLY_VERIFIED_L1_ANCHOR`', 'accepted · exit 0', 0, 'cardano')],
    },
    {
      id: 'edited',
      label: 'The file was edited',
      summary: 'Someone changed one byte of the document after it was attested.',
      steps: [{
        ...digest,
        checks: [{ label: 'SHA-256 of the file equals the attested digest', ok: false, code: 'MISMATCH' }],
        cards: { you: digest.cards.you, verify: { title: 'C1 Digest', detail: 'MISMATCH', tone: 'fail' } },
      }, fails('Verdict', 'Any failed check makes the certificate invalid for this file, whatever the trust input: '
        + 'exit 4. The certificate may still be valid for the original bytes.',
      'C1', 'supplied bytes do not hash to the attested digest')],
    },
    {
      id: 'wrong-members',
      label: 'Wrong members file',
      summary: 'The members file names a different membership or threshold than the evidence.',
      steps: [digest, binding, inclusion, fails('Finality', 'C4 fails: the evidence was not signed by the members '
        + 'and threshold you pinned. Re-check where your keys came from before trusting either side.',
      'C4', 'finality evidence invalid: …'), {
        title: 'Verdict',
        text: 'A failed check gives exit 4.',
        cards: { verify: { title: 'Invalid', detail: 'exit 4', tone: 'fail' } },
        focus: ['verify'],
      }],
    },
    {
      id: 'not-anchored',
      label: 'Datum for an unanchored certificate',
      summary: 'You pass an anchor datum, but the certificate was issued before the next anchor.',
      steps: [digest, binding, inclusion, finality, fails('Anchor linkage', 'C5 fails: the certificate is '
        + '`FINALIZED`, and its evidence reaches no anchor. Re-issue it with `yano-attest certificate` after the next '
        + 'anchor lands, then verify again.',
      'C5', 'evidence segment carries no anchor reference; request an anchored certificate'), {
        title: 'Verdict',
        text: 'With an anchor datum as the trust input, the anchor must verify. It did not, so the result is exit 4.',
        cards: { verify: { title: 'Invalid', detail: 'exit 4', tone: 'fail' } },
        focus: ['verify'],
      }],
    },
  ],
  legend: [
    ['actor', 'you'],
    ['client', 'offline verifier'],
    ['external', 'trust input you obtained'],
    ['cardano', 'Cardano'],
    ['fail', 'failed check'],
  ],
  sources: [
    { repo: 'yano-x', path: 'products/attest/client/src/main/java/org/yanoproject/x/attest/client/AttestVerifier.java',
      anchors: ['supplied bytes do not hash to the attested digest', 'message id does not recompute from the signed body',
        'message signature does not verify under the sender key', 'message body is not a canonical doc-trail append command',
        'doc-trail command does not carry the certificate subject',
        'message proof does not bind the message to the evidence block', 'finality evidence invalid: ',
        'evidence segment carries no anchor reference; request an anchored certificate',
        'anchor datum does not bind the evidence segment: ',
        'trail head proof does not verify against the certified state root'] },
    { repo: 'yano-x', path: 'products/attest/client/src/main/java/org/yanoproject/x/attest/client/AttestVerification.java',
      anchors: ['enum Digest { NOT_SUPPLIED, MATCH, MISMATCH }', 'enum Anchor { NONE, NODE_REFERENCE, INDEPENDENTLY_VERIFIED, INVALID }',
        'enum TrailHead { NOT_INCLUDED, VERIFIED, INVALID }', 'level != ProofLabVocabulary.TrustLevel.INTERNAL_CONSISTENCY_ONLY'] },
    { repo: 'yano-x', path: 'products/attest/cli/src/main/java/org/yanoproject/x/attest/cli/AttestCli.java',
      anchors: ['OK = 0', 'INVALID = 4', 'PINNED = 5', 'UNPINNED = 6'] },
    { repo: 'yano-x', path: 'docs/appchain/ATTEST.md',
      anchors: ['`INTERNAL_CONSISTENCY_ONLY`', '`CALLER_PINNED_ROOT`', '`INDEPENDENTLY_VERIFIED_L1_ANCHOR`',
        'Treat only `0` and `5` as verified', 'The certificate remains valid without C6'] },
  ],
};
