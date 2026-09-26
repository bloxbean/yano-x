/**
 * Studio YAML profile `yano-x-studio-yaml-v1` (ADR-031.2 contract C4).
 *
 * The authoritative compiler reads YAML with Jackson/SnakeYAML, which resolves plain scalars with YAML 1.1
 * rules: `017` is octal 15, `0x1F` is 31, `yes`/`on`/`True` are booleans and `~` is null. Guessing those rules in
 * a browser would silently change values. This parser therefore accepts only an unambiguous subset: block and
 * flow collections, single-line scalars, comments and one optional leading `---`. A plain scalar is read only
 * as a canonical decimal int64, exactly `true`/`false`, or ASCII text on a positive whitelist that no YAML 1.1
 * resolver can reinterpret. Everything else is refused with a precise location and must be quoted.
 *
 * Anchors, aliases, tags, directives, complex keys, block scalars, multi-line scalars, tabs, raw control, C1 and
 * Unicode line-separator characters, duplicate keys and multiple documents are refused. The emitter only writes
 * documents inside this profile, and a differential test pins the profile against the Java parser.
 * Nodes record source offsets so a blueprint value can be replaced without rewriting unrelated text.
 */

export const YAML_PROFILE = 'yano-x-studio-yaml-v1';

export const YAML_DEFAULT_LIMITS = Object.freeze({
  maxCharacters: 262_144,
  maxTokens: 32_768,
  maxDepth: 48,
  // The compiler accepts `{bytesHex: ...}` literals of up to 131,072 hexadecimal characters.
  maxScalarCharacters: 131_072
});

const INT64_MIN = -(2n ** 63n);
const INT64_MAX = 2n ** 63n - 1n;
const DECIMAL = /^-?(?:0|[1-9][0-9]*)$/;
const RESERVED_WORDS = new Set(['true', 'false', 'yes', 'no', 'on', 'off', 'y', 'n', 'null', '~']);
// Positive whitelists: unquoted keys and text are accepted only when no YAML 1.1 resolver can reinterpret them.
const PLAIN_KEY = /^[A-Za-z_][A-Za-z0-9_.-]*$/;
const PLAIN_TEXT = /^[A-Za-z_][A-Za-z0-9_./-]*$/;
// SnakeYAML limits implicit keys to 1,024 source characters including quotes; stay well inside it.
const MAX_KEY_CHARACTERS = 256;
// SnakeYAML drops an implicit key whose source span exceeds 1024 characters; escapes make source longer than text.
const MAX_KEY_SOURCE_CHARACTERS = 512;

/** Typed parse failure; `code` is stable, positions are one-based, messages never echo document values. */
export class YamlInputError extends Error {
  constructor(code, message, position = {}) {
    super(position.line ? `${message} (line ${position.line}, column ${position.column})` : message);
    this.name = 'YamlInputError';
    this.code = code;
    this.line = position.line;
    this.column = position.column;
    this.offset = position.offset;
  }
}

/**
 * Parses one document of the Studio YAML profile.
 *
 * @param {string} text decoded document text (a leading byte-order mark must already be removed)
 * @param {object} [options] limits overriding {@link YAML_DEFAULT_LIMITS}
 * @returns {{root: object, lineEnding: string}} root node with source spans and the detected line ending
 */
export function parseYaml(text, options = {}) {
  const limits = {...YAML_DEFAULT_LIMITS, ...options};
  if (typeof text !== 'string') throw new YamlInputError('YAML_INPUT', 'YAML input must be text');
  if (text.length > limits.maxCharacters) throw new YamlInputError('YAML_TOO_LARGE', 'YAML input exceeds its size limit');
  const parser = new Parser(text, limits);
  return parser.document();
}

class Parser {
  constructor(text, limits) {
    this.text = text;
    this.limits = limits;
    this.tokens = 0;
    this.lines = [];
    let crlf = 0; let lf = 0;
    let start = 0;
    for (let i = 0; i < text.length; i++) {
      if (text.charCodeAt(i) !== 0x0a) continue;
      const end = i > start && text.charCodeAt(i - 1) === 0x0d ? i - 1 : i;
      if (end !== i) crlf++; else lf++;
      this.lines.push({start, end});
      start = i + 1;
    }
    if (start < text.length) this.lines.push({start, end: text.length});
    // Characters are checked after the line table exists so positions name the offending line.
    for (let i = 0; i < text.length; i++) {
      const c = text.charCodeAt(i);
      if (c !== 0x0a) this.checkCharacter(c, i);
    }
    this.lineEnding = crlf > lf ? '\r\n' : '\n';
    for (const line of this.lines) {
      let indent = 0;
      while (line.start + indent < line.end && text.charCodeAt(line.start + indent) === 0x20) indent++;
      line.indent = indent;
      const first = text[line.start + indent];
      line.blank = line.start + indent >= line.end || first === '#';
    }
  }

