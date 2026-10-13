// Progressive enhancement for illustrations rendered by ./render.mjs.
//
// The build-time markup is complete on its own. This script only adds
// controls: step chips, back/next, Play, scenario and point-of-view pickers,
// keyboard navigation, and Present (full screen). It respects
// prefers-reduced-motion: Play then advances one step per press.

const PLAY_INTERVAL_MS = 2600;
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');

function enhancePresent(figure) {
  const button = figure.querySelector('[data-present]');
  if (!button || !figure.requestFullscreen) return;
  button.hidden = false;
  button.addEventListener('click', () => {
    if (document.fullscreenElement === figure) void document.exitFullscreen();
    else void figure.requestFullscreen().catch(() => {});
  });
  document.addEventListener('fullscreenchange', () => {
    const active = document.fullscreenElement === figure;
    figure.classList.toggle('is-presenting', active);
    button.textContent = active ? 'Exit' : 'Present';
  });
}

function enhanceSteps(figure) {
  const sections = [...figure.querySelectorAll('.yx-scn')];
  const caption = figure.querySelector('[data-caption]');
  const controls = figure.querySelector('[data-controls]');
  const chips = figure.querySelector('[data-chips]');
  const counter = figure.querySelector('[data-counter]');
  const prev = figure.querySelector('[data-prev]');
  const next = figure.querySelector('[data-next]');
  const play = figure.querySelector('[data-play]');
  const scenarioButtons = [...figure.querySelectorAll('[data-scenario-button]')];
  const viewButtons = [...figure.querySelectorAll('[data-view-button]')];
  for (const picker of figure.querySelectorAll('[data-scenario-picker], [data-view-picker]')) picker.hidden = false;
  caption.hidden = false;
  controls.hidden = false;
  figure.classList.add('is-ready');
  figure.tabIndex = -1;

  let scenario = 0;
  let step = 0;
  let view = viewButtons[0]?.dataset.viewButton ?? 'default';
  let timer = null;

  const items = () => [...sections[scenario].querySelectorAll('.yx-steps > li')];

  function updatePlay() {
    if (reducedMotion.matches) play.textContent = 'Next step';
    else play.textContent = timer ? 'Pause' : 'Play';
    play.setAttribute('aria-pressed', String(Boolean(timer)));
  }

  function stop() {
    clearInterval(timer);
    timer = null;
    updatePlay();
  }

  function renderChips() {
    chips.replaceChildren(...items().map((item, index) => {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'yx-chip';
      const number = document.createElement('span');
      number.textContent = String(index + 1);
      chip.append(number, item.querySelector('.yx-steps__title').textContent.trim());
      chip.addEventListener('click', () => { stop(); go(index); });
      return chip;
    }));
  }

  function go(index) {
    const list = items();
    step = Math.max(0, Math.min(index, list.length - 1));
    sections.forEach((section, i) => { section.hidden = i !== scenario; });
    const section = sections[scenario];
    for (const card of section.querySelectorAll('.yx-card')) {
      const from = Number(card.dataset.from);
      const on = from <= step && step <= Number(card.dataset.to);
      card.classList.toggle('is-on', on);
      card.classList.toggle('is-active', on && from === step);
    }
    for (const wire of section.querySelectorAll('.yx-wire')) {
      wire.classList.toggle('is-on', Number(wire.dataset.step) === step);
    }
    const item = list[step];
    const viewButton = viewButtons.find((button) => button.dataset.viewButton === view);
    const viewFocus = (viewButton?.dataset.viewFocus ?? '').split(' ').filter(Boolean);
    const focus = viewFocus.length ? viewFocus : (item.dataset.focus ?? '').split(' ').filter(Boolean);
    for (const lane of section.querySelectorAll('.yx-lane-head, .yx-lane')) {
      const focused = focus.includes(lane.dataset.lane);
      lane.classList.toggle('is-focus', focused);
      lane.classList.toggle('is-dim', !focused);
    }
    const text = item.querySelector(`.yx-view-text[data-view="${view}"]`)
      ?? item.querySelector('.yx-view-text[data-view="default"]');
    const number = document.createElement('span');
    number.className = 'yx-ill__caption-step';
    number.textContent = String(step + 1);
    const body = document.createElement('div');
    const title = document.createElement('strong');
    title.innerHTML = item.querySelector('.yx-steps__title').innerHTML;
    const paragraph = document.createElement('p');
    paragraph.innerHTML = text.innerHTML;
    body.append(title, paragraph);
    const extra = item.querySelector('.yx-step-extra');
    if (extra) body.append(extra.cloneNode(true));
    caption.replaceChildren(number, body);
    [...chips.children].forEach((chip, i) => chip.setAttribute('aria-pressed', String(i === step)));
    counter.textContent = `Step ${step + 1} / ${list.length}`;
    prev.disabled = step === 0;
    next.disabled = step === list.length - 1;
  }

  prev.addEventListener('click', () => { stop(); go(step - 1); });
  next.addEventListener('click', () => { stop(); go(step + 1); });
  play.addEventListener('click', () => {
    if (reducedMotion.matches) { go(step + 1 >= items().length ? 0 : step + 1); return; }
    if (timer) { stop(); return; }
    if (step >= items().length - 1) go(0);
    timer = setInterval(() => {
      if (step >= items().length - 1) { stop(); return; }
      go(step + 1);
    }, PLAY_INTERVAL_MS);
    updatePlay();
  });
  scenarioButtons.forEach((button, index) => button.addEventListener('click', () => {
    stop();
    scenario = index;
    scenarioButtons.forEach((other) => other.setAttribute('aria-pressed', String(other === button)));
    renderChips();
    go(0);
  }));
  viewButtons.forEach((button) => button.addEventListener('click', () => {
    view = button.dataset.viewButton;
    viewButtons.forEach((other) => other.setAttribute('aria-pressed', String(other === button)));
    go(step);
  }));
  figure.addEventListener('keydown', (event) => {
    if (event.target instanceof HTMLElement && event.target.closest('input, textarea, select')) return;
    if (event.key === 'ArrowRight') { event.preventDefault(); stop(); go(step + 1); }
    else if (event.key === 'ArrowLeft') { event.preventDefault(); stop(); go(step - 1); }
  });
  document.addEventListener('visibilitychange', () => { if (document.hidden) stop(); });
  reducedMotion.addEventListener('change', stop);

  renderChips();
  go(0);
  updatePlay();
}

