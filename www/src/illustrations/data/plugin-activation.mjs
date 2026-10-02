// How a JVM node turns the JARs in yano.plugins.directory into an immutable
// plugin catalog at start-up, in the order the host checks them
// (PluginLoaderHandle, PluginRuntimeEnvironment.discoverInputs, PluginArtifactScanner,
// DirectoryPluginArtifactValidator and PluginCatalogBuilder in the Yano runtime;
// ADR-011.2 §5 and §11.1). The "What if" scenarios end at the exact failure.

const SCAN_CHECKS = [
  'Exactly one qualified schema-v1 manifest',
  'No packaged `org/yanoproject/api/**` class',
  'Manifest contributions and ServiceLoader entries agree',
  'No JAR-manifest `Class-Path`',
];
const scanChecks = (failAt = -1, code) => SCAN_CHECKS.map((label, i) => ({
  label,
  ok: failAt < 0 || i < failAt ? true : i === failAt ? false : null,
  ...(i === failAt && code ? { code } : {}),
}));

const capture = {
  title: 'Capture',
  text: 'At start-up the node copies every JAR in `yano.plugins.directory` into a private snapshot before it reads '
    + 'or loads anything: at most 256 JARs, 1 GiB each and 4 GiB in total. Replacing a JAR later does not change '
    + 'the running node.',
  wires: [{ from: 'jar', to: 'scan', label: 'snapshot copy' }],
  cards: { jar: { title: '`shipment-yano-plugin.jar`', detail: 'in `plugins/`' } },
};

const scan = {
  title: 'Scan',
  text: 'The scanner reads each snapshot as data and loads no code. It needs one manifest, no copy of the host '
    + 'API, ServiceLoader entries that match the manifest exactly, and no `Class-Path` outside the recorded JARs.',
  checks: scanChecks(),
  cards: { scan: { title: 'Scanned', detail: 'manifest + providers', tone: 'ok' } },
};

const apiRange = {
  title: 'Check the API range',
  text: 'The manifest’s `yanoApi {min, max, minLevel}` must include the host’s API major, 3, and the host’s level, '
    + '12, must be at least `minLevel`. There is a maximum major but no maximum level.',
  checks: [{ label: '`min` ≤ 3 ≤ `max`, and 12 ≥ `minLevel`', ok: true }],
  cards: { scan: { title: 'API compatible', detail: 'major 3 · level 12', tone: 'ok' } },
};

const isolated = {
  title: 'Validate in isolation',
  text: 'Each JAR is opened alone in a short-lived loader to resolve its provider types, even JARs that policy will '
    + 'filter out. No provider constructor or static initializer runs.',
  cards: { scan: { title: 'Types resolved', detail: 'one JAR at a time', tone: 'ok' } },
};

const policy = {
  title: 'Apply policy',
  text: '`yano.plugins.allow-list` and `yano.plugins.deny-list` select bundles. A filtered bundle stays in the '
    + 'inventory but never reaches the executable loader. A JAR that mixes selected and filtered bundles is refused.',
  cards: { catalog: { title: 'Selected', detail: 'by allow and deny lists' } },
};

const sharedLoader = {
  title: 'Build the shared loader',
  text: 'Selected snapshots join one parent-first class loader, in SHA-256 order. Host classes win over any plugin '
    + 'copy. Plugins are trusted, in-process code: this loader is not a sandbox.',
  wires: [{ from: 'scan', to: 'loader', label: 'selected JARs only' }],
  cards: { loader: { title: 'One shared loader', detail: 'parent-first', tone: 'leader' } },
};

const correlate = {
  title: 'Correlate providers',
  text: 'On the shared loader, every manifested provider must have a matching ServiceLoader entry, and it must come '
    + 'from its own JAR.',
  checks: [{ label: 'Each manifested provider has a ServiceLoader entry from its own JAR', ok: true }],
  cards: { loader: { title: 'Providers matched', tone: 'ok' } },
};

const order = {
  title: 'Order bundles',
  text: 'Declared bundle dependencies, versions and cycles are checked, and the selected bundles are put in '
    + 'dependency order.',
  cards: { catalog: { title: 'Dependency order', detail: 'no cycles', tone: 'ok' } },
};