  checkCharacter(c, offset) {
    const bad = c === 0x09 ? 'Tab characters are not supported; use spaces'
      : c === 0x0d ? (this.text.charCodeAt(offset + 1) === 0x0a ? null : 'A carriage return must be followed by a line feed')
        : c < 0x20 || c === 0x7f ? 'Control characters are not supported'
          : c >= 0x80 && c <= 0x9f ? 'C1 control characters (including U+0085) are not supported'
            : c === 0x2028 || c === 0x2029 ? 'Unicode line and paragraph separators are not supported'
              : c === 0xfeff ? 'A byte-order mark is only allowed at the start of the file'
                : c === 0xfffe || c === 0xffff ? 'Unicode noncharacters U+FFFE and U+FFFF are not supported'
                  : null;
    if (bad) throw this.error('YAML_UNSUPPORTED_CHARACTER', bad, offset);
    if (c >= 0xd800 && c <= 0xdfff) {
      const next = this.text.charCodeAt(offset + 1);
      const previous = this.text.charCodeAt(offset - 1);
      const pairedHigh = c <= 0xdbff && next >= 0xdc00 && next <= 0xdfff;
      const pairedLow = c >= 0xdc00 && previous >= 0xd800 && previous <= 0xdbff;
      if (!pairedHigh && !pairedLow) throw this.error('YAML_UNSUPPORTED_CHARACTER', 'Invalid Unicode text', offset);
    }
  }

  position(offset) {
    let low = 0; let high = this.lines.length - 1; let found = 0;
    while (low <= high) {
      const middle = (low + high) >> 1;
      if (this.lines[middle].start <= offset) { found = middle; low = middle + 1; } else high = middle - 1;
    }
    const line = this.lines[found] || {start: 0};
    return {line: found + 1, column: offset - line.start + 1, offset};
  }

  error(code, message, offset) { return new YamlInputError(code, message, this.position(offset)); }

  /** Counts parser tokens like the Java pre-scan: one per key or scalar, two per collection (start and end). */
  token(count = 1) {
    this.tokens += count;
    if (this.tokens > this.limits.maxTokens) throw new YamlInputError('YAML_TOKEN_LIMIT', 'YAML document token limit exceeded');
  }

  collectionDepth(depth, offset) {
    if (depth > this.limits.maxDepth) throw this.error('YAML_TOO_DEEP', 'YAML nesting exceeds its limit', offset);
    this.token(2);
  }

  nextContent(index) {
    while (index < this.lines.length && this.lines[index].blank) index++;
    return index < this.lines.length ? index : -1;
  }

  charAt(line, column) {
    const offset = this.lines[line].start + column;
    return offset < this.lines[line].end ? this.text[offset] : '';
  }

  document() {
    let first = this.nextContent(0);
    if (first < 0) throw new YamlInputError('YAML_EMPTY', 'The YAML document is empty');
    for (const line of this.lines) {
      const content = this.text.slice(line.start, line.end);
      if (content.startsWith('%')) throw this.error('YAML_UNSUPPORTED_CONSTRUCT', 'YAML directives are not supported', line.start);
      if (content.startsWith('...') && (content.length === 3 || content[3] === ' ')) {
        throw this.error('YAML_UNSUPPORTED_CONSTRUCT', 'YAML document end markers are not supported', line.start);
      }
    }
    const isMarker = index => {
      const content = this.text.slice(this.lines[index].start, this.lines[index].end);
      return content.startsWith('---') && (content.length === 3 || content[3] === ' ');
    };
    if (isMarker(first)) {
      const rest = this.text.slice(this.lines[first].start + 3, this.lines[first].end).trim();
      if (rest && !rest.startsWith('#')) {
        throw this.error('YAML_UNSUPPORTED_CONSTRUCT', 'Content on the document start line is not supported', this.lines[first].start);
      }
      first = this.nextContent(first + 1);
      if (first < 0) throw new YamlInputError('YAML_EMPTY', 'The YAML document is empty');
    }
    for (let index = first; index < this.lines.length; index++) {
      if (!this.lines[index].blank && this.lines[index].indent === 0 && isMarker(index)) {
        throw this.error('YAML_MULTIPLE_DOCUMENTS', 'Only one YAML document is allowed', this.lines[index].start);
      }
    }
    const result = this.blockNode(first, this.lines[first].indent, -1, 1);
    const rest = this.nextContent(result.next);
    if (rest >= 0) throw this.error('YAML_SYNTAX', 'Unexpected content after the document root', this.lines[rest].start + this.lines[rest].indent);
    return {root: result.node, lineEnding: this.lineEnding};
  }

  /** Parses the node beginning at a non-space character; returns the node and the next unconsumed line index. */
  blockNode(line, column, parentIndent, depth) {
    const c = this.charAt(line, column);
    const after = this.charAt(line, column + 1);
    if (c === '-' && (after === '' || after === ' ')) return this.blockSequence(line, column, depth);
    if (this.mappingKeyAt(line, column)) return this.blockMapping(line, column, depth);
    const inline = this.inlineValue(line, column, parentIndent, depth);
    return inline;
  }

