// A short decision aid for the "When an app ledger is the right answer" section.
// It encodes that section's own criteria; it is guidance, not a product rule.

export default {
  id: 'app-ledger-fit',
  type: 'chooser',
  title: 'Do you need an app ledger?',
  tag: 'Decision aid',
  intro: 'four questions drawn from the criteria on this page. Answer them for your use case.',
  start: 'who',
  nodes: {
    who: {
      question: 'Who needs to agree on the records?',
      options: [
        { label: 'One organization, and nobody outside it checks them', next: 'database' },
        { label: 'Several organizations', next: 'open' },
      ],
    },
    open: {
      question: 'Who may take part?',
      options: [
        { label: 'Anyone, without permission', next: 'public-chain' },
        { label: 'Known organizations that run members', next: 'proof' },
      ],
    },
    proof: {
      question: 'Will someone later need to prove a specific record, rather than be told about it?',
      options: [
        { label: 'Yes, records can be disputed or audited', next: 'volume' },
        { label: 'No, it is high-frequency data with nothing to dispute', next: 'shared-service' },
      ],
    },
    volume: {
      question: 'Could every event go directly onto a public chain?',
      help: 'Think about event volume, privacy, cost, and how custom the rules are.',
      options: [
        { label: 'Yes: few events, public data, simple rules', next: 'cardano' },
        { label: 'No: too many events, private data, or custom rules', next: 'app-ledger' },
      ],
    },
    database: {
      result: {
        title: 'A conventional database is simpler',
        text: 'With one organization and no outside verifier, replicated agreement adds cost without adding trust.',
      },
    },
    'public-chain': {
      result: {
        title: 'Use a public blockchain',
        text: 'An app ledger has a known set of members. Permissionless participation needs a public chain such as '
          + 'Cardano itself.',
      },
    },
    'shared-service': {
      result: {
        title: 'A shared service is probably enough',
        text: 'If nobody will dispute the data, a shared database or message broker run by one party is simpler. '
          + 'Revisit this if records start to matter in audits or disputes.',
      },
    },
    cardano: {
      result: {
        title: 'Consider Cardano directly',
        text: 'When events are few, public, and simple, writing them to Cardano gives independent verification '
          + 'without running members.',
      },
    },
    'app-ledger': {
      result: {
        title: 'An app ledger fits',
        text: 'Several known organizations need the same ordered records, verifiable proofs, and rules of their own, '
          + 'at a volume or privacy level a public chain cannot carry. Anchor to Cardano when outsiders need a '
          + 'public reference.',
        facts: [
          ['Try it', 'a three-member ledger on your laptop'],
          ['Then', 'pick a recipe for your rules'],
        ],
        links: [
          { label: 'Local showcase', href: '/start-here/quickstart/' },
          { label: 'Choosing a recipe', href: '/recipes/choosing-a-recipe/' },
        ],
      },
    },
  },
  sources: [
    { repo: 'yano-x', path: 'docs/site/start-here-what-is-an-app-ledger.md',
      anchors: ['several organizations must agree on the same sequence of application records',
        'genuinely needs permissionless participation', 'high-frequency data with no dispute surface',
        'the volume, privacy, or cost profile makes putting each event directly on a public chain impractical'] },
  ],
};
