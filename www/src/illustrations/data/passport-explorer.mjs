// A DPP Starter passport: proof-bound answers from five collections at one
// height, the off-ledger pieces they commit to, and the flags the passport
// view raises. Flags and conditions follow PassportView.

export default {
  id: 'passport-explorer',
  type: 'diagram',
  title: 'Inside a passport',
  tag: 'Concept',
  hint: 'Select a record to see who writes it and which flags it can raise.',
  caption: 'A passport is a set of proof-bound answers at one height and one state root. Documents and claim '
    + 'disclosures stay off the ledger, committed by hash. The flags expose what a configuration-only prototype '
    + 'cannot prevent.',
  zones: [
    { id: 'answers', kind: 'ledger', label: 'Passport bundle · one height, one root', contains: ['product', 'versions', 'claims', 'events', 'certificates'] },
    { id: 'outside', kind: 'external', label: 'Off the ledger', contains: ['documents', 'disclosure'] },
  ],
  blocks: [
    {
      id: 'product', label: 'Product', sub: 'products', kind: 'core',
      detail: 'Written by a `manufacturer`: the manufacturer organization, status (`DRAFT`, `ACTIVE`, `INACTIVE`, '
        + '`REPLACED`, `RETIRED`), the current version, and a successor. A revoked passport is a tombstone. Flags: '
        + '`DANGLING` when the current version has no active version record; `FOREIGN_WRITER` when the writer\'s '
        + 'organization differs from the manufacturer the record names.',
    },
    {
      id: 'versions', label: 'Versions', sub: 'product-versions', kind: 'core',
      detail: 'Written by the `manufacturer`: the document\'s SHA-256, media type, length, and reference. Flags: '
        + '`REWRITTEN` when a version record has a revision above 1; `FOREIGN_WRITER`. A version shows '
        + '`CONTENT_VERIFIED` when the document store holds bytes that match the digest, otherwise `FINALIZED`.',
    },
    {
      id: 'claims', label: 'Claims', sub: 'claims', kind: 'core',
      detail: 'Written by a `claim-issuer`: public text, or a salted SHA-256 commitment whose salt and text are '
        + 'handed to verifiers out of band. Flags: `EXPIRED` or `NOT_YET_VALID` against the passport\'s height, and '
        + '`FOREIGN_WRITER`.',
    },
    {
      id: 'events', label: 'Events', sub: 'events', kind: 'core',
      detail: 'Appended by an `operator` (manufacturer, logistics, repairer, recycler). The ledger orders them, '
        + 'never a clock. Flag: `FOREIGN_WRITER` when the writer\'s organization differs from the one the event names.',
    },
    {
      id: 'certificates', label: 'Certificates', sub: 'certificates', kind: 'core',
      detail: 'A `certifier` proposes; two `auditor`s from distinct organizations approve; the map applies it once. '
        + 'The passport proves that the approval was consumed exactly once. Flags: `EXPIRED` or `NOT_YET_VALID`.',
    },
    {
      id: 'documents', label: 'Document store', sub: 'bytes by SHA-256', kind: 'external',
      detail: 'Passport documents are stored by hash outside consensus and served only while the bytes still hash to '
        + 'their name. The ledger cannot prove that a document remains available.',
    },
    {
      id: 'disclosure', label: 'Disclosure', sub: 'dpp-disclosure-v1', kind: 'external',
      detail: 'For a committed claim, the issuer hands the salt and text to a verifier, who runs '
        + '`yano-dpp disclose` or the console\'s check to recompute the commitment.',
    },
    {
      id: 'verifier', label: 'Verifier', sub: 'yano-dpp verify', kind: 'client',
      detail: 'Checks that every record names the bundle\'s chain, genesis, height, root, and block, verifies each '
        + 'proof, and checks finality: under the bundle\'s own members (exit 6), your pinned members (exit 5), or an '
        + 'anchor datum (exit 0). The passport view reports flags beside the result: facts the prototype cannot '
        + 'prevent, not verification failures.',
    },
  ],
  edges: [
    { from: 'versions', to: 'documents', label: 'SHA-256', style: 'dashed' },
    { from: 'claims', to: 'disclosure', label: 'commitment', style: 'dashed' },
    { from: 'answers', to: 'verifier', label: 'bundle' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 372,
      zones: { answers: [16, 16, 500, 236], outside: [16, 268, 500, 92] },
      blocks: {
        product: [36, 56, 140, 56],
        versions: [196, 56, 140, 56],
        claims: [356, 56, 140, 56],
        events: [116, 168, 140, 56],
        certificates: [276, 168, 140, 56],
        documents: [196, 296, 140, 52],
        disclosure: [356, 296, 140, 52],
        verifier: [580, 106, 164, 64],
      },
      edges: {
        'versions->documents': { fromSide: 'b', toSide: 't', fromAt: [266, 112], toAt: [266, 296], labelAt: [266, 260] },
        'claims->disclosure': { fromSide: 'b', toSide: 't', fromAt: [426, 112], toAt: [426, 296], labelAt: [426, 260] },
        'answers->verifier': { fromSide: 'r', toSide: 'l', fromAt: [516, 138], toAt: [580, 138], labelAt: [548, 124] },
      },
    },
    narrow: {
      width: 380,
      height: 600,
      zones: { answers: [8, 8, 364, 300], outside: [8, 324, 364, 96] },
      blocks: {
        product: [24, 48, 160, 56],
        versions: [196, 48, 160, 56],
        claims: [24, 136, 160, 56],
        events: [196, 136, 160, 56],
        certificates: [110, 224, 160, 56],
        documents: [196, 352, 160, 52],
        disclosure: [24, 352, 160, 52],
        verifier: [110, 516, 160, 64],
      },
      edges: {
        'versions->documents': { fromSide: 'r', toSide: 'r', fromAt: [356, 76], via: [[366, 76], [366, 378]], toAt: [356, 378], label: false },
        'claims->disclosure': { fromSide: 'l', toSide: 'l', fromAt: [24, 164], via: [[14, 164], [14, 378]], toAt: [24, 378], label: false },
        'answers->verifier': { fromSide: 'b', toSide: 't', fromAt: [190, 308], toAt: [190, 516], labelAt: [226, 470] },
      },
    },
  },
  legend: [['core', 'proof-bound record'], ['external', 'off the ledger'], ['client', 'offline verifier']],
  sources: [
    { repo: 'yano-x', path: 'products/dpp/client/src/main/java/org/yanoproject/x/dpp/client/PassportView.java',
      anchors: ['FLAG_REWRITTEN = "REWRITTEN"', 'FLAG_DANGLING = "DANGLING"', 'FLAG_FOREIGN_WRITER = "FOREIGN_WRITER"',
        'FLAG_EXPIRED = "EXPIRED"', 'FLAG_NOT_YET_VALID = "NOT_YET_VALID"', 'answer.entry().revision() > 1',
        'if (!currentSeen)', '"CONTENT_VERIFIED" : "FINALIZED"', 'APPROVAL_CONSUMPTION_FACT'] },
    { repo: 'yano-x', path: 'products/dpp/profile/src/main/java/org/yanoproject/x/dpp/profile/DppStarterProfile.java',
      anchors: ['"manufacturer"', '"claim-issuer"', '"certifier"', '"auditor"',
        'a certifier proposes, two auditors from distinct organizations approve'] },
    { repo: 'yano-x', path: 'docs/appchain/DPP_STARTER.md',
      anchors: ['`DRAFT`, `ACTIVE`, `INACTIVE`, `REPLACED`', 'dpp-disclosure-v1', 'the ledger orders them, never a clock'] },
  ],
};
