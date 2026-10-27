// The local showcase quickstart, synced to the terminal: doctor, quickstart,
// one order, verify, stop, and restart. Facts come from showcase.sh, its
// chain catalog, and the setup commands it runs.

const start = {
  title: 'Start',
  text: '`quickstart` prepares the instance under `data/showcase/first-demo`: it seals the showcase applications '
    + 'with the setup commands `new`, `request`, `seal`, and `join`, then starts three nodes on a private devnet. '
    + 'Every node hosts the same 13 showcase chains. It then makes node 0 the `workflow-chain` anchor leader, runs '
    + 'the composite workflow and authenticated-map demonstrations, and checks that the nodes converged.',
  viewText: { terminal: 'Ends with the per-chain check and `Status UI: http://127.0.0.1:7070/ui/app-chain/`.' },
  command: './showcase.sh quickstart --profile light --nodes 3 --instance first-demo',
  wires: [
    { from: 'showcase', to: 'nodes', label: 'start 3 nodes' },
    { from: 'showcase', to: 'l1', label: 'anchor workflow-chain', tone: 'cardano' },
  ],
  cards: {
    showcase: { title: 'Instance first-demo', detail: 'identity recorded' },
    nodes: { title: '3 nodes ready', detail: '13 chains each', tone: 'ok' },
    l1: { title: 'Private devnet', detail: '`workflow-chain` anchored', tone: 'cardano' },
  },
};

