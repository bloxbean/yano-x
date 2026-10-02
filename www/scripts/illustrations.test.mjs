// Illustration integrity: structure, layout fit, page markers, and source anchors.
//
// Every illustration names the repository files whose facts it shows, plus an
// exact phrase or identifier ("anchor") that must still appear there. When the
// code or guide changes and the anchor disappears, this test fails, so the
// picture cannot silently drift from the implementation. Anchors in the
// upstream Yano repository are checked when a checkout is available (the
// sibling directory or YANO_SOURCE_DIR) and skipped otherwise.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { ILLUSTRATIONS } from '../src/illustrations/registry.mjs';
import { renderIllustration, illustrationText } from '../src/illustrations/render.mjs';
import { CONTENT_ROOT, IMPORTED_DOCS, REPO_ROOT } from './repo-sources.mjs';

const YANO_ROOT = process.env.YANO_SOURCE_DIR ?? path.resolve(REPO_ROOT, '../yano');
const roots = { 'yano-x': REPO_ROOT, yano: YANO_ROOT };
const all = Object.values(ILLUSTRATIONS);
const normalize = (text) => text.replace(/\s+/g, ' ');

// Approximate rendered widths, in SVG user units, for the CSS font sizes.
const LABEL_EM = 14 * 0.6;
const SUB_EM = 12 * 0.56;

test('registry ids match their data', () => {
  for (const [id, data] of Object.entries(ILLUSTRATIONS)) {
    assert.equal(data.id, id);
    assert.match(id, /^[a-z0-9-]+$/);
    assert.ok(data.title, `${id}: title`);
    assert.ok(Array.isArray(data.sources) && data.sources.length > 0, `${id}: needs sources`);
  }
});

test('step-throughs reference only declared lanes and views', () => {
  for (const data of all.filter((d) => d.type === 'steps')) {
    const lanes = new Set(data.lanes.map((lane) => lane.id));
    assert.equal(lanes.size, data.lanes.length, `${data.id}: duplicate lane`);
    const views = new Set((data.views ?? []).map((view) => view.id));
    for (const view of data.views ?? []) {
      for (const lane of view.focus ?? []) assert.ok(lanes.has(lane), `${data.id}: view ${view.id} focus ${lane}`);
    }
    const scenarioIds = new Set(data.scenarios.map((s) => s.id));
    assert.equal(scenarioIds.size, data.scenarios.length, `${data.id}: duplicate scenario`);
    for (const scenario of data.scenarios) {
      assert.ok(scenario.steps.length > 1, `${data.id}/${scenario.id}: needs steps`);
      scenario.steps.forEach((step, i) => {
        const where = `${data.id}/${scenario.id} step ${i + 1}`;
        assert.ok(step.title && step.text, `${where}: title and text`);
        for (const lane of Object.keys(step.cards ?? {})) assert.ok(lanes.has(lane), `${where}: card lane ${lane}`);
        for (const wire of step.wires ?? []) {
          assert.ok(lanes.has(wire.from) && lanes.has(wire.to), `${where}: wire ${wire.from}->${wire.to}`);
          assert.notEqual(wire.from, wire.to, `${where}: wire to itself`);
        }
        for (const lane of step.focus ?? []) assert.ok(lanes.has(lane), `${where}: focus ${lane}`);
        for (const view of Object.keys(step.viewText ?? {})) assert.ok(views.has(view), `${where}: view ${view}`);
      });
    }
  }
});

