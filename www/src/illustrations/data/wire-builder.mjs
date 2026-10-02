// How a stock command becomes bytes, and which admission rule refuses a bad
// one. Hex values were produced with docs/appchain/tutorials/tools/stdlib_command.py;
// the rules follow the stock contracts' strict canonical decoder.

const PUT_HEX = '83004b737570706c6965722d343246616374697665';

const choose = {
  title: 'Write the command',
  text: 'A `kv-registry` PUT is a CBOR array: operation `0`, the key bytes, and the value bytes. The machine reads '
    + 'only the body; the topic is a label.',
  command: 'python3 docs/appchain/tutorials/tools/stdlib_command.py kv-registry put supplier-42 --value-text active',
  cards: { app: { title: '[0, "supplier-42", "active"]', detail: 'key and value are byte strings' } },
};

const encode = {
  title: 'Encode',
  text: '`83` opens an array of three items. `00` is operation 0. `4b` starts an 11-byte string, the key. `46` '
    + 'starts a 6-byte string, the value. Stock contracts accept only this shortest, canonical form.',
  cards: { app: { title: 'bodyHex', detail: `\`${PUT_HEX}\`` } },
};

const admitChecks = (failAt, code) => {
  const rules = [
    'One canonical CBOR array of three items',
    'Key has 1 to 256 bytes',
    'Operation is 0 (PUT) or 1 (DELETE), and a DELETE carries an empty value',
    'A PUT value is not empty and matches `value-format`',
  ];
  return rules.map((label, i) => ({
    label,
    ok: failAt === undefined || i < failAt ? true : i === failAt ? false : null,
    ...(i === failAt ? { code } : {}),
  }));
};

const admit = {
  title: 'Admit',
  text: 'The receiving member decodes the body against the next block. Every rule passes, so it answers **202** '
    + 'and gossips the message.',
  wires: [{ from: 'app', to: 'member', label: 'POST `{topic, bodyHex}`' }],
  checks: admitChecks(),
  cards: { member: { title: 'Admitted', detail: '202 + messageId', tone: 'ok' } },
};

const apply = {
  title: 'Apply',
  text: 'In the final block every member runs the same decision: the key has no entry, so the sender becomes its '
    + 'owner. Only now has state changed.',
  wires: [{ from: 'member', to: 'sm', label: 'final block' }],
  checks: [{ label: 'The key has no entry, or the sender owns it', ok: true }],
  state: {
    caption: 'kv-registry state after the block',
    columns: ['Key', 'Value'],
    rows: [['`supplier-42`', '`[member 1 key, "active"]`']],
    highlight: [0],
  },
  cards: { sm: { title: 'Entry written', detail: 'owner: member 1', tone: 'final' } },
};

const refused = (title, text, checks, hex, extra = {}) => ({
  title,
  text,
  wires: [{ from: 'member', to: 'app', label: '400 `APPLICATION_REJECTED`', tone: 'fail' }],
  checks,
  cards: {
    app: { title: 'bodyHex', detail: `\`${hex}\`` },
    member: { title: 'Refused', detail: 'not pooled, no message id', tone: 'fail' },
  },
  ...extra,
});