function enhanceDiagram(figure) {
  const panel = figure.querySelector('[data-detail]');
  if (!panel) return;
  const details = figure.querySelector('.yx-details');
  const hint = panel.innerHTML;
  details.hidden = true;
  panel.hidden = false;
  figure.classList.add('is-ready');
  let selected = null;
  const blocks = [...figure.querySelectorAll('[data-block]')];

  function select(id) {
    selected = selected === id ? null : id;
    for (const block of blocks) {
      const on = block.dataset.block === selected;
      block.classList.toggle('is-selected', on);
      block.setAttribute('aria-pressed', String(on));
    }
    const entry = selected && details.querySelector(`[data-detail-for="${selected}"]`);
    if (!entry) { panel.innerHTML = hint; return; }
    const title = document.createElement('strong');
    title.textContent = entry.querySelector('dt').textContent;
    const text = document.createElement('p');
    text.innerHTML = entry.querySelector('dd').innerHTML;
    panel.replaceChildren(title, text);
  }

  for (const block of blocks) {
    block.setAttribute('aria-pressed', 'false');
    block.addEventListener('click', () => select(block.dataset.block));
    block.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); select(block.dataset.block); }
    });
  }
}

function enhanceChooser(figure) {
  const host = figure.querySelector('[data-chooser]');
  const template = figure.querySelector('template[data-nodes]');
  const tree = figure.querySelector('.yx-tree');
  const nodes = new Map([...template.content.querySelectorAll('[data-node]')].map((node) => [node.dataset.node, node]));
  const start = figure.dataset.start;
  const path = [];
  tree.hidden = true;
  host.hidden = false;
  figure.classList.add('is-ready');

  function render() {
    const id = path.length ? path[path.length - 1].next : start;
    const node = nodes.get(id).cloneNode(true);
    const trail = document.createElement('ol');
    trail.className = 'yx-trail';
    for (const step of path) {
      const item = document.createElement('li');
      item.innerHTML = `<span>${step.question}</span> <strong>${step.answer}</strong>`;
      trail.append(item);
    }
    const nav = document.createElement('div');
    nav.className = 'yx-chooser__nav';
    if (path.length) {
      const back = document.createElement('button');
      back.type = 'button';
      back.className = 'yx-ill__btn';
      back.textContent = '← Back';
      back.addEventListener('click', () => { path.pop(); render(); host.querySelector('button')?.focus(); });
      const restart = document.createElement('button');
      restart.type = 'button';
      restart.className = 'yx-ill__btn';
      restart.textContent = 'Start over';
      restart.addEventListener('click', () => { path.length = 0; render(); host.querySelector('button')?.focus(); });
      nav.append(back, restart);
    }
    for (const option of node.querySelectorAll('[data-next]')) {
      option.addEventListener('click', () => {
        path.push({
          question: node.querySelector('.yx-tree__q').innerHTML,
          answer: option.innerHTML,
          next: option.dataset.next,
        });
        render();
        host.querySelector('.yx-option, .yx-result')?.focus?.();
      });
    }
    const result = node.querySelector('.yx-result');
    if (result) result.tabIndex = -1;
    host.replaceChildren(...(path.length ? [trail] : []), node, nav);
  }

  render();
}

export function enhanceIllustrations(root = document) {
  for (const figure of root.querySelectorAll('[data-yx-illustration]')) {
    if (figure.dataset.enhanced) continue;
    figure.dataset.enhanced = 'true';
    enhancePresent(figure);
    const type = figure.dataset.yxIllustration;
    if (type === 'steps') enhanceSteps(figure);
    else if (type === 'diagram') enhanceDiagram(figure);
    else if (type === 'chooser') enhanceChooser(figure);
  }
}
