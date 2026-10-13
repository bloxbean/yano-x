// The seven checks that take one `ordered-log` record from "a node says so" to
// "Cardano says so", with what each check requires you to pin. Facts follow the
// typed proof and anchor endpoints, the SDK verifiers, and the anchor datum ABI.

const recompute = {
  title: 'Recompute the record',
  text: 'Ask a node for a typed proof of `finalized-message-v1` with the view `latest-confirmed-anchor`. Recompute '
    + 'the state key from your message id, and decode the value: height, index, topic, and sender. The node '
    + 'labels the answer `NODE_CONFIRMED_L1_REFERENCE`, which is its own claim about Cardano.',
  command: 'POST …/proof-subjects/finalized-message-v1/proof   {"view": "latest-confirmed-anchor", …}',
  wires: [
    { from: 'you', to: 'node', label: 'message id' },
    { from: 'node', to: 'you', label: 'value + proof + certificate' },
  ],
  cards: {
    node: { title: 'Not trusted', detail: 'any member, any operator' },
    you: { title: 'Record decoded', detail: 'height 42 · index 0' },
  },
};

const path = {
  title: 'Check the path',
  text: 'Verify the MPF path from that key and value to the returned state root, with '
    + '`ProofVerifier.verifyInternalConsistency`. This is proof math only, `INTERNAL_CONSISTENCY_ONLY`: a node '
    + 'could have invented the whole tree and its root.',
  checks: [{ label: 'The path recomputes the returned root', ok: true }],
  cards: {
    you: { title: 'Consistent', detail: 'root `7f3a…` unauthenticated', tone: 'pending' },
  },
};

const finality = {
  title: 'Check finality',
  text: 'Verify the block header’s finality certificate with `ProofVerifier.verifyCertified`. You pin the '
    + 'genesis id, state profile, member keys, threshold, and the consensus-context digest for that height, '
    + 'all from configuration you trust, never from the response.',
  checks: [
    { label: 'The path recomputes the returned root', ok: true },
    { label: 'A threshold of the pinned member keys signed the header that commits this root', ok: true },
  ],
  cards: {
    you: { title: 'Certified', detail: 'pinned members signed `7f3a…`', tone: 'ok' },
  },
};

const hashChain = {
  title: 'Follow the hash chain',
  text: 'Needed when your record sits in an older block than the anchor, as a message proof against its '
    + 'block’s messages root does. `evidence/{messageId}` returns every block up to the anchored one, and '
    + '`EvidenceVerifier` checks each link and signature. A typed proof at the anchored height skips this.',
  command: 'GET …/evidence/{messageId}',
  wires: [{ from: 'node', to: 'you', label: 'blocks to the anchor' }],
  cards: {
    you: { title: 'Linked', detail: 'block 42 = anchored block', tone: 'ok' },
  },
};

const fetchL1 = {
  title: 'Fetch from Cardano',
  text: 'Read the anchor transaction and the thread UTxO from a Cardano source you choose: your own node, '
    + 'db-sync, Koios, or an explorer. A transaction hash in a Yano response is not verification.',
  wires: [{ from: 'l1', to: 'you', label: 'thread UTxO', tone: 'cardano' }],
  cards: {
    l1: { title: 'Independent source', detail: 'not a Yano node', tone: 'cardano' },
  },
};

const identity = {
  title: 'Check the anchor identity',
  text: 'Require the validator’s script address and the thread token’s policy id that you recorded when the '
    + 'anchor was bootstrapped. Anyone can write a datum to some other address; only this thread is the '
    + 'ledger’s anchor.',
  checks: [
    { label: 'The output sits at the expected script address', ok: true },
    { label: 'It holds the expected thread token', ok: true },
  ],
  cards: {
    l1: { title: 'Thread ✓', detail: 'policy + script address', tone: 'cardano' },
  },
};

const datum = {
  title: 'Match the datum',
  text: 'Decode the inline datum and match its fields: chain id, genesis id, application id, commitment '
    + 'profile, format fingerprint, height, block hash, state root, member keys, and threshold. Now the record '
    + 'is tied to Cardano without trusting any node: `INDEPENDENTLY_VERIFIED_L1_ANCHOR`.',
  checks: [
    { label: 'Height and block hash match the certified block', ok: true },
    { label: 'State root matches the proof’s root', ok: true },
    { label: 'Identity, member keys, and threshold match what you pinned', ok: true },
  ],
  cards: {
    you: { title: 'Anchored on Cardano', detail: 'no node trusted', tone: 'final' },
  },
};

