/**
 * Pure edit operations on binding drafts (ADR-031.2 §5.1). Every operation returns a new draft and never
 * reorders authored lists implicitly, inserts defaults, rewrites expressions or invents evidence. Graph gestures
 * must call these same operations; layout never changes a draft.
 */
import {cloneDraft} from './binding-draft.mjs';

const clone = cloneDraft;

function checkIndex(list, index, label) {
  if (!Number.isInteger(index) || index < 0 || index >= list.length) throw new RangeError(`${label} index is out of range`);
}

/** Moves one list entry by `delta` positions; out-of-range moves are rejected rather than clamped. */
function move(list, index, delta, label) {
  checkIndex(list, index, label);
  const target = index + delta;
  if (!Number.isInteger(target) || target < 0 || target >= list.length) throw new RangeError(`${label} cannot move further`);
  const [item] = list.splice(index, 1);
  list.splice(target, 0, item);
}

export function addComponent(draft, component) {
  const next = clone(draft);
  next.components.push({id: component.id, machine: component.machine, topic: component.topic ?? null,
    config: component.config ?? null, maxEffectsPerBlock: component.maxEffectsPerBlock ?? null,
    fromHeight: component.fromHeight ?? null, admission: component.admission ?? null});
  return next;
}

/** Updates authored component fields except `id`; renaming is a separate, previewed operation. */
export function updateComponent(draft, index, patch) {
  if ('id' in patch) throw new Error('Use renameComponent to change a component id');
  const next = clone(draft);
  checkIndex(next.components, index, 'Component');
  Object.assign(next.components[index], structuredClone(patch));
  return next;
}

export function removeComponent(draft, index) {
  const next = clone(draft);
  checkIndex(next.components, index, 'Component');
  next.components.splice(index, 1);
  return next;
}

export function moveComponent(draft, index, delta) {
  const next = clone(draft);
  move(next.components, index, delta, 'Component');
  return next;
}

/**
 * Every authored place that names a component: binding sources, command targets and lookup participants.
 *
 * @returns {Array<{segments: Array<string|number>, bindingId: string|null, role: string}>} binding roles are
 *          rewritten by {@link renameComponent}; `configuration-mention` entries are shown but never rewritten
 */
export function componentReferences(draft, componentId) {
  const references = [];
  const prefix = draft.wrapped ? ['composite'] : [];
  draft.bindings.forEach((binding, index) => {
    const at = [...prefix, 'bindings', index];
    if (binding.from.component === componentId) {
      references.push({segments: [...at, 'from', 'component'], bindingId: binding.id, role: 'source'});
    }
    (binding.when ?? []).forEach((clause, c) => {
      if (clause.kind === 'lookup' && clause.component === componentId) {
        references.push({segments: [...at, 'when', c, 'lookup', 'component'], bindingId: binding.id, role: 'lookup'});
      }
    });
    if (binding.to.kind === 'command' && binding.to.component === componentId) {
      references.push({segments: [...at, 'to', 'component'], bindingId: binding.id, role: 'target'});
    }
  });
  // ADR-031.3: rule lookups name components too; a rename rewrites them like binding lookups.
  (draft.rules ?? []).forEach((rule, index) => {
    rule.require.forEach((clause, c) => {
      if (clause.kind === 'lookup' && clause.component === componentId) {
        references.push({segments: [...prefix, 'rules', index, 'require', c, 'lookup', 'component'], bindingId: null,
          ruleId: rule.id, role: 'rule-lookup'});
      }
    });
    // ADR-031.4: so do rule reads.
    for (const read of rule.reads ?? []) {
      if (read.component === componentId) {
        references.push({segments: [...prefix, 'rules', index, 'reads', read.name, 'component'], bindingId: null,
          ruleId: rule.id, role: 'rule-read'});
      }
    }
  });
  // Machine settings are opaque to Studio: a text setting equal to the id may name the component (for example a
  // governed participant), but a rename never rewrites configuration. Show it so the author decides explicitly.
  draft.components.forEach((component, index) => {
    (component.config ?? []).forEach(setting => {
      if (setting.value.type === 'text' && setting.value.value === componentId) {
        references.push({segments: [...prefix, 'components', index, 'config', setting.name], bindingId: null,
          role: 'configuration-mention', changedByRename: false});
      }
    });
  });
  return references;
}

/**
 * Renames a component and every reference to it. Callers show `componentReferences` first; the rename is never
 * applied implicitly. Renaming changes the state namespace and default topic of a component, so it changes the
 * committed profile of a deployed chain.
 */
