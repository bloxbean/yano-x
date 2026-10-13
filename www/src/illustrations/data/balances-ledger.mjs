// balances as a rule simulator: who may mint, which account a transfer
// debits, how amounts are stored, and the two rejection codes. Account names
// and amounts are example data; encodings are BigInteger.toByteArray().

const MINT_RULE = 'No minter is configured, or the sender is the minter';
const FUNDS_RULE = 'The sender’s own account holds at least the amount';
const COLUMNS = ['Key', 'Amount', 'Stored bytes'];

const mint = {
  title: 'Mint to B',
  text: 'Member M is the configured minter. It mints 200 to B’s account, which is named by B’s member public key in '
    + 'lowercase hex. The amount is stored as `BigInteger.toByteArray()`, so 200 is `00c8`: a sign byte comes first.',
  wires: [{ from: 'm', to: 'sm', label: 'MINT `<B key hex>`, 200' }],
  checks: [{ label: MINT_RULE, ok: true }],
  state: { caption: 'balances state', columns: COLUMNS, rows: [['`b/<B key hex>`', '200', '`00c8`']], highlight: [0] },
  cards: { sm: { title: 'B: 200', tone: 'final' } },
};

const transfer = {
  title: 'Transfer to alice',
  text: 'Member B transfers 50 to `alice`. A transfer always debits the sender’s own account; the command names only '
    + 'the recipient. `alice` is not a member key, so nobody can ever spend from it: it is receive-only.',
  wires: [{ from: 'b', to: 'sm', label: 'TRANSFER `alice`, 50' }],
  checks: [{ label: FUNDS_RULE, ok: true }],
  state: {
    caption: 'balances state',
    columns: COLUMNS,
    rows: [['`b/<B key hex>`', '150', '`0096`'], ['`b/alice`', '50', '`32`']],
    highlight: [0, 1],
  },
  cards: { sm: { title: 'B: 150 · alice: 50', tone: 'final' } },
};

const drain = {
  title: 'Drain to zero',
  text: 'Member B transfers its remaining 150 to `alice`. B’s balance reaches zero, so its key is deleted: an '
    + 'absent account means a zero balance.',
  wires: [{ from: 'b', to: 'sm', label: 'TRANSFER `alice`, 150' }],
  checks: [{ label: FUNDS_RULE, ok: true }],
  state: { caption: 'balances state', columns: COLUMNS, rows: [['`b/alice`', '200', '`00c8`']], highlight: [0] },
  cards: { sm: { title: 'B: absent · alice: 200', tone: 'final' } },
};

