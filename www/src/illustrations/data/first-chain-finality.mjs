// Tutorial 1, synced to the terminal: one event submitted through node 1 of a
// three-node launcher cluster, ordered by node 0 and finalized by any two
// members. Each step carries the command the reader runs at that point.
// Facts: cluster.sh (threshold default, proposer injection, output markers),
// the stock config (fixed proposer for orders-chain, 1 s cadence), and Yano's
// consensus guide (two-phase round, quorum timeouts).

const SUBMIT_COMMAND = './yano.sh appchain cluster submit orders-chain orders \\\n'
  + '  \'{"event":"order-created","orderId":"A-1001","quantity":4}\' --node 1';

const start = {
  title: 'Start',
  text: 'The launcher starts node 0 first. It produces blocks for the private Cardano devnet and is also a member. '
    + 'After a 25-second warm-up, nodes 1 and 2 join it. The threshold defaults to a majority, `N/2 + 1` in '
    + 'whole numbers: 2 of 3.',
  viewText: { terminal: 'Prints `members : 3   threshold: 2`, then `Cluster up.`' },
  command: './yano.sh appchain cluster start 3',
  cards: {
    terminal: { title: 'Cluster up.', detail: 'threshold: 2' },
    n0: { title: 'Ready', detail: 'http 7070' },
    n1: { title: 'Ready', detail: 'http 7071' },
    n2: { title: 'Ready', detail: 'http 7072' },
  },
};

const check = {
  title: 'Check agreement',
  text: 'Status asks every running node for each chain\'s tip and state root, then compares the roots. '
    + '`AGREED` means all of them report the same root for that chain. It is a comparison, not a vote.',
  viewText: { terminal: 'Prints `orders-chain: AGREED (…)`, and the same for `registry-chain` and `effects-chain`.' },
  command: './yano.sh appchain cluster status',
  cards: { terminal: { title: 'AGREED', detail: 'three chains', tone: 'ok' } },
};

const submit = {
  title: 'Submit',
  text: 'The launcher sends a topic and a body to node 1. Node 1 checks the command, signs the envelope with its own '
    + 'member key, keeps it in its pending pool, and answers **202** with a message id. You sign nothing, and 202 '
    + 'means queued, not final.',
  viewText: { terminal: 'Prints `submitted <message id>... to orders-chain`.' },
  command: SUBMIT_COMMAND,
  wires: [
    { from: 'terminal', to: 'n1', label: 'POST `{topic, body}`' },
    { from: 'n1', to: 'terminal', label: '202 + messageId' },
  ],
  cards: {
    terminal: { title: '202 Accepted', detail: 'not final yet', tone: 'pending' },
    n1: { title: 'Envelope', detail: 'signed by node 1' },
  },
};

const gossip = {
  title: 'Gossip',
  text: 'Node 1 gossips the envelope to nodes 0 and 2. Each checks the signature and that node 1 is a member, then '
    + 'holds the message in memory until a block includes it.',
  viewText: { terminal: 'Nothing new yet. The message is waiting in the pools.' },
  wires: [
    { from: 'n1', to: 'n0', label: 'gossip envelope' },
    { from: 'n1', to: 'n2', label: 'gossip envelope' },
  ],
  cards: {
    n0: { title: 'Pending pool', detail: '1 message' },
    n2: { title: 'Pending pool', detail: '1 message' },
  },
};

const propose = {
  title: 'Propose',
  text: '`orders-chain` uses a fixed sequencer, and the launcher names node 0 as its proposer, so node 0 leads. On its '
    + 'next tick (every second on this chain) it builds block h, applies it to compute the state root, and sends the '
    + 'block with its PREPARE vote.',
  viewText: { terminal: 'Still pending.' },
  wires: [
    { from: 'n0', to: 'n1', label: 'block h + PREPARE' },
    { from: 'n0', to: 'n2', label: 'block h + PREPARE' },
  ],
  cards: { n0: { title: 'Block h · view 0', detail: 'own root `7f3a…`', tone: 'leader' } },
};

const vote = {
  title: 'Vote',
  text: 'Nodes 1 and 2 check the block and apply it themselves. Each votes PREPARE only if its own state root matches '
    + 'byte for byte. Two PREPARE votes reach the threshold and form a **PreparedQC**, and the members then sign COMMIT.',
  viewText: { terminal: 'Still pending. Every member is re-running the block.' },
  wires: [
    { from: 'n1', to: 'n0', label: 'PREPARE, then COMMIT' },
    { from: 'n2', to: 'n0', label: 'PREPARE, then COMMIT' },
  ],
  cards: {
    n1: { title: 'Re-executed ✓', detail: 'own root `7f3a…`', tone: 'ok' },
    n2: { title: 'Re-executed ✓', detail: 'own root `7f3a…`', tone: 'ok' },
  },
};