export function renameComponent(draft, index, newId) {
  const next = clone(draft);
  checkIndex(next.components, index, 'Component');
  const oldId = next.components[index].id;
  next.components[index].id = newId;
  for (const binding of next.bindings) {
    if (binding.from.component === oldId) binding.from.component = newId;
    for (const clause of binding.when ?? []) if (clause.kind === 'lookup' && clause.component === oldId) clause.component = newId;
    if (binding.to.kind === 'command' && binding.to.component === oldId) binding.to.component = newId;
  }
  for (const rule of next.rules ?? []) {
    for (const clause of rule.require) if (clause.kind === 'lookup' && clause.component === oldId) clause.component = newId;
    for (const read of rule.reads ?? []) if (read.component === oldId) read.component = newId;
  }
  return next;
}

export function addBinding(draft, binding) {
  const next = clone(draft);
  next.bindings.push(structuredClone(binding));
  return next;
}

export function updateBinding(draft, index, patch) {
  if ('id' in patch) throw new Error('Use renameBinding to change a binding id');
  const next = clone(draft);
  checkIndex(next.bindings, index, 'Binding');
  Object.assign(next.bindings[index], structuredClone(patch));
  return next;
}

/**
 * Binding ids appear in receipts and derived message ids, so a rename is explicit even without references. Attachment
 * parameters of type `binding` that name the old id are rewritten too (ADR-031.3 arrival rules).
 */
export function renameBinding(draft, index, newId) {
  const next = clone(draft);
  checkIndex(next.bindings, index, 'Binding');
  const oldId = next.bindings[index].id;
  next.bindings[index].id = newId;
  for (const reference of bindingParameterReferences(next, oldId)) reference.value.value = newId;
  return next;
}

/** Draft paths of the attachment parameters that {@link renameBinding} rewrites for `bindingId`. */
export function bindingReferences(draft, bindingId) {
  const prefix = draft.wrapped ? ['composite'] : [];
  const named = new Set(bindingParameterReferences(draft, bindingId));
  const references = [];
  draft.components.forEach((component, index) => (component.admission ?? []).forEach((attachment, a) => {
    for (const entry of attachment.params ?? []) {
      if (named.has(entry)) {
        references.push({segments: [...prefix, 'components', index, 'admission', a, 'params', entry.name],
          componentId: component.id, ruleId: attachment.rule});
      }
    }
  }));
  return references;
}

/** Attachment parameter values that name a binding through a `binding`-typed rule parameter. */
function bindingParameterReferences(draft, bindingId) {
  const types = new Map((draft.rules ?? []).map(rule => [rule.id,
    new Map((rule.params ?? []).map(parameter => [parameter.name, parameter.type]))]));
  const references = [];
  for (const component of draft.components) {
    for (const attachment of component.admission ?? []) {
      for (const entry of attachment.params ?? []) {
        if (types.get(attachment.rule)?.get(entry.name) === 'binding' && entry.value.type === 'text'
          && entry.value.value === bindingId) references.push(entry);
      }
    }
  }
  return references;
}

// ---------------------------------------------------------------------------------------------------------------
// ADR-031.3 admission rules and attachments
// ---------------------------------------------------------------------------------------------------------------

export function addRule(draft, rule) {
  const next = clone(draft);
  next.rules = [...(next.rules ?? []), structuredClone(rule)];
  return next;
}

/** Updates authored rule fields except `id`; renaming rewrites attachments and is a separate operation. */
export function updateRule(draft, index, patch) {
  if ('id' in patch) throw new Error('Use renameRule to change a rule id');
  const next = clone(draft);
  checkIndex(next.rules ?? [], index, 'Rule');
  Object.assign(next.rules[index], structuredClone(patch));
  return next;
}

/** Every component attachment that names a rule. */
export function ruleReferences(draft, ruleId) {
  const prefix = draft.wrapped ? ['composite'] : [];
  const references = [];
  draft.components.forEach((component, index) => (component.admission ?? []).forEach((attachment, a) => {
    if (attachment.rule === ruleId) {
      references.push({segments: [...prefix, 'components', index, 'admission', a, 'rule'], componentId: component.id,
        role: 'attachment'});
    }
  }));
  return references;
}

/** Renames a rule and every attachment of it. Rule ids appear in receipts, so the rename changes the program. */
export function renameRule(draft, index, newId) {
  const next = clone(draft);
  checkIndex(next.rules ?? [], index, 'Rule');
  const oldId = next.rules[index].id;
  next.rules[index].id = newId;
  for (const component of next.components) {
    for (const attachment of component.admission ?? []) if (attachment.rule === oldId) attachment.rule = newId;
  }
  return next;
}

/** Removes the rule only; attachments that still name it are reported by the checks, never removed silently. */
export function removeRule(draft, index) {
  const next = clone(draft);
  checkIndex(next.rules ?? [], index, 'Rule');
  next.rules.splice(index, 1);
  return next;
}

