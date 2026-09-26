/**
 * The binding editor's Validate view (ADR-031.2 §6): CLI handoff, imported reports with their match state,
 * validation diagnostics and rehearsed source outcomes. Everything shown comes from `binding-validation.mjs`
 * and `binding-explain.mjs`; report text is displayed only as escaped text. Reports that do not match the
 * current inputs are shown as history and never decorate or navigate the current draft.
 */
import {renderPath} from './binding-draft.mjs';
import {h, put} from './binding-dom.mjs';
import {visibleText} from './binding-explain.mjs';
import {STARTERS, STARTER_BASE} from './binding-starters.mjs';
import {assessReports, assuranceState, handoffCommands, handoffText, HANDOFF_NAMES,
  MAX_REHEARSAL_BLOCKS} from './binding-validation.mjs';

const MATCH_LABELS = Object.freeze({matching: 'Matches the current inputs', stale: 'Does not match the current inputs',
  unverifiable: 'Cannot be matched'});
const REHEARSAL_LABELS = Object.freeze({matching: 'fixture and continuation verified',
  stale: 'fixture or continuation differs', unverified: 'rehearsal inputs not verified', 'not-applicable': ''});
const CHAIN_LABELS = Object.freeze({start: 'first block', linked: 'continues an imported report at the preceding height',
  gap: 'heights are not consecutive', broken: 'does not continue the previous block’s state',
  'continues-unknown': 'continues a block that was not imported'});
const STAGE_LABELS = Object.freeze({validation: 'the document was rejected',
  rehearsal: 'the fixture or continuation was rejected; the document had compiled'});
/** Rendering bound per block; the report file holds every message. */
export const MAX_RENDERED_MESSAGES = 50;

const shortHex = hex => `${hex.slice(0, 12)}…`;

/** Recomputes the assessment only when the reports, fixtures, catalog or draft changed. */
function assessment(editor) {
  const session = editor.session;
  const key = [session.reports, JSON.stringify([...session.fixtures]), session.catalog,
    session.document.text, session.mode, session.draft];
  const cached = editor.assessmentCache;
  if (cached && cached.key.every((value, index) => value === key[index])) return cached.value;
  const value = assessReports({reports: session.reports, fixtureDigests: session.fixtureDigests(),
    replacedFixtures: session.replacedFixtures(), catalog: session.catalog,
    documentSha256: session.exportedDocumentSha256(), draft: session.draft});
  editor.assessmentCache = {key, value};
  return value;
}

/** Renders the Validate tab for the editor's current session. */
export function renderValidation(editor, panel) {
  const current = assessment(editor);
  put(panel,
    handoffSection(editor),
    reportsSection(editor, current),
    checksSection(editor, current),
    rehearsalSection(editor, current));
}

/** Plain-text status for announcements after imports and removals. */
export function assuranceSummary(editor) {
  return assuranceState(assessment(editor), {origin: editor.session.document.origin}).label;
}

