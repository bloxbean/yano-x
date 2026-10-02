// Build-time renderer for interactive illustrations.
//
// Every illustration renders to complete, readable HTML here: a block diagram
// as inline SVG, or a step-through with its lanes and every step written out.
// The client script (./client.js) only adds controls on top of that markup,
// so a page without JavaScript, a search index, and a screen reader all get
// the full content. See scripts/remark-illustrations.mjs for how a page asks
// for one.

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' };

export function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"]/g, (c) => ESCAPES[c]);
}

/** Escape, then allow `code` and **strong** — the only inline markup in illustration text. */
export function inline(text) {
  return escapeHtml(text)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
}

function plain(text) {
  return String(text ?? '').split(/(`[^`]*`)/).map((part) =>
    part.startsWith('`') ? part.slice(1, -1) : part.replace(/\*\*/g, '')).join('');
}

function header(data, extra = '') {
  return `<div class="yx-ill__bar">`
    + `<span class="yx-ill__title" id="${data.id}-title">${escapeHtml(data.title)}</span>`
    + `<span class="yx-ill__counter" data-counter aria-hidden="true"></span>`
    + `<span class="yx-ill__spacer"></span>${extra}`
    + `<span class="yx-ill__tag">${escapeHtml(data.tag ?? 'Illustrative data')}</span>`
    + `<button type="button" class="yx-ill__btn" data-present hidden>Present</button>`
    + `</div>`
    + (data.intro ? `<p class="yx-ill__intro"><strong>What you're seeing:</strong> ${inline(data.intro)}</p>` : '');
}

function legend(items) {
  if (!items?.length) return '';
  return `<ul class="yx-ill__legend">${items.map(([kind, label]) =>
    `<li><span class="yx-swatch yx-k-${escapeHtml(kind)}" aria-hidden="true"></span>${inline(label)}</li>`).join('')}</ul>`;
}

// ---------------------------------------------------------------------------
// Block diagrams
// ---------------------------------------------------------------------------

const LINE_HEIGHT = 18;

function anchor([x, y, w, h], side) {
  switch (side) {
    case 'l': return [x, y + h / 2];
    case 'r': return [x + w, y + h / 2];
    case 't': return [x + w / 2, y];
    default: return [x + w / 2, y + h];
  }
}

function autoSides(a, b) {
  if (b[0] >= a[0] + a[2] - 1) return ['r', 'l'];
  if (b[0] + b[2] <= a[0] + 1) return ['l', 'r'];
  return b[1] >= a[1] + a[3] - 1 ? ['b', 't'] : ['t', 'b'];
}

/** Orthogonal route from p1 to p2, leaving and entering along each side's normal. */
function route(p1, s1, p2, s2, via) {
  if (via) return [p1, ...via, p2];
  const horizontal = s1 === 'l' || s1 === 'r';
  if (p1[0] === p2[0] || p1[1] === p2[1]) return [p1, p2];
  if (horizontal && (s2 === 'l' || s2 === 'r')) {
    const mx = (p1[0] + p2[0]) / 2;
    return [p1, [mx, p1[1]], [mx, p2[1]], p2];
  }
  if (!horizontal && (s2 === 't' || s2 === 'b')) {
    const my = (p1[1] + p2[1]) / 2;
    return [p1, [p1[0], my], [p2[0], my], p2];
  }
  return horizontal ? [p1, [p2[0], p1[1]], p2] : [p1, [p1[0], p2[1]], p2];
}

/** A polyline with rounded corners. */
function roundedPath(points, radius = 9) {
  let d = `M${points[0][0]},${points[0][1]}`;
  for (let i = 1; i < points.length - 1; i++) {
    const [px, py] = points[i - 1];
    const [cx, cy] = points[i];
    const [nx, ny] = points[i + 1];
    const inLen = Math.hypot(cx - px, cy - py);
    const outLen = Math.hypot(nx - cx, ny - cy);
    const r = Math.min(radius, inLen / 2, outLen / 2);
    const ax = cx - ((cx - px) / inLen) * r;
    const ay = cy - ((cy - py) / inLen) * r;
    const bx = cx + ((nx - cx) / outLen) * r;
    const by = cy + ((ny - cy) / outLen) * r;
    d += ` L${ax},${ay} Q${cx},${cy} ${bx},${by}`;
  }
  const last = points[points.length - 1];
  return `${d} L${last[0]},${last[1]}`;
}

function midpoint(points) {
  // Midpoint by length along the polyline, so labels sit on the visible segment.
  const lengths = points.slice(1).map((p, i) => Math.hypot(p[0] - points[i][0], p[1] - points[i][1]));
  let remaining = lengths.reduce((a, b) => a + b, 0) / 2;
  for (let i = 0; i < lengths.length; i++) {
    if (remaining <= lengths[i]) {
      const t = lengths[i] === 0 ? 0 : remaining / lengths[i];
      return [points[i][0] + (points[i + 1][0] - points[i][0]) * t,
        points[i][1] + (points[i + 1][1] - points[i][1]) * t];
    }
    remaining -= lengths[i];
  }
  return points[0];
}

function textLines(label) {
  return String(label).split('\n');
}

function renderSvg(data, layoutName, layout) {
  const rect = (id) => layout.blocks?.[id] ?? layout.zones?.[id];
  const parts = [];
  for (const zone of data.zones ?? []) {
    const r = layout.zones?.[zone.id];
    if (!r) continue;
    parts.push(`<g class="yx-zone yx-k-${zone.kind ?? 'ledger'}">`
      + `<rect x="${r[0]}" y="${r[1]}" width="${r[2]}" height="${r[3]}" rx="16"/>`
      + `<text class="yx-zone__label" x="${r[0] + 16}" y="${r[1] + 22}">`
      + `${escapeHtml(zone.upper === false ? zone.label : zone.label.toUpperCase())}</text></g>`);
  }
  const edgeParts = [];
  const labelParts = [];
  for (const edge of data.edges ?? []) {
    const a = rect(edge.from);
    const b = rect(edge.to);
    if (!a || !b) continue;
    const opts = layout.edges?.[`${edge.from}->${edge.to}`] ?? {};
    const [autoFrom, autoTo] = autoSides(a, b);
    const s1 = opts.fromSide ?? autoFrom;
    const s2 = opts.toSide ?? autoTo;
    const p1 = opts.fromAt ?? anchor(a, s1);
    const p2 = opts.toAt ?? anchor(b, s2);
    const points = route(p1, s1, p2, s2, opts.via);
    const marker = `url(#${data.id}-${layoutName}-arrow)`;
    edgeParts.push(`<path class="yx-edge${edge.style ? ` yx-edge--${edge.style}` : ''}" d="${roundedPath(points)}" `
      + `marker-end="${marker}"${edge.both ? ` marker-start="${marker}"` : ''}/>`);
    if (edge.label && opts.label !== false) {
      const [mx, my] = opts.labelAt ?? midpoint(points);
      labelParts.push(`<text class="yx-edge__label" x="${mx}" y="${my}" text-anchor="middle" dominant-baseline="central">${escapeHtml(edge.label)}</text>`);
    }
  }
  const blockParts = [];
  for (const block of data.blocks ?? []) {
    const r = layout.blocks?.[block.id];
    if (!r) continue;
    const [x, y, w, h] = r;
    const override = layout.labels?.[block.id] ?? {};
    const lines = textLines(override.label ?? block.label);
    const subText = 'sub' in override ? override.sub : block.sub;
    const sub = subText ? textLines(subText) : [];
    const total = lines.length + sub.length;
    const top = y + h / 2 - ((total - 1) * LINE_HEIGHT) / 2;
    const text = lines.map((line, i) =>
      `<text class="yx-block__label" x="${x + w / 2}" y="${top + i * LINE_HEIGHT}" text-anchor="middle" dominant-baseline="central">${escapeHtml(line)}</text>`)
      .concat(sub.map((line, i) =>
        `<text class="yx-block__sub" x="${x + w / 2}" y="${top + (lines.length + i) * LINE_HEIGHT}" text-anchor="middle" dominant-baseline="central">${escapeHtml(line)}</text>`))
      .join('');
    const explore = block.detail
      ? ` is-explorable" data-block="${block.id}" tabindex="0" role="button" aria-label="${escapeHtml(plain(block.label).replace(/\n/g, ' '))}: details`
      : '';
    blockParts.push(`<g class="yx-block yx-k-${block.kind ?? 'core'}${explore}"><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="10"/>${text}</g>`);
  }
  return `<svg class="yx-diagram__svg yx-diagram__svg--${layoutName}" viewBox="0 0 ${layout.width} ${layout.height}" `
    + `role="${(data.blocks ?? []).some((b) => b.detail) ? 'group' : 'img'}" `
    + `aria-labelledby="${data.id}-title" aria-describedby="${data.id}-desc" preserveAspectRatio="xMidYMid meet">`
    + `<defs><marker id="${data.id}-${layoutName}-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">`
    + `<path class="yx-arrowhead" d="M0,0 L10,5 L0,10 z"/></marker></defs>`
    + parts.join('') + edgeParts.join('') + blockParts.join('') + labelParts.join('')
    + `</svg>`;
}

/** A text rendering of the diagram's connections, for screen readers and text-only readers. */
function diagramDescription(data) {
  const name = (id) => {
    const item = (data.blocks ?? []).find((b) => b.id === id) ?? (data.zones ?? []).find((z) => z.id === id);
    return plain(item ? `${item.label}`.replace(/\n/g, ' ') : id);
  };
  const members = (data.zones ?? []).map((zone) => {
    const inside = (zone.contains ?? []).map(name).join(', ');
    return inside ? `<li>${escapeHtml(zone.label)}: ${escapeHtml(inside)}.</li>` : '';
  }).join('');
  const edges = (data.edges ?? []).map((edge) =>
    `<li>${escapeHtml(name(edge.from))} → ${escapeHtml(name(edge.to))}${edge.label ? ` (${escapeHtml(edge.label)})` : ''}.</li>`).join('');
  return `<ul class="yx-sr-only" id="${data.id}-desc">${members}${edges}</ul>`;
}

function detailLink(link) {
  return link ? ` <a class="yx-ill__link" href="${escapeHtml(link.href)}">${escapeHtml(link.label)} →</a>` : '';
}

/**
 * Blocks with a `detail` are explorable: the client turns the list below into
 * a panel that follows the selected block. Without JavaScript the list itself
 * is the explanation.
 */
function blockDetails(data) {
  const explorable = (data.blocks ?? []).filter((block) => block.detail);
  if (!explorable.length) return '';
  return `<div class="yx-ill__detail" data-detail aria-live="polite" hidden>`
    + `<p class="yx-ill__hint">${inline(data.hint ?? 'Select a block to see what it does.')}</p></div>`
    + `<dl class="yx-details">${explorable.map((block) =>
      `<div data-detail-for="${block.id}"><dt>${escapeHtml(plain(block.label).replace(/\n/g, ' '))}</dt>`
      + `<dd>${inline(block.detail)}${detailLink(block.link)}</dd></div>`).join('')}</dl>`;
}

export function renderDiagram(data) {
  const layouts = Object.entries(data.layouts);
  return `<figure class="yx-ill yx-ill--diagram not-content" id="${data.id}" data-yx-illustration="diagram" aria-labelledby="${data.id}-title">`
    + header(data)
    + `<div class="yx-diagram${layouts.length > 1 ? ' yx-diagram--responsive' : ''}">`
    + layouts.map(([name, layout]) => renderSvg(data, name, layout)).join('')
    + `</div>`
    + diagramDescription(data)
    + blockDetails(data)
    + (data.caption ? `<figcaption class="yx-ill__caption-text">${inline(data.caption)}</figcaption>` : '')
    + legend(data.legend)
    + `</figure>`;
}

// ---------------------------------------------------------------------------
// Step-throughs
// ---------------------------------------------------------------------------

/**
 * For each lane, the card shown at each step is the most recent one defined
 * at or before that step. Returns cards with the step range they cover.
 */
function laneCards(steps, laneId) {
  const out = [];
  let current = null;
  steps.forEach((step, index) => {
    if (!step.cards || !(laneId in step.cards)) return;
    if (current) current.to = index - 1;
    const card = step.cards[laneId];
    current = card ? { ...card, from: index, to: steps.length - 1 } : null;
    if (current) out.push(current);
  });
  return out;
}

function wireRows(scenarios) {
  return Math.max(1, ...scenarios.flatMap((s) => s.steps.map((step) => step.wires?.length ?? 0)));
}

function renderStage(data, scenario, rows) {
  const laneIndex = Object.fromEntries(data.lanes.map((lane, i) => [lane.id, i]));
  const laneName = Object.fromEntries(data.lanes.map((lane) => [lane.id, lane.label]));
  const heads = data.lanes.map((lane, i) =>
    `<div class="yx-lane-head yx-k-${lane.kind ?? 'member'}" style="--col:${i + 1}" data-lane="${lane.id}">`
    + `<span class="yx-lane-head__name">${inline(lane.label)}</span>`
    + (lane.note ? `<small>${inline(lane.note)}</small>` : '')
    + `</div>`).join('');
  const wires = scenario.steps.flatMap((step, s) => (step.wires ?? []).map((wire, row) => {
    const a = laneIndex[wire.from];
    const b = laneIndex[wire.to];
    const start = Math.min(a, b) + 1;
    const end = Math.max(a, b) + 2;
    const direction = b >= a ? 'ltr' : 'rtl';
    return `<div class="yx-wire yx-wire--${direction}${wire.tone ? ` yx-tone-${wire.tone}` : ''}" data-step="${s}" `
      + `style="--from:${start};--to:${end};--row:${row + 2}">`
      + `<span class="yx-wire__label"><span class="yx-wire__route">${escapeHtml(laneName[wire.from])} → ${escapeHtml(laneName[wire.to])}: </span>${inline(wire.label)}</span></div>`;
  })).join('');
  const lanes = data.lanes.map((lane, i) => {
    const last = scenario.steps.length - 1;
    const cards = laneCards(scenario.steps, lane.id).map((card) =>
      `<div class="yx-card${card.tone ? ` yx-tone-${card.tone}` : ''}${card.to === last ? ' is-end' : ''}" `
      + `data-from="${card.from}" data-to="${card.to}">`
      + `<span class="yx-card__title">${inline(card.title)}</span>`
      + (card.detail ? `<span class="yx-card__detail">${inline(card.detail)}</span>` : '')
      + `</div>`).join('');
    return `<div class="yx-lane yx-k-${lane.kind ?? 'member'}" style="--col:${i + 1};--row:${rows + 2}" `
      + `data-lane="${lane.id}">${cards}</div>`;
  }).join('');
  return `<div class="yx-stage" style="--lanes:${data.lanes.length};--wire-rows:${rows}" aria-hidden="true">${heads}${wires}${lanes}</div>`;
}

function focusFor(step) {
  if (step.focus) return step.focus;
  const lanes = new Set(Object.keys(step.cards ?? {}));
  for (const wire of step.wires ?? []) { lanes.add(wire.from); lanes.add(wire.to); }
  return [...lanes];
}

const CHECK_MARK = { true: '✓', false: '✗', warn: '!', null: '–' };

/** Optional per-step extras: a command, rule checks in evaluation order, and a state table. */
function stepExtras(step) {
  const parts = [];
  if (step.command) {
    parts.push(`<pre class="yx-cmd"><code>${escapeHtml(step.command)}</code></pre>`);
  }
  if (step.checks?.length) {
    parts.push(`<ul class="yx-checks">${step.checks.map((check) => {
      const state = check.ok === true ? 'ok' : check.ok === false ? 'fail' : check.ok === 'warn' ? 'warn' : 'skip';
      const spoken = { ok: 'passes', fail: 'fails', warn: 'warning', skip: 'not reached' }[state];
      return `<li class="is-${state}"><span class="yx-checks__mark" aria-hidden="true">${CHECK_MARK[String(check.ok ?? null)]}</span>`
        + `<span class="yx-sr-only">${spoken}: </span>`
        + `<span>${inline(check.label)}</span>${check.code ? ` <code>${escapeHtml(check.code)}</code>` : ''}</li>`;
    }).join('')}</ul>`);
  }
  if (step.state) {
    const { caption, columns, rows, highlight = [] } = step.state;
    parts.push(`<table class="yx-state">${caption ? `<caption>${inline(caption)}</caption>` : ''}`
      + `<thead><tr>${columns.map((c) => `<th scope="col">${inline(c)}</th>`).join('')}</tr></thead>`
      + `<tbody>${rows.length ? rows.map((row, r) =>
        `<tr${highlight.includes(r) ? ' class="is-changed"' : ''}>${row.map((cell) => `<td>${inline(cell)}</td>`).join('')}</tr>`).join('')
        : `<tr><td colspan="${columns.length}" class="yx-state__empty">empty</td></tr>`}</tbody></table>`);
  }
  return parts.length ? `<div class="yx-step-extra">${parts.join('')}</div>` : '';
}

function renderStepList(data, scenario) {
  return `<ol class="yx-steps">${scenario.steps.map((step, i) => {
    const views = (data.views ?? []).slice(1).map((view) => step.viewText?.[view.id]
      ? `<span class="yx-view-text" data-view="${view.id}" hidden>${inline(step.viewText[view.id])}</span>` : '').join('');
    return `<li data-step="${i}" data-focus="${focusFor(step).join(' ')}">`
      + `<strong class="yx-steps__title">${inline(step.title)}</strong> `
      + `<span class="yx-view-text" data-view="default">${inline(step.text)}</span>${views}${stepExtras(step)}</li>`;
  }).join('')}</ol>`;
}

export function renderSteps(data) {
  const scenarios = data.scenarios;
  const rows = wireRows(scenarios);
  const scenarioPicker = scenarios.length > 1
    ? `<div class="yx-ill__group" role="group" aria-label="Scenario" data-scenario-picker hidden>${scenarios.map((scenario, i) =>
      `<button type="button" class="yx-pill" data-scenario-button="${scenario.id}" aria-pressed="${i === 0}">${inline(scenario.label)}</button>`).join('')}</div>`
    : '';
  const viewPicker = (data.views?.length ?? 0) > 1
    ? `<div class="yx-ill__group yx-ill__group--views" role="group" aria-label="Point of view" data-view-picker hidden>${data.views.map((view, i) =>
      `<button type="button" class="yx-pill" data-view-button="${view.id}" data-view-focus="${(view.focus ?? []).join(' ')}" aria-pressed="${i === 0}">${inline(view.label)}</button>`).join('')}</div>`
    : '';
  const body = scenarios.map((scenario, i) =>
    `<section class="yx-scn" data-scenario="${scenario.id}"${i === 0 ? '' : ' data-alternate'}>`
    + (i === 0 ? '' : `<h4 class="yx-scn__heading">What if: ${inline(scenario.label)}</h4>`)
    + (scenario.summary ? `<p class="yx-scn__summary">${inline(scenario.summary)}</p>` : '')
    + (data.lanes?.length ? renderStage(data, scenario, rows) : '')
    + renderStepList(data, scenario)
    + `</section>`).join('');
  return `<figure class="yx-ill yx-ill--steps not-content" id="${data.id}" data-yx-illustration="steps" aria-labelledby="${data.id}-title">`
    + header(data)
    + (scenarioPicker || viewPicker ? `<div class="yx-ill__pickers">${scenarioPicker}${viewPicker}</div>` : '')
    + body
    + `<div class="yx-ill__controls" data-controls hidden>`
    + `<div class="yx-chips" role="group" aria-label="Steps" data-chips></div>`
    + `<div class="yx-ill__nav">`
    + `<button type="button" class="yx-ill__btn" data-prev aria-label="Previous step">←</button>`
    + `<button type="button" class="yx-ill__btn yx-ill__btn--primary" data-play>Play</button>`
    + `<button type="button" class="yx-ill__btn" data-next aria-label="Next step">→</button>`
    + `</div></div>`
    + `<div class="yx-ill__caption" data-caption aria-live="polite" hidden></div>`
    + legend(data.legend)
    + `</figure>`;
}

// ---------------------------------------------------------------------------
// Choosers (decision trees)
// ---------------------------------------------------------------------------

function renderResult(node) {
  const result = node.result;
  return `<div class="yx-result">`
    + `<strong class="yx-result__title">${inline(result.title)}</strong>`
    + (result.text ? `<p>${inline(result.text)}</p>` : '')
    + (result.facts?.length ? `<dl class="yx-result__facts">${result.facts.map(([k, v]) =>
      `<div><dt>${inline(k)}</dt><dd>${inline(v)}</dd></div>`).join('')}</dl>` : '')
    + (result.links?.length ? `<p class="yx-result__links">${result.links.map((link) =>
      `<a class="yx-ill__link" href="${escapeHtml(link.href)}">${escapeHtml(link.label)} →</a>`).join(' ')}</p>` : '')
    + `</div>`;
}

/** The whole tree as a nested outline: the no-JavaScript view and the client's data. */
function renderChooserNode(data, id, seen) {
  const node = data.nodes[id];
  if (node.result) return `<div class="yx-tree__node" data-node="${id}">${renderResult(node)}</div>`;
  if (seen.has(id)) return `<div class="yx-tree__node" data-node-ref="${id}">See “${inline(node.question)}” above.</div>`;
  const next = new Set(seen).add(id);
  return `<div class="yx-tree__node" data-node="${id}"><p class="yx-tree__q">${inline(node.question)}</p>`
    + (node.help ? `<p class="yx-tree__help">${inline(node.help)}</p>` : '')
    + `<ul>${node.options.map((option) =>
      `<li data-next="${option.next}"><span class="yx-tree__answer">${inline(option.label)}</span>${renderChooserNode(data, option.next, next)}</li>`).join('')}</ul></div>`;
}

export function renderChooser(data) {
  return `<figure class="yx-ill yx-ill--chooser not-content" id="${data.id}" data-yx-illustration="chooser" `
    + `data-start="${data.start}" aria-labelledby="${data.id}-title">`
    + header(data)
    + `<div class="yx-chooser" data-chooser hidden aria-live="polite"></div>`
    + `<div class="yx-tree">${renderChooserNode(data, data.start, new Set())}</div>`
    + `<template data-nodes>${Object.entries(data.nodes).map(([id, node]) => node.result
      ? `<div data-node="${id}" data-kind="result">${renderResult(node)}</div>`
      : `<div data-node="${id}" data-kind="question"><p class="yx-tree__q">${inline(node.question)}</p>`
        + (node.help ? `<p class="yx-tree__help">${inline(node.help)}</p>` : '')
        + node.options.map((option) => `<button type="button" class="yx-option" data-next="${option.next}">${inline(option.label)}</button>`).join('')
        + `</div>`).join('')}</template>`
    + (data.caption ? `<figcaption class="yx-ill__caption-text">${inline(data.caption)}</figcaption>` : '')
    + `</figure>`;
}

export function renderIllustration(data) {
  switch (data.type) {
    case 'diagram': return renderDiagram(data);
    case 'steps': return renderSteps(data);
    case 'chooser': return renderChooser(data);
    default: throw new Error(`Illustration ${data.id}: unknown type "${data.type}"`);
  }
}

/** Plain-text summary used where markup cannot go (llms.txt, validation messages). */
export function illustrationText(data) {
  if (data.type === 'diagram') {
    return [data.title, data.caption, ...(data.edges ?? []).map((e) => `${e.from} → ${e.to}${e.label ? ` (${e.label})` : ''}`),
      ...(data.blocks ?? []).filter((b) => b.detail).map((b) => `${b.label.replace(/\n/g, ' ')}: ${b.detail}`)]
      .filter(Boolean).map(plain).join('\n');
  }
  if (data.type === 'chooser') {
    return [data.title, ...Object.values(data.nodes).map((node) => node.result
      ? `Result: ${plain(node.result.title)} — ${plain(node.result.text ?? '')}`
      : `${plain(node.question)} ${node.options.map((o) => `[${plain(o.label)}]`).join(' ')}`)].join('\n');
  }
  return [data.title, ...data.scenarios.flatMap((s, si) => [
    si === 0 ? '' : `What if: ${s.label}`,
    ...s.steps.map((step, i) => `${i + 1}. ${plain(step.title)} — ${plain(step.text)}`),
  ])].filter(Boolean).join('\n');
}
