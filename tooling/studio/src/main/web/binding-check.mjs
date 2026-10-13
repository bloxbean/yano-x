/**
 * Conservative, advisory checks for a binding draft (ADR-031.2 §4.2). They help authors before handing a
 * document to the CLI; they never establish validity. The Java compiler and catalog-selected provider remain
 * the authority for types, evidence, cycles, limits and profile construction. Messages name declarations only,
 * never literal values.
 */
import {configurationKey,findInstance} from './binding-catalog.mjs';
import {isHidden} from './binding-explain.mjs';
import {LIMIT_NAMES,PARAMETER_TYPES} from './binding-draft.mjs';

const ID = /^[a-z][a-z0-9-]{0,62}$/;
const IDENTIFIER = /^[a-zA-Z][a-zA-Z0-9_]{0,62}$/;
const DENY_CODE = /^[A-Z][A-Z0-9_]{0,62}$/;
const CONTEXT_FIELDS = new Set(['height', 'sender', 'derived', 'depth', 'binding']);
/** CEL reserved words, which cannot name a parameter or fact (ADR-031.3 §5.2). */
const RESERVED = new Set(['as', 'break', 'const', 'continue', 'else', 'false', 'for', 'function', 'if', 'import', 'in',
  'let', 'loop', 'namespace', 'null', 'package', 'return', 'true', 'var', 'void', 'while']);
const NAME = /^[a-zA-Z][a-zA-Z0-9_.-]{0,126}$/;
export const BASELINE_EVENT = 'composite.command-accepted.v1';

/** Authored configuration as the catalog's typed map (lowercase hex for bytes); never adds defaults. */
export function typedConfiguration(component) {
  const result = Object.create(null);
  for (const setting of component.config ?? []) {
    const value = setting.value;
    result[setting.name] = value.type === 'bytes' ? {type: 'bytes', hex: value.hex.toLowerCase()} : value;
  }
  return result;
}

/**
 * The catalog instance describing a draft component, or null when this exact configuration is not described or
 * the catalog is not the one `expected` names (see `findInstance`).
 */
export function componentInstance(catalog, component, expected) {
  return catalog ? findInstance(catalog, component.machine, typedConfiguration(component), expected) : null;
}

function eventFields(catalog, instance, eventId) {
  if (eventId === BASELINE_EVENT) {
    return new Map(catalog.language.baselineEvent.fields.map(field => [field.name, field.type]));
  }
  const event = instance?.events?.find(value => value.eventId === eventId);
  return event ? new Map(event.fields.map(field => [field.name, field.type])) : null;
}

function literalType(value) { return value.type; }

/**
 * Static type of a mapping source where it can be known without evaluation; `null` means opaque or unknown
 * (expressions and `cbor-field` are typed by the compiler, not here).
 */
function sourceType(source, fields, functions) {
  if (source.kind === 'field') return fields?.get(source.name) ?? null;
  if (source.kind === 'literal') return literalType(source.value);
  if (source.kind === 'expr') return null;
  const signature = functions.get(source.fn);
  if (!signature) return null;
  // An incomplete call has no argument to take its type from; FUNCTION_ARITY reports it.
  if (signature.result === 'same-as-arguments') {
    return source.args.length ? sourceType(source.args[0], fields, functions) : null;
  }
  if (signature.result === 'opaque') return null;
  return signature.result;
}

/**
 * Advisory diagnostics for a draft.
 *
 * @param {object} draft authored draft
 * @param {object|null} catalog imported authoring catalog, or null
 * @param {{expected?: object}} [options] `expected` is the catalog identity the draft is bound to (`catalogBinding`)
 * @returns {Array<{code, severity:'advisory'|'info', message, segments}>}
 */