function handoffSection(editor) {
  const blocks = editor.handoffBlocks ?? 0;
  const select = h('select', {id: 'handoff-blocks'}, Array.from({length: MAX_REHEARSAL_BLOCKS + 1}, (_, count) =>
    h('option', {value: String(count), text: count === 0 ? 'Validate only' : `Validate and rehearse ${count} block${count === 1 ? '' : 's'}`})));
  select.value = String(blocks);
  select.addEventListener('change', () => { editor.handoffBlocks = Number(select.value); editor.render('handoff'); });
  const exportable = editor.session.mode === 'editable';
  return h('section', {class: 'form-section', 'aria-labelledby': 'handoff-heading'},
    h('h3', {id: 'handoff-heading', text: 'Hand off to the CLI'}),
    h('p', {class: 'hint', text: 'Studio never runs the compiler or engine. Download the document, run the version-matched '
      + 'CLI yourself, then import its report files here. Commands use fixed file names; choose the directories yourself.'}),
    h('div', {class: 'stack'}, h('label', {for: 'handoff-blocks'}, 'Scenario'), select),
    h('pre', {id: 'handoff-commands', class: 'commands', tabindex: '0', 'aria-label': 'CLI commands'},
      handoffCommands(blocks).join('\n')),
    h('div', {class: 'inline-actions'},
      h('button', {type: 'button', id: 'handoff-download-document', text: `Download ${HANDOFF_NAMES.document}`,
        disabled: !exportable, onclick: () => editor.downloadDocument()}),
      h('button', {type: 'button', class: 'secondary', id: 'handoff-download', text: `Download ${HANDOFF_NAMES.handoff}`,
        onclick: () => editor.services.download({name: HANDOFF_NAMES.handoff, text: handoffText(blocks)})}),
      editor.session.catalogOrigin === 'bundled' ? h('button', {type: 'button', class: 'secondary', id: 'handoff-context',
        text: `Download tutorial ${HANDOFF_NAMES.context}`, onclick: async () => {
          try {
            const bytes = await editor.services.fetchBytes('binding-authoring-context.json');
            editor.services.download({name: HANDOFF_NAMES.context, bytes, text: ''});
          } catch (error) { editor.announce(error.message, true); }
        }}) : null),
    exampleFixtures(editor),
    h('p', {class: 'hint', text: editor.session.catalogOrigin === 'bundled'
      ? 'The bundled reference catalog assists editing only; reports from your installation will not match it. Run the '
        + 'catalog command above with the same plugins directory and context, and import catalog.json before the reports.'
      : 'Reports match only the imported catalog’s exact tool, plugins and context; re-export it if any of them changed.'}),
    editor.session.document.origin === 'blueprint' ? h('p', {class: 'hint warn', text: 'This composite comes from a '
      + 'blueprint. The CLI checks it under the context you supply; project rendering derives the chain’s real context '
      + 'from the blueprint and remains the authority.'}) : null);
}

/** Downloads a loaded starter's public example fixtures under the handoff's fixed names. */
function exampleFixtures(editor) {
  const document = editor.session.document;
  const starter = document.origin === 'starter' ? STARTERS.find(value => value.file === document.name) : null;
  if (!starter?.fixtures) return null;
  const id = starter.id;
  return h('div', {class: 'inline-actions'},
    h('button', {type: 'button', class: 'secondary', id: 'handoff-fixtures',
      text: `Download example fixtures (${starter.fixtures})`, onclick: async () => {
        try {
          for (let block = 1; block <= starter.fixtures; block++) {
            const bytes = await editor.services.fetchBytes(`${STARTER_BASE}fixtures/${id}/fixture-${block}.json`);
            editor.services.download({name: HANDOFF_NAMES.fixture(block), bytes, text: ''});
          }
          editor.handoffBlocks = starter.fixtures;
          editor.announce(`Downloaded ${starter.fixtures} example fixture file${starter.fixtures === 1 ? '' : 's'}; the commands now rehearse them.`);
          editor.render('handoff');
        } catch (error) { editor.announce(error.message, true); }
      }}),
    h('span', {class: 'hint', text: 'Synthetic, public inputs for this starter. Their authentication proofs are '
      + 'placeholders, which dry runs accept because they assume authenticated inputs.'}));
}

