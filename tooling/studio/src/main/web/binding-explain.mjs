/**
 * Explanation of imported rehearsal receipts (ADR-031.2 §6.2, contract C6).
 *
 * Pure and DOM-free. Every statement is derived from canonical receipt bytes that the report importer decoded
 * and cross-checked, from the receipt-code table carried in the same report and, optionally, from the current
 * draft (only to name bindings the receipt does not mention). Nothing here executes a binding, recomputes a
 * state root or treats a report as authenticated. A step is described as committed only when its whole receipt
 * was accepted and the message was executed in the reported block; a rehearsal describes assumed inputs only.
 */
import {clauseLocation} from './binding-catalog.mjs';

const CATEGORY_LABELS = Object.freeze({
  'target-rejection': 'target rejection', 'resource-exhaustion': 'resource exhaustion',
  'evaluation-error': 'evaluation error', 'replay-or-conflict': 'replay or conflict',
  'contract-violation': 'contract violation'});

/**
 * Text from a receipt or kernel made safe to show as text: control, format and bidirectional-override
 * characters are shown as `\u{…}` escapes so they cannot reorder or hide surrounding text.
 */
export function visibleText(text) {
  let result = '';
  for (const character of String(text)) {
    result += isHidden(character) ? `\\u{${character.codePointAt(0).toString(16).padStart(4, '0')}}` : character;
  }
  return result;
}

/**
 * Control, format (including bidirectional controls and tag characters), line and paragraph separators, soft
 * hyphens and variation selectors: characters that can hide, reorder or disguise the text around them.
 */
export function isHidden(character) {
  const code = character.codePointAt(0);
  return /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}\p{Default_Ignorable_Code_Point}]/u.test(character)
    || (code >= 0xfe00 && code <= 0xfe0f) || (code >= 0xe0100 && code <= 0xe01ef);
}

function codeInfo(code, table) {
  if (!code) return null;
  const entry = table.find(value => value.code === code);
  return Object.freeze(entry ? {code, known: true, category: entry.category, categoryLabel: CATEGORY_LABELS[entry.category],
    levels: entry.levels, origin: entry.origin, entry}
    : {code, known: false, category: null, categoryLabel: 'unclassified', levels: [], origin: 'kernel', entry: null});
}

function conditionView(condition, last, step, info) {
  if (last && step.status === 'REJECTED' && info && clauseLocation(info.entry, step)) {
    return {bindingId: condition.bindingId, clause: condition.failedClause, kind: 'failed-here',
      label: `Failed with ${visibleText(step.code)} while evaluating condition ${condition.failedClause + 1}`};
  }
  if (last && step.status === 'REJECTED' && info?.entry?.locatesLastCondition === 'ambiguous') {
    return {bindingId: condition.bindingId, clause: condition.failedClause, kind: 'maybe-failed-here',
      // The engine may record -1 before evaluating any clause (for example when charging work), so -1 here does
      // not prove that the conditions held.
      label: condition.failedClause < 0
        ? 'Recorded without a false clause; the failure may have occurred before, during or after its conditions (the code does not say)'
        : `Recorded at condition ${condition.failedClause + 1}; the failure may concern it (the code does not say)`};
  }
  return condition.failedClause < 0
    ? {bindingId: condition.bindingId, clause: -1, kind: 'fired', label: 'All conditions held; the binding ran'}
    : {bindingId: condition.bindingId, clause: condition.failedClause, kind: 'skipped',
      label: `Condition ${condition.failedClause + 1} was false; the binding was skipped`};
}

function stepStatus(step, receipt, disposition, table) {
  const accepted = receipt.status === 'ACCEPTED';
  if (step.status === 'REJECTED') {
    const levels = table.find(value => value.code === step.code)?.levels ?? [];
    // The engine records source-level outcomes (for example a replay conflict) on the last step processed, but a
    // target component may return the same code for its own step, so neither reading is asserted.
    return levels.length === 1 && levels[0] === 'source' && step.depth > 0
      ? {status: 'rejected', label: `Rejected: ${visibleText(step.code)} (a source-level engine code that a target `
        + 'component can also use, so it may concern the whole source message rather than this step)'}
      : {status: 'rejected', label: `Rejected: ${visibleText(step.code)}`};
  }
  if (disposition !== 'executed') {
    // A replay or duplicate shows a stored receipt: its steps belong to the block that first executed the message.
    const stored = `from the stored receipt of height ${receipt.height}, not executed again`;
    return accepted ? {status: 'earlier', label: `${step.status === 'EFFECT_PLANNED' ? 'Effect intent recorded' : 'Committed'} earlier (${stored})`}
      : {status: 'earlier', label: `Planned, not committed (${stored})`};
  }
  if (step.status === 'EFFECT_PLANNED') {
    return accepted ? {status: 'effect-intent', label: 'Effect intent recorded in the outbox (not delivered)'}
      : {status: 'effect-discarded', label: 'Effect intent planned, not recorded'};
  }
  return accepted ? {status: 'committed', label: 'Committed in this rehearsal'}
    : {status: 'planned-not-committed', label: 'Planned, not committed'};
}

