// Setting up an application with other operators (ADR-056): the coordinator
// drafts, each operator makes a key and a member card, the coordinator seals,
// everyone compares the fingerprint, then each operator joins and starts.
// Messages and checks come from the appchain-cli commands.

const CLI = 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli';

const draft = {
  title: 'New',
  text: 'The coordinator answers a few questions: the application name, the Cardano network, the operators (the '
    + 'first is the lead), the chains, and any answers their recipes need. `new` writes a draft with a random '
    + 'invite code. It can be edited freely until seal. Instead of sending files, `invite --serve` can hand out '
    + 'one join link per operator.',
  command: './yano.sh appchain new supply',
  wires: [{ from: 'coord', to: 'ops', label: 'send `supply.draft.yaml`' }],
  cards: { coord: { title: '`supply.draft.yaml`', detail: 'invite 7c1e-52aa-9d03' } },
};

const request = {
  title: 'Request',
  text: 'Each operator, on their own machine, creates the node directory and its member key, and writes a member '
    + 'card: name, public key, the address other members reach this node at, and a signature over the invite. '
    + 'The private key never leaves the node directory.',
  command: './yano.sh appchain request supply.draft.yaml',
  wires: [{ from: 'ops', to: 'coord', label: 'send `alice.member`, …' }],
  cards: { ops: { title: 'Key + member card', detail: 'key id a6622032b8e615ee' } },
};

const seal = {
  title: 'Seal',
  text: 'With every card beside the draft, `seal` checks each card, resolves every chain once, and writes '
    + '`supply.chain`. It prints the members with their key ids and the chain fingerprint.',
  command: './yano.sh appchain seal supply.draft.yaml',
  wires: [{ from: 'coord', to: 'ops', label: 'send `supply.chain`' }],
  cards: { coord: { title: 'Sealed', detail: 'fingerprint 94c9-2bad-…', tone: 'ok' } },
};

const JOIN_CHECKS = [
  { label: 'The chain fingerprint matches the one read out on the call', ok: true },
  { label: 'This node\'s key is in the sealed roster under its own name', ok: true },
  { label: 'The installed plugin bundles match the sealed ones', ok: true },
];

const join = {
  title: 'Join',
  text: 'On a call, the coordinator reads out the fingerprint and each operator reads out their key id. Each '
    + 'operator then runs `join`, which shows the same summary and asks them to confirm. It writes the node '
    + 'configuration from the sealed settings.',
  command: './yano.sh appchain join supply.chain',
  checks: JOIN_CHECKS,
  cards: { ops: { title: 'Joined', detail: 'config written', tone: 'ok' } },
};

const start = {
  title: 'Start',
  text: 'Each operator starts their node. On a devnet the lead starts first: its node creates the Cardano devnet, '
    + 'and every other node downloads that genesis from it.',
  command: './yano.sh appchain start',
  wires: [{ from: 'ops', to: 'nodes', label: 'start' }],
  cards: { nodes: { title: 'Starting', detail: 'lead first on devnet', tone: 'pending' } },
};

const status = {
  title: 'Status',
  text: 'Anyone runs `status`. It asks every member\'s node for each chain\'s height, root and identity, and shows '
    + 'the stage. Once every member is online and agrees, the application is live. It also shows this node\'s '
    + 'Cardano view and plugin catalog fingerprint, which the members compare.',
  command: './yano.sh appchain status supply',
  wires: [{ from: 'nodes', to: 'ops', label: 'members online 3/3' }],
  cards: { nodes: { title: 'Live', detail: 'members agree', tone: 'final' } },
};

export default {
  id: 'setup-ceremony',
  type: 'steps',
  title: 'Set up an application, step by step',
  intro: 'a coordinator and three operators (alice, bob and carol) set up the `supply` application. Only public '
    + 'files travel between them. The “What if” scenario shows how a substituted card is caught.',
  lanes: [
    { id: 'coord', label: 'Coordinator', note: 'defines the application', kind: 'actor' },
    { id: 'ops', label: 'Operators', note: 'one machine each', kind: 'member' },
    { id: 'nodes', label: 'Member nodes', note: 'one per operator', kind: 'runtime' },
  ],
  scenarios: [
    { id: 'setup', label: 'Set up', steps: [draft, request, seal, join, start, status] },
    {
      id: 'substituted',
      label: 'A card is swapped',
      summary: 'A card proves its key, not who sent it. The operator it replaced notices at join.',
      steps: [draft, request, {
        ...seal,
        text: 'Suppose someone sends the coordinator a card named `carol` that carries their own key, and it is '
          + 'sealed. The summary shows a key id that carol does not recognize.',
        cards: { coord: { title: 'Sealed', detail: 'carol: wrong key', tone: 'pending' } },
      }, {
        ...join,
        text: 'Carol\'s `join` refuses because her own key is not in the roster: “This node\'s key (…) is not a '
          + 'member of supply”. The key ids compared on the call show the same thing. The coordinator seals again '
          + 'with the right card.',
        checks: [JOIN_CHECKS[0], { ...JOIN_CHECKS[1], ok: false, code: 'is not a member of supply' },
          { ...JOIN_CHECKS[2], ok: null }],
        cards: { ops: { title: 'Join refused', detail: 'carol\'s key missing', tone: 'fail' } },
      }],
    },
  ],
  legend: [
    ['actor', 'coordinator'],
    ['member', 'operators'],
    ['runtime', 'their nodes'],
  ],
  sources: [
    { repo: 'yano-x', path: `${CLI}/NewCommand.java`,
      anchors: ['to every operator (you too); each runs', 'Names.newInvite()'] },
    { repo: 'yano-x', path: `${CLI}/RequestCommand.java`,
      anchors: ['with a new member key (keep it safe; ', '--host <public-host>'] },
    { repo: 'yano-x', path: `${CLI}/SealCommand.java`,
      anchors: ['terminal.ok("Sealed " + output);', 'On a call, read out the chain'] },
    { repo: 'yano-x', path: `${CLI}/JoinCommand.java`,
      anchors: ['is not a member of', 'Do not start: the file differs from the one the coordinator sealed.',
        'This distribution\'s plugin bundles differ from the sealed ones: '] },
    { repo: 'yano-x', path: `${CLI}/StatusCommand.java`,
      anchors: ['stage = "Live";', 'members online ', 'ask the lead ('] },
  ],
};
