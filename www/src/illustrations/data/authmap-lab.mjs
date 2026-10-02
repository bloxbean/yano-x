// authenticated-map mutations against one owner collection, with the order of
// checks in AuthenticatedMapStateMachine.transition and the receipt codes it
// records. Collection names follow the documented example; values are examples.

const COLUMNS = ['Entry', 'Status', 'Revision', 'Controller', 'Value'];
const row = (status, revision, controller, value) => ['`products` / `sku-42`', status, revision, controller, value];

const create = {
  title: 'A creates',
  text: 'Member A sends PUT `products` / `sku-42`. The entry is absent, so PUT may create it. In an `owner` '
    + 'collection the creator becomes the controller. The receipt records APPLIED at revision 1.',
  wires: [{ from: 'a', to: 'map', label: 'PUT `sku-42`' }],
  checks: [
    { label: 'Absent entries accept only PUT or PUT_IF_ABSENT', ok: true },
    { label: 'Encoding, schema and validator accept the value', ok: true },
  ],
  state: { caption: 'authenticated-map state', columns: COLUMNS, rows: [row('ACTIVE', '1', 'A', '`product v1`')], highlight: [0] },
  cards: { map: { title: 'Receipt: APPLIED', detail: 'revision 1', tone: 'final' } },
};

const cas = {
  title: 'A updates with a precondition',
  text: 'Member A sends COMPARE_AND_SET with expected revision 1. The entry is still at revision 1, so the value is '
    + 'replaced and the revision becomes 2. CAS needs a revision, a value hash, or both.',
  wires: [{ from: 'a', to: 'map', label: 'CAS `sku-42`, expect rev 1' }],
  checks: [
    { label: 'The sender is the controller', ok: true },
    { label: 'The entry is ACTIVE', ok: true },
    { label: 'The expected revision and value hash match', ok: true },
    { label: 'Encoding, schema and validator accept the value', ok: true },
  ],
  state: { caption: 'authenticated-map state', columns: COLUMNS, rows: [row('ACTIVE', '2', 'A', '`product v2`')], highlight: [0] },
  cards: { map: { title: 'Receipt: APPLIED', detail: 'revision 2', tone: 'final' } },
};

const transfer = {
  title: 'A hands control to B',
  text: 'Member A sends TRANSFER_CONTROLLER to member B’s key, expecting revision 2. Only `owner` collections allow '
    + 'this. The value is kept; the revision becomes 3.',
  wires: [{ from: 'a', to: 'map', label: 'TRANSFER_CONTROLLER → B' }],
  checks: [
    { label: 'The sender is the controller', ok: true },
    { label: 'The entry is ACTIVE', ok: true },
    { label: 'The collection uses `owner` authorization', ok: true },
    { label: 'The expected revision and value hash match', ok: true },
  ],
  state: { caption: 'authenticated-map state', columns: COLUMNS, rows: [row('ACTIVE', '3', 'B', '`product v2`')], highlight: [0] },
  cards: { map: { title: 'Receipt: APPLIED', detail: 'controller B', tone: 'final' } },
};

const revoke = {
  title: 'B revokes',
  text: 'Member B, now the controller, sends REVOKE. The entry becomes a tombstone at revision 4: the value bytes '
    + 'are removed, the last value hash is kept, and a proof can show REVOKED rather than absent.',
  wires: [{ from: 'b', to: 'map', label: 'REVOKE `sku-42`' }],
  checks: [
    { label: 'The sender is the controller', ok: true },
    { label: 'The entry is ACTIVE', ok: true },
    { label: 'The expected revision and value hash match', ok: true },
  ],
  state: { caption: 'authenticated-map state', columns: COLUMNS, rows: [row('REVOKED', '4', 'B', '(removed)')], highlight: [0] },
  cards: { map: { title: 'Receipt: APPLIED', detail: 'tombstone', tone: 'final' } },
};

const rejected = (title, text, from, label, checks, code, rows) => ({
  title,
  text,
  wires: [{ from, to: 'map', label }],
  checks,
  state: { caption: 'authenticated-map state (unchanged)', columns: COLUMNS, rows },
  cards: { map: { title: 'Receipt: REJECTED', detail: `error ${code}`, tone: 'fail' } },
});

const aRevokes = {
  title: 'A revokes',
  text: 'Member A, the controller, revokes the entry. It becomes a tombstone at revision 2.',
  wires: [{ from: 'a', to: 'map', label: 'REVOKE `sku-42`' }],
  checks: [
    { label: 'The sender is the controller', ok: true },
    { label: 'The entry is ACTIVE', ok: true },
    { label: 'The expected revision and value hash match', ok: true },
  ],
  state: { caption: 'authenticated-map state', columns: COLUMNS, rows: [row('REVOKED', '2', 'A', '(removed)')], highlight: [0] },
  cards: { map: { title: 'Receipt: APPLIED', detail: 'tombstone', tone: 'final' } },
};

