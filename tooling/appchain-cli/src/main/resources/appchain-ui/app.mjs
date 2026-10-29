// App-Chain Setup page (ADR-056 D7). Every action runs a `yano.sh appchain` command on this computer through the
// local server and shows that command; the page itself holds no keys and writes no files of its own.

const token = new URLSearchParams(location.search).get('t') ?? readStored();
storeToken(token);
let recipes = {};
const STAGES = ['Draft', 'Members', 'Sealed', 'Starting', 'Live'];

// What the operator is doing survives the five-second refresh: a section is rebuilt only when its data changed,
// and its typed values, choices, confirmations and running buttons are restored when it is.
const ui = {confirmed: new Set(), request: {}, anchor: {}, busy: new Set(), slices: {}, createTouched: false,
  createProgrammatic: false};

function readStored() {
  try { return sessionStorage.getItem('appchain-ui-token'); } catch { return null; }
}
function storeToken(value) {
  try { if (value) sessionStorage.setItem('appchain-ui-token', value); } catch { /* private window */ }
}

async function api(path, body) {
  const response = await fetch(`/api/${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: {'X-UI-Token': token ?? '', 'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  try { return JSON.parse(text); } catch { return {error: text}; }
}

/** Builds an element; strings become text nodes, so file contents never become markup. */
function el(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attributes)) {
    if (key === 'class') node.className = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else if (value !== false && value !== undefined && value !== null) node.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) if (child !== null && child !== undefined && child !== false) node.append(child);
  return node;
}
const $ = id => document.getElementById(id);
const initial = name => (name ?? '?').slice(0, 1);
const shown = args => args.map(arg => (/[\s"'$]/.test(arg) ? `'${arg.replaceAll("'", "'\\''")}'` : arg)).join(' ');

function toast(message, kind = 'ok') {
  const item = el('div', {class: `toast ${kind}`, role: kind === 'bad' ? 'alert' : null}, el('span', {}, message),
    el('button', {type: 'button', class: 'close', 'aria-label': 'Dismiss', onclick: () => item.remove()}, '×'));
  $('toasts').append(item);
  // Errors stay until dismissed; confirmations fade.
  if (kind !== 'bad') setTimeout(() => item.remove(), 5000);
}

/** A confirmation in the page; resolves true only on the explicit confirm button, never on Escape. */
function ask(title, body, ok = 'Continue', danger = false) {
  const dialog = $('dialog');
  dialog.returnValue = '';
  $('dialog-title').textContent = title;
  $('dialog-body').replaceChildren(...[body].flat());
  $('dialog-ok').textContent = ok;
  $('dialog-ok').className = danger ? 'danger solid' : 'primary';
  dialog.showModal();
  return new Promise(resolve => dialog.addEventListener('close', () => resolve(dialog.returnValue === 'ok'),
    {once: true}));
}

/** Runs one command; {@code key} names the button so its busy state survives a refresh. */
async function run(args, key) {
  if (key) { ui.busy.add(key); markBusy(); }
  const status = el('em', {class: 'run'}, 'running');
  const entry = el('div', {class: 'entry'},
    el('div', {class: 'cmd'}, el('span', {}, `$ yano.sh appchain ${shown(args)}`), status));
  const log = $('log');
  log.querySelector('.empty')?.remove();
  log.prepend(entry);
  const verb = args[0] === 'topics' ? `topics ${args[1]}` : args[0];
  try {
    const result = await api('run', {args});
    const ok = result.exit === 0;
    status.className = ok ? 'ok' : 'bad';
    status.textContent = ok ? 'done' : result.exit === 3 ? 'not yet' : 'failed';
    entry.append(el('pre', {class: ok ? '' : 'bad'}, (result.out ?? '') + (result.err ?? '') + (result.error ?? '')));
    toast(ok ? `${verb} finished` : `${verb}: ${firstLine(result.err ?? result.error ?? '')}`, ok ? 'ok' : 'bad');
    return result;
  } catch {
    ui.slices = {}; // redraw everything once the server answers again
    status.className = 'bad';
    status.textContent = 'failed';
    entry.append(el('pre', {class: 'bad'}, 'Lost contact with the local server. Is the ui command still running?'));
    toast('Lost contact with the local server', 'bad');
    return {exit: 1};
  } finally {
    if (key) ui.busy.delete(key);
    markBusy();
    await refresh(true);
  }
}
const firstLine = text => text.split('\n').find(line => line.trim()) ?? 'see Activity';

/** A button whose busy state is remembered by key. */
function action(key, label, attributes, onclick) {
  return el('button', {...attributes, type: 'button', 'data-key': key, disabled: attributes.disabled || ui.busy.has(key),
    class: `${attributes.class ?? 'primary'}${ui.busy.has(key) ? ' busy' : ''}`, onclick}, label);
}
function markBusy() {
  document.querySelectorAll('[data-key]').forEach(button => {
    const busy = ui.busy.has(button.dataset.key);
    button.classList.toggle('busy', busy);
    if (busy) button.disabled = true;
    // Buttons outside the redrawn sections are re-enabled here; the others come back with their section.
    else if ('static' in button.dataset) button.disabled = false;
  });
}

function commandHint(args) {
  return el('div', {class: 'command'}, `yano.sh appchain ${shown(args)}`);
}

// --- Create -----------------------------------------------------------------------------------------------

function chainRow() {
  const recipe = el('select', {name: 'recipe'},
    Object.entries(recipes).filter(([id]) => id !== 'declarative-composite')
      .map(([id, info]) => el('option', {value: id, title: info.summary}, id)),
    el('option', {value: 'bindings'}, 'several state machines (bindings file)'));
  recipe.value = 'audit-log';
  const extra = el('input', {name: 'extra', placeholder: 'answers: name=value, … or bindings file'});
  const topics = el('select', {name: 'topics'},
    el('option', {value: ''}, 'not protected'),
    el('option', {value: 'members'}, 'namespaces by member vote'),
    el('option', {value: 'registrars'}, 'by named registrars (members can still vote)'),
    el('option', {value: 'anyone'}, 'claimed by anyone (members can still recover)'));
  const registrars = el('input', {name: 'registrars', placeholder: 'registrars, e.g. alice', hidden: true,
    'aria-label': 'Registrars'});
  topics.addEventListener('change', () => { registrars.hidden = topics.value !== 'registrars'; });
  const row = el('div', {class: 'chain-row'},
    el('label', {}, 'Chain id', el('input', {name: 'id', required: true, placeholder: 'orders', autocomplete: 'off'})),
    el('label', {}, 'Runs', recipe),
    el('label', {}, 'Answers or bindings', extra),
    el('label', {}, 'Topic policies', topics, registrars),
    el('button', {type: 'button', class: 'ghost tiny remove', 'aria-label': 'Remove this chain',
      onclick: () => { row.remove(); updateRows(); }}, 'Remove'));
  const hint = () => {
    const answers = recipes[recipe.value]?.answers ?? [];
    extra.placeholder = recipe.value === 'bindings' ? 'bindings file next to the draft, e.g. flow.yaml'
      : answers.length ? `needs: ${answers.map(answer => `${answer}=…`).join(', ')}` : 'no answers needed';
  };
  recipe.addEventListener('change', hint);
  hint();
  return row;
}

function updateRows() {
  const rows = [...document.querySelectorAll('#chain-rows .chain-row')];
  rows.forEach(row => { row.querySelector('.remove').disabled = rows.length === 1; });
  updateHint();
}

function createArgs(form) {
  const data = new FormData(form);
  const args = ['new', data.get('name') || '<app>', '--network', data.get('network'), '--operators',
    (data.get('operators') || '').replaceAll(' ', ''), '--threshold', data.get('threshold'),
    '--sequencing', data.get('sequencing')];
  for (const row of form.querySelectorAll('.chain-row')) {
    const id = row.querySelector('[name=id]').value.trim();
    const recipe = row.querySelector('[name=recipe]').value;
    const extra = row.querySelector('[name=extra]').value.trim();
    const topics = row.querySelector('[name=topics]').value;
    if (!id) continue;
    if (recipe === 'bindings') {
      args.push('--chain', `${id}=bindings:${extra}`);
    } else {
      args.push('--chain', `${id}=${recipe}`);
      for (const answer of extra.split(',').map(item => item.trim()).filter(Boolean)) {
        args.push('--answer', `${id}.${answer}`);
      }
    }
    if (topics === 'registrars') {
      const names = row.querySelector('[name=registrars]').value.split(/[\s,+]+/).filter(Boolean);
      args.push('--topics', `${id}=registrars:${names.join('+')}`);
    } else if (topics) {
      args.push('--topics', `${id}=${topics}`);
    }
  }
  return args;
}

function updateHint() {
  $('create-hint').textContent = `yano.sh appchain ${shown(createArgs($('create-form')))}`;
}

// --- Rendering ---------------------------------------------------------------------------------------------

function chainChips(chains) {
  return el('div', {class: 'chips'}, chains.map(chain => el('div', {class: 'chain-chip'},
    el('strong', {}, chain.id), el('span', {}, chain.recipe),
    chain.topics ? el('span', {class: 'topics'}, `topics: ${chain.topics}`) : null)));
}

function renderDrafts(state) {
  const box = $('drafts');
  box.replaceChildren();
  if (!state.drafts.length) {
    box.append(el('p', {class: 'empty'}, 'No draft here yet. Create one above, or add a draft file you received.'));
    return;
  }
  for (const draft of state.drafts) {
    const cards = state.cards.filter(card => card.invite === draft.invite);
    const byName = Object.fromEntries(cards.map(card => [card.name, card]));
    const missing = draft.operators.filter(name => !byName[name]);
    const mine = state.node?.name;
    const form = ui.request[draft.file] ??= {name: mine && draft.operators.includes(mine) ? mine : draft.operators[0],
      host: draft.network === 'devnet' ? '127.0.0.1' : '', nodePort: '13337', httpPort: '8080'};
    const keep = field => event => { form[field] = event.target.value; };
    const name = el('select', {onchange: keep('name')},
      draft.operators.map(operator => el('option', {value: operator, selected: operator === form.name}, operator)));
    const host = el('input', {value: form.host, placeholder: 'node-a.example.com', oninput: keep('host')});
    const nodePort = el('input', {value: form.nodePort, size: 6, inputmode: 'numeric', oninput: keep('nodePort')});
    const httpPort = el('input', {value: form.httpPort, size: 6, inputmode: 'numeric', oninput: keep('httpPort')});
    const requestArgs = () => ['request', draft.file, '--name', form.name, '--host', form.host.trim(),
      '--node-port', form.nodePort, '--http-port', form.httpPort];
    const sealArgs = ['seal', draft.file];
    const ready = !missing.length;
    const requestKey = `request:${draft.file}`;
    box.append(el('article', {class: 'item'},
      el('div', {class: 'item-head'}, el('h3', {}, draft.name),
        el('div', {class: 'meta'}, el('span', {class: 'tag'}, `invite ${draft.invite}`),
          el('span', {class: 'tag'}, draft.network), el('span', {class: 'tag'}, `${draft.threshold} signatures`),
          draft.sealed ? el('span', {class: 'tag ok'}, 'sealed')
            : el('span', {class: `tag ${ready ? 'accent' : 'warn'}`},
              `${cards.length}/${draft.operators.length} cards`))),
      el('div', {class: 'people'}, draft.operators.map(operator => {
        const card = byName[operator];
        return el('div', {class: 'person'}, el('span', {class: `avatar ${card ? '' : 'pending'}`}, initial(operator)),
          el('div', {}, el('strong', {}, operator + (operator === draft.operators[0] ? ' · lead' : '')),
            card ? (card.valid ? el('small', {}, `key ${card.keyId}`) : el('small', {class: 'bad'}, 'bad signature'))
              : el('small', {class: 'warn'}, 'waiting for card')));
      })),
      chainChips(draft.chains),
      draft.sealed ? null : mine ? (byName[mine] ? null : el('div', {class: 'actions'},
          action(requestKey, `Make ${mine}'s member card`, {}, () => run(['request', draft.file], requestKey)),
          commandHint(['request', draft.file])))
        : el('div', {class: 'inline-form'},
          el('label', {}, 'I am', name), el('label', {}, 'Reachable at', host),
          el('label', {}, 'Node port', nodePort), el('label', {}, 'HTTP port', httpPort),
          action(requestKey, 'Make my member card', {}, () => run(requestArgs(), requestKey))),
      el('div', {class: 'actions'},
        draft.sealed ? null : action(`seal:${draft.file}`, ready ? 'Seal the application'
          : `Waiting for ${missing.join(', ')}`, {disabled: !ready}, () => run(sealArgs, `seal:${draft.file}`)),
        el('button', {type: 'button', class: 'ghost', onclick: download(draft.file)}, 'Download draft to send')),
      draft.sealed ? null : commandHint(sealArgs)));
  }
}