export default {
  id: 'showcase-quickstart',
  type: 'steps',
  title: 'The showcase in six commands',
  intro: 'the quickstart from this page, with the command you run at each step. Switch to “Your terminal” to see '
    + 'only what the commands print.',
  lanes: [
    { id: 'terminal', label: 'Your terminal', note: '`examples/showcase`', kind: 'client' },
    { id: 'showcase', label: 'showcase.sh', note: 'runs the setup commands', kind: 'runtime' },
    { id: 'nodes', label: 'Three members', note: 'http 7070–7072', kind: 'member' },
    { id: 'l1', label: 'Devnet', note: 'node 0 produces it', kind: 'cardano' },
  ],
  views: [
    { id: 'default', label: 'Showcase' },
    { id: 'terminal', label: 'Your terminal', focus: ['terminal'] },
  ],
  scenarios: [
    {
      id: 'quickstart',
      label: 'Quickstart',
      steps: [{
        title: 'Check',
        text: '`doctor` checks for Java 25, Python 3 with BLAKE2b support, `curl`, `jq`, and the packaged artifacts: '
          + '`yano.jar`, the showcase configuration, the load drivers, and the plugin bundles. It starts nothing.',
        viewText: { terminal: 'Prints `doctor: Java 25…, curl, jq, and packaged artifacts are present`.' },
        command: './showcase.sh doctor --profile light',
        cards: { terminal: { title: 'doctor', detail: 'prerequisites present', tone: 'ok' } },
      }, start, {
        title: 'Submit an order',
        text: 'The demo script submits an order to `orders-chain` through node 0, waits until the message is final, '
          + 'and requests a typed proof that it is recorded.',
        viewText: { terminal: 'Prints the proof summary with `"claimSatisfied": true`, then '
          + '`ORDERED: message finalized at height N`.' },
        command: './demos/submit-orders.sh first-demo \'{"order":"A-100","event":"created"}\'',
        wires: [
          { from: 'terminal', to: 'nodes', label: 'POST `{topic, body}`' },
          { from: 'nodes', to: 'terminal', label: 'final + proof' },
        ],
        cards: { terminal: { title: 'ORDERED', detail: 'final, proof satisfied', tone: 'final' } },
      }, {
        title: 'Verify',
        text: 'For every showcase chain, `verify` waits until all nodes report the same tip and root and checks that '
          + 'the tip\'s certificate has at least the threshold of signatures. It also waits for the `workflow-chain` '
          + 'anchor to be confirmed.',
        viewText: { terminal: 'Prints one line per chain, such as `orders-chain nodes=3 tip=… cert=…`.' },
        command: './showcase.sh verify --instance first-demo',
        wires: [{ from: 'showcase', to: 'nodes', label: 'compare tips and roots' }],
        cards: {
          terminal: { title: 'Converged', detail: '13 chains', tone: 'final' },
          nodes: { title: 'Same tip and root', detail: 'on every node', tone: 'final' },
        },
      }, {
        title: 'Stop',
        text: '`stop` stops the nodes and keeps the instance: its identity, its chain data, and its chosen ports.',
        viewText: { terminal: 'Prints `✓ Stopped node2; its data is kept`, and the same for node 1 and node 0.' },
        command: './showcase.sh stop --instance first-demo',
        wires: [{ from: 'showcase', to: 'nodes', label: 'stop, keep data' }],
        cards: { nodes: { title: 'Stopped', detail: 'data retained' } },
      }, {
        title: 'Restart',
        text: '`restart` starts the same instance from its retained state. Run `verify` again, then retrieve the same '
          + 'order: the history is unchanged.',
        viewText: { terminal: 'Prints `node0 is up at http://127.0.0.1:7070` for each node. Retained tips and roots '
          + 'return unchanged.' },
        command: './showcase.sh restart --instance first-demo',
        wires: [{ from: 'showcase', to: 'nodes', label: 'start from retained state' }],
        cards: { nodes: { title: '3 nodes ready', detail: 'same history', tone: 'ok' } },
      }],
    },
    {
      id: 'ports-busy',
      label: 'The default ports are busy',
      summary: 'The showcase gives every node its ports explicitly, so it stops rather than moving them.',
      steps: [{
        title: 'Check',
        text: '`doctor` passes. It does not check ports.',
        command: './showcase.sh doctor --profile light',
        cards: { terminal: { title: 'doctor', detail: 'prerequisites present', tone: 'ok' } },
      }, {
        ...start,
        text: 'Another program holds a port in 7070–7072. Each node keeps the ports the showcase gave it, so `start` '
          + 'refuses to run the node whose port is taken, and the showcase stops.',
        viewText: { terminal: 'Prints `Port … is already in use by another program`, then `node… did not start`.' },
        wires: [{ from: 'showcase', to: 'nodes', label: 'start refused', tone: 'fail' }],
        cards: {
          showcase: { title: 'Start refused', detail: 'port busy', tone: 'fail' },
          nodes: { title: 'Not started', tone: 'fail' },
          l1: null,
        },
      }, {
        title: 'Choose other ports',
        text: 'Stop the program that holds the ports and run the same command again. Or start a new instance with '
          + 'other bases; the instance remembers them for later commands.',
        command: './showcase.sh quickstart --profile light --nodes 3 \\\n'
          + '  --instance second-demo --http-base 7170 --server-base 14337',
        cards: { nodes: { title: '3 nodes ready', detail: 'http 7170–7172', tone: 'ok' } },
      }],
    },
  ],
  legend: [
    ['client', 'your terminal'],
    ['runtime', 'showcase script'],
    ['member', 'member nodes'],
    ['cardano', 'devnet and anchor'],
  ],
  sources: [
    { repo: 'yano-x', path: 'examples/showcase/src/main/showcase/showcase.sh',
      anchors: ['need java; need python3; need curl; need jq', 'hashlib.blake2b(b"yano-showcase-doctor"',
        'case "$java_version" in 25|25.*)', 'and packaged artifacts are present',
        '[ "$ANCHOR_MODE" != script ] || anchor_chains workflow-chain', 'local args=(anchor "$app" --chain "$cid"',
        'run_composite quickstart', 'run_authenticated_map quickstart',
        'note "Status UI: http://127.0.0.1:$HTTP_BASE/ui/app-chain/"', "printf '%s/data/showcase/%s'",
        'note "ORDERED: message finalized at height', 'claimSatisfied:.claimResult.satisfied',
        'nodes=%d tip=%d cert=%s/%s', 'die "$cid tip certificate has $cert signatures below active threshold',
        'die "workflow-chain has no confirmed L1 anchor"', 'HTTP_BASE=7070', 'SERVER_BASE=13337',
        '--node-port "$((SERVER_BASE + index))" --http-port "$((HTTP_BASE + index))"',
        'note "node$index is up at http://127.0.0.1:$((HTTP_BASE + index))"', 'die "node$index did not start; see',
        'appchain stop --node-dir "$(node_dir "$index")"', 'stop_nodes; start_nodes;;'] },
    { repo: 'yano-x', path: 'examples/showcase/src/main/showcase/demos/submit-orders.sh',
      anchors: ['showcase.sh" run orders --instance'] },
    { repo: 'yano-x', path: 'examples/showcase/src/main/showcase/config/showcase-catalog-v1.json',
      anchors: ['"orders-chain"', '"workflow-chain"', '"cardano-history-chain"'] },
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/StartCommand.java',
      anchors: ['" is already in use by another program. Stop it, or "'] },
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/StopCommand.java',
      anchors: ['terminal.ok("Stopped " + node.requireIdentity().name() + "; its data is kept");'] },
  ],
};
