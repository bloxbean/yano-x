/**
 * The topics.yaml the CLI plans (`yano.sh appchain topics plan`). Studio checks only the document's shape; the
 * CLI compiles it against the chain, so Studio never computes the bytes owners or members sign.
 */
import {isValidEntryName} from './assets/topics/topics.mjs';
import {parseYaml, emitYaml, YamlMap} from './studio-yaml.mjs';
import {parseJson, stringifyJson} from './lossless-json.mjs';

const OPERATIONS = ['create', 'update', 'rotate', 'recover', 'retire', 'revoke'];
const COMMAND_FIELDS = ['target', 'operation', 'authority', 'nonce', 'notAfter', 'owners', 'policy', 'delegationHex'];
const fail = message => { throw new Error(message); };
const fields = (value, allowed, what) => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(`Expected ${what} to be a mapping`);
  for (const key of Object.keys(value)) if (!allowed.includes(key)) fail(`Unknown ${what} field: ${key}`);
};
const integer = (value, what, min = 0n) => {
  if (!['bigint', 'number'].includes(typeof value) || typeof value === 'number' && !Number.isSafeInteger(value)
      || BigInt(value) < min || BigInt(value) > 9223372036854775807n) fail(`${what} must be a whole number`);
  return BigInt(value);
};
const copy = value => parseJson(stringifyJson(value));

function keySet(value, what) {
  fields(value, ['threshold', 'keys'], what);
  if (!Array.isArray(value.keys) || !value.keys.length || value.keys.length > 16) fail(`${what} need 1 to 16 keys`);
  for (const key of value.keys) if (!/^[0-9a-f]{64}$/.test(key)) fail(`${what}: each key is 64 lowercase hex characters`);
  if (new Set(value.keys).size !== value.keys.length) fail(`${what}: a key is listed twice`);
  const threshold = integer(value.threshold, `${what} threshold`, 1n);
  if (threshold > BigInt(value.keys.length)) fail(`${what}: more signatures required than keys`);
}

/** Checks one command's shape and the fields its operation and authority use, as the CLI's planner does. */
export function checkCommand(command) {
  fields(command, COMMAND_FIELDS, 'command');
  const {target, operation, authority} = command;
  if (typeof target !== 'string' || !isValidEntryName(target)) fail('Enter a valid namespace (ending in /) or topic');
  if (!OPERATIONS.includes(operation)) fail('Unknown operation');
  if (typeof authority !== 'string' || !authority) fail('Enter the authority');
  if (!/^[0-9a-f]{32}$/.test(command.nonce ?? '')) fail('The nonce is 32 lowercase hex characters');
  if (authority === 'governance') {
    if (!['create', 'recover', 'retire'].includes(operation)) fail('A member vote creates, recovers or retires');
    if (operation === 'create' && !/^[^/]+\/$/.test(target)) fail('A member vote creates a top-level namespace');
    if (Object.hasOwn(command, 'notAfter')) fail('A member vote has no expiry');
  } else {
    if (operation === 'recover') fail('Recovery is a member vote: set the authority to governance');
    integer(command.notAfter, 'The expiry', 1n);
  }
  if (['create', 'rotate', 'recover'].includes(operation)) keySet(command.owners, 'Owners');
  else if (Object.hasOwn(command, 'owners')) fail(`${operation} does not change owners`);
  if (!['create', 'update'].includes(operation) && Object.hasOwn(command, 'policy')) fail(`${operation} has no policy`);
  if (Object.hasOwn(command, 'delegationHex') !== (operation === 'revoke')) {
    fail('Only a revocation names a credential');
  }
  if (command.policy !== undefined) {
    const policy = command.policy;
    fields(policy, ['writers', 'delegate', 'maxDelegationMs', 'schema', 'schemaCddl', 'maxPayloadBytes'], 'policy');
    if (policy.writers !== undefined && policy.writers !== 'open') keySet(policy.writers, 'Writers');
    for (const key of policy.delegate ?? []) if (!/^[0-9a-f]{64}$/.test(key)) fail('Each delegator key is 64 hex');
    if (Object.hasOwn(policy, 'schema') && Object.hasOwn(policy, 'schemaCddl')) fail('Choose schema or schemaCddl');
    for (const key of ['maxDelegationMs', 'maxPayloadBytes']) {
      if (policy[key] !== undefined) integer(policy[key], key, 1n);
    }
  }
  return command;
}

function yamlValue(node) {
  if (node.kind === 'map') return Object.fromEntries(node.entries.map(e => [e.key.text, yamlValue(e.value)]));
  if (node.kind === 'seq') return node.items.map(yamlValue);
  if (node.kind === 'scalar') return node.value;
  fail('Unsupported YAML value');
}

/** Reads topics.yaml (or its JSON form) and checks every command. */
export function readTopics(text) {
  const value = text.trim().startsWith('{') ? parseJson(text) : yamlValue(parseYaml(text).root);
  fields(value, ['schemaVersion', 'kind', 'chainId', 'commands'], 'document');
  if (integer(value.schemaVersion, 'schemaVersion') !== 1n || value.kind !== 'Topics') {
    fail('Expected kind: Topics, schemaVersion: 1');
  }
  if (typeof value.chainId !== 'string' || !value.chainId || value.chainId.length > 128) fail('Enter the chain ID');
  if (!Array.isArray(value.commands) || !value.commands.length || value.commands.length > 64) {
    fail('A topics document has 1 to 64 commands');
  }
  const targets = new Set();
  for (const command of value.commands) {
    checkCommand(command);
    if (targets.has(command.target)) fail('Each command needs its own target; plan sequential changes separately');
    targets.add(command.target);
  }
  return copy(value);
}

export function topicsYaml(document) {
  const toYaml = value => Array.isArray(value) ? value.map(toYaml)
    : value && typeof value === 'object' ? new YamlMap(Object.entries(value).map(([k, v]) => [k, toYaml(v)]))
      : typeof value === 'number' ? integer(value, 'A number') : value;
  return emitYaml(toYaml(document));
}

/** The CLI commands that follow for this document, in order. */
export function nextSteps(document, file = 'topics.yaml') {
  const plan = file.replace(/\.(ya?ml|json)$/, '') + '.plan.json';
  const signed = plan.replace(/\.plan\.json$/, '.signed.json');
  const votes = document.commands.some(command => command.authority === 'governance');
  const signatures = document.commands.some(command => command.authority !== 'governance');
  const lines = [`yano.sh appchain topics plan ${file}`];
  if (signatures) {
    lines.push(`# each signer, with their own key file:`,
      `yano.sh appchain topics sign ${plan} --key-file <seed file>`, `yano.sh appchain topics submit ${signed}`);
  }
  if (votes) lines.push('# every operator, from their node directory:', `yano.sh appchain topics approve ${plan}`);
  return lines;
}
