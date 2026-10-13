// Tutorial 9: from a generated project to a reviewed pilot. The rail follows
// the project CLI's real status markers (init, render, validate, doctor,
// gitops), and one step sorts the secrets out of the project. Facts come from
// the devtools project CLI, renderer, lifecycle, and capability catalog.

const INIT_COMMAND = './yano.sh appchain init --recipe audit-log --network preprod \\\n'
  + '  --members 3 --finality all --sequencing rotating --runtime jvm \\\n'
  + '  --deployment host --name permissioned-pilot --chain-id permissioned-pilot \\\n'
  + '  --output permissioned-pilot --non-interactive';

const DOCTOR_COMMAND = './yano.sh appchain doctor permissioned-pilot \\\n'
  + '  --distribution /path/to/yano-x-jvm-<version>.zip';

const initialize = {
  title: 'Initialize',
  text: '`init` writes the blueprint `appchain.yaml`, the generated runtime files, and `appchain.lock`. Without '
    + '`--member-key` values the members are placeholders, so the lock records the acknowledgement '
    + '`PUBLIC_MEMBER_IDENTITIES_REQUIRED_BEFORE_START`.',
  command: INIT_COMMAND,
  wires: [{ from: 'cli', to: 'project', label: 'write project files' }],
  cards: {
    cli: { title: '`PROJECT_INITIALIZED`', tone: 'ok' },
    project: { title: 'Members: placeholders', detail: 'acknowledgement recorded', tone: 'pending' },
  },
  state: {
    caption: 'What `init` generates',
    columns: ['File', 'Holds', 'Who edits it'],
    rows: [
      ['`appchain.yaml`', 'public intent: recipe, network, members, hosts', 'you'],
      ['`config/shared-consensus.yaml`', 'values identical on every member', 'generated'],
      ['`config/nodes/nodeN.yaml`', 'one node\'s ports, peers, paths, secret names', 'generated'],
      ['`secrets/nodeN.env.example`', 'names of the private values', 'generated'],
      ['`appchain.lock`', 'resolved values and file digests', 'generated'],
    ],
    highlight: [0],
  },
};

const sortSecrets = {
  title: 'Sort the secrets',
  text: 'Private material never enters the project. Each `secrets/nodeN.env.example` names the values that member '
    + 'must supply, and the project\'s `.gitignore` excludes `secrets/*.env`. Each value stays with its owner.',
  wires: [{ from: 'project', to: 'vault', label: 'names only' }],
  cards: { vault: { title: 'One set per member', detail: 'signing key, API key' } },
  state: {
    caption: 'Where each secret lives',
    columns: ['Material', 'Held by', 'In the project'],
    rows: [
      ['Member signing key', 'that member\'s secret manager', 'name only: `YANO_APPCHAIN_SIGNING_KEY`'],
      ['API key', 'that member\'s gateway or secret manager', 'name only: `YANO_APPCHAIN_API_KEYS`'],
      ['Anchor wallet key', 'the anchor leader only', 'name only, if an anchor is selected'],
      ['Connector credential', 'the executor host only', 'no'],
      ['Business actor key', 'the actor\'s organization', 'no'],
      ['Member public key', 'everyone', 'yes, in `appchain.yaml`'],
    ],
    highlight: [5],
  },
};

const pin = {
  title: 'Pin identities',
  text: 'Edit `appchain.yaml`: add each member\'s public key and its host, in matching order. A public key is not a '
    + 'secret. Edit only this file; the other files are regenerated from it.',
  wires: [{ from: 'you', to: 'project', label: 'edit `appchain.yaml`' }],
  cards: {
    you: { title: 'Real member keys', detail: 'public keys and hosts' },
    project: { title: 'Members: pinned', detail: 'blueprint edited' },
  },
};

const render = {
  title: 'Render',
  text: '`render` regenerates the derived files and the lock from `appchain.yaml`. With the keys pinned, the '
    + 'acknowledgement is gone. It refuses to overwrite a generated file that was edited by hand.',
  command: './yano.sh appchain render permissioned-pilot',
  wires: [{ from: 'cli', to: 'project', label: 'regenerate' }],
  cards: {
    cli: { title: '`PROJECT_RENDERED`', detail: 'acknowledgements=[]', tone: 'ok' },
    project: { title: 'Lock updated', detail: 'members pinned', tone: 'ok' },
  },
};

