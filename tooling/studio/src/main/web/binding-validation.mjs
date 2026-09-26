/**
 * CLI handoff and imported-report assessment for the binding editor (ADR-031.2 §6, contracts C2, C3 and C6).
 *
 * Pure and DOM-free. Handoff instructions use only fixed file names and a placeholder for the plugin directory;
 * nothing from a document, catalog or report becomes part of a command. Imported reports are unauthenticated:
 * the assessment decides only whether a report describes the exact current inputs, never who produced it.
 */
import {explainMessage,rehearsalSequences} from './binding-explain.mjs';
import {reportMatch} from './binding-report.mjs';

export const HANDOFF_NAMES = Object.freeze({document: 'bindings.yaml', context: 'context.json', catalog: 'catalog.json',
  validateReport: 'validate-report.json', handoff: 'bindings-handoff.txt',
  fixture: block => `fixture-${block}.json`, result: block => `result-${block}.json`,
  rehearsalReport: block => `dry-run-report-${block}.json`});
export const MAX_REHEARSAL_BLOCKS = 16;
const LAUNCHER = './yano.sh appchain';
const PLUGINS = '<plugins-directory>';

/**
 * Commands for exporting the matching authoring catalog, validating the exported document and rehearsing
 * `blocks` consecutive fixture blocks. Reports match only a catalog exported by the same CLI from the same
 * plugin directory and context, so the catalog command comes first.
 *
 * @param {number} blocks number of rehearsal blocks, 0 for validation only
 * @returns {string[]} one command per line, using fixed names only
 */
export function handoffCommands(blocks) {
  if (!Number.isInteger(blocks) || blocks < 0 || blocks > MAX_REHEARSAL_BLOCKS) throw new RangeError('Unsupported block count');
  const base = `${LAUNCHER} bindings`;
  const inputs = `--plugins-directory ${PLUGINS} --context ${HANDOFF_NAMES.context}`;
  const commands = [`${base} catalog ${HANDOFF_NAMES.document} ${inputs} > ${HANDOFF_NAMES.catalog}`,
    `${base} validate ${HANDOFF_NAMES.document} ${inputs} --report ${HANDOFF_NAMES.validateReport}`];
  for (let block = 1; block <= blocks; block++) {
    const prior = block === 1 ? '' : ` --prior-result ${HANDOFF_NAMES.result(block - 1)}`;
    commands.push(`${base} dry-run ${HANDOFF_NAMES.document} ${inputs} --fixture ${HANDOFF_NAMES.fixture(block)}${prior}`
      + ` --report ${HANDOFF_NAMES.rehearsalReport(block)} > ${HANDOFF_NAMES.result(block)}`);
  }
  return commands;
}

/** Complete downloadable handoff instructions (plain text, fixed names, no document-derived content). */
export function handoffText(blocks) {
  return [
    'Yano X binding editor: CLI handoff',
    '',
    'Put these files in one working directory of your choosing:',
    `  ${HANDOFF_NAMES.document}   the document downloaded from Studio`,
    `  ${HANDOFF_NAMES.context}    your chain's context (chainId, settings, consensusProfile, membership)`,
    ...(blocks ? [`  ${HANDOFF_NAMES.fixture(1)}${blocks > 1 ? ` … ${HANDOFF_NAMES.fixture(blocks)}` : ''}   one rehearsal block per file, heights consecutive`] : []),
    '',
    `Replace ${PLUGINS} with the directory holding exactly your chain's manifested runtime bundles, then run:`,
    '',
    ...handoffCommands(blocks).map(command => `  ${command}`),
    '',
    `Import ${HANDOFF_NAMES.catalog} into Studio first (Import catalog JSON), then the report files. A report matches`,
    'only a catalog exported by the same CLI from the same plugin directory and context; the bundled reference',
    'catalog assists editing but will not match reports from your installation.',
    '',
    'Reports are unauthenticated files: a match means the same exact',
    'inputs were used, not that anyone attested them. Dry runs assume authenticated inputs and do not verify',
    'signatures, finality, roots or anchors. Nothing here submits to a node, installs a plugin or delivers an effect.',
    ''].join('\n');
}

// A dry-run fails in its rehearsal stage when every diagnostic concerns the fixture, the prior result or the
// continuation: the document had already compiled and its profile had been constructed by then.
const REHEARSAL_CODES = new Set(['FIXTURE_INVALID', 'FIXTURE_ADMISSION_REJECTED', 'CONTINUATION_MISMATCH']);
const REHEARSAL_INPUTS = new Set(['fixture', 'priorResult', 'continuation', 'execution']);