/**
 * Explains one rehearsed source message.
 *
 * @param {object} message imported report message (`disposition`, decoded `receipt`, identifiers)
 * @param {{receiptCodes: object[], effects?: object[], draft?: object|null}} context report tables and, when
 *        the report matches, the current draft
 * @returns {object} frozen view model; labels are plain text for textual rendering
 */
export function explainMessage(message, {receiptCodes, effects = [], draft = null}) {
  const receipt = message.receipt;
  const accepted = receipt.status === 'ACCEPTED';
  const executed = message.disposition === 'executed';
  const info = accepted ? null : codeInfo(receipt.code, receiptCodes);
  const failedStep = accepted ? null : receipt.steps.find(step => step.ordinal === receipt.failedStepOrdinal) ?? null;
  const truncated = !accepted && (failedStep === null || failedStep.code !== receipt.code);
  const notes = [];
  let outcome;
  let headline;
  if (message.disposition === 'replay-existing-receipt') {
    outcome = 'replayed';
    headline = 'Replay: this receipt was already stored before this block. It is not this block’s outcome and nothing ran again.';
  } else if (message.disposition === 'duplicate-in-fixture') {
    outcome = 'duplicate';
    headline = 'Duplicate: the same message id appeared earlier in this fixture; the receipt shown is from its first occurrence.';
  } else if (accepted) {
    outcome = 'committed';
    headline = `Accepted: all ${receipt.steps.length} recorded step${receipt.steps.length === 1 ? '' : 's'} committed together in this rehearsal.`;
  } else if (receipt.code === 'REPLAY_OR_CONFLICT') {
    outcome = 'rolled-back';
    headline = 'Rejected with REPLAY_OR_CONFLICT: nothing from this cascade committed.';
    notes.push('The engine uses this code when the source message id cannot be claimed (already claimed, or claimed '
      + 'with different content); a target component can also return it, so the receipt alone does not say which.');
  } else {
    outcome = 'rolled-back';
    headline = `Rejected with ${visibleText(receipt.code)}: nothing from this cascade committed, including the source command.`;
  }
  if (info && !info.known) notes.push('This code is not a framework code: it is probably the target component’s own rejection.');
  if (info?.known && info.origin !== 'engine') notes.push('A target component may also use this framework code for its own rejections.');
  if (info?.code?.startsWith('MAPPING_')) {
    notes.push(info.levels.includes('condition')
      ? 'The receipt does not record which mapped field or lookup source failed.'
      : 'The receipt does not record which mapped field failed.');
  }
  if (!receipt.steps.length) notes.push('The receipt records no steps, so which bindings were evaluated is not recorded.');
  if (truncated) {
    // Compaction removes non-failed steps (usually earlier ones) and may drop the failed step's event names.
    const present = new Set(receipt.steps.map(step => step.ordinal));
    const highest = Math.max(-1, ...present);
    const missing = Array.from({length: highest + 1}, (_, ordinal) => ordinal).filter(ordinal => !present.has(ordinal));
    notes.push(failedStep === null
      ? 'History truncated: the failed step is not in the receipt.'
      : `History truncated: the receipt ran out of space (${visibleText(receipt.code)}), so some non-failed steps were omitted`
        + `${missing.length ? ` (missing ordinals ${missing.join(', ')})` : ''}; the failed step keeps its own code ${visibleText(failedStep.code)}.`);
  }
  const effectByScope = new Map(effects.map(effect => [effect.scope, effect]));
  const steps = [...receipt.steps].sort((a, b) => a.ordinal - b.ordinal).map(step => {
    const status = stepStatus(step, receipt, message.disposition, receiptCodes);
    const stepInfo = step.status === 'REJECTED' ? codeInfo(step.code, receiptCodes) : null;
    const derived = new Set(receipt.steps.map(value => value.bindingId).filter(Boolean));
    const conditions = step.conditions.map((condition, index) => {
      const view = conditionView(condition, index === step.conditions.length - 1, step, stepInfo);
      // In a rejected cascade a binding can fire while its derived command is still queued when the cascade stops.
      // Truncated history may have dropped the derived step instead, so absence then proves nothing.
      if (!accepted && view.kind === 'fired' && !derived.has(condition.bindingId)) {
        return Object.freeze({...view, kind: truncated ? 'fired-unknown' : 'fired-not-executed', label: truncated
          ? 'All conditions held; its derived step is not in the retained (truncated) history'
          : 'All conditions held; its derived command was not executed (cascade stopped)'});
      }
      return Object.freeze(view);
    });
    // In a rejected cascade any step may have stopped part-way (a mapping failure stops its parent after the
    // binding that fired), so subscribed bindings without a record on a step were not evaluated there.
    let notEvaluated = [];
    if (!accepted && draft) {
      const recorded = new Set(step.conditions.map(condition => condition.bindingId));
      notEvaluated = draft.bindings.filter(binding => binding.from.component === step.targetComponentId
        && step.eventsProduced.includes(binding.from.event) && !recorded.has(binding.id)).map(binding => binding.id);
    }
    const effect = step.status === 'EFFECT_PLANNED' ? effectByScope.get(`binding/${step.messageIdHex}`) ?? null : null;
    return Object.freeze({ordinal: step.ordinal, depth: step.depth, bindingId: step.bindingId,
      target: step.targetComponentId, messageIdHex: step.messageIdHex, rawBody: step.rawBody,
      events: Object.freeze([...step.eventsProduced]), code: step.code || null, ...status,
      conditions: Object.freeze(conditions), notEvaluated: Object.freeze(notEvaluated),
      effect: effect ? Object.freeze({type: effect.type, gate: effect.gate, result: effect.result,
        expiryBlocks: effect.expiryBlocks, payloadHex: effect.payloadHex}) : null});
  });
  let notVisited = [];
  if (accepted && executed && draft && receipt.steps.length) {
    const recorded = new Set(receipt.steps.flatMap(step => step.conditions.map(condition => condition.bindingId)));
    notVisited = draft.bindings.map(binding => binding.id).filter(id => !recorded.has(id));
  }
  return Object.freeze({messageIndex: message.messageIndex, messageIdHex: message.messageIdHex, topic: message.topic,
    disposition: message.disposition, outcome, headline, height: receipt.height, code: info, truncated,
    notes: Object.freeze(notes), steps: Object.freeze(steps), notVisited: Object.freeze(notVisited)});
}