function renderSealed(state) {
  const box = $('sealed');
  box.replaceChildren();
  if (!state.sealed.length) {
    box.append(el('p', {class: 'empty'},
      'No sealed file here yet. The coordinator seals the draft and sends it to everyone.'));
    return;
  }
  const joined = new Set((state.node?.apps ?? []).map(app => app.name));
  for (const app of state.sealed) {
    const key = `join:${app.name}`;
    // A tick confirms one fingerprint: a resealed file with another one asks again.
    const seen = `${app.name} ${app.fingerprint}`;
    const joinArgs = ['join', app.file, '--expect-fingerprint', app.fingerprint];
    const join = action(key, joined.has(app.name) ? 'Join again to refresh' : 'Join',
      {class: joined.has(app.name) ? 'ghost' : 'primary', disabled: !ui.confirmed.has(seen)},
      () => run(joinArgs, key));
    const compared = el('input', {type: 'checkbox', id: `compare-${app.name}`, checked: ui.confirmed.has(seen),
      onchange: event => {
        if (event.target.checked) ui.confirmed.add(seen); else ui.confirmed.delete(seen);
        join.disabled = !event.target.checked || ui.busy.has(key);
      }});
    box.append(el('article', {class: 'item'},
      el('div', {class: 'item-head'}, el('h3', {}, app.name),
        el('div', {class: 'meta'}, el('span', {class: 'tag'}, app.network),
          el('span', {class: 'tag'}, `${app.threshold} signatures per block`),
          joined.has(app.name) ? el('span', {class: 'tag ok'}, 'joined') : null)),
      el('p', {class: 'label-muted'}, 'Chain fingerprint'),
      el('div', {class: 'fingerprint'}, app.fingerprint.split('-').map(group => el('span', {}, group))),
      el('div', {class: 'people'}, app.members.map(member => el('div', {class: 'person'},
        el('span', {class: 'avatar'}, initial(member.name)),
        el('div', {}, el('strong', {}, member.name + (member.name === app.lead ? ' · lead' : '')),
          el('small', {}, member.keyId))))),
      chainChips(app.chains),
      el('label', {class: `confirm${joined.has(app.name) ? ' calm' : ''}`, for: compared.id}, compared,
        'On a call with the others, I compared this fingerprint and every key id, and they match.'),
      el('div', {class: 'actions'}, join,
        el('button', {type: 'button', class: 'ghost', onclick: download(app.file)}, 'Download sealed file to send')),
      commandHint(joinArgs)));
  }
}