test('block diagrams lay out every block, and labels fit their boxes', () => {
  for (const data of all.filter((d) => d.type === 'diagram')) {
    const ids = new Set([...(data.blocks ?? []).map((b) => b.id), ...(data.zones ?? []).map((z) => z.id)]);
    for (const edge of data.edges ?? []) {
      assert.ok(ids.has(edge.from) && ids.has(edge.to), `${data.id}: edge ${edge.from}->${edge.to}`);
    }
    for (const zone of data.zones ?? []) {
      for (const id of zone.contains ?? []) assert.ok(ids.has(id), `${data.id}: zone ${zone.id} contains ${id}`);
    }
    for (const [name, layout] of Object.entries(data.layouts)) {
      for (const block of data.blocks) {
        const rect = layout.blocks?.[block.id];
        assert.ok(rect, `${data.id}/${name}: no position for ${block.id}`);
        const [x, y, w, h] = rect;
        assert.ok(x >= 0 && y >= 0 && x + w <= layout.width && y + h <= layout.height,
          `${data.id}/${name}: ${block.id} outside the canvas`);
        const override = layout.labels?.[block.id] ?? {};
        const label = String(override.label ?? block.label).split('\n');
        const sub = String(('sub' in override ? override.sub : block.sub) ?? '').split('\n').filter(Boolean);
        for (const line of label) {
          assert.ok(line.length * LABEL_EM <= w - 12, `${data.id}/${name}: "${line}" overflows ${block.id}`);
        }
        for (const line of sub) {
          assert.ok(line.length * SUB_EM <= w - 12, `${data.id}/${name}: "${line}" overflows ${block.id}`);
        }
        assert.ok((label.length + sub.length) * 18 <= h + 4, `${data.id}/${name}: ${block.id} is too short`);
      }
      for (const zone of data.zones ?? []) assert.ok(layout.zones?.[zone.id], `${data.id}/${name}: zone ${zone.id}`);
    }
  }
});

test('every illustration renders', () => {
  for (const data of all) {
    const html = renderIllustration(data);
    assert.doesNotMatch(html, /undefined|NaN|\[object Object\]/, `${data.id}: rendering leaked a placeholder`);
    assert.ok(illustrationText(data).length > 0);
  }
});

function pageSources() {
  const imported = Object.keys(IMPORTED_DOCS).map((rel) => path.join(REPO_ROOT, rel));
  const authored = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (/\.mdx?$/.test(entry.name)) authored.push(full);
    }
  };
  walk(CONTENT_ROOT);
  return [...new Set([...imported, ...authored])].filter((file) => fs.existsSync(file));
}

test('page markers name known illustrations, and fallbacks match their steps', () => {
  const OPEN = /<!--\s*illustration:\s*([a-z0-9-]+)\s*-->/g;
  for (const file of pageSources()) {
    const text = fs.readFileSync(file, 'utf8');
    for (const match of text.matchAll(OPEN)) {
      const id = match[1];
      const data = ILLUSTRATIONS[id];
      assert.ok(data, `${path.relative(REPO_ROOT, file)}: unknown illustration ${id}`);
      const rest = text.slice(match.index + match[0].length);
      const close = rest.search(/<!--\s*\/illustration\s*-->/);
      const nextOpen = rest.search(/<!--\s*illustration:/);
      if (close < 0 || (nextOpen >= 0 && nextOpen < close)) continue;
      const fallback = rest.slice(0, close);
      if (data.type !== 'steps') continue;
      const titles = [...fallback.matchAll(/^\d+\.\s+\*\*(.+?)\.\*\*/gm)].map((m) => m[1]);
      assert.deepEqual(titles, data.scenarios[0].steps.map((step) => step.title),
        `${path.relative(REPO_ROOT, file)}: fallback list for ${id} must match its steps`);
    }
  }
});

test('source anchors still exist in the cited files', (t) => {
  for (const data of all) {
    for (const source of data.sources) {
      const root = roots[source.repo];
      assert.ok(root, `${data.id}: unknown repo ${source.repo}`);
      const file = path.join(root, source.path);
      if (source.repo !== 'yano-x' && !fs.existsSync(root)) {
        t.diagnostic(`skipped ${source.repo}:${source.path} (no checkout at ${root})`);
        continue;
      }
      assert.ok(fs.existsSync(file), `${data.id}: missing ${source.repo}:${source.path}`);
      const text = normalize(fs.readFileSync(file, 'utf8'));
      for (const anchor of source.anchors) {
        assert.ok(text.includes(normalize(anchor)),
          `${data.id}: "${anchor}" no longer appears in ${source.repo}:${source.path}`);
      }
    }
  }
});