/**
 * Groups rehearsal histories by execution identity (program, context and manifest) and orders them by height.
 * A continuation links to every report at the preceding height with its recorded prior post-state digest.
 * Alternate reports at the same height remain visible; their display order never determines a predecessor.
 *
 * @param {object[]} reports imported dry-run reports
 * @returns {Array<{executionIdentity: string, blocks: object[], complete: boolean}>} sequences in first-seen order;
 *          each block carries `predecessors` (report references) and `chain`: `start`, `linked`, `gap` (missing
 *          preceding height), `broken` (digests differ), or `continues-unknown` (no earlier report). `complete`
 *          means every report has a digest-linked path to a starting report; it does not verify any inputs.
 */
export function rehearsalSequences(reports) {
  const groups = new Map();
  for (const report of reports.filter(value => value.result?.rehearsal)) {
    const identity = report.result.rehearsal.executionIdentity;
    if (!groups.has(identity)) groups.set(identity, []);
    groups.get(identity).push(report);
  }
  return Object.freeze([...groups].map(([executionIdentity, group]) => {
    const runs = [...group].sort((a, b) => (a.result.rehearsal.assumptions.height < b.result.rehearsal.assumptions.height ? -1
      : a.result.rehearsal.assumptions.height > b.result.rehearsal.assumptions.height ? 1 : 0));
    const byHeight = new Map();
    for (const report of runs) {
      const height = report.result.rehearsal.assumptions.height;
      if (!byHeight.has(height)) byHeight.set(height, []);
      byHeight.get(height).push(report);
    }
    const completeReports = new Set();
    const blocks = runs.map(report => {
      const run = report.result.rehearsal;
      const previous = byHeight.get(run.assumptions.height - 1n) ?? [];
      const predecessors = run.fromPriorResult ? previous.filter(candidate =>
        candidate.result.rehearsal.postStateSha256 === run.priorPostStateSha256) : [];
      let chain = 'start';
      if (run.fromPriorResult) {
        chain = predecessors.length ? 'linked' : previous.length ? 'broken'
          : runs[0].result.rehearsal.assumptions.height < run.assumptions.height ? 'gap' : 'continues-unknown';
      }
      if (!run.fromPriorResult || predecessors.some(candidate => completeReports.has(candidate))) {
        completeReports.add(report);
      }
      return Object.freeze({report, predecessors: Object.freeze(predecessors),
        height: run.assumptions.height, timestamp: run.assumptions.timestamp,
        stateRootHex: run.assumptions.stateRootHex, pendingEffects: run.assumptions.pendingEffects,
        continuationOnly: run.continuationOnly, empty: run.messages.length === 0, chain,
        messages: run.messages});
    });
    return Object.freeze({executionIdentity, blocks: Object.freeze(blocks), complete: completeReports.size === runs.length});
  }));
}