/** Authored rule order does not change the program (rules are committed sorted by id); it is kept as written. */
export function moveRule(draft, index, delta) {
  const next = clone(draft);
  move(next.rules ?? [], index, delta, 'Rule');
  return next;
}

export function addRuleClause(draft, ruleIndex, clause) {
  const next = clone(draft);
  checkIndex(next.rules ?? [], ruleIndex, 'Rule');
  next.rules[ruleIndex].require.push(structuredClone(clause));
  return next;
}

export function updateRuleClause(draft, ruleIndex, clauseIndex, clause) {
  const next = clone(draft);
  checkIndex(next.rules ?? [], ruleIndex, 'Rule');
  checkIndex(next.rules[ruleIndex].require, clauseIndex, 'Clause');
  next.rules[ruleIndex].require[clauseIndex] = structuredClone(clause);
  return next;
}

export function removeRuleClause(draft, ruleIndex, clauseIndex) {
  const next = clone(draft);
  checkIndex(next.rules ?? [], ruleIndex, 'Rule');
  checkIndex(next.rules[ruleIndex].require, clauseIndex, 'Clause');
  next.rules[ruleIndex].require.splice(clauseIndex, 1);
  return next;
}

/** Clause order is evaluation order: the first clause that does not hold decides the refusal. */
export function moveRuleClause(draft, ruleIndex, clauseIndex, delta) {
  const next = clone(draft);
  checkIndex(next.rules ?? [], ruleIndex, 'Rule');
  move(next.rules[ruleIndex].require, clauseIndex, delta, 'Clause');
  return next;
}

/** Declares or replaces one rule parameter, keeping its authored position; `null` removes it. */
export function setRuleParameter(draft, ruleIndex, name, declaration) {
  const next = clone(draft);
  checkIndex(next.rules ?? [], ruleIndex, 'Rule');
  const rule = next.rules[ruleIndex];
  const params = rule.params ?? [];
  const existing = params.find(parameter => parameter.name === name);
  if (declaration === null) rule.params = params.filter(parameter => parameter.name !== name);
  else if (existing) Object.assign(existing, structuredClone(declaration), {name});
  else rule.params = [...params, {name, type: declaration.type, default: declaration.default ?? null}];
  return next;
}

/**
 * Declares, replaces (`read` with a new `component`, `namespace` or `key`) or removes (`read === null`) one ADR-031.4
 * read of a rule. Reads keep their authored order; the compiler sorts them by name.
 */
export function setRuleRead(draft, ruleIndex, name, read) {
  const next = clone(draft);
  checkIndex(next.rules ?? [], ruleIndex, 'Rule');
  const rule = next.rules[ruleIndex];
  const reads = rule.reads ?? [];
  const existing = reads.find(value => value.name === name);
  if (read === null) {
    const remaining = reads.filter(value => value.name !== name);
    rule.reads = remaining.length ? remaining : null;
  } else if (existing) Object.assign(existing, structuredClone(read), {name});
  else rule.reads = [...reads, {name, component: read.component, namespace: read.namespace ?? null,
    key: structuredClone(read.key)}];
  return next;
}

export function addAttachment(draft, componentIndex, attachment) {
  const next = clone(draft);
  checkIndex(next.components, componentIndex, 'Component');
  const component = next.components[componentIndex];
  component.admission = [...(component.admission ?? []), structuredClone(attachment)];
  return next;
}

export function removeAttachment(draft, componentIndex, attachmentIndex) {
  const next = clone(draft);
  checkIndex(next.components, componentIndex, 'Component');
  checkIndex(next.components[componentIndex].admission ?? [], attachmentIndex, 'Attachment');
  next.components[componentIndex].admission.splice(attachmentIndex, 1);
  return next;
}

/** Attachment order is evaluation order within each slot, and part of the committed program. */
export function moveAttachment(draft, componentIndex, attachmentIndex, delta) {
  const next = clone(draft);
  checkIndex(next.components, componentIndex, 'Component');
  move(next.components[componentIndex].admission ?? [], attachmentIndex, delta, 'Attachment');
  return next;
}

/** Sets (value) or removes (null) one attachment parameter value, keeping its authored position. */
export function setAttachmentParameter(draft, componentIndex, attachmentIndex, name, value) {
  const next = clone(draft);
  checkIndex(next.components, componentIndex, 'Component');
  checkIndex(next.components[componentIndex].admission ?? [], attachmentIndex, 'Attachment');
  const attachment = next.components[componentIndex].admission[attachmentIndex];
  const params = attachment.params ?? [];
  const existing = params.find(entry => entry.name === name);
  if (value === null) attachment.params = params.filter(entry => entry.name !== name);
  else if (existing) existing.value = structuredClone(value);
  else attachment.params = [...params, {name, value: structuredClone(value)}];
  return next;
}

