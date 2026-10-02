// The three answers an authenticated-map proof can give, and the bytes of the
// physical state key a proof is about. Facts follow AuthenticatedMapContract,
// the map state machine, and the composite component-key layout.

export default {
  id: 'authmap-presence',
  type: 'diagram',
  title: 'Absent, active or revoked',
  tag: 'Concept',
  hint: 'Select a status or a key part to see what it means.',
  caption: 'A proof distinguishes three states: never written, active, and revoked. The physical key is the map’s '
    + 'own key inside the composite state; the domain API returns it as `proofKey`, so you never build it by hand.',
  zones: [
    { id: 'status', kind: 'ledger', label: 'What a proof can show', contains: ['absent', 'active', 'revoked'] },
    { id: 'key', kind: 'core', label: 'Physical state key, in byte order',
      contains: ['domain', 'component', 'length', 'version', 'collection', 'appkey'] },
  ],
  blocks: [
    {
      id: 'absent', label: 'ABSENT', sub: 'exclusion proof', kind: 'external',
      detail: 'No entry and no tombstone exist for this collection and key. The proof is an exclusion proof. Only PUT '
        + 'and PUT_IF_ABSENT can act on an absent key; any other operation is rejected with ABSENT (5).',
    },
    {
      id: 'active', label: 'ACTIVE', sub: 'entry included', kind: 'final',
      detail: 'The record holds status, revision, optional controller, value, logical value hash, creation height and '
        + 'last-mutation height. The logical value hash is Blake2b-256 of `yano-authenticated-map-value-v1`, a zero '
        + 'byte, the 4-byte value length, and the value.',
    },
    {
      id: 'revoked', label: 'REVOKED', sub: 'tombstone included', kind: 'fail',
      detail: 'REVOKE keeps the record as a tombstone: status REVOKED, revision plus one, value bytes removed, last '
        + 'logical value hash kept. Its proof is an inclusion proof, so revoked is provably different from never '
        + 'written. Only RESTORE acts on it, and only when the collection allows restore.',
    },
    {
      id: 'domain', label: 'Composite domain', sub: 'yano-composite-state-v1', kind: 'core',
      detail: 'Every component of a composite machine keeps its keys under this prefix: the ASCII bytes '
        + '`yano-composite-state-v1` followed by a zero byte.',
    },
    {
      id: 'component', label: 'Component id', sub: '1-byte length + id', kind: 'core',
      detail: 'One length byte, then the component id `authenticated-map`.',
    },
    {
      id: 'length', label: 'Local key length', sub: 'u16', kind: 'core',
      detail: 'Two bytes: the length of the map’s own key, which follows.',
    },
    {
      id: 'version', label: 'Version and kind', sub: '01 01', kind: 'ledger',
      detail: 'The map’s own key starts here. `01` is the key codec version; `01` marks a map entry. Framework records '
        + 'such as receipts use kind `00`, so no application key can address them.',
    },
    {
      id: 'collection', label: 'Collection', sub: 'u16 length + ASCII id', kind: 'ledger',
      detail: 'Two-byte length, then the collection id: at most 64 bytes of lowercase letters, digits, `.`, `_` and `-`.',
    },
    {
      id: 'appkey', label: 'Application key', sub: 'u32 length + bytes', kind: 'ledger',
      detail: 'Four-byte length, then the application key: 1 to 128 bytes, and no more than the collection’s '
        + '`maxKeyBytes`.',
    },
  ],
  edges: [
    { from: 'absent', to: 'active', label: 'PUT' },
    { from: 'active', to: 'revoked', label: 'REVOKE' },
    { from: 'revoked', to: 'active', label: 'RESTORE', style: 'dashed' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 400,
      zones: {
        status: [16, 12, 728, 150],
        key: [16, 186, 728, 200],
      },
      blocks: {
        absent: [40, 56, 170, 76],
        active: [295, 56, 170, 76],
        revoked: [550, 56, 170, 76],
        domain: [36, 230, 216, 64],
        component: [272, 230, 216, 64],
        length: [508, 230, 216, 64],
        version: [36, 306, 216, 64],
        collection: [272, 306, 216, 64],
        appkey: [508, 306, 216, 64],
      },
      edges: {
        'absent->active': { fromSide: 'r', toSide: 'l', labelAt: [252, 84] },
        'active->revoked': { fromSide: 'r', toSide: 'l', fromAt: [465, 78], toAt: [550, 78], labelAt: [507, 68] },
        'revoked->active': { fromSide: 'l', toSide: 'r', fromAt: [550, 112], toAt: [465, 112], labelAt: [507, 124] },
      },
    },
    narrow: {
      width: 380,
      height: 590,
      zones: {
        status: [8, 8, 364, 300],
        key: [8, 324, 364, 250],
      },
      blocks: {
        absent: [100, 48, 180, 56],
        active: [100, 140, 180, 56],
        revoked: [100, 232, 180, 56],
        domain: [16, 364, 170, 56],
        component: [194, 364, 170, 56],
        length: [16, 430, 170, 56],
        version: [194, 430, 170, 56],
        collection: [16, 496, 170, 56],
        appkey: [194, 496, 170, 56],
      },
      edges: {
        'absent->active': { fromSide: 'b', toSide: 't', labelAt: [215, 122] },
        'active->revoked': { fromSide: 'b', toSide: 't', fromAt: [150, 196], toAt: [150, 232], labelAt: [96, 214] },
        'revoked->active': { fromSide: 't', toSide: 'b', fromAt: [230, 232], toAt: [230, 196], labelAt: [290, 214] },
      },
    },
  },
  legend: [
    ['external', 'absent'],
    ['final', 'active'],
    ['fail', 'revoked'],
    ['core', 'composite prefix'],
    ['ledger', 'map key'],
  ],
  sources: [
    { repo: 'yano-x', path: 'state-machines/stdlib-contracts/src/main/java/org/yanoproject/x/stdlib/contracts/AuthenticatedMapContract.java',
      anchors: ['PRESENCE_ABSENT = 0', 'PRESENCE_ACTIVE = 1', 'PRESENCE_REVOKED = 2', 'KEY_CODEC_VERSION = 1',
        'NAMESPACE_KIND_FRAMEWORK = 0', 'NAMESPACE_KIND_AUTHENTICATED_MAP = 1', 'MAX_COLLECTION_ID_BYTES = 64',
        'MAX_APPLICATION_KEY_BYTES = 128', '"yano-authenticated-map-value-v1\\0"',
        'input.put(VALUE_HASH_DOMAIN).putInt(bounded.length).put(bounded);',
        '{@code version:u8 || namespace:u8 || collectionLen:u16 || collection || keyLen:u32 || key}',
        'Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}")', '"revoked entry must contain a canonical empty tombstone value"'] },
    { repo: 'yano-x', path: 'composition/contracts/src/main/java/org/yanoproject/x/composite/contracts/CompositeCommitmentV1.java',
      anchors: ['COMPONENT_DOMAIN = "yano-composite-state-v1\\0"',
        '.put(COMPONENT_DOMAIN).put((byte) id.length) .put(id).putShort((short) local.length).put(local)'] },
    { repo: 'yano-x', path: 'sdk/client/src/main/java/org/yanoproject/x/client/StdlibAppChainClient.java',
      anchors: ['the same key the bounded domain API reports as {@code proofKey}'] },
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/AuthenticatedMapStateMachine.java',
      anchors: ['throw failure(AuthenticatedMapContract.ERROR_ABSENT);', 'if (!descriptor.restoreAllowed())'] },
  ],
};
