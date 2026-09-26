/**
 * Editor session for the guided binding editor (ADR-031.2 §4, §5.1, contracts C1, C5 and C8).
 *
 * One structured draft sits behind the forms, the graph and the YAML view. The session owns the rules that keep
 * them honest: structured edits re-emit canonical YAML (after the author has acknowledged that comments and
 * formatting will be lost) and are accepted only when that YAML re-imports to the same draft; text edits update
 * the draft only when the text is a complete, representable document, otherwise the last valid draft is kept and
 * export is refused; unsupported imports are read-only and only their original bytes can be downloaded; a draft
 * is bound to one catalog's context and plugin identity. Nothing here touches the DOM, the network or browser
 * storage, and nothing decides validity: advisory checks help authors and the Java CLI stays the authority.
 */
import {catalogBinding,importAuthoringCatalog} from './binding-catalog.mjs';
import {checkDraft} from './binding-check.mjs';
import {cloneDraft,DraftImportError,emitDocument,emptyDraft,importDocument} from './binding-draft.mjs';
import {BlueprintError,draftForChain,readBlueprint,spliceComposite} from './binding-blueprint.mjs';
import {autoLayout,exportLayout,graphModel,importLayout} from './binding-layout.mjs';
import {importReport} from './binding-report.mjs';
import {decodeUtf8Strict} from './lossless-json.mjs';
import {sha256Hex,sha256TextHex} from './sha256.mjs';

/** Fixed export names: the CLI handoff never derives a file name from document content. */
export const EXPORT_NAMES = Object.freeze({document: 'bindings.yaml', blueprint: 'appchain.yaml',
  layout: 'bindings.layout.json', original: 'original-bindings.yaml', originalBlueprint: 'original-appchain.yaml'});
/** Binding YAML bound (contract C7), in UTF-16 units; file bytes are also bounded before decoding. */
export const MAX_DOCUMENT_CHARACTERS = 262_144;
export const MAX_DOCUMENT_BYTES = 1024 * 1024;
/** Imported CLI reports and fixture digests kept per session (never persisted). */
export const MAX_REPORTS = 64;
export const MAX_FIXTURE_BYTES = 32 * 1024 * 1024;

/** A session operation that the current state does not allow; `code` is stable for UI messages and tests. */
export class SessionError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'SessionError';
    this.code = code;
  }
}

/**
 * Decodes an imported file strictly (UTF-8, bounded) without echoing content in errors.
 *
 * @param {Uint8Array|string} input file bytes or text
 * @param {number} maxBytes byte bound checked before decoding
 */
export function fileText(input, maxBytes = MAX_DOCUMENT_BYTES) {
  if (typeof input === 'string') {
    if (input.length > maxBytes) throw new SessionError('FILE_TOO_LARGE', 'The file is too large');
    return input;
  }
  if (input.byteLength > maxBytes) throw new SessionError('FILE_TOO_LARGE', 'The file is too large');
  return decodeUtf8Strict(input);
}

function errorView(error) {
  return Object.freeze({code: error.code ?? 'INVALID', message: error.message,
    line: error.line ?? null, column: error.column ?? null, segments: error.segments ?? null});
}

/** Exact original file content: bytes when the file was read as bytes (a BOM or other bytes are kept). */
function original(input, text) {
  return typeof input === 'string' ? {text, bytes: null} : {text, bytes: input.slice()};
}