const validate = {
  title: 'Validate',
  text: 'Validation checks the blueprint, the lock, the catalogs, the resolved configuration, and every generated '
    + 'file\'s digest.',
  command: './yano.sh appchain config validate --mode project permissioned-pilot',
  wires: [{ from: 'cli', to: 'project', label: 'check digests' }],
  cards: { cli: { title: '`VALID_PROJECT`', tone: 'ok' } },
};

const doctor = {
  title: 'Doctor',
  text: 'Doctor checks the project against the exact release you will run. Its last line is `DOCTOR_OK`, '
    + '`DOCTOR_WARNINGS`, or `DOCTOR_FAILED`. Here it warns: a rotating sequencer needs a synchronized L1 slot view, '
    + 'which you must provide. A warning is a to-do item; a failure blocks.',
  command: DOCTOR_COMMAND,
  wires: [{ from: 'cli', to: 'you', label: 'readiness report' }],
  cards: {
    cli: { title: '`DOCTOR_WARNINGS`', detail: 'one external prerequisite', tone: 'pending' },
  },
  state: {
    caption: 'Readiness stages in the report',
    columns: ['Stage', 'Status'],
    rows: [
      ['`CONFIG_VALID`', 'PASS'],
      ['`ARTIFACTS_READY`', 'PASS'],
      ['`IDENTITIES_READY`', 'PASS'],
      ['`RUNTIME_STARTABLE`', 'PASS'],
      ['`APPLICATION_BOOTSTRAPPED`', 'NOT_REQUIRED'],
      ['`EXECUTORS_READY`', 'NOT_REQUIRED'],
      ['`EXTERNAL_TARGETS_READY`', 'PENDING'],
      ['`OUTCOME_READY`', 'PENDING'],
    ],
    highlight: [6, 7],
  },
};

const exportVms = {
  title: 'Export for VMs',
  text: 'For existing Linux VMs, export an Ansible deployment from the same project. The export contains the '
    + 'consensus configuration and member placement, but no private keys: each member\'s `nodeN.env` comes from '
    + 'its own secret store.',
  command: './yano.sh appchain gitops permissioned-pilot --target ansible \\\n  --output permissioned-pilot-vms',
  wires: [{ from: 'vault', to: 'you', label: '`nodeN.env` per host' }],
  cards: {
    cli: { title: '`GITOPS_EXPORTED`', detail: 'target=ansible', tone: 'final' },
    you: { title: 'Ready to review', detail: 'project and VM export', tone: 'final' },
  },
};

