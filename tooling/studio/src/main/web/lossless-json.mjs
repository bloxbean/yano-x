/**
 * Strict, lossless JSON for Studio tooling files (ADR-031.2 contract C4).
 *
 * Every JSON number that Studio reads is parsed without passing through a JavaScript Number: integers become
 * BigInt values, and fractions or exponents are rejected because no binding tooling contract uses them.
 * Objects have a null prototype, duplicate names fail closed, and input size, nesting, string and entry counts
 * are bounded before a large tree can be built. The parser never evaluates or executes imported text.
 */

export const JSON_DEFAULT_LIMITS = Object.freeze({
  maxCharacters: 16 * 1024 * 1024,
  maxDepth: 64,
  maxStringCharacters: 16 * 1024 * 1024,
  maxEntries: 1_000_000,
  maxNumberCharacters: 32
});

const INT64_MIN = -(2n ** 63n);
const INT64_MAX = 2n ** 63n - 1n;

/** Typed failure with a stable code and a one-based line/column position; messages never echo input values. */
export class JsonInputError extends Error {
  constructor(code, message, position = {}) {
    super(position.line ? `${message} (line ${position.line}, column ${position.column})` : message);
    this.name = 'JsonInputError';
    this.code = code;
    this.line = position.line;
    this.column = position.column;
    this.path = position.path;
  }
}

/**
 * Parses one JSON value.
 *
 * @param {string} text complete decoded text (use {@link decodeUtf8Strict} for file bytes)
 * @param {object} [options] limits overriding {@link JSON_DEFAULT_LIMITS}
 * @returns {*} null-prototype objects, arrays, strings, booleans, null and BigInt integers
 */
