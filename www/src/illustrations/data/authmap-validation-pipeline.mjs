// Where an authenticated-map value is validated: advisory preflight, ingress
// admission, the candidate block, and authoritative apply. Collections follow
// the documented products/gtins examples; values are examples.

const PREFLIGHT = './yano.sh appchain state validate --genesis-file product-registry/config/authenticated-map-genesis.hex '
  + '--collection products --key 736b752d31 --value-file product.cbor';

const preflightChecks = (failAt, code) => [
  'The collection exists in genesis',
  'Key and value fit the collection bounds',
  'The value has the collection’s encoding',
  'The collection’s schema or plugin accepts the value',
].map((label, i) => ({
  label,
  ok: failAt === undefined || i < failAt ? true : i === failAt ? false : null,
  ...(i === failAt ? { code } : {}),
}));

const ingressChecks = (failAt) => [
  'Topic is `authenticated-map.command.v1`',
  'Canonical command within the genesis byte limit',
  'Known collection; key and value within its bounds',
  'Encoding, then schema, then plugin validator',
  'Authorization kinds match the collection genesis',
].map((label, i) => ({
  label,
  ok: failAt === undefined || i < failAt ? true : i === failAt ? false : null,
  ...(i === failAt ? { code: 'APPLICATION_REJECTED' } : {}),
}));

const preflight = {
  title: 'Preflight',
  text: 'Before submitting, check the value offline against the exact genesis. The result is advisory: `ACCEPTED`, '
    + '`REJECTED` with a code, or `UNAVAILABLE` when the collection uses a plugin the CLI cannot run.',
  command: PREFLIGHT,
  checks: preflightChecks(),
  cards: { tool: { title: 'ACCEPTED', detail: 'advisory only', tone: 'ok' } },
};

const ingress = {
  title: 'Ingress',
  text: 'The receiving member runs the same value rules as admission. Every rule passes, so it answers **202** and '
    + 'gossips the command.',
  wires: [{ from: 'tool', to: 'member', label: 'POST command' }],
  checks: ingressChecks(),
  cards: { member: { title: 'Admitted', detail: '202 + messageId', tone: 'ok' } },
};

const block = {
  title: 'Candidate block',
  text: 'A command is checked again for the height of the block that would include it. Here that matters for '
    + '`member` collections: the sender must be a member at that height.',
  wires: [{ from: 'member', to: 'map', label: 'proposed in block h' }],
  checks: [{ label: 'For a `member` collection, the sender is a member at height h', ok: true }],
  cards: { member: null, map: { title: 'In block h', detail: 'not applied yet' } },
};

const apply = {
  title: 'Apply',
  text: 'Every member applies the command: authorization and status rules first, then encoding, schema and '
    + 'validator once more. This is the authoritative check. The receipt records APPLIED with the new revision.',
  checks: [
    { label: 'Authorization and status rules', ok: true },
    { label: 'Encoding, then schema, then plugin validator', ok: true },
  ],
  cards: { map: { title: 'Receipt: APPLIED', detail: 'revision 1', tone: 'final' } },
};

const refusedAtIngress = (text, failAt) => ({
  title: 'Ingress',
  text,
  wires: [{ from: 'member', to: 'tool', label: '400 `APPLICATION_REJECTED`', tone: 'fail' }],
  checks: ingressChecks(failAt),
  cards: { member: { title: 'Refused', detail: 'not pooled, no receipt', tone: 'fail' } },
});