const fingerprint = {
  title: 'Fingerprint',
  text: 'Every JAR is hashed again and must be unchanged. The catalog fingerprint covers the host API level and each '
    + 'bundle’s id, version, digest and `yanoApi`. The node logs it at start-up and reports it as '
    + '`pluginCatalogFingerprint` in the chain identity.',
  cards: { catalog: { title: 'Fingerprint', detail: '`pluginCatalogFingerprint`', tone: 'ok' } },
};

const publish = {
  title: 'Publish and activate',
  text: 'The immutable registry is published. Only then do lifecycle callbacks run, and each provider is constructed '
    + 'when the node first needs it, for example when a chain selects its state machine.',
  wires: [{ from: 'catalog', to: 'node', label: 'immutable registry' }],
  cards: { node: { title: 'Running', detail: 'providers built on demand', tone: 'final' } },
};

const fatal = (title, text, detail) => ({
  title,
  text: `${text} Any structural catalog error stops start-up, whatever the contribution.`,
  cards: { node: { title: 'Start-up fails', detail, tone: 'fail' } },
});

export default {
  id: 'plugin-activation',
  type: 'steps',
  title: 'From JAR to running provider',
  intro: 'a JVM node starts with one plugin JAR in its plugin directory. Step through the checks in the order the host '
    + 'runs them, then try a “What if” to see which check stops a broken bundle.',
  lanes: [
    { id: 'jar', label: 'Plugin JAR', note: 'in yano.plugins.directory', kind: 'client' },
    { id: 'scan', label: 'Scanner', note: 'reads bytes, loads no code', kind: 'runtime' },
    { id: 'loader', label: 'Shared loader', note: 'parent-first', kind: 'core' },
    { id: 'catalog', label: 'Catalog', note: 'registry + fingerprint', kind: 'ledger' },
    { id: 'node', label: 'Node', note: 'lifecycle', kind: 'member' },
  ],
  scenarios: [
    {
      id: 'valid',
      label: 'A valid bundle',
      steps: [capture, scan, apiRange, isolated, policy, sharedLoader, correlate, order, fingerprint, publish],
    },
    {
      id: 'host-class',
      label: 'It embeds a host API class',
      summary: 'The bundle was packaged with a copy of `yano-core-api` inside it.',
      steps: [capture, {
        ...scan,
        text: 'The JAR contains `org/yanoproject/api/**` classes. Two copies of one interface would break type '
          + 'identity, so the scanner refuses the artifact before any code is loaded.',
        checks: scanChecks(1, 'plugin artifact packages Yano API class'),
        cards: { scan: { title: 'Invalid artifact', detail: 'packages a Yano API class', tone: 'fail' } },
      }, fatal('Start-up fails', 'Keep the host API `compileOnly` and rebuild. `yano-plugins validate` reports the same '
        + 'error offline, with exit code 2.', 'invalid plugin artifact')],
    },
    {
      id: 'service-entry',
      label: 'A ServiceLoader entry is missing',
      summary: 'The manifest declares a provider that `META-INF/services` does not list.',
      steps: [capture, {
        ...scan,
        text: 'The manifest names a contribution whose provider has no `META-INF/services` entry. The scanner '
          + 'requires the two to agree exactly, in the same artifact.',
        checks: scanChecks(2, 'ServiceLoader entries differ'),
        cards: { scan: { title: 'Invalid artifact', detail: '`missing=[…]`', tone: 'fail' } },
      }, fatal('Start-up fails', 'Add the service file entry, or remove the contribution from the manifest.',
        'entries differ')],
    },
    {
      id: 'min-level',
      label: 'minLevel is too high',
      summary: 'The manifest declares `minLevel: 13`, but this host is at level 12.',
      steps: [capture, scan, {
        ...apiRange,
        text: 'The host’s level, 12, is below the bundle’s `minLevel` of 13. The bundle needs API that this host '
          + 'does not have.',
        checks: [{ label: '`min` ≤ 3 ≤ `max`, and 12 ≥ `minLevel`', ok: false, code: 'level 12 < 13' }],
        cards: { scan: { title: 'Incompatible', detail: 'needs level 13', tone: 'fail' } },
      }, fatal('Start-up fails', 'The node reports that the bundle does not support Yano plugin API major 3 level 12. '
        + 'Use the host version the bundle was built for.', 'API level too low')],
    },
    {
      id: 'drift',
      label: 'A different JAR on one member',
      summary: 'Member C has a rebuilt JAR with the same id and version as members A and B.',
      steps: [capture, scan, apiRange, isolated, policy, sharedLoader, correlate, order, {
        ...fingerprint,
        text: 'Every check passes on member C. Only its fingerprint differs from A and B, because the JAR’s digest '
          + 'differs. Nothing at start-up compares fingerprints across members.',
        cards: { catalog: { title: 'Fingerprint differs', detail: 'from members A and B', tone: 'pending' } },
      }, {
        title: 'Diverge',
        text: 'Member C starts. If its code computes a different result, its state root differs and it does not vote '
          + 'for that block, so it stalls. Catch drift before traffic: `appchain drift` compares '
          + '`cluster.plugin-catalog` across members, and `appchain doctor` checks each JAR’s digest against the '
          + 'project lock.',
        cards: { node: { title: 'Stalls on divergence', detail: 'drift: `DRIFT_DETECTED`', tone: 'fail' } },
      }],
    },
  ],
  legend: [
    ['client', 'artifact'],
    ['runtime', 'data-only checks'],
    ['core', 'executable loader'],
    ['final', 'running'],
    ['fail', 'refused'],
  ],
  sources: [
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/plugins/PluginLoaderHandle.java',
      anchors: ['MAX_PLUGIN_JARS = 256', 'MAX_PLUGIN_JAR_BYTES = 1024L * 1024L * 1024L',
        'MAX_PLUGIN_SNAPSHOT_BYTES = 4L * 1024L * 1024L * 1024L',
        'The parent is a trusted application boundary, not a sandbox boundary.', 'Directory loaders are parent-first'] },
    { repo: 'yano', path: 'plugin-catalog/src/main/java/org/yanoproject/catalog/PluginArtifactScanner.java',
      anchors: ['API_CLASS_PREFIX = "org/yanoproject/api/"', 'plugin artifact packages Yano API class',
        'manifest and supported ServiceLoader entries differ; missing=', 'JAR manifest Class-Path is unsupported',
        'artifact contains more than one qualified plugin manifest', 'without loading provider code'] },
    { repo: 'yano', path: 'plugin-catalog/src/main/java/org/yanoproject/catalog/YanoApiRange.java',
      anchors: ['return apiMajor >= min && apiMajor <= max && apiLevel >= minLevel;'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/plugin/PluginApiVersion.java',
      anchors: ['CURRENT_MAJOR = 3', 'CURRENT_LEVEL = 12'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/plugins/DirectoryPluginArtifactValidator.java',
      anchors: ['Provider constructors and static initializers are not invoked.'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/plugins/PluginRuntimeEnvironment.java',
      anchors: ['PluginCatalogBuilder.validateManifestCompatibility(inputs);', 'validateDirectoryArtifacts(handle, inputs);',
        'handle.activateDirectoryArtifacts(', 'One directory artifact cannot mix selected and policy-filtered bundles',
        'YANO_PLUGIN_CATALOG_PROVENANCE'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/plugins/PluginCatalogBuilder.java',
      anchors: ['does not support Yano plugin API major', 'has no matching ServiceLoader entry',
        'verifyProviderOrigin(key, provider, input);', 'validateSelectedDependencies(selected);',
        'topologicalOrder(selected);', 'verifyDirectoryArtifactsUnchanged(inputs);',
        'data.writeInt(candidate.manifest().yanoApi().minLevel());'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/config/YanoPropertyKeys.java',
      anchors: ['"yano.plugins.allow-list"', '"yano.plugins.deny-list"', '"yano.plugins.directory"'] },
    { repo: 'yano', path: 'adr/app-layer/011.2-manifested-bundle-catalog.md',
      anchors: ['Any structural catalog error is startup-fatal regardless of contribution',
        'in complete-SHA-256 order', 'lazy selected-provider construction at required activation point',
        'No `NodePlugin` lifecycle callback or typed contribution factory method runs'] },
    { repo: 'yano', path: 'app/src/main/java/org/yanoproject/app/api/appchain/AppChainResource.java',
      anchors: ['"pluginCatalogFingerprint"'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainDriftClient.java',
      anchors: ['"cluster.plugin-catalog"', 'DRIFT_DETECTED'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md', anchors: ['requires byte-identical `stateRoot`'] },
  ],
};