export default {
  id: 'authmap-lab',
  type: 'steps',
  title: 'Mutate an authenticated map',
  intro: 'two members and the `products` collection, which uses `owner` authorization and does not allow restore. '
    + 'Every final command leaves a receipt. A “What if” ends on the check that rejects, with its receipt code.',
  lanes: [
    { id: 'a', label: 'Member A', kind: 'member' },
    { id: 'b', label: 'Member B', kind: 'member' },
    { id: 'map', label: 'authenticated-map', note: 'on every member', kind: 'core' },
  ],
  scenarios: [
    { id: 'lifecycle', label: 'Create to tombstone', steps: [create, cas, transfer, revoke] },
    {
      id: 'stale-cas',
      label: 'A stale precondition',
      summary: 'Two writers race; the second one expected an older revision.',
      steps: [create, cas, rejected('A updates from an old read',
        'Member A sends CAS expecting revision 1, but the entry is at revision 2. The precondition fails.',
        'a', 'CAS `sku-42`, expect rev 1', [
          { label: 'The sender is the controller', ok: true },
          { label: 'The entry is ACTIVE', ok: true },
          { label: 'The expected revision and value hash match', ok: false, code: 'PRECONDITION (8)' },
          { label: 'Encoding, schema and validator accept the value', ok: null },
        ], 8, [row('ACTIVE', '2', 'A', '`product v2`')])],
    },
    {
      id: 'not-controller',
      label: 'Not the controller',
      summary: 'In an `owner` collection, only the controller may change an entry.',
      steps: [create, rejected('B overwrites',
        'Member B sends PUT `sku-42`. The entry exists and A controls it, so authorization fails first.',
        'b', 'PUT `sku-42`', [
          { label: 'The sender is the controller', ok: false, code: 'UNAUTHORIZED (3)' },
          { label: 'The entry is ACTIVE', ok: null },
        ], 3, [row('ACTIVE', '1', 'A', '`product v1`')])],
    },
    {
      id: 'exists',
      label: 'PUT_IF_ABSENT on an entry',
      summary: 'PUT_IF_ABSENT succeeds only when no entry or tombstone exists.',
      steps: [create, rejected('A creates again',
        'Member A sends PUT_IF_ABSENT for the same key. The entry is active, so the operation fails.',
        'a', 'PUT_IF_ABSENT `sku-42`', [
          { label: 'The sender is the controller', ok: true },
          { label: 'The entry is ACTIVE', ok: true },
          { label: 'An active entry rejects PUT_IF_ABSENT', ok: false, code: 'ALREADY_EXISTS (4)' },
        ], 4, [row('ACTIVE', '1', 'A', '`product v1`')])],
    },
    {
      id: 'tombstone',
      label: 'PUT_IF_ABSENT on a tombstone',
      summary: 'A tombstone is not absence. The only operation it accepts is RESTORE.',
      steps: [create, aRevokes, rejected('A creates again',
        'Member A sends PUT_IF_ABSENT for the revoked key. The tombstone still exists, so the entry is REVOKED, not '
          + 'absent.', 'a', 'PUT_IF_ABSENT `sku-42`', [
          { label: 'The sender is the controller', ok: true },
          { label: 'The entry is ACTIVE, or the operation is RESTORE', ok: false, code: 'REVOKED (6)' },
        ], 6, [row('REVOKED', '2', 'A', '(removed)')])],
    },
    {
      id: 'restore',
      label: 'Restore is not allowed',
      summary: '`products` sets `restoreAllowed: false`.',
      steps: [create, aRevokes, rejected('A restores',
        'Member A sends RESTORE. The collection does not allow it, so the tombstone stays.',
        'a', 'RESTORE `sku-42`', [
          { label: 'The sender is the controller', ok: true },
          { label: 'The entry is ACTIVE, or the operation is RESTORE', ok: true },
          { label: 'The collection allows restore', ok: false, code: 'RESTORE_FORBIDDEN (9)' },
        ], 9, [row('REVOKED', '2', 'A', '(removed)')])],
    },
    {
      id: 'member-transfer',
      label: 'Transfer in a member collection',
      summary: '`canonical-events` uses `member` authorization: entries have no controller to transfer.',
      steps: [{
        title: 'A writes an event',
        text: 'Member A sends PUT `canonical-events` / `evt-1`. Any active member may write here; no controller is '
          + 'recorded.',
        wires: [{ from: 'a', to: 'map', label: 'PUT `evt-1`' }],
        checks: [
          { label: 'The sender is a member at this height', ok: true },
          { label: 'Encoding, schema and validator accept the value', ok: true },
        ],
        state: { caption: 'authenticated-map state', columns: COLUMNS,
          rows: [['`canonical-events` / `evt-1`', 'ACTIVE', '1', '(none)', '`["created", 1]`']], highlight: [0] },
        cards: { map: { title: 'Receipt: APPLIED', detail: 'revision 1', tone: 'final' } },
      }, {
        title: 'A transfers control',
        text: 'Member A sends TRANSFER_CONTROLLER. A passes the member check, but only `owner` collections support '
          + 'a transfer.',
        wires: [{ from: 'a', to: 'map', label: 'TRANSFER_CONTROLLER `evt-1`' }],
        checks: [
          { label: 'The sender is a member at this height', ok: true },
          { label: 'The entry is ACTIVE', ok: true },
          { label: 'The collection uses `owner` authorization', ok: false, code: 'UNAUTHORIZED (3)' },
        ],
        state: { caption: 'authenticated-map state (unchanged)', columns: COLUMNS,
          rows: [['`canonical-events` / `evt-1`', 'ACTIVE', '1', '(none)', '`["created", 1]`']] },
        cards: { map: { title: 'Receipt: REJECTED', detail: 'error 3', tone: 'fail' } },
      }],
    },
    {
      id: 'batch',
      label: 'A batch with one bad item',
      summary: 'A batch is all or nothing.',
      steps: [create, {
        title: 'A sends a batch',
        text: 'Member A sends a batch: PUT `sku-43` (new) and PUT_IF_ABSENT `sku-42` (exists). The second item fails, '
          + 'so the receipt records error 4 and neither entry changes: `sku-43` is not created.',
        wires: [{ from: 'a', to: 'map', label: 'batch: PUT `sku-43`, PUT_IF_ABSENT `sku-42`' }],
        checks: [
          { label: 'Item 1: an absent entry accepts PUT', ok: true },
          { label: 'Item 2: an active entry rejects PUT_IF_ABSENT', ok: false, code: 'ALREADY_EXISTS (4)' },
        ],
        state: { caption: 'authenticated-map state (unchanged)', columns: COLUMNS, rows: [row('ACTIVE', '1', 'A', '`product v1`')] },
        cards: { map: { title: 'Receipt: REJECTED', detail: 'error 4, nothing written', tone: 'fail' } },
      }],
    },
  ],
  legend: [
    ['member', 'member node'],
    ['core', 'deterministic state machine'],
    ['final', 'applied'],
    ['fail', 'rejected, with a receipt'],
  ],
  sources: [
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/AuthenticatedMapStateMachine.java',
      anchors: ['throw failure(AuthenticatedMapContract.ERROR_ABSENT);', 'throw failure(AuthenticatedMapContract.ERROR_REVOKED);',
        'throw failure(AuthenticatedMapContract.ERROR_RESTORE_FORBIDDEN);',
        'throw failure(AuthenticatedMapContract.ERROR_ALREADY_EXISTS);', 'throw failure(AuthenticatedMapContract.ERROR_ACTIVE);',
        'throw failure(AuthenticatedMapContract.ERROR_PRECONDITION);', 'throw failure(AuthenticatedMapContract.ERROR_UNAUTHORIZED);',
        'descriptor.authorization() != AuthenticatedMapContract.AUTH_OWNER',
        'authorize(sender, descriptor, current, governedAuthorized, senderMember);',
        'descriptor.authorization() == AuthenticatedMapContract.AUTH_OWNER ? sender : new byte[0];',
        'Receipt receipt = Receipt.rejected(messageId, height, batchCommitment, rejected.errorCode());',
        'return mapDecision(List.of(), List.of(), receipt);'] },
    { repo: 'yano-x', path: 'state-machines/stdlib-contracts/src/main/java/org/yanoproject/x/stdlib/contracts/AuthenticatedMapContract.java',
      anchors: ['ERROR_UNAUTHORIZED = 3', 'ERROR_ALREADY_EXISTS = 4', 'ERROR_ABSENT = 5', 'ERROR_REVOKED = 6',
        'ERROR_ACTIVE = 7', 'ERROR_PRECONDITION = 8', 'ERROR_RESTORE_FORBIDDEN = 9',
        '"compare-and-set requires a revision or value hash"',
        'return new Entry(STATUS_REVOKED, Math.addExact(revision, 1), controller, new byte[0], logicalValueHash, createdHeight, height);'] },
    { repo: 'yano-x', path: 'docs/appchain/state-machines/authenticated-map.md',
      anchors: ['id: products', 'id: canonical-events', 'authorization: member'] },
  ],
};
