// Tutorial 1, synced to the terminal: one event submitted through bob's node in
// the `appchain try` rehearsal (operators alice, bob and carol), ordered by the
// lead and finalized by any two members. Each step carries the command the
// reader runs at that point.
// Facts: TryCommand.java (operators, default chain, ports, its own submission),
// Draft.java (majority threshold, fixed sequencing), SealedApp.java (the lead is
// the fixed proposer), StatusCommand.java (agreement line), and Yano's consensus
// guide (two-phase round, quorum timeouts).

const SUBMIT_COMMAND = 'BOB=$(jq -r .httpPort appchain-try/bob/yano-node/node.json)\n'
  + 'curl -X POST http://127.0.0.1:$BOB/api/v1/app-chain/chains/orders/messages \\\n'
  + '  -H \'Content-Type: application/json\' -d \'{"topic":"orders","body":"order A-1001 created"}\'';
const STATUS_COMMAND = './yano.sh appchain status --node-dir appchain-try/bob/yano-node';

const start = {
  title: 'Start',
  text: '`try` runs the real setup steps for a coordinator and three operators: `new`, one `request` per operator, '
    + '`seal`, `join`, and `start`. Alice is the lead, so her node produces the private Cardano devnet and is also a '
    + 'member; bob and carol follow it. The threshold defaults to a majority, `N/2 + 1` in whole numbers: 2 of 3.',
  viewText: {
    terminal: 'Prints every command it runs, then `✓ Node alice is up at …` and the same for bob and carol.',
  },
  command: './yano.sh appchain try',
  cards: {
    terminal: { title: 'Nodes up', detail: 'threshold: 2 of 3' },
    n0: { title: 'Ready', detail: 'http 8080' },
    n1: { title: 'Ready', detail: 'http 8081' },
    n2: { title: 'Ready', detail: 'http 8082' },
  },
};

const check = {
  title: 'Check agreement',
  text: 'Status asks every member\'s node for each chain\'s genesis identity, tip, and state root. `members agree` '
    + 'means they all run the same chain, and status warns when two members report different roots at the same '
    + 'height. It is a comparison, not a vote.',
  viewText: { terminal: 'Prints `members online 3/3`, then `chain orders … members agree`.' },
  command: STATUS_COMMAND,
  cards: { terminal: { title: 'members agree', detail: 'chain orders', tone: 'ok' } },
};

const submit = {
  title: 'Submit',
  text: 'You send a topic and a body to bob\'s node. It checks the command, signs the envelope with bob\'s member '
    + 'key, keeps it in its pending pool, and answers **202** with a message id. You sign nothing, and 202 means '
    + 'queued, not final.',
  viewText: { terminal: 'Prints a JSON answer with the `messageId`.' },
  command: SUBMIT_COMMAND,
  wires: [
    { from: 'terminal', to: 'n1', label: 'POST `{topic, body}`' },
    { from: 'n1', to: 'terminal', label: '202 + messageId' },
  ],
  cards: {
    terminal: { title: '202 Accepted', detail: 'not final yet', tone: 'pending' },
    n1: { title: 'Envelope', detail: 'signed by bob' },
  },
};

const gossip = {
  title: 'Gossip',
  text: 'Bob\'s node gossips the envelope to alice and carol. Each checks the signature and that bob is a member, '
    + 'then holds the message in memory until a block includes it.',
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
  text: 'The application uses fixed sequencing by default: the lead, alice, proposes every block. On her node\'s '
    + 'next block tick it builds block h, applies it to compute the state root, and sends the block with its '
    + 'PREPARE vote.',
  viewText: { terminal: 'Still pending.' },
  wires: [
    { from: 'n0', to: 'n1', label: 'block h + PREPARE' },
    { from: 'n0', to: 'n2', label: 'block h + PREPARE' },
  ],
  cards: { n0: { title: 'Block h · view 0', detail: 'own root `7f3a…`', tone: 'leader' } },
};

const vote = {
  title: 'Vote',
  text: 'Bob and carol check the block and apply it themselves. Each votes PREPARE only if its own state root matches '
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
  command: 'curl -s http://127.0.0.1:$BOB/api/v1/app-chain/chains/orders/blocks | jq .',
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
  text: 'Every node now reports the new tip of `orders` and the same root, so status still prints `members agree`, '
    + 'with a higher height. Agreement after new traffic is the result this tutorial is about.',
  viewText: { terminal: 'Prints `chain orders` with a higher height and `members agree`.' },
  command: STATUS_COMMAND,
  cards: { terminal: { title: 'members agree', detail: 'same root on 3 nodes', tone: 'final' } },
};

const carolDown = {
  title: 'Carol stops',
  text: 'Suppose carol stops her node after the application started. Two members remain, and the threshold is '
    + 'still 2.',
  viewText: { terminal: 'Status shows carol `offline` and `members online 2/3`.' },
  command: './yano.sh appchain stop --node-dir appchain-try/carol/yano-node',
  cards: { n2: { title: 'Offline', detail: 'no votes', tone: 'fail' } },
};