export function removeBinding(draft, index) {
  const next = clone(draft);
  checkIndex(next.bindings, index, 'Binding');
  next.bindings.splice(index, 1);
  return next;
}

/** Binding order is execution order and part of the committed program. */
export function moveBinding(draft, index, delta) {
  const next = clone(draft);
  move(next.bindings, index, delta, 'Binding');
  return next;
}

export function addClause(draft, bindingIndex, clause) {
  const next = clone(draft);
  checkIndex(next.bindings, bindingIndex, 'Binding');
  const binding = next.bindings[bindingIndex];
  binding.when = [...(binding.when ?? []), structuredClone(clause)];
  return next;
}

export function updateClause(draft, bindingIndex, clauseIndex, clause) {
  const next = clone(draft);
  checkIndex(next.bindings, bindingIndex, 'Binding');
  checkIndex(next.bindings[bindingIndex].when ?? [], clauseIndex, 'Clause');
  next.bindings[bindingIndex].when[clauseIndex] = structuredClone(clause);
  return next;
}

/**
 * Removes one clause. Removing the last clause leaves an explicit `when: []`, which compiles exactly like an absent
 * `when`; the list is not deleted so that the edit changes only what the author removed.
 */
export function removeClause(draft, bindingIndex, clauseIndex) {
  const next = clone(draft);
  checkIndex(next.bindings, bindingIndex, 'Binding');
  checkIndex(next.bindings[bindingIndex].when ?? [], clauseIndex, 'Clause');
  next.bindings[bindingIndex].when.splice(clauseIndex, 1);
  return next;
}

export function moveClause(draft, bindingIndex, clauseIndex, delta) {
  const next = clone(draft);
  checkIndex(next.bindings, bindingIndex, 'Binding');
  move(next.bindings[bindingIndex].when ?? [], clauseIndex, delta, 'Clause');
  return next;
}

export function setTarget(draft, bindingIndex, target) {
  const next = clone(draft);
  checkIndex(next.bindings, bindingIndex, 'Binding');
  next.bindings[bindingIndex].to = structuredClone(target);
  return next;
}

/** Sets one field assignment, preserving its authored position; new fields are appended. */
export function setAssignment(draft, bindingIndex, field, source) {
  const next = clone(draft);
  checkIndex(next.bindings, bindingIndex, 'Binding');
  const mapping = next.bindings[bindingIndex].to.mapping;
  if (mapping.kind !== 'fields') throw new Error('Mapping is not a field map');
  const existing = mapping.assignments.find(assignment => assignment.field === field);
  if (existing) existing.source = structuredClone(source);
  else mapping.assignments.push({field, source: structuredClone(source)});
  return next;
}

export function removeAssignment(draft, bindingIndex, field) {
  const next = clone(draft);
  checkIndex(next.bindings, bindingIndex, 'Binding');
  const mapping = next.bindings[bindingIndex].to.mapping;
  if (mapping.kind !== 'fields') throw new Error('Mapping is not a field map');
  mapping.assignments = mapping.assignments.filter(assignment => assignment.field !== field);
  return next;
}

/**
 * Authors (value) or removes (null) one committed limit; removal shows the catalog default without writing it, and
 * removing the last authored limit omits the limits block.
 */
export function setLimit(draft, name, value) {
  const next = clone(draft);
  const limits = next.limits ?? [];
  const existing = limits.find(limit => limit.name === name);
  if (value === null) next.limits = limits.filter(limit => limit.name !== name);
  else if (existing) existing.value = value;
  else next.limits = [...limits, {name, value}];
  if (next.limits !== null && !next.limits.length) next.limits = null;
  return next;
}

export function setWorkflowFromHeight(draft, value) {
  const next = clone(draft);
  next.workflowFromHeight = value;
  return next;
}

/**
 * Replaces one existing value at a draft-model path, for example `['bindings', 0, 'to', 'command']`. Nothing else
 * in the draft changes and no new field can be introduced. Form controls edit through this operation so each
 * control changes only its own value, even when several controls change before the form is redrawn.
 *
 * @throws {RangeError} when the path does not name an existing value
 */
export function setValue(draft, path, value) {
  if (!Array.isArray(path) || !path.length) throw new RangeError('A draft path is required');
  const next = clone(draft);
  let node = next;
  const exists = (container, segment) => container !== null && typeof container === 'object'
    && (Array.isArray(container) ? Number.isInteger(segment) && segment >= 0 && segment < container.length
      : typeof segment === 'string' && Object.hasOwn(container, segment));
  for (const segment of path.slice(0, -1)) {
    if (!exists(node, segment)) throw new RangeError('The draft has no value at that path');
    node = node[segment];
  }
  if (!exists(node, path.at(-1))) throw new RangeError('The draft has no value at that path');
  node[path.at(-1)] = structuredClone(value);
  return next;
}