export default {
  id: 'pilot-secrets-and-render',
  type: 'steps',
  title: 'From project to pilot',
  intro: 'the commands that turn a generated project into a reviewed pilot, with the status line each one '
    + 'prints. The “What if” scenarios show what each check refuses.',
  lanes: [
    { id: 'you', label: 'You', note: 'platform lead', kind: 'actor' },
    { id: 'cli', label: 'yano.sh appchain', note: 'offline tools', kind: 'client' },
    { id: 'project', label: 'Project folder', note: 'reviewable files', kind: 'core' },
    { id: 'vault', label: 'Secret stores', note: 'outside the project', kind: 'external' },
  ],
  scenarios: [
    {
      id: 'rail',
      label: 'Init to export',
      steps: [initialize, sortSecrets, pin, render, validate, doctor, exportVms],
    },
    {
      id: 'unpinned',
      label: 'Skip pinning identities',
      summary: 'The project validates, but nothing can start or be exported until the members are real.',
      steps: [initialize, sortSecrets, {
        ...doctor,
        text: 'Doctor lists the acknowledgement as a warning, and the identity and runtime stages stay pending. The '
          + 'result is `DOCTOR_WARNINGS`, not a failure, so read the list rather than the exit code.',
        cards: { cli: { title: '`DOCTOR_WARNINGS`', detail: 'identities pending', tone: 'pending' } },
        state: {
          caption: 'Lines that change',
          columns: ['Check', 'Status'],
          rows: [
            ['`acknowledgement` PUBLIC_MEMBER_IDENTITIES_REQUIRED_BEFORE_START', 'WARN'],
            ['`IDENTITIES_READY`', 'PENDING'],
            ['`RUNTIME_STARTABLE`', 'PENDING'],
          ],
          highlight: [0, 1, 2],
        },
      }, {
        title: 'Export refused',
        text: 'The Ansible export needs one public key and one host per member, so it refuses the project. The '
          + 'generated start scripts refuse it too: “Public member identities are missing”.',
        command: './yano.sh appchain gitops permissioned-pilot --target ansible \\\n  --output permissioned-pilot-vms',
        checks: [
          { label: 'Host deployment on the JVM runtime', ok: true },
          { label: 'One public key and one host per member', ok: false },
        ],
        cards: { cli: { title: 'Export refused', detail: 'members not pinned', tone: 'fail' } },
      }],
    },
    {
      id: 'manual-edit',
      label: 'Edit a generated file',
      summary: 'Generated files are output. A hand edit is detected by its digest.',
      steps: [initialize, {
        title: 'Edit a node file',
        text: 'Suppose someone changes a port directly in `config/nodes/node0.yaml` instead of in `appchain.yaml`.',
        wires: [{ from: 'you', to: 'project', label: 'edit `node0.yaml`' }],
        cards: { project: { title: 'Digest differs', detail: '`config/nodes/node0.yaml`', tone: 'fail' } },
      }, {
        ...render,
        text: '`render` compares every generated file with the digest in the lock and stops: “Generated file has manual '
          + 'edits: config/nodes/node0.yaml; move the change into appchain.yaml or reconcile it explicitly”.',
        wires: [{ from: 'cli', to: 'you', label: 'refused' }],
        cards: { cli: { title: 'Render refused', detail: 'manual edit found', tone: 'fail' } },
      }],
    },
    {
      id: 'wrong-release',
      label: 'Doctor another release',
      summary: 'Doctor pins the project to the release it was generated for.',
      steps: [initialize, pin, render, validate, {
        title: 'Doctor',
        text: 'Pointed at a different release, the release capability index does not match the one in the lock, so '
          + 'the `distribution-index` check fails and the result is `DOCTOR_FAILED`. Use the release named in the lock.',
        command: './yano.sh appchain doctor permissioned-pilot \\\n  --distribution /path/to/another-release.zip',
        wires: doctor.wires,
        cards: { cli: { title: '`DOCTOR_FAILED`', detail: '`distribution-index` FAIL', tone: 'fail' } },
      }],
    },
  ],
  legend: [
    ['actor', 'you'],
    ['client', 'offline CLI'],
    ['core', 'project files'],
    ['external', 'secret stores'],
  ],
  sources: [
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectCli.java',
      anchors: ['"PROJECT_INITIALIZED"', '"PROJECT_RENDERED"', '--member-key <64-hex>',
        '--node-host <hostname>', '--finality <policy>', '--sequencing <mode>',
        'gitops [project-directory] --target helm|kustomize|ansible --output <empty-dir>',
        'doctor [project-directory] [--distribution <path>]'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainDevtoolsCli.java',
      anchors: ['"VALID_PROJECT recipe=%s runtime=%s deployment=%s files=%d "'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectLifecycle.java',
      anchors: ['"DOCTOR_FAILED"', '"DOCTOR_WARNINGS" : "DOCTOR_OK"', 'check("CONFIG_VALID"', 'check("ARTIFACTS_READY"',
        'check("IDENTITIES_READY"', 'check("RUNTIME_STARTABLE"', 'check("APPLICATION_BOOTSTRAPPED"',
        'check("EXECUTORS_READY"', 'check("EXTERNAL_TARGETS_READY"', 'check("OUTCOME_READY"',
        'check("acknowledgement", "WARN", acknowledgement)', 'check("distribution-index"'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectRenderer.java',
      anchors: ['"PUBLIC_MEMBER_IDENTITIES_REQUIRED_BEFORE_START"', '"config/shared-consensus.yaml"',
        '"config/nodes/node" + index + ".yaml"', '"secrets/node" + index + ".env.example"', 'secrets/*.env',
        'references.add("YANO_APPCHAIN_SIGNING_KEY");', 'references.add("YANO_APPCHAIN_API_KEYS");',
        'Generated file has manual edits: ', 'move the change into appchain.yaml or reconcile it explicitly'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainGitOpsExporter.java',
      anchors: ['"GITOPS_EXPORTED"'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectResolver.java',
      anchors: ['boolean bootstrapRequired = memberKeys.isEmpty();'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainAnsibleExporter.java',
      anchors: ['Ansible export requires a JVM host project with one public key', 'resolution.bootstrapRequired()'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectOperations.java',
      anchors: ['Public member identities are missing'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/resources/appchain-dx/v1alpha1/appchain-capability-catalog.json',
      anchors: ['synchronized-l1-slot-view', 'YANO_APPCHAIN_ANCHOR_SIGNING_KEY'] },
  ],
};