export function checkDraft(draft, catalog, {expected} = {}) {
  const diagnostics = [];
  const prefix = draft.wrapped ? ['composite'] : [];
  const add = (code, message, segments, severity = 'advisory') => diagnostics.push({code, severity, message,
    segments: [...prefix, ...segments]});
  const functions = new Map((catalog?.language.functions ?? []).map(fn => [fn.id, fn]));
  const components = new Map();
  const topics = new Map();
  draft.components.forEach((component, index) => {
    if (!ID.test(component.id)) add('INVALID_ID', 'Component ids use lowercase letters, digits and hyphens', ['components', index, 'id']);
    if (components.has(component.id)) add('DUPLICATE_COMPONENT', `Component ${component.id} is declared twice`, ['components', index, 'id']);
    else components.set(component.id, {component, index});
    const topic = component.topic ?? `${component.id}.command.v1`;
    if (topics.has(topic)) add('DUPLICATE_TOPIC', 'Two components share one ingress topic', ['components', index, 'topic']);
    topics.set(topic, index);
    if (catalog) {
      const instance = componentInstance(catalog, component, expected);
      if (!instance) {
        add('DESCRIPTOR_UNAVAILABLE', `No catalog descriptor for ${component.machine} with this exact configuration; `
          + 'export a catalog for this document', ['components', index], 'info');
      } else if (instance.status !== 'available') {
        add('DESCRIPTOR_' + instance.status.toUpperCase().replaceAll('-', '_'),
          `${component.machine} is ${instance.status} in the loaded catalog`, ['components', index], 'info');
      }
    }
  });
  if (draft.components.length > 16) add('COMPONENT_LIMIT', 'At most 16 components are allowed', ['components']);
  if (draft.bindings.length > 256) add('BINDING_LIMIT', 'At most 256 bindings are allowed', ['bindings']);
  const bindingIds = new Set();
  const edges = new Map();
  draft.bindings.forEach((binding, index) => {
    const at = ['bindings', index];
    if (!ID.test(binding.id)) add('INVALID_ID', 'Binding ids use lowercase letters, digits and hyphens', [...at, 'id']);
    if (bindingIds.has(binding.id)) add('DUPLICATE_BINDING', `Binding ${binding.id} is declared twice`, [...at, 'id']);
    bindingIds.add(binding.id);
    const source = components.get(binding.from.component);
    if (!source) add('UNKNOWN_COMPONENT', `Source ${binding.from.component} is not declared`, [...at, 'from', 'component']);
    const sourceInstance = source && catalog ? componentInstance(catalog, source.component, expected) : null;
    const fields = catalog && (sourceInstance?.status === 'available' || binding.from.event === BASELINE_EVENT)
      ? eventFields(catalog, sourceInstance, binding.from.event) : null;
    if (sourceInstance?.status === 'available' && !fields) {
      add('UNKNOWN_EVENT', `${source.component.machine} does not publish ${binding.from.event}`, [...at, 'from', 'event']);
    }
    (binding.when ?? []).forEach((clause, c) => {
      const clauseAt = [...at, 'when', c];
      if (clause.kind === 'field' && fields) {
        const type = fields.get(clause.field);
        if (!type) add('UNKNOWN_EVENT_FIELD', `The event has no field ${clause.field}`, [...clauseAt, 'field']);
        else if (['lt', 'le', 'gt', 'ge'].includes(clause.operator) && type !== 'integer') {
          add('BINDING_TYPE_MISMATCH', 'Ordering comparisons need an integer field', clauseAt);
        } else if (['in', 'exists', 'absent'].includes(clause.operator) && type === 'boolean') {
          add('BINDING_TYPE_MISMATCH', 'This operator does not accept a boolean field', clauseAt);
        } else if (!['exists', 'absent'].includes(clause.operator)) {
          const operands = clause.operator === 'in' ? clause.operand : [clause.operand];
          if (operands.some(operand => operand.type !== type)) {
            add('BINDING_TYPE_MISMATCH', `Literal types must match the ${type} field`, clauseAt);
          }
        }
      }
      if (clause.kind === 'lookup' && !components.has(clause.component)) {
        add('UNKNOWN_COMPONENT', `Lookup participant ${clause.component} is not declared`, [...clauseAt, 'lookup', 'component']);
      }
      if (clause.kind === 'expr' && !clause.text.trim()) add('EXPRESSION_EMPTY', 'Expression text is empty', [...clauseAt, 'expr']);
      if (clause.kind === 'field' && clause.operator === 'in' && clause.operand.length > 64) {
        add('IN_LIMIT', 'An in list allows at most 64 entries', [...clauseAt, 'in']);
      }
    });
    if ((binding.when ?? []).length > 8) add('CLAUSE_LIMIT', 'A condition allows at most 8 clauses', [...at, 'when']);
    const to = binding.to;
    const targetAt = to.kind === 'effect' ? [...at, 'to', 'effect'] : [...at, 'to'];
    if (to.kind === 'command') {
      const target = components.get(to.component);
      if (!target) {
        add('UNKNOWN_COMPONENT', `Target ${to.component} is not declared`, [...at, 'to', 'component']);
      } else {
        const list = edges.get(binding.from.component) ?? new Set();
        list.add(to.component);
        edges.set(binding.from.component, list);
      }
      if (to.mapping.kind === 'identity') add('COMMAND_IDENTITY', 'Command targets cannot use map: identity', [...at, 'to', 'map']);
      const targetInstance = target && catalog ? componentInstance(catalog, target.component, expected) : null;
      if (targetInstance?.status === 'available') {
        const command = targetInstance.commands.find(value => value.commandName === to.command);
        if (!command) add('UNKNOWN_TARGET_COMMAND', `${target.component.machine} has no command ${to.command}`, [...at, 'to', 'command']);
        if (to.mapping.kind === 'raw' && targetInstance.rawBodyTarget === 'forbidden-evidence') {
          add('BINDING_EVIDENCE_UNSATISFIABLE', 'Raw bodies cannot target a machine with evidence-bearing commands',
            [...at, 'to', 'rawBody']);
        }
        if (command && to.mapping.kind === 'fields') {
          const assigned = new Map(to.mapping.assignments.map(assignment => [assignment.field, assignment.source]));
          for (const field of command.fields) {
            const mapped = assigned.get(field.name);
            if (!mapped && (field.required || command.layout !== 'MAP')) {
              add('MISSING_TARGET_FIELD', `Map ${field.name}`, [...at, 'to', 'map'], 'advisory');
            }
            if (mapped && field.role === 'evidence' && mapped.kind !== 'field') {
              add('BINDING_EVIDENCE_UNSATISFIABLE', `${field.name} is evidence: copy it directly from an event field`,
                [...at, 'to', 'map', field.name]);
            }
            const type = mapped ? sourceType(mapped, fields, functions) : null;
            if (mapped && type && type !== field.type) {
              add('BINDING_TYPE_MISMATCH', `${field.name} needs ${field.type}, not ${type}`, [...at, 'to', 'map', field.name]);
            }
          }
          for (const field of assigned.keys()) {
            if (!command.fields.some(value => value.name === field)) {
              add('UNKNOWN_TARGET_FIELD', `${to.command} has no field ${field}`, [...at, 'to', 'map', field]);
            }
          }
          if (command.layout === 'RAW_BYTES') add('RAW_TARGET_REQUIRES_RAW_MAPPING', 'This command needs rawBody', [...at, 'to']);
        }
      }
    } else {
      if (!NAME.test(to.type)) add('INVALID_EFFECT_TYPE', 'Effect types use letters, digits, ".", "_" or "-"', [...targetAt, 'type']);
      if (to.gate !== null && !['app-final', 'l1-final'].includes(to.gate)) add('INVALID_EFFECT', 'gate is app-final or l1-final', [...targetAt, 'gate']);
      if (to.result !== null && !['none', 'chain'].includes(to.result)) add('INVALID_EFFECT', 'result is none or chain', [...targetAt, 'result']);
      if ((to.result ?? 'none') === 'none' && (to.expiryBlocks ?? 0n) !== 0n) {
        add('INVALID_EFFECT', 'expiryBlocks requires result: chain', [...targetAt, 'expiryBlocks']);
      }
    }
    if (to.mapping.kind === 'raw' && fields && fields.get(to.mapping.field) !== 'bytes') {
      add('BINDING_TYPE_MISMATCH', 'rawBody needs a bytes event field', [...targetAt, 'rawBody']);
    }
    if (to.mapping.kind === 'fields') {
      if (!to.mapping.assignments.length || to.mapping.assignments.length > 16) {
        add('MAPPING_FIELD_LIMIT', 'A field map needs 1 to 16 assignments', [...targetAt, 'map']);
      }
      for (const assignment of to.mapping.assignments) {
        checkSource(assignment.source, [...targetAt, 'map', assignment.field], fields, functions, add, 0);
      }
    }
  });
  const visiting = new Set();
  const done = new Set();
  const cyclic = node => {
    if (done.has(node)) return false;
    if (visiting.has(node)) return true;
    visiting.add(node);
    for (const next of edges.get(node) ?? []) if (cyclic(next)) return true;
    visiting.delete(node);
    done.add(node);
    return false;
  };
  if ([...edges.keys()].some(node => cyclic(node))) add('CYCLIC_BINDING_GRAPH', 'Command bindings form a cycle', ['bindings']);
  checkRules(draft, catalog, expected, components, bindingIds, add);
  for (const limit of draft.limits ?? []) {
    const bound = catalog?.language.limits.find(value => value.name === limit.name);
    if (!LIMIT_NAMES.includes(limit.name)) add('UNKNOWN_FIELD', 'Unknown limit', ['limits', limit.name]);
    else if (bound && (limit.value < BigInt(bound.minimum) || limit.value > BigInt(bound.maximum))) {
      add('LIMIT_RANGE', `${limit.name} must be between ${bound.minimum} and ${bound.maximum}`, ['limits', limit.name]);
    }
  }
  hiddenCharacters(draft, (segments, what) => add('HIDDEN_CHARACTERS',
    `${what} contains invisible or bidirectional control characters that can make it read differently than it runs`,
    segments));
  if (draft.workflowFromHeight !== null && draft.workflowFromHeight < 1n) {
    add('INVALID_HEIGHT', 'workflowFromHeight must be at least 1', ['workflowFromHeight']);
  }
  return diagnostics;
}

