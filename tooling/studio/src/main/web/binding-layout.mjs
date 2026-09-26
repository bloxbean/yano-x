/**
 * Graph model and optional layout sidecar `yano-x-binding-layout-v1` (ADR-031.2 §4.3, contract C5).
 *
 * Positions and collapsed panels are presentation state only. They are never written into YAML, IR, profile
 * commitments, locks, URLs or browser storage, and losing them cannot change a workflow. The graph is derived
 * from the draft; it is not an editable program of its own.
 */
import {decodeUtf8Strict,expectArray,expectObject,expectSmallInteger,expectString,isObject,JsonInputError,
  parseJson,stringifyJson} from './lossless-json.mjs';

export const LAYOUT_SCHEMA = 'yano-x-binding-layout-v1';
export const LAYOUT_LIMITS = Object.freeze({maxBytes: 256 * 1024, maxNodes: 1024, maxCoordinate: 1_000_000});
// Any bounded id the draft can hold: invalid ids stay drawable and round-trip through the sidecar.
const NODE_ID = /^(?:component|effect):[^\u0000-\u001f\u007f]{0,256}$/;

/**
 * Derives graph nodes and ordered edges from a draft: one node per component, one sink per effect binding, one
 * edge per binding in authored (execution) order.
 */
export function graphModel(draft) {
  const nodes = draft.components.map((component, index) => ({id: `component:${component.id}`, kind: 'component',
    label: component.id, detail: component.machine, index}));
  const known = new Set(nodes.map(node => node.id));
  const edges = [];
  draft.bindings.forEach((binding, order) => {
    const from = `component:${binding.from.component}`;
    let to;
    if (binding.to.kind === 'effect') {
      to = `effect:${binding.id}`;
      if (!known.has(to)) {
        nodes.push({id: to, kind: 'effect', label: binding.to.type, detail: 'outbox intent', index: nodes.length});
        known.add(to);
      }
    } else to = `component:${binding.to.component}`;
    edges.push({id: binding.id, order, from, to, event: binding.from.event,
      action: binding.to.kind === 'effect' ? `effect ${binding.to.type}` : binding.to.command,
      conditions: (binding.when ?? []).length, dangling: !known.has(from) || !known.has(to)});
  });
  return {nodes, edges};
}

/**
 * Deterministic layered positions: longest-path rank (bounded, so cycles terminate), rows by authored order.
 * Positions affect only drawing.
 */
export function autoLayout(model) {
  const rank = new Map(model.nodes.map(node => [node.id, 0]));
  for (let pass = 0; pass < model.nodes.length; pass++) {
    let changed = false;
    for (const edge of model.edges) {
      if (!rank.has(edge.from) || !rank.has(edge.to) || edge.from === edge.to) continue;
      const next = rank.get(edge.from) + 1;
      if (rank.get(edge.to) < next && next < model.nodes.length) { rank.set(edge.to, next); changed = true; }
    }
    if (!changed) break;
  }
  const rows = new Map();
  const positions = new Map();
  for (const node of model.nodes) {
    const column = rank.get(node.id);
    const row = rows.get(column) ?? 0;
    rows.set(column, row + 1);
    positions.set(node.id, {x: 24 + column * 380, y: 40 + row * 120});
  }
  return positions;
}

/** Validates an imported layout sidecar; unknown node ids are kept but ignored when drawing. */
export function importLayout(input) {
  const text = typeof input === 'string' ? input : decodeUtf8Strict(input);
  if (text.length > LAYOUT_LIMITS.maxBytes) throw new JsonInputError('LAYOUT_TOO_LARGE', 'Layout exceeds 256 KiB');
  const root = parseJson(text, {maxCharacters: LAYOUT_LIMITS.maxBytes, maxDepth: 8});
  if (!isObject(root) || root.schema !== LAYOUT_SCHEMA) throw new JsonInputError('LAYOUT_SCHEMA', 'Not a yano-x-binding-layout-v1 file');
  expectObject(root, '$', ['schema', 'nodes'], ['collapsed']);
  const nodes = new Map();
  if (!isObject(root.nodes)) throw new JsonInputError('CONTRACT_TYPE', '$.nodes must be an object');
  const ids = Object.keys(root.nodes);
  if (ids.length > LAYOUT_LIMITS.maxNodes) throw new JsonInputError('CONTRACT_LIMIT', 'Too many layout nodes');
  for (const id of ids) {
    expectString(id, '$.nodes key', 270, NODE_ID);
    const position = expectObject(root.nodes[id], `$.nodes.${id}`, ['x', 'y']);
    nodes.set(id, {x: expectSmallInteger(position.x, `$.nodes.${id}.x`, -LAYOUT_LIMITS.maxCoordinate, LAYOUT_LIMITS.maxCoordinate),
      y: expectSmallInteger(position.y, `$.nodes.${id}.y`, -LAYOUT_LIMITS.maxCoordinate, LAYOUT_LIMITS.maxCoordinate)});
  }
  const collapsed = new Set(root.collapsed === undefined ? [] : expectArray(root.collapsed, '$.collapsed',
    LAYOUT_LIMITS.maxNodes).map((id, index) => expectString(id, `$.collapsed[${index}]`, 270, NODE_ID)));
  return {nodes, collapsed};
}

/** Serializes presentation state only: node ids (component and binding ids) and positions, nothing else. */
export function exportLayout(layout) {
  const nodes = {};
  for (const [id, position] of [...layout.nodes.entries()].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)) {
    nodes[id] = {x: BigInt(Math.round(position.x)), y: BigInt(Math.round(position.y))};
  }
  return `${stringifyJson({schema: LAYOUT_SCHEMA, nodes, collapsed: [...layout.collapsed].sort()})}\n`;
}