const certify = {
  title: 'Certify',
  text: 'When COMMIT votes reach 2 of 3, the member holding them assembles the **finality certificate** and shares it. '
    + 'Every node writes block h and its new root in one atomic batch. The block is final, and there is no rollback '
    + 'below finality.',
  viewText: { terminal: 'The newest block summary shows `certSignatures` of at least 2.' },
  command: 'curl -s http://127.0.0.1:7070/api/v1/app-chain/chains/orders-chain/blocks | jq .',
  wires: [
    { from: 'n0', to: 'n1', label: 'finality certificate' },
    { from: 'n0', to: 'n2', label: 'finality certificate' },
  ],
  cards: {
    n0: { title: 'Final at h', detail: 'root `7f3a…`', tone: 'final' },
    n1: { title: 'Final at h', detail: 'root `7f3a…`', tone: 'final' },
    n2: { title: 'Final at h', detail: 'root `7f3a…`', tone: 'final' },
  },
};

const agree = {
  title: 'Agree again',
  text: 'Every node now reports the new tip of `orders-chain` and the same root, so status prints `AGREED` again. '
    + 'Agreement after new traffic is the result this tutorial is about.',
  viewText: { terminal: 'Prints `orders-chain: AGREED (…)` with a higher tip on every node.' },
  command: './yano.sh appchain cluster status',
  cards: { terminal: { title: 'AGREED', detail: 'same root on 3 nodes', tone: 'final' } },
};

const nodeTwoDown = {
  title: 'Node 2 stops',
  text: 'Suppose node 2\'s process stops after the cluster started. Two members remain, and the threshold is still 2.',
  viewText: { terminal: 'Status no longer shows node 2 as ready.' },
  cards: { n2: { title: 'Offline', detail: 'no votes', tone: 'fail' } },
};

const submitWithoutTwo = {
  ...submit,
  text: 'You submit through node 1 as before. Node 1 signs the envelope, answers 202, and gossips it to node 0.',
  cards: { ...submit.cards, n2: { title: 'Offline', detail: 'no votes', tone: 'fail' } },
};

