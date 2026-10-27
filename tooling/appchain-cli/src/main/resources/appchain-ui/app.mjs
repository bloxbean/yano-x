// App-Chain Setup page (ADR-056 D7). Every action runs a `yano.sh appchain` command on this computer through the
// local server and shows that command; the page itself holds no keys and writes no files of its own.

const token = new URLSearchParams(location.search).get('t') ?? readStored();
storeToken(token);
let recipes = {};
let createTouched = false;
let createProgrammatic = false;
const STAGES = ['Draft', 'Members', 'Sealed', 'Starting', 'Live'];
const STEPS = ['create', 'cards', 'seal', 'join', 'live'];

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

function toast(message, kind = 'ok') {
  const item = el('div', {class: `toast ${kind}`}, message);
  $('toasts').append(item);
  setTimeout(() => item.remove(), 6000);
}

/** A confirmation in the page; resolves true only on Continue. */
function ask(title, body, ok = 'Continue') {
  const dialog = $('dialog');
  $('dialog-title').textContent = title;
  $('dialog-body').replaceChildren(...[body].flat());
  $('dialog-ok').textContent = ok;
  dialog.showModal();
  return new Promise(resolve => dialog.addEventListener('close', () => resolve(dialog.returnValue === 'ok'),
    {once: true}));
}

async function run(args, button) {
  if (button) { button.disabled = true; button.classList.add('busy'); }
  const status = el('em', {class: 'run'}, 'running');
  const entry = el('div', {class: 'entry'}, el('div', {class: 'cmd'}, `$ yano.sh appchain ${args.join(' ')}`, status));
  const log = $('log');
  log.querySelector('.empty')?.remove();
  log.prepend(entry);
  try {
    const result = await api('run', {args});
    const ok = result.exit === 0;
    status.className = ok ? 'ok' : 'bad';
    status.textContent = ok ? 'done' : result.exit === 3 ? 'not yet' : 'failed';
    entry.append(el('pre', {class: ok ? '' : 'bad'}, (result.out ?? '') + (result.err ?? '') + (result.error ?? '')));
    const verb = args[0] === 'topics' ? `topics ${args[1]}` : args[0];
    toast(ok ? `${verb} finished` : `${verb}: ${firstLine(result.err ?? result.error ?? '')}`, ok ? 'ok' : 'bad');
    await refresh();
    return result;
  } finally {
    if (button) { button.disabled = false; button.classList.remove('busy'); }
  }
}
const firstLine = text => text.split('\n').find(line => line.trim()) ?? 'see Activity';

function commandHint(args) {
  return el('div', {class: 'command'}, `yano.sh appchain ${args.join(' ')}`);
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
    el('option', {value: 'registrars'}, 'namespaces by named registrars'),
    el('option', {value: 'anyone'}, 'namespaces claimed by anyone'));
  const registrars = el('input', {name: 'registrars', placeholder: 'registrars, e.g. alice', hidden: true});
  topics.addEventListener('change', () => { registrars.hidden = topics.value !== 'registrars'; });
  const row = el('div', {class: 'chain-row'},
    el('label', {}, 'Chain id', el('input', {name: 'id', required: true, placeholder: 'orders', autocomplete: 'off'})),
    el('label', {}, 'Runs', recipe),
    el('label', {}, 'Answers or bindings', extra),
    el('label', {}, 'Topic policies', topics, registrars),
    el('button', {type: 'button', class: 'ghost tiny', 'aria-label': 'Remove this chain',
      onclick: () => { row.remove(); updateHint(); }}, 'Remove'));
  const hint = () => {
    const answers = recipes[recipe.value]?.answers ?? [];
    extra.placeholder = recipe.value === 'bindings' ? 'bindings file next to the draft, e.g. flow.yaml'
      : answers.length ? `needs: ${answers.map(answer => `${answer}=…`).join(', ')}` : 'no answers needed';
  };
  recipe.addEventListener('change', hint);
  hint();
  return row;
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
  $('create-hint').textContent = `yano.sh appchain ${createArgs($('create-form')).join(' ')}`;
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
    const name = el('select', {}, draft.operators.map(operator => el('option', {value: operator}, operator)));
    if (mine && draft.operators.includes(mine)) name.value = mine;
    const host = el('input', {value: draft.network === 'devnet' ? '127.0.0.1' : '', placeholder: 'node-a.example.com'});
    const nodePort = el('input', {value: '13337', size: 6, inputmode: 'numeric'});
    const httpPort = el('input', {value: '8080', size: 6, inputmode: 'numeric'});
    const requestArgs = () => ['request', draft.file, '--name', name.value, '--host', host.value.trim(),
      '--node-port', nodePort.value, '--http-port', httpPort.value];
    const sealArgs = ['seal', draft.file];
    const ready = !missing.length;
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
      mine ? (byName[mine] ? null : el('div', {class: 'actions'},
          el('button', {class: 'primary', onclick: event => run(['request', draft.file], event.target)},
            `Make ${mine}'s member card`), commandHint(['request', draft.file])))
        : el('div', {class: 'inline-form'},
          el('label', {}, 'I am', name), el('label', {}, 'Reachable at', host),
          el('label', {}, 'Node port', nodePort), el('label', {}, 'HTTP port', httpPort),
          el('button', {class: 'primary', onclick: event => run(requestArgs(), event.target)}, 'Make my member card')),
      el('div', {class: 'actions'},
        el('button', {class: draft.sealed ? 'ghost' : 'primary', disabled: !ready || draft.sealed,
          onclick: event => run(sealArgs, event.target)},
          draft.sealed ? 'Sealed' : ready ? 'Seal the application' : `Waiting for ${missing.join(', ')}`),
        el('button', {class: 'ghost', onclick: download(draft.file)}, 'Download draft to send')),
      commandHint(sealArgs)));
  }
}

