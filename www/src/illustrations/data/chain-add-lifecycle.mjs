// Adding a chain to a generated local host project: propose, plan, stop,
// apply, start, verify. Status markers, plan fields, and refusals come from
// the devtools project CLI and AppChainProjectOperations.

const propose = {
  title: 'Propose',
  text: '`chain add` copies the existing node placement into a new chain entry and edits only `appchain.yaml`. The '
    + 'running nodes and the active configuration are unchanged.',
  command: './yano.sh appchain chain add ./my-application \\\n  --chain-id documents --recipe document-trail',
  wires: [{ from: 'cli', to: 'project', label: 'edit `appchain.yaml`' }],
  cards: {
    cli: { title: '`CHAIN_PROPOSED`', tone: 'ok' },
    project: { title: 'Blueprint: + documents', detail: 'lock unchanged' },
    nodes: { title: 'Running', detail: 'orders' },
  },
};

const plan = {
  title: 'Plan',
  text: '`plan` compares the proposed blueprint with the current lock and prints JSON. Save its `digest`: it binds '
    + 'the old lock and the exact proposed blueprint, so any later edit needs a new plan.',
  command: './yano.sh appchain plan ./my-application',
  wires: [{ from: 'cli', to: 'you', label: 'plan + digest' }],
  cards: { cli: { title: '`PLAN_READY`', detail: 'no blockers', tone: 'ok' } },
  state: {
    caption: 'What the plan shows',
    columns: ['Field', 'Value'],
    rows: [
      ['`status`', '`PLAN_READY`'],
      ['`chains`', '`orders`: `UNCHANGED`, `documents`: `ADD`'],
      ['`blockers`', 'none'],
      ['`activation`', '`STOP_RENDER_RESTART`'],
      ['`digest`', '64 hex characters'],
    ],
    highlight: [1],
  },
};

const stop = {
  title: 'Stop',
  text: 'Save an old message id, its height, the root, and its proof, and take a supported backup. Then stop the '
    + 'nodes. Stopping keeps all chain data.',
  command: './my-application/scripts/stop',
  wires: [{ from: 'you', to: 'nodes', label: 'stop' }],
  cards: { nodes: { title: 'Stopped', detail: 'data retained' } },
};

const APPLY_CHECKS = [
  { label: 'All project nodes are stopped', ok: true },
  { label: 'The plan digest matches the current blueprint and lock', ok: true },
  { label: 'The plan has no blockers', ok: true },
  { label: 'Existing node placement and chain settings are unchanged', ok: true },
  { label: 'No generated file was edited outside the reviewed change', ok: true },
];

const apply = {
  title: 'Apply',
  text: '`apply` checks the reviewed plan, records a pending journal, stages the new revision, and installs the '
    + 'generated files with `appchain.lock` last. It never deletes or resets chain data, and it installs no plugins.',
  command: './yano.sh appchain apply ./my-application --plan <digest>',
  wires: [{ from: 'cli', to: 'project', label: 'install revision' }],
  checks: APPLY_CHECKS,
  cards: {
    cli: { title: '`APPLIED`', detail: 'retained state preserved', tone: 'ok' },
    project: { title: 'Lock: + documents', detail: 'installed last', tone: 'ok' },
  },
};

const start = {
  title: 'Start',
  text: 'The start script validates the project, checks that no apply is pending and that the lock is the applied '
    + 'one, and starts each node. Existing chains resume from retained state; `documents` starts at its genesis.',
  command: './my-application/scripts/start',
  wires: [{ from: 'you', to: 'nodes', label: 'start' }],
  cards: { nodes: { title: 'Running', detail: 'orders + documents', tone: 'ok' } },
};

const verify = {
  title: 'Verify',
  text: '`drift` compares the identity of every chain on every member. Then retrieve the old message and proof at '
    + 'the saved height, and submit a command to the new chain.',
  command: '"$YANO_HOME/yano.sh" appchain drift ./my-application \\\n'
    + '  --peer http://127.0.0.1:8080/api/v1/ --peer http://127.0.0.1:8081/api/v1/ \\\n'
    + '  --peer http://127.0.0.1:8082/api/v1/ --api-key-env YANO_APPCHAIN_API_KEYS',
  wires: [{ from: 'cli', to: 'nodes', label: 'read identities' }],
  cards: {
    cli: { title: '`DRIFT_OK peers=3`', tone: 'final' },
    nodes: { title: 'Both chains verified', detail: 'old proof unchanged', tone: 'final' },
  },
};

