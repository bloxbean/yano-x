// The Trust Registry's credential-status journey on the launcher's demo
// registry: writes with one-use actor authorizations, a published list, and
// proof-bound answers at the tip or a retained height. Presence values,
// provenance kinds, and exit codes follow StatusAnswer and TRUST_REGISTRY.md.

const COLUMNS = ['Key', 'Presence', 'Revision', 'Bit'];

const put = {
  title: 'Set a status bit',
  text: '`issuer-a` signs a one-use authorization to set index 5 of `list-1` to 1. The chain checks the actor\'s '
    + 'role, key, and policy, applies the write, and consumes the authorization exactly once.',
  command: 'yano-trust put … --actor issuer-a --list list-1 --index 5 --bit 1 --reason 3',
  wires: [{ from: 'issuer', to: 'ledger', label: 'signed put' }],
  state: {
    caption: 'Entries in the `status` collection',
    columns: COLUMNS,
    rows: [['list-1/5', 'ACTIVE', '1', '1']],
  },
  cards: {
    issuer: { title: 'issuer-a', detail: 'issuer-org-a · role issuer' },
    ledger: { title: 'Applied at height 1', detail: 'list-1/5 · revision 1 · ACTIVE', tone: 'final' },
  },
};

const revoke = {
  title: 'Revoke another index',
  text: 'Index 8 was set to 0, then revoked. A revoked entry is a tombstone at revision 2, and it is terminal: '
    + 'no collection in the registry allows restore. A tombstoned index counts as bit 1 in every list projection.',
  command: 'yano-trust revoke … --actor issuer-a --list list-1 --index 8',
  wires: [{ from: 'issuer', to: 'ledger', label: 'signed revoke' }],
  state: {
    caption: 'Entries in the `status` collection',
    columns: COLUMNS,
    rows: [['list-1/5', 'ACTIVE', '1', '1'], ['list-1/8', 'REVOKED', '2', 'counts as 1']],
    highlight: [1],
  },
  cards: { ledger: { title: 'list-1/8', detail: 'revision 2 · REVOKED', tone: 'fail' } },
};

const publish = {
  title: 'Publish the list',
  text: '`publish-list` replays the applied status writes into a bitstring and writes the SHA-256 of the raw '
    + 'bitstring to `status-lists/list-1`. The list itself is a projection; the chain holds its hash.',
  command: 'yano-trust publish-list … --actor issuer-a --list list-1 --purpose revocation',
  wires: [{ from: 'issuer', to: 'ledger', label: 'list hash' }],
  cards: { ledger: { title: 'status-lists/list-1', detail: 'SHA-256 of the bitstring', tone: 'final' } },
};

const answer = {
  title: 'Answer with a proof',
  text: '`status` returns the entry with a state proof at one height under one root, and the certified block that '
    + 'carries that root. Provenance names who wrote it: `DIRECT_ROLE`, by issuer-a, under policy `issuer-write`, '
    + 'bound to the consumed authorization. Without a trust input the answer is consistent only (exit 6).',
  command: 'yano-trust status … --list list-1 --index 5 --members "$YANO_TRUST_MEMBERS"',
  wires: [{ from: 'ledger', to: 'you', label: 'entry + proofs' }],
  checks: [
    { label: 'Every fact is a state proof under the same root', ok: true },
    { label: 'The certified block at that height carries the root', ok: true },
    { label: 'Finality verifies under your pinned members', ok: true },
  ],
  cards: { you: { title: 'ACTIVE · bit 1', detail: '`CALLER_PINNED_ROOT` · exit 5', tone: 'final' } },
};

const absent = {
  title: 'Ask about a key never written',
  text: 'For a key with no entry the answer is `ABSENT`, backed by an exclusion proof: at that height, under that '
    + 'root, the key had no entry. It is not a claim that the identifier never existed anywhere else.',
  command: 'yano-trust status … --subject did:example:nobody',
  wires: [{ from: 'ledger', to: 'you', label: 'exclusion proof' }],
  cards: { you: { title: 'ABSENT', detail: 'exclusion proof · provenance NONE' } },
};

const history = {
  title: 'Ask at an earlier height',
  text: 'Any retained height can be asked: `--height` answers as of that block, under that block\'s root. A height '
    + 'the node has pruned is unavailable, which is not the same as absent.',
  command: 'yano-trust status … --list list-1 --index 5 --height 2',
  wires: [{ from: 'ledger', to: 'you', label: 'answer at height 2' }],
  cards: { you: { title: 'As of height 2', detail: 'proof under that root' } },
};