function renderNode(state) {
  const box = $('node');
  const pill = $('node-pill');
  box.replaceChildren();
  const node = state.node;
  if (!node?.name) {
    $('node-badge').textContent = 'No node yet';
    pill.className = 'node-pill';
    box.append(el('p', {class: 'empty'}, 'This node has no key yet. Make a member card for a draft first.'));
    return;
  }
  const live = (node.apps ?? []).some(app => app.status?.stage === 'Live');
  const word = node.running ? (live ? 'live' : 'running') : 'stopped';
  $('node-badge').textContent = `${node.name} · ${word}`;
  pill.className = `node-pill ${node.running ? word : ''}`;
  box.append(
    el('div', {class: 'node-id'}, el('span', {class: 'avatar'}, initial(node.name)),
      el('div', {}, el('strong', {}, node.name), el('small', {class: 'muted'}, word))),
    el('dl', {class: 'kv'}, el('dt', {}, 'Key id'), el('dd', {}, node.keyId), el('dt', {}, 'Members reach it at'),
      el('dd', {}, node.node), el('dt', {}, 'HTTP'), el('dd', {}, node.http)),
    el('div', {class: 'actions'},
      action('start', 'Start', {disabled: node.running}, () => run(['start'], 'start')),
      action('stop', 'Stop', {class: 'danger', disabled: !node.running}, async () => {
        const yes = await ask(`Stop ${node.name}'s node?`, [el('p', {}, 'The other members lose this signer until it '
          + 'starts again. Its data is kept.')], 'Stop node', true);
        if (yes) await run(['stop'], 'stop');
      })));
}