function reportsSection(editor, current) {
  const session = editor.session;
  const assurance = assuranceState(current, {origin: session.document.origin});
  const reports = h('input', {type: 'file', id: 'import-reports', multiple: true, accept: '.json,application/json'});
  reports.addEventListener('change', () => editor.readFiles(reports, (bytes, name) => session.importReportFile(bytes, name),
    count => `Imported ${count} report file${count === 1 ? '' : 's'}. ${assuranceSummary(editor)}`));
  const fixtures = h('input', {type: 'file', id: 'import-fixtures', multiple: true, accept: '.json,application/json'});
  const replaced = [];
  fixtures.addEventListener('change', () => {
    replaced.length = 0;
    editor.readFiles(fixtures, (bytes, name) => { if (session.importFixtureFile(bytes, name)) replaced.push(name); },
      count => `Recorded the digests of ${count} fixture file${count === 1 ? '' : 's'}.${replaced.length
        ? ` Replaced the earlier ${replaced.map(name => visibleText(name)).join(', ')}; any rehearsal of the replaced `
          + 'content is stale.' : ''} ${assuranceSummary(editor)}`);
  });
  const remove = index => {
    const name = current.entries[index]?.name ?? 'the report';
    session.removeReport(index);
    const remaining = session.reports.length;
    editor.pendingFocus = remaining ? `remove-report-${Math.min(index, remaining - 1)}` : 'import-reports';
    editor.announce(`Removed ${visibleText(name)}. ${assuranceSummary(editor)}`);
  };
  return h('section', {class: 'form-section', 'aria-labelledby': 'reports-heading'},
    h('h3', {id: 'reports-heading', text: 'Imported reports'}),
    h('p', {id: 'assurance-state', class: `assurance ${assurance.state}`, role: 'status', 'aria-live': 'polite'},
      h('strong', {text: 'Status: '}), assurance.label),
    h('p', {class: 'hint', text: 'Reports are unauthenticated files that anyone can edit. A match means the same exact '
      + 'document, context, plugin catalog and tool were used; it is not an attestation, a finality certificate or '
      + 'deployment approval. Project rendering revalidates independently.'}),
    h('div', {class: 'field-row'},
      h('label', {class: 'file-button'}, 'Import report files', reports),
      h('label', {class: 'file-button'}, 'Import fixture files (to verify rehearsal inputs)', fixtures)),
    current.entries.length ? h('ul', {class: 'report-list'}, current.entries.map((entry, index) => h('li', {class: 'report-entry'},
      h('div', {class: 'assignment-head'}, h('strong', {text: visibleText(entry.name)}),
        h('span', {class: 'tag', text: `${entry.report.operation} · ${entry.report.operationOutcome}`}),
        h('span', {class: `tag ${entry.match.state === 'matching' ? 'required' : 'missing'}`, text: MATCH_LABELS[entry.match.state]})),
      entry.match.reasons.length ? h('p', {class: 'hint', text: visibleText(entry.match.reasons.join('; '))}) : null,
      h('div', {class: 'row-actions'}, h('button', {type: 'button', class: 'secondary', id: `remove-report-${index}`,
        text: 'Remove', 'aria-label': `Remove report ${visibleText(entry.name)}`, onclick: () => remove(index)}))))) : h('p', {class: 'hint', text: 'No reports imported.'}),
    session.fixtures.size ? h('p', {class: 'hint', text: `Fixture digests recorded: ${[...session.fixtures.keys()]
      .map(name => visibleText(name)).join(', ')}. Importing a file of the same name replaces it.`}) : null,
    current.entries.length || session.fixtures.size ? h('div', {class: 'inline-actions'},
      current.entries.length ? h('button', {type: 'button', class: 'secondary', id: 'remove-all-reports', text: 'Remove all reports',
        onclick: () => { session.clearReports(); editor.pendingFocus = 'import-reports'; editor.announce(`Removed all reports. ${assuranceSummary(editor)}`); }}) : null,
      session.fixtures.size ? h('button', {type: 'button', class: 'secondary', id: 'forget-fixtures', text: 'Forget fixture digests',
        onclick: () => { session.clearFixtures(); editor.pendingFocus = 'import-fixtures'; editor.announce(`Forgot the fixture digests. ${assuranceSummary(editor)}`); }}) : null) : null);
}