export default {
  id: 'first-chain-finality',
  type: 'steps',
  title: 'Your first finalized event',
  intro: 'the three-node cluster from this tutorial, with the command you run at each step. Pick a step, press Play, '
    + 'or switch to “Your terminal” to see only what the commands print.',
  lanes: [
    { id: 'terminal', label: 'Your terminal', note: '`./yano.sh appchain`', kind: 'client' },
    { id: 'n0', label: 'Node 0', note: 'devnet producer · proposer', kind: 'leader' },
    { id: 'n1', label: 'Node 1', note: 'receives your event', kind: 'member' },
    { id: 'n2', label: 'Node 2', note: 'member', kind: 'member' },
  ],
  views: [
    { id: 'default', label: 'Cluster' },
    { id: 'terminal', label: 'Your terminal', focus: ['terminal'] },
  ],
  scenarios: [
    {
      id: 'normal',
      label: 'Three members, threshold 2',
      steps: [start, check, submit, gossip, propose, vote, certify, agree],
    },
    {
      id: 'one-down',
      label: 'One member is down',
      summary: 'With a threshold of 2, any two of the three members can make a block final.',
      steps: [nodeTwoDown, submitWithoutTwo, {
        ...propose,
        text: 'Node 0 builds block h and sends it with its PREPARE vote. Only node 1 can answer.',
        wires: [{ from: 'n0', to: 'n1', label: 'block h + PREPARE' }],
      }, {
        ...vote,
        text: 'Node 1 re-executes the block and votes. Node 0\'s vote and node 1\'s vote are two of three, so they form '
          + 'a PreparedQC and then a COMMIT quorum.',
        wires: [{ from: 'n1', to: 'n0', label: 'PREPARE, then COMMIT' }],
        cards: { n1: vote.cards.n1 },
      }, {
        title: 'Final without node 2',
        text: 'Block h is final on nodes 0 and 1. When node 2 runs again, it fetches the missing blocks from a peer, '
          + 'verifies each block and its certificate, and re-applies them before it catches up.',
        viewText: { terminal: 'Your message is final. Node 0 and node 1 agree.' },
        wires: [{ from: 'n0', to: 'n1', label: 'finality certificate' }],
        cards: {
          n0: { title: 'Final at h', detail: '2 of 3', tone: 'final' },
          n1: { title: 'Final at h', detail: '2 of 3', tone: 'final' },
          n2: { title: 'Behind', detail: 'catches up later', tone: 'pending' },
        },
      }],
    },
    {
      id: 'threshold-three',
      label: 'Threshold 3, one member down',
      summary: 'When every member must vote, one missing member stops finality.',
      steps: [{
        title: 'Start with threshold 3',
        text: 'The threshold is part of the cluster\'s identity, so a retained cluster refuses a different one. Start a '
          + 'second cluster in a new data directory instead. Every block now needs three COMMIT votes.',
        viewText: { terminal: 'Prints `members : 3   threshold: 3`.' },
        command: 'export YANO_CLUSTER_DIR=/tmp/yano-tutorial-threshold-3\n'
          + './yano.sh appchain cluster start 3 --threshold 3',
        cards: {
          terminal: { title: 'Cluster up.', detail: 'threshold: 3' },
          n0: { title: 'Ready' },
          n1: { title: 'Ready' },
          n2: { title: 'Ready' },
        },
      }, nodeTwoDown, submitWithoutTwo, {
        ...propose,
        text: 'Node 0 builds block h and sends it with its PREPARE vote. Only node 1 can answer.',
        wires: [{ from: 'n0', to: 'n1', label: 'block h + PREPARE' }],
      }, {
        title: 'Stalled',
        text: 'Only two PREPARE votes exist, so no PreparedQC forms. The round times out, but moving to a new view also '
          + 'needs three signed timeouts. Nothing becomes final until node 2 returns, and the message expires from the '
          + 'pools after 10 minutes by default.',
        viewText: { terminal: 'The submission was accepted, but the tip of `orders-chain` does not move.' },
        checks: [
          { label: 'PREPARE votes reach the threshold of 3 (only 2 arrive)', ok: false },
          { label: 'Signed timeouts reach 3 for a new view (only 2 arrive)', ok: false },
        ],
        cards: {
          terminal: { title: 'Not final', detail: 'tip unchanged', tone: 'fail' },
          n0: { title: 'Waiting', detail: '2 of 3 votes', tone: 'pending' },
          n1: { title: 'Waiting', detail: '2 of 3 votes', tone: 'pending' },
        },
      }],
    },
  ],
  legend: [
    ['client', 'your terminal'],
    ['leader', 'proposer for `orders-chain`'],
    ['member', 'member node'],
    ['final', 'final'],
    ['fail', 'offline or stalled'],
  ],
  sources: [
    { repo: 'yano-x', path: 'scripts/appchain-cluster/cluster.sh',
      anchors: ['default_threshold() { echo $(( $1 / 2 + 1 )); }', 'local warmup="${CLUSTER_WARMUP:-25}"',
        'members : $n   threshold:', 'c_grn "Cluster up."', 'AGREED (${seen:0:16}...)',
        'echo "submitted ${mid:0:16}... to $cid"', '-Dyano.app-chain.chains[$idx].sequencer.proposer=$proposer',
        'proposer="$(node_pub 0)"', 'HTTP_BASE="${YANO_CLUSTER_HTTP_BASE:-7070}"',
        'differs from retained state; restore the original profile or use a new data directory'] },
    { repo: 'yano-x', path: 'config/application-appchain.yml',
      anchors: ['chain-id: "orders-chain"', 'No sequencer.mode → fixed proposer', 'interval-ms: 1000'] },
    { repo: 'yano', path: 'docs/APP_CHAIN_CONSENSUS_GUIDE.md',
      anchors: ['`sequencer.proposer` selects the view-0 leader', 'requires byte-identical `stateRoot`', 'PreparedQC',
        'FinalityCert', 'A quorum of signed timeout records forms a', 'there is no rollback path below finality',
        'the threshold cert is fully verified'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainEngine.java',
      anchors: ['pendingRound.commits.size() < group.thresholdAt(height)',
        'certificate.timeouts().size() < group.thresholdAt(certificate.height())'] },
    { repo: 'yano', path: 'runtime/src/main/java/org/yanoproject/runtime/appchain/AppChainSubsystem.java',
      anchors: ['.sender(signer.publicKey())'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/AppChainConfig.java',
      anchors: ['DEFAULT_DEFAULT_TTL_SECONDS = 600'] },
    { repo: 'yano', path: 'app/src/main/java/org/yanoproject/app/api/appchain/AppChainResource.java',
      anchors: ['summary.put("certSignatures", b.cert().signatures().size());'] },
  ],
};