function renderApps(state) {
  const box = $('apps');
  box.replaceChildren();
  const node = state.node;
  if (!node?.apps?.length) {
    box.append(el('p', {class: 'empty'}, 'This node has not joined an application yet.'));
    return;
  }
  for (const app of node.apps) {
    const status = app.status;
    const item = el('article', {class: 'item'}, el('div', {class: 'item-head'}, el('h3', {}, app.name),
      status ? el('div', {class: 'meta'}, el('span', {class: `tag ${status.stage === 'Live' ? 'ok' : 'warn'}`},
        status.stage)) : null));
    if (status) {
      const at = STAGES.indexOf(status.stage);
      item.append(
        el('ol', {class: 'ladder', 'aria-label': 'Application stage'}, STAGES.map((stage, index) =>
          el('li', {class: index === at ? 'now' : index < at ? 'done' : '',
            'aria-current': index === at ? 'step' : null}, stage))),
        el('div', {class: 'people'}, status.members.map(member => el('div', {class: 'person'},
          el('span', {class: `avatar ${member.online ? '' : 'pending'}`}, initial(member.name)),
          el('div', {}, el('strong', {}, member.name),
            el('small', {class: member.online ? 'ok' : 'warn'}, member.online ? 'online' : 'offline'))))),
        el('div', {class: 'stats'}, status.chains.map(chain => el('div', {class: 'stat'},
          el('div', {class: 'top'}, el('strong', {}, chain.chain),
            el('span', {class: `tag ${chain.agree ? 'ok' : 'bad'}`}, chain.agree ? 'members agree' : 'MISMATCH')),
          el('div', {class: 'height'}, el('span', {'data-height': `${app.name}/${chain.chain}`},
            String(chain.tipHeight ?? '–')), el('small', {}, 'height')),
          el('p', {}, `${chain.members ?? '?'} members · ${chain.threshold ?? '?'} signatures`
            + ` · anchoring ${chain.anchoring ?? '?'}`),
          chain.topics ? el('p', {class: 'topics'}, `topics: ${chain.topics}`) : null))),
        ...status.problems.map(problem => el('div', {class: 'problem'}, problem)),
        el('p', {class: 'muted', 'data-cardano': app.name}, `Cardano: ${status.cardano}`),
        status.next?.length ? el('div', {class: 'next'}, el('strong', {}, 'Next: '), nextText(status.next)) : null);
    } else {
      item.append(el('p', {class: 'empty'}, 'Start this node to see the application live.'));
    }
    item.append(el('div', {class: 'tools'}, approveTool(app), topicsTool(app, state), anchorTool(app, status, node)));
    box.append(item);
  }
}