const serve = {
  title: 'Serve the list',
  text: '`yano-trust serve` serves the list as a W3C Bitstring Status List. Anyone can check that the served '
    + 'bitstring hashes to the value the chain holds; `list` reports `matches chain: true`.',
  command: 'yano-trust list … --list list-1   # matches chain: true',
  wires: [{ from: 'service', to: 'you', label: 'Bitstring Status List' }],
  cards: {
    service: { title: 'GET /status-lists/list-1', detail: 'replayed projection' },
    you: { title: 'matches chain: true', tone: 'ok' },
  },
};

export default {
  id: 'status-registry',
  type: 'steps',
  title: 'Credential status, proven',
  intro: 'the launcher\'s demo registry: `issuer-a` manages `list-1`, and you query it. Commands abbreviate the '
    + '`--url`, `--chain`, and `--seed-file` options.',
  lanes: [
    { id: 'issuer', label: 'issuer-a', note: 'actor key', kind: 'actor' },
    { id: 'ledger', label: 'Registry ledger', note: 'governed map', kind: 'ledger' },
    { id: 'service', label: 'yano-trust serve', note: 'read-only', kind: 'runtime' },
    { id: 'you', label: 'You', note: 'verifier', kind: 'client' },
  ],
  scenarios: [
    {
      id: 'journey',
      label: 'Status journey',
      steps: [put, revoke, publish, answer, absent, history, serve],
    },
    {
      id: 'stale-list',
      label: 'A write after publication',
      summary: 'issuer-a changes a status after the list was published.',
      steps: [put, publish, {
        title: 'Write again',
        text: 'issuer-a revokes index 5 after the list was published. The entry changes, but the published list hash '
          + 'still describes the earlier bitstring.',
        wires: [{ from: 'issuer', to: 'ledger', label: 'signed revoke' }],
        cards: { ledger: { title: 'list-1/5', detail: 'revision 2 · REVOKED', tone: 'fail' } },
      }, {
        title: 'The list no longer matches',
        text: 'The replayed bitstring now differs from the published hash, so `list` reports `matches chain: false`. '
          + 'Run `publish-list` again so served lists match the chain.',
        wires: [{ from: 'service', to: 'you', label: 'replayed list' }],
        cards: { you: { title: 'matches chain: false', detail: 'publish again', tone: 'fail' } },
      }],
    },
    {
      id: 'wrong-members',
      label: 'A wrong members file',
      summary: 'You verify an exported answer against the wrong member set.',
      steps: [put, {
        ...answer,
        title: 'Verify offline',
        text: '`yano-trust verify --answer answer.json --members` checks the finality certificate under the members '
          + 'you pinned. With a wrong member set, or a tampered entry, verification fails: exit 4.',
        command: 'yano-trust verify --answer answer.json --members wrong-members.json',
        checks: [
          { label: 'Every fact is a state proof under the same root', ok: true },
          { label: 'The certified block at that height carries the root', ok: true },
          { label: 'Finality verifies under your pinned members', ok: false, code: 'exit 4' },
        ],
        cards: { you: { title: 'Invalid', detail: 'exit 4', tone: 'fail' } },
      }],
    },
  ],
  legend: [
    ['actor', 'issuer (actor key)'],
    ['ledger', 'registry ledger'],
    ['runtime', 'read service'],
    ['client', 'you'],
  ],
  sources: [
    { repo: 'yano-x', path: 'docs/appchain/TRUST_REGISTRY.md',
      anchors: ['Applied at height 1: status/list-1/5 revision 1 ACTIVE', 'which answers `revision 2 REVOKED`',
        'A tombstoned status index counts as bit 1 in every projection', 'No collection allows restore',
        '`--height 2` answers as of height 2', 'answers `ABSENT` with an absence proof', '(`matches chain: true`, exit 0)',
        'run `publish-list` again', 'a wrong member set or a tampered entry exits 4',
        'The hash the chain holds is the SHA-256 of the raw, uncompressed bitstring'] },
    { repo: 'yano-x', path: 'products/trust-registry/client/src/main/java/org/yanoproject/x/trust/client/StatusAnswer.java',
      anchors: ['ACTIVE, REVOKED, ABSENT', 'NONE, GENESIS, RECEIPT, DIRECT_ROLE'] },
  ],
};