function renderSealed(state) {
  const box = $('sealed');
  box.replaceChildren();
  if (!state.sealed.length) {
    box.append(el('p', {class: 'empty'}, 'No sealed file here yet. The coordinator seals the draft and sends it to everyone.'));
    return;
  }
  const joined = new Set((state.node?.apps ?? []).map(app => app.name));
  for (const app of state.sealed) {
    const compared = el('input', {type: 'checkbox', id: `compare-${app.name}`});
    const joinArgs = ['join', app.file, '--expect-fingerprint', app.fingerprint];
    const join = el('button', {class: 'primary', disabled: true, onclick: event => run(joinArgs, event.target)},
      joined.has(app.name) ? 'Join again to refresh' : 'Join');
    compared.addEventListener('change', () => { join.disabled = !compared.checked; });
    box.append(el('article', {class: 'item'},
      el('div', {class: 'item-head'}, el('h3', {}, app.name),
        el('div', {class: 'meta'}, el('span', {class: 'tag'}, app.network),
          el('span', {class: 'tag'}, `${app.threshold} signatures per block`),
          joined.has(app.name) ? el('span', {class: 'tag ok'}, 'joined') : null)),
      el('p', {class: 'empty'}, 'Chain fingerprint'),
      el('div', {class: 'fingerprint'}, app.fingerprint.split('-').map(group => el('span', {}, group))),
      el('div', {class: 'people'}, app.members.map(member => el('div', {class: 'person'},
        el('span', {class: 'avatar'}, initial(member.name)),
        el('div', {}, el('strong', {}, member.name + (member.name === app.lead ? ' · lead' : '')),
          el('small', {}, member.keyId))))),
      chainChips(app.chains),
      el('label', {class: 'confirm', for: compared.id}, compared,
        'On a call with the others, I compared this fingerprint and every key id, and they match.'),
      el('div', {class: 'actions'}, join,
        el('button', {class: 'ghost', onclick: download(app.file)}, 'Download sealed file to send')),
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
  $('node-badge').textContent = `${node.name} · ${node.running ? (live ? 'live' : 'running') : 'stopped'}`;
  pill.className = `node-pill ${node.running ? (live ? 'live' : 'running') : ''}`;
  box.append(
    el('div', {class: 'node-id'}, el('span', {class: 'avatar'}, initial(node.name)),
      el('div', {}, el('strong', {}, node.name), el('small', {class: 'empty'}, node.running ? 'running' : 'stopped'))),
    el('dl', {class: 'kv'}, el('dt', {}, 'Key id'), el('dd', {}, node.keyId), el('dt', {}, 'Members reach it at'),
      el('dd', {}, node.node), el('dt', {}, 'HTTP'), el('dd', {}, node.http)),
    el('div', {class: 'actions'},
      el('button', {class: 'primary', disabled: node.running, onclick: event => run(['start'], event.target)}, 'Start'),
      el('button', {class: 'ghost', disabled: !node.running, onclick: event => run(['stop'], event.target)}, 'Stop')));
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
        el('div', {class: 'ladder'}, STAGES.map((stage, index) =>
          el('span', {class: index === at ? 'now' : index < at ? 'done' : ''}, stage))),
        el('div', {class: 'people'}, status.members.map(member => el('div', {class: 'person'},
          el('span', {class: `avatar ${member.online ? '' : 'pending'}`}, initial(member.name)),
          el('div', {}, el('strong', {}, member.name),
            el('small', {class: member.online ? 'ok' : 'warn'}, member.online ? 'online' : 'offline'))))),
        el('div', {class: 'stats'}, status.chains.map(chain => el('div', {class: 'stat'},
          el('div', {class: 'top'}, el('strong', {}, chain.chain),
            el('span', {class: `tag ${chain.agree ? 'ok' : 'bad'}`}, chain.agree ? 'members agree' : 'MISMATCH')),
          el('div', {class: 'height'}, String(chain.tipHeight ?? '–'), el('small', {}, 'height')),
          el('p', {}, `${chain.members ?? '?'} members · ${chain.threshold ?? '?'} signatures · anchoring ${chain.anchoring ?? '?'}`),
          chain.topics ? el('p', {class: 'topics'}, `topics: ${chain.topics}`) : null))),
        ...status.problems.map(problem => el('div', {class: 'problem'}, problem)),
        el('p', {class: 'empty'}, `Cardano: ${status.cardano}`),
        status.next?.length ? el('div', {class: 'next'}, el('strong', {}, 'Next: '), status.next.join(' ')) : null);
    } else {
      item.append(el('p', {class: 'empty'}, 'Start this node to see the application live.'));
    }
    item.append(el('div', {class: 'tools'}, approveTool(app), topicsTool(app, state), anchorTool(app, status, node)));
    box.append(item);
  }
}