/** What changes with every block, without a redraw: the dashboard keeps focus, open lists and drags. */
function liveSlice(node) {
  return [node.running, (node.apps ?? []).map(app => [app.name, app.status && {stage: app.status.stage,
    members: app.status.members.map(member => [member.name, member.online]), problems: app.status.problems,
    next: app.status.next, chains: app.status.chains.map(chain => [chain.chain, chain.agree, chain.members,
      chain.threshold, chain.anchoring, chain.topics])}])];
}
function patchLive(node) {
  for (const app of node.apps ?? []) {
    for (const chain of app.status?.chains ?? []) {
      const height = document.querySelector(`[data-height="${CSS.escape(`${app.name}/${chain.chain}`)}"]`);
      if (height) height.textContent = String(chain.tipHeight ?? '–');
    }
    const cardano = document.querySelector(`[data-cardano="${CSS.escape(app.name)}"]`);
    if (cardano && app.status) cardano.textContent = `Cardano: ${app.status.cardano}`;
  }
}

/** The CLI's next step, pointing at this page's own tool where there is one. */
function nextText(lines) {
  const text = lines.join(' ');
  return /yano\.sh appchain anchor/.test(text) ? 'optional: anchor a chain to Cardano with Anchor to Cardano below.'
    : text;
}

function fileDrop(input, label) {
  const zone = el('label', {class: 'drop inline'}, input, el('span', {}, label));
  wireDrop(zone, input);
  return zone;
}

/** Lets a drop zone take a dragged file as if it were chosen. */
function wireDrop(zone, input) {
  zone.addEventListener('dragover', event => { event.preventDefault(); zone.classList.add('over'); });
  zone.addEventListener('dragleave', () => zone.classList.remove('over'));
  zone.addEventListener('drop', event => {
    event.preventDefault();
    zone.classList.remove('over');
    if (!event.dataTransfer?.files?.length) return;
    input.files = event.dataTransfer.files;
    input.dispatchEvent(new Event('change'));
  });
}