/** `null` for a completed operation, else `validation` or `rehearsal` (where the operation failed). */
export function failureStage(report) {
  if (report.operationOutcome !== 'failed') return null;
  const rehearsal = report.operation === 'dry-run' && report.diagnostics.length > 0 && report.diagnostics.every(
    diagnostic => REHEARSAL_CODES.has(diagnostic.code) || REHEARSAL_INPUTS.has(diagnostic.location.input));
  return rehearsal ? 'rehearsal' : 'validation';
}

/**
 * Assesses imported reports against the current inputs.
 *
 * @param {{reports: Array<{name, report}>, fixtureDigests: Set<string>,
 *          replacedFixtures?: Map<string, {names: string[], current: string}>, catalog: object|null,
 *          documentSha256: string|null, draft: object|null}} state current editor state: the active fixture
 *          digests and, from {@code BindingSession#replacedFixtures}, earlier digests replaced under the same name
 * @returns {object} `entries` (every report with its match), `checks` (reports without a rehearsal, matching ones
 *          first, each with its failure stage), `validation` (the most relevant check, for summaries) and
 *          `sequences` (rehearsal block sequences per execution identity, matching ones first)
 */
export function assessReports({reports, fixtureDigests, replacedFixtures = new Map(), catalog, documentSha256, draft}) {
  const catalogCodes = catalog ? JSON.stringify(catalog.language.receiptCodes) : null;
  // A rehearsal's fixture is verified when its digest is in the active selection. When a later import of the same
  // file name replaced that content, the rehearsal is compared with the replacement, so it reads as stale.
  const fixtureOf = report => {
    const digest = report.inputs.find(input => input.role === 'fixture')?.sha256 ?? null;
    if (digest && fixtureDigests.has(digest)) return {sha256: digest, reason: null};
    const replaced = digest ? replacedFixtures.get(digest) : undefined;
    if (!replaced) return {sha256: null, reason: null};
    const names = replaced.names.join(' and ');
    return {sha256: replaced.current, reason: replaced.names.length === 1
      ? `its fixture ${names} was replaced by a file with different content`
      : `its fixture, imported as ${names}, was replaced by files with different content`};
  };
  const withReason = (match, reason) => reason
    ? Object.freeze({...match, reasons: Object.freeze([...match.reasons, reason])}) : match;
  const entries = reports.map(({name, report}) => {
    const fixture = fixtureOf(report);
    let match = withReason(reportMatch(report, {catalog, documentSha256, fixtureSha256: fixture.sha256}),
      fixture.reason);
    // With equal identities the tool's receipt-code table must be the catalog's; a different table is not trusted.
    if (match.state === 'matching' && catalogCodes !== null && JSON.stringify(report.receiptCodes) !== catalogCodes) {
      match = Object.freeze({...match, state: 'unverifiable', rehearsal: match.rehearsal === 'not-applicable'
        ? 'not-applicable' : 'unverified', reasons: Object.freeze([...match.reasons,
        'its receipt-code table differs from the catalog of the same tool'])});
    }
    return Object.freeze({name, report, match, stage: failureStage(report)});
  });
  const matchingFirst = list => [...list.filter(entry => entry.match.state === 'matching'),
    ...list.filter(entry => entry.match.state !== 'matching')];
  const checks = matchingFirst(entries.filter(entry => !entry.report.result?.rehearsal));
  const matchingChecks = checks.filter(entry => entry.match.state === 'matching');
  const validation = matchingChecks.find(entry => entry.stage === 'validation') ?? matchingChecks[0] ?? checks[0] ?? null;
  const byReport = new Map(entries.map(entry => [entry.report, entry]));
  const sequences = rehearsalSequences(entries.filter(entry => entry.report.result?.rehearsal).map(entry => entry.report))
    .map(sequence => {
      // Heights form a DAG: every candidate predecessor has already been assessed. Verification needs at least
      // one verified path, never whichever historical report happens to be adjacent in display/import order.
      const verifiedReports = new Set();
      const blocks = sequence.blocks.map(block => {
        const run = block.report.result.rehearsal;
        const entry = byReport.get(block.report);
        const previous = block.predecessors[0]?.result.rehearsal.postStateSha256 ?? null;
        const fixture = fixtureOf(block.report);
        let match = withReason(reportMatch(block.report, {catalog, documentSha256, fixtureSha256: fixture.sha256,
          priorPostStateSha256: run.fromPriorResult ? previous : undefined}), fixture.reason);
        if (entry.match.state !== 'matching' && match.state === 'matching') match = entry.match;
        const chainOk = !run.fromPriorResult || block.predecessors.some(report => verifiedReports.has(report));
        if (!chainOk) {
          match = Object.freeze({...match, rehearsal: match.rehearsal === 'matching' ? 'unverified' : match.rehearsal,
            reasons: Object.freeze([...match.reasons, block.predecessors.length
              ? 'the block it continues is not verified'
              : 'no imported report matches its preceding height and state digest'])});
        }
        if (match.state === 'matching' && match.rehearsal === 'matching') verifiedReports.add(block.report);
        const matching = match.state === 'matching';
        // Explanations use the catalog's receipt-code table when the report matches, else the report's own.
        const receiptCodes = matching && catalog ? catalog.language.receiptCodes : block.report.receiptCodes;
        return Object.freeze({...block, name: entry.name, match,
          messages: block.messages.map(message => explainMessage(message, {receiptCodes, effects: run.effects,
            draft: matching ? draft : null}))});
      });
      return Object.freeze({...sequence, blocks: Object.freeze(blocks)});
    });
  const ordered = [...sequences].sort((a, b) => Number(b.blocks.some(block => block.match.state === 'matching'))
    - Number(a.blocks.some(block => block.match.state === 'matching')));
  return Object.freeze({entries: Object.freeze(entries), checks: Object.freeze(checks), validation,
    sequences: Object.freeze(ordered)});
}

