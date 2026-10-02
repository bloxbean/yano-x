// The local showcase quickstart, synced to the terminal: doctor, quickstart,
// one order, verify, stop, and restart. Facts come from showcase.sh, its
// chain catalog, and the cluster launcher it drives.

const start = {
  title: 'Start',
  text: '`quickstart` prepares the instance under `data/showcase/first-demo` and starts three nodes on a private '
    + 'devnet. Every node hosts the same 13 showcase chains. It then bootstraps the `workflow-chain` devnet anchor, '
    + 'runs the composite workflow and authenticated-map demonstrations, and checks that the nodes converged.',
  viewText: { terminal: 'Ends with the per-chain check and `Status UI: http://127.0.0.1:7070/ui/app-chain/`.' },
  command: './showcase.sh quickstart --profile light --nodes 3 --instance first-demo',
  wires: [
    { from: 'showcase', to: 'nodes', label: 'start 3 nodes' },
    { from: 'showcase', to: 'l1', label: 'anchor bootstrap', tone: 'cardano' },
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
    { id: 'showcase', label: 'showcase.sh', note: 'drives the cluster launcher', kind: 'runtime' },
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
        viewText: { terminal: 'Prints `stopped 3 node(s)`.' },
        command: './showcase.sh stop --instance first-demo',
        wires: [{ from: 'showcase', to: 'nodes', label: 'stop, keep data' }],
        cards: { nodes: { title: 'Stopped', detail: 'data retained' } },
      }, {
        title: 'Restart',
        text: '`restart` starts the same instance from its retained state. Run `verify` again, then retrieve the same '
          + 'order: the history is unchanged.',
        viewText: { terminal: 'Prints `Cluster up.` Retained tips and roots return unchanged.' },
        command: './showcase.sh restart --instance first-demo',
        wires: [{ from: 'showcase', to: 'nodes', label: 'start from retained state' }],
        cards: { nodes: { title: '3 nodes ready', detail: 'same history', tone: 'ok' } },
      }],
    },
    {
      id: 'ports-busy',
      label: 'The default ports are busy',
      summary: 'The showcase passes its port bases to the launcher explicitly, so it stops rather than moving them.',
      steps: [{
        title: 'Check',
        text: '`doctor` passes. It does not check ports.',
        command: './showcase.sh doctor --profile light',
        cards: { terminal: { title: 'doctor', detail: 'prerequisites present', tone: 'ok' } },
      }, {
        ...start,
        text: 'Another program holds a port in 7070–7072. Because the port bases are explicit, the launcher refuses '
          + 'to start any node: “explicit HTTP range 7070-7072 is busy”.',
        viewText: { terminal: 'Prints `explicit HTTP range 7070-7072 is busy (port …)`.' },
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
        '[ "$COMMAND" != quickstart ] || [ "$PROFILE" != light ] || ANCHOR=true',
        '"$CLUSTER" anchor-bootstrap workflow-chain', 'run_composite quickstart', 'run_authenticated_map quickstart',
        'note "Status UI: http://127.0.0.1:$HTTP_BASE/ui/app-chain/"', "printf '%s/data/showcase/%s'",
        'note "ORDERED: message finalized at height', 'claimSatisfied:.claimResult.satisfied',
        'nodes=%d tip=%d cert=%s/%s', 'die "$cid tip certificate has $cert signatures below active threshold',
        'die "workflow-chain has no confirmed L1 anchor"', 'HTTP_BASE=7070', 'SERVER_BASE=13337',
        '--http-base "$HTTP_BASE" --server-base "$SERVER_BASE"',
        'cluster_env; "$CLUSTER" stop; write_node_configs "$NODES"; up_light'] },
    { repo: 'yano-x', path: 'examples/showcase/src/main/showcase/demos/submit-orders.sh',
      anchors: ['showcase.sh" run orders --instance'] },
    { repo: 'yano-x', path: 'examples/showcase/src/main/showcase/config/showcase-catalog-v1.json',
      anchors: ['"orders-chain"', '"workflow-chain"', '"cardano-history-chain"'] },
    { repo: 'yano-x', path: 'scripts/appchain-cluster/cluster.sh',
      anchors: ['--http-base)    HTTP_BASE="$2"; HTTP_BASE_EXPLICIT=1; shift 2;;',
        'die "explicit HTTP range $HTTP_BASE-$(range_end "$HTTP_BASE" "$count") is busy (port $busy)"',
        'c_grn "stopped $killed node(s)"', 'c_grn "Cluster up."'] },
  ],
};
