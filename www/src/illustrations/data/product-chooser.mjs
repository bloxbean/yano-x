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
        { label: 'A document several organizations approve, stored and announced to other systems', next: 'evidence' },
        { label: 'What Cardano\'s parameters, stake, or proposals were at a past epoch', next: 'history' },
        { label: 'Cardano-shaped transactions on an app ledger', next: 'eutxo' },
        { label: 'Something else', next: 'other' },
      ],
    },
    evidence: result('Evidence',
      'Publishes an immutable document through a threshold-approved workflow, preserves it in object storage and IPFS, '
        + 'notifies Kafka, and proves the chain of events.',
      '`preview`', '`examples/evidence/demo.sh` (needs Docker)', '/products/evidence/', 'Evidence'),
    history: result('Cardano History',
      'Records Cardano epoch facts on an app ledger and answers with compact proofs instead of trusted indexer '
        + 'responses.',
      '`preview`', 'the showcase\'s `cardano-history-chain` with `tools/yano-cardano-history`',
      '/products/cardano-history/', 'Cardano History'),
    eutxo: result('eUTxO ledger',
      'A deterministic Cardano-shaped UTxO ledger, with an optional federated bridge. For disposable test funds '
        + 'only.',
      '`experimental`', '`./yano.sh appchain eutxo demo up --scenario ledger …`', '/products/eutxo/',
      'eUTxO ledger'),
    other: result('A recipe or a composite',
      'No product packages this yet. A stock recipe, a declarative binding between stock components, or a plugin '
        + 'usually does.',
      'stable to experimental, per recipe', '`./yano.sh appchain try --chain <id>=<recipe>`',
      '/recipes/choosing-a-recipe/',
      'Choosing a recipe'),
  },
  sources: [
    { repo: 'yano-x', path: 'docs/site/products-overview.md',
      anchors: ['Publishes a document through threshold approval',
        'Proves historical Cardano parameters', 'A Cardano-shaped UTxO ledger with an optional federated bridge'] },
    { repo: 'yano-x', path: 'distribution/jvm/jvm-distribution.gradle',
      anchors: ["into 'examples/evidence'", "into 'product-ui/eutxo'",
        "'yano-cardano-history': cardanoHistoryCliInstallDirectory"] },
  ],
};