/**
 * The single assurance label for what Studio can say about the current draft (ADR-031.2 §6.1), derived from every
 * matching report regardless of import order. For a composite extracted from a blueprint the wording never says
 * "validated": project rendering derives its own context from the blueprint and decides (contract C8).
 *
 * @param {object} assessment result of {@link assessReports}
 * @param {{origin?: string}} [document] the open document's origin
 * @returns {{state: 'draft'|'matching-report'|'rejected-report'|'stale-report'|'rehearsed', label: string}}
 */
export function assuranceState(assessment, {origin} = {}) {
  const blueprint = origin === 'blueprint';
  const matchingChecks = assessment.checks.filter(entry => entry.match.state === 'matching');
  const blocks = assessment.sequences.flatMap(sequence => sequence.blocks);
  const validationFailures = matchingChecks.filter(entry => entry.stage === 'validation');
  const rehearsalFailures = matchingChecks.filter(entry => entry.stage === 'rehearsal');
  // Blocks whose validation inputs match but whose own rehearsal is not verified (a replaced or missing fixture, or
  // an unverified predecessor) are named next to any verified ones, never folded into a stronger status.
  const unverifiedBlocks = blocks.filter(block => block.match.state === 'matching' && block.match.rehearsal !== 'matching');
  const failedRuns = (rehearsalFailures.length
    ? ` ${rehearsalFailures.length} matching dry-run report${rehearsalFailures.length === 1 ? '' : 's'} failed on the fixture or continuation.`
    : '') + (unverifiedBlocks.length
    ? ` ${unverifiedBlocks.length} rehearsal block${unverifiedBlocks.length === 1 ? ' is' : 's are'} stale or unverified.`
    : '');
  const scope = blueprint ? 'the composite extracted from this blueprint, under the context you supplied; project '
    + 'rendering derives its own context from the blueprint and decides' : null;
  if (validationFailures.length) {
    return {state: 'rejected-report', label: blueprint
      ? `Imported matching CLI report: it says the CLI rejected ${scope}.`
      : 'Imported matching CLI report: it says the CLI rejected exactly the current inputs.'};
  }
  if (blocks.some(block => block.match.state === 'matching' && block.match.rehearsal === 'matching')) {
    // A completed dry-run compiled and validated the document before executing it.
    return {state: 'rehearsed', label: (blueprint
      ? `Rehearsed source outcomes for ${scope}.`
      : 'Rehearsed source outcomes: the reports give real engine results for the identified, assumed fixture inputs.') + failedRuns};
  }
  if (matchingChecks.length || blocks.some(block => block.match.state === 'matching')) {
    return {state: 'matching-report', label: (blueprint
      ? `Imported matching CLI report for ${scope}.`
      : 'Imported matching CLI report: it says the exact current inputs validated under the named catalog.') + failedRuns};
  }
  if (assessment.entries.length) {
    return {state: 'stale-report', label: 'Stale or unverifiable reports: none of them describes this draft.'};
  }
  return {state: 'draft', label: 'Draft with local checks only; no Java validation.'};
}
