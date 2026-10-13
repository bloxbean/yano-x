// How doc-trail chains entry hashes into a head, and which edits a verifier
// detects by recomputing it. Hash values are shortened examples; the formula is
// DocTrailTransitions.decide and DocTrailContract.computeHead.

const COLUMNS = ['Key', 'Count', 'Head'];
const HEAD_1 = 'Blake2b(`00…00` ‖ h1 ‖ A) = `3f1c…`';
const HEAD_2 = 'Blake2b(`3f1c…` ‖ h2 ‖ B) = `9a07…`';

const start = {
  title: 'Start empty',
  text: 'Entity `product-42` has no trail yet. The first append chains from a genesis head of 32 zero bytes.',
  state: { caption: 'doc-trail state', columns: COLUMNS, rows: [] },
  cards: { sm: { title: 'No entry', detail: 'genesis head `00…00`' } },
};

const appendA = {
  title: 'A appends v1',
  text: 'Member A appends the SHA-256 hash h1 of certificate v1, with a reference to where it is stored. The new '
    + 'head hashes the old head, the entry hash and A’s member key. The reference is not part of the head.',
  wires: [{ from: 'a', to: 'sm', label: '[`product-42`, h1, `s3://…/v1.pdf`]' }],
  state: { caption: 'doc-trail state', columns: COLUMNS, rows: [['`e/product-42`', '1', HEAD_1]], highlight: [0] },
  cards: { sm: { title: 'Count 1', detail: 'head `3f1c…`', tone: 'final' } },
};

const appendB = {
  title: 'B appends v2',
  text: 'Member B appends hash h2 of version 2. There is no owner check: any member may append to any entity. The '
    + 'head now commits to both entries, their order, and both authors.',
  wires: [{ from: 'b', to: 'sm', label: '[`product-42`, h2, `s3://…/v2.pdf`]' }],
  state: { caption: 'doc-trail state', columns: COLUMNS, rows: [['`e/product-42`', '2', HEAD_2]], highlight: [0] },
  cards: { sm: { title: 'Count 2', detail: 'head `9a07…`', tone: 'final' } },
};

const verify = (claim, ok, detail) => ({
  title: 'Verify',
  text: ok
    ? 'A verifier reads the two finalized commands from block history, recomputes the head from (h1, A) then '
      + '(h2, B), and compares it with the proven head. They match.'
    : `A verifier recomputes the head from ${claim} and compares it with the proven head \`9a07…\`. ${detail}`,
  wires: [{ from: 'sm', to: 'verifier', label: 'proof of `e/product-42`' }],
  checks: [{ label: 'The recomputed head equals the proven head', ok }],
  cards: {
    verifier: ok
      ? { title: 'Trail matches', detail: 'count 2, head `9a07…`', tone: 'ok' }
      : { title: 'Mismatch', detail: 'the claimed trail is not this one', tone: 'fail' },
  },
});

export default {
  id: 'doc-trail-chain',
  type: 'steps',
  title: 'A chained document trail',
  intro: 'two members append document hashes to one product. The head is a chain of hashes, so a verifier who '
    + 'recomputes it detects any change to the bytes, the order, or the authors.',
  lanes: [
    { id: 'a', label: 'Member A', note: 'manufacturer', kind: 'member' },
    { id: 'b', label: 'Member B', note: 'auditor', kind: 'member' },
    { id: 'sm', label: 'doc-trail', note: 'on every member', kind: 'core' },
    { id: 'verifier', label: 'Verifier', note: 'recomputes the head', kind: 'client' },
  ],
  scenarios: [
    { id: 'trail', label: 'Append and verify', steps: [start, appendA, appendB, verify('(h1, A), (h2, B)', true)] },
    {
      id: 'tamper',
      label: 'A document is edited',
      summary: 'Someone changes one byte of certificate v1 after it was appended.',
      steps: [appendA, appendB, verify('the edited file’s hash h1′', false,
        'The edited file hashes to a different h1′, so every later head differs.')],
    },
    {
      id: 'reorder',
      label: 'The order is swapped',
      summary: 'Someone claims version 2 came first.',
      steps: [appendA, appendB, verify('(h2, B) then (h1, A)', false,
        'The head chains entries in order, so a different order gives a different head.')],
    },
    {
      id: 'author',
      label: 'The author is swapped',
      summary: 'Someone claims member B appended version 1.',
      steps: [appendA, appendB, verify('(h1, B), (h2, B)', false,
        'Each step hashes the sender’s member key, so a different author gives a different head.')],
    },
    {
      id: 'reference',
      label: 'The reference is edited',
      summary: 'Someone points version 1’s reference at another file.',
      steps: [appendA, appendB, {
        title: 'Verify',
        text: 'The recomputed head still matches: the reference is not hashed into it. Only the finalized command in '
          + 'block history shows the original reference, so check the document by its hash, not its location.',
        wires: [{ from: 'sm', to: 'verifier', label: 'proof of `e/product-42`' }],
        checks: [{ label: 'The recomputed head equals the proven head', ok: true }],
        cards: { verifier: { title: 'Head matches', detail: 'the reference is not covered', tone: 'pending' } },
      }],
    },
  ],
  legend: [
    ['member', 'member node'],
    ['core', 'deterministic state machine'],
    ['client', 'verifier'],
    ['fail', 'tampering detected'],
  ],
  sources: [
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/DocTrailTransitions.java',
      anchors: ['GENESIS_HEAD = new byte[32]', 'concat(current.headHash(), command.entryHash(), context.sender())',
        'Math.addExact(current.count(), 1)'] },
    { repo: 'yano-x', path: 'state-machines/stdlib-contracts/src/main/java/org/yanoproject/x/stdlib/contracts/DocTrailContract.java',
      anchors: ['public static byte[] computeHead(List<byte[]> entryHashes, List<byte[]> authors)',
        'head = Blake2bUtil.blake2bHash256(concat(head, hash, author));', '("e/" + entityId)',
        'array.add(new UnicodeString(reference != null ? reference : ""));'] },
  ],
};
