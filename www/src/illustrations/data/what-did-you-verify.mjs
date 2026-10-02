// Four results a reader meets in the first tutorials, and what each does and
// does not establish. Facts follow Yano's submission contract and consensus
// guide, and Yano X's proof and anchoring documentation; see `sources`.

export default {
  id: 'what-did-you-verify',
  type: 'diagram',
  title: 'What did you verify?',
  tag: 'Concept',
  hint: 'Select a result to see what it establishes and what it does not.',
  caption: 'Each result adds evidence to the one before. None of them alone means “the business action '
    + 'succeeded and everyone can prove it”.',
  blocks: [
    {
      id: 'accepted', label: '202 Accepted', sub: 'one member queued it', kind: 'member',
      detail: '**Establishes:** one member checked the envelope and the command against its current state, and '
        + 'queued the message in memory. **Does not establish:** that the message is ordered, final, or '
        + 'successful. A rule can still fail when the block runs, and a message that no other member received is '
        + 'lost if that member restarts.',
    },
    {
      id: 'final', label: 'Final', sub: 'threshold-certified', kind: 'final',
      detail: '**Establishes:** a threshold of members re-executed the block, got the same state root, and signed '
        + 'it in two rounds, PREPARE then COMMIT. The message has a fixed height and position and is never rolled '
        + 'back. **Does not establish:** that your command succeeded. A command that breaks a business rule is '
        + 'final as a no-op, so read the application’s result.',
      link: { label: 'Consensus and finality', href: '/concepts/consensus-and-finality/' },
    },
    {
      id: 'proof', label: 'Proof', sub: 'against one root', kind: 'core',
      detail: '**Establishes:** a record is, or is not, in the state under one specific root. **Does not '
        + 'establish:** that this root is the ledger’s certified root, unless you pinned it or checked it against '
        + 'a certificate or an anchor. Absence from the state does not prove a fact never happened, and no proof '
        + 'shows that data will stay available.',
      link: { label: 'State and proofs', href: '/concepts/state-and-proofs/' },
    },
    {
      id: 'anchor', label: 'Anchor', sub: 'optional, on Cardano', kind: 'cardano',
      detail: '**Establishes:** a Cardano transaction records the ledger’s height, block hash, and state root. In '
        + 'script mode, Cardano accepts each advance only with a threshold of member signatures; in metadata mode '
        + 'it enforces nothing. **Does not establish:** that the application rules were followed, because Cardano '
        + 'does not run them. It covers the anchored height, not later blocks, and finality never waits for it.',
      link: { label: 'Cardano anchoring', href: '/concepts/anchoring/' },
    },
  ],
  edges: [
    { from: 'accepted', to: 'final' },
    { from: 'final', to: 'proof' },
    { from: 'proof', to: 'anchor' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 96,
      blocks: {
        accepted: [20, 16, 156, 64],
        final: [208, 16, 156, 64],
        proof: [396, 16, 156, 64],
        anchor: [584, 16, 156, 64],
      },
    },
    narrow: {
      width: 380,
      height: 296,
      blocks: {
        accepted: [40, 12, 300, 56],
        final: [40, 84, 300, 56],
        proof: [40, 156, 300, 56],
        anchor: [40, 228, 300, 56],
      },
    },
  },
  sources: [
    { repo: 'yano', path: 'docs/appchain/submission.md',
      anchors: ['**202** with `messageId`', 'Accepted is not finalized'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['**202** is only a local acceptance acknowledgement', 'In-memory (lost on restart, by design): the pending pool',
        'requires byte-identical `stateRoot`', 'there is no rollback path below finality', 'deterministic no-op'] },
    { repo: 'yano-x', path: 'docs/appchain/PROOF_LAB.md',
      anchors: ['A proof that reconstructs the root is only `INTERNAL_CONSISTENCY_ONLY`',
        'durable availability remains `NOT_PROVEN`', 'a pruned proof is unavailable, not evidence that the fact was absent'] },
    { repo: 'yano-x', path: 'docs/site/concepts-anchoring.md',
      anchors: ['height, block hash, and **state root**', 'Nothing — a data-only commitment',
        'm-of-n member signatures on every advance'] },
  ],
};