/**
 * True when text contains characters that can hide, reorder or disguise it (see `visibleText`). With `multiline`,
 * line feeds and tabs are ordinary text, as they are for display.
 */
export function hasHiddenCharacters(text, {multiline = false} = {}) {
  for (const character of String(text)) {
    if (isHidden(character) && !(multiline && (character === '\n' || character === '\t'))) return true;
  }
  return false;
}

/** Reports every authored text (ids, names, events, expressions, literals) that contains hidden characters. */
function hiddenCharacters(draft, report) {
  const text = (value, segments, what, multiline = false) => {
    if (typeof value === 'string' && hasHiddenCharacters(value, {multiline})) report(segments, what);
  };
  const literal = (value, segments, what) => { if (value?.type === 'text') text(value.value, segments, what, true); };
  const source = (value, segments) => {
    if (!value) return;
    if (['field', 'context', 'command', 'param', 'config', 'fact'].includes(value.kind)) {
      text(value.name, [...segments, value.kind], 'A field name');
    }
    else if (value.kind === 'literal') literal(value.value, [...segments, 'literal'], 'A literal');
    else if (value.kind === 'expr') text(value.text, [...segments, 'expr'], 'An expression', true);
    else {
      text(value.fn, [...segments, 'fn'], 'A function name');
      value.args.forEach((arg, index) => source(arg, [...segments, 'args', index]));
    }
  };
  draft.components.forEach((component, index) => {
    const at = ['components', index];
    text(component.id, [...at, 'id'], 'A component id');
    text(component.machine, [...at, 'machine'], 'A machine selector');
    text(component.topic, [...at, 'topic'], 'A topic');
    (component.config ?? []).forEach(setting => {
      text(setting.name, [...at, 'config', setting.name], 'A setting name');
      literal(setting.value, [...at, 'config', setting.name], 'A setting value');
    });
    (component.admission ?? []).forEach((attachment, a) => {
      text(attachment.rule, [...at, 'admission', a, 'rule'], 'A rule id');
      (attachment.params ?? []).forEach(entry => {
        text(entry.name, [...at, 'admission', a, 'params', entry.name], 'A parameter name');
        literal(entry.value, [...at, 'admission', a, 'params', entry.name], 'A parameter value');
      });
    });
  });
  (draft.rules ?? []).forEach((rule, index) => {
    const at = ['rules', index];
    text(rule.id, [...at, 'id'], 'A rule id');
    text(rule.command, [...at, 'command'], 'A command');
    text(rule.deny, [...at, 'deny'], 'A deny code');
    (rule.reads ?? []).forEach(read => {
      text(read.name, [...at, 'reads', read.name], 'A read name');
      text(read.component, [...at, 'reads', read.name, 'component'], 'A read component');
      if (read.namespace !== null) text(read.namespace, [...at, 'reads', read.name, 'namespace'], 'A namespace', true);
      source(read.key, [...at, 'reads', read.name, 'key']);
    });
    (rule.params ?? []).forEach(parameter => {
      text(parameter.name, [...at, 'params', parameter.name], 'A parameter name');
      text(parameter.type, [...at, 'params', parameter.name, 'type'], 'A parameter type');
      if (parameter.default !== null) literal(parameter.default, [...at, 'params', parameter.name, 'default'], 'A default');
    });
    rule.require.forEach((clause, c) => {
      const clauseAt = [...at, 'require', c];
      if (clause.kind === 'expr') text(clause.text, [...clauseAt, 'expr'], 'An expression', true);
      else {
        text(clause.component, [...clauseAt, 'lookup', 'component'], 'A lookup component');
        source(clause.key, [...clauseAt, 'lookup', 'key']);
        source(clause.operand, [...clauseAt, 'lookup', 'eq']);
      }
    });
  });
  draft.bindings.forEach((binding, index) => {
    const at = ['bindings', index];
    text(binding.id, [...at, 'id'], 'A binding id');
    text(binding.from.component, [...at, 'from', 'component'], 'A source component');
    text(binding.from.event, [...at, 'from', 'event'], 'An event id');
    (binding.when ?? []).forEach((clause, c) => {
      const clauseAt = [...at, 'when', c];
      if (clause.kind === 'expr') text(clause.text, [...clauseAt, 'expr'], 'An expression', true);
      else if (clause.kind === 'lookup') {
        text(clause.component, [...clauseAt, 'lookup', 'component'], 'A lookup participant');
        source(clause.key, [...clauseAt, 'lookup', 'key']);
        source(clause.operand, [...clauseAt, 'lookup', 'eq']);
      } else {
        text(clause.field, [...clauseAt, 'field'], 'A field name');
        const operands = Array.isArray(clause.operand) ? clause.operand : [clause.operand];
        operands.forEach(operand => literal(operand, [...clauseAt, clause.operator], 'A literal'));
      }
    });
    const to = binding.to;
    const targetAt = to.kind === 'effect' ? [...at, 'to', 'effect'] : [...at, 'to'];
    if (to.kind === 'effect') text(to.type, [...targetAt, 'type'], 'An effect type');
    else { text(to.component, [...at, 'to', 'component'], 'A target component'); text(to.command, [...at, 'to', 'command'], 'A command'); }
    if (to.mapping.kind === 'raw') text(to.mapping.field, [...targetAt, 'rawBody'], 'A field name');
    if (to.mapping.kind === 'fields') {
      to.mapping.assignments.forEach(assignment => {
        text(assignment.field, [...targetAt, 'map', assignment.field], 'A mapped field name');
        source(assignment.source, [...targetAt, 'map', assignment.field]);
      });
    }
  });
}

