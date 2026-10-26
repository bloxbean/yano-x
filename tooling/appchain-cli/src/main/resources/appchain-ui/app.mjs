// App-Chain Setup page (ADR-056 D7). Every action runs a `yano.sh appchain` command on this computer through the
// local server and shows that command; the page itself holds no keys and writes no files of its own.

const token = new URLSearchParams(location.search).get('t') ?? readStored();
storeToken(token);
let recipes = {};

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
    else if (value !== false && value !== undefined) node.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) if (child !== null && child !== undefined) node.append(child);
  return node;
}

async function run(args, button) {
  if (button) button.disabled = true;
  const entry = el('div', {class: 'entry'}, el('div', {class: 'command'}, `$ yano.sh appchain ${args.join(' ')}`),
    el('div', {class: 'note'}, 'running…'));
  document.getElementById('log').prepend(entry);
  try {
    const result = await api('run', {args});
    entry.lastChild.replaceWith(el('pre', {class: result.exit === 0 ? '' : 'bad'},
      (result.out ?? '') + (result.err ?? '') + (result.error ?? '')));
    await refresh();
    return result;
  } finally {
    if (button) button.disabled = false;
  }
}

function commandHint(args) {
  return el('div', {class: 'command'}, `yano.sh appchain ${args.join(' ')}`);
}

// --- Create -----------------------------------------------------------------------------------------------

function chainRow() {
  const recipe = el('select', {name: 'recipe', 'aria-label': 'Recipe'},
    Object.entries(recipes).filter(([id]) => id !== 'declarative-composite')
      .map(([id, info]) => el('option', {value: id, title: info.summary}, id)),
    el('option', {value: 'bindings'}, 'several state machines (bindings file)'));
  recipe.value = 'audit-log';
  const extra = el('input', {name: 'extra', 'aria-label': 'Answers or bindings file',
    placeholder: 'answers: name=value, … or bindings file'});
  const row = el('div', {class: 'chain-row'},
    el('input', {name: 'id', required: true, 'aria-label': 'Chain id', placeholder: 'orders'}), recipe, extra,
    el('button', {type: 'button', class: 'secondary', onclick: () => row.remove()}, 'Remove'));
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
  const args = ['new', data.get('name'), '--network', data.get('network'), '--operators',
    data.get('operators').replaceAll(' ', ''), '--threshold', data.get('threshold'),
    '--sequencing', data.get('sequencing')];
  for (const row of form.querySelectorAll('.chain-row')) {
    const id = row.querySelector('[name=id]').value.trim();
    const recipe = row.querySelector('[name=recipe]').value;
    const extra = row.querySelector('[name=extra]').value.trim();
    if (!id) continue;
    if (recipe === 'bindings') {
      args.push('--chain', `${id}=bindings:${extra}`);
    } else {
      args.push('--chain', `${id}=${recipe}`);
      for (const answer of extra.split(',').map(item => item.trim()).filter(Boolean)) {
        args.push('--answer', `${id}.${answer}`);
      }
    }
  }
  return args;
}

// --- Rendering ---------------------------------------------------------------------------------------------

