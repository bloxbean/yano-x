// The Evidence product as one block diagram: an approved release, three
// connector effects after finality, receipts back into the ledger, and a
// Cardano anchor. Facts follow the evidence contracts, the demo harness, and
// the connector guide; see `sources`.

export default {
  id: 'evidence-flow',
  type: 'diagram',
  title: 'How Evidence publishes a document',
  tag: 'Concept',
  hint: 'Select a block to see what it does.',
  caption:
    'Every member records what was authorized and what each connector reported. Connector effects run only '
    + 'after finality, and each result comes back into the ledger once.',
  zones: [
    { id: 'ledger', kind: 'ledger', label: 'App ledger', contains: ['release', 'record'] },
  ],
  blocks: [
    {
      id: 'doc', label: 'Document', sub: 'exact bytes', kind: 'actor',
      detail: 'The exact bytes are staged in object storage, and their IPFS CID is computed, before anything is '
        + 'submitted. The ledger holds the document’s identity, version, and hashes, never the document.',
    },
    {
      id: 'release', label: 'Release', sub: 'registry + approval + release', kind: 'core',
      detail: 'In `evidence-v1-gated`, a registry entry and a member approval for the exact command come first. '
        + '`evidence.release.v1` then records the version and emits the storage effects in one atomic step. '
        + 'In `role-evidence`, named business actors approve instead.',
      link: { label: 'Tutorial 4', href: '/tutorials/04-evidence-publication/' },
    },
    {
      id: 'record', label: 'Evidence record', sub: 'status from receipts', kind: 'core',
      detail: 'Status is derived from authenticated receipts, never asserted. Storage moves from '
        + '`STORAGE_PENDING` to `STORAGE_READY`, or to `PARTIAL`, `STORAGE_FAILED`, or `EXPIRED`. Notification then '
        + 'moves through `NOTIFICATION_PENDING` to `READY`.',
    },
    {
      id: 'objput', label: 'object.put', kind: 'runtime',
      detail: 'Copies the staged bytes to an immutable, versioned destination and never overwrites a different '
        + 'object. The receipt records size, SHA-256, and destination fingerprint.',
    },
    {
      id: 'pin', label: 'ipfs.pin', kind: 'runtime',
      detail: 'Pins a known CID on a configured Kubo node. A confirmed pin is a point-in-time report, not proof '
        + 'of lasting availability.',
    },
    {
      id: 'kafkapub', label: 'kafka.publish', kind: 'runtime',
      detail: 'Publishes the canonical `evidence.available.v1` event once storage is ready. The receipt records '
        + 'the destination fingerprint, partition, and offset.',
    },
    { id: 's3', label: 'Object storage', sub: 'RustFS or S3', kind: 'external' },
    { id: 'ipfs', label: 'IPFS', sub: 'Kubo', kind: 'external' },
    { id: 'kafka', label: 'Kafka', kind: 'external' },
    {
      id: 'cardano', label: 'Cardano', sub: 'script anchor', kind: 'cardano',
      detail: 'A script anchor publishes a certified state root. The demo checks the anchor transaction on every '
        + 'member and that its datum commits to the certified root.',
      link: { label: 'Cardano anchoring', href: '/concepts/anchoring/' },
    },
  ],
  edges: [
    { from: 'doc', to: 'release', label: 'stage, then release' },
    { from: 'release', to: 'objput' },
    { from: 'release', to: 'pin' },
    { from: 'objput', to: 's3' },
    { from: 'pin', to: 'ipfs' },
    { from: 'pin', to: 'record', label: 'two results' },
    { from: 'record', to: 'kafkapub', label: 'storage ready' },
    { from: 'kafkapub', to: 'kafka' },
    { from: 'record', to: 'cardano', label: 'anchor', style: 'dashed' },
  ],
  layouts: {
    wide: {
      width: 760,
      height: 456,
      zones: {
        ledger: [16, 96, 728, 120],
      },
      blocks: {
        doc: [150, 16, 140, 52],
        cardano: [524, 16, 140, 52],
        release: [40, 136, 360, 64],
        record: [484, 136, 220, 64],
        objput: [40, 276, 170, 44],
        pin: [230, 276, 170, 44],
        kafkapub: [509, 276, 170, 44],
        s3: [40, 388, 170, 44],
        ipfs: [230, 388, 170, 44],
        kafka: [509, 388, 170, 44],
      },
      edges: {
        'doc->release': { fromSide: 'b', toSide: 't', labelAt: [220, 84] },
        'release->objput': { fromAt: [125, 200], toAt: [125, 276] },
        'release->pin': { fromAt: [315, 200], toAt: [315, 276] },
        'pin->record': { fromAt: [370, 276], via: [[370, 228], [520, 228]], toAt: [520, 200], labelAt: [445, 228] },
        'record->kafkapub': { fromSide: 'b', toSide: 't', labelAt: [650, 228] },
        'record->cardano': { fromSide: 't', toSide: 'b', labelAt: [628, 84] },
      },
    },
    narrow: {
      width: 380,
      height: 420,
      labels: {
        release: { sub: 'approved command' },
        s3: { label: 'S3 storage', sub: '' },
      },
      zones: {
        ledger: [8, 80, 364, 108],
      },
      blocks: {
        doc: [16, 12, 166, 48],
        cardano: [198, 12, 166, 48],
        release: [16, 116, 166, 56],
        record: [198, 116, 166, 56],
        objput: [16, 244, 108, 40],
        pin: [132, 244, 100, 40],
        kafkapub: [240, 244, 124, 40],
        s3: [16, 356, 108, 40],
        ipfs: [132, 356, 100, 40],
        kafka: [240, 356, 124, 40],
      },
      edges: {
        'doc->release': { fromAt: [132, 60], toAt: [132, 116], label: false },
        'release->objput': { fromAt: [70, 172], toAt: [70, 244] },
        'release->pin': { fromAt: [166, 172], toAt: [166, 244] },
        'objput->s3': { fromAt: [70, 284], toAt: [70, 356] },
        'pin->ipfs': { fromAt: [182, 284], toAt: [182, 356] },
        'pin->record': { fromAt: [212, 244], toAt: [212, 172], label: false },
        'record->kafkapub': { fromAt: [302, 172], toAt: [302, 244], label: false },
        'kafkapub->kafka': { fromAt: [302, 284], toAt: [302, 356] },
        'record->cardano': { fromSide: 't', toSide: 'b', label: false },
      },
    },
  },
  legend: [
    ['actor', 'your document'],
    ['core', 'deterministic, on every member'],
    ['runtime', 'connector effect'],
    ['external', 'external system'],
    ['cardano', 'Cardano'],
  ],
  sources: [
    { repo: 'yano-x',
      path: 'products/evidence/contracts/src/main/java/org/yanoproject/x/examples/evidence/state/EvidenceStatus.java',
      anchors: ['Business status derived only from immutable record fields and validated receipts.',
        'STORAGE_PENDING,', 'STORAGE_READY,', 'PARTIAL,', 'NOTIFICATION_PENDING,', 'READY,',
        'receipt.size() == command.size()', 'receipt.verifiedSha256(), command.digest()'] },
    { repo: 'yano-x', path: 'products/evidence/harness/README.md',
      anchors: ['`evidence-staging`', 'publish `evidence.available.v1` to Kafka',
        'The composite applies the document-trail append and'] },
    { repo: 'yano-x',
      path: 'products/evidence/profile/src/main/java/org/yanoproject/x/evidence/profile/EvidenceCompositePresets.java',
      anchors: ['EVIDENCE_V1_GATED = "evidence-v1-gated"'] },
    { repo: 'yano-x', path: 'docs/APP_CHAIN_USER_GUIDE.md',
      anchors: ['A confirmation records the destination fingerprint, partition, and offset.',
        'A confirmed pin is a point-in-time report', 'it does not overwrite or resurrect a conflicting key'] },
    { repo: 'yano-x', path: 'docs/appchain/tutorials/04-evidence-publication.md',
      anchors: ['The source document itself is not put into consensus state.'] },
  ],
};