export function parseJson(text, options = {}) {
  const limits = {...JSON_DEFAULT_LIMITS, ...options};
  if (typeof text !== 'string') throw new JsonInputError('JSON_INPUT', 'JSON input must be text');
  if (text.length > limits.maxCharacters) throw new JsonInputError('JSON_TOO_LARGE', 'JSON input exceeds its size limit');
  let index = 0;
  let entries = 0;
  const position = at => {
    let line = 1; let column = 1;
    for (let i = 0; i < at && i < text.length; i++) {
      if (text.charCodeAt(i) === 10) { line++; column = 1; } else column++;
    }
    return {line, column};
  };
  const fail = (code, message, at = index) => { throw new JsonInputError(code, message, position(at)); };
  const whitespace = () => {
    while (index < text.length) {
      const c = text.charCodeAt(index);
      if (c === 0x20 || c === 0x09 || c === 0x0a || c === 0x0d) index++; else break;
    }
  };
  const count = () => { if (++entries > limits.maxEntries) fail('JSON_TOO_LARGE', 'JSON input has too many values'); };

  const string = () => {
    const start = index++;
    let result = '';
    let segment = index;
    while (true) {
      if (index >= text.length) fail('JSON_SYNTAX', 'Unterminated JSON string', start);
      const c = text.charCodeAt(index);
      if (c === 0x22) {
        result += text.slice(segment, index++);
        break;
      }
      if (c < 0x20) fail('JSON_SYNTAX', 'Raw control character in JSON string');
      if (c === 0x5c) {
        result += text.slice(segment, index);
        const escape = text[index + 1];
        const simple = {'"': '"', '\\': '\\', '/': '/', b: '\b', f: '\f', n: '\n', r: '\r', t: '\t'}[escape];
        if (simple !== undefined) { result += simple; index += 2; }
        else if (escape === 'u') {
          const unit = hex4(index + 2);
          index += 6;
          if (unit >= 0xd800 && unit <= 0xdbff) {
            if (text[index] !== '\\' || text[index + 1] !== 'u') fail('JSON_SYNTAX', 'Unpaired surrogate escape');
            const low = hex4(index + 2);
            if (low < 0xdc00 || low > 0xdfff) fail('JSON_SYNTAX', 'Unpaired surrogate escape');
            index += 6;
            result += String.fromCharCode(unit, low);
          } else if (unit >= 0xdc00 && unit <= 0xdfff) fail('JSON_SYNTAX', 'Unpaired surrogate escape');
          else result += String.fromCharCode(unit);
        } else fail('JSON_SYNTAX', 'Invalid JSON escape');
        segment = index;
      } else {
        if (c >= 0xd800 && c <= 0xdfff) {
          const next = text.charCodeAt(index + 1);
          if (c > 0xdbff || !(next >= 0xdc00 && next <= 0xdfff)) fail('JSON_SYNTAX', 'Invalid Unicode text');
          index++;
        }
        index++;
      }
      if (index - start > limits.maxStringCharacters + 2) fail('JSON_TOO_LARGE', 'JSON string exceeds its limit');
    }
    if (result.length > limits.maxStringCharacters) fail('JSON_TOO_LARGE', 'JSON string exceeds its limit', start);
    return result;
  };
  const hex4 = at => {
    const digits = text.slice(at, at + 4);
    if (!/^[0-9a-fA-F]{4}$/.test(digits)) fail('JSON_SYNTAX', 'Invalid \\u escape', at);
    return Number.parseInt(digits, 16);
  };
  const number = () => {
    const match = /^-?(?:0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?/.exec(text.slice(index, index + limits.maxNumberCharacters + 2));
    if (!match) fail('JSON_SYNTAX', 'Invalid JSON number');
    if (match[0].length > limits.maxNumberCharacters) fail('JSON_TOO_LARGE', 'JSON number exceeds its length limit');
    if (match[1] !== undefined || match[2] !== undefined) {
      fail('JSON_NON_INTEGER', 'Fractional or exponent numbers are not accepted by binding tooling');
    }
    index += match[0].length;
    return BigInt(match[0]);
  };
  const literal = (word, value) => {
    if (text.startsWith(word, index)) { index += word.length; return value; }
    return fail('JSON_SYNTAX', 'Unexpected JSON token');
  };
  // `depth` counts open containers; scalars never add nesting.
  const value = depth => {
    whitespace();
    count();
    const c = text[index];
    if ((c === '{' || c === '[') && depth + 1 > limits.maxDepth) fail('JSON_TOO_DEEP', 'JSON nesting exceeds its limit');
    if (c === '{') {
      index++;
      const result = Object.create(null);
      whitespace();
      if (text[index] === '}') { index++; return result; }
      while (true) {
        whitespace();
        if (text[index] !== '"') fail('JSON_SYNTAX', 'Expected a JSON object member name');
        const nameAt = index;
        const name = string();
        if (Object.prototype.hasOwnProperty.call(result, name)) fail('JSON_DUPLICATE_KEY', 'Duplicate JSON object member', nameAt);
        whitespace();
        if (text[index++] !== ':') fail('JSON_SYNTAX', 'Expected ":" after a JSON member name', index - 1);
        result[name] = value(depth + 1);
        whitespace();
        const separator = text[index++];
        if (separator === '}') return result;
        if (separator !== ',') fail('JSON_SYNTAX', 'Expected "," or "}" in a JSON object', index - 1);
      }
    }
    if (c === '[') {
      index++;
      const result = [];
      whitespace();
      if (text[index] === ']') { index++; return result; }
      while (true) {
        result.push(value(depth + 1));
        whitespace();
        const separator = text[index++];
        if (separator === ']') return result;
        if (separator !== ',') fail('JSON_SYNTAX', 'Expected "," or "]" in a JSON array', index - 1);
      }
    }
    if (c === '"') return string();
    if (c === 't') return literal('true', true);
    if (c === 'f') return literal('false', false);
    if (c === 'n') return literal('null', null);
    if (c === '-' || (c >= '0' && c <= '9')) return number();
    return fail('JSON_SYNTAX', index >= text.length ? 'Unexpected end of JSON input' : 'Unexpected JSON token');
  };
  const result = value(0);
  whitespace();
  if (index !== text.length) fail('JSON_SYNTAX', 'Unexpected trailing JSON data');
  return result;
}

/**
 * Serializes values produced by {@link parseJson} (or plain equivalents) without losing BigInt precision.
 * BigInt values are written as bare decimal integer tokens, suitable for legacy Java long fields.
 *
 * @param {*} value JSON-compatible value
 * @param {number} [indent] spaces per nesting level; 0 writes compact JSON
 * @returns {string} JSON text ending without a newline
 */
export function stringifyJson(value, indent = 2) {
  const write = (current, depth) => {
    if (current === null) return 'null';
    if (typeof current === 'bigint') return current.toString();
    if (typeof current === 'boolean') return current ? 'true' : 'false';
    if (typeof current === 'string') return quoteJson(current);
    if (typeof current === 'number') {
      if (!Number.isSafeInteger(current)) throw new JsonInputError('JSON_NUMBER', 'Only safe integers or BigInt values can be serialized');
      return String(current);
    }
    const pad = indent ? `\n${' '.repeat(indent * (depth + 1))}` : '';
    const close = indent ? `\n${' '.repeat(indent * depth)}` : '';
    if (Array.isArray(current)) {
      if (!current.length) return '[]';
      return `[${current.map(item => pad + write(item, depth + 1)).join(',')}${close}]`;
    }
    if (typeof current === 'object') {
      const keys = Object.keys(current).filter(key => current[key] !== undefined);
      if (!keys.length) return '{}';
      const separator = indent ? ': ' : ':';
      return `{${keys.map(key => pad + quoteJson(key) + separator + write(current[key], depth + 1)).join(',')}${close}}`;
    }
    throw new JsonInputError('JSON_VALUE', 'Unsupported value for JSON serialization');
  };
  return write(value, 0);
}

/** JSON string literal with every non-printable, separator or non-ASCII-control character escaped. */
export function quoteJson(text) {
  let result = '"';
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c === 0x22) result += '\\"';
    else if (c === 0x5c) result += '\\\\';
    else if (c < 0x20 || c === 0x7f || (c >= 0x80 && c <= 0x9f) || c === 0x2028 || c === 0x2029) {
      result += `\\u${c.toString(16).padStart(4, '0')}`;
    } else result += text[i];
  }
  return `${result}"`;
}

