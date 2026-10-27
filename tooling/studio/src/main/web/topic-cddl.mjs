/** Browser authoring compiler for cddl-yano-subset-v1. Never used by consensus.
 * The Java compiler is the differential oracle; both produce core schema IR v1.
 */
import {unhex} from './assets/topics/cbor.mjs';
import {encodeCanonicalValue} from './assets/topics/canonical-value.mjs';
const encodeCbor = value => encodeCanonicalValue(value, {maximumBytes: 65536});
const utf8 = new TextEncoder();
const fail = message => { throw new Error(`CDDL: ${message}`); };
const MIN = -(1n << 64n), MAX = (1n << 64n) - 1n;
const identifier = s => /^[A-Za-z_][A-Za-z0-9_-]{0,63}$/.test(s) ? s : fail('invalid identifier');
const cmp = (a, b) => {
  for (let i = 0; i < Math.min(a.length, b.length); i++) if (a[i] !== b[i]) return a[i] - b[i];
  return a.length - b.length;
};

export function compileCddl(source, root = 'root') {
  identifier(root);
  if (typeof source !== 'string' || !source.trim() || !source.isWellFormed()
      || source.includes('\0') || utf8.encode(source).length > 65536) fail('invalid source size or text');
  const tokens = [];
  // Match Java Character.isWhitespace: NBSP, figure space, narrow NBSP and BOM are not separators.
  const whitespace = /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/;
  const pattern = /[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|;[^\n]*|h'[0-9a-fA-F]*'|"(?:[^"\\\x00-\x1f]|\\(?:["\\nrt]|u[0-9a-fA-F]{4}))*"|\.\.|\.[A-Za-z]+|[A-Za-z_][A-Za-z0-9_-]*|-?[0-9]+|[={}\[\]():,/?*]/y;
  let pos = 0;
  while (pos < source.length) {
    pattern.lastIndex = pos;
    const match = pattern.exec(source);
    if (!match) fail(`unsupported syntax at character ${pos}`);
    pos = pattern.lastIndex;
    const t = match[0];
    if (whitespace.test(t) || t.startsWith(';')) continue;
    tokens.push(t);
    if (tokens.length > 16384) fail('token limit');
  }
  let offset = 0, nesting = 0;
  const peek = () => tokens[offset];
  const take = t => {
    if (peek() !== t) fail(`expected ${t} at token ${offset}`);
    offset++;
  };
  const accept = t => peek() === t && (++offset, true);
  const number = () => {
    const t = tokens[offset++];
    if (!/^-?(?:0|[1-9][0-9]*)$/.test(t ?? '') || t === '-0') fail('noncanonical integer');
    return BigInt(t);
  };
  const occurrence = value => {
    if (value < 0n || value > 65536n) fail('occurrence limit');
    return Number(value);
  };
  function primary() {
    if (++nesting > 64) fail('source nesting limit');
    try {
      if (accept('(')) { const e = type(); take(')'); return e; }
      if (accept('{')) {
        const fields = [], seen = new Set();
        if (!accept('}')) while (true) {
          const required = !accept('?'), token = tokens[offset++];
          const key = token?.startsWith('"') ? JSON.parse(token) : identifier(token ?? '');
          if (seen.has(key) || !key.isWellFormed() || utf8.encode(key).length > 256) fail('duplicate or invalid map key');
          seen.add(key); take(':'); fields.push([key, required ? 1 : 0, type()]);
          if (fields.length > 256) fail('map field limit');
          if (accept('}')) break;
          take(','); if (accept('}')) break;
        }
        return {map: fields};
      }
      if (accept('[')) {
        const terms = [];
        if (!accept(']')) while (true) {
          let min = 1, max = 1;
          if (accept('?')) min = 0;
          else if (tokens[offset + 1] === '*') { min = occurrence(number()); take('*'); max = occurrence(number()); }
          if (min > max) fail('occurrence range');
          terms.push([min, max, type()]);
          if (terms.length > 256) fail('array term limit');
          if (accept(']')) break;
          take(','); if (accept(']')) break;
        }
        return {array: terms};
      }
      if (/^-?[0-9]/.test(peek() ?? '')) {
        const n = number();
        if (accept('..')) {
          const end = number();
          // Java ranges are controls on native INT, so outlying endpoints are intersected with its domain.
          return {node: [0, 2, n < MIN ? MIN : n, end > MAX ? MAX : end]};
        }
        return {node: [0, n < 0n ? 1 : 0, n, n]};
      }
      const token = tokens[offset++];
      if (token?.startsWith('"')) {
        const value = JSON.parse(token), size = utf8.encode(value).length;
        if (!value.isWellFormed()) fail('invalid Unicode scalar');
        return {node: [1, size, size, 1, value]};
      }
      if (token?.startsWith("h'")) {
        const value = unhex(token.slice(2, -1).toLowerCase());
        return {node: [2, value.length, value.length, 1, value]};
      }
      const primitives = {
        uint: [0, 0, 0n, MAX], nint: [0, 1, MIN, -1n], int: [0, 2, MIN, MAX],
        tstr: [1, 0, 1048576, 0, ''], bstr: [2, 0, 1048576, 0, new Uint8Array()],
        bool: [3, 0], false: [3, 1], true: [3, 2], null: [4]
      };
      return Object.hasOwn(primitives, token) ? {node: primitives[token]} : {ref: identifier(token ?? '')};
    } finally { nesting--; }
  }
  function controlled() {
    const e = primary(), controls = [];
    while (peek()?.startsWith('.') && peek() !== '..') {
      const op = tokens[offset++].slice(1);
      if (!['size', 'ge', 'gt', 'le', 'lt', 'eq'].includes(op)) fail('unsupported control');
      let min, max;
      if (op === 'size' && accept('(')) { min = number(); take('..'); max = number(); take(')'); }
      else min = max = number();
      controls.push([op, min, max]);
    }
    return controls.length ? {base: e, controls} : e;
  }
  function type() {
    const options = [controlled()];
    while (accept('/')) options.push(controlled());
    return options.length === 1 ? options[0] : {choice: options};
  }
  const rules = new Map();
  while (offset < tokens.length) {
    const name = identifier(tokens[offset++]); take('=');
    if (rules.has(name)) fail('duplicate rule');
    rules.set(name, type()); if (rules.size > 128) fail('rule limit');
  }
  const active = new Set(), resolved = new Map();
  let steps = 0, expansionNesting = 0;
  function reference(name) {
    if (resolved.has(name)) return resolved.get(name);
    if (active.has(name) || !rules.has(name)) fail('recursive or unknown reference');
    active.add(name); const node = resolve(rules.get(name)); active.delete(name);
    resolved.set(name, node); return node;
  }
  function resolve(e) {
    if (++steps > 8192) fail('expansion limit');
    if (e.ref) return reference(e.ref);
    // Source-local depth is insufficient when shallow named rules expand into one deep tree.
    // Aliases do not count: a full 128-rule alias chain still resolves to its single leaf.
    if (++expansionNesting > 64) fail('expansion nesting limit');
    try {
      if (e.node) return [...e.node];
      if (e.map) return [6, e.map.map(([k, req, v]) => [k, req, resolve(v)])];
      if (e.array) return [7, e.array.map(([min, max, v]) => [min, max, resolve(v)])];
      if (e.choice) {
        const options = e.choice.flatMap(x => { const n = resolve(x); return n[0] === 5 ? n[1] : [n]; });
        if (options.length < 2 || options.length > 32) fail('choice limit');
        return [5, options];
      }
      // Named nodes are cached. Controls create a new node, as the Java compiler does.
      const n = [...resolve(e.base)];
      for (const [op, min, max] of e.controls) {
        if (op === 'size') {
          if (![1, 2].includes(n[0]) || min < 0n || max > 2147483647n) fail('invalid size control');
          n[1] = Math.max(n[1], Number(min)); n[2] = Math.min(n[2], Number(max));
        } else {
          if (n[0] !== 0) fail('numeric control on noninteger');
          if (['ge', 'gt', 'eq'].includes(op)) { const v = min + (op === 'gt' ? 1n : 0n); if (v > n[2]) n[2] = v; }
          if (['le', 'lt', 'eq'].includes(op)) { const v = max - (op === 'lt' ? 1n : 0n); if (v < n[3]) n[3] = v; }
        }
      }
      return n;
    } finally { expansionNesting--; }
  }
  let nodes = 0;
  function canonical(n, depth = 1) {
    if (++nodes > 2048 || depth > 32) fail('schema structural limit');
    if (n[0] === 0) {
      if (n[2] > n[3] || n[2] < MIN || n[3] > MAX || n[1] === 0 && n[2] < 0n
          || n[1] === 1 && n[3] >= 0n) fail('integer bounds');
      return [0, n[1], n[2].toString(), n[3].toString()];
    }
    if (n[0] === 1 || n[0] === 2) {
      const size = n[0] === 1 ? utf8.encode(n[4]).length : n[4].length;
      if (n[1] < 0 || n[1] > n[2] || n[2] > 1048576 || n[3] && (size < n[1] || size > n[2])) fail('size bounds');
      return n;
    }
    if (n[0] === 5) {
      const options = n[1].map(v => canonical(v, depth + 1)).map(v => [v, encodeCbor(v)])
        .sort((a, b) => cmp(a[1], b[1]));
      for (let i = 1; i < options.length; i++) if (cmp(options[i - 1][1], options[i][1]) === 0) fail('duplicate choice');
      return [5, options.map(v => v[0])];
    }
    if (n[0] === 6) return [6, n[1].map(([key, required, v]) => [key, required, canonical(v, depth + 1)])
      .sort((a, b) => cmp(encodeCbor(a[0]), encodeCbor(b[0])))];
    if (n[0] === 7) return [7, n[1].map(([min, max, v]) => [min, max, canonical(v, depth + 1)])];
    return n;
  }
  const definition = encodeCbor([1, canonical(reference(root))]);
  if (definition.length > 65536) fail('IR byte limit');
  return definition;
}