export default {
  id: 'balances-ledger',
  type: 'steps',
  title: 'Mint and transfer',
  intro: 'a minter, one member, and an application account called `alice`. Try a “What if” to meet each rule that '
    + 'turns a final command into a no-op.',
  lanes: [
    { id: 'm', label: 'Member M', note: 'configured minter', kind: 'member' },
    { id: 'b', label: 'Member B', kind: 'member' },
    { id: 'sm', label: 'balances', note: 'on every member', kind: 'core' },
  ],
  scenarios: [
    { id: 'happy', label: 'Mint, transfer, drain', steps: [mint, transfer, drain] },
    {
      id: 'not-minter',
      label: 'B tries to mint',
      summary: 'With a minter configured, only that member’s MINT commands apply.',
      steps: [mint, {
        title: 'B mints',
        text: 'Member B mints 1000 to itself. B is not the configured minter, so the decision is `BALANCE_NOT_MINTER` '
          + 'and nothing is written.',
        wires: [{ from: 'b', to: 'sm', label: 'MINT `<B key hex>`, 1000' }],
        checks: [{ label: MINT_RULE, ok: false, code: 'BALANCE_NOT_MINTER' }],
        state: { caption: 'balances state (unchanged)', columns: COLUMNS, rows: [['`b/<B key hex>`', '200', '`00c8`']] },
        cards: { b: { title: 'Final, no effect', detail: '`BALANCE_NOT_MINTER`', tone: 'fail' } },
      }],
    },
    {
      id: 'overspend',
      label: 'B overspends',
      summary: 'Balances never go negative.',
      steps: [mint, {
        title: 'B sends 500',
        text: 'Member B transfers 500 to `alice` but holds 200. The decision is `BALANCE_INSUFFICIENT`; neither '
          + 'account changes.',
        wires: [{ from: 'b', to: 'sm', label: 'TRANSFER `alice`, 500' }],
        checks: [{ label: FUNDS_RULE, ok: false, code: 'BALANCE_INSUFFICIENT' }],
        state: { caption: 'balances state (unchanged)', columns: COLUMNS, rows: [['`b/<B key hex>`', '200', '`00c8`']] },
        cards: { b: { title: 'Final, no effect', detail: '`BALANCE_INSUFFICIENT`', tone: 'fail' } },
      }],
    },
    {
      id: 'receive-only',
      label: 'Spend alice’s units',
      summary: 'A transfer has no “from” field. Units in a non-member account cannot move again.',
      steps: [mint, transfer, drain, {
        title: 'B tries to pay for alice',
        text: 'Member B transfers 10 to `carol`, hoping to use alice’s units. The machine debits B’s own account, which '
          + 'is empty, so the decision is `BALANCE_INSUFFICIENT`. No member can sign as `alice`.',
        wires: [{ from: 'b', to: 'sm', label: 'TRANSFER `carol`, 10' }],
        checks: [{ label: FUNDS_RULE, ok: false, code: 'BALANCE_INSUFFICIENT' }],
        state: { caption: 'balances state (unchanged)', columns: COLUMNS, rows: [['`b/alice`', '200', '`00c8`']] },
        cards: { b: { title: 'Final, no effect', detail: 'B’s account is empty', tone: 'fail' } },
      }],
    },
    {
      id: 'uppercase',
      label: 'Mint to an uppercase key',
      summary: 'Account names are compared as exact text. A sender’s account is always lowercase hex.',
      steps: [{
        title: 'Mint to uppercase hex',
        text: 'Member M mints 100 to B’s key written in uppercase hex. That is a different account from B’s own, '
          + 'lowercase account.',
        wires: [{ from: 'm', to: 'sm', label: 'MINT `<B KEY HEX>`, 100' }],
        checks: [{ label: MINT_RULE, ok: true }],
        state: { caption: 'balances state', columns: COLUMNS, rows: [['`b/<B KEY HEX>`', '100', '`64`']], highlight: [0] },
        cards: { sm: { title: 'Uppercase account: 100', tone: 'final' } },
      }, {
        title: 'B tries to spend',
        text: 'Member B transfers 10 to `alice`. The machine debits `b/<B key hex>` in lowercase, which is empty: '
          + '`BALANCE_INSUFFICIENT`. The 100 units are stranded.',
        wires: [{ from: 'b', to: 'sm', label: 'TRANSFER `alice`, 10' }],
        checks: [{ label: FUNDS_RULE, ok: false, code: 'BALANCE_INSUFFICIENT' }],
        state: { caption: 'balances state (unchanged)', columns: COLUMNS, rows: [['`b/<B KEY HEX>`', '100', '`64`']] },
        cards: { b: { title: 'Final, no effect', detail: 'stranded units', tone: 'fail' } },
      }],
    },
  ],
  legend: [
    ['member', 'member node'],
    ['core', 'deterministic state machine'],
    ['final', 'state changed'],
    ['fail', 'no effect'],
  ],
  sources: [
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/BalancesTransitions.java',
      anchors: ['!minterHex.isEmpty() && !minterHex.equals(sender)', '"BALANCE_NOT_MINTER"',
        'facts.senderBalance().compareTo(command.amount()) < 0', '"BALANCE_INSUFFICIENT"',
        'String sender = HexUtil.encodeHexString(context.sender());',
        'amount.signum() == 0 ? StateMutation.delete(key) : StateMutation.put(key, amount.toByteArray())'] },
    { repo: 'yano-x', path: 'state-machines/stdlib-contracts/src/main/java/org/yanoproject/x/stdlib/contracts/BalancesContract.java',
      anchors: ['("b/" + account)', 'return new BigInteger(1, entry);', 'amount.signum() <= 0'] },
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/BalancesStateMachine.java',
      anchors: ['machines.balances.minter must be a 32-byte hex Ed25519 member public key',
        'minterHex.trim().toLowerCase()'] },
  ],
};