/** Decodes file bytes as UTF-8, rejecting malformed sequences instead of substituting replacement characters. */
export function decodeUtf8Strict(bytes) {
  try {
    return new TextDecoder('utf-8', {fatal: true}).decode(bytes);
  } catch {
    throw new JsonInputError('INVALID_UTF8', 'File is not valid UTF-8 text');
  }
}

// ---------------------------------------------------------------------------
// Typed accessors used by contract validators. Paths are JSONPath-like and never contain input values.
// ---------------------------------------------------------------------------

export function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** Requires an object whose names are all allowed and which contains every required name. */
export function expectObject(value, path, required = [], optional = []) {
  if (!isObject(value)) throw new JsonInputError('CONTRACT_TYPE', `${path} must be an object`, {path});
  const allowed = new Set([...required, ...optional]);
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) throw new JsonInputError('CONTRACT_UNKNOWN_FIELD', `${path} has unsupported field ${quoteJson(key.slice(0, 64))}`, {path});
  }
  for (const key of required) {
    if (!Object.prototype.hasOwnProperty.call(value, key)) throw new JsonInputError('CONTRACT_MISSING_FIELD', `${path}.${key} is required`, {path});
  }
  return value;
}

export function expectArray(value, path, maximum) {
  if (!Array.isArray(value)) throw new JsonInputError('CONTRACT_TYPE', `${path} must be an array`, {path});
  if (value.length > maximum) throw new JsonInputError('CONTRACT_LIMIT', `${path} exceeds ${maximum} entries`, {path});
  return value;
}

export function expectString(value, path, maximum = 65_536, pattern) {
  if (typeof value !== 'string') throw new JsonInputError('CONTRACT_TYPE', `${path} must be text`, {path});
  if (value.length > maximum) throw new JsonInputError('CONTRACT_LIMIT', `${path} exceeds ${maximum} characters`, {path});
  if (pattern && !pattern.test(value)) throw new JsonInputError('CONTRACT_FORMAT', `${path} has an invalid format`, {path});
  return value;
}

export function expectBoolean(value, path) {
  if (typeof value !== 'boolean') throw new JsonInputError('CONTRACT_TYPE', `${path} must be a boolean`, {path});
  return value;
}

/** Accepts a BigInt produced by the lossless parser within an inclusive range and returns it as BigInt. */
export function expectInteger(value, path, minimum = INT64_MIN, maximum = INT64_MAX) {
  if (typeof value !== 'bigint') throw new JsonInputError('CONTRACT_TYPE', `${path} must be an integer`, {path});
  if (value < BigInt(minimum) || value > BigInt(maximum)) {
    throw new JsonInputError('CONTRACT_RANGE', `${path} is outside its permitted range`, {path});
  }
  return value;
}

/** Small bounded integers (ordinals, indexes, counts) returned as JavaScript numbers. */
export function expectSmallInteger(value, path, minimum, maximum) {
  return Number(expectInteger(value, path, minimum, maximum));
}

/** Canonical decimal text for a signed 64-bit integer: no sign on zero, no leading zeros, no plus sign. */
export function expectDecimalInt64(value, path) {
  expectString(value, path, 20, /^(?:0|-?[1-9][0-9]*)$/);
  const parsed = BigInt(value);
  if (parsed < INT64_MIN || parsed > INT64_MAX) throw new JsonInputError('CONTRACT_RANGE', `${path} is outside the int64 range`, {path});
  return parsed;
}

export function isInt64(value) {
  return typeof value === 'bigint' && value >= INT64_MIN && value <= INT64_MAX;
}

export const INT64 = Object.freeze({min: INT64_MIN, max: INT64_MAX});
