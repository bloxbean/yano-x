import {compileTopicPlan, readTopics, topicsYaml, namespaceTree, applicableNames} from './topics-model.mjs';
import {parseJson, stringifyJson, decodeUtf8Strict} from './lossless-json.mjs';
import {importAuthoringCatalog} from './binding-catalog.mjs';
import {compositeNames, diagnoseComposite, readComposite, readPending, topicStateKey} from './topics-diagnostics.mjs';
import {nonce, decodeWire, isValidEntryName, isValidTopic} from './assets/topics/topics.mjs';
const $ = id => document.getElementById(id);
const utf8 = new TextEncoder();
let snapshot, lastProof;
let composite, diagnosticsCatalog, pendingInspection = {pending: [], checks: []};
function notice(message, error = false) { $('notice').textContent = message; $('notice').dataset.error = String(error); }
function action(id, fn) {
  $(id).addEventListener('click', async () => {
    $(id).disabled = true;
    try { await fn(); } catch (error) { notice(error.message, true); }
    finally { $(id).disabled = false; }
  });
}
const pretty = value => stringifyJson(value, 2);
const integer = id => { const s = $(id).value; if (!/^(?:0|[1-9][0-9]*)$/.test(s)) throw new Error(`Enter a whole number for ${id}`); return BigInt(s); };
const publicKeys = id => $(id).value.split(/\s+/).filter(Boolean);
function download(name, content) {
  const url = URL.createObjectURL(new Blob([content], {type: 'application/octet-stream'}));
  const link = document.createElement('a'); link.href = url; link.download = name; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
async function fileText(file) {
  if (!file || file.size > 2 * 1024 * 1024) throw new Error('Choose a file no larger than 2 MiB');
  return decodeUtf8Strict(new Uint8Array(await file.arrayBuffer()));
}
function useSnapshot(value) {
  if (value.kind === 'TopicsPlan') {
    if (value.commands?.some(c => c.signatures?.length)) throw new Error('Import the unsigned plan before collecting signatures');
    $('document').value = topicsYaml(value.document); snapshot = value.snapshot;
  } else snapshot = value;
  if (!snapshot?.chainId || !snapshot.entries || !snapshot.generationId) throw new Error('Expected a CLI plan or topic snapshot');
  $('chain-id').value = snapshot.chainId;
  $('snapshot-summary').textContent = `Height ${snapshot.height} · generation ${snapshot.generationId.slice(0, 16)}…`;
  $('proof-height').value = String(BigInt(snapshot.height) + 1n);
  const list = $('namespace-tree'); list.replaceChildren();
  for (const entry of namespaceTree(snapshot.entries)) {
    const item = document.createElement('li'), button = document.createElement('button');
    button.type = 'button'; button.className = 'secondary';
    button.textContent = `${'› '.repeat(entry.depth)}${entry.name} · r${entry.head}`;
    button.addEventListener('click', () => {
      $('inspect-name').value = entry.name;
      $('inspection').textContent = pretty(snapshot.entries[entry.name]);
    });
    item.append(button); list.append(item);
  }
  notice('Snapshot loaded. Review the generation and policy before exporting.');
  pendingInspection = {pending: [], checks: []};
  renderDiagnostics();
}
function renderDiagnostics() {
  if (!composite || !snapshot) return;
  const result = diagnoseComposite(composite, snapshot, {...pendingInspection, catalog: diagnosticsCatalog});
  $('topic-diagnostics').textContent = pretty(result);
}
$('composite-file').addEventListener('change', async event => {
  try {
    composite = readComposite(await fileText(event.target.files[0]));
    $('composite-summary').textContent = `${composite.components.length} components loaded. Refresh to inspect their policies.`;
    renderDiagnostics(); notice('Composite loaded for advisory topic checks.');
  } catch (error) { notice(error.message, true); }
});
$('topic-catalog-file').addEventListener('change', async event => {
  try {
    diagnosticsCatalog = importAuthoringCatalog(utf8.encode(await fileText(event.target.files[0])));
    $('topic-catalog-summary').textContent = `Catalog for ${diagnosticsCatalog.context.chainId}; exact component configurations only.`;
    renderDiagnostics(); notice('Catalog loaded as editing assistance.');
  } catch (error) { notice(error.message, true); }
});
$('plan-file').addEventListener('change', async event => {
  try { useSnapshot(parseJson(await fileText(event.target.files[0]))); } catch (error) { notice(error.message, true); }
});
$('yaml-file').addEventListener('change', async event => {
  try { $('document').value = topicsYaml(readTopics(await fileText(event.target.files[0]))); notice('Topics document imported.'); }
  catch (error) { notice(error.message, true); }
});
function baseUrl() {
  const url = new URL($('peer').value);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error('Use an HTTP(S) peer URL without credentials or query');
  const chain = $('chain-id').value;
  if (!chain || chain.length > 128) throw new Error('Enter a chain ID');
  return url.href.replace(/\/$/, '') + (url.pathname === '/' ? '/api/v1' : '') + '/app-chain/chains/' + encodeURIComponent(chain);
}
async function request(path, missing = false) {
  const response = await fetch(baseUrl() + path, {credentials: 'omit', redirect: 'error', signal: AbortSignal.timeout(15000)});
  if (missing && response.status === 404) return null;
  if (!response.ok) throw new Error(`Peer returned HTTP ${response.status}`);
  const reader = response.body.getReader(), chunks = []; let size = 0;
  try {
    while (true) {
      const {done, value} = await reader.read(); if (done) break;
      size += value.length; if (size > 4 * 1024 * 1024) throw new Error('Peer response exceeds 4 MiB');
      chunks.push(value);
    }
  } finally { await reader.cancel(); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return parseJson(decodeUtf8Strict(bytes), {maxCharacters: 4 * 1024 * 1024});
}
const stateKey = topicStateKey;
action('example', async () => {
  const response = await fetch('assets/topics-plan-v1.json');
  if (!response.ok) throw new Error('Example could not be loaded');
  const fixtures = parseJson(await response.text()); useSnapshot(fixtures[1].plan);
  const command = fixtures[1].document.commands[0];
  $('target').value = command.target; $('operation').value = command.operation;
  $('authority').value = command.authority; $('owners').value = snapshot.entries['games/'].active.content.owners.keys.join('\n');
  $('writers').value = command.policy.writers.keys.join('\n'); $('delegators').value = command.policy.delegate.join('\n');
  $('schema').value = command.policy.schemaCddl; $('not-after').value = String(Date.now() + 3600000);
  $('payload-limit').value = String(command.policy.maxPayloadBytes);
});
action('apply-form', () => {
  const operation = $('operation').value, authority = $('authority').value;
  const command = {target: $('target').value, operation, authority, nonce: nonce()};
  if (authority !== 'governance') command.notAfter = integer('not-after');
  if (['create', 'rotate', 'recover'].includes(operation)) command.owners = {threshold: integer('owner-threshold'), keys: publicKeys('owners')};
  if (operation === 'revoke') command.delegationHex = $('revoke-delegation').value.trim();
  if (['create', 'update'].includes(operation)) {
    const keys = publicKeys('writers');
    command.policy = {writers: keys.length ? {threshold: integer('writer-threshold'), keys} : 'open',
      delegate: publicKeys('delegators'), maxDelegationMs: integer('delegation-ms'), maxPayloadBytes: integer('payload-limit')};
    if ($('schema').value.trim()) command.policy.schemaCddl = $('schema').value;
    else command.policy.schema = 'none';
  }
  $('document').value = topicsYaml({schemaVersion: 1, kind: 'Topics', chainId: $('chain-id').value, commands: [command]});
  $('plan-preview').textContent = 'Draft changed. Preview the new plan.'; notice('Draft replaced; review its new nonce and expiry.');
});
async function plan() {
  if (!snapshot) throw new Error('Load or refresh a chain snapshot first');
  const originalSnapshot = snapshot, originalDocument = $('document').value;
  const planSnapshot = {...snapshot};
  delete planSnapshot.inspectedNames; // Local inspection coverage is not part of the CLI's Snapshot wire contract.
  const compiled = await compileTopicPlan(readTopics(originalDocument), planSnapshot);
  if (snapshot !== originalSnapshot || $('document').value !== originalDocument) {
    throw new Error('The draft or snapshot changed while verifying. Preview the current draft again.');
  }
  return compiled;
}
action('preview', async () => { $('plan-preview').textContent = pretty(await plan()); notice('Unsigned preview ready.'); });
action('export-yaml', () => download('topics.yaml', topicsYaml(readTopics($('document').value))));
action('export-plan', async () => { download('topics-plan.json', pretty(await plan()) + '\n'); notice('Unsigned plan exported. Sign with appchain topics sign --key-file.'); });
async function refreshSnapshot() {
  const doc = $('document').value.trim() ? readTopics($('document').value)
    : {commands: [], chainId: $('chain-id').value};
  const names = new Set(Object.keys(snapshot?.entries ?? {}));
  if (composite) compositeNames(composite).forEach(name => names.add(name));
  for (const command of doc.commands) {
    applicableNames(command.target).forEach(n => names.add(n));
    if (isValidEntryName(command.authority)) applicableNames(command.authority).forEach(n => names.add(n));
  }
  if (names.size > 512) throw new Error('Too many entries for one snapshot');
  const before = await request('/status'), config = await request('/topics/config'), entries = {}, freedHeads = {};
  for (const name of names) {
    const entry = await request('/topics/entries/' + encodeURIComponent(name), true);
    if (entry) { if (entry.name !== name) throw new Error('Peer returned a different entry'); entries[name] = entry; }
    else {
      const freed = await request('/state/entry/' + stateKey('freed', name) + '?height=' + before.stateCommitment.version);
      if (freed.stateRoot !== before.stateCommitment.stateRoot) throw new Error('Freed revision differs from inspected root');
      if (!['PRESENT', 'ABSENT', 'TOMBSTONED'].includes(freed.presence)) throw new Error('Invalid freed revision presence');
      if (freed.presence === 'PRESENT') {
        if (typeof freed.valueHex !== 'string' || !/^[0-9a-f]{16}$/.test(freed.valueHex)) throw new Error('Invalid freed revision');
        freedHeads[name] = BigInt('0x' + freed.valueHex);
      } else if (freed.valueHex != null && freed.valueHex !== '') {
        throw new Error('Invalid absent freed revision value');
      }
    }
  }
  const after = await request('/status');
  if (pretty(before.stateCommitment) !== pretty(after.stateCommitment)) throw new Error('Chain advanced while reading; refresh again');
  if (before.chainId !== doc.chainId || before.chainId !== $('chain-id').value) throw new Error('Peer chain differs from draft');
  useSnapshot({chainId: before.chainId, generationId: before.stateCommitment.generationId,
    height: before.stateCommitment.version, stateRoot: before.stateCommitment.stateRoot,
    maxMessageBytes: before.consensusProfile.maxMessageBytes, config: config.enabled ? config.config : null, entries,
    freedHeads, inspectedNames: [...names]});
}
action('refresh', refreshSnapshot);
action('check-topics', async () => {
  if (!composite) throw new Error('Import a composite document first');
  await refreshSnapshot();
  const inspected = snapshot;
  const result = await readPending(inspected, path => request(path));
  if (snapshot !== inspected) throw new Error('Snapshot changed during inspection; run the check again');
  pendingInspection = result; renderDiagnostics(); notice('Active and pending policy checks complete for the displayed root.');
});
function showProof(value) { lastProof = value; $('inspection').textContent = pretty(value); $('export-proof').disabled = false; }
action('resolve', async () => {
  const topic = $('inspect-name').value;
  if (!isValidTopic(topic)) throw new Error('Enter a valid topic');
  const height = integer('proof-height');
  if (height < 2n) throw new Error('Candidate height must be at least 2');
  const proof = await request(`/topics/proof?topic=${encodeURIComponent(topic)}&height=${height}`);
  showProof({kind: 'topic-policy-v1', trust: 'peer-response', proof}); notice('Effective policy proof fetched for independent verification.');
});
action('entry', async () => {
  const name = $('inspect-name').value;
  if (!isValidEntryName(name)) throw new Error('Enter a valid entry name');
  const height = integer('proof-height') - 1n;
  if (height < 1n) throw new Error('The entry proof needs a committed parent height');
  showProof({kind: 'topic-entry-state-proof', name, trust: 'peer-response',
    proof: await request(`/state/proof/${stateKey('entry', name)}?height=${height}`)});
  notice('Entry-only proof fetched. It does not establish an effective writer policy.');
});
action('history', async () => {
  const name = $('inspect-name').value;
  if (!isValidEntryName(name)) throw new Error('Enter a valid entry name');
  const height = integer('proof-height') - 1n;
  if (height < 1n) throw new Error('History needs a committed parent height');
  const entryProof = await request(`/state/proof/${stateKey('entry', name)}?height=${height}`);
  if (!entryProof.valueHex) throw new Error('Entry is absent at that height');
  const value = Uint8Array.from(entryProof.valueHex.match(/../g), b => parseInt(b, 16));
  const entry = decodeWire('entry-record', value), head = BigInt(entry[3]), revisions = [];
  for (let revision = head; revision > 0n && revision > head - 16n; revision--) {
    const proof = await request(`/state/proof/${stateKey('history', name, revision)}?height=${height}`);
    revisions.push({revision, proof});
  }
  showProof({kind: 'topic-revision-history', name, height, trust: 'peer-response', entryProof, revisions});
  notice('Fetched up to 16 recent revision proofs at the same height.');
});
action('export-proof', () => download('topic-proof-response.json', pretty(lastProof) + '\n'));
$('not-after').value = String(Date.now() + 3600000);