export default {
  id: 'verify-ladder',
  type: 'steps',
  title: 'Verify a record against Cardano',
  intro: 'one `orders-chain` message, final at height 42 and covered by a script anchor. Each step names what '
    + 'it proves and what you must pin yourself.',
  lanes: [
    { id: 'you', label: 'You, the verifier', note: 'pinned inputs', kind: 'client' },
    { id: 'node', label: 'Yano node', note: 'answers, not trusted', kind: 'member' },
    { id: 'l1', label: 'Cardano', note: 'your own source', kind: 'cardano' },
  ],
  scenarios: [
    {
      id: 'ladder',
      label: 'All seven checks',
      steps: [recompute, path, finality, hashChain, fetchL1, identity, datum],
    },
    {
      id: 'forged-root',
      label: 'A node forges the root',
      summary: 'Proof math alone cannot catch a made-up tree.',
      steps: [{
        ...recompute,
        cards: {
          node: { title: 'Dishonest', detail: 'invents a tree', tone: 'fail' },
          you: { title: 'Record decoded', detail: 'height 42 · index 0' },
        },
      }, {
        ...path,
        text: 'The path checks out: the node built a tree around your record and computed its root, `91c4…`. '
          + 'Consistency says nothing about who agreed to that root.',
        cards: { you: { title: 'Consistent', detail: 'root `91c4…`', tone: 'pending' } },
      }, {
        ...finality,
        text: 'No threshold of the member keys you pinned signed a header that commits `91c4…`. The check fails '
          + 'and you stop here.',
        checks: [
          { label: 'The path recomputes the returned root', ok: true },
          { label: 'A threshold of the pinned member keys signed the header that commits this root', ok: false },
        ],
        cards: { you: { title: 'Rejected', detail: 'no certificate for `91c4…`', tone: 'fail' } },
      }],
    },
    {
      id: 'too-new',
      label: 'The anchor is older than the record',
      summary: 'Absent under an older anchor does not mean it never happened.',
      steps: [{
        ...recompute,
        text: 'Your message is final at height 42, but the latest confirmed anchor is at height 40. The proof '
          + 'is against the anchored root, where your record does not exist yet: its presence is `ABSENT`.',
        cards: {
          node: { title: 'Anchored at 40', detail: 'tip 42' },
          you: { title: 'ABSENT at 40', detail: 'exclusion proof', tone: 'pending' },
        },
      }, {
        title: 'Wait for the next anchor',
        text: 'Poll `anchor/commitment` until `anchoredHeight` is at least 42, then ask again. Anchors follow '
          + '`every-blocks`, so a quiet ledger may need one more block, or the `max-interval-minutes` timer.',
        command: 'GET …/anchor/commitment',
        wires: [{ from: 'node', to: 'you', label: '`anchoredHeight: 42`' }],
        cards: {
          node: { title: 'Anchored at 42', detail: 'new advance', tone: 'final' },
          you: { title: 'PRESENT at 42', detail: 'continue the checks', tone: 'ok' },
        },
      }],
    },
  ],
  legend: [
    ['client', 'you'],
    ['member', 'a node you do not trust'],
    ['cardano', 'Cardano'],
    ['final', 'independently verified'],
    ['fail', 'rejected'],
  ],
  sources: [
    { repo: 'yano', path: 'app/src/main/java/org/yanoproject/app/api/appchain/AppChainResource.java',
      anchors: ['@Path("proof-subjects/{subjectId}/proof")', 'case "latest-confirmed-anchor"',
        '@Path("anchor/commitment")', 'result.put("provenance", "L1-confirmed by this node");',
        '@Path("evidence/{messageIdHex}")', 'result.put("presence", proof.presence().name());'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainSubsystem.java',
      anchors: ['.TrustLevel.NODE_CONFIRMED_L1_REFERENCE;', '.TrustLevel.INTERNAL_CONSISTENCY_ONLY;'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/proof/ProofLabVocabulary.java',
      anchors: ['INTERNAL_CONSISTENCY_ONLY', 'NODE_CONFIRMED_L1_REFERENCE', 'INDEPENDENTLY_VERIFIED_L1_ANCHOR'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/state/StateProof.java',
      anchors: ['PRESENT,', 'ABSENT,'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/evidence/EvidenceVerifier.java',
      anchors: ['public static Result verify(EvidenceBundle bundle, TrustContext expected)'] },
    { repo: 'yano', path: 'docs/appchain/state-machines/ordered-log.md',
      anchors: ['sha256("~yano/finalized-message/v1/" || message-id)',
        'cbor([schema-version, block-height, message-index, topic, sender])'] },
    { repo: 'yano', path: 'core-api/src/main/cddl/appchain/anchor-v1.cddl',
      anchors: ['chain-genesis-id : bstr .size 32', 'application-id : bstr .size (1..128)',
        'format-fingerprint : bstr .size 32', 'threshold    : uint'] },
    { repo: 'yano-x', path: 'sdk/client/src/main/java/org/yanoproject/x/client/ProofVerifier.java',
      anchors: ['public static boolean verifyInternalConsistency(AppChainClient.Proof proof)',
        'public static boolean verify(AppChainClient.Proof proof, TrustedStateRoot trustedRoot)',
        'public static boolean verifyCertified('] },
    { repo: 'yano-x', path: 'docs/appchain/COMPOSABLE_STATE_AND_PROOFS.md',
      anchors: ['never copy it from the untrusted response'] },
    { repo: 'yano-x', path: 'docs/APP_CHAIN_USER_GUIDE.md',
      anchors: ['Transaction-hash visibility alone is not anchor verification.'] },
  ],
};