function checkSource(source, segments, fields, functions, add, depth) {
  if (depth > 2) add('FUNCTION_NESTING_LIMIT', 'Functions nest at most two levels', segments);
  if (source.kind === 'field' && fields && !fields.has(source.name)) {
    add('UNKNOWN_EVENT_FIELD', `The event has no field ${source.name}`, segments);
  }
  if (source.kind === 'context' && !CONTEXT_FIELDS.has(source.name)) {
    add('RULE_FIELD_UNKNOWN', `The step context has no field ${source.name}`, segments);
  }
  if (['command', 'param', 'config', 'fact'].includes(source.kind)) {
    add('RULE_SCOPE_INVALID', `A binding cannot read ${source.kind} values; only admission rules can`, [...segments, source.kind]);
  }
  if (source.kind === 'fn') {
    const signature = functions.get(source.fn);
    if (functions.size && !signature) add('UNKNOWN_FUNCTION', `Unknown function ${source.fn}`, [...segments, 'fn']);
    else if (signature && (source.args.length < signature.minArguments || source.args.length > signature.maxArguments)) {
      add('FUNCTION_ARITY', `${source.fn} takes ${signature.minArguments} to ${signature.maxArguments} arguments`, segments);
    }
    source.args.forEach((arg, index) => checkSource(arg, [...segments, 'args', index], fields, functions, add, depth + 1));
  }
  if (source.kind === 'expr' && !source.text.trim()) add('EXPRESSION_EMPTY', 'Expression text is empty', [...segments, 'expr']);
}