  mappingKeyAt(line, column) {
    const c = this.charAt(line, column);
    if (c === '"' || c === "'") {
      try {
        const scalar = this.quoted(line, column);
        const offset = this.lines[line].start + scalar.endColumn;
        return this.text[offset] === ':' && (offset + 1 >= this.lines[line].end || this.text[offset + 1] === ' ');
      } catch { return false; }
    }
    const lineEnd = this.lines[line].end;
    for (let offset = this.lines[line].start + column; offset < lineEnd; offset++) {
      const ch = this.text[offset];
      if (ch === ':' && (offset + 1 >= lineEnd || this.text[offset + 1] === ' ')) return true;
      if (ch === '#' && this.text[offset - 1] === ' ') return false;
      if (ch === '[' || ch === '{' || ch === '"' || ch === "'") {
        if (offset === this.lines[line].start + column) return false;
      }
    }
    return false;
  }

  key(line, column) {
    const lineStart = this.lines[line].start;
    const c = this.charAt(line, column);
    if (c === '?' && (this.charAt(line, column + 1) === ' ' || this.charAt(line, column + 1) === '')) {
      throw this.error('YAML_UNSUPPORTED_CONSTRUCT', 'Complex mapping keys are not supported', lineStart + column);
    }
    if (c === '"' || c === "'") {
      const scalar = this.quoted(line, column);
      this.requireColon(line, scalar.endColumn);
      return {text: scalar.value, style: scalar.style, start: lineStart + column, end: lineStart + scalar.endColumn,
        colonColumn: scalar.endColumn};
    }
    let end = column;
    while (lineStart + end < this.lines[line].end) {
      const ch = this.text[lineStart + end];
      if (ch === ':' && (lineStart + end + 1 >= this.lines[line].end || this.text[lineStart + end + 1] === ' ')) break;
      end++;
    }
    this.requireColon(line, end);
    const text = this.text.slice(lineStart + column, lineStart + end);
    this.checkPlainKey(text, lineStart + column);
    return {text, style: 'plain', start: lineStart + column, end: lineStart + end, colonColumn: end};
  }

  /** A block mapping key must be followed on the same line by ":" and then a space or the end of the line. */
  requireColon(line, column) {
    const offset = this.lines[line].start + column;
    const lineEnd = this.lines[line].end;
    if (this.text[offset] !== ':' || (offset + 1 < lineEnd && this.text[offset + 1] !== ' ')) {
      throw this.error('YAML_SYNTAX', 'Expected ": " after a mapping key', Math.min(offset, lineEnd));
    }
  }

  checkPlainKey(text, offset) {
    if (/^[&*!]/.test(text)) {
      throw this.error('YAML_UNSUPPORTED_CONSTRUCT', 'Anchors, aliases and tags are not supported in keys', offset);
    }
    if (!PLAIN_KEY.test(text) || RESERVED_WORDS.has(text.toLowerCase())) {
      throw this.error('YAML_AMBIGUOUS_SCALAR',
        'Quote this mapping key; unquoted keys start with a letter or "_" and use letters, digits, "_", "." or "-"', offset);
    }
  }

  checkKeyLength(key) {
    if (key.text.length > MAX_KEY_CHARACTERS || !(key.end - key.start <= MAX_KEY_SOURCE_CHARACTERS)) {
      throw this.error('YAML_SCALAR_LIMIT', 'Mapping key exceeds its length limit', key.start);
    }
  }

  blockMapping(line, column, depth) {
    this.collectionDepth(depth, this.lines[line].start + column);
    const indent = column;
    const entries = [];
    const names = new Set();
    let current = line;
    let currentColumn = column;
    let next = line + 1;
    const start = this.lines[line].start + column;
    let end = start;
    while (true) {
      const key = this.key(current, currentColumn);
      this.token();
      if (names.has(key.text)) throw this.error('YAML_DUPLICATE_KEY', 'Duplicate mapping key', key.start);
      names.add(key.text);
      this.checkKeyLength(key);
      let valueColumn = key.colonColumn + 1;
      while (this.charAt(current, valueColumn) === ' ') valueColumn++;
      const rest = this.charAt(current, valueColumn);
      let value;
      if (rest === '' || rest === '#') {
        const following = this.nextContent(current + 1);
        if (following >= 0 && this.lines[following].indent > indent) {
          value = this.blockNode(following, this.lines[following].indent, indent, depth + 1);
        } else if (following >= 0 && this.lines[following].indent === indent && this.isDash(following, indent)) {
          value = this.blockSequence(following, indent, depth + 1);
        } else {
          // Java reads an empty block value as empty text, not null. Refuse it rather than guess.
          throw this.error('YAML_EMPTY_VALUE', 'Empty values are not supported; write "" for empty text',
            this.lines[current].start + key.colonColumn);
        }
      } else {
        value = this.inlineValue(current, valueColumn, indent, depth + 1);
      }
      entries.push({key: {text: key.text, style: key.style, start: key.start, end: key.end, ...this.position(key.start)},
        value: value.node});
      end = Math.max(end, value.node.end);
      next = value.next;
      const following = this.nextContent(next);
      if (following < 0 || this.lines[following].indent < indent) break;
      if (this.lines[following].indent > indent) {
        throw this.error('YAML_INDENTATION', 'Unexpected indentation; multi-line scalars are not supported',
          this.lines[following].start + this.lines[following].indent);
      }
      if (this.isDash(following, indent)) {
        throw this.error('YAML_SYNTAX', 'A sequence entry cannot continue a mapping at the same indentation',
          this.lines[following].start + indent);
      }
      current = following;
      currentColumn = indent;
    }
    return {next, node: {kind: 'map', style: 'block', entries, start, end, ...this.position(start)}};
  }

