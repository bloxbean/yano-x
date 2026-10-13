// A composite profile, using the stock `evidence-v1-gated` preset as the
// example: four namespaced components, two ordered workflows, one state root.
// Facts follow Yano X's CompositeProfile, CompositeStateMachine, and
// EvidenceCompositePresets; see `sources`.

const COMPONENT = 'Each component declares its version, configuration id, topics, query paths, effect quota, and the '
  + 'heights at which it is active. It reads and writes only its own namespace.';

export default {
  id: 'composite-profile',
  type: 'diagram',
  title: 'A composite profile',
  tag: 'Concept',
  hint: 'Select a component or workflow to see what the profile commits for it.',
  caption: 'Example: the stock `evidence-v1-gated` preset. The profile is canonical data, committed to the state, '
    + 'so every member runs the same components in the same order.',
  zones: [
    {
      id: 'profile', kind: 'ledger', label: 'Composite profile · example preset',
      contains: ['registry', 'approvals', 'doc-trail', 'evidence', 'notify', 'release'],
    },
  ],
  blocks: [
    {
      id: 'registry', label: 'registry', sub: 'registry.command.v1', kind: 'core',
      detail: `The \`kv-registry\` machine as component \`registry\`, on topic \`registry.command.v1\`. ${COMPONENT}`,
    },
    {
      id: 'approvals', label: 'approvals', sub: 'approvals.command.v1', kind: 'core',
      detail: `The \`approvals\` machine as component \`approvals\`, on topic \`approvals.command.v1\`, with its `
        + `on-approved effect disabled in this preset. ${COMPONENT}`,
    },
    {
      id: 'doc-trail', label: 'doc-trail', sub: 'doc-trail.command.v1', kind: 'core',
      detail: `The \`doc-trail\` machine as component \`doc-trail\`, on topic \`doc-trail.command.v1\`. ${COMPONENT}`,
    },
    {
      id: 'evidence', label: 'evidence', sub: 'no public topic', kind: 'core',
      detail: 'The evidence registry as component `evidence`. In the gated preset it has no public topic: evidence '
        + 'changes only through the two workflows. It answers the `evidence/get` query.',
    },
    {
      id: 'notify', label: 'evidence-notify', sub: 'workflow 1 · evidence.command.v1', kind: 'leader',
      detail: 'Runs first, for messages on `evidence.command.v1`. Its only participant is the `evidence` component.',
    },
    {
      id: 'release', label: 'evidence-release', sub: 'workflow 2 · evidence.release.v1', kind: 'leader',
      detail: 'Runs second, for messages on `evidence.release.v1`, and coordinates all four components in one '
        + 'atomic step. Workflows are the only way to change several components together. Workflow order is '
        + 'execution order, so it is part of the committed profile.',
    },
    {
      id: 'root', label: 'One state root', sub: 'profile marker from height 1', kind: 'final',
      detail: 'All components and workflows write to one authenticated state. In fixed mode the profile’s canonical '
        + 'bytes are stored as a marker at height 1 and checked on restart and at the start of every block, so a '
        + 'different order or configuration fails instead of silently forming another application. Governed mode '
        + 'records profile epochs instead.',
    },
  ],
  edges: [
    { from: 'profile', to: 'root', label: 'one atomic transition per block' },
  ],
  legend: [
    ['core', 'component'],
    ['leader', 'workflow, in committed order'],
  ],
  layouts: {
    wide: {
      width: 760,
      height: 332,
      zones: { profile: [16, 16, 728, 210] },
      blocks: {
        registry: [32, 50, 164, 60],
        approvals: [208, 50, 164, 60],
        'doc-trail': [384, 50, 164, 60],
        evidence: [560, 50, 164, 60],
        notify: [32, 136, 340, 60],
        release: [384, 136, 340, 60],
        root: [260, 264, 240, 56],
      },
      edges: {
        'profile->root': { fromSide: 'b', toSide: 't', fromAt: [380, 226], toAt: [380, 264], labelAt: [380, 245] },
      },
    },
    narrow: {
      width: 380,
      height: 408,
      zones: { profile: [8, 8, 364, 312] },
      blocks: {
        registry: [20, 42, 164, 60],
        approvals: [196, 42, 164, 60],
        'doc-trail': [20, 114, 164, 60],
        evidence: [196, 114, 164, 60],
        notify: [20, 186, 340, 56],
        release: [20, 252, 340, 56],
        root: [70, 344, 240, 56],
      },
      edges: {
        'profile->root': { fromSide: 'b', toSide: 't', fromAt: [190, 320], toAt: [190, 344], label: false },
      },
    },
  },
  sources: [
    { repo: 'yano-x', path: 'products/evidence/profile/src/main/java/org/yanoproject/x/evidence/profile/EvidenceCompositePresets.java',
      anchors: ['EVIDENCE_V1_GATED = "evidence-v1-gated"', 'REGISTRY_TOPIC = "registry.command.v1"',
        'APPROVALS_TOPIC = "approvals.command.v1"', 'DOC_TRAIL_TOPIC = "doc-trail.command.v1"',
        'gated ? List.of() : List.of(EvidenceContract.COMMAND_TOPIC)', '"on-approved-effect-disabled-v1"',
        'workflows = List.of(notify, release);'] },
    { repo: 'yano-x', path: 'products/evidence/profile/src/main/java/org/yanoproject/x/evidence/profile/EvidenceNotifyWorkflow.java',
      anchors: ['ID = "evidence-notify"'] },
    { repo: 'yano-x', path: 'products/evidence/profile/src/main/java/org/yanoproject/x/evidence/profile/EvidenceReleaseWorkflow.java',
      anchors: ['ID = "evidence-release"'] },
    { repo: 'yano-x', path: 'products/evidence/contracts/src/main/java/org/yanoproject/x/evidence/profile/contracts/EvidenceReleaseCommandV1.java',
      anchors: ['TOPIC = "evidence.release.v1"'] },
    { repo: 'yano-x', path: 'products/evidence/contracts/src/main/java/org/yanoproject/x/examples/evidence/EvidenceContract.java',
      anchors: ['COMMAND_TOPIC = "evidence.command.v1"', 'GET_QUERY_PATH = "evidence/get"'] },
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/ComponentDescriptor.java',
      anchors: ['String componentId,', 'String semanticVersion,', 'String configurationId,', 'long fromHeight,',
        'long untilHeight,', 'List<String> topics,', 'List<String> queryPaths,', 'int maxEffectsPerBlock'] },
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/CompositeProfile.java',
      anchors: ['Workflow order is execution order and therefore consensus-relevant.'] },
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/CompositeStateMachine.java',
      anchors: ['composite profile marker is absent after genesis height', 'verifyRetainedMarker(state);',
        'retained composite profile marker does not match effective profile'] },
  ],
};
