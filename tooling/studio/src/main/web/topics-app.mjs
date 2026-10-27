import {checkCommand, nextSteps, readTopics, topicsYaml} from './topics-model.mjs';
import {decodeUtf8Strict} from './lossless-json.mjs';
import {nonce} from './assets/topics/topics.mjs';

const $ = id => document.getElementById(id);
const NEEDS_OWNERS = ['create', 'rotate', 'recover'];
const HAS_POLICY = ['create', 'update'];

function notice(message, error = false) { $('notice').textContent = message; $('notice').dataset.error = String(error); }
function action(id, fn) {
  $(id).addEventListener('click', () => {
    try { fn(); } catch (error) { notice(error.message, true); }
  });
}
const integer = (id, what) => {
  const value = $(id).value.trim();
  if (!/^(?:0|[1-9][0-9]*)$/.test(value)) throw new Error(`Enter a whole number for ${what}`);
  return BigInt(value);
};
const keys = id => $(id).value.split(/\s+/).filter(Boolean).map(key => key.toLowerCase());

function download(name, content) {
  const url = URL.createObjectURL(new Blob([content], {type: 'application/octet-stream'}));
  const link = document.createElement('a'); link.href = url; link.download = name; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Shows only the fields the chosen operation and authority use. */
function showFields() {
  const operation = $('operation').value;
  $('owners-fields').hidden = !NEEDS_OWNERS.includes(operation);
  $('policy-fields').hidden = !HAS_POLICY.includes(operation);
  $('revoke-fields').hidden = operation !== 'revoke';
  $('expiry-field').hidden = $('authority').value.trim() === 'governance';
  if (operation === 'recover') $('authority').value = 'governance';
}

function showNext(document) {
  $('next-steps').textContent = nextSteps(document).join('\n');
}

function fromForm() {
  const operation = $('operation').value;
  const authority = $('authority').value.trim();
  const command = {target: $('target').value.trim(), operation, authority, nonce: nonce()};
  if (authority !== 'governance') {
    if (!$('not-after').value.trim()) $('not-after').value = String(Date.now() + 3_600_000);
    command.notAfter = integer('not-after', 'the expiry');
  }
  if (NEEDS_OWNERS.includes(operation)) {
    command.owners = {threshold: integer('owner-threshold', 'owner signatures'), keys: keys('owners')};
  }
  if (operation === 'revoke') command.delegationHex = $('revoke-delegation').value.trim().toLowerCase();
  if (HAS_POLICY.includes(operation)) {
    const writers = keys('writers');
    command.policy = {
      writers: writers.length ? {threshold: integer('writer-threshold', 'writer signatures'), keys: writers} : 'open',
      delegate: keys('delegators'),
      maxDelegationMs: integer('delegation-ms', 'the credential lifetime'),
      maxPayloadBytes: integer('payload-limit', 'the payload limit')
    };
    if ($('schema').value.trim()) command.policy.schemaCddl = $('schema').value;
    else command.policy.schema = 'none';
  }
  return checkCommand(command);
}

action('apply-form', () => {
  const chainId = $('chain-id').value.trim();
  const document_ = readTopics(topicsYaml({schemaVersion: 1, kind: 'Topics', chainId, commands: [fromForm()]}));
  $('document').value = topicsYaml(document_);
  showNext(document_);
  notice('topics.yaml is ready. Download it, then plan it with the CLI. Each write of the form uses a new nonce.');
});
action('export-yaml', () => {
  const document_ = readTopics($('document').value);
  download('topics.yaml', topicsYaml(document_));
  showNext(document_);
  notice('Downloaded topics.yaml.');
});
$('yaml-file').addEventListener('change', async () => {
  try {
    const file = $('yaml-file').files[0];
    if (!file || file.size > 2 * 1024 * 1024) throw new Error('Choose a file no larger than 2 MiB');
    const document_ = readTopics(decodeUtf8Strict(new Uint8Array(await file.arrayBuffer())));
    $('document').value = topicsYaml(document_);
    $('chain-id').value = document_.chainId;
    showNext(document_);
    notice(`Imported ${file.name}.`);
  } catch (error) { notice(error.message, true); }
});
for (const id of ['operation', 'authority']) $(id).addEventListener('input', showFields);
showFields();
showNext({commands: [{authority: 'governance'}]});