  isDash(line, column) {
    const c = this.charAt(line, column);
    const after = this.charAt(line, column + 1);
    return c === '-' && (after === '' || after === ' ');
  }

  blockSequence(line, column, depth) {
    this.collectionDepth(depth, this.lines[line].start + column);
    const indent = column;
    const items = [];
    let current = line;
    let next = line + 1;
    const start = this.lines[line].start + column;
    let end = start;
    while (true) {
      let itemColumn = indent + 1;
      while (this.charAt(current, itemColumn) === ' ') itemColumn++;
      const rest = this.charAt(current, itemColumn);
      let item;
      if (rest === '' || rest === '#') {
        const following = this.nextContent(current + 1);
        if (following >= 0 && this.lines[following].indent > indent) {
          item = this.blockNode(following, this.lines[following].indent, indent, depth + 1);
        } else {
          throw this.error('YAML_EMPTY_VALUE', 'Empty sequence entries are not supported; write "" for empty text',
            this.lines[current].start + indent);
        }
      } else {
        item = this.blockNode(current, itemColumn, indent, depth + 1);
      }
      items.push(item.node);
      end = Math.max(end, item.node.end);
      next = item.next;
      const following = this.nextContent(next);
      if (following < 0 || this.lines[following].indent < indent) break;
      if (this.lines[following].indent > indent) {
        throw this.error('YAML_INDENTATION', 'Unexpected indentation; multi-line scalars are not supported',
          this.lines[following].start + this.lines[following].indent);
      }
      if (!this.isDash(following, indent)) break;
      current = following;
    }
    return {next, node: {kind: 'seq', style: 'block', items, start, end, ...this.position(start)}};
  }

  /** A value that begins on the current line: flow collection or single-line scalar. */
  inlineValue(line, column, parentIndent, depth) {
    const lineStart = this.lines[line].start;
    const c = this.charAt(line, column);
    const after = this.charAt(line, column + 1);
    let node; let endLine = line; let endColumn;
    if (c === '[' || c === '{') {
      const flow = new FlowReader(this, line, column, parentIndent);
      node = flow.collection(depth);
      endLine = flow.line;
      endColumn = flow.column;
    } else if (c === '"' || c === "'") {
      const scalar = this.quoted(line, column);
      this.token();
      node = {kind: 'scalar', type: 'text', value: scalar.value, style: scalar.style, start: lineStart + column,
        end: lineStart + scalar.endColumn, ...this.position(lineStart + column)};
      endColumn = scalar.endColumn;
    } else {
      this.rejectIndicator(c, after, lineStart + column);
      let end = column;
      const lineEnd = this.lines[line].end;
      while (lineStart + end < lineEnd) {
        const ch = this.text[lineStart + end];
        if (ch === '#' && this.text[lineStart + end - 1] === ' ') break;
        if (ch === ':' && (lineStart + end + 1 >= lineEnd || this.text[lineStart + end + 1] === ' ')) {
          throw this.error('YAML_SYNTAX', 'Mapping values are not allowed in this scalar', lineStart + end);
        }
        end++;
      }
      while (end > column && this.text.charCodeAt(lineStart + end - 1) === 0x20) end--;
      const raw = this.text.slice(lineStart + column, lineStart + end);
      node = this.plainScalar(raw, lineStart + column);
      endColumn = column + raw.length;
    }
    this.requireLineEnd(endLine, endColumn);
    const following = this.nextContent(endLine + 1);
    if (following >= 0 && this.lines[following].indent > parentIndent && parentIndent >= 0
        && !(this.lines[following].indent <= parentIndent)) {
      const nested = this.lines[following].indent;
      // A deeper line after a complete inline value would be a multi-line scalar or a YAML error in Java.
      if (nested > parentIndent && !this.belongsToAncestor(following, parentIndent)) {
        throw this.error('YAML_INDENTATION', 'Unexpected indentation; multi-line scalars are not supported',
          this.lines[following].start + nested);
      }
    }
    return {next: endLine + 1, node};
  }

  belongsToAncestor(line, parentIndent) {
    return this.lines[line].indent <= parentIndent;
  }