/** Rule lookup sources read rule scopes only: step context, the selected command, parameters, config or facts. */
function checkRuleSource(rule, source, segments, add, depth) {
  if (!source) return;
  if (depth > 2) add('FUNCTION_NESTING_LIMIT', 'Functions nest at most two levels', segments);
  if (source.kind === 'field') add('RULE_SCOPE_INVALID', 'A rule cannot read event fields', [...segments, 'field']);
  if (source.kind === 'command' && rule.command === null) {
    add('RULE_SCOPE_INVALID', 'Only a rule that selects a command can read command fields', [...segments, 'command']);
  }
  if (source.kind === 'context' && !CONTEXT_FIELDS.has(source.name)) {
    add('RULE_FIELD_UNKNOWN', `The step context has no field ${source.name}`, segments);
  }
  if (source.kind === 'param' && !(rule.params ?? []).some(parameter => parameter.name === source.name)) {
    add('RULE_FIELD_UNKNOWN', `${rule.id} declares no parameter ${source.name}`, segments);
  }
  if (source.kind === 'expr' && !source.text.trim()) add('EXPRESSION_EMPTY', 'Expression text is empty', [...segments, 'expr']);
  if (source.kind === 'fn') source.args.forEach((arg, index) => checkRuleSource(rule, arg, [...segments, 'args', index], add, depth + 1));
}

