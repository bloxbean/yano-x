// What `appchain bindings profile-check` does, in order, and what its verdict
// does and does not mean. Steps follow BindingProfileCheck.run/parse/check and
// BindingCatalogSession.validateCatalog.

const COMMAND = './yano.sh appchain bindings profile-check --profiles retained-profiles.json '
  + '--context context.json --plugins-directory /absolute/path/to/candidate/plugins';

const gather = {
  title: 'Gather the profiles',
  text: 'Export the retained chain’s canonical composite profiles from records you trust: genesis first, then every '
    + 'historical or target profile you need. That is 1 to 64 hex strings. The checker does not authenticate where '
    + 'they came from, and it opens no retained store.',
  command: COMMAND,
  wires: [{ from: 'you', to: 'check', label: 'profiles + context + plugins' }],
  cards: { you: { title: '`retained-profiles.json`', detail: 'genesis first' } },
};

const decode = (ok) => ({
  title: 'Decode each profile',
  text: ok
    ? 'Each entry must be a declarative schema-v2 profile with explicit binding IR, and that IR must decode with '
      + 'this build. Duplicates are refused.'
    : 'This IR has admission rules written before ADR-031.4 changed the rule layout in place. It fails to decode '
      + 'with “binding IR predates ADR-031.4”. The execution version still reads 1.2.0, so the version number gives '
      + 'no warning.',
  checks: [
    { label: 'Declarative schema-v2 profile with explicit IR', ok: true },
    ok ? { label: 'IR decodes with this build', ok: true }
      : { label: 'IR decodes with this build', ok: false, code: 'predates ADR-031.4' },
    { label: 'No duplicate profile', ok: ok ? true : null },
  ],
  cards: { check: ok ? { title: 'Profiles decoded', tone: 'ok' } : { title: 'Decode failed', tone: 'fail' } },
});

const context = (ok) => ({
  title: 'Check the context',
  text: 'Use the chain’s original explicit context. More than one profile needs `membership.mode` set to '
    + '`governed` in its settings; the checker then builds a governed catalog itself. It changes no deployed '
    + 'chain’s mode.',
  checks: [{ label: 'Several profiles: `membership.mode` is `governed`', ok }],
  cards: { check: ok ? { title: 'Context accepted', tone: 'ok' }
    : { title: 'Catalog not built', detail: '`multiple profiles require explicit membership.mode=governed`', tone: 'fail' } },
});

const build = {
  title: 'Build the candidate catalog',
  text: 'The checker opens the candidate plugin directory through the real host catalog and constructs the '
    + '`declarative-composite` provider with every profile’s IR. This runs the installed plugins’ code, '
    + 'unsandboxed: check only bundles you trust.',
  wires: [{ from: 'check', to: 'catalog', label: 'construct with all IR' }],
  cards: { catalog: { title: 'Candidate provider', detail: 'constructed', tone: 'ok' } },
};

const compare = (same) => ({
  title: 'Compare byte for byte',
  text: 'For each profile, a synthetic read-only marker holds that profile, and the candidate answers the query '
    + '`composite/active-profile-v1`. Its bytes must equal the bytes you supplied. '
    + (same ? 'They do.' : 'Here they differ: for example, a machine’s `applicationVersion` or its query subjects '
      + 'changed, and both are part of the component descriptor.'),
  checks: [{ label: 'Candidate returns the same canonical profile bytes', ok: same }],
  wires: [{ from: 'catalog', to: 'check', label: same ? 'same bytes' : 'different bytes', tone: same ? 'ok' : 'fail' }],
});