  rejectIndicator(c, after, offset) {
    if (c === '&' || c === '*' || c === '!') {
      throw this.error('YAML_UNSUPPORTED_CONSTRUCT', 'YAML anchors, aliases and tags are not supported', offset);
    }
    if (c === '|' || c === '>') throw this.error('YAML_UNSUPPORTED_CONSTRUCT', 'YAML block scalars are not supported', offset);
    if (c === '%' || c === '@' || c === '`') throw this.error('YAML_SYNTAX', 'Reserved YAML indicator', offset);
    if (c === '?' && (after === '' || after === ' ')) {
      throw this.error('YAML_UNSUPPORTED_CONSTRUCT', 'Complex mapping keys are not supported', offset);
    }
    if ((c === '-' || c === ':') && (after === '' || after === ' ')) {
      throw this.error('YAML_SYNTAX', 'Unexpected YAML indicator in a value', offset);
    }
    if (c === ']' || c === '}' || c === ',') throw this.error('YAML_SYNTAX', 'Unexpected flow indicator', offset);
  }

  requireLineEnd(line, column) {
    const lineStart = this.lines[line].start;
    let offset = lineStart + column;
    const lineEnd = this.lines[line].end;
    const beforeSpaces = offset;
    while (offset < lineEnd && this.text[offset] === ' ') offset++;
    if (offset < lineEnd && !(this.text[offset] === '#' && offset > beforeSpaces)) {
      throw this.error('YAML_SYNTAX', 'Unexpected content after a value', offset);
    }
  }

  plainScalar(raw, offset) {
    this.token();
    if (raw.length > this.limits.maxScalarCharacters) throw this.error('YAML_SCALAR_LIMIT', 'Scalar exceeds its length limit', offset);
    const position = this.position(offset);
    const base = {kind: 'scalar', style: 'plain', start: offset, end: offset + raw.length, ...position};
    if (DECIMAL.test(raw)) {
      if (raw.length > 20) throw this.error('YAML_NUMBER_RANGE', 'Integer is outside the signed 64-bit range', offset);
      const value = BigInt(raw);
      if (value < INT64_MIN || value > INT64_MAX) throw this.error('YAML_NUMBER_RANGE', 'Integer is outside the signed 64-bit range', offset);
      return {...base, type: 'integer', value};
    }
    if (raw === 'true' || raw === 'false') return {...base, type: 'boolean', value: raw === 'true'};
    if (RESERVED_WORDS.has(raw.toLowerCase())) {
      throw this.error('YAML_AMBIGUOUS_SCALAR', 'Quote this value; YAML 1.1 reads it as a boolean or null', offset);
    }
    if (!PLAIN_TEXT.test(raw)) {
      throw this.error('YAML_AMBIGUOUS_SCALAR', 'Quote this value; unquoted text is limited to letters, digits, '
        + '"_", ".", "/" and "-" starting with a letter or "_", and integers must be plain decimal', offset);
    }
    return {...base, type: 'text', value: raw};
  }

  /** Single-line quoted scalar beginning at the quote; returns its value and the column after the closing quote. */
  quoted(line, column) {
    const lineStart = this.lines[line].start;
    const lineEnd = this.lines[line].end;
    const quote = this.text[lineStart + column];
    let offset = lineStart + column + 1;
    let value = '';
    while (true) {
      if (offset >= lineEnd) {
        throw this.error('YAML_UNSUPPORTED_CONSTRUCT', 'Quoted scalars must end on the same line', lineStart + column);
      }
      const ch = this.text[offset];
      if (quote === "'") {
        if (ch === "'") {
          if (this.text[offset + 1] === "'" && offset + 1 < lineEnd) { value += "'"; offset += 2; continue; }
          offset++;
          break;
        }
        value += ch; offset++;
        continue;
      }
      if (ch === '"') { offset++; break; }
      if (ch === '\\') {
        const escape = this.text[offset + 1];
        const simple = {'"': '"', '\\': '\\', b: '\b', f: '\f', n: '\n', r: '\r', t: '\t'}[escape];
        if (simple !== undefined && offset + 1 < lineEnd) { value += simple; offset += 2; continue; }
        if (escape === 'u') {
          const unit = this.hex4(offset + 2, lineEnd);
          offset += 6;
          if (unit >= 0xd800 && unit <= 0xdbff) {
            if (this.text[offset] !== '\\' || this.text[offset + 1] !== 'u') {
              throw this.error('YAML_INVALID_ESCAPE', 'Unpaired surrogate escape', offset);
            }
            const low = this.hex4(offset + 2, lineEnd);
            if (low < 0xdc00 || low > 0xdfff) throw this.error('YAML_INVALID_ESCAPE', 'Unpaired surrogate escape', offset);
            offset += 6;
            value += String.fromCharCode(unit, low);
          } else if (unit >= 0xdc00 && unit <= 0xdfff) {
            throw this.error('YAML_INVALID_ESCAPE', 'Unpaired surrogate escape', offset - 6);
          } else value += String.fromCharCode(unit);
          continue;
        }
        throw this.error('YAML_INVALID_ESCAPE', 'Only \\" \\\\ \\b \\f \\n \\r \\t and \\uXXXX escapes are supported', offset);
      }
      value += ch; offset++;
    }
    if (value.length > this.limits.maxScalarCharacters) {
      throw this.error('YAML_SCALAR_LIMIT', 'Scalar exceeds its length limit', lineStart + column);
    }
    return {value, style: quote === '"' ? 'double' : 'single', endColumn: offset - lineStart};
  }