function checksSection(editor, current) {
  if (!current.checks.length) return null;
  return h('section', {class: 'form-section', 'aria-labelledby': 'validation-heading'},
    h('h3', {id: 'validation-heading', text: 'CLI results'}),
    h('ol', {class: 'report-list'}, current.checks.map((entry, reportIndex) => {
      const matching = entry.match.state === 'matching';
      const report = entry.report;
      const outcome = report.operationOutcome === 'failed' ? `failed: ${STAGE_LABELS[entry.stage]}` : 'completed';
      return h('li', {class: 'report-entry'},
        h('h4', {id: `check-heading-${reportIndex}`, text: `${visibleText(entry.name)} · ${report.operation} ${outcome}`}),
        h('p', {class: matching ? 'hint' : 'hint warn', text: matching
          ? (report.operationOutcome === 'completed' ? 'The CLI completed this operation for exactly the current inputs (imported report).'
            : entry.stage === 'rehearsal' ? 'The CLI accepted the current document but rejected the rehearsal inputs (imported report).'
              : 'The CLI rejected exactly the current inputs (imported report).')
          : 'Historical: this report does not describe the current draft, so its locations are not linked to the form.'}),
        report.result?.profile ? h('p', {class: 'hint', text: `Profile digest ${shortHex(report.result.profile.digestHex)} · `
          + `execution version ${visibleText(report.result.profile.executionVersion)}`}) : null,
        report.diagnostics.length ? h('ol', {class: 'condition-list'}, report.diagnostics.slice(0, 64).map((diagnostic, index) =>
          diagnosticView(editor, diagnostic, matching, `diagnostic-go-${reportIndex}-${index}`))) : null,
        report.diagnostics.length > 64 ? h('p', {class: 'hint', text: `${report.diagnostics.length - 64} more diagnostics `
          + 'are in the report file and are not shown here.'}) : null);
    })));
}

function diagnosticView(editor, diagnostic, matching, id) {
  const location = diagnostic.location;
  const where = location.pathSegments ? visibleText(renderPath(location.pathSegments))
    : location.bindingId ? `binding ${visibleText(location.bindingId)}` : location.input ? `input ${visibleText(location.input)}`
      : 'no recorded location';
  const navigable = matching && location.input === 'document' && location.pathSegments;
  return h('li', {class: 'diagnostic'},
    h('div', {class: 'assignment-head'}, h('strong', {text: visibleText(diagnostic.code)}),
      location.part ? h('span', {class: 'tag', text: location.part}) : null),
    h('p', {text: diagnostic.controlledMessage ?? 'Unknown diagnostic code; this Studio has no description for it.'}),
    h('p', {class: 'hint', text: `Location: ${where}${location.line ? ` (line ${location.line}, column ${location.column})` : ''}`
      + `${location.expressionLine ? `; expression line ${location.expressionLine}, column ${location.expressionColumn}` : ''}`}),
    diagnostic.detail ? h('p', {class: 'hint'}, 'Reported detail', diagnostic.detailMayContainInput ? ' (may quote your input)' : '',
      ': ', h('code', {text: visibleText(diagnostic.detail)})) : null,
    navigable ? h('button', {type: 'button', class: 'secondary', id, text: 'Show in the form',
      onclick: () => editor.navigate([...location.pathSegments])}) : null);
}

function rehearsalSection(editor, current) {
  if (!current.sequences.length) return null;
  const draft = editor.session.draft;
  return h('section', {class: 'form-section', 'aria-labelledby': 'rehearsal-heading'},
    h('h3', {id: 'rehearsal-heading', text: 'Rehearsal'}),
    h('p', {class: 'hint', text: 'Dry runs execute the real engine on assumed, unauthenticated fixture inputs. They do '
      + 'not verify signatures, finality, roots or anchors, and nothing was submitted or delivered.'}),
    current.sequences.map((sequence, sequenceIndex) => h('div', {class: 'sequence'},
      h('h4', {text: `History ${sequenceIndex + 1} · program ${shortHex(sequence.executionIdentity)}`}),
      h('p', {class: 'hint', text: 'Reports are ordered by height. Reports at the same height are alternatives; '
        + 'continuations link by preceding height and state digest, not by position in this list.'}),
      sequence.complete ? null : h('p', {class: 'hint warn', text: 'Some reports have no complete chain of imported predecessors.'}),
      h('ol', {class: 'block-list'}, sequence.blocks.map((block, blockIndex) => {
        const matching = block.match.state === 'matching';
        const id = `sequence-${sequenceIndex}-block-${blockIndex}`;
        const shown = block.messages.slice(0, MAX_RENDERED_MESSAGES);
        return h('li', {class: 'block'},
          h('h5', {text: `Block ${block.height} · ${visibleText(block.name)}`}),
          h('p', {class: 'hint', text: `${CHAIN_LABELS[block.chain]}. ${MATCH_LABELS[block.match.state]}`
            + `${REHEARSAL_LABELS[block.match.rehearsal] ? `; ${REHEARSAL_LABELS[block.match.rehearsal]}` : ''}.`}),
          block.match.reasons.length ? h('p', {class: 'hint', text: `Why: ${visibleText(block.match.reasons.join('; '))}.`}) : null,
          h('dl', {class: 'assumptions'},
            h('dt', {text: 'Timestamp'}), h('dd', {text: String(block.timestamp)}),
            h('dt', {text: 'Assumed prior root'}), h('dd', {text: shortHex(block.stateRootHex)}),
            h('dt', {text: 'Pending effects'}), h('dd', {text: String(block.pendingEffects)})),
          block.empty ? h('p', {class: 'hint', text: 'No messages in this block (an explicit empty block).'}) : null,
          block.continuationOnly ? h('p', {class: 'hint', text: 'Run with --continuation-only: its standard output carries '
            + 'state only, and this report still records every receipt.'}) : null,
          h('ol', {class: 'message-list'}, shown.map((message, messageIndex) =>
            messageView(editor, message, `${id}-message-${messageIndex}`, matching ? draft : null))),
          block.messages.length > shown.length ? h('p', {class: 'hint', text: `${block.messages.length - shown.length} more `
            + 'messages are in the report file and are not shown here.'}) : null);
      })))));
}

