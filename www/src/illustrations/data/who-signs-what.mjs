// The keys and credentials around an app ledger: what each one signs or
// unlocks, what it cannot do, and what an attacker holding it gains. Facts
// follow the domain-roles guide, the user guide's security sections, the
// plugin loader, and the anchor validator.

export default {
  id: 'who-signs-what',
  type: 'diagram',
  title: 'Who signs what',
  tag: 'Concept',
  hint: 'Select a key to see what it can do, what it cannot, and what a thief would gain.',
  caption: 'Only member and actor signatures end up in what the ledger records and proves. The other secrets are '
    + 'node-local or belong to tooling: they control access and operations, and their misuse is limited by the '
    + 'checks members make for themselves.',
  zones: [
    { id: 'recorded', kind: 'ledger', label: 'Signatures the ledger records', contains: ['member', 'actor'] },
    { id: 'local', kind: 'runtime', label: 'Node-local secrets', contains: ['api', 'anchor', 'connector'] },
    { id: 'supply', kind: 'external', label: 'Software supply', contains: ['publisher'] },
  ],
  blocks: [
    {
      id: 'member', label: 'Member key', sub: 'one per node', kind: 'member',
      detail: '**Signs:** the envelope of every message its node accepts, PREPARE and COMMIT votes, certificates, '
        + 'script-anchor co-signatures, and snapshot manifests. Configured as `yano.app-chain.signing-key`. '
        + '**Cannot:** finalize a block alone when the threshold is above 1, or make other members accept a root '
        + 'they did not compute. **If stolen:** the thief can submit and vote as that one member; a threshold still '
        + 'needs other members. In the stock `approvals` and `balances` machines, the member key is also the '
        + 'business approver or account owner.',
    },
    {
      id: 'actor', label: 'Actor key', sub: 'a person or company', kind: 'actor',
      detail: '**Signs:** role-workflow statements inside message bodies, binding the chain, proposal, policy '
        + 'revision, payload hash, deadline, actor revision, key id, and clause. Held by the actor, never by a node. '
        + '**Cannot:** act outside the actor\'s roles, be replayed on another chain, proposal, or payload, or rewrite a '
        + 'finalized decision after the key is revoked. Key epochs are `ACTIVE`, `SUSPENDED`, or `REVOKED`.',
    },
    {
      id: 'api', label: 'API key', sub: 'REST access', kind: 'client',
      detail: 'Sent as `X-API-Key` and configured as `yano.app-chain.api.keys`. It controls REST access only; it is '
        + 'not a member or actor identity. A full key unlocks privileged routes, such as pause, membership and '
        + 'threshold administration, effect operations, and plugin operations; topic-scoped keys can only submit. '
        + 'The node signs every submission with its member key, so whoever can submit through a node speaks as that '
        + 'member on the allowed topics.',
    },
    {
      id: 'anchor', label: 'Anchor wallet', sub: 'pays Cardano fees', kind: 'cardano',
      detail: 'A hot wallet, `anchor.signing-key`, separate from member keys, on the anchor leader. In metadata mode '
        + 'the leader alone signs a data-only commitment. In script mode the validator also requires a threshold of '
        + 'member signatures, and members check the root against their own ledger before co-signing, so a '
        + 'compromised leader can stop anchoring but cannot advance the anchor alone.',
    },
    {
      id: 'connector', label: 'Connector secrets', sub: 'Kafka, S3, IPFS, …', kind: 'external',
      detail: 'Broker, bucket, Kubo, and payment credentials stay in each executor node\'s configuration, never in an '
        + 'effect payload or shared configuration. They grant access to the external system. An effect result is a '
        + 'member\'s attestation, not proof that the external system acted.',
    },
    {
      id: 'publisher', label: 'Publisher key', sub: 'signs plugin catalogs', kind: 'external',
      detail: '`./yano.sh appchain plugin sign` signs a plugin\'s catalog and manifest; `validate`, `init --trust-key`, '
        + 'and `doctor` check the signature, and the project lock pins the JAR digests. **The node does not check '
        + 'publisher signatures when it loads plugins**, and plugins are trusted in-process code with no sandbox. '
        + 'Install only plugins you trust, and use the plugin allow-list.',
    },
  ],
  edges: [],
  layouts: {
    wide: {
      width: 760,
      height: 252,
      zones: {
        recorded: [16, 16, 236, 220],
        local: [268, 16, 296, 220],
        supply: [580, 16, 164, 220],
      },
      blocks: {
        member: [32, 56, 204, 64],
        actor: [32, 148, 204, 64],
        api: [284, 52, 264, 48],
        anchor: [284, 112, 264, 48],
        connector: [284, 172, 264, 48],
        publisher: [592, 100, 140, 64],
      },
      labels: {
        publisher: { label: 'Publisher\nkey', sub: 'plugin catalogs' },
      },
    },
    narrow: {
      width: 380,
      height: 508,
      zones: {
        recorded: [8, 8, 364, 112],
        local: [8, 136, 364, 248],
        supply: [8, 400, 364, 100],
      },
      blocks: {
        member: [20, 44, 166, 64],
        actor: [194, 44, 166, 64],
        api: [40, 172, 300, 56],
        anchor: [40, 240, 300, 56],
        connector: [40, 308, 300, 56],
        publisher: [40, 432, 300, 56],
      },
      labels: {
        actor: { label: 'Actor key', sub: 'person or company' },
      },
    },
  },
  legend: [['member', 'member node key'], ['actor', 'business actor'], ['client', 'API caller'],
    ['cardano', 'Cardano wallet'], ['external', 'outside the ledger']],
  sources: [
    { repo: 'yano-x', path: 'docs/APP_CHAIN_DOMAIN_ROLES.md',
      anchors: ['An API key only controls REST access; it is neither a consensus-member identity nor a business actor identity.',
        'The relay member authenticates transport and consensus participation.',
        'cannot rewrite a finalized historical authorization'] },
    { repo: 'yano-x', path: 'docs/APP_CHAIN_USER_GUIDE.md',
      anchors: ['The node signs your submission with **its own** member key', 'hot wallet holding fee money only',
        'Use `approvals` when app-chain member keys are intentionally the business approvers',
        'A result is a *member attestation*, not an independently verified fact'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/config/YanoPropertyKeys.java',
      anchors: ['yano.app-chain.signing-key', 'yano.app-chain.api.keys', 'yano.app-chain.anchor.signing-key'] },
    { repo: 'yano-x', path: 'capabilities/role-workflow-contracts/src/main/java/org/yanoproject/x/roles/contracts/RecordStatus.java',
      anchors: ['ACTIVE(0), SUSPENDED(1), REVOKED(2)'] },
    { repo: 'yano-x', path: 'docs/appchain/OPTIONAL_CONNECTORS.md',
      anchors: ['Never put them into an effect payload', 'An accepted effect is not proof that the external system acted'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/plugins/PluginLoaderHandle.java',
      anchors: ['The parent is a trusted application boundary, not a sandbox boundary.',
        'parent-first loading does not isolate hostile code'] },
    { repo: 'yano-x', path: 'adr/app-layer/open_item.md',
      anchors: ['Signed manifests/JARs or external deployment attestations and provenance policy.'] },
    { repo: 'yano-x', path: 'docs/site/scaffold-sign-install.md',
      anchors: ['./yano.sh appchain plugin sign', 'the complete plugin-JAR digests'] },
  ],
};