export default {
  id: 'upgrade-preflight',
  type: 'steps',
  title: 'What profile-check proves',
  intro: 'a read-only preflight before you change plugin bundles for a retained chain. Step through a passing '
    + 'check, then the ways it fails.',
  lanes: [
    { id: 'you', label: 'Operator', note: 'before an upgrade', kind: 'actor' },
    { id: 'check', label: 'profile-check', note: 'offline tool', kind: 'runtime' },
    { id: 'catalog', label: 'Candidate catalog', note: 'your new plugins', kind: 'core' },
    { id: 'report', label: 'Report', note: 'JSON + exit code', kind: 'ledger' },
  ],
  scenarios: [
    {
      id: 'reproduces',
      label: 'Every profile reproduces',
      steps: [gather, decode(true), context(true), build, compare(true), {
        title: 'Read the verdict',
        text: 'Exit **0** and `reproducesProfiles: true`: every supplied profile is reconstructible byte for byte. '
          + 'That is all it proves. It is not replay, migration, proof verification or semantic equivalence.',
        cards: { report: { title: 'Exit 0', detail: '`reproducesProfiles: true`', tone: 'final' } },
      }],
    },
    {
      id: 'predates',
      label: 'Rules from before ADR-031.4',
      summary: 'A profile written by an earlier experimental runtime.',
      steps: [gather, decode(false), {
        title: 'Exit 2',
        text: 'The tool reports “Profile input is invalid” and exits **2**. This build cannot run that chain. '
          + 'Re-create an experimental chain from its YAML, or keep it on its exact qualified runtime.',
        cards: { report: { title: 'Exit 2', detail: 'invalid input', tone: 'fail' } },
      }],
    },
    {
      id: 'differs',
      label: 'The candidate builds different bytes',
      summary: 'The new bundles construct, but not the same profile.',
      steps: [gather, decode(true), context(true), build, compare(false), {
        title: 'Exit 2',
        text: 'That profile reports `reproducesProfile: false` with “candidate returned different canonical profile '
          + 'bytes”, and the tool exits **2**. Do not deploy these bundles to the retained chain.',
        cards: { report: { title: 'Exit 2', detail: '`reproducesProfiles: false`', tone: 'fail' } },
      }],
    },
    {
      id: 'fixed-mode',
      label: 'Two profiles, fixed membership',
      summary: 'The context still describes a fixed-membership chain.',
      steps: [gather, decode(true), context(false), {
        title: 'Exit 2',
        text: 'Every profile reports “candidate catalog construction failed”, and the tool exits **2**. Supply the '
          + 'governed context the chain actually uses.',
        cards: { report: { title: 'Exit 2', detail: 'construction failed', tone: 'fail' } },
      }],
    },
  ],
  legend: [
    ['actor', 'operator'],
    ['runtime', 'offline tool'],
    ['core', 'candidate plugins'],
    ['final', 'passes'],
    ['fail', 'fails'],
  ],
  sources: [
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/BindingProfileCheck.java',
      anchors: ['profiles must be a JSON array of 1-64 canonical profile hex strings',
        'profile must be a declarative schema-v2 profile with explicit IR', 'duplicate canonical profile',
        'candidate catalog construction failed: ', 'machine.query("composite/active-profile-v1", new byte[0], marker)',
        'candidate returned different canonical profile bytes', 'return report.reproducesProfiles() ? 0 : 2;',
        'Profile input is invalid: ', 'Profile reconstruction only; not migration, replay qualification, ',
        'never opens retained stores or invokes machine init/apply'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/BindingCatalogSession.java',
      anchors: ['multiple profiles require explicit membership.mode=governed', 'machines.composite.profile-mode", "governed"'] },
    { repo: 'yano-x', path: 'composition/contracts/src/main/java/org/yanoproject/x/composite/contracts/BindingIrV1.java',
      anchors: ['binding IR predates ADR-031.4: '] },
    { repo: 'yano-x', path: 'composition/runtime/src/main/java/org/yanoproject/x/composite/bindings/DeclarativeCompositeProvider.java',
      anchors: ['EXECUTION_VERSION = "1.2.0"'] },
    { repo: 'yano-x', path: 'docs/appchain/DECLARATIVE_BINDINGS_UPGRADES.md',
      anchors: ['Machine `applicationVersion`', 'Machine query subjects'] },
  ],
};