  hex4(offset, lineEnd) {
    const digits = this.text.slice(offset, Math.min(offset + 4, lineEnd));
    if (!/^[0-9a-fA-F]{4}$/.test(digits)) throw this.error('YAML_INVALID_ESCAPE', 'Invalid \\u escape', offset);
    return Number.parseInt(digits, 16);
  }
}

/** Flow collections may span lines; continuation lines must be indented deeper than the enclosing block. */
class FlowReader {
  constructor(parser, line, column, parentIndent) {
    this.parser = parser;
    this.line = line;
    this.column = column;
    this.parentIndent = parentIndent;
  }

  get offset() { return this.parser.lines[this.line].start + this.column; }

  current() { return this.parser.charAt(this.line, this.column); }

  skip() {
    while (true) {
      const c = this.current();
      if (c === ' ') { this.column++; continue; }
      const lineStart = this.parser.lines[this.line].start;
      const atEnd = lineStart + this.column >= this.parser.lines[this.line].end;
      if (c === '#' && (this.column === 0 || this.parser.text[lineStart + this.column - 1] === ' ')) {
        this.column = this.parser.lines[this.line].end - lineStart;
        continue;
      }
      if (atEnd) {
        if (this.line + 1 >= this.parser.lines.length) {
          throw this.parser.error('YAML_SYNTAX', 'Unterminated flow collection', this.offset);
        }
        this.line++;
        const next = this.parser.lines[this.line];
        if (!next.blank && next.indent <= this.parentIndent) {
          throw this.parser.error('YAML_INDENTATION', 'Flow collection lines must be indented deeper than their parent',
            next.start + next.indent);
        }
        this.column = 0;
        continue;
      }
      return;
    }
  }

  collection(depth) {
    this.parser.collectionDepth(depth, this.offset);
    const open = this.current();
    const start = this.offset;
    const position = this.parser.position(start);
    this.column++;
    if (open === '[') {
      const items = [];
      this.skip();
      if (this.current() === ']') { this.column++; return {kind: 'seq', style: 'flow', items, start, end: this.offset, ...position}; }
      while (true) {
        this.skip();
        items.push(this.value(depth + 1, false));
        this.skip();
        const separator = this.current();
        if (separator === ']') { this.column++; break; }
        if (separator !== ',') throw this.parser.error('YAML_SYNTAX', 'Expected "," or "]" in a flow sequence', this.offset);
        this.column++;
        this.skip();
        if (this.current() === ']') throw this.parser.error('YAML_UNSUPPORTED_CONSTRUCT', 'Trailing commas are not supported', this.offset);
      }
      return {kind: 'seq', style: 'flow', items, start, end: this.offset, ...position};
    }
    const entries = [];
    const names = new Set();
    this.skip();
    if (this.current() === '}') { this.column++; return {kind: 'map', style: 'flow', entries, start, end: this.offset, ...position}; }
    while (true) {
      this.skip();
      const key = this.flowKey();
      this.parser.checkKeyLength(key);
      this.parser.token();
      if (names.has(key.text)) throw this.parser.error('YAML_DUPLICATE_KEY', 'Duplicate mapping key', key.start);
      names.add(key.text);
      // An implicit key and its ":" must be on the same line.
      while (this.current() === ' ') this.column++;
      if (this.current() !== ':') throw this.parser.error('YAML_UNSUPPORTED_CONSTRUCT', 'Flow mapping entries need a ": value" part on the key line', this.offset);
      const colon = this.offset;
      this.column++;
      const after = this.current();
      if (key.style === 'plain' && after !== ' ' && after !== '' ) {
        throw this.parser.error('YAML_SYNTAX', 'A space is required after ":"', colon);
      }
      this.skip();
      const value = this.value(depth + 1, true);
      entries.push({key: {...key, ...this.parser.position(key.start)}, value});
      this.skip();
      const separator = this.current();
      if (separator === '}') { this.column++; break; }
      if (separator !== ',') throw this.parser.error('YAML_SYNTAX', 'Expected "," or "}" in a flow mapping', this.offset);
      this.column++;
      this.skip();
      if (this.current() === '}') throw this.parser.error('YAML_UNSUPPORTED_CONSTRUCT', 'Trailing commas are not supported', this.offset);
    }
    return {kind: 'map', style: 'flow', entries, start, end: this.offset, ...position};
  }

  flowKey() {
    const c = this.current();
    const start = this.offset;
    if (c === '"' || c === "'") {
      const scalar = this.parser.quoted(this.line, this.column);
      this.column = scalar.endColumn;
      return {text: scalar.value, style: scalar.style, start, end: this.offset};
    }
    if (c === '?') throw this.parser.error('YAML_UNSUPPORTED_CONSTRUCT', 'Complex mapping keys are not supported', start);
    const lineStart = this.parser.lines[this.line].start;
    const lineEnd = this.parser.lines[this.line].end;
    let end = this.column;
    while (lineStart + end < lineEnd) {
      const ch = this.parser.text[lineStart + end];
      if (ch === ':' || ch === ',' || ch === '[' || ch === ']' || ch === '{' || ch === '}' || ch === ' ' || ch === '#') break;
      end++;
    }
    const text = this.parser.text.slice(lineStart + this.column, lineStart + end);
    this.parser.checkPlainKey(text, start);
    this.column = end;
    return {text, style: 'plain', start, end: this.offset};
  }