function messageView(editor, message, id, draft) {
  const bindingIndex = bindingId => draft ? draft.bindings.findIndex(binding => binding.id === bindingId) : -1;
  const open = (bindingId, suffix) => {
    const index = bindingIndex(bindingId);
    return index >= 0 ? h('button', {type: 'button', class: 'secondary', id: `${id}-${suffix}`, text: `Open ${visibleText(bindingId)}`,
      onclick: () => editor.select('binding', index, `select-binding-${index}`)}) : null;
  };
  return h('li', {class: `message ${message.outcome}`},
    h('p', {}, h('strong', {text: `Message ${message.messageIndex + 1} · ${visibleText(message.topic)}: `}), message.headline),
    message.code ? h('p', {class: 'hint', text: `Code ${visibleText(message.code.code)} · ${message.code.categoryLabel}`
      + `${message.code.levels.length ? ` · raised at ${message.code.levels.join(', ')} level` : ''}`}) : null,
    message.notes.map(note => h('p', {class: 'hint warn', text: note})),
    h('ol', {class: 'step-list'}, message.steps.map((step, stepIndex) => h('li', {class: `trace-step ${step.status}`},
      h('p', {}, h('strong', {text: `Step ${step.ordinal} (depth ${step.depth}) · `}),
        step.bindingId ? `binding ${visibleText(step.bindingId)} → ` : 'source → ', visibleText(step.target),
        ` · ${step.label}${step.rawBody ? ' · raw body' : ''}`),
      step.events.length ? h('p', {class: 'hint', text: `Events: ${step.events.map(visibleText).join(', ')}`}) : null,
      step.conditions.length ? h('ul', {class: 'condition-list'}, step.conditions.map((condition, conditionIndex) =>
        h('li', {}, `${visibleText(condition.bindingId)}: ${condition.label} `,
          condition.kind !== 'fired' ? open(condition.bindingId, `step-${stepIndex}-condition-${conditionIndex}`) : null))) : null,
      step.notEvaluated.length ? h('p', {class: 'hint', text: `Not evaluated (cascade stopped): ${step.notEvaluated.map(visibleText).join(', ')}`}) : null,
      step.effect ? h('p', {class: 'hint', text: `Effect ${visibleText(step.effect.type)} · gate ${visibleText(step.effect.gate)}`
        + ` · result ${visibleText(step.effect.result)} · expires after ${step.effect.expiryBlocks} blocks`}) : null,
      step.bindingId ? open(step.bindingId, `step-${stepIndex}`) : null))),
    message.notVisited.length ? h('p', {class: 'hint', text: `No step produced the source event of: ${message.notVisited.map(visibleText).join(', ')}.`}) : null);
}