export class BindingSession {
  /**
   * @param {{catalog?: object, catalogOrigin?: string, catalogName?: string}} [options] initial catalog
   */
  constructor(options = {}) {
    this.listeners = new Set();
    this.catalog = null;
    this.catalogOrigin = null;
    this.catalogName = null;
    this.binding = null;
    this.blueprint = null;
    this.layout = {nodes: new Map(), collapsed: new Set()};
    this.selection = {kind: 'document', index: null};
    // Every change to the draft or its text increments `revision`; changes that may move, add, remove or retype
    // items also set `structuralRevision`. Controls drawn at an older revision use these to detect staleness.
    this.revision = 0;
    this.structuralRevision = 0;
    // Imported CLI reports ({name, report}) and fixtures. `fixtures` is the active selection, one SHA-256 per file
    // name (file name → sha256; the handoff's names are fixed); `fixtureHistory` (sha256 → file names) remembers
    // earlier imports, so a report rehearsed with content that a later import of the same name replaced reads as
    // stale. Matches are recomputed on every read, so edits and catalog changes make reports stale without clearing.
    this.reports = [];
    this.fixtures = new Map();
    this.fixtureHistory = new Map();
    if (options.catalog) this.useCatalog(options.catalog, options.catalogOrigin ?? 'imported', options.catalogName ?? '');
    this.newDocument();
  }