export default {
  id: 'authmap-validation-pipeline',
  type: 'steps',
  title: 'Where a value is checked',
  intro: 'a product value on its way into the `products` collection, which requires canonical CBOR and the '
    + '`product-v1` schema. Try a “What if” to see where a bad value stops.',
  lanes: [
    { id: 'tool', label: 'Your tooling', note: 'CLI or Java preflight', kind: 'client' },
    { id: 'member', label: 'Receiving member', note: 'admission', kind: 'member' },
    { id: 'map', label: 'authenticated-map', note: 'apply, on every member', kind: 'core' },
  ],
  scenarios: [
    { id: 'valid', label: 'A valid product', steps: [preflight, ingress, block, apply] },
    {
      id: 'schema',
      label: 'Breaks the schema',
      summary: 'The value says `status: "unknown"`; `product-v1` allows only `active`, `held` or `retired`.',
      steps: [{
        ...preflight,
        text: 'Preflight evaluates the compiled schema and reports `REJECTED` with code 11, `VALUE_SCHEMA`.',
        checks: preflightChecks(3, 'VALUE_SCHEMA (11)'),
        cards: { tool: { title: 'REJECTED', detail: 'code 11', tone: 'fail' } },
      }, refusedAtIngress('Submitted anyway, the value fails the same schema at admission. REST keeps only a safe code: '
        + '**400** `APPLICATION_REJECTED`. Nothing is pooled and no receipt will exist.', 3)],
    },
    {
      id: 'encoding',
      label: 'Not canonical CBOR',
      summary: 'The same map with its keys out of canonical order.',
      steps: [{
        ...preflight,
        text: 'Canonical CBOR requires map keys in canonical order. Preflight reports `REJECTED` with code 10, '
          + '`VALUE_ENCODING`; the schema is never reached.',
        checks: preflightChecks(2, 'VALUE_ENCODING (10)'),
        cards: { tool: { title: 'REJECTED', detail: 'code 10', tone: 'fail' } },
      }, refusedAtIngress('Admission applies the encoding rule before the schema and refuses the command with **400**.', 3)],
    },
    {
      id: 'plugin',
      label: 'A plugin collection',
      summary: 'The showcase `gtins` collection uses the `gs1-gtin-v1` plugin. `95012345` has a wrong check digit.',
      steps: [{
        title: 'Preflight',
        text: 'The CLI has no adapter for a custom plugin, so it reports `UNAVAILABLE` (code 12). That never means '
          + 'accepted.',
        command: './yano.sh appchain state validate --genesis-file <genesis.hex> --collection gtins --key <hex> --value-hex <hex>',
        checks: preflightChecks(3, 'UNAVAILABLE (12)').map((check, i) => (i === 3 ? { ...check, ok: null } : check)),
        cards: { tool: { title: 'UNAVAILABLE', detail: 'not checked', tone: 'pending' } },
      }, refusedAtIngress('Every member runs the genesis-pinned plugin at admission. It rejects the check digit, so '
        + 'the member answers **400** `APPLICATION_REJECTED`.', 3)],
    },
    {
      id: 'collection',
      label: 'An unknown collection',
      summary: 'Codes 1 and 2 come only from preflight. On a node the same faults are refused before any receipt.',
      steps: [{
        ...preflight,
        text: 'The collection is not in genesis. Preflight reports `REJECTED` with code 1, `UNKNOWN_COLLECTION`.',
        checks: preflightChecks(0, 'UNKNOWN_COLLECTION (1)'),
        cards: { tool: { title: 'REJECTED', detail: 'code 1', tone: 'fail' } },
      }, refusedAtIngress('Admission refuses a command naming an unknown collection, so no receipt can ever carry '
        + 'code 1.', 2)],
    },
  ],
  legend: [
    ['client', 'your tooling'],
    ['member', 'member node'],
    ['core', 'deterministic state machine'],
    ['fail', 'refused'],
  ],
  sources: [
    { repo: 'yano-x', path: 'sdk/client/src/main/java/org/yanoproject/x/client/AuthenticatedMapPreflight.java',
      anchors: ['return rejected(AuthenticatedMapContract.ERROR_UNKNOWN_COLLECTION);',
        'return rejected(AuthenticatedMapContract.ERROR_COLLECTION_BOUNDS);',
        'return rejected(AuthenticatedMapContract.ERROR_VALUE_ENCODING);',
        '? accepted() : rejected(AuthenticatedMapContract.ERROR_VALUE_SCHEMA);',
        'return unavailable(AuthenticatedMapContract.ERROR_VALUE_VALIDATOR);', 'ACCEPTED, REJECTED, UNAVAILABLE'] },
    { repo: 'yano-x', path: 'tooling/devtools/src/main/java/org/yanoproject/x/devtools/AppChainStateCli.java',
      anchors: ['.fromGenesis(genesis)', 'result.put("authoritative", false);'] },
    { repo: 'yano-x', path: 'state-machines/stdlib/src/main/java/org/yanoproject/x/stdlib/AuthenticatedMapStateMachine.java',
      anchors: ['"authenticated-map requires topic "', '"authenticated-map command exceeds genesis byte limit"',
        'validateCommandBounds(mutations); validateCommandValues(mutations); validateAuthorizationAssignments(command);',
        '"authenticated-map sender is not a member at candidate height"',
        'throw failure(AuthenticatedMapContract.ERROR_VALUE_ENCODING);',
        'throw failure(AuthenticatedMapContract.ERROR_VALUE_SCHEMA);',
        'throw failure(AuthenticatedMapContract.ERROR_VALUE_VALIDATOR);', 'throw new IllegalArgumentException("unknown collection");'] },
    { repo: 'yano-x', path: 'examples/showcase/src/main/showcase/showcase.sh',
      anchors: ['"product schema violation"', '"custom GTIN validator violation"',
        "'keys == [\"code\"] and .code == \"APPLICATION_REJECTED\"'"] },
    { repo: 'yano-x', path: 'docs/appchain/state-machines/authenticated-map.md',
      anchors: ['status: "active" / "held" / "retired"'] },
  ],
};
