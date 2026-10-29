// Adding or removing a member of a running application with `approve`
// (ADR-056 D5): the quorum-safe procedure every existing operator runs.
// Messages come from the appchain-cli approve and request commands.

const CLI = 'tooling/appchain-cli/src/main/java/org/yanoproject/x/appchain/cli';

const request = {
  title: 'Request',
  text: 'The new operator, dave, runs `request` against the sealed file on his own machine. It makes his key and '
    + 'a member card. He sends the card to every existing operator.',
  command: './yano.sh appchain request supply.chain',
  wires: [{ from: 'dave', to: 'ops', label: 'send `dave.member`' }],
  cards: { dave: { title: 'Key + member card', detail: 'key id ee22fe112c2e69cb' } },
};

const approve = {
  title: 'Approve',
  text: 'Each existing operator runs `approve` once, in any order. It is safe to run again: it skips what is '
    + 'already done and waits for what is not.',
  command: './yano.sh appchain approve dave.member',
  wires: [{ from: 'ops', to: 'chain', label: 'approve' }],
  cards: { ops: { title: 'Approving', detail: '3 operators', tone: 'pending' } },
};

const threshold = {
  title: 'Raise the threshold',
  text: 'Two of three is a majority; two of four is not. So `approve` first raises the threshold to three and waits '
    + 'until enough operators approve and the chain makes ten more blocks. It refuses if fewer than three members '
    + 'are online, so growth cannot stall the chain.',
  wires: [{ from: 'ops', to: 'chain', label: 'threshold 3' }],
  cards: { chain: { title: 'Threshold 3', detail: 'active after 10 blocks', tone: 'pending' } },
};

const add = {
  title: 'Add',
  text: 'Then it submits the member change and waits again until it is active. A chain makes blocks only when it '
    + 'has messages; on an idle chain, `approve` offers to send small heartbeat messages, or you send ordinary '
    + 'traffic.',
  wires: [{ from: 'ops', to: 'chain', label: 'add dave' }],
  cards: { chain: { title: 'dave is a member', detail: '4 members, 3 signatures', tone: 'ok' } },
};

const restart = {
  title: 'Restart',
  text: 'Each operator\'s `approve` adds dave as a peer in its own node configuration and restarts that node once.',
  cards: { ops: { title: 'Restarted', detail: 'peer dave added', tone: 'ok' } },
};

const joinStart = {
  title: 'Join and start',
  text: 'Dave runs `join` with the same sealed file and then `start`. His node catches up from the other members.',
  command: './yano.sh appchain join supply.chain\n./yano.sh appchain start',
  wires: [{ from: 'dave', to: 'chain', label: 'catch up' }],
  cards: { dave: { title: 'Member', detail: 'caught up', tone: 'final' } },
};

export default {
  id: 'member-change',
  type: 'steps',
  title: 'Add a member, step by step',
  intro: 'dave joins the running `supply` application, which alice, bob and carol run with two signatures per '
    + 'block. The “What if” scenario shows a lost key.',
  lanes: [
    { id: 'dave', label: 'Joining operator', note: 'dave, or carol again', kind: 'actor' },
    { id: 'ops', label: 'Existing operators', note: 'alice, bob, carol', kind: 'member' },
    { id: 'chain', label: 'Each chain', note: 'governed membership', kind: 'ledger' },
  ],
  scenarios: [
    { id: 'add', label: 'Add a member', steps: [request, approve, threshold, add, restart, joinStart] },
    {
      id: 'lost-key',
      label: 'A key is lost',
      summary: 'Remove the lost key first, then add the new one. Adding first would raise the threshold to a level '
        + 'the lost key can never help reach.',
      steps: [{
        title: 'Remove',
        text: 'Carol lost her node directory. The other operators each run `approve --remove carol`. The chain keeps '
          + 'finalizing with alice and bob.',
        command: './yano.sh appchain approve --remove carol',
        wires: [{ from: 'ops', to: 'chain', label: 'remove carol' }],
        cards: { chain: { title: 'carol removed', detail: '2 members, 2 signatures', tone: 'ok' } },
      }, {
        ...request,
        text: 'Carol makes a fresh node directory and runs `request supply.chain`, which creates a new key and card.',
        wires: [{ from: 'dave', to: 'ops', label: 'send `carol.member`' }],
        cards: { dave: { title: 'New key + card', detail: 'carol' } },
      }, {
        ...approve,
        command: './yano.sh appchain approve carol.member',
        text: 'The others approve the new card as for any new member, and carol joins and starts. With fixed '
          + 'sequencing (the default) the lead\'s key is the exception: the runtime refuses to remove the fixed '
          + 'proposer, so losing it means sealing a new application.',
      }],
    },
  ],
  legend: [
    ['actor', 'the joining operator'],
    ['member', 'existing operators'],
    ['ledger', 'each chain of the application'],
  ],
  sources: [
    { repo: 'yano-x', path: `${CLI}/ApproveCommand.java`,
      anchors: [' the threshold from ', '"/admin/members/" + (remove == null ? "add" : "remove")',
        'bring the others online first so the chain cannot stall', 'Restarting this node to ',
        'Losing the lead\'s key means sealing a new application.'] },
    { repo: 'yano-x', path: `${CLI}/RequestCommand.java`,
      anchors: ['<app>.draft.yaml|<app>.chain'] },
    { repo: 'yano-x', path: `${CLI}/Heartbeat.java`, anchors: ['class Heartbeat'] },
  ],
};