export default {
  id: 'chain-add-lifecycle',
  type: 'steps',
  title: 'Add a chain, step by step',
  intro: 'adding a `documents` chain to a running local project that already hosts `orders`. Each step shows the '
    + 'command and the status it prints. The “What if” scenarios show what the checks refuse.',
  lanes: [
    { id: 'you', label: 'You', note: 'operator', kind: 'actor' },
    { id: 'cli', label: 'yano.sh appchain', note: 'offline tools', kind: 'client' },
    { id: 'project', label: 'Project files', note: '`my-application/`', kind: 'core' },
    { id: 'nodes', label: 'Member nodes', note: 'same machine', kind: 'member' },
  ],
  scenarios: [
    { id: 'add', label: 'Add a chain', steps: [propose, plan, stop, apply, start, verify] },
    {
      id: 'blocked',
      label: 'Change an existing chain',
      summary: 'This route only adds chains. A change to an existing chain is blocked.',
      steps: [{
        title: 'Edit an existing chain',
        text: 'Suppose you also change the threshold of `orders` in `appchain.yaml`.',
        wires: [{ from: 'you', to: 'project', label: 'edit `appchain.yaml`' }],
        cards: { project: { title: 'Blueprint: orders changed', tone: 'pending' } },
      }, {
        ...plan,
        text: '`plan` reports the change and a blocker for it, and exits with an error.',
        cards: { cli: { title: '`PLAN_BLOCKED`', detail: '1 blocker', tone: 'fail' } },
        state: {
          caption: 'What the plan shows',
          columns: ['Field', 'Value'],
          rows: [
            ['`status`', '`PLAN_BLOCKED`'],
            ['`chains`', '`orders`: `CHANGE`'],
            ['`blockers`', '“orders: existing consensus or genesis changes require a separate migration”'],
          ],
          highlight: [2],
        },
      }, stop, {
        ...apply,
        text: '`apply` refuses a blocked plan. Restore the original values; a consensus or genesis change to an '
          + 'existing chain needs a separate migration.',
        checks: [APPLY_CHECKS[0], APPLY_CHECKS[1], { ...APPLY_CHECKS[2], ok: false, code: 'Plan is blocked' },
          { ...APPLY_CHECKS[3], ok: null }, { ...APPLY_CHECKS[4], ok: null }],
        cards: { cli: { title: 'Apply refused', detail: 'plan is blocked', tone: 'fail' }, project: null },
      }],
    },
    {
      id: 'stale',
      label: 'Edit after planning',
      summary: 'The digest binds the exact blueprint you reviewed.',
      steps: [propose, plan, {
        title: 'Edit the blueprint',
        text: 'After planning, someone edits `appchain.yaml` again, for example to rename the new chain.',
        wires: [{ from: 'you', to: 'project', label: 'edit again' }],
        cards: { project: { title: 'Blueprint changed', detail: 'after the plan', tone: 'pending' } },
      }, stop, {
        ...apply,
        text: 'The current blueprint no longer matches the reviewed digest, so `apply` refuses: “Plan is stale; run '
          + 'plan again”. Review the new plan and apply its digest.',
        checks: [APPLY_CHECKS[0], { ...APPLY_CHECKS[1], ok: false, code: 'Plan is stale; run plan again' },
          { ...APPLY_CHECKS[2], ok: null }, { ...APPLY_CHECKS[3], ok: null }, { ...APPLY_CHECKS[4], ok: null }],
        cards: { cli: { title: 'Apply refused', detail: 'plan is stale', tone: 'fail' }, project: null },
      }],
    },
    {
      id: 'interrupted',
      label: 'Apply is interrupted',
      summary: 'A pending journal makes an interrupted apply resumable and blocks startup until it finishes.',
      steps: [propose, plan, stop, {
        ...apply,
        text: '`apply` writes `.deployment/pending.json` and starts installing files, then the machine loses power.',
        checks: undefined,
        cards: { cli: { title: 'Interrupted', tone: 'fail' }, project: { title: 'Pending journal', tone: 'pending' } },
      }, {
        ...start,
        text: 'The start script refuses while the journal exists: “Resume pending apply first”.',
        cards: { nodes: { title: 'Not started', detail: 'apply pending', tone: 'fail' } },
      }, {
        ...apply,
        text: 'Run `apply` again with the same digest. It accepts only the old or the staged bytes for each file and '
          + 'finishes the install. A different digest is refused: “Resume the pending operation with its original '
          + 'plan digest”.',
        checks: undefined,
      }, start],
    },
  ],
  legend: [
    ['actor', 'you'],
    ['client', 'offline CLI'],
    ['core', 'project files'],
    ['member', 'member nodes'],
  ],
  sources: [
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectCli.java',
      anchors: ['chain add <project> --chain-id <id> --recipe <recipe>', 'apply [project-directory] --plan <reviewed-digest>',
        'CHAIN_PROPOSED: appchain.yaml updated.', 'APPLIED: configuration revision installed; retained state preserved.'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectOperations.java',
      anchors: ['"PLAN_READY" : "PLAN_BLOCKED"', '"STOP_RENDER_RESTART"', '"UNCHANGED" : "CHANGE"', '"ADD"',
        'existing consensus or genesis changes require a separate migration', 'Plan is stale; run plan again',
        'Plan is blocked: ', 'Stop all project nodes with scripts/stop before applying',
        'Node placement or local settings changed: ', 'Unreviewed edit during apply: ', 'The lock is installed last',
        'operations.resolve("pending.json")', 'Resume pending apply first',
        'Resume the pending operation with its original plan digest',
        'A interrupted write may contain either the old bytes or the staged bytes, never a third value.'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectLifecycle.java',
      anchors: ['"DRIFT_OK"'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainProjectRenderer.java',
      anchors: ['"$YANO_HOME/yano.sh" appchain start-check "$root"', '"$root/scripts/validate"'] },
  ],
};