const submitWithoutCarol = {
  ...submit,
  text: 'You submit through bob\'s node as before. It signs the envelope, answers 202, and gossips it to alice.',
  cards: { ...submit.cards, n2: { title: 'Offline', detail: 'no votes', tone: 'fail' } },
};

export default {
  id: 'first-chain-finality',
  type: 'steps',
  title: 'Your first finalized event',
  intro: 'the three nodes from this tutorial, with the command you run at each step. Pick a step, press Play, '
    + 'or switch to “Your terminal” to see only what the commands print.',
  lanes: [
    { id: 'terminal', label: 'Your terminal', note: '`./yano.sh appchain`', kind: 'client' },
    { id: 'n0', label: 'Alice', note: 'lead · devnet producer · proposer', kind: 'leader' },
    { id: 'n1', label: 'Bob', note: 'receives your event', kind: 'member' },
    { id: 'n2', label: 'Carol', note: 'member', kind: 'member' },
  ],
  views: [
    { id: 'default', label: 'Nodes' },
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
      steps: [carolDown, submitWithoutCarol, {
        ...propose,
        text: 'Alice\'s node builds block h and sends it with its PREPARE vote. Only bob can answer.',
        wires: [{ from: 'n0', to: 'n1', label: 'block h + PREPARE' }],
      }, {
        ...vote,
        text: 'Bob re-executes the block and votes. Alice\'s vote and bob\'s vote are two of three, so they form '
          + 'a PreparedQC and then a COMMIT quorum.',
        wires: [{ from: 'n1', to: 'n0', label: 'PREPARE, then COMMIT' }],
        cards: { n1: vote.cards.n1 },
      }, {
        title: 'Final without carol',
        text: 'Block h is final on alice\'s and bob\'s nodes. When carol starts her node again, it fetches the missing '
          + 'blocks from a peer, verifies each block and its certificate, and re-applies them before it catches up.',
        viewText: { terminal: 'Your message is final. Alice and bob agree.' },
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
        text: 'The threshold is sealed into the application when it is created, so it cannot change on a running '
          + 'rehearsal. Rehearse again in a new directory from a template with `threshold: all`. Every block now needs '
          + 'three COMMIT votes.',
        viewText: { terminal: 'Every node starts as before; each chain now needs 3 signatures.' },
        command: 'printf \'threshold: all\\nchains:\\n  - id: orders\\n    recipe: audit-log\\n\' > all.template.yaml\n'
          + './yano.sh appchain try --from all.template.yaml --dir appchain-try-all',
        cards: {
          terminal: { title: 'Nodes up', detail: 'threshold: 3 of 3' },
          n0: { title: 'Ready' },
          n1: { title: 'Ready' },
          n2: { title: 'Ready' },
        },
      }, {
        ...carolDown,
        command: './yano.sh appchain stop --node-dir appchain-try-all/carol/yano-node',
      }, submitWithoutCarol, {
        ...propose,
        text: 'Alice\'s node builds block h and sends it with its PREPARE vote. Only bob can answer.',
        wires: [{ from: 'n0', to: 'n1', label: 'block h + PREPARE' }],
      }, {
        title: 'Stalled',
        text: 'Only two PREPARE votes exist, so no PreparedQC forms. The round times out, but moving to a new view also '
          + 'needs three signed timeouts. Nothing becomes final until carol returns, and the message expires from the '
          + 'pools after 10 minutes by default.',
        viewText: { terminal: 'The submission was accepted, but the tip of `orders` does not move.' },
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
    ['leader', 'lead and proposer'],
    ['member', 'member node'],
    ['final', 'final'],
    ['fail', 'offline or stalled'],
  ],
  sources: [
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/TryCommand.java',
      anchors: ['List.of("alice", "bob", "carol")', 'private static final String LOG_RECIPE = "audit-log";',
        '"orders=" + LOG_RECIPE', 'The lead (alice) starts the devnet; the others follow it.',
        'int http = Ports.firstFree(8080, used);', 'An application submits a message through bob\'s node',
        'Map.of("topic", "orders", "body", "order A-100 created")',
        'Set.of("dir", "from", "chain", "answer", "setting", "without")'] },
    { repo: 'yano-x', path: 'tooling/appchain-formats/src/main/java/org/yanoproject/x/appchain/formats/Draft.java',
      anchors: ['threshold = threshold == null ? "majority" : threshold;', 'case "majority" -> members / 2 + 1;',
        'case "all" -> members;', 'sequencing = sequencing == null ? "fixed" : sequencing;'] },
    { repo: 'yano-x', path: 'tooling/appchain-formats/src/main/java/org/yanoproject/x/appchain/formats/SealedApp.java',
      anchors: ['!members.getFirst().publicKey().equals(values.get("sequencer.proposer"))'] },
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/StatusCommand.java',
      anchors: ['"members agree" : "MISMATCH"', '"  members online "', 'member.online() ? "online " : "offline"'] },
    { repo: 'yano-x', path: 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli/StartCommand.java',
      anchors: ['" is up at "'] },
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