function renderDrafts(state) {
  const box = document.getElementById('drafts');
  box.replaceChildren();
  if (!state.drafts.length) {
    box.append(el('p', {class: 'note'}, 'No draft here yet. Create one above, or add a draft file you received.'));
    return;
  }
  for (const draft of state.drafts) {
    const cards = state.cards.filter(card => card.invite === draft.invite);
    const byName = Object.fromEntries(cards.map(card => [card.name, card]));
    const missing = draft.operators.filter(name => !byName[name]);
    const mine = state.node?.name;
    const name = el('select', {'aria-label': 'Your operator name'},
      draft.operators.map(operator => el('option', {value: operator}, operator)));
    if (mine && draft.operators.includes(mine)) name.value = mine;
    const host = el('input', {'aria-label': 'Address other members reach this node at',
      value: draft.network === 'devnet' ? '127.0.0.1' : '', placeholder: 'node-a.example.com'});
    const nodePort = el('input', {'aria-label': 'Node-to-node port', value: '13337', size: 6});
    const httpPort = el('input', {'aria-label': 'HTTP port', value: '8080', size: 6});
    const requestArgs = () => ['request', draft.file, '--name', name.value, '--host', host.value.trim(),
      '--node-port', nodePort.value, '--http-port', httpPort.value];
    const sealArgs = ['seal', draft.file];
    box.append(el('div', {class: 'item'},
      el('h3', {}, `${draft.name} `, el('span', {class: 'note'}, `invite ${draft.invite} · ${draft.network}`)),
      el('div', {class: 'members'}, draft.operators.flatMap(operator => [
        el('span', {}, operator + (operator === draft.operators[0] ? ' (lead)' : '')),
        el('span', {class: byName[operator] ? 'ok' : 'note'},
          byName[operator] ? `card · key ${byName[operator].keyId}` : 'waiting for card'),
        el('span', {}, byName[operator] && !byName[operator].valid ? el('span', {class: 'bad'}, 'bad signature') : ''),
      ])),
      draft.chains.map(chain => el('div', {class: 'note'}, `chain ${chain.id}: ${chain.recipe}`)),
      mine ? (byName[mine] ? null : el('div', {class: 'actions'},
          el('span', {}, `This node is ${mine}.`),
          el('button', {onclick: event => run(['request', draft.file], event.target)}, `Make ${mine}'s card`)))
        : el('div', {class: 'actions'},
          el('span', {}, 'Make my card as'), name, host, nodePort, httpPort,
          el('button', {onclick: event => run(requestArgs(), event.target)}, 'Make my card')),
      el('div', {class: 'actions'},
        el('button', {disabled: missing.length > 0 || draft.sealed, onclick: event => run(sealArgs, event.target)},
          draft.sealed ? 'Sealed' : missing.length ? `Seal (waiting for ${missing.join(', ')})` : 'Seal'),
        el('a', {href: `/api/file?name=${encodeURIComponent(draft.file)}`, onclick: download(draft.file)},
          'Download draft to send')),
      commandHint(sealArgs)));
  }
}

function renderSealed(state) {
  const box = document.getElementById('sealed');
  box.replaceChildren();
  if (!state.sealed.length) {
    box.append(el('p', {class: 'note'}, 'No sealed file here yet.'));
    return;
  }
  const joined = new Set((state.node?.apps ?? []).map(app => app.name));
  for (const app of state.sealed) {
    const compared = el('input', {type: 'checkbox', id: `compare-${app.name}`});
    const joinArgs = ['join', app.file, '--expect-fingerprint', app.fingerprint];
    const join = el('button', {disabled: true, onclick: event => run(joinArgs, event.target)},
      joined.has(app.name) ? 'Joined (join again to refresh)' : 'Join');
    compared.addEventListener('change', () => { join.disabled = !compared.checked; });
    box.append(el('div', {class: 'item'},
      el('h3', {}, `${app.name} `, el('span', {class: 'note'},
        `${app.network} · ${app.threshold} signatures per block`)),
      el('div', {}, 'Chain fingerprint ', el('span', {class: 'fingerprint'}, app.fingerprint)),
      el('div', {class: 'members'}, app.members.flatMap(member => [
        el('span', {}, member.name + (member.name === app.lead ? ' (lead)' : '')),
        el('code', {}, member.keyId), el('span', {})])),
      app.chains.map(chain => el('div', {class: 'note'}, `chain ${chain.id}: ${chain.recipe}`)),
      el('label', {class: 'actions', for: compared.id}, compared,
        'On a call with the others, I compared this fingerprint and every key id, and they match.'),
      el('div', {class: 'actions'}, join,
        el('a', {href: `/api/file?name=${encodeURIComponent(app.file)}`, onclick: download(app.file)},
          'Download sealed file to send')),
      commandHint(joinArgs)));
  }
}

