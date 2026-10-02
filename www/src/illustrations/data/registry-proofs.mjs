// Tutorial 2 as a step-through: why you compare the proven entry, not the
// state root. Heights and roots are illustrative; the rules are the
// kv-registry decision and the host's per-block message-root record.

const ENTRY_A = '`[node 1 key, "active"]`';

export default {
  id: 'registry-proofs',
  type: 'steps',
  title: 'Prove a registry entry',
  intro: 'the commands of this tutorial, one step at a time. Watch the state table: the root changes with every '
    + 'block, but the proven entry changes only when its owner writes.',
  lanes: [
    { id: 'you', label: 'Your shell', note: 'curl and the launcher', kind: 'client' },
    { id: 'n1', label: 'Node 1', note: 'port 7071', kind: 'member' },
    { id: 'n2', label: 'Node 2', note: 'port 7072', kind: 'member' },
    { id: 'chain', label: 'registry-chain', note: 'kv-registry', kind: 'core' },
  ],
  scenarios: [{
    id: 'tutorial',
    label: 'Tutorial 2',
    steps: [{
      title: 'Write as node 1',
      text: 'The launcher sends PUT `supplier-42` = `active` through node 1. Node 1 signs the envelope, so its member '
        + 'key becomes the owner when the block is final.',
      command: './yano.sh appchain cluster kv registry-chain set supplier-42 active --node 1',
      wires: [{ from: 'you', to: 'n1', label: 'PUT `supplier-42`' }],
      cards: { n1: { title: 'Signed and gossiped', detail: '202' } },
    }, {
      title: 'Wait for the proof',
      text: 'Poll the proof endpoint until the key is `PRESENT`. The response carries the height, the state root, the '
        + 'value and an MPF proof of that value under that root.',
      wires: [{ from: 'chain', to: 'you', label: 'proof: `PRESENT`' }],
      state: {
        caption: 'What the proof shows (illustrative heights and roots)',
        columns: ['Height', 'State root', '`supplier-42`'],
        rows: [['4', '`1a2b…`', ENTRY_A]],
        highlight: [0],
      },
      cards: { chain: { title: 'Entry final', detail: ENTRY_A, tone: 'final' } },
    }, {
      title: 'Write as node 2',
      text: 'You send PUT `supplier-42` = `suspended` through node 2 over REST, then wait until that message is final. '
        + 'Node 2 does not own the key, so the decision is `KV_NOT_OWNER` and nothing is written.',
      wires: [{ from: 'you', to: 'n2', label: 'POST PUT `suspended`' }],
      checks: [{ label: 'The key has no entry, or the sender owns it', ok: false, code: 'KV_NOT_OWNER' }],
      cards: { n2: { title: 'Final, no effect', detail: '`KV_NOT_OWNER`', tone: 'fail' } },
    }, {
      title: 'Compare',
      text: 'Read the proof again. The entry is byte-for-byte the same. The root is not: every block also writes a '
        + 'record of its own messages root, so the root moves with every block. Compare the proven entry, not the root.',
      wires: [{ from: 'chain', to: 'you', label: 'proof at a later height' }],
      state: {
        caption: 'Before and after the rejected write',
        columns: ['Height', 'State root', '`supplier-42`'],
        rows: [['4', '`1a2b…`', ENTRY_A], ['5', '`7c9d…`', ENTRY_A]],
        highlight: [1],
      },
      cards: { you: { title: 'Entry unchanged', detail: 'root changed', tone: 'ok' } },
    }, {
      title: 'Update as owner',
      text: 'Node 1 writes `suspended`. It owns the key, so the value changes, and the next proof shows it.',
      command: './yano.sh appchain cluster kv registry-chain set supplier-42 suspended --node 1',
      wires: [{ from: 'you', to: 'n1', label: 'PUT `suspended`' }],
      checks: [{ label: 'The key has no entry, or the sender owns it', ok: true }],
      state: {
        caption: 'After the owner’s write',
        columns: ['Height', 'State root', '`supplier-42`'],
        rows: [['4', '`1a2b…`', ENTRY_A], ['5', '`7c9d…`', ENTRY_A], ['6', '`e04f…`', '`[node 1 key, "suspended"]`']],
        highlight: [2],
      },
      cards: { chain: { title: 'Value replaced', detail: '`[node 1 key, "suspended"]`', tone: 'final' } },
    }],
  }],
  legend: [
    ['client', 'your shell'],
    ['member', 'member node'],
    ['core', 'deterministic state machine'],
    ['fail', 'no effect'],
  ],
  sources: [
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/KvRegistryTransitions.java',
      anchors: ['"KV_NOT_OWNER"', 'encodeEntry(context.sender(), command.value())'] },
    { repo: 'yano-x', path: 'scripts/appchain-cluster/cluster.sh',
      anchors: ['kv_cbor() {', 'set|put|PUT)'] },
    { repo: 'yano-x', path: 'config/application-appchain.yml',
      anchors: ['chain-id: "registry-chain"', 'state-machine: kv-registry', 'value-format: utf8'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/transition/FinalizedBlockMessageRootIndex.java',
      anchors: ['LOGICAL_NAMESPACE = "~yano/finalized-block-messages/v1/"',
        'StateMutation.put(blockKey(block.height()), record.canonicalBytes())'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/transition/FinalizedBlockMessageRootIndexedStateMachine.java',
      anchors: ['source.getOrDefault(ENABLED_SETTING, "true")'] },
    { repo: 'yano', path: 'app/src/main/java/org/yanoproject/app/api/appchain/AppChainResource.java',
      anchors: ['result.put("presence", proof.presence().name());', 'result.put("valueHex"',
        'result.put("stateRoot", HexUtil.encodeHexString(stateRoot));', '"No finalized message with id "'] },
  ],
};
