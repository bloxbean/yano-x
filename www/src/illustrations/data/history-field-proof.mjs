// How one protocol parameter becomes a compact proof in Cardano History:
// the read route, the state key, the canonical leaf, the primary-pair proof,
// and the claim a verifier checks. Routes and keys follow
// CardanoHistoryDomainApi and EpochParamsContract.

export default {
  id: 'history-field-proof',
  type: 'diagram',
  title: 'From a route to a field proof',
  tag: 'Concept',
  hint: 'Select a step to see what it carries and what it checks.',
  caption: 'One named field is one canonical leaf, so a proof of `key-deposit` does not depend on an era-specific '
    + 'parameter layout. The response carries proof coordinates; the proof bytes come from the state-proof API at '
    + 'the same height.',
  blocks: [
    {
      id: 'route', label: 'Read route', sub: 'epochs/{e}/parameters/\nfields/{id}', kind: 'client',
      detail: '`GET /api/v1/plugins/org.yanoproject.x.cardano-history/epochs/{epoch}/parameters/fields/{field_id}'
        + '?chain=<chain-id>`. The answer names the `committedHeight` and `stateRoot`, the typed value, whether the '
        + 'epoch is `complete`, whether the field was `found`, and proof coordinates, not proof bytes.',
    },
    {
      id: 'key', label: 'State key', sub: 'params/{e}/fields/{id}', kind: 'core',
      detail: 'The field lives under `params/{epoch}/fields/{field_id}` in the `l1-epoch-params-v1` component; the '
        + 'physical key carries the component prefix. The whole document is the state key '
        + '`params/{epoch}/document`: a key, not a route.',
    },
    {
      id: 'leaf', label: 'Canonical leaf', sub: 'typed value · id checked', kind: 'core',
      detail: 'The leaf is decoded with the canonical codec, and its field id must equal the one requested, or the '
        + 'route fails with “Protocol-parameter field identity mismatch”. A verifier never parses a '
        + 'hard-fork-specific positional array.',
    },
    {
      id: 'proof', label: 'Primary-pair proof', sub: 'fact + completeness', kind: 'core',
      detail: 'The coordinates name two physical keys at one height: the field\'s fact key and the epoch\'s '
        + 'completeness record, `params/{epoch}/meta`. Proving both under the same root shows the value, and that the '
        + 'epoch\'s dataset is complete. In Java, `ProofSubjects.epochProtocolParameterField` builds the subject.',
    },
    {
      id: 'verify', label: 'Verify the claim', sub: 'e.g. key-deposit = 2,000,000', kind: 'final',
      detail: 'First the proof: does the leaf sit under a root you trust? Then the claim: does the proven value meet '
        + 'the condition you asked about? An accepted result needs both, and the value is read from the proof, so '
        + 'editing presentation JSON cannot turn a false claim into a true one.',
    },
    {
      id: 'cardano', label: 'Cardano anchor', sub: 'trusted root, optional', kind: 'cardano',
      detail: 'The CLI\'s `verify --trusted-root` takes a root you obtained independently. Without an independently '
        + 'verified Cardano anchor, a bundle is `ROOT_VERIFIED_ANCHOR_UNCHECKED` (exit 5). The verifier ignores any '
        + 'trust source embedded in a bundle.',
    },
  ],
  edges: [
    { from: 'route', to: 'key' },
    { from: 'key', to: 'leaf' },
    { from: 'leaf', to: 'proof' },
    { from: 'proof', to: 'verify' },
    { from: 'cardano', to: 'verify', label: 'root', style: 'dashed' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 256,
      blocks: {
        route: [16, 32, 220, 72],
        key: [270, 36, 220, 64],
        leaf: [524, 36, 220, 64],
        proof: [524, 168, 220, 64],
        verify: [270, 168, 220, 64],
        cardano: [16, 168, 220, 64],
      },
      edges: {
        'leaf->proof': { fromSide: 'b', toSide: 't' },
        'proof->verify': { fromSide: 'l', toSide: 'r' },
        'cardano->verify': { fromSide: 'r', toSide: 'l', labelAt: [253, 186] },
      },
    },
    narrow: {
      width: 380,
      height: 560,
      blocks: {
        route: [40, 8, 300, 72],
        key: [40, 104, 300, 64],
        leaf: [40, 192, 300, 64],
        proof: [40, 280, 300, 64],
        verify: [40, 368, 300, 64],
        cardano: [40, 480, 300, 64],
      },
      edges: {
        'cardano->verify': { fromSide: 't', toSide: 'b', labelAt: [214, 456] },
      },
    },
  },
  legend: [['client', 'read route'], ['core', 'ledger state'], ['final', 'verified claim'], ['cardano', 'Cardano']],
  sources: [
    { repo: 'yano-x', path: 'products/cardano-history/runtime/src/main/java/org/yanoproject/x/history/CardanoHistoryDomainApi.java',
      anchors: ['"epochs/{epoch}/parameters/fields/{field_id}"', 'Protocol-parameter field identity mismatch',
        '{\\"kind\\":\\"primary-pair\\",\\"factPhysicalKey\\":\\"', '"completenessPhysicalKey\\":\\"',
        '",\\"committedHeight\\":"', 'EpochParamsContract.fieldKey(epoch, fieldId)', 'EpochParamsContract.metaKey(epoch)'] },
    { repo: 'yano-x', path: 'state-machines/stdlib-contracts/src/main/java/org/yanoproject/x/stdlib/contracts/EpochParamsContract.java',
      anchors: ['("params/" + epoch + "/document")', '("params/" + epoch + "/meta")', '("params/" + epoch + "/fields/" + fieldId)'] },
    { repo: 'yano-x', path: 'products/cardano-history/runtime/src/main/java/org/yanoproject/x/history/CardanoHistoryProduct.java',
      anchors: ['"org.yanoproject.x.cardano-history"', 'PARAMS_COMPONENT = "l1-epoch-params-v1"'] },
    { repo: 'yano-x', path: 'sdk/client/src/main/java/org/yanoproject/x/client/ProofSubjects.java',
      anchors: ['epochProtocolParameterField'] },
    { repo: 'yano-x', path: 'docs/appchain/CARDANO_HISTORY.md',
      anchors: ['`key-deposit == 2_000_000 lovelace`', '`ROOT_VERIFIED_ANCHOR_UNCHECKED`',
        'The verifier ignores any trust source embedded in a proof bundle',
        'editing presentation JSON cannot turn a false claim into a true one'] },
  ],
};