async function upload(file) {
  let saved;
  try {
    saved = await api('upload', {name: file.name, content: await file.text()});
  } catch {
    saved = {error: 'Lost contact with the local server'};
  }
  toast(saved.error ?? `Added ${file.name}`, saved.error ? 'bad' : 'ok');
  return !saved.error;
}

function approveTool(app) {
  const key = `approve:${app.name}`;
  const card = el('input', {type: 'file', accept: '.member', 'aria-label': 'New member card'});
  card.addEventListener('change', async () => {
    const file = card.files[0];
    card.value = '';
    if (!file || ui.busy.has(key) || !await upload(file)) return;
    let listed;
    try {
      listed = (await api('state')).cards?.find(item => item.file === file.name);
    } catch {
      listed = null;
    }
    const yes = await ask(`Add ${listed?.name ?? 'this operator'} to ${app.name}?`, [
      el('p', {}, listed ? `Member card ${file.name}: ${listed.name}, key ${listed.keyId}`
        + (listed.valid ? '' : ' (its signature does not check)') : `Member card ${file.name}`),
      el('p', {}, 'Compare the key id with them on a call first. This node approves the member on every chain.')],
    'Approve member');
    if (yes) await run(['approve', file.name, '--app', app.name, '--heartbeat'], key);
  });
  return el('div', {class: 'tool'}, el('h4', {}, 'Add a member'),
    el('p', {}, 'Approve a new operator’s member card on every chain; each existing operator does this.'),
    fileDrop(card, 'Drop or choose their .member card'));
}

// UNKNOWN still offers the vote (approve checks before it casts); LAPSED offers to vote again.
const STANDING = {IN_EFFECT: ['ok', 'in effect'], DECIDED: ['accent', 'approved, activating'],
  VOTED: ['', 'you voted'], IN_FLIGHT: ['', 'your vote is on its way'], REFUSED: ['bad', 'your vote was refused'],
  LAPSED: ['warn', 'your earlier vote lapsed'], SUPERSEDED: ['bad', 'out of date']};
// The standing that most needs the operator's attention is shown for the whole plan.
const ATTENTION = ['SUPERSEDED', 'REFUSED', 'LAPSED', 'IN_FLIGHT', 'VOTED', 'DECIDED', 'IN_EFFECT'];

/** A tag when this member has nothing to cast on a plan, or null when a vote is still open. */
function planStatus(plan) {
  const progress = plan.votes.map(vote => vote.progress ?? 'UNKNOWN');
  if (progress.some(value => value === 'OPEN' || value === 'UNKNOWN')) return null;
  if (progress.includes('LAPSED') && !progress.some(value => ['SUPERSEDED', 'REFUSED'].includes(value))) return null;
  const [kind, text] = STANDING[ATTENTION.find(value => progress.includes(value))] ?? ['warn', 'unknown'];
  return el('span', {class: `tag ${kind}`}, text);
}

function topicsTool(app, state) {
  const chains = new Set((app.status?.chains ?? []).filter(chain => chain.topics).map(chain => chain.chain));
  if (!chains.size) return null;
  const plans = (state.plans ?? []).filter(plan => plan.kind === 'votes' && chains.has(plan.chain));
  const input = el('input', {type: 'file', accept: '.json', 'aria-label': 'Topics plan'});
  input.addEventListener('change', async () => {
    const file = input.files[0];
    input.value = '';
    if (file) await upload(file);
    await refresh(true);
  });
  return el('div', {class: 'tool'}, el('h4', {}, 'Topic votes'),
    el('p', {}, 'Vote on a namespace change someone planned with topics plan. This node never votes twice.'),
    plans.length ? plans.map(plan => el('div', {class: 'plan'},
      el('span', {}, plan.votes.map(vote => `${vote.operation} ${vote.target}`).join(', ')),
      planStatus(plan) ?? voteButton(plan, state))) : el('p', {class: 'muted'}, 'No plan to vote on here yet.'),
    fileDrop(input, 'Drop or choose a .plan.json you received'));
}