export default {
  id: 'wire-builder',
  type: 'steps',
  title: 'From command to bytes',
  intro: 'one `kv-registry` command from encoding to state. Try a “What if” to see which admission rule refuses a '
    + 'bad body, in the order the decoder checks it.',
  lanes: [
    { id: 'app', label: 'Your encoder', note: 'helper or Java contract', kind: 'client' },
    { id: 'member', label: 'Receiving member', note: 'admission', kind: 'member' },
    { id: 'sm', label: 'kv-registry', note: 'apply, on every member', kind: 'core' },
  ],
  scenarios: [
    { id: 'put', label: 'A valid PUT', steps: [choose, encode, admit, apply] },
    {
      id: 'delete-value',
      label: 'A DELETE with a value',
      summary: 'Operation 1 must carry an empty value. The helper always sends one; this body was written by hand.',
      steps: [{
        title: 'Write the command',
        text: 'A DELETE of `supplier-42` that also carries the value `x`.',
        cards: { app: { title: '[1, "supplier-42", "x"]', detail: 'third item is not empty' } },
      }, refused('Refused at admission', 'The decoder rejects a DELETE whose value is not empty. Admission rejections '
        + 'keep only a safe code, so REST answers **400** with `APPLICATION_REJECTED`. Nothing is pooled.',
      admitChecks(2, 'APPLICATION_REJECTED'), '83014b737570706c6965722d34324178')],
    },
    {
      id: 'long-key',
      label: 'A 300-byte key',
      summary: 'Stock keys are bounded so that every entry can be proved through the standard proof endpoint.',
      steps: [{
        title: 'Write the command',
        text: 'A PUT whose key is 300 bytes long.',
        cards: { app: { title: '[0, <300 bytes>, "active"]', detail: 'key too long' } },
      }, refused('Refused at admission', 'Keys must have 1 to 256 bytes. The member refuses the body before it '
        + 'reaches a block.', admitChecks(1, 'APPLICATION_REJECTED'), '830059012c…')],
    },
    {
      id: 'non-canonical',
      label: 'Non-canonical CBOR',
      summary: 'The same values in a longer encoding are different bytes, so they are refused.',
      steps: [{
        title: 'Write the command',
        text: 'The PUT again, but operation 0 is written as `18 00`, a one-byte integer, instead of `00`.',
        cards: { app: { title: '[0, "supplier-42", "active"]', detail: 'operation encoded as `18 00`' } },
      }, refused('Refused at admission', 'The decoder re-encodes what it read and compares the bytes. `18 00` is '
        + 'not the shortest form of 0, so the body is not canonical.',
      admitChecks(0, 'APPLICATION_REJECTED'), '8318004b737570706c6965722d343246616374697665')],
    },
    {
      id: 'zero-amount',
      label: 'Amount 0 (balances)',
      summary: 'Every stock machine uses the same strict decoder. Here a `balances` MINT carries a zero amount.',
      steps: [{
        title: 'Write the command',
        text: 'A `balances` MINT `[0, "alice", 0]`. The helper refuses it (“amount must be positive”), so this body '
          + 'was written by hand.',
        cards: { app: { title: '[0, "alice", 0]', detail: 'MINT with amount 0' } },
      }, refused('Refused at admission', 'The `balances` decoder requires a positive amount, so the receiving member '
        + 'refuses the body.', [
        { label: 'One canonical CBOR array of three items', ok: true },
        { label: 'Operation is 0 (MINT) or 1 (TRANSFER)', ok: true },
        { label: 'Account is text, and `b/<account>` fits in 256 bytes', ok: true },
        { label: 'Amount is positive', ok: false, code: 'APPLICATION_REJECTED' },
      ], '830065616c69636500')],
    },
  ],
  legend: [
    ['client', 'your application'],
    ['member', 'member node'],
    ['core', 'deterministic state machine'],
    ['fail', 'refused'],
  ],
  sources: [
    { repo: 'yano-x', path: 'state-machines/stdlib-contracts/src/main/java/org/yanoproject/x/stdlib/contracts/KvRegistryContract.java',
      anchors: ['decodeArray(bytes, 3)', 'op == OP_DELETE && value.length != 0', 'op != OP_PUT && op != OP_DELETE',
        'requireStateKey(value, "key")', 'DEFAULT_TOPIC = "kv-registry.command.v1"'] },
    { repo: 'yano-x', path: 'state-machines/stdlib-contracts/src/main/java/org/yanoproject/x/stdlib/contracts/internal/StdlibContractCbor.java',
      anchors: ['MAX_STATE_KEY_BYTES = 256', 'if (!Arrays.equals(input, encode(array))) throw malformed();',
        'must contain 1-256 bytes'] },
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/KvRegistryTransitions.java',
      anchors: ['"PUT requires a value"', 'PUT value does not conform to value-format', '"KV_NOT_OWNER"'] },
    { repo: 'yano-x', path: 'state-machines/stdlib-contracts/src/main/java/org/yanoproject/x/stdlib/contracts/BalancesContract.java',
      anchors: ['"amount must be positive"', 'accountKey(account);'] },
    { repo: 'yano-x', path: 'docs/appchain/tutorials/tools/stdlib_command.py',
      anchors: ['return array(uint(0), bstr(key), bstr(value))', 'raise ValueError("amount must be positive")'] },
    { repo: 'yano', path: 'core-api/src/main/java/org/yanoproject/api/appchain/AppSubmissionRejectedException.java',
      anchors: ['Pattern.compile("[A-Z_]{1,32}")', '"APPLICATION_REJECTED"'] },
    { repo: 'yano', path: 'app/src/main/java/org/yanoproject/app/api/appchain/AppChainResource.java',
      anchors: ['rejection.put("code", e.code());', 'Response.Status.BAD_REQUEST'] },
  ],
};