/** Fact names an expression or lookup source reads, found by reference text (advisory; the compiler decides). */
function factReferences(clause) {
  const names = new Set();
  const visit = source => {
    if (!source) return;
    if (source.kind === 'fact') names.add(source.name);
    if (source.kind === 'expr') for (const match of source.text.matchAll(/\bfacts\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)/g)) names.add(match[1]);
    if (source.kind === 'fn') source.args.forEach(visit);
  };
  if (clause.kind === 'expr') visit({kind: 'expr', text: clause.text});
  if (clause.kind === 'lookup') { visit(clause.key); visit(clause.operand); }
  return names;
}

/**
 * Advisory ADR-031.3 checks: rule declarations, attachments, parameter values, command selectors and fact names
 * against the loaded catalog. They mirror compiler diagnostics by code and never establish validity.
 */
function checkRules(draft, catalog, expected, components, bindingIds, add) {
  const rules = new Map();
  (draft.rules ?? []).forEach((rule, index) => {
    const at = ['rules', index];
    if (!ID.test(rule.id)) add('INVALID_ID', 'Rule ids use lowercase letters, digits and hyphens', [...at, 'id']);
    if (rules.has(rule.id)) add('RULE_DUPLICATE', `Rule ${rule.id} is declared twice`, [...at, 'id']);
    else if (bindingIds.has(rule.id)) {
      add('DOCUMENT_STRUCTURE_INVALID', `${rule.id} is both a rule and a binding id; ids are unique across both`,
        [...at, 'id']);
    } else rules.set(rule.id, {rule, index});
    if (!DENY_CODE.test(rule.deny) || rule.deny.startsWith('ADMISSION_RULE_')) {
      add('RULE_DENY_CODE', 'Deny codes are upper-case identifiers that do not start with ADMISSION_RULE_', [...at, 'deny']);
    }
    for (const parameter of rule.params ?? []) {
      const where = [...at, 'params', parameter.name];
      if (!IDENTIFIER.test(parameter.name) || RESERVED.has(parameter.name)) {
        add('RULE_PARAMETER_TYPE', `${parameter.name} is not a usable parameter name`, where);
      }
      if (!PARAMETER_TYPES.includes(parameter.type)) add('RULE_PARAMETER_TYPE', `Unknown parameter type ${parameter.type}`, [...where, 'type']);
      else if (parameter.default !== null && parameter.default.type !== (parameter.type === 'binding' ? 'text' : parameter.type)) {
        add('RULE_PARAMETER_TYPE', `The default of ${parameter.name} must be ${parameter.type}`, [...where, 'default']);
      }
    }
    if ((rule.reads ?? []).length > 4) add('EXPECTED_OBJECT', 'A rule declares at most 4 reads', [...at, 'reads']);
    for (const read of rule.reads ?? []) {
      const where = [...at, 'reads', read.name];
      if (!IDENTIFIER.test(read.name) || RESERVED.has(read.name)) {
        add('DOCUMENT_STRUCTURE_INVALID', `${read.name} is not a usable read name`, where);
      }
      if (!components.has(read.component)) {
        add('RULE_READ_UNKNOWN_COMPONENT', `Read component ${read.component} is not declared`, [...where, 'component']);
      }
      checkRuleSource(rule, read.key, [...where, 'key'], add, 0);
    }
    if (!rule.require.length || rule.require.length > 8) add('CLAUSE_LIMIT', 'A rule needs 1 to 8 clauses', [...at, 'require']);
    rule.require.forEach((clause, c) => {
      const clauseAt = [...at, 'require', c];
      if (clause.kind === 'expr' && !clause.text.trim()) add('EXPRESSION_EMPTY', 'Expression text is empty', [...clauseAt, 'expr']);
      if (clause.kind === 'lookup' && !components.has(clause.component)) {
        add('RULE_LOOKUP_COMPONENT_UNKNOWN', `Lookup component ${clause.component} is not declared`, [...clauseAt, 'lookup', 'component']);
      }
      if (clause.kind === 'lookup') {
        checkRuleSource(rule, clause.key, [...clauseAt, 'lookup', 'key'], add, 0);
        if (clause.operand) checkRuleSource(rule, clause.operand, [...clauseAt, 'lookup', 'eq'], add, 0);
      }
    });
  });
  if (rules.size > 64) add('RULE_LIMIT', 'At most 64 rules are allowed', ['rules']);
  const limit = draft.limits?.find(value => value.name === 'maxRulesPerComponent')?.value
    ?? BigInt(catalog?.language.limits.find(value => value.name === 'maxRulesPerComponent')?.default ?? 4);
  const attached = new Set();
  draft.components.forEach((component, index) => {
    const attachments = component.admission ?? [];
    if (BigInt(attachments.length) > limit) {
      add('DOCUMENT_STRUCTURE_INVALID', `A component attaches at most ${limit} rules (maxRulesPerComponent)`,
        ['components', index, 'admission']);
    }
    const instance = catalog ? componentInstance(catalog, component, expected) : null;
    const available = instance?.status === 'available' ? instance : null;
    const seen = new Set();
    attachments.forEach((attachment, a) => {
      const at = ['components', index, 'admission', a];
      const entry = rules.get(attachment.rule);
      if (!entry) { add('RULE_UNKNOWN', `No rule ${attachment.rule} is declared`, [...at, 'rule']); return; }
      const {rule, index: ruleIndex} = entry;
      if (seen.has(rule.id)) add('RULE_DUPLICATE', `${rule.id} is attached twice`, [...at, 'rule']);
      seen.add(rule.id);
      attached.add(rule.id);
      const values = new Map((attachment.params ?? []).map(value => [value.name, value.value]));
      for (const name of values.keys()) {
        if (!(rule.params ?? []).some(parameter => parameter.name === name)) {
          add('RULE_PARAMETER_UNKNOWN', `${rule.id} declares no parameter ${name}`, [...at, 'params', name]);
        }
      }
      for (const parameter of rule.params ?? []) {
        const value = values.get(parameter.name);
        if (value === undefined) {
          if (parameter.default === null) {
            add('RULE_PARAMETER_MISSING', `Supply ${parameter.name}`, [...at, 'params', parameter.name]);
          }
          continue;
        }
        if (value.type !== (parameter.type === 'binding' ? 'text' : parameter.type)) {
          add('RULE_PARAMETER_TYPE', `${parameter.name} needs ${parameter.type}`, [...at, 'params', parameter.name]);
        } else if (parameter.type === 'binding' && !draft.bindings.some(binding => binding.id === value.value
          && binding.to.kind === 'command' && binding.to.component === component.id)) {
          add('RULE_PARAMETER_TYPE', `${parameter.name} must name a binding that targets ${component.id}`,
            [...at, 'params', parameter.name]);
        }
      }
      if (!available) return;
      if (rule.command !== null && !available.commandSelectable) {
        add('RULE_COMMAND_UNSELECTABLE', `${rule.id} selects a command, but ${component.machine} commands cannot be `
          + `selected: ${available.unselectableReason}`, ['rules', ruleIndex, 'command']);
      } else if (rule.command !== null && !available.commands.some(command => command.commandName === rule.command)) {
        add('RULE_COMMAND_UNKNOWN', `${component.machine} has no command ${rule.command}`, ['rules', ruleIndex, 'command']);
      }
      const facts = new Set((available.ruleFacts ?? []).map(fact => fact.name));
      rule.require.forEach((clause, c) => {
        for (const name of factReferences(clause)) {
          if (!facts.has(name)) {
            add('RULE_FACT_UNKNOWN', `${component.machine} declares no fact ${name}`, ['rules', ruleIndex, 'require', c]);
          }
        }
      });
      // Read keys may read facts too (ADR-031.4).
      for (const read of rule.reads ?? []) {
        for (const name of factReferences({kind: 'lookup', key: read.key, operand: null})) {
          if (!facts.has(name)) {
            add('RULE_FACT_UNKNOWN', `${component.machine} declares no fact ${name}`,
              ['rules', ruleIndex, 'reads', read.name, 'key']);
          }
        }
      }
    });
  });
  rules.forEach(({index}, id) => {
    if (!attached.has(id)) add('RULE_UNATTACHED', `${id} is not attached to any component`, ['rules', index]);
  });
  // ADR-031.4: a read names a namespace its component's kernel views (the default namespace is "").
  if (catalog) {
    rules.forEach(({rule, index}) => {
      for (const read of rule.reads ?? []) {
        const component = components.get(read.component)?.component;
        const instance = component ? componentInstance(catalog, component, expected) : null;
        if (instance?.status !== 'available') continue;
        const namespace = read.namespace ?? '';
        // A kernel that declares no view has none to read.
        if (!(instance.ruleValueViews ?? []).some(view => view.namespace === namespace)) {
          add('RULE_READ_UNKNOWN_NAMESPACE', `${component.machine} declares no value view for namespace '${namespace}'`,
            read.namespace === null ? ['rules', index, 'reads', read.name] : ['rules', index, 'reads', read.name,
              'namespace']);
        }
      }
    });
  }
}

export {configurationKey};
