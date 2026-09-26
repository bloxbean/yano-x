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
    fromHeight: component.fromHeight ?? null});
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

/** Binding ids appear in receipts and derived message ids, so a rename is explicit even without references. */
export function renameBinding(draft, index, newId) {
  const next = clone(draft);
  checkIndex(next.bindings, index, 'Binding');
  next.bindings[index].id = newId;
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

/** Authors (value) or removes (null) one committed limit; removal shows the catalog default without writing it. */
export function setLimit(draft, name, value) {
  const next = clone(draft);
  const limits = next.limits ?? [];
  const existing = limits.find(limit => limit.name === name);
  if (value === null) next.limits = limits.filter(limit => limit.name !== name);
  else if (existing) existing.value = value;
  else next.limits = [...limits, {name, value}];
  if (next.limits !== null && !next.limits.length && draft.limits === null) next.limits = null;
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