  value(depth, inMapping) {
    const c = this.current();
    const start = this.offset;
    if (c === '[' || c === '{') return this.collection(depth);
    if (c === '"' || c === "'") {
      const scalar = this.parser.quoted(this.line, this.column);
      this.column = scalar.endColumn;
      this.parser.token();
      return {kind: 'scalar', type: 'text', value: scalar.value, style: scalar.style, start, end: this.offset,
        ...this.parser.position(start)};
    }
    if (c === ',' || c === ']' || c === '}' || c === '') {
      throw this.parser.error('YAML_UNSUPPORTED_CONSTRUCT', 'Empty flow values are not supported', start);
    }
    this.parser.rejectIndicator(c, this.parser.charAt(this.line, this.column + 1), start);
    const lineStart = this.parser.lines[this.line].start;
    const lineEnd = this.parser.lines[this.line].end;
    let end = this.column;
    while (lineStart + end < lineEnd) {
      const ch = this.parser.text[lineStart + end];
      if (ch === ',' || ch === '[' || ch === ']' || ch === '{' || ch === '}') break;
      if (ch === '#' && this.parser.text[lineStart + end - 1] === ' ') break;
      if (ch === ':') {
        const next = lineStart + end + 1 < lineEnd ? this.parser.text[lineStart + end + 1] : '';
        if (next === ' ' || next === '' || next === ',' || next === ']' || next === '}' || next === '[' || next === '{') {
          throw this.parser.error(inMapping ? 'YAML_SYNTAX' : 'YAML_UNSUPPORTED_CONSTRUCT',
            inMapping ? 'Mapping values are not allowed in this scalar' : 'Single-pair mappings inside flow sequences are not supported',
            lineStart + end);
        }
      }
      end++;
    }
    while (end > this.column && this.parser.text.charCodeAt(lineStart + end - 1) === 0x20) end--;
    const raw = this.parser.text.slice(lineStart + this.column, lineStart + end);
    this.column += raw.length;
    // A plain scalar must be followed by a separator; text on the next line would be a multi-line scalar.
    const checkpoint = {line: this.line, column: this.column};
    const crossesLine = () => {
      const saved = {line: this.line, column: this.column};
      this.skip();
      const moved = this.line !== saved.line;
      const next = this.current();
      this.line = saved.line; this.column = saved.column;
      return moved && next !== ',' && next !== ']' && next !== '}' && next !== ':';
    };
    if (crossesLine()) {
      throw this.parser.error('YAML_UNSUPPORTED_CONSTRUCT', 'Multi-line plain scalars are not supported', start);
    }
    this.line = checkpoint.line; this.column = checkpoint.column;
    return this.parser.plainScalar(raw, start);
  }
}

// ---------------------------------------------------------------------------
// Tree helpers
// ---------------------------------------------------------------------------

/** Converts a parsed node into a typed JSON-safe tree used for differential and golden tests. */
export function typedTree(node) {
  switch (node.kind) {
    case 'map': return {map: node.entries.map(entry => [entry.key.text, typedTree(entry.value)])};
    case 'seq': return {seq: node.items.map(typedTree)};
    case 'scalar':
      if (node.type === 'integer') return {int: node.value.toString()};
      if (node.type === 'boolean') return {bool: node.value};
      return {text: node.value};
    default: throw new Error('unknown YAML node');
  }
}

/** Looks up one entry of a mapping node by exact key text. */
export function mapEntry(node, key) {
  return node?.kind === 'map' ? node.entries.find(entry => entry.key.text === key) : undefined;
}

// ---------------------------------------------------------------------------
// Emitter
// ---------------------------------------------------------------------------

/** Ordered mapping for the emitter; plain objects cannot preserve integer-like key order. */
export class YamlMap {
  constructor(entries = []) { this.entries = entries.filter(([, value]) => value !== undefined); }
  static of(...entries) { return new YamlMap(entries); }
}

/** Marks a map or sequence for single-line flow style when it fits; long values fall back to block style. */
export class YamlFlow {
  constructor(value) { this.value = value; }
}

export const flow = value => new YamlFlow(value);

/** True when text can be written unquoted and will be read back by both Studio and Java as the same text. */
export function isSafePlainText(text) {
  return typeof text === 'string' && text.length > 0 && text.length <= 256 && PLAIN_TEXT.test(text)
    && !RESERVED_WORDS.has(text.toLowerCase());
}

