// Which proof trust level a verification reaches, from what the verifier
// pinned. The five values are Yano's ProofLabVocabulary.TrustLevel; how each is
// reached follows the node, the Java client, and the Yano X verifier tools.

export default {
  id: 'trust-ladder',
  type: 'chooser',
  title: 'How much does this proof prove?',
  tag: 'Decision aid',
  intro: 'the five trust levels name where the root came from. Answer for the root your verifier compared the '
    + 'proof against.',
  start: 'source',
  nodes: {
    source: {
      question: 'Where did the root you checked the proof against come from?',
      help: 'A proof always recomputes a root. The question is whether anything other than the serving node '
        + 'vouches for that root.',
      options: [
        { label: 'From the same response as the proof', next: 'internal' },
        { label: 'From me: a root I pinned, or member keys and a threshold I pinned', next: 'pinned-root' },
        { label: 'From a Cardano anchor', next: 'anchor' },
      ],
    },
    anchor: {
      question: 'Who read the anchor from Cardano?',
      options: [
        { label: 'The node reported a confirmed anchor', next: 'node-l1' },
        { label: 'I supplied an anchor datum to my verifier', next: 'pinned-anchor' },
        { label: 'I or my verifier read the anchor output from Cardano', next: 'independent' },
      ],
    },
    internal: {
      result: {
        title: '`INTERNAL_CONSISTENCY_ONLY`',
        text: 'The proof is consistent with the root it came with, and that root is whatever the node chose. A '
          + 'node’s proof response reports this level by default.',
        facts: [
          ['Establishes', 'the proof is well formed'],
          ['Does not establish', 'that the root is the ledger’s certified root'],
        ],
      },
    },
    'pinned-root': {
      result: {
        title: '`CALLER_PINNED_ROOT`',
        text: 'The proof matches a root you authenticated yourself: a root you pinned, or the root of a block whose '
          + 'finality certificate verifies under member keys and a threshold you pinned.',
        facts: [
          ['Establishes', 'the record is in the state your pinned members certified'],
          ['Does not establish', 'any public Cardano record'],
          ['For example', 'Attest `--members keys.json`; `yano-explorer verify --members`'],
        ],
      },
    },
    'node-l1': {
      result: {
        title: '`NODE_CONFIRMED_L1_REFERENCE`',
        text: 'The node proved the record at the height of its latest confirmed anchor and says Cardano carries '
          + 'that anchor. That is the node’s claim about Cardano, not something you checked.',
        facts: [
          ['Establishes', 'what the node reports about its anchor'],
          ['Does not establish', 'that the anchor is on Cardano'],
          ['For example', 'a proof request with `"view": "latest-confirmed-anchor"`'],
        ],
      },
    },
    'pinned-anchor': {
      result: {
        title: '`CALLER_PINNED_ANCHOR`',
        text: 'The proof is bound to an anchor datum you supplied: its height, root, block hash, genesis, and member '
          + 'set, without the verifier reading Cardano itself. Yano defines this level, but no Yano X command '
          + 'reports it today: the tools that take an anchor datum report the independent level below.',
        facts: [
          ['Establishes', 'the record matches the anchor data you supplied'],
          ['Does not establish', 'that the datum came from Cardano'],
        ],
      },
    },
    independent: {
      result: {
        title: '`INDEPENDENTLY_VERIFIED_L1_ANCHOR`',
        text: 'The root is bound to anchor data read from Cardano, independently of any member or node. This is the '
          + 'strongest level.',
        facts: [
          ['Establishes', 'the record is in the state the anchored root commits to'],
          ['Does not establish', 'that the data is still available: availability is always reported separately'],
          ['For example', 'Attest, Trust Registry, and `yano-explorer verify` with `--anchor-datum-hex`, using a datum you read from Cardano'],
        ],
      },
    },
  },
  sources: [
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/proof/ProofLabVocabulary.java',
      anchors: ['INTERNAL_CONSISTENCY_ONLY', 'CALLER_PINNED_ROOT', 'NODE_CONFIRMED_L1_REFERENCE', 'CALLER_PINNED_ANCHOR',
        'INDEPENDENTLY_VERIFIED_L1_ANCHOR', 'NOT_PROVEN', 'LOCALLY_RETAINED'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainSubsystem.java',
      anchors: ['.TrustLevel.INTERNAL_CONSISTENCY_ONLY;', 'case LATEST_CONFIRMED_ANCHOR ->',
        '.TrustLevel.NODE_CONFIRMED_L1_REFERENCE;'] },
    { repo: 'yano', path: 'app/src/main/java/org/yanoproject/app/api/appchain/AppChainResource.java',
      anchors: ['case "latest-confirmed-anchor" ->', 'response.put("trust", result.trust().name());'] },
    { repo: 'yano-x', path: 'sdk/client/src/main/java/org/yanoproject/x/client/PortableProofBundle.java',
      anchors: ['case FINALITY_CERTIFICATE, CALLER_PINNED ->', 'ProofLabVocabulary.TrustLevel.CALLER_PINNED_ROOT;',
        'ProofLabVocabulary.TrustLevel.INDEPENDENTLY_VERIFIED_L1_ANCHOR;'] },
    { repo: 'yano-x', path: 'docs/appchain/EXPLORER.md',
      anchors: ['| `CALLER_PINNED_ROOT` (exit 5) | `verify --members` |',
        '`INDEPENDENTLY_VERIFIED_L1_ANCHOR`'] },
    { repo: 'yano-x', path: 'products/explorer/cli/src/main/java/org/yanoproject/x/explorer/cli/ExplorerCli.java',
      anchors: ['AttestTrust.IndependentAnchor.fromDatumHex(options.get("anchor-datum-hex")'] },
    { repo: 'yano-x', path: 'products/attest/client/src/main/java/org/yanoproject/x/attest/client/AttestTrust.java',
      anchors: ['return ProofLabVocabulary.TrustLevel.INDEPENDENTLY_VERIFIED_L1_ANCHOR;'] },
    { repo: 'yano-x', path: 'docs/appchain/ATTEST.md',
      anchors: ['| `--members keys.json` | `CALLER_PINNED_ROOT` |',
        '| `--anchor-datum-hex <cbor>` | `INDEPENDENTLY_VERIFIED_L1_ANCHOR` | The evidence segment matches the datum you read from Cardano. |'] },
    { repo: 'yano-x', path: 'docs/appchain/TRUST_REGISTRY.md',
      anchors: ['an `AnchorDatumV1` the caller read from Cardano (`--anchor-datum-hex`)'] },
    { repo: 'yano-x', path: 'docs/appchain/PROOF_LAB.md',
      anchors: ['only `INTERNAL_CONSISTENCY_ONLY` until the root is pinned by',
        'independently checked Cardano script output', 'durable availability remains `NOT_PROVEN`'] },
  ],
};
