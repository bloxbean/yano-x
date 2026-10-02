// Which Yano X product fits a problem. Each result restates what the product's
// own guide says it does and how to try it from the JVM distribution; it is
// guidance, not a product rule.

const result = (title, text, maturity, tryIt, href, label) => ({
  result: {
    title,
    text,
    facts: [['Maturity', maturity], ['Try it', tryIt]],
    links: [{ label, href }],
  },
});

export default {
  id: 'product-chooser',
  type: 'chooser',
  title: 'Which product fits?',
  tag: 'Decision aid',
  intro: 'a few questions about what you need to prove or share. Each answer names a product and how to try it.',
  start: 'need',
  nodes: {
    need: {
      question: 'What do you need to prove or share?',
      options: [
        { label: 'A document existed, unchanged', next: 'document' },
        { label: 'A credential, issuer, or identifier is still valid', next: 'trust' },
        { label: 'A product\'s records across its life', next: 'dpp' },
        { label: 'One agreed value from several data sources', next: 'feed' },
        { label: 'What Cardano\'s parameters, stake, or proposals were at a past epoch', next: 'history' },
        { label: 'A readable, verifiable view of an existing ledger', next: 'explorer' },
        { label: 'Cardano-shaped transactions on an app ledger', next: 'eutxo' },
      ],
    },
    document: {
      question: 'Who has to approve the document?',
      options: [
        { label: 'Nobody: I need a timestamped digest and a portable certificate', next: 'attest' },
        { label: 'Several organizations, before it is released', next: 'release' },
      ],
    },
    release: {
      question: 'Must the released document also be stored and announced to other systems?',
      help: 'For example, kept in object storage and IPFS and announced on Kafka.',
      options: [
        { label: 'Yes, with connectors to external systems', next: 'evidence' },
        { label: 'No, approvers work from a browser and the ledger is enough', next: 'desk' },
      ],
    },
    attest: result('Attest',
      'Records a document\'s SHA-256 on a `doc-trail` ledger and hands out a certificate that verifies offline against '
        + 'member keys or a Cardano anchor.',
      '`preview`', 'the showcase\'s `documents-chain` with `tools/yano-attest`', '/products/attest/', 'Attest'),
    evidence: result('Evidence',
      'Publishes an immutable document through a threshold-approved workflow, preserves it in object storage and IPFS, '
        + 'notifies Kafka, and proves the chain of events.',
      '`preview`', '`examples/evidence/demo.sh` (needs Docker)', '/products/evidence/', 'Evidence'),
    desk: result('Evidence Desk',
      'An issuer proposes, auditors from distinct organizations approve with keys held in their browsers, and the '
        + 'approved release is applied once.',
      '`preview`', 'the showcase\'s `document-review-chain` with `product-ui/evidence`', '/products/evidence-desk/',
      'Evidence Desk'),
    trust: result('Trust Registry',
      'Answers credential status and issuer authorization with proofs bound to a certified block, and serves Bitstring '
        + 'Status Lists that hash to the ledger\'s value.',
      '`preview`', '`examples/trust-registry/registry.sh up`', '/products/trust-registry/', 'Trust Registry'),
    dpp: result('DPP Starter',
      'A prototype product passport registry: governed product, version, claim, and event records, certification by '
        + 'two independent auditors, and passports that verify offline.',
      '`reference` (prototype)', '`examples/dpp/dpp.sh up`', '/products/dpp-starter/', 'DPP Starter'),
    feed: result('Attestation Feed',
      'Sources sign their own readings, every verifier recomputes each round\'s aggregate, and publishers from two '
        + 'organizations approve the record. Not a public price oracle.',
      '`experimental` (starter)', '`examples/attestation-feed/feed.sh up`', '/products/attestation-feed/',
      'Attestation Feed'),
    history: result('Cardano History',
      'Records Cardano epoch facts on an app ledger and answers with compact proofs instead of trusted indexer '
        + 'responses.',
      '`preview`', 'the showcase\'s `cardano-history-chain` with `tools/yano-cardano-history`',
      '/products/cardano-history/', 'Cardano History'),
    explorer: result('Verifiable Explorer',
      'Indexes stock ledgers only after verifying each block, and exports rows and states as bundles that verify '
        + 'offline. The index adds convenience, never trust.',
      '`preview`', '`examples/explorer/explorer.sh up` against the showcase', '/products/explorer/',
      'Verifiable Explorer'),
    eutxo: result('eUTxO and ZK',
      'A deterministic Cardano-shaped UTxO ledger, with an optional federated bridge and optional ZeroJ validity '
        + 'proofs. For disposable test funds only.',
      '`experimental`', '`./yano.sh appchain eutxo demo up --scenario ledger …`', '/products/eutxo-and-zk/',
      'eUTxO and ZK'),
  },
  sources: [
    { repo: 'yano-x', path: 'docs/appchain/ATTEST.md', anchors: ['Attest records the SHA-256 digest of a document'] },
    { repo: 'yano-x', path: 'docs/appchain/EVIDENCE_DESK.md',
      anchors: ['auditors from independent organizations approve or reject it with keys that'] },
    { repo: 'yano-x', path: 'docs/appchain/TRUST_REGISTRY.md', anchors: ['W3C Bitstring Status Lists'] },
    { repo: 'yano-x', path: 'docs/appchain/DPP_STARTER.md', anchors: ['The DPP Starter is the configuration-only prototype'] },
    { repo: 'yano-x', path: 'docs/appchain/ATTESTATION_FEED.md', anchors: ['It must not be pitched as a public price oracle'] },
    { repo: 'yano-x', path: 'docs/appchain/EXPLORER.md', anchors: ['The index is never an authority'] },
    { repo: 'yano-x', path: 'distribution/jvm/jvm-distribution.gradle',
      anchors: ["into 'examples/trust-registry'", "into 'examples/explorer'", "into 'examples/dpp'",
        "into 'examples/attestation-feed'", "into 'examples/evidence'", "into 'product-ui/evidence'",
        "'yano-attest': attestCliInstallDirectory", "'yano-cardano-history': cardanoHistoryCliInstallDirectory"] },
  ],
};