function voteButton(plan, state) {
  const progress = plan.votes.map(vote => vote.progress);
  const again = progress.includes('LAPSED') && !progress.includes('OPEN');
  return action(`vote:${plan.file}`, again ? 'Vote again' : 'Vote', {class: 'primary tiny'}, async () => {
        const yes = await ask(`Vote yes on ${plan.file}?`, [
          el('p', {}, `As ${state.node.name}, on chain ${plan.chain}, through this node:`),
          el('pre', {class: 'details'}, plan.votes.flatMap(vote => vote.details ?? []).join('\n')),
          again ? el('p', {}, 'This node voted before, but that vote expired before enough operators voted.') : null,
          progress.includes('UNKNOWN') ? el('p', {}, 'The node could not say where this vote stands; approve checks '
            + 'again before it votes and never votes twice.') : null], again ? 'Vote again' : 'Vote yes');
    // The vote names the exact plan shown; a file changed since then is refused.
    if (yes) {
      await run(['topics', 'approve', plan.file, '--plan-sha256', plan.sha256, '--yes', '--no-wait',
        ...(again ? ['--again'] : [])], `vote:${plan.file}`);
    }
  });
}

function anchorTool(app, status, node) {
  const choices = (status?.chains ?? []).map(item => item.chain);
  const chosen = choices.includes(ui.anchor[app.name]) ? ui.anchor[app.name] : choices[0];
  const chain = el('select', {'aria-label': 'Chain to anchor',
    onchange: event => { ui.anchor[app.name] = event.target.value; }},
  choices.map(item => el('option', {value: item, selected: item === chosen}, item)));
  return el('div', {class: 'tool'}, el('h4', {}, 'Anchor to Cardano'),
    el('p', {}, 'Optional: write a chain’s height and state root to Cardano from this node.'),
    el('div', {class: 'actions'}, chain,
      action(`anchor:${app.name}`, 'Set up', {class: 'ghost', disabled: !node.running || !choices.length},
        async () => {
          const yes = await ask('Set up anchoring?', [el('p', {}, 'Anchoring spends ADA from a wallet on this node. '
            + 'Fund it only with what you are willing to spend on fees; its balance is the limit.')], 'Set up');
          if (yes) await run(['anchor', app.name, '--chain', chain.value], `anchor:${app.name}`);
        })));
}

/** Puts the sections in order after {@code reference}, moving only those out of place so focus is kept. */
function place(reference, ids) {
  let at = reference;
  for (const id of ids) {
    if (at.nextElementSibling !== $(id)) at.after($(id));
    at = $(id);
  }
}

function renderProgress(state) {
  const apps = state.node?.apps ?? [];
  const live = apps.some(app => app.status?.stage === 'Live');
  const joined = apps.length > 0;
  const sealed = state.sealed.length > 0 || state.drafts.some(draft => draft.sealed);
  const cardsIn = state.drafts.some(draft => draft.operators.every(name =>
    state.cards.some(card => card.invite === draft.invite && card.name === name)));
  // Draft · Members · Sealed · Starting · Live, the stages status reports.
  const step = live ? 4 : joined ? 3 : sealed ? 2 : state.drafts.length ? 1 : 0;
  $('stepper').querySelectorAll('li').forEach((item, index) => {
    item.className = index === step ? 'now' : index < step ? 'done' : '';
    if (index === step) item.setAttribute('aria-current', 'step'); else item.removeAttribute('aria-current');
    item.querySelector('.state').textContent = index === step ? ' (current)' : index < step ? ' (done)' : '';
  });
  $('hero-title').textContent = live ? 'Your application is live' : joined ? 'Start your node and go live'
    : sealed ? 'Compare the fingerprint, then join' : cardsIn ? 'Seal the application'
      : state.drafts.length ? 'Collect a member card from every operator' : 'Set up an app chain with your partners';
  const joinable = state.sealed.some(app => !apps.some(joinedApp => joinedApp.name === app.name));
  const current = joinable ? 'join' : live ? null : joined ? 'live' : sealed ? 'join'
    : state.drafts.length ? 'cards' : 'create';
  for (const id of ['create', 'cards', 'join', 'live']) {
    $(id).classList.toggle('highlight', id === current);
    $(id).classList.toggle('done', id === 'create' ? state.drafts.length > 0 || joined
      : id === 'cards' ? sealed || joined : id === 'join' && joined);
  }
  // Once this node has joined, its applications lead. Setup folds into its history only when nothing in it is
  // left to do here; the drop zone stays in view, so a new sealed file or draft brings the steps back.
  const names = new Set(apps.map(app => app.name));
  const settled = joined && state.sealed.every(app => names.has(app.name)) && state.drafts.every(draft => draft.sealed);
  const hero = $('flow').querySelector('.hero');
  const history = $('history');
  history.hidden = !settled;
  const drop = $('upload-drop');
  if (settled) {
    place(hero, ['live']);
    const slot = $('history-slot');
    for (const id of ['create', 'cards', 'join']) if ($(id).parentElement !== slot) slot.append($(id));
    if (drop.parentElement !== $('live')) $('live').append(drop);
  } else {
    place(hero, joined ? ['live', 'join', 'cards', 'create'] : ['create', 'cards', 'join', 'live']);
    if (drop.parentElement !== $('cards')) $('cards').append(drop);
  }
  const details = $('create-details');
  if (!ui.createTouched && details.open !== (step === 0)) {
    ui.createProgrammatic = true;
    details.open = step === 0;
  }
}