function approveTool(app) {
  const card = el('input', {type: 'file', accept: '.member'});
  card.addEventListener('change', async () => {
    const file = card.files[0];
    card.value = '';
    if (!file) return;
    const saved = await api('upload', {name: file.name, content: await file.text()});
    if (saved.error) { toast(saved.error, 'bad'); return; }
    await run(['approve', file.name, '--app', app.name, '--heartbeat'], null);
  });
  return el('div', {class: 'tool'}, el('h4', {}, 'Add a member'),
    el('p', {}, 'Approve a new operator’s member card on every chain; each existing operator does this.'),
    el('label', {class: 'drop inline'}, card, el('span', {}, 'Choose their .member card')));
}

function topicsTool(app, state) {
  const chains = new Set((app.status?.chains ?? []).filter(chain => chain.topics).map(chain => chain.chain));
  if (!chains.size) return null;
  const plans = (state.plans ?? []).filter(plan => chains.has(plan.chain) && plan.votes?.length);
  const upload = el('input', {type: 'file', accept: '.json'});
  upload.addEventListener('change', async () => {
    const file = upload.files[0];
    upload.value = '';
    if (!file) return;
    const saved = await api('upload', {name: file.name, content: await file.text()});
    toast(saved.error ?? `Added ${file.name}`, saved.error ? 'bad' : 'ok');
    await refresh();
  });
  return el('div', {class: 'tool'}, el('h4', {}, 'Topic votes'),
    el('p', {}, 'Vote on a namespace change someone planned with  topics plan. It never votes twice.'),
    plans.length ? plans.map(plan => el('div', {class: 'plan'},
      el('span', {}, plan.votes.map(vote => `${vote.operation} ${vote.target}`).join(', ')),
      planStatus(plan) ?? el('button', {class: 'primary tiny', onclick: async event => {
        const yes = await ask(`Vote yes on ${plan.file}?`, [
          el('p', {}, `As ${state.node.name}, on chain ${plan.chain}, through this node:`),
          el('ul', {}, plan.votes.map(vote => el('li', {}, `${vote.operation} ${vote.target}`)))], 'Vote yes');
        // The vote returns at once; the dashboard shows the namespace once enough operators voted.
        if (yes) await run(['topics', 'approve', plan.file, '--yes', '--no-wait'], event.target);
      }}, 'Vote'))) : el('p', {class: 'empty'}, 'No plan to vote on here yet.'),
    el('label', {class: 'drop inline'}, upload, el('span', {}, 'Add a .plan.json you received')));
}

/** What a plan needs from this member, or null when a vote is still to be cast. */
function planStatus(plan) {
  const progress = plan.votes.map(vote => vote.progress);
  if (progress.every(value => value === 'IN_EFFECT')) return el('span', {class: 'tag ok'}, 'in effect');
  if (progress.some(value => value === 'SUPERSEDED')) return el('span', {class: 'tag bad'}, 'out of date');
  if (progress.every(value => value === 'IN_EFFECT' || value === 'DECIDED')) {
    return el('span', {class: 'tag accent'}, 'approved, activating');
  }
  if (progress.every(value => value && value !== 'OPEN')) return el('span', {class: 'tag'}, 'you voted');
  return null;
}