/** Double-quoted YAML text using only escapes accepted by both the Studio profile and the Java parser. */
export function quoteYaml(text) {
  let result = '"';
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c === 0x22) result += '\\"';
    else if (c === 0x5c) result += '\\\\';
    else if (c === 0x0a) result += '\\n';
    else if (c === 0x09) result += '\\t';
    else if (c === 0x0d) result += '\\r';
    else if (c < 0x20 || c === 0x7f || (c >= 0x80 && c <= 0x9f) || c === 0x2028 || c === 0x2029 || c === 0xfeff
        || c === 0xfffe || c === 0xffff) {
      result += `\\u${c.toString(16).padStart(4, '0')}`;
    } else result += text[i];
  }
  return `${result}"`;
}

function emitKey(key) {
  const source = PLAIN_KEY.test(key) && !RESERVED_WORDS.has(key.toLowerCase()) ? key : quoteYaml(key);
  if (key.length > MAX_KEY_CHARACTERS || source.length > MAX_KEY_SOURCE_CHARACTERS) {
    throw new YamlInputError('YAML_SCALAR_LIMIT', 'Mapping key exceeds its length limit');
  }
  return source;
}

function emitScalar(value) {
  if (typeof value === 'bigint') {
    if (value < INT64_MIN || value > INT64_MAX) throw new YamlInputError('YAML_NUMBER_RANGE', 'Integer is outside the signed 64-bit range');
    return value.toString();
  }
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'string') return isSafePlainText(value) ? value : quoteYaml(value);
  throw new YamlInputError('YAML_EMIT', 'Unsupported value for YAML emission');
}

function emitFlow(value) {
  if (value instanceof YamlFlow) return emitFlow(value.value);
  if (value instanceof YamlMap) {
    if (!value.entries.length) return '{}';
    return `{${value.entries.map(([key, item]) => `${emitKey(key)}: ${emitFlow(item)}`).join(', ')}}`;
  }
  if (Array.isArray(value)) return `[${value.map(emitFlow).join(', ')}]`;
  return emitScalar(value);
}

const FLOW_WIDTH = 100;

/** Comment text on one line: every line-breaking or control character becomes a space. */
function commentText(value) {
  let result = '';
  for (const character of String(value)) {
    const c = character.codePointAt(0);
    result += c < 0x20 || c === 0x7f || (c >= 0x80 && c <= 0x9f) || c === 0x2028 || c === 0x2029 || c === 0xfeff
      ? ' ' : character;
  }
  return result;
}

/**
 * Writes a deterministic document inside the Studio YAML profile.
 *
 * @param {YamlMap} root ordered root mapping
 * @param {object} [options] `{header: string[]}` comment lines, `{lineEnding}` output line ending
 * @returns {string} document text ending in one line ending
 */
export function emitYaml(root, options = {}) {
  const lines = [];
  for (const comment of options.header || []) {
    lines.push(`# ${commentText(comment)}`.trimEnd());
  }
  emitBlockMap(root, 0, lines);
  const ending = options.lineEnding === '\r\n' ? '\r\n' : '\n';
  return lines.join(ending) + ending;
}

function isCollection(value) {
  return value instanceof YamlMap || Array.isArray(value) || value instanceof YamlFlow;
}

function inlineFlow(value, prefixLength) {
  const unwrapped = value instanceof YamlFlow ? value.value : value;
  if (unwrapped instanceof YamlMap && !unwrapped.entries.length) return '{}';
  if (Array.isArray(unwrapped) && !unwrapped.length) return '[]';
  if (!(value instanceof YamlFlow)) return null;
  const text = emitFlow(value);
  return prefixLength + text.length <= FLOW_WIDTH ? text : null;
}

function emitBlockMap(map, indent, lines) {
  const pad = ' '.repeat(indent);
  for (const [key, value] of map.entries) {
    const prefix = `${pad}${emitKey(key)}:`;
    if (!isCollection(value)) { lines.push(`${prefix} ${emitScalar(value)}`); continue; }
    const inline = inlineFlow(value, prefix.length + 1);
    if (inline !== null) { lines.push(`${prefix} ${inline}`); continue; }
    lines.push(prefix);
    emitBlockValue(value instanceof YamlFlow ? value.value : value, indent + 2, lines);
  }
}

function emitBlockValue(value, indent, lines) {
  if (value instanceof YamlMap) emitBlockMap(value, indent, lines);
  else emitBlockSequence(value, indent, lines);
}

function emitBlockSequence(items, indent, lines) {
  const pad = ' '.repeat(indent);
  for (const item of items) {
    if (!isCollection(item)) { lines.push(`${pad}- ${emitScalar(item)}`); continue; }
    const inline = inlineFlow(item, indent + 2);
    if (inline !== null) { lines.push(`${pad}- ${inline}`); continue; }
    const value = item instanceof YamlFlow ? item.value : item;
    if (value instanceof YamlMap) {
      const nested = [];
      emitBlockMap(value, indent + 2, nested);
      nested[0] = `${pad}- ${nested[0].slice(indent + 2)}`;
      lines.push(...nested);
    } else {
      lines.push(`${pad}-`);
      emitBlockSequence(value, indent + 2, lines);
    }
  }
}