function download(name) {
  return async event => {
    event.preventDefault();
    try {
      const response = await fetch(`/api/file?name=${encodeURIComponent(name)}`, {headers: {'X-UI-Token': token}});
      if (!response.ok) throw new Error(String(response.status));
      const link = el('a', {href: URL.createObjectURL(await response.blob()), download: name});
      link.click();
      URL.revokeObjectURL(link.href);
    } catch {
      toast(`Cannot download ${name}`, 'bad');
    }
  };
}

/** Rebuilds a section only when its data changed, so a refresh never takes away what the operator is doing. */
function section(name, data, render, state, force) {
  const json = JSON.stringify(data);
  if (!force && ui.slices[name] === json) return;
  ui.slices[name] = json;
  render(state);
}

function banner(text) {
  $('problems').hidden = !text;
  $('problems').textContent = text ?? '';
}

let polling = false;
let requested = 0;
let applied = 0;

async function refresh(force = false) {
  // A poll waits for the one before it; a forced refresh after a command always runs, and an older answer that
  // arrives after a newer one is dropped.
  if (!force && polling) return;
  const ticket = ++requested;
  polling = true;
  let state;
  try {
    state = await api('state');
  } catch {
    if (ticket === requested) polling = false;
    banner('Lost contact with the local server. Is the ui command still running?');
    return;
  }
  if (ticket === requested) polling = false;
  if (ticket < applied) return;
  applied = ticket;
  if (state.error) {
    banner(`${state.error}. Open the address the ui command printed.`);
    return;
  }
  $('work-dir').textContent = state.work;
  banner((state.problems ?? []).join(' · ') || null);
  renderProgress(state);
  const node = state.node ?? {};
  section('drafts', [state.drafts, state.cards, node.name], renderDrafts, state, force);
  section('sealed', [state.sealed, (node.apps ?? []).map(app => app.name)], renderSealed, state, force);
  section('node', [node.name, node.keyId, node.node, node.http, node.running,
    (node.apps ?? []).map(app => app.status?.stage)], renderNode, state, force);
  section('apps', [liveSlice(node), state.plans], renderApps, state, force);
  patchLive(node);
  markBusy();
}

async function start() {
  try {
    recipes = await api('recipes');
  } catch {
    recipes = {error: 'Lost contact with the local server. Is the ui command still running?'};
  }
  if (recipes.error) {
    banner(`${recipes.error}. Open the address the ui command printed.`);
    recipes = {};
  }
  const rows = $('chain-rows');
  rows.append(chainRow());
  $('add-chain').addEventListener('click', () => { rows.append(chainRow()); updateRows(); });
  $('create-form').addEventListener('input', updateHint);
  $('create-form').addEventListener('change', updateHint);
  $('create-details').addEventListener('toggle', () => {
    if (ui.createProgrammatic) ui.createProgrammatic = false;
    else ui.createTouched = true;
  });
  const create = $('create-form').querySelector('button[type=submit]');
  create.dataset.key = 'create';
  create.dataset.static = '';
  $('create-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (!ui.busy.has('create')) await run(createArgs(event.target), 'create');
  });
  $('upload').addEventListener('change', async event => {
    const file = event.target.files[0];
    event.target.value = '';
    if (file) await upload(file);
    await refresh(true);
  });
  wireDrop($('upload-drop'), $('upload'));
  $('clear-log').addEventListener('click', () =>
    $('log').replaceChildren(el('p', {class: 'empty'}, 'Commands you run appear here with their output.')));
  updateRows();
  await refresh(true);
  setInterval(() => refresh(false), 5000);
}

start();