function anchorTool(app, status, node) {
  const chain = el('select', {}, (status?.chains ?? []).map(item => el('option', {value: item.chain}, item.chain)));
  return el('div', {class: 'tool'}, el('h4', {}, 'Anchor to Cardano'),
    el('p', {}, 'Optional: write a chain’s height and state root to Cardano from this node.'),
    el('div', {class: 'actions'}, chain,
      el('button', {class: 'ghost', disabled: !node.running || !status?.chains?.length, onclick: async event => {
        const yes = await ask('Set up anchoring?', [el('p', {}, 'Anchoring spends ADA from a wallet on this node. '
          + 'Fund it only with what you are willing to spend on fees; its balance is the limit.')], 'Set up');
        if (yes) await run(['anchor', app.name, '--chain', chain.value], event.target);
      }}, 'Set up')));
}

function renderProgress(state) {
  const apps = state.node?.apps ?? [];
  const live = apps.some(app => app.status?.stage === 'Live');
  const joined = apps.length > 0;
  const sealed = state.sealed.length > 0 || state.drafts.some(draft => draft.sealed);
  const cardsIn = state.drafts.some(draft => draft.operators.every(name =>
    state.cards.some(card => card.invite === draft.invite && card.name === name)));
  const step = live ? 5 : joined ? 4 : sealed ? 3 : cardsIn ? 2 : state.drafts.length ? 1 : 0;
  $('stepper').querySelectorAll('li').forEach((item, index) => {
    item.className = index < step ? 'done' : index === step ? 'now' : '';
  });
  const titles = ['Set up an app chain with your partners', 'Collect a member card from every operator',
    'Seal the application', 'Compare the fingerprint, then join', 'Start your node and go live', 'Your application is live'];
  $('hero-title').textContent = titles[step];
  for (const [index, id] of STEPS.entries()) {
    $(id)?.classList.toggle('highlight', index === step && step < 5);
    $(id)?.classList.toggle('done', index < step && id !== 'live');
  }
  // Once this node has joined, its applications matter most: the dashboard leads, setup follows.
  const flow = $('flow');
  const anchor = step >= 4 ? flow.querySelector('.hero') : $('join');
  if (anchor.nextElementSibling !== $('live')) anchor.after($('live'));
  // The form folds away once a draft exists, unless the operator opened or closed it themselves.
  const details = $('create-details');
  if (!createTouched && details.open !== (step === 0)) {
    createProgrammatic = true;
    details.open = step === 0;
  }
}

function download(name) {
  return async event => {
    event.preventDefault();
    const response = await fetch(`/api/file?name=${encodeURIComponent(name)}`, {headers: {'X-UI-Token': token}});
    if (!response.ok) { toast(`Cannot download ${name}`, 'bad'); return; }
    const link = el('a', {href: URL.createObjectURL(await response.blob()), download: name});
    link.click();
    URL.revokeObjectURL(link.href);
  };
}

async function refresh() {
  const state = await api('state');
  if (state.error) {
    $('problems').hidden = false;
    $('problems').textContent = `${state.error}. Open the address the ui command printed.`;
    return;
  }
  $('work-dir').textContent = state.work;
  $('problems').hidden = !(state.problems ?? []).length;
  $('problems').textContent = (state.problems ?? []).join(' · ');
  renderProgress(state);
  renderDrafts(state);
  renderSealed(state);
  renderNode(state);
  renderApps(state);
}

async function start() {
  recipes = await api('recipes');
  const rows = $('chain-rows');
  rows.append(chainRow());
  $('add-chain').addEventListener('click', () => { rows.append(chainRow()); updateHint(); });
  $('create-form').addEventListener('input', updateHint);
  $('create-form').addEventListener('change', updateHint);
  $('create-details').addEventListener('toggle', () => {
    if (createProgrammatic) createProgrammatic = false;
    else createTouched = true;
  });
  $('create-form').addEventListener('submit', async event => {
    event.preventDefault();
    await run(createArgs(event.target), event.submitter);
  });
  $('upload').addEventListener('change', async event => {
    const file = event.target.files[0];
    event.target.value = '';
    if (!file) return;
    const result = await api('upload', {name: file.name, content: await file.text()});
    toast(result.error ?? `Added ${file.name}`, result.error ? 'bad' : 'ok');
    await refresh();
  });
  $('clear-log').addEventListener('click', () =>
    $('log').replaceChildren(el('p', {class: 'empty'}, 'Commands you run appear here with their output.')));
  updateHint();
  await refresh();
  setInterval(refresh, 5000);
}

start();