function renderNode(state) {
  const box = document.getElementById('node');
  const badge = document.getElementById('node-badge');
  box.replaceChildren();
  const node = state.node;
  if (!node?.name) {
    badge.textContent = 'No node yet';
    box.append(el('p', {class: 'note'}, 'This node has no key yet: make a member card for a draft first.'));
    return;
  }
  const live = (node.apps ?? []).some(app => app.status?.stage === 'Live');
  badge.textContent = `${node.name} · key ${node.keyId} · ${node.running ? (live ? 'live' : 'running') : 'stopped'}`;
  badge.className = `badge ${live ? 'live' : ''}`;
  box.append(el('p', {}, `${node.name} reachable at `, el('code', {}, node.node), ' · ', el('code', {}, node.http)),
    el('div', {class: 'actions'},
      el('button', {disabled: node.running, onclick: event => run(['start'], event.target)}, 'Start'),
      el('button', {class: 'secondary', disabled: !node.running, onclick: event => run(['stop'], event.target)},
        'Stop'),
      commandHint(['start'])));
  for (const app of node.apps ?? []) {
    const status = app.status;
    const item = el('div', {class: 'item'}, el('h3', {}, app.name));
    if (status) {
      item.append(
        el('div', {class: 'ladder'}, ['Draft', 'Members', 'Sealed', 'Starting', 'Live'].map(stage =>
          el('span', {class: stage === status.stage ? 'now' : ''}, stage))),
        el('div', {class: 'members'}, status.members.flatMap(member => [
          el('span', {}, member.name), el('code', {}, member.keyId),
          el('span', {class: member.online ? 'ok' : 'warn'}, member.online ? 'online' : 'offline')])),
        ...status.chains.map(chain => el('div', {class: 'note'},
          `chain ${chain.chain}: height ${chain.tipHeight}, ${chain.agree ? 'members agree' : 'MISMATCH'}, `
          + `${chain.members ?? '?'} members, ${chain.threshold ?? '?'} signatures, anchoring ${chain.anchoring ?? '?'}`)),
        el('div', {class: 'note'}, `Cardano: ${status.cardano}`),
        ...status.problems.map(problem => el('div', {class: 'bad'}, problem)),
        el('p', {}, 'Next: ', status.next.join(' ')));
    } else {
      item.append(el('p', {class: 'note'}, 'Start this node to see the application live.'));
    }
    const card = el('input', {type: 'file', accept: '.member', 'aria-label': 'Member card to approve'});
    card.addEventListener('change', async () => {
      const file = card.files[0];
      if (!file) return;
      await api('upload', {name: file.name, content: await file.text()});
      await run(['approve', file.name, '--app', app.name, '--heartbeat'], null);
    });
    const anchorChain = el('select', {'aria-label': 'Chain to anchor'},
      (status?.chains ?? []).map(chain => el('option', {value: chain.chain}, chain.chain)));
    item.append(
      el('label', {class: 'file'}, 'Approve a new member (their .member card)', card),
      el('div', {class: 'actions'}, 'Optional: anchor', anchorChain, 'to Cardano from this node',
        el('button', {class: 'secondary', disabled: !node.running, onclick: event => {
          if (confirm('Anchoring spends ADA from a wallet on this node; fund it only with what you will spend. '
            + 'Continue?')) run(['anchor', app.name, '--chain', anchorChain.value], event.target);
        }}, 'Set up anchoring')));
    box.append(item);
  }
}

function download(name) {
  return async event => {
    event.preventDefault();
    const response = await fetch(`/api/file?name=${encodeURIComponent(name)}`, {headers: {'X-UI-Token': token}});
    const link = el('a', {href: URL.createObjectURL(await response.blob()), download: name});
    link.click();
    URL.revokeObjectURL(link.href);
  };
}

async function refresh() {
  const state = await api('state');
  if (state.error) {
    document.getElementById('problems').hidden = false;
    document.getElementById('problems').textContent = `${state.error}. Open the address the ui command printed.`;
    return;
  }
  document.getElementById('work-dir').textContent = state.work;
  const problems = document.getElementById('problems');
  problems.hidden = !(state.problems ?? []).length;
  problems.textContent = (state.problems ?? []).join(' · ');
  renderDrafts(state);
  renderSealed(state);
  renderNode(state);
}

async function start() {
  recipes = await api('recipes');
  const rows = document.getElementById('chain-rows');
  rows.append(chainRow());
  document.getElementById('add-chain').addEventListener('click', () => rows.append(chainRow()));
  document.getElementById('create-form').addEventListener('submit', async event => {
    event.preventDefault();
    await run(createArgs(event.target), event.submitter);
  });
  document.getElementById('upload').addEventListener('change', async event => {
    const file = event.target.files[0];
    if (!file) return;
    const result = await api('upload', {name: file.name, content: await file.text()});
    if (result.error) alert(result.error);
    event.target.value = '';
    await refresh();
  });
  await refresh();
  setInterval(refresh, 5000);
}

start();