  /** Registers a change listener; returns an unsubscribe function. */
  subscribe(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  changed(reason) {
    for (const listener of this.listeners) listener(reason);
  }

  bump(structural) {
    this.revision += 1;
    if (structural) this.structuralRevision = this.revision;
  }

  // -------------------------------------------------------------------------------------------------------------
  // Documents
  // -------------------------------------------------------------------------------------------------------------

  /** Starts an empty wrapped document. */
  newDocument() {
    const draft = emptyDraft();
    this.setDocument({name: 'New document', origin: 'new', original: null, draft,
      text: emitDocument(draft), lineEnding: '\n'});
    this.blueprint = null;
    this.changed('document');
  }

  /**
   * Loads document text from a starter or a file. Editable documents keep their exact text until the first
   * structured edit; syntax-invalid and unsupported documents open without a structured draft.
   *
   * @returns {'editable'|'read-only'|'syntax-error'} the resulting mode
   */
  loadText(input, {name, origin}) {
    const text = fileText(input);
    if (text.length > MAX_DOCUMENT_CHARACTERS) throw new SessionError('DOCUMENT_TOO_LARGE', 'Binding documents are limited to 262,144 characters');
    this.blueprint = null;
    const imported = this.openText(text, {name, origin, original: original(input, text)});
    this.changed('document');
    return imported;
  }

  /** Opens text as the document; returns `editable`, `read-only` or `syntax-error`. */
  openText(text, {name, origin, original: source}) {
    const imported = importDocument(text);
    if (imported.state === 'editable') {
      this.setDocument({name, origin, original: source, draft: imported.draft, text, lineEnding: imported.lineEnding});
    } else {
      // Unsupported constructs open read-only; broken syntax stays editable as text, with no draft yet.
      this.document = {name, origin, original: source, mode: imported.state === 'read-only' ? 'read-only' : 'text-invalid',
        draft: null, baseline: null, text, lineEnding: '\n', error: errorView(imported.error),
        formattingAcknowledged: false, edited: false};
      this.selection = {kind: 'document', index: null};
      this.bump(true);
    }
    return imported.state;
  }

  setDocument({name, origin, original: source, draft, text, lineEnding}) {
    this.document = {name, origin, original: source, mode: 'editable', draft, baseline: cloneDraft(draft), text,
      lineEnding, error: null, formattingAcknowledged: false, edited: false};
    this.selection = {kind: 'document', index: null};
    this.bump(true);
  }

  get mode() { return this.document.mode; }
  get draft() { return this.document.draft; }
  get text() { return this.document.text; }

  /** Whether forms may change the draft now; text that no longer parses must be fixed or reverted first. */
  get editable() { return this.document.mode === 'editable'; }

  /** Whether anything was edited since the document was opened, reset or started. */
  get dirty() { return this.document.edited; }

  /**
   * Whether the next structured edit would rewrite author formatting or comments. The editor must obtain an
   * explicit acknowledgement first (ADR-031.2 §5.1); the original text stays downloadable. For a blueprint
   * composite the comparison is against the blueprint itself: re-splicing the unedited draft must reproduce it.
   */
  get formattingWouldChange() {
    const document = this.document;
    if (document.mode !== 'editable' || document.formattingAcknowledged) return false;
    if (document.origin === 'blueprint') return document.blueprintFormattingChanges;
    return emitDocument(document.draft, {lineEnding: document.lineEnding}) !== document.text;
  }

  /** Records that the author accepted canonical re-emission for this document. */
  acknowledgeFormatting() {
    this.document.formattingAcknowledged = true;
  }

  /**
   * Applies one structured edit (a pure function from `binding-edit.mjs`) and re-emits canonical YAML. The edit is
   * accepted only when that YAML re-imports as an editable document that emits identically, so every draft Studio
   * produces can be downloaded and reopened without loss.
   *
   * @param {(draft: object) => object} operation
   * @param {{structural?: boolean}} [options] `structural: false` for edits that replace one leaf value only
   * @throws {SessionError} when the text is invalid, the document is read-only, formatting is unacknowledged or
   *         the result could not be represented
   */
  edit(operation, {structural = true} = {}) {
    if (!this.editable) {
      throw new SessionError(this.document.mode === 'read-only' ? 'READ_ONLY' : 'TEXT_INVALID',
        this.document.mode === 'read-only' ? 'This document is read-only' : 'Fix or revert the YAML text first');
    }
    if (this.formattingWouldChange) {
      throw new SessionError('FORMATTING_UNACKNOWLEDGED', 'Structured edits rewrite YAML formatting and comments');
    }
    const next = operation(this.document.draft);
    let text;
    try {
      text = emitDocument(next, {lineEnding: this.document.lineEnding});
    } catch (error) {
      throw new SessionError('EDIT_UNREPRESENTABLE', `That change cannot be written as a binding document: ${error.message}`);
    }
    if (text.length > MAX_DOCUMENT_CHARACTERS) {
      throw new SessionError('EDIT_UNREPRESENTABLE', 'That change would exceed the 262,144-character document limit');
    }
    const reread = importDocument(text);
    if (reread.state !== 'editable' || emitDocument(reread.draft, {lineEnding: this.document.lineEnding}) !== text) {
      throw new SessionError('EDIT_UNREPRESENTABLE', `That change could not be reopened safely: ${reread.error?.message ?? 'it reads back differently'}`);
    }
    // Keep the draft exactly as the emitted text reads back, so text and draft can never disagree.
    this.document.draft = reread.draft;
    this.document.text = text;
    this.document.formattingAcknowledged = true;
    this.document.edited = true;
    this.bump(structural);
    this.changed('draft');
  }

  /**
   * Replaces the YAML text. A complete, representable document becomes the draft; anything else keeps the last
   * valid draft and marks the text invalid so that neither forms nor export can use the stale model.
   *
   * @returns {'editable'|'syntax-error'|'unsupported'} the text state
   */
  setText(text) {
    if (this.document.mode === 'read-only' && this.document.draft === null) {
      throw new SessionError('READ_ONLY', 'This document is read-only');
    }
    this.document.text = text;
    this.document.edited = true;
    this.bump(true);
    if (text.length > MAX_DOCUMENT_CHARACTERS) {
      this.document.mode = 'text-invalid';
      this.document.error = errorView(new SessionError('DOCUMENT_TOO_LARGE', 'Binding documents are limited to 262,144 characters'));
      this.changed('text');
      return 'syntax-error';
    }
    const imported = importDocument(text);
    if (imported.state === 'editable') {
      this.document.mode = 'editable';
      this.document.draft = imported.draft;
      this.document.error = null;
      // The author wrote this text; a later structured edit re-emits it and needs a fresh acknowledgement.
      this.document.formattingAcknowledged = false;
      this.document.blueprintFormattingChanges = true;
      if (this.document.baseline === null) this.document.baseline = cloneDraft(imported.draft);
      this.changed('text');
      return 'editable';
    }
    this.document.mode = 'text-invalid';
    this.document.error = errorView(imported.error);
    this.changed('text');
    return imported.state === 'read-only' ? 'unsupported' : 'syntax-error';
  }

  /** Whether there is a last valid draft to return to. */
  get canRevertText() {
    return this.document.mode === 'text-invalid' && this.document.draft !== null;
  }

  /** Discards invalid text and shows canonical YAML for the last valid draft. */
  revertText() {
    if (!this.canRevertText) throw new SessionError('NO_VALID_DRAFT', 'There is no valid draft to revert to');
    this.document.text = emitDocument(this.document.draft, {lineEnding: this.document.lineEnding});
    this.document.mode = 'editable';
    this.document.error = null;
    this.bump(true);
    this.changed('text');
  }

  /** Restores the document exactly as it was opened, discarding every edit (including repairs of invalid text). */
  reset() {
    const document = this.document;
    if (document.origin === 'blueprint') {
      this.blueprint = document.blueprint;
      this.selectChain(document.blueprint.chainIndex);
      return;
    }
    if (document.origin === 'blueprint-pending') return;
    if (document.original) {
      this.openText(document.original.text, {name: document.name, origin: document.origin, original: document.original});
    } else {
      this.setDocument({name: document.name, origin: document.origin, original: null, draft: cloneDraft(document.baseline),
        text: emitDocument(document.baseline, {lineEnding: document.lineEnding}), lineEnding: document.lineEnding});
    }
    this.changed('document');
  }

  /**
   * The exact text to hand to the CLI. Refused unless the text is a complete editable document, so a stale draft
   * is never exported as if it were the text.
   */
  exportDocument() {
    if (this.document.mode !== 'editable') {
      throw new SessionError(this.document.mode === 'read-only' ? 'READ_ONLY' : 'TEXT_INVALID',
        this.document.mode === 'read-only' ? 'Only the original bytes of a read-only document can be downloaded'
          : 'The YAML text is not a valid document; fix or revert it before exporting');
    }
    return {name: EXPORT_NAMES.document, text: this.document.text};
  }

  /** SHA-256 of the exact bytes `exportDocument` would produce, or null when export is refused. */
  exportedDocumentSha256() {
    return this.document.mode === 'editable' ? sha256TextHex(this.document.text) : null;
  }

  /** The original imported file, byte for byte, available for every imported document including read-only ones. */
  originalDocument() {
    const blueprint = this.document.blueprint;
    if (blueprint) return {name: EXPORT_NAMES.originalBlueprint, text: blueprint.text, bytes: blueprint.bytes};
    if (!this.document.original) throw new SessionError('NO_ORIGINAL', 'This document was not imported');
    return {name: EXPORT_NAMES.original, text: this.document.original.text, bytes: this.document.original.bytes};
  }

  // -------------------------------------------------------------------------------------------------------------
  // Catalogs
  // -------------------------------------------------------------------------------------------------------------

  /** Uses an already imported catalog and binds the draft to its context and plugin identity. */
  useCatalog(catalog, origin, name) {
    this.catalog = catalog;
    this.catalogOrigin = origin;
    this.catalogName = name;
    this.binding = catalogBinding(catalog);
    this.changed('catalog');
  }

  /** Imports catalog bytes; a malformed catalog leaves the current one in place. */
  importCatalog(bytes, name) {
    const catalog = importAuthoringCatalog(bytes);
    this.useCatalog(catalog, 'imported', name);
    return catalog;
  }

  /**
   * Advisory checks for the current draft against the bound catalog. They are hints only, so a failure inside them
   * is reported as one advisory diagnostic instead of interrupting the editor.
   */
  diagnostics() {
    if (!this.document.draft) return [];
    try {
      return checkDraft(this.document.draft, this.catalog, {expected: this.binding});
    } catch (error) {
      return [{code: 'CHECKS_INCOMPLETE', severity: 'info', segments: [],
        message: `Studio could not finish its advisory checks (${error.message}); the CLI still validates the document`}];
    }
  }

  // -------------------------------------------------------------------------------------------------------------
  // Blueprints
  // -------------------------------------------------------------------------------------------------------------

  /**
   * Reads an application blueprint. When exactly one chain carries a declarative composite it is opened;
   * otherwise the caller asks the author to choose with {@link selectChain}.
   *
   * @returns {Array<{chainIndex, chainId}>} chains with a composite
   */
  importBlueprint(input, name) {
    const text = fileText(input);
    const blueprint = readBlueprint(text);
    if (!blueprint.composites.length) {
      throw new BlueprintError('BLUEPRINT_NO_COMPOSITE', 'No chain in this blueprint has an inline composite to edit. '
        + 'Studio edits existing inline composites; it does not add one to a chain.');
    }
    this.blueprint = {...blueprint, name, bytes: original(input, text).bytes, chainIndex: null};
    const chains = blueprint.composites.map(entry => ({chainIndex: entry.chainIndex, chainId: entry.chainId}));
    if (chains.length === 1) {
      this.selectChain(chains[0].chainIndex);
    } else {
      // Nothing is open until the author chooses a chain; the previous document is closed, not left attached.
      this.document = {name: `${name} · choose a chain`, origin: 'blueprint-pending', original: null,
        blueprint: this.blueprint, mode: 'read-only', draft: null, baseline: null, text: '', lineEnding: '\n',
        error: errorView(new SessionError('CHOOSE_CHAIN', 'Choose which chain’s composite to edit')),
        formattingAcknowledged: false, edited: false};
      this.selection = {kind: 'document', index: null};
      this.bump(true);
      this.changed('blueprint');
    }
    return chains;
  }

  /** Opens one blueprint chain's composite as the draft; the rest of the blueprint is never edited. */
  selectChain(chainIndex) {
    if (!this.blueprint) throw new SessionError('NO_BLUEPRINT', 'No blueprint is loaded');
    const entry = this.blueprint.composites.find(value => value.chainIndex === chainIndex);
    if (!entry) throw new SessionError('NO_BLUEPRINT', 'That chain has no inline composite');
    const chainId = entry.chainId;
    const blueprint = this.blueprint;
    let draft;
    try {
      draft = draftForChain(blueprint, chainIndex);
    } catch (error) {
      if (!(error instanceof DraftImportError)) throw error;
      // An unsupported composite opens read-only with its exact location; only the blueprint can be downloaded.
      this.blueprint = {...blueprint, chainIndex};
      this.document = {name: `${blueprint.name} · chain ${chainId}`, origin: 'blueprint', original: null,
        blueprint: this.blueprint, mode: 'read-only', draft: null, baseline: null, text: '', lineEnding: '\n',
        error: errorView(error), formattingAcknowledged: false, edited: false};
      this.selection = {kind: 'document', index: null};
      this.bump(true);
      this.changed('document');
      return;
    }
    this.setDocument({name: `${blueprint.name} · chain ${chainId}`, origin: 'blueprint', original: null, draft,
      text: emitDocument(draft), lineEnding: '\n'});
    this.blueprint = {...blueprint, chainIndex};
    this.document.blueprint = this.blueprint;
    // Structured edits re-splice canonical YAML; that loses comments or formatting unless it reproduces the file.
    let unchanged = false;
    try { unchanged = spliceComposite(this.blueprint, chainIndex, draft) === blueprint.text; }
    catch { unchanged = false; }
    this.document.blueprintFormattingChanges = !unchanged;
    this.changed('document');
  }

  /**
   * The blueprint with only the selected composite replaced, verified by re-reading (C8). An unedited composite
   * returns the original blueprint unchanged.
   *
   * @throws {BlueprintError|SessionError} when the splice cannot be proven safe; export the document alone instead
   */
  exportBlueprint() {
    const blueprint = this.document.blueprint;
    if (this.document.origin !== 'blueprint' || !blueprint) throw new SessionError('NO_BLUEPRINT', 'No blueprint chain is open');
    this.exportDocument();
    if (!this.document.edited) return {name: EXPORT_NAMES.blueprint, text: blueprint.text, bytes: blueprint.bytes};
    return {name: EXPORT_NAMES.blueprint, text: spliceComposite(blueprint, blueprint.chainIndex, this.document.draft)};
  }

  // -------------------------------------------------------------------------------------------------------------
  // Imported CLI results (unauthenticated)
  // -------------------------------------------------------------------------------------------------------------

  /** Imports one report file; a malformed or contradictory report is rejected and nothing changes. */
  importReportFile(bytes, name) {
    if (this.reports.length >= MAX_REPORTS) throw new SessionError('TOO_MANY_REPORTS', `At most ${MAX_REPORTS} reports can be imported`);
    const report = importReport(bytes);
    const digest = sha256Hex(typeof bytes === 'string' ? new TextEncoder().encode(bytes) : bytes);
    if (this.reports.some(entry => entry.digest === digest)) {
      throw new SessionError('DUPLICATE_REPORT', 'This report is already imported');
    }
    this.reports = [...this.reports, Object.freeze({name, report, digest})];
    this.changed('reports');
    return report;
  }

  removeReport(index) {
    this.reports = this.reports.filter((_, position) => position !== index);
    this.changed('reports');
  }

  clearReports() {
    this.reports = [];
    this.changed('reports');
  }

  clearFixtures() {
    this.fixtures = new Map();
    this.fixtureHistory = new Map();
    this.changed('reports');
  }

  /**
   * Records the SHA-256 of a fixture file as the active content for its file name, replacing any earlier import of
   * that name, so rehearsal reports can be matched to it. The bytes are not parsed or kept: Studio never interprets
   * fixture content, it only compares exact digests.
   *
   * @returns {boolean} whether an earlier import of the same name with different content was replaced
   */
  importFixtureFile(bytes, name) {
    if (bytes.byteLength > MAX_FIXTURE_BYTES) throw new SessionError('FILE_TOO_LARGE', 'Fixture files are limited to 32 MiB');
    const digest = sha256Hex(bytes);
    const previous = this.fixtures.get(name);
    this.fixtures.set(name, digest);
    this.fixtureHistory.set(digest, new Set([...(this.fixtureHistory.get(digest) ?? []), name]));
    this.changed('reports');
    return previous !== undefined && previous !== digest;
  }

  /** The SHA-256 digests of the active fixture selection. */
  fixtureDigests() {
    return new Set(this.fixtures.values());
  }

  /**
   * Earlier fixture digests that later imports of the same file names replaced with different content, each with
   * those names and a digest now active under one of them.
   *
   * @returns {Map<string, {names: string[], current: string}>}
   */
  replacedFixtures() {
    const active = this.fixtureDigests();
    const replaced = new Map();
    for (const [digest, names] of this.fixtureHistory) {
      if (active.has(digest)) continue;
      const replacedNames = [...names].filter(name => this.fixtures.has(name));
      if (replacedNames.length) replaced.set(digest, {names: replacedNames, current: this.fixtures.get(replacedNames[0])});
    }
    return replaced;
  }

  // -------------------------------------------------------------------------------------------------------------
  // Graph and layout (presentation only)
  // -------------------------------------------------------------------------------------------------------------

  /** Graph model with positions: sidecar positions where present, deterministic layout otherwise. */
  graph() {
    const model = graphModel(this.document.draft ?? emptyDraft());
    const automatic = autoLayout(model);
    const positions = new Map(model.nodes.map(node => [node.id, this.layout.nodes.get(node.id) ?? automatic.get(node.id)]));
    return {...model, positions};
  }

  /** Moves a node; the draft, its YAML and its identity are unaffected. */
  moveNode(id, x, y) {
    const bound = value => Math.max(-1_000_000, Math.min(1_000_000, Math.round(value)));
    this.layout.nodes.set(id, {x: bound(x), y: bound(y)});
    this.changed('layout');
  }

  resetLayout() {
    this.layout = {nodes: new Map(), collapsed: new Set()};
    this.changed('layout');
  }

  importLayout(bytes) {
    this.layout = importLayout(bytes);
    this.changed('layout');
  }

  exportLayout() {
    return {name: EXPORT_NAMES.layout, text: exportLayout(this.layout)};
  }

  // -------------------------------------------------------------------------------------------------------------
  // Selection
  // -------------------------------------------------------------------------------------------------------------

  select(kind, index = null) {
    this.selection = {kind, index};
    this.changed('selection');
  }
}
