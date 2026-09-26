/**
 * DOM for the guided binding editor (ADR-031.2 §4). Every control edits the one draft through the pure
 * operations in `binding-edit.mjs` via the session; the graph selects forms and moves layout only. Content from
 * documents, catalogs and files is rendered as text, never as markup. Nothing here uses the network beyond
 * reading Studio's own static assets, and nothing is persisted in the browser.
 */
import {BASELINE_EVENT,componentInstance} from './binding-check.mjs';
import {findInstance} from './binding-catalog.mjs';
import {LIMIT_NAMES,OPERATORS,renderPath} from './binding-draft.mjs';
import * as edits from './binding-edit.mjs';
import {EXPORT_NAMES,fileText,SessionError} from './binding-session.mjs';
import {displayText,h,put,svg} from './binding-dom.mjs';
import {STARTERS, STARTER_BASE} from './binding-starters.mjs';
import {renderValidation} from './binding-validation-view.mjs';
import {visibleText} from './binding-explain.mjs';

export {STARTERS, STARTER_BASE};

const ID = /^[a-z][a-z0-9-]{0,62}$/;
const INT64 = /^-?(?:0|[1-9][0-9]*)$/;
const MIN_INT64 = -(2n ** 63n);
const MAX_INT64 = 2n ** 63n - 1n;
const HEX = /^(?:[0-9a-fA-F]{2})*$/;
const TYPES = ['text', 'integer', 'bytes', 'boolean'];
const NODE_WIDTH = 190;
const NODE_HEIGHT = 62;
/** The text a textarea reports for `text`: browsers present every CR LF and lone CR as LF. */
const shownText = text => text.replace(/\r\n?/g, '\n');

const TABS = Object.freeze(['form', 'yaml', 'graph', 'validate']);

/** Parses canonical signed 64-bit decimal text without passing through Number; null when invalid. */
export function parseInt64(text) {
  if (!INT64.test(text) || text === '-0') return null;
  const value = BigInt(text);
  return value < MIN_INT64 || value > MAX_INT64 ? null : value;
}

const key = value => encodeURIComponent(value);
const plural = (count, word) => `${count} ${word}${count === 1 ? '' : 's'}`;
const short = hex => hex ? `${hex.slice(0, 12)}…` : 'not recorded';
const truncate = (text, length) => text.length > length ? `${text.slice(0, length - 1)}…` : text;

function literalText(value) {
  if (value.type === 'bytes') return `bytes 0x${truncate(value.hex, 24)}`;
  if (value.type === 'integer') return value.value.toString();
  if (value.type === 'boolean') return String(value.value);
  return JSON.stringify(truncate(value.value, 40));
}

function defaultLiteral(type) {
  if (type === 'integer') return {type, value: 0n};
  if (type === 'boolean') return {type, value: true};
  if (type === 'bytes') return {type, hex: ''};
  return {type: 'text', value: ''};
}

/** The value at a draft-model path, or undefined. */
function valueAt(draft, path) {
  return path.reduce((node, segment) => node?.[segment], draft);
}

export class BindingEditor {
  /**
   * @param {BindingSession} session editor session
   * @param {{download: Function, fetchBytes: Function, bundledCatalog: object|null}} services static-asset
   *        reading and file download; no other side effects are available to the editor
   */
  constructor(session, services) {
    this.session = session;
    this.services = services;
    this.tab = 'form';
    this.pendingFocus = null;
    this.pendingPath = null;
    this.typing = false;
    this.renderQueued = false;
    this.textTimer = null;
    this.handoffBlocks = 0;
    // Content replaced by a kind switch, kept so switching back restores it (never written to the document).
    this.stash = new Map();
    session.subscribe(reason => {
      if (reason === 'document' || reason === 'blueprint' || reason === 'text') this.stash.clear();
      this.schedule(reason);
    });
    this.wire();
    this.render('init');
  }

  el(id) { return document.getElementById(id); }

  /**
   * Renders after the current event completes, so tab navigation lands before controls are rebuilt. While a
   * render is pending the page carries `data-render-pending`; handlers still firing from the previous DOM edit
   * only their own value (see `apply`).
   */
  schedule(reason) {
    this.lastReason = reason;
    if (this.renderQueued) return;
    this.renderQueued = true;
    document.documentElement.dataset.renderPending = 'true';
    setTimeout(() => { this.renderQueued = false; this.render(this.lastReason); }, 0);
  }

  announce(message, error = false) {
    const status = this.el('status');
    // Messages can name draft ids, file names or importer text, so they are escaped like all displayed text.
    status.textContent = displayText(message);
    status.classList.toggle('error', error);
    // Refusals and failures are also announced assertively.
    this.el('alert').textContent = error ? displayText(message) : '';
  }

  wire() {
    this.el('add-component').addEventListener('click', () => this.addComponent());
    this.el('add-binding').addEventListener('click', () => this.addBinding());
    this.el('select-limits').addEventListener('click', () => this.select('limits', null, 'limit-maxCascadeDepth'));
    for (const name of TABS) {
      const tab = this.el(`tab-${name}`);
      tab.addEventListener('click', () => this.showTab(name));
      tab.addEventListener('keydown', event => {
        const index = TABS.indexOf(name);
        const next = event.key === 'ArrowRight' ? TABS[(index + 1) % TABS.length] : event.key === 'ArrowLeft'
          ? TABS[(index + TABS.length - 1) % TABS.length] : event.key === 'Home' ? TABS[0]
            : event.key === 'End' ? TABS.at(-1) : null;
        if (next) { event.preventDefault(); this.showTab(next); this.el(`tab-${next}`).focus(); }
      });
    }
    const text = this.el('yaml-text');
    text.addEventListener('input', () => {
      clearTimeout(this.textTimer);
      this.textTimer = setTimeout(() => this.applyText(), 250);
    });
    text.addEventListener('change', () => { clearTimeout(this.textTimer); this.applyText(); });
    this.el('revert-text').addEventListener('click', () => {
      try { this.session.revertText(); this.announce('Showing the last valid draft.'); }
      catch (error) { this.announce(error.message, true); }
    });
  }

  applyText() {
    const text = this.el('yaml-text');
    if (text.readOnly || text.value === shownText(this.session.text)) return;
    this.typing = true;
    try {
      // A textarea reports every line break as LF; typing keeps the document's own line ending.
      const state = this.session.setText(this.session.document.lineEnding === '\r\n'
        ? text.value.replace(/\n/g, '\r\n') : text.value);
      this.announce(state === 'editable' ? 'The draft now follows the YAML text.'
        : 'The YAML text is not a valid document; forms and export use nothing from it.', state !== 'editable');
    } catch (error) {
      this.announce(error.message, true);
    }
  }

  showTab(name) {
    this.tab = name;
    for (const other of TABS) {
      const tab = this.el(`tab-${other}`);
      tab.setAttribute('aria-selected', String(other === name));
      tab.tabIndex = other === name ? 0 : -1;
      this.el(`panel-${other}`).hidden = other !== name;
    }
    this.render('tab');
  }

  select(kind, index, focus) {
    this.pendingFocus = focus ?? null;
    if (this.tab !== 'form') this.showTab('form');
    this.session.select(kind, index);
  }

  // -------------------------------------------------------------------------------------------------------------
  // Editing
  // -------------------------------------------------------------------------------------------------------------

  /**
   * Applies one draft operation after any required formatting acknowledgement. Operations read the current
   * draft they are given, never values captured when the form was drawn. Every control belongs to the form drawn
   * at session revision `epoch`. If the draft changed since, a leaf edit is still exact (its path addresses the
   * same item) unless a structural change intervened; a structural edit from an older form is always refused
   * rather than applied to whatever now sits at its index. The revision is rechecked after any dialog.
   *
   * @param {(draft: object) => object} operation pure edit
   * @param {string} [focus] element id to focus after the redraw
   * @param {string} [message] status announcement
   * @param {{structural?: boolean, epoch?: number}} [options] `structural: false` only for one-leaf edits;
   *        `epoch` defaults to the revision of the form currently on screen
   */
  async apply(operation, focus, message, {structural = true, epoch = this.epoch, kindSwitch = false} = {}) {
    const session = this.session;
    // Leaf edits never move, add, remove or retype items, so every control drawn at `epoch` still addresses the
    // same item until a structural edit happens (for example typing in a field and then clicking a button).
    const current = () => session.structuralRevision <= epoch;
    const refuse = () => {
      this.announce('The form changed before that edit was applied, so it was not applied. Please repeat it.', true);
      this.schedule('stale');
      return false;
    };
    if (!session.editable) {
      this.announce(session.mode === 'read-only' ? 'This document is read-only.' : 'Fix or revert the YAML text first.', true);
      return false;
    }
    if (!current()) return refuse();
    if (session.formattingWouldChange) {
      const accepted = await this.confirm('Rewrite YAML formatting?', [
        'Structured edits write canonical YAML. Comments, quoting and spacing in the imported text are not kept.',
        'The original file stays available with “Download original”. The committed program only changes where you edit it.'],
      'Continue editing', 'Cancel');
      if (!accepted) { this.render('cancelled'); return false; }
      if (!current()) return refuse();
      session.acknowledgeFormatting();
    }
    try {
      // Without an explicit target, focus stays wherever the user moved it (a change event fires on Tab).
      if (focus) this.pendingFocus = focus;
      session.edit(operation, {structural});
      // Kept content serves only immediate switching back; any other structural change could make it refer to
      // a different item, so it is discarded.
      if (structural && !kindSwitch) this.stash.clear();
      if (message) this.announce(message);
      return true;
    } catch (error) {
      this.announce(error.message, true);
      this.render('failed');
      return false;
    }
  }

  /**
   * Sets one value at a draft-model path. `structural` marks values that change which controls exist or how they
   * are typed (a literal type, a source kind, a target or mapping kind, an operator or expectation).
   */
  set(path, value, focus, structural = false) {
    return this.apply(draft => edits.setValue(draft, path, value), focus, null, {structural});
  }

  /** Replaces one value with a function of its current value; structural unless stated otherwise. */
  update(path, change, focus, structural = true) {
    return this.apply(draft => edits.setValue(draft, path, change(valueAt(draft, path))), focus, null, {structural});
  }

  /**
   * Switches the kind of the value at `path` (a target, mapping, source, literal type or operator) without losing
   * work: the value being replaced is kept in memory under its kind, and switching back restores it. Arrow-key
   * navigation through radio buttons or options is therefore never destructive.
   *
   * @param {Array} path draft path of the value
   * @param {(value) => string} kindOf the value's current kind
   * @param {string} next kind to switch to
   * @param {(value) => object} fresh the value to use when nothing was stashed for `next`
   */
  switchKind(path, kindOf, next, fresh, focus) {
    return this.keptSwitch(path, current => {
      if (kindOf(current) === next) return current;
      const key = this.stashKey(path);
      const kept = this.stash.get(key) ?? {};
      kept[kindOf(current)] = structuredClone(current);
      this.stash.set(key, kept);
      return kept[next] ? structuredClone(kept[next]) : fresh(current);
    }, focus);
  }

  /** A structural edit that may use the kept content (the stash survives it). */
  keptSwitch(path, change, focus) {
    return this.apply(draft => edits.setValue(draft, path, change(valueAt(draft, path))), focus, null,
      {structural: true, kindSwitch: true});
  }

  /**
   * Stash keys name a binding or component by position and id together: kept content is cleared on every other
   * structural edit, and duplicate ids at different positions never share kept content.
   */
  stashKey(path) {
    const draft = this.session.draft;
    return JSON.stringify(path.map((segment, index) => index === 1 && path[0] === 'bindings'
      ? `b:${segment}:${draft.bindings[segment]?.id}`
      : index === 1 && path[0] === 'components' ? `c:${segment}:${draft.components[segment]?.id}` : segment));
  }

  confirm(title, paragraphs, acceptLabel, cancelLabel, extra = null) {
    const dialog = this.el('dialog');
    const returnFocus = document.activeElement;
    return new Promise(resolve => {
      const finish = value => { dialog.close(); resolve(value); if (returnFocus?.isConnected) returnFocus.focus(); };
      const accept = h('button', {type: 'button', id: 'dialog-accept', text: acceptLabel, onclick: () => finish(true)});
      const cancel = h('button', {type: 'button', class: 'secondary', id: 'dialog-cancel', text: cancelLabel,
        onclick: () => finish(false)});
      put(dialog, h('h2', {id: 'dialog-title', text: title}), paragraphs.map(text => h('p', {text})), extra,
        h('div', {class: 'dialog-actions'}, cancel, accept));
      dialog.oncancel = event => { event.preventDefault(); finish(false); };
      dialog.showModal();
      // A dialog that asks for text starts in its field, and Enter there accepts it.
      const field = extra?.querySelector?.('input');
      if (field) {
        field.addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); finish(true); } });
        field.focus();
        field.select();
      } else cancel.focus();
    });
  }

  addComponent() {
    const draft = this.session.draft;
    if (!draft) return;
    let n = draft.components.length + 1;
    while (draft.components.some(component => component.id === `component-${n}`)) n++;
    const index = draft.components.length;
    this.apply(value => edits.addComponent(value, {id: `component-${n}`, machine: ''}), null,
      `Added component component-${n}. Choose its machine.`).then(done => {
      if (done) this.select('component', index, `c${index}-machine`);
    });
  }

  addBinding() {
    const draft = this.session.draft;
    if (!draft) return;
    let n = draft.bindings.length + 1;
    while (draft.bindings.some(binding => binding.id === `binding-${n}`)) n++;
    const index = draft.bindings.length;
    const first = draft.components[0]?.id ?? '';
    const binding = {id: `binding-${n}`, from: {component: first, event: ''}, when: null,
      to: {kind: 'command', component: '', command: '', mapping: {kind: 'fields', assignments: []}}};
    this.apply(value => edits.addBinding(value, binding), null, `Added binding binding-${n}. Choose its source event.`)
      .then(done => { if (done) this.select('binding', index, `b${index}-source`); });
  }

  // -------------------------------------------------------------------------------------------------------------
  // Rendering
  // -------------------------------------------------------------------------------------------------------------

  render(reason) {
    const active = document.activeElement;
    const focusId = this.pendingFocus ?? (active && active !== document.body ? active.id : null);
    this.pendingFocus = null;
    this.normalizeSelection();
    // Controls created below belong to this revision of the draft (see `apply`).
    this.epoch = this.session.revision;
    // One failing panel must not leave the others stale or the page marked as still rendering; the failure is
    // logged and announced so it is never silent.
    let failed = false;
    const draw = part => {
      try { part(); } catch (error) {
        console.error(error);
        if (!failed) this.announce(`Part of the editor could not be drawn (${error.message}). Download your work.`, true);
        failed = true;
      }
    };
    try {
      for (const part of [() => this.renderDocumentPanel(), () => this.renderCatalogPanel(), () => this.renderLists(),
        () => this.renderHeading(), () => this.renderForm(), () => this.renderYaml(reason), () => this.renderGraph(),
        () => this.renderValidate(), () => this.renderChecks(), () => this.renderExport()]) draw(part);
      draw(() => {
        if (this.pendingPath) {
          this.focusPath(this.pendingPath);
          this.pendingPath = null;
        } else if (focusId) {
          const target = document.getElementById(focusId);
          if (target && target !== document.activeElement && !this.el('dialog').open) target.focus();
        }
      });
    } finally {
      if (!this.renderQueued) delete document.documentElement.dataset.renderPending;
    }
  }

  normalizeSelection() {
    const {kind, index} = this.session.selection;
    const draft = this.session.draft;
    const valid = kind === 'document' || kind === 'limits'
      || (draft && kind === 'component' && index < draft.components.length)
      || (draft && kind === 'binding' && index < draft.bindings.length);
    if (!valid) this.session.selection = {kind: 'document', index: null};
  }

  dirty() {
    return this.session.dirty;
  }

  async replaceDocument(load) {
    if (this.dirty() && !(await this.confirm('Replace the current draft?', [
      'Your edits to the current document are not saved anywhere. Download it first if you need it.'],
    'Replace draft', 'Keep editing'))) return;
    try { await load(); }
    catch (error) { this.announce(error.message, true); }
  }

  renderDocumentPanel() {
    const session = this.session;
    const document_ = session.document;
    const panel = this.el('document-panel');
    const starter = h('select', {id: 'starter-select', 'aria-label': 'Starter'},
      STARTERS.map(value => h('option', {value: value.id, text: value.label})));
    if (this.starterChoice) starter.value = this.starterChoice;
    starter.addEventListener('change', () => { this.starterChoice = starter.value; });
    const importYaml = h('input', {type: 'file', id: 'import-yaml', accept: '.yaml,.yml,text/yaml,text/plain'});
    importYaml.addEventListener('change', () => this.readFile(importYaml, bytes =>
      this.replaceDocument(async () => {
        const mode = session.loadText(bytes, {name: importYaml.files[0].name, origin: 'file'});
        this.announce(mode === 'editable' ? 'Imported the document.' : mode === 'read-only'
          ? 'Imported read-only: it uses a construct Studio cannot edit safely.' : 'The file is not valid YAML.', mode !== 'editable');
      })));
    const importBlueprint = h('input', {type: 'file', id: 'import-blueprint', accept: '.yaml,.yml,text/yaml,text/plain'});
    importBlueprint.addEventListener('change', () => this.readFile(importBlueprint, bytes =>
      this.replaceDocument(async () => {
        const chains = session.importBlueprint(bytes, importBlueprint.files[0].name);
        this.announce(chains.length === 1 ? `Opened the composite of chain ${chains[0].chainId}.`
          : `The blueprint has ${chains.length} composite chains; choose one below.`);
      })));
    const chooser = session.blueprint && session.blueprint.composites.length > 1 ? h('label', {},
      'Blueprint chain', h('select', {id: 'blueprint-chain', onchange: event => {
        if (event.target.value === '') return;
        this.replaceDocument(async () => session.selectChain(Number(event.target.value)));
      }},
      h('option', {value: '', text: 'Choose a chain…', selected: session.blueprint.chainIndex === null}),
      session.blueprint.composites.map(entry => h('option', {value: entry.chainIndex, text: entry.chainId,
        selected: entry.chainIndex === session.blueprint.chainIndex})))) : null;
    put(panel,
      h('h2', {id: 'document-heading', text: 'File'}),
      h('p', {class: 'note'}, h('strong', {text: visibleText(document_.name)}), ` · ${document_.origin}`),
      h('div', {class: 'stack'},
        h('button', {type: 'button', id: 'new-document', class: 'secondary', text: 'New document',
          onclick: () => this.replaceDocument(async () => { session.newDocument(); this.announce('Started a new document.'); })}),
        h('label', {}, 'Starter', starter),
        h('button', {type: 'button', id: 'load-starter', class: 'secondary', text: 'Load starter',
          onclick: () => this.replaceDocument(async () => {
            const chosen = STARTERS.find(value => value.id === starter.value);
            const bytes = await this.services.fetchBytes(`${STARTER_BASE}${chosen.file}`);
            session.loadText(bytes, {name: chosen.file, origin: 'starter'});
            this.announce(`Loaded the ${chosen.label} starter. ${chosen.description}`);
          })}),
        h('label', {class: 'file-button'}, 'Import binding YAML', importYaml),
        h('label', {class: 'file-button'}, 'Import appchain.yaml blueprint', importBlueprint),
        chooser,
        h('button', {type: 'button', id: 'reset-draft', class: 'secondary', text: 'Reset draft',
          disabled: !session.dirty, onclick: async () => {
            if (await this.confirm('Reset the draft?', ['Every edit since this document was loaded is discarded.'],
              'Reset draft', 'Keep edits')) { session.reset(); this.announce('The draft is back to how it was loaded.'); }
          }})));
  }

  /** Reads several selected files in order; each is imported independently and failures are reported by name. */
  readFiles(input, handler, summary) {
    const files = [...input.files];
    if (!files.length) return;
    (async () => {
      let imported = 0;
      const problems = [];
      for (const file of files.slice(0, 64)) {
        try {
          if (file.size > 32 * 1024 * 1024) throw new Error('the file is too large');
          handler(new Uint8Array(await file.arrayBuffer()), file.name);
          imported++;
        } catch (error) {
          problems.push(`${visibleText(file.name)}: ${error.message}`);
        }
      }
      if (files.length > 64) problems.push('only the first 64 files were read');
      this.announce([imported ? summary(imported) : null, ...problems].filter(Boolean).join(' '), problems.length > 0);
    })().finally(() => { input.value = ''; });
  }

  /** Downloads the exact text the CLI should compile. */
  downloadDocument() {
    try {
      this.services.download(this.session.exportDocument());
      this.announce(`Downloaded ${EXPORT_NAMES.document}.`);
    } catch (error) {
      this.announce(error.message, true);
    }
  }

  renderValidate() {
    const panel = this.el('panel-validate');
    if (this.tab !== 'validate') { put(panel); return; }
    renderValidation(this, panel);
  }

  readFile(input, handler) {
    const file = input.files[0];
    if (!file) return;
    if (file.size > 16 * 1024 * 1024) { this.announce('The file is too large.', true); input.value = ''; return; }
    file.arrayBuffer().then(buffer => handler(new Uint8Array(buffer)))
      .catch(error => this.announce(error.message, true))
      .finally(() => { input.value = ''; });
  }

  renderCatalogPanel() {
    const session = this.session;
    const catalog = session.catalog;
    const panel = this.el('catalog-panel');
    const input = h('input', {type: 'file', id: 'import-catalog', accept: '.json,application/json'});
    input.addEventListener('change', () => this.readFile(input, bytes => {
      try {
        session.importCatalog(bytes, input.files[0].name);
        this.announce('Imported the catalog. Descriptors and checks now come from it; review every component.');
      } catch (error) { this.announce(`The catalog was not imported: ${error.message}`, true); }
    }));
    const summary = catalog ? h('dl', {class: 'catalog-summary'},
      h('dt', {text: 'Source'}), h('dd', {text: session.catalogOrigin === 'bundled' ? 'Bundled reference' : visibleText(session.catalogName)}),
      h('dt', {text: 'Chain'}), h('dd', {text: visibleText(catalog.context.chainId)}),
      h('dt', {text: 'Context'}), h('dd', {text: short(catalog.context.sha256)}),
      h('dt', {text: 'Plugins'}), h('dd', {text: short(catalog.catalog.fingerprint.slice('sha256:'.length))}),
      h('dt', {text: 'Tool'}), h('dd', {text: visibleText(`${catalog.identity.producerVersion} · host ${catalog.identity.hostVersion}`)}),
      h('dt', {text: 'Described'}), h('dd', {text: plural(catalog.instances.length, 'instance')}))
      : h('p', {class: 'note', text: 'No catalog: forms accept names as text and checks are limited.'});
    put(panel,
      h('h2', {id: 'catalog-heading', text: 'Authoring catalog'}),
      summary,
      session.catalogOrigin === 'bundled' ? h('p', {class: 'note'},
        'Reference descriptors for the tutorial context only. For your chain, run ',
        h('code', {text: 'appchain bindings catalog bindings.yaml --plugins-directory <plugins> --context <context.json>'}),
        ' and import its output.') : null,
      h('label', {class: 'file-button'}, 'Import catalog JSON', input),
      session.catalogOrigin !== 'bundled' && this.services.bundledCatalog ? h('button', {type: 'button',
        class: 'secondary', id: 'use-bundled-catalog', text: 'Use the bundled reference', onclick: () => {
          session.useCatalog(this.services.bundledCatalog, 'bundled', 'Bundled reference catalog');
          this.announce('Using the bundled reference catalog.');
        }}) : null);
  }

  renderLists() {
    const session = this.session;
    const draft = session.draft;
    const {kind, index} = session.selection;
    const components = draft?.components ?? [];
    put(this.el('component-list'), ...components.map((component, i) => h('li', {},
      h('button', {type: 'button', class: 'item', id: `select-component-${i}`,
        'aria-current': kind === 'component' && index === i ? 'true' : 'false',
        onclick: () => this.select('component', i, `select-component-${i}`)},
      h('span', {text: visibleText(component.id) || '(no id)'}), h('small', {text: visibleText(component.machine) || 'machine not chosen'})))));
    const bindings = draft?.bindings ?? [];
    put(this.el('binding-list'), ...bindings.map((binding, i) => h('li', {},
      h('button', {type: 'button', class: 'item', id: `select-binding-${i}`,
        'aria-current': kind === 'binding' && index === i ? 'true' : 'false',
        onclick: () => this.select('binding', i, `select-binding-${i}`)},
      h('span', {}, h('span', {class: 'item-order', text: `${i + 1}. `}), visibleText(binding.id) || '(no id)'),
      h('small', {text: visibleText(`${binding.from.component || '?'} → ${binding.to.kind === 'effect'
        ? `effect ${binding.to.type || '(type not set)'}` : `${binding.to.component || '?'} ${binding.to.command || ''}`}`)})))));
    this.el('select-limits').setAttribute('aria-current', kind === 'limits' ? 'true' : 'false');
    const editable = session.editable;
    this.el('add-component').disabled = !editable;
    this.el('add-binding').disabled = !editable;
  }

  renderHeading() {
    const session = this.session;
    const {kind, index} = session.selection;
    const draft = session.draft;
    const title = kind === 'component' ? `Component · ${visibleText(draft.components[index].id)}`
      : kind === 'binding' ? `Binding ${index + 1} · ${visibleText(draft.bindings[index].id)}`
        : kind === 'limits' ? 'Limits and activation' : 'Document';
    this.el('editor-title').textContent = title;
    const badge = this.el('mode-badge');
    const mode = session.mode;
    badge.textContent = session.document.origin === 'blueprint-pending' ? 'Choose a chain'
      : mode === 'editable' ? 'Editable draft' : mode === 'read-only' ? 'Read-only' : 'Text not valid';
    badge.className = `badge${mode === 'editable' ? '' : mode === 'read-only' ? ' warn' : ' error'}`;
    const banner = this.el('mode-banner');
    const error = session.document.error;
    // YAML messages already carry their position; draft errors carry a path and the key's position.
    const position = error?.line && !error.message.includes(`(line ${error.line},`) ? ` (line ${error.line}, column ${error.column})` : '';
    const where = error ? visibleText(`${error.segments ? renderPath(error.segments) : ''}${position}`.trim()) : '';
    if (session.document.origin === 'blueprint-pending') {
      banner.hidden = false;
      banner.className = 'banner';
      put(banner, h('strong', {text: 'Choose a chain. '}),
        'This blueprint has several inline composites. Choose one under Blueprint chain in the Document panel.');
    } else if (mode === 'read-only') {
      banner.hidden = false;
      banner.className = 'banner';
      put(banner, h('strong', {text: 'Opened read-only. '}),
        `This document uses a construct Studio cannot edit without changing it: ${visibleText(error.message)}${where ? ` at ${where}` : ''}. `,
        'Only the original file can be downloaded. Start a new document or edit the original outside Studio.');
    } else if (mode === 'text-invalid') {
      banner.hidden = false;
      banner.className = 'banner error';
      put(banner, h('strong', {text: 'The YAML text is not a valid document. '}),
        `${visibleText(error.message)}${where ? ` at ${where}` : ''}. `,
        session.draft ? 'Forms show the last valid draft and are disabled; export is disabled until the text is fixed or reverted.'
          : 'There is no valid draft yet: fix the text in the YAML view to use the forms. Export is disabled until then.',
        session.canRevertText ? h('div', {}, h('button', {type: 'button', class: 'secondary', id: 'banner-revert',
          text: 'Revert to the last valid draft',
          onclick: () => { this.session.revertText(); this.announce('Showing the last valid draft.'); }})) : null);
    } else banner.hidden = true;
  }

  renderForm() {
    const panel = this.el('panel-form');
    const session = this.session;
    if (!session.draft) {
      put(panel, h('p', {class: 'note', text: 'No editable draft. Use the YAML tab to read the document.'}));
      return;
    }
    const {kind, index} = session.selection;
    const content = kind === 'component' ? this.componentForm(index)
      : kind === 'binding' ? this.bindingForm(index)
        : kind === 'limits' ? this.limitsForm() : this.documentForm();
    put(panel, h('fieldset', {class: 'form-root', disabled: !session.editable,
      'aria-describedby': session.editable ? null : 'mode-banner'}, content));
  }

  // -------------------------------------------------------------------------------------------------------------
  // Document and limits
  // -------------------------------------------------------------------------------------------------------------

  documentForm() {
    const draft = this.session.draft;
    const wrapped = h('input', {type: 'checkbox', id: 'doc-wrapped', checked: draft.wrapped,
      onchange: event => this.set(['wrapped'], event.target.checked, 'doc-wrapped')});
    return [
      h('section', {class: 'form-section'},
        h('h3', {text: 'Guided workflow'}),
        h('ol', {class: 'hint'},
          h('li', {text: 'Choose an authoring catalog that matches your plugins and context.'}),
          h('li', {text: 'Add named component instances and choose their machines and settings.'}),
          h('li', {text: 'Add bindings: a source event, optional conditions, then a target command or effect.'}),
          h('li', {text: 'Map every required target field from event fields, literals, functions or expressions.'}),
          h('li', {text: 'Review the YAML and graph, download bindings.yaml and validate it with the CLI.'}))),
      h('section', {class: 'form-section'},
        h('h3', {text: 'Summary'}),
        h('p', {class: 'hint', text: `${plural(draft.components.length, 'component')}, ${plural(draft.bindings.length, 'binding')}, `
          + `${draft.limits ? plural(draft.limits.length, 'authored limit') : 'no authored limits'}.`}),
        h('label', {class: 'inline-check'}, wrapped, ' Write the ', h('code', {text: 'composite:'}), ' wrapper'),
        h('p', {class: 'hint', text: 'The wrapper changes only the YAML layout, not the compiled program.'}))];
  }

  limitsForm() {
    const draft = this.session.draft;
    const catalogLimits = new Map((this.session.catalog?.language.limits ?? []).map(limit => [limit.name, limit]));
    const authored = new Map((draft.limits ?? []).map(limit => [limit.name, limit.value]));
    const rows = LIMIT_NAMES.map(name => {
      const bound = catalogLimits.get(name);
      const input = h('input', {id: `limit-${name}`, inputmode: 'numeric', autocomplete: 'off',
        value: authored.has(name) ? authored.get(name).toString() : '', placeholder: 'omitted',
        'aria-label': `${name} authored value`, dataset: {path: JSON.stringify(['limits', name])}});
      input.addEventListener('change', () => {
        const text = input.value.trim();
        if (text === '') { this.apply(value => edits.setLimit(value, name, null), input.id, `${name} is omitted; the default applies.`); return; }
        const value = parseInt64(text);
        if (value === null) { this.announce(`${name} must be a whole number.`, true); return; }
        this.apply(value_ => edits.setLimit(value_, name, value), input.id);
      });
      return h('tr', {}, h('th', {scope: 'row'}, h('code', {text: name})), h('td', {}, input),
        h('td', {text: bound ? bound.default : '—'}), h('td', {text: bound ? `${bound.minimum}–${bound.maximum}` : '—'}),
        h('td', {text: authored.has(name) ? 'authored' : 'default, not written'}));
    });
    const height = h('input', {id: 'workflow-from-height', inputmode: 'numeric', autocomplete: 'off',
      value: draft.workflowFromHeight === null ? '' : draft.workflowFromHeight.toString(), placeholder: 'omitted',
      dataset: {path: JSON.stringify(['workflowFromHeight'])}});
    height.addEventListener('change', () => {
      const text = height.value.trim();
      const value = text === '' ? null : parseInt64(text);
      if (text !== '' && value === null) { this.announce('workflowFromHeight must be a whole number.', true); return; }
      this.apply(draft_ => edits.setWorkflowFromHeight(draft_, value), height.id);
    });
    return [
      h('section', {class: 'form-section'},
        h('h3', {text: 'Committed limits'}),
        h('p', {class: 'hint', text: 'Empty fields are omitted from the document and use the default shown. '
          + 'Studio never writes a default for you. Changing a limit changes the committed program.'}),
        h('table', {class: 'limits'}, h('thead', {}, h('tr', {},
          ['Limit', 'Authored', 'Default', 'Range', 'State'].map(text => h('th', {scope: 'col', text})))), h('tbody', {}, rows))),
      h('section', {class: 'form-section'},
        h('h3', {text: 'Activation'}),
        h('label', {for: 'workflow-from-height'}, 'workflowFromHeight (optional)'), height,
        h('p', {class: 'hint', text: 'First height at which the binding workflow runs. Omitted means from genesis.'}))];
  }

  // -------------------------------------------------------------------------------------------------------------
  // Components
  // -------------------------------------------------------------------------------------------------------------

  instance(component) {
    return componentInstance(this.session.catalog, component, this.session.binding);
  }

  componentForm(index) {
    const session = this.session;
    const draft = session.draft;
    const component = draft.components[index];
    const instance = this.instance(component);
    const probe = instance ?? (component.config?.length
      ? findInstance(session.catalog, component.machine, {}, session.binding) : null);
    const selectors = (session.catalog?.selectors ?? []).filter(selector => selector.composable !== false);
    const at = field => ['components', index, field];
    const machine = this.textControl({id: `c${index}-machine`, list: 'machine-options', autocomplete: 'off',
      dataset: {path: JSON.stringify(at('machine'))}}, component.machine,
    text => this.set(at('machine'), text, null, true), {trim: true});
    const topic = this.textControl({id: `c${index}-topic`, autocomplete: 'off',
      placeholder: `${component.id}.command.v1 (default, not written)`, dataset: {path: JSON.stringify(at('topic'))}},
    component.topic ?? '', text => this.set(at('topic'), text === '' ? null : text));
    const optionalInteger = (field, label, hint) => {
      const input = h('input', {id: `c${index}-${field}`, inputmode: 'numeric', autocomplete: 'off',
        value: component[field] === null ? '' : component[field].toString(), placeholder: 'omitted',
        dataset: {path: JSON.stringify(at(field))}});
      input.addEventListener('change', () => {
        const text = input.value.trim();
        const value = text === '' ? null : parseInt64(text);
        if (text !== '' && value === null) { this.announce(`${field} must be a whole number.`, true); return; }
        this.set(at(field), value);
      });
      return h('div', {class: 'stack'}, h('label', {for: input.id}, label), input, h('p', {class: 'hint', text: hint}));
    };
    return [
      h('div', {class: 'inline-actions'},
        h('button', {type: 'button', class: 'secondary', id: `c${index}-rename`, text: 'Rename…',
          onclick: () => this.renameComponentDialog(index)}),
        h('button', {type: 'button', class: 'secondary', id: `c${index}-up`, text: 'Move up', disabled: index === 0,
          onclick: () => this.apply(value => edits.moveComponent(value, index, -1), `c${index - 1}-up`)
            .then(done => done && this.session.select('component', index - 1))}),
        h('button', {type: 'button', class: 'secondary', id: `c${index}-down`, text: 'Move down',
          disabled: index === draft.components.length - 1,
          onclick: () => this.apply(value => edits.moveComponent(value, index, 1), `c${index + 1}-down`)
            .then(done => done && this.session.select('component', index + 1))}),
        h('button', {type: 'button', class: 'secondary', id: `c${index}-remove`, text: 'Remove',
          onclick: () => this.removeComponentDialog(index)})),
      h('section', {class: 'form-section'},
        h('h3', {text: 'Machine and routing'}),
        h('div', {class: 'field-row'},
          h('div', {class: 'stack'}, h('label', {for: machine.id}, 'Machine selector'), machine, machine.escapedHint,
            h('datalist', {id: 'machine-options'}, selectors.map(selector => h('option', {value: selector.machineId})))),
          h('div', {class: 'stack'}, h('label', {for: topic.id}, 'Ingress topic (optional)'), topic,
            topic.escapedHint)),
        h('div', {class: 'field-row'},
          optionalInteger('maxEffectsPerBlock', 'maxEffectsPerBlock (optional)', 'Omitted means 0.'),
          optionalInteger('fromHeight', 'fromHeight (optional)', 'Omitted means 1.'))),
      this.configurationSection(index, component, instance, probe),
      this.descriptorSection(component, instance)];
  }

  configurationSection(index, component, instance, probe) {
    const settings = component.config ?? [];
    const descriptor = (instance ?? probe)?.configurationDescriptor ?? [];
    // Removing the last setting drops the empty map; an absent and an empty configuration compile identically.
    const withConfig = change => draft => {
      const config = change(draft.components[index].config ?? []);
      return edits.updateComponent(draft, index, {config: config.length ? config : null});
    };
    const items = settings.map((setting, s) => {
      const id = `c${index}-set-${s}`;
      const path = ['components', index, 'config', s];
      const name = this.textControl({id: `${id}-name`, autocomplete: 'off', 'aria-label': 'Setting name'}, setting.name,
        next => {
          const current = this.session.draft.components[index]?.config ?? [];
          if (!next || current.some((other, o) => o !== s && other.name === next)) {
            this.announce('Setting names must be present and unique.', true); name.resetValue(); return;
          }
          this.set([...path, 'name'], next);
        }, {trim: true});
      const declared = descriptor.find(value => value.name === setting.name);
      return h('li', {class: 'setting', dataset: {path: JSON.stringify(['components', index, 'config', setting.name])}},
        h('div', {class: 'field-row'}, h('div', {class: 'stack'}, h('label', {for: name.id}, 'Name'), name, name.escapedHint),
          this.literalEditor(`${id}-value`, setting.value, [...path, 'value'], declared ? [declared.type] : TYPES)),
        declared ? h('p', {class: 'hint', text: `Declared ${declared.type}${declared.required ? ', required' : ''}`}) : null,
        h('div', {class: 'row-actions'}, h('button', {type: 'button', class: 'secondary', id: `${id}-remove`,
          text: 'Remove setting', 'aria-label': `Remove setting ${visibleText(setting.name)}`,
          onclick: () => this.apply(withConfig(config => config.filter((_, o) => o !== s)),
            `c${index}-add-setting`)})));
    });
    const missing = descriptor.filter(value => !settings.some(setting => setting.name === value.name));
    return h('section', {class: 'form-section', dataset: {path: JSON.stringify(['components', index, 'config'])}},
      h('h3', {text: 'Configuration'}),
      h('p', {class: 'hint', text: instance ? 'Settings are written exactly as authored. Defaults are shown, never written.'
        : probe ? 'This exact configuration is not described. Setting names below come from the empty-configuration probe.'
          : 'No descriptor for this machine in the catalog; settings are free text.'}),
      h('ol', {class: 'setting-list'}, items),
      missing.length ? h('ul', {class: 'hint'}, missing.map(value => h('li', {},
        h('code', {text: value.name}), ` · ${value.type} · `, value.default ? `default ${literalText(value.default)} (not written) `
          : 'required ',
        h('button', {type: 'button', class: 'secondary', id: `c${index}-author-${key(value.name)}`, text: 'Author this setting',
          onclick: () => this.apply(withConfig(config => config.some(setting => setting.name === value.name) ? config
            : [...config, {name: value.name, value: value.default ? structuredClone(value.default) : defaultLiteral(value.type)}]))})))) : null,
      h('div', {class: 'inline-actions'}, h('button', {type: 'button', class: 'secondary', id: `c${index}-add-setting`,
        text: 'Add setting', onclick: () => this.apply(withConfig(config => {
          let n = 1;
          while (config.some(setting => setting.name === `setting-${n}`)) n++;
          return [...config, {name: `setting-${n}`, value: defaultLiteral('text')}];
        }), `c${index}-set-${settings.length}-name`)})));
  }

  descriptorSection(component, instance) {
    const catalog = this.session.catalog;
    if (!catalog) return h('section', {class: 'form-section descriptor'}, h('h3', {text: 'Descriptor'}),
      h('p', {class: 'hint', text: 'Import an authoring catalog to see events, commands and settings.'}));
    if (!instance) return h('section', {class: 'form-section descriptor'}, h('h3', {text: 'Descriptor'}),
      h('p', {class: 'hint warn'}, `The catalog does not describe ${component.machine || 'this machine'} with this exact configuration. `,
        'Studio never normalizes configuration. Export a catalog for this document: ',
        h('code', {text: 'appchain bindings catalog bindings.yaml --plugins-directory <plugins> --context <context.json>'})));
    const fields = list => h('ul', {}, list.map(field => h('li', {},
      h('code', {text: field.name}), ` ${field.type}${field.required ? '' : ', optional'}${field.role === 'evidence' ? ', evidence' : ''}`)));
    return h('section', {class: 'form-section descriptor'},
      h('h3', {text: 'Descriptor'}),
      h('dl', {},
        h('dt', {text: 'Status'}), h('dd', {text: instance.status}),
        instance.applicationVersion ? [h('dt', {text: 'Version'}), h('dd', {text: visibleText(instance.applicationVersion)})] : null,
        instance.diagnostic ? [h('dt', {text: 'Diagnostic'}), h('dd', {text: `${instance.diagnostic.code}${instance.diagnostic.detail ? `: ${visibleText(instance.diagnostic.detail)}` : ''}`})] : null,
        instance.readParticipants ? [h('dt', {text: 'Reads'}), h('dd', {text: instance.readParticipants.join(', ') || 'none'})] : null,
        instance.rawBodyTarget ? [h('dt', {text: 'Raw bodies'}), h('dd', {text: instance.rawBodyTarget === 'allowed' ? 'allowed'
          : 'not allowed (evidence-bearing commands)'})] : null),
      instance.events ? h('details', {}, h('summary', {text: `Events (${instance.events.length})`}),
        instance.events.map(event => h('div', {}, h('code', {text: event.eventId}), fields(event.fields)))) : null,
      instance.commands ? h('details', {}, h('summary', {text: `Commands (${instance.commands.length})`}),
        instance.commands.map(command => h('div', {}, h('code', {text: command.commandName}),
          ` · ${command.layout} · opcode ${command.opCode}`, fields(command.fields)))) : null);
  }

  async renameComponentDialog(index) {
    const epoch = this.epoch;
    const draft = this.session.draft;
    const component = draft.components[index];
    const references = edits.componentReferences(draft, component.id);
    const input = h('input', {id: 'rename-input', value: component.id, autocomplete: 'off', 'aria-describedby': 'rename-help'});
    const extra = h('div', {class: 'stack'}, h('label', {for: 'rename-input'}, 'New component id'), input,
      h('p', {id: 'rename-help', class: 'hint', text: 'Lowercase letters, digits and hyphens; must start with a letter.'}),
      references.length ? h('ul', {}, references.map(reference => h('li', {text: visibleText(
        `${renderPath(reference.segments)}${reference.bindingId ? ` (binding ${reference.bindingId})` : ''}`)
        + (reference.role === 'configuration-mention' ? ' — a setting mentions this id; it is not changed' : ' — updated')}))) : null);
    const accepted = await this.confirm(`Rename ${component.id}`, [
      'Renaming changes the component state namespace and default topic, so it changes the committed profile of a '
        + 'deployed chain. Every binding reference below is updated.'], 'Rename', 'Cancel', extra);
    if (!accepted) return;
    const next = input.value.trim();
    if (!ID.test(next) || draft.components.some((other, i) => i !== index && other.id === next)) {
      this.announce('The new id must be a unique lowercase identifier.', true);
      return;
    }
    this.apply(value => edits.renameComponent(value, index, next), `select-component-${index}`, `Renamed to ${next}.`,
      {epoch});
  }

  async removeComponentDialog(index) {
    const epoch = this.epoch;
    const draft = this.session.draft;
    const component = draft.components[index];
    const references = edits.componentReferences(draft, component.id).filter(value => value.role !== 'configuration-mention');
    const accepted = await this.confirm(`Remove ${component.id}?`, references.length ? [
      `${plural(references.length, 'reference')} still name this component. They are kept and will point at a missing component.`]
      : ['No binding references this component.'], 'Remove', 'Cancel');
    if (!accepted) return;
    this.apply(value => edits.removeComponent(value, index), 'add-component', `Removed ${component.id}.`, {epoch})
      .then(done => done && this.session.select('document'));
  }

  // -------------------------------------------------------------------------------------------------------------
  // Bindings
  // -------------------------------------------------------------------------------------------------------------

  eventFields(binding) {
    const catalog = this.session.catalog;
    if (!catalog) return null;
    if (binding.from.event === BASELINE_EVENT) {
      return new Map(catalog.language.baselineEvent.fields.map(field => [field.name, field.type]));
    }
    const source = this.session.draft.components.find(component => component.id === binding.from.component);
    const instance = source ? this.instance(source) : null;
    const event = instance?.events?.find(value => value.eventId === binding.from.event);
    return event ? new Map(event.fields.map(field => [field.name, field.type])) : null;
  }

  bindingForm(index) {
    const draft = this.session.draft;
    const binding = draft.bindings[index];
    const fields = this.eventFields(binding);
    return [
      h('div', {class: 'inline-actions'},
        h('button', {type: 'button', class: 'secondary', id: `b${index}-rename`, text: 'Rename…',
          onclick: () => this.renameBindingDialog(index)}),
        h('button', {type: 'button', class: 'secondary', id: `b${index}-up`, text: 'Move earlier', disabled: index === 0,
          onclick: () => this.apply(value => edits.moveBinding(value, index, -1), `b${index - 1}-up`,
            'Moved earlier. Binding order is execution order.').then(done => done && this.session.select('binding', index - 1))}),
        h('button', {type: 'button', class: 'secondary', id: `b${index}-down`, text: 'Move later',
          disabled: index === draft.bindings.length - 1,
          onclick: () => this.apply(value => edits.moveBinding(value, index, 1), `b${index + 1}-down`,
            'Moved later. Binding order is execution order.').then(done => done && this.session.select('binding', index + 1))}),
        h('button', {type: 'button', class: 'secondary', id: `b${index}-remove`, text: 'Remove',
          onclick: async () => {
            const epoch = this.epoch;
            if (!(await this.confirm(`Remove ${binding.id}?`, ['The binding and its conditions and mapping are removed.'],
              'Remove', 'Cancel'))) return;
            this.apply(value => edits.removeBinding(value, index), 'add-binding', `Removed ${binding.id}.`, {epoch})
              .then(done => done && this.session.select('document'));
          }})),
      this.sourceSection(index, binding, fields),
      this.conditionsSection(index, binding, fields),
      this.targetSection(index, binding, fields)];
  }

  sourceSection(index, binding, fields) {
    const draft = this.session.draft;
    const ids = draft.components.map(component => component.id);
    const at = field => ['bindings', index, 'from', field];
    const component = h('select', {id: `b${index}-source`, dataset: {path: JSON.stringify(at('component'))}},
      !ids.includes(binding.from.component) ? h('option', {value: binding.from.component,
        text: binding.from.component ? `${binding.from.component} (not declared)` : 'Choose a component…'}) : null,
      ids.map(id => h('option', {value: id, text: id, selected: id === binding.from.component})));
    component.value = binding.from.component;
    component.addEventListener('change', () => this.set(at('component'), component.value, null, true));
    const source = draft.components.find(value => value.id === binding.from.component);
    const instance = source ? this.instance(source) : null;
    let event;
    if (instance?.status === 'available') {
      const known = [...instance.events.map(value => value.eventId), BASELINE_EVENT];
      event = h('select', {id: `b${index}-event`, dataset: {path: JSON.stringify(at('event'))}},
        !known.includes(binding.from.event) ? h('option', {value: binding.from.event,
          text: binding.from.event ? `${binding.from.event} (not published)` : 'Choose an event…'}) : null,
        known.map(id => h('option', {value: id, text: id === BASELINE_EVENT ? `${id} (every accepted command)` : id})));
      event.value = binding.from.event;
      event.addEventListener('change', () => this.set(at('event'), event.value, null, true));
    } else {
      event = this.textControl({id: `b${index}-event`, autocomplete: 'off', dataset: {path: JSON.stringify(at('event'))}},
        binding.from.event, text => this.set(at('event'), text, null, true), {trim: true});
    }
    return h('section', {class: 'form-section'},
      h('h3', {text: 'When this event happens'}),
      h('div', {class: 'field-row'},
        h('div', {class: 'stack'}, h('label', {for: component.id}, 'Source component'), component),
        h('div', {class: 'stack'}, h('label', {for: event.id}, 'Event'), event, event.escapedHint)),
      fields ? h('p', {class: 'hint', text: `Event fields: ${[...fields].map(([name, type]) => `${name} (${type})`).join(', ') || 'none'}`})
        : h('p', {class: 'hint', text: 'The event schema is not described by the catalog; field names are free text.'}));
  }

  conditionsSection(index, binding, fields) {
    const clauses = binding.when ?? [];
    const firstField = fields ? [...fields.keys()][0] ?? '' : '';
    const firstBytes = fields ? [...fields].find(([, type]) => type === 'bytes')?.[0] ?? '' : '';
    const add = clause => this.apply(value => edits.addClause(value, index, clause), `b${index}-w${clauses.length}-kind`);
    return h('section', {class: 'form-section', dataset: {path: JSON.stringify(['bindings', index, 'when'])}},
      h('h3', {text: 'Only if (all conditions hold)'}),
      clauses.length ? null : h('p', {class: 'hint', text: 'No conditions: the binding runs for every such event.'}),
      h('ol', {class: 'clause-list'}, clauses.map((clause, c) => this.clauseEditor(index, c, clause, clauses.length, fields))),
      h('div', {class: 'inline-actions'},
        h('button', {type: 'button', class: 'secondary', id: `b${index}-add-field-clause`, text: 'Add field condition',
          onclick: () => add({kind: 'field', field: firstField, operator: 'eq',
            operand: defaultLiteral(fields?.get(firstField) ?? 'text')})}),
        h('button', {type: 'button', class: 'secondary', id: `b${index}-add-lookup`, text: 'Add state lookup',
          onclick: () => add({kind: 'lookup', component: this.session.draft.components[0]?.id ?? '',
            key: {kind: 'field', name: firstBytes}, expectation: 'exists', operand: null})}),
        h('button', {type: 'button', class: 'secondary', id: `b${index}-add-expression`, text: 'Add expression',
          onclick: () => add({kind: 'expr', text: ''})})));
  }

  clauseEditor(index, c, clause, count, fields) {
    const id = `b${index}-w${c}`;
    const path = ['bindings', index, 'when', c];
    const label = clause.kind === 'field' ? 'Field condition' : clause.kind === 'lookup' ? 'State lookup' : 'Expression';
    let body;
    if (clause.kind === 'field') {
      const field = this.fieldPicker(`${id}-field`, clause.field, fields, name => this.set([...path, 'field'], name), 'Event field');
      const operator = h('select', {id: `${id}-operator`}, OPERATORS.map(value => h('option', {value, text: value})));
      operator.value = clause.operator;
      operator.addEventListener('change', () => this.keptSwitch(path, current => {
        const op = operator.value;
        const key = this.stashKey([...path, 'operand']);
        const kept = this.stash.get(key) ?? {};
        kept[current.operator] = structuredClone(current.operand);
        this.stash.set(key, kept);
        if (kept[op] !== undefined) return {...current, operator: op, operand: structuredClone(kept[op])};
        const type = fields?.get(current.field) ?? (Array.isArray(current.operand) ? current.operand[0]?.type
          : current.operand?.type) ?? 'text';
        const single = Array.isArray(current.operand) ? current.operand[0] ?? defaultLiteral(type)
          : current.operand === true ? defaultLiteral(type) : current.operand;
        return {...current, operator: op, operand: op === 'in' ? [single] : op === 'exists' || op === 'absent' ? true : single};
      }));
      const type = fields?.get(clause.field);
      let operand;
      if (clause.operator === 'exists' || clause.operator === 'absent') {
        operand = h('p', {class: 'hint', text: `The field must be ${clause.operator === 'exists' ? 'present' : 'absent'}.`});
      } else if (clause.operator === 'in') {
        operand = h('div', {class: 'stack'}, h('ol', {class: 'arg-list'}, clause.operand.map((literal, n) => h('li', {class: 'source-arg'},
          this.literalEditor(`${id}-in${n}`, literal, [...path, 'operand', n], type ? [type] : TYPES),
          h('button', {type: 'button', class: 'secondary', id: `${id}-in${n}-remove`, text: 'Remove value', disabled: clause.operand.length === 1,
            'aria-label': `Remove value ${n + 1} of condition ${c + 1}`,
            onclick: () => this.update([...path, 'operand'], values => values.filter((_, o) => o !== n), `${id}-in-add`)})))),
        h('button', {type: 'button', class: 'secondary', id: `${id}-in-add`, text: 'Add value',
          onclick: () => this.update([...path, 'operand'], values => [...values, defaultLiteral(type ?? values[0]?.type ?? 'text')])}));
      } else {
        operand = this.literalEditor(`${id}-operand`, clause.operand, [...path, 'operand'], type ? [type] : TYPES);
      }
      body = [h('div', {class: 'field-row'}, field, h('div', {class: 'stack'}, h('label', {for: operator.id}, 'Operator'), operator)), operand];
    } else if (clause.kind === 'lookup') {
      const ids = this.session.draft.components.map(component => component.id);
      const participant = h('select', {id: `${id}-participant`},
        !ids.includes(clause.component) ? h('option', {value: clause.component, text: `${clause.component || '(none)'} (not declared)`}) : null,
        ids.map(value => h('option', {value, text: value})));
      participant.value = clause.component;
      participant.addEventListener('change', () => this.set([...path, 'component'], participant.value));
      const expectation = h('select', {id: `${id}-expectation`}, [['exists', 'key exists'], ['absent', 'key is absent'],
        ['eq', 'stored value equals']].map(([value, text]) => h('option', {value, text})));
      expectation.value = clause.expectation;
      expectation.addEventListener('change', () => this.keptSwitch(path, current => {
        const key = this.stashKey([...path, 'operand']);
        const kept = this.stash.get(key) ?? {};
        if (current.operand !== null) kept.eq = structuredClone(current.operand);
        this.stash.set(key, kept);
        return {...current, expectation: expectation.value, operand: expectation.value === 'eq'
          ? current.operand ?? (kept.eq ? structuredClone(kept.eq) : {kind: 'field', name: [...(fields?.keys() ?? [])][0] ?? ''})
          : null};
      }));
      body = [h('div', {class: 'field-row'},
        h('div', {class: 'stack'}, h('label', {for: participant.id}, 'Participant component'), participant),
        h('div', {class: 'stack'}, h('label', {for: expectation.id}, 'Expectation'), expectation)),
      h('div', {class: 'stack', dataset: {path: JSON.stringify([...path, 'lookup', 'key'])}}, h('strong', {class: 'hint', text: 'Key (bytes)'}),
        this.sourceEditor(`${id}-key`, clause.key, fields, 0, [...path, 'key'], undefined, `Condition ${c + 1} key`)),
      clause.expectation === 'eq' ? h('div', {class: 'stack', dataset: {path: JSON.stringify([...path, 'lookup', 'eq'])}},
        h('strong', {class: 'hint', text: 'Expected value (bytes)'}),
        this.sourceEditor(`${id}-eqv`, clause.operand, fields, 0, [...path, 'operand'], undefined,
          `Condition ${c + 1} expected value`)) : null,
      h('p', {class: 'hint', text: 'The participant must be a declared read participant of the source machine.'})];
    } else {
      const text = this.textControl({id: `${id}-expr`, rows: 3, spellcheck: 'false', 'aria-describedby': `${id}-expr-help`},
        clause.text, value => this.set([...path, 'text'], value), {multiline: true});
      body = [h('label', {for: text.id}, 'Restricted CEL expression'), text, text.escapedHint,
        h('p', {id: `${id}-expr-help`, class: 'hint', text: 'Evaluated with event fields as event.<name>. The CLI checks types and limits.'})];
    }
    return h('li', {class: 'clause', dataset: {path: JSON.stringify(path)}},
      h('div', {class: 'assignment-head'}, h('strong', {id: `${id}-kind`, tabindex: '-1', text: `${c + 1}. ${label}`})),
      body,
      h('div', {class: 'row-actions'},
        h('button', {type: 'button', class: 'secondary', id: `${id}-up`, text: 'Move up', disabled: c === 0,
          'aria-label': `Move condition ${c + 1} up`,
          onclick: () => this.apply(value => edits.moveClause(value, index, c, -1), `b${index}-w${c - 1}-up`)}),
        h('button', {type: 'button', class: 'secondary', id: `${id}-down`, text: 'Move down', disabled: c === count - 1,
          'aria-label': `Move condition ${c + 1} down`,
          onclick: () => this.apply(value => edits.moveClause(value, index, c, 1), `b${index}-w${c + 1}-down`)}),
        h('button', {type: 'button', class: 'secondary', id: `${id}-remove`, text: 'Remove condition',
          'aria-label': `Remove condition ${c + 1}`,
          onclick: () => this.apply(value => edits.removeClause(value, index, c), `b${index}-add-field-clause`)})));
  }

  targetSection(index, binding, fields) {
    const draft = this.session.draft;
    const to = binding.to;
    const at = field => ['bindings', index, 'to', field];
    const kindName = `b${index}-target-kind`;
    const radio = (value, text) => h('label', {class: 'inline-check'}, h('input', {type: 'radio', name: kindName,
      id: `${kindName}-${value}`, value, checked: to.kind === value, onchange: () => this.switchKind(['bindings', index, 'to'],
        current => current.kind, value, current => {
          const mapping = current.mapping.kind === 'identity' && value === 'command' ? {kind: 'fields', assignments: []}
            : current.mapping;
          return value === 'command' ? {kind: 'command', component: '', command: '', mapping}
            : {kind: 'effect', type: '', gate: null, result: null, expiryBlocks: null, mapping};
        }, `${kindName}-${value}`)}), ` ${text}`);
    const targetPath = to.kind === 'effect' ? ['bindings', index, 'to', 'effect'] : ['bindings', index, 'to'];
    let details;
    let command = null;
    let instance = null;
    if (to.kind === 'command') {
      const ids = draft.components.map(component => component.id);
      const component = h('select', {id: `b${index}-target`, dataset: {path: JSON.stringify(['bindings', index, 'to', 'component'])}},
        !ids.includes(to.component) ? h('option', {value: to.component,
          text: to.component ? `${to.component} (not declared)` : 'Choose a component…'}) : null,
        ids.map(id => h('option', {value: id, text: id})));
      component.value = to.component;
      component.addEventListener('change', () => this.set(at('component'), component.value, null, true));
      const target = draft.components.find(value => value.id === to.component);
      instance = target ? this.instance(target) : null;
      let commandInput;
      if (instance?.status === 'available') {
        const names = instance.commands.map(value => value.commandName);
        commandInput = h('select', {id: `b${index}-command`, dataset: {path: JSON.stringify(['bindings', index, 'to', 'command'])}},
          !names.includes(to.command) ? h('option', {value: to.command, text: to.command ? `${to.command} (not declared)` : 'Choose a command…'}) : null,
          names.map(name => h('option', {value: name, text: name})));
        commandInput.value = to.command;
        commandInput.addEventListener('change', () => this.set(at('command'), commandInput.value));
        command = instance.commands.find(value => value.commandName === to.command) ?? null;
      } else {
        commandInput = this.textControl({id: `b${index}-command`, autocomplete: 'off',
          dataset: {path: JSON.stringify(['bindings', index, 'to', 'command'])}}, to.command,
        text => this.set(at('command'), text), {trim: true});
      }
      details = h('div', {class: 'field-row'},
        h('div', {class: 'stack'}, h('label', {for: component.id}, 'Target component'), component),
        h('div', {class: 'stack'}, h('label', {for: commandInput.id}, 'Command'), commandInput,
          commandInput.escapedHint));
    } else {
      const type = this.textControl({id: `b${index}-effect-type`, autocomplete: 'off', placeholder: 'for example notify.v1',
        dataset: {path: JSON.stringify([...targetPath, 'type'])}}, to.type, text => this.set(at('type'), text),
      {trim: true});
      const choice = (field, options, label) => {
        const select = h('select', {id: `b${index}-effect-${field}`, dataset: {path: JSON.stringify([...targetPath, field])}},
          options.map(([value, text]) => h('option', {value, text})));
        select.value = to[field] ?? '';
        select.addEventListener('change', () => this.set(at(field), select.value === '' ? null : select.value));
        return h('div', {class: 'stack'}, h('label', {for: select.id}, label), select);
      };
      const expiry = h('input', {id: `b${index}-effect-expiry`, value: to.expiryBlocks === null ? '' : to.expiryBlocks.toString(),
        inputmode: 'numeric', placeholder: 'omitted', autocomplete: 'off', dataset: {path: JSON.stringify([...targetPath, 'expiryBlocks'])}});
      expiry.addEventListener('change', () => {
        const text = expiry.value.trim();
        const value = text === '' ? null : parseInt64(text);
        if (text !== '' && value === null) { this.announce('expiryBlocks must be a whole number.', true); return; }
        this.set(at('expiryBlocks'), value);
      });
      details = [h('div', {class: 'field-row'},
        h('div', {class: 'stack'}, h('label', {for: type.id}, 'Effect type'), type, type.escapedHint),
        choice('gate', [['', 'Not set (app-final)'], ['app-final', 'app-final'], ['l1-final', 'l1-final']], 'Finality gate'),
        choice('result', [['', 'Not set (none)'], ['none', 'none'], ['chain', 'chain']], 'Result policy'),
        h('div', {class: 'stack'}, h('label', {for: expiry.id}, 'expiryBlocks (optional)'), expiry)),
      h('p', {class: 'hint', text: 'An effect is an outbox intent recorded with the cascade. It is not a remote call and '
        + 'has no exactly-once delivery promise; no payload schema is published for effect types.'})];
    }
    return h('section', {class: 'form-section', dataset: {path: JSON.stringify(['bindings', index, 'to'])}},
      h('h3', {text: 'Then'}),
      h('div', {class: 'inline-actions', role: 'radiogroup', 'aria-label': 'Target kind'},
        radio('command', 'Run a command on a component'), radio('effect', 'Record an effect intent')),
      details,
      this.mappingEditor(index, binding, fields, command, instance, targetPath));
  }

  mappingEditor(index, binding, fields, command, instance, targetPath) {
    const to = binding.to;
    const mapping = to.mapping;
    const path = ['bindings', index, 'to', 'mapping'];
    const kinds = [['fields', 'Field map'], ['raw', 'Forward the raw body (advanced)']];
    if (to.kind === 'effect') kinds.push(['identity', 'Identity (whole event)']);
    const kind = h('select', {id: `b${index}-mapping-kind`}, kinds.map(([value, text]) => h('option', {value, text})));
    kind.value = mapping.kind;
    kind.addEventListener('change', () => this.switchKind(path, value => value.kind, kind.value, () => kind.value === 'fields'
      ? {kind: 'fields', assignments: []}
      : kind.value === 'raw' ? {kind: 'raw', field: fields ? [...fields].find(([, type]) => type === 'bytes')?.[0] ?? '' : ''}
        : {kind: 'identity'}, kind.id));
    let body = null;
    if (mapping.kind === 'raw') {
      const field = this.fieldPicker(`b${index}-raw-field`, mapping.field, fields && new Map([...fields].filter(([, type]) => type === 'bytes')),
        name => this.set([...path, 'field'], name), 'Bytes event field to forward');
      body = [field, h('p', {class: `hint${instance?.rawBodyTarget === 'forbidden-evidence' ? ' warn' : ''}`, text:
        instance?.rawBodyTarget === 'forbidden-evidence'
          ? 'This target has evidence-bearing commands, so raw forwarding is rejected: raw bytes could select any of its commands.'
          : 'Raw bytes are decoded by the target codec and may select any of its commands. Evidence-bearing targets reject raw forwarding.'})];
    } else if (mapping.kind === 'identity') {
      body = h('p', {class: 'hint', text: 'The effect payload is the whole event payload.'});
    } else {
      body = this.assignments(index, binding, fields, command, targetPath);
    }
    return h('div', {class: 'stack', dataset: {path: JSON.stringify([...targetPath, mapping.kind === 'raw' ? 'rawBody' : 'map'])}},
      h('div', {class: 'field-row'}, h('div', {class: 'stack'}, h('label', {for: kind.id}, 'Mapping'), kind)), body);
  }

  assignments(index, binding, fields, command, targetPath) {
    const mapping = binding.to.mapping;
    const authored = new Map(mapping.assignments.map((assignment, position) => [assignment.field, position]));
    const rows = [];
    const row = (name, descriptor) => {
      const id = `b${index}-map-${key(name)}`;
      const position = authored.get(name);
      const tags = descriptor ? [h('span', {class: 'tag', text: descriptor.type}),
        h('span', {class: `tag${descriptor.required ? ' required' : ''}`, text: descriptor.required ? 'required' : 'optional'}),
        descriptor.role === 'evidence' ? h('span', {class: 'tag evidence', text: 'evidence: copy an event field directly'}) : null]
        : command ? [h('span', {class: 'tag missing', text: 'not a field of this command'})] : [];
      return h('li', {class: 'assignment', dataset: {path: JSON.stringify([...targetPath, 'map', name])}},
        h('div', {class: 'assignment-head'}, h('strong', {id: `${id}-name`, tabindex: '-1', text: name}), tags),
        position !== undefined ? [this.sourceEditor(id, mapping.assignments[position].source, fields, 0,
          ['bindings', index, 'to', 'mapping', 'assignments', position, 'source'], descriptor?.type, `Mapping ${visibleText(name)}`),
        h('div', {class: 'row-actions'}, h('button', {type: 'button', class: 'secondary', id: `${id}-remove`, text: 'Remove mapping',
          'aria-label': `Remove the mapping for ${visibleText(name)}`,
          onclick: () => this.apply(draft => edits.removeAssignment(draft, index, name), `${id}-map`)}))]
          : [h('p', {class: 'hint', text: 'Not mapped.'}), h('div', {class: 'row-actions'}, h('button', {type: 'button',
            class: 'secondary', id: `${id}-map`, text: `Map ${name}`, onclick: () => this.apply(draft => edits.setAssignment(draft,
              index, name, this.suggestedSource(name, descriptor, fields)), `${id}-kind`)}))]);
    };
    if (command) {
      for (const field of command.fields) rows.push(row(field.name, field));
      for (const name of authored.keys()) if (!command.fields.some(field => field.name === name)) rows.push(row(name, null));
    } else {
      for (const name of authored.keys()) rows.push(row(name, null));
    }
    const newName = h('input', {id: `b${index}-new-field`, autocomplete: 'off', placeholder: 'field name', 'aria-label': 'New mapped field name'});
    newName.addEventListener('keydown', event => {
      if (event.key === 'Enter') { event.preventDefault(); document.getElementById(`b${index}-add-field`)?.click(); }
    });
    return [
      command ? h('p', {class: 'hint', text: `${command.commandName}: ${command.layout} layout. Assignment order in YAML is not codec field order.`})
        : h('p', {class: 'hint', text: binding.to.kind === 'effect' ? 'Effect payload fields are free text.'
          : 'The target command is not described; field names are free text.'}),
      h('ol', {class: 'assignment-list'}, rows),
      h('div', {class: 'inline-actions'}, newName, h('button', {type: 'button', class: 'secondary', id: `b${index}-add-field`,
        text: 'Add mapped field', onclick: () => {
          const name = newName.value.trim();
          const current = this.session.draft.bindings[index]?.to.mapping;
          if (!name || current?.kind !== 'fields' || current.assignments.some(assignment => assignment.field === name)) {
            this.announce('Enter a new, unique field name.', true);
            return;
          }
          this.apply(draft => edits.setAssignment(draft, index, name, this.suggestedSource(name, null, fields)),
            `b${index}-map-${key(name)}-kind`);
        }}))];
  }

  /** An explicit starting source for a newly mapped field; never a hidden default in the document. */
  suggestedSource(name, descriptor, fields) {
    const type = descriptor?.type;
    if (fields?.get(name) && (!type || fields.get(name) === type)) return {kind: 'field', name};
    const sameType = fields && type ? [...fields].find(([, fieldType]) => fieldType === type)?.[0] : null;
    if (sameType) return {kind: 'field', name: sameType};
    if (descriptor?.role === 'evidence') return {kind: 'field', name: fields ? [...fields.keys()][0] ?? '' : ''};
    return {kind: 'literal', value: defaultLiteral(type ?? 'text')};
  }

  renameBindingDialog(index) {
    const epoch = this.epoch;
    const draft = this.session.draft;
    const binding = draft.bindings[index];
    const input = h('input', {id: 'rename-input', value: binding.id, autocomplete: 'off'});
    this.confirm(`Rename ${binding.id}`, ['Binding ids appear in receipts and in derived message ids, so a rename '
      + 'changes the committed program even though nothing else refers to it.'], 'Rename', 'Cancel',
    h('div', {class: 'stack'}, h('label', {for: 'rename-input'}, 'New binding id'), input)).then(accepted => {
      if (!accepted) return;
      const next = input.value.trim();
      if (!ID.test(next) || draft.bindings.some((other, i) => i !== index && other.id === next)) {
        this.announce('The new id must be a unique lowercase identifier.', true);
        return;
      }
      this.apply(value => edits.renameBinding(value, index, next), `select-binding-${index}`, `Renamed to ${next}.`,
        {epoch});
    });
  }

  // -------------------------------------------------------------------------------------------------------------
  // Values
  // -------------------------------------------------------------------------------------------------------------

  /**
   * Creates a text input, or a textarea when `multiline`, that holds `value` exactly. Browsers drop CR and LF from
   * single-line inputs and turn CR into LF in textareas, and a `trim` field would drop surrounding spaces the author
   * never touched, so such a value is shown and edited as a quoted JSON string instead, described by
   * `control.escapedHint` (callers place it after the control). `onText` receives the decoded text; `trim` trims
   * only plain typed values, never an escaped one.
   */
  textControl(attributes, value, onText, {multiline = false, trim = false} = {}) {
    const escaped = (multiline ? value.includes('\r') : /[\r\n]/.test(value)) || (trim && value !== value.trim());
    const hintId = `${attributes.id}-escaped`;
    const describedBy = [attributes['aria-describedby'], escaped ? hintId : null].filter(Boolean).join(' ');
    // Suggestions would insert unquoted text into an escaped field.
    const {list, ...rest} = attributes;
    const control = h(multiline ? 'textarea' : 'input', {...rest, list: escaped ? null : list,
      value: escaped ? JSON.stringify(value) : value, 'aria-describedby': describedBy || null});
    control.addEventListener('change', () => {
      if (!escaped) { onText(trim ? control.value.trim() : control.value); return; }
      let text = null;
      try { text = JSON.parse(control.value); } catch { /* reported below */ }
      if (typeof text !== 'string') {
        control.setAttribute('aria-invalid', 'true');
        this.announce('This value is edited as a quoted JSON string, such as "a\\nb" or " padded ".', true);
        return;
      }
      control.removeAttribute('aria-invalid');
      onText(text);
    });
    control.resetValue = () => { control.value = escaped ? JSON.stringify(value) : value; };
    control.escapedHint = escaped ? h('p', {id: hintId, class: 'hint',
      text: 'Shown as a quoted JSON string so that line breaks and surrounding spaces stay exact: \\n is a line '
        + 'feed, \\r a carriage return.'}) : null;
    return control;
  }

  fieldPicker(id, value, fields, onChange, label) {
    let control;
    if (fields && fields.size) {
      const names = [...fields.keys()];
      control = h('select', {id}, !names.includes(value) ? h('option', {value, text: value ? `${value} (not in event)` : 'Choose a field…'}) : null,
        names.map(name => h('option', {value: name, text: `${name} (${fields.get(name)})`})));
      control.value = value;
      control.addEventListener('change', () => onChange(control.value));
    } else {
      control = this.textControl({id, autocomplete: 'off'}, value, onChange, {trim: true});
    }
    return h('div', {class: 'stack'}, h('label', {for: id}, label), control, control.escapedHint);
  }

  /** Typed literal at a draft path: text, int64 (decimal text, BigInt), bytes (explicit hex) or boolean. */
  literalEditor(id, literal, path, types = TYPES) {
    const allowed = types.includes(literal.type) ? types : [literal.type, ...types];
    const type = h('select', {id: `${id}-type`}, allowed.map(value => h('option', {value, text: value})));
    type.value = literal.type;
    type.addEventListener('change', () => this.switchKind(path, value => value.type, type.value, () => defaultLiteral(type.value)));
    let input;
    if (literal.type === 'boolean') {
      input = h('select', {id: `${id}-value`}, h('option', {value: 'true', text: 'true'}), h('option', {value: 'false', text: 'false'}));
      input.value = String(literal.value);
      input.addEventListener('change', () => this.set(path, {type: 'boolean', value: input.value === 'true'}));
    } else if (literal.type === 'integer') {
      input = h('input', {id: `${id}-value`, inputmode: 'numeric', autocomplete: 'off', value: literal.value.toString()});
      input.addEventListener('change', () => {
        const value = parseInt64(input.value.trim());
        if (value === null) { this.announce('Integers are whole numbers between -9223372036854775808 and 9223372036854775807.', true); return; }
        this.set(path, {type: 'integer', value});
      });
    } else if (literal.type === 'bytes') {
      input = h('input', {id: `${id}-value`, autocomplete: 'off', value: literal.hex, spellcheck: 'false', placeholder: 'hexadecimal'});
      input.addEventListener('change', () => {
        const hex = input.value.trim();
        if (!HEX.test(hex) || hex.length > 131_072) { this.announce('Bytes are written as an even number of hexadecimal digits.', true); return; }
        this.set(path, {type: 'bytes', hex});
      });
    } else {
      // Text with line feeds is edited in a textarea, which holds them exactly; only a carriage return is escaped.
      const multiline = literal.value.includes('\n');
      input = this.textControl({id: `${id}-value`, autocomplete: 'off', rows: multiline ? 3 : null}, literal.value,
        value => this.set(path, {type: 'text', value}), {multiline});
    }
    return h('div', {class: 'field-row'},
      h('div', {class: 'stack'}, h('label', {for: type.id}, 'Literal type'), type),
      h('div', {class: 'stack'}, h('label', {for: input.id}, literal.type === 'bytes' ? 'Bytes (hex)' : 'Value'), input,
        input.escapedHint));
  }

  /** Mapping source at a draft path: event field, typed literal, documented function (two levels) or CEL. */
  sourceEditor(id, source, fields, depth, path, expectedType, context = 'Source') {
    const functions = this.session.catalog?.language.functions ?? [];
    const firstField = () => fields ? [...fields.keys()][0] ?? '' : '';
    // Functions nest at most two levels, so the innermost argument cannot itself be a function.
    const kinds = [['field', 'Event field'], ['literal', 'Literal'], ['fn', 'Function'], ['expr', 'Expression']]
      .filter(([value]) => value !== 'fn' || depth < 2 || source.kind === 'fn');
    const kind = h('select', {id: `${id}-kind`, 'aria-label': `${context}: source kind`},
      kinds.map(([value, text]) => h('option', {value, text})));
    kind.value = source.kind;
    kind.addEventListener('change', () => this.switchKind(path, value => value.kind, kind.value, () => kind.value === 'field'
      ? {kind: 'field', name: firstField()}
      : kind.value === 'literal' ? {kind: 'literal', value: defaultLiteral(expectedType ?? 'text')}
        : kind.value === 'fn' ? {kind: 'fn', fn: functions[0]?.id ?? 'hex', args: [{kind: 'field', name: firstField()}]}
          : {kind: 'expr', text: ''}));
    let body;
    if (source.kind === 'field') {
      body = this.fieldPicker(`${id}-field`, source.name, fields, name => this.set([...path, 'name'], name), 'Event field');
    } else if (source.kind === 'literal') {
      body = this.literalEditor(`${id}-literal`, source.value, [...path, 'value'], expectedType ? [expectedType] : TYPES);
    } else if (source.kind === 'expr') {
      const text = this.textControl({id: `${id}-expr`, rows: 2, spellcheck: 'false'}, source.text,
        value => this.set([...path, 'text'], value), {multiline: true});
      body = h('div', {class: 'stack'}, h('label', {for: text.id}, 'Restricted CEL expression'), text, text.escapedHint);
    } else {
      const known = functions.map(fn => fn.id);
      const setFn = value => this.set([...path, 'fn'], value);
      let fn;
      if (known.length) {
        fn = h('select', {id: `${id}-fn`}, !known.includes(source.fn)
          ? h('option', {value: source.fn, text: `${source.fn} (unknown)`}) : null,
        functions.map(value => h('option', {value: value.id, text: `${value.id} → ${value.result}`})));
        fn.value = source.fn;
        fn.addEventListener('change', () => setFn(fn.value));
      } else {
        fn = this.textControl({id: `${id}-fn`, autocomplete: 'off'}, source.fn, setFn, {trim: true});
      }
      const signature = functions.find(value => value.id === source.fn);
      const args = [...path, 'args'];
      body = h('div', {class: 'stack'},
        h('label', {for: fn.id}, 'Function'), fn, fn.escapedHint,
        signature ? h('p', {class: 'hint', text: `${signature.minArguments === signature.maxArguments ? signature.minArguments
          : `${signature.minArguments}–${signature.maxArguments}`} argument(s); ${signature.bound}`}) : null,
        h('ol', {class: 'arg-list'}, source.args.map((arg, n) => h('li', {class: 'source-arg'},
          h('strong', {class: 'hint', text: `Argument ${n + 1}`}),
          depth < 2 ? this.sourceEditor(`${id}-a${n}`, arg, fields, depth + 1, [...args, n], undefined, `${context} argument ${n + 1}`)
            : h('p', {class: 'hint', text: 'Functions nest at most two levels; edit deeper arguments in the YAML view.'}),
          h('div', {class: 'row-actions'},
            h('button', {type: 'button', class: 'secondary', id: `${id}-a${n}-up`, text: 'Move up', disabled: n === 0,
              'aria-label': `${context}: move argument ${n + 1} up`,
              onclick: () => this.update(args, list => list.map((item, a) => a === n - 1 ? list[n] : a === n ? list[n - 1] : item),
                `${id}-a${n - 1}-up`)}),
            h('button', {type: 'button', class: 'secondary', id: `${id}-a${n}-remove`, text: 'Remove argument',
              'aria-label': `${context}: remove argument ${n + 1}`,
              disabled: source.args.length === 1, onclick: () => this.update(args, list => list.filter((_, a) => a !== n), `${id}-add-arg`)}))))),
        h('button', {type: 'button', class: 'secondary', id: `${id}-add-arg`, text: 'Add argument', disabled: source.args.length >= 8,
          'aria-label': `${context}: add argument`,
          onclick: () => this.update(args, list => [...list, {kind: 'field', name: firstField()}])}));
    }
    return h('div', {class: 'source-editor'},
      h('div', {class: 'stack'}, h('label', {for: kind.id}, 'Source'), kind), body);
  }

  // -------------------------------------------------------------------------------------------------------------
  // YAML, graph, checks and export
  // -------------------------------------------------------------------------------------------------------------

  renderYaml(reason) {
    const session = this.session;
    const text = this.el('yaml-text');
    const typed = this.typing && reason === 'text';
    this.typing = false;
    if (!typed && text.value !== shownText(session.text)) text.value = session.text;
    text.readOnly = session.mode === 'read-only';
    this.el('revert-text').disabled = !session.canRevertText;
    const state = this.el('yaml-state');
    state.textContent = session.mode === 'read-only' ? 'Read-only original text.'
      : session.mode === 'text-invalid' ? 'Not a valid document; the draft keeps its last valid version.'
        : session.formattingWouldChange ? 'In sync. This is your text; a form edit will rewrite it in canonical form.'
          : 'In sync with the draft.';
  }

  renderGraph() {
    const panel = this.el('panel-graph');
    if (this.tab !== 'graph') { put(panel); return; }
    const session = this.session;
    if (!session.draft) { put(panel, h('p', {class: 'note', text: 'No editable draft to draw.'})); return; }
    const graph = session.graph();
    // Bindings that name a missing component still get an edge, drawn to a placeholder so the drawing and its
    // textual equivalent always list the same bindings.
    const positions = new Map(graph.positions);
    const missing = [];
    for (const edge of graph.edges) {
      for (const [end, other] of [[edge.from, edge.to], [edge.to, edge.from]]) {
        if (positions.has(end)) continue;
        const anchor = positions.get(other) ?? {x: 24, y: 40};
        positions.set(end, {x: anchor.x + (end === edge.to ? NODE_WIDTH + 140 : -(NODE_WIDTH + 140)), y: anchor.y + 30 * missing.length});
        missing.push(end);
      }
    }
    const xs = [...positions.values()].map(p => p.x);
    const ys = [...positions.values()].map(p => p.y);
    const minX = Math.min(0, ...xs) - 20;
    const minY = Math.min(0, ...ys) - 40;
    const width = Math.max(480, Math.max(0, ...xs) - minX + NODE_WIDTH + 60);
    const height = Math.max(240, Math.max(0, ...ys) - minY + NODE_HEIGHT + 60);
    const {kind, index} = session.selection;
    const root = svg('svg', {viewBox: `${minX} ${minY} ${width} ${height}`, width, height, role: 'group',
      'aria-label': 'Binding graph. Components and effect intents are nodes; bindings are edges in execution order.'});
    const pairs = new Map();
    for (const edge of graph.edges) {
      const from = positions.get(edge.from);
      const to = positions.get(edge.to);
      if (!from || !to) continue;
      const pair = `${edge.from}>${edge.to}`;
      const lane = pairs.get(pair) ?? 0;
      pairs.set(pair, lane + 1);
      const offset = lane * 16;
      let d; let lx; let ly;
      if (edge.from === edge.to) {
        const x = from.x + NODE_WIDTH - 30; const y = from.y;
        d = `M ${x} ${y} C ${x + 60} ${y - 70 - offset}, ${x - 90} ${y - 70 - offset}, ${x - 60} ${y}`;
        lx = x - 30; ly = y - 58 - offset;
      } else {
        const x1 = from.x + NODE_WIDTH; const y1 = from.y + NODE_HEIGHT / 2 + offset / 2;
        const x2 = to.x; const y2 = to.y + NODE_HEIGHT / 2 + offset / 2;
        const bend = Math.max(40, Math.abs(x2 - x1) / 2);
        d = `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`;
        // Labels sit above both nodes so they never cover a node, whatever the gap between them.
        lx = (x1 + x2) / 2; ly = Math.min(from.y, to.y) - 12 - offset;
      }
      const label = visibleText(`${edge.order + 1}. ${edge.event} → ${edge.action}${edge.conditions ? ` [${edge.conditions} if]` : ''}`);
      const group = svg('g', {class: `graph-edge${edge.dangling ? ' dangling' : ''}`, tabindex: '0', role: 'button',
        id: `graph-edge-${edge.order}`,
        'aria-current': kind === 'binding' && index === edge.order ? 'true' : 'false',
        'aria-label': visibleText(`Binding ${edge.order + 1} ${edge.id}: ${edge.event} to ${edge.action}`
          + `${edge.dangling ? ', which names a missing component' : ''}. Opens the binding form.`),
        onclick: () => this.select('binding', edge.order),
        onkeydown: event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); this.select('binding', edge.order, `select-binding-${edge.order}`); } }},
      svg('title', {text: label}), svg('path', {d, 'marker-end': 'url(#arrow)'}),
      svg('text', {x: lx, y: ly, 'text-anchor': 'middle', text: truncate(label, 44)}));
      root.append(group);
    }
    for (const id of missing) {
      const position = positions.get(id);
      root.append(svg('g', {class: 'graph-missing', transform: `translate(${position.x} ${position.y})`, 'aria-hidden': 'true'},
        svg('rect', {width: NODE_WIDTH, height: NODE_HEIGHT, rx: 10}),
        svg('text', {x: 14, y: 26, text: truncate(visibleText(id.slice(id.indexOf(':') + 1)) || '(empty)', 24)}),
        svg('text', {x: 14, y: 46, class: 'detail', text: 'missing component'})));
    }
    root.prepend(svg('defs', {}, svg('marker', {id: 'arrow', viewBox: '0 0 10 10', refX: '9', refY: '5', markerWidth: '7',
      markerHeight: '7', orient: 'auto-start-reverse'}, svg('path', {d: 'M 0 0 L 10 5 L 0 10 z', fill: '#54d6a4'}))));
    for (const node of graph.nodes) {
      const position = positions.get(node.id);
      const componentIndex = node.kind === 'component' ? node.index : null;
      const effectBinding = node.kind === 'effect' ? session.draft.bindings.findIndex(binding => `effect:${binding.id}` === node.id) : null;
      const current = (kind === 'component' && index === componentIndex) || (kind === 'binding' && index === effectBinding);
      const group = svg('g', {class: `graph-node ${node.kind}`, tabindex: '0', role: 'button',
        id: `graph-node-${key(node.id)}`, transform: `translate(${position.x} ${position.y})`,
        'aria-current': current ? 'true' : 'false',
        'aria-label': visibleText(`${node.kind === 'component' ? `Component ${node.label}, machine ${node.detail || 'not chosen'}`
          : `Effect intent ${node.label}`}. Enter opens its form; arrow keys move it (layout only).`)},
      svg('title', {text: visibleText(`${node.label} · ${node.detail}`)}),
      svg('rect', {width: NODE_WIDTH, height: NODE_HEIGHT, rx: 10}),
      svg('text', {x: 14, y: 26, text: truncate(visibleText(node.label), 24)}),
      svg('text', {x: 14, y: 46, class: 'detail', text: truncate(visibleText(node.detail || ''), 28)}));
      group.addEventListener('focus', () => { this.graphNode = node.id; });
      const open = () => node.kind === 'component' ? this.select('component', componentIndex, `select-component-${componentIndex}`)
        : this.select('binding', effectBinding, `select-binding-${effectBinding}`);
      group.addEventListener('keydown', event => {
        const step = event.shiftKey ? 100 : 20;
        const moves = {ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step]};
        if (moves[event.key]) {
          event.preventDefault();
          this.pendingFocus = group.id;
          session.moveNode(node.id, position.x + moves[event.key][0], position.y + moves[event.key][1]);
        } else if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open(); }
      });
      this.dragBehaviour(root, group, node, position, open);
      root.append(group);
    }
    const text = h('div', {class: 'graph-text'},
      h('h3', {class: 'hint', text: 'Graph as text'}),
      h('p', {class: 'hint', text: `Components: ${session.draft.components.map(component => `${component.id} (${component.machine || 'no machine'})`).join(', ') || 'none'}.`}),
      h('ol', {}, graph.edges.map(edge => h('li', {}, h('button', {type: 'button', id: `graph-text-${edge.order}`,
        onclick: () => this.select('binding', edge.order, `select-binding-${edge.order}`),
        text: visibleText(`${edge.id}: when ${edge.from.slice('component:'.length)} publishes ${edge.event || '(no event)'}`
          + `${edge.conditions ? ` and ${edge.conditions === 1 ? 'its condition holds' : `all ${edge.conditions} conditions hold`}` : ''}, `
          + `${edge.to.startsWith('effect:') ? `record ${edge.action}` : `run ${edge.to.slice('component:'.length)} ${edge.action || '(no command)'}`}`
          + `${edge.dangling ? ' — references a missing component' : ''}`)})))));
    const layoutInput = h('input', {type: 'file', id: 'import-layout', accept: '.json,application/json'});
    layoutInput.addEventListener('change', () => this.readFile(layoutInput, bytes => {
      try { session.importLayout(bytes); this.announce('Imported the layout. The document is unchanged.'); }
      catch (error) { this.announce(`The layout was not imported: ${error.message}`, true); }
    }));
    put(panel,
      h('p', {class: 'hint', text: 'Drag nodes or focus one and use the arrow keys to arrange the drawing. Positions are '
        + 'presentation only: they never change the document, its compiled program or its identity.'}),
      h('div', {class: 'graph-wrap'}, root),
      this.nodeMover(graph),
      h('div', {class: 'inline-actions'},
        h('button', {type: 'button', class: 'secondary', id: 'layout-export', text: 'Download layout',
          onclick: () => this.services.download(session.exportLayout())}),
        h('button', {type: 'button', class: 'secondary', id: 'layout-reset', text: 'Automatic layout',
          onclick: () => { session.resetLayout(); this.announce('Using the automatic layout.'); }}),
        h('label', {class: 'file-button'}, 'Import layout', layoutInput)),
      text);
  }

  /** Single-pointer alternative to dragging: move the chosen node with buttons (layout only). */
  nodeMover(graph) {
    const nodes = graph.nodes;
    if (!nodes.length) return null;
    const chosen = nodes.some(node => node.id === this.graphNode) ? this.graphNode : nodes[0].id;
    const select = h('select', {id: 'move-node'}, nodes.map(node => h('option', {value: node.id,
      text: visibleText(`${node.kind === 'component' ? 'Component' : 'Effect'} ${node.label}`)})));
    select.value = chosen;
    select.addEventListener('change', () => { this.graphNode = select.value; });
    const move = (dx, dy, label, id) => h('button', {type: 'button', class: 'secondary', id, text: label,
      'aria-label': `Move the chosen node ${label.toLowerCase()}`, onclick: () => {
        const position = graph.positions.get(select.value);
        this.graphNode = select.value;
        this.pendingFocus = id;
        this.session.moveNode(select.value, position.x + dx, position.y + dy);
      }});
    return h('div', {class: 'inline-actions node-mover'},
      h('label', {for: 'move-node'}, 'Node'), select,
      move(-40, 0, 'Left', 'move-left'), move(40, 0, 'Right', 'move-right'),
      move(0, -40, 'Up', 'move-up'), move(0, 40, 'Down', 'move-down'));
  }

  dragBehaviour(root, group, node, position, open) {
    let start = null;
    group.addEventListener('pointerdown', event => {
      if (event.button !== 0) return;
      const matrix = root.getScreenCTM()?.inverse();
      if (!matrix) return;
      const point = new DOMPoint(event.clientX, event.clientY).matrixTransform(matrix);
      start = {x: point.x, y: point.y, matrix, moved: false};
      group.setPointerCapture(event.pointerId);
    });
    group.addEventListener('pointermove', event => {
      if (!start) return;
      const point = new DOMPoint(event.clientX, event.clientY).matrixTransform(start.matrix);
      const dx = point.x - start.x; const dy = point.y - start.y;
      if (Math.abs(dx) + Math.abs(dy) > 3) start.moved = true;
      group.setAttribute('transform', `translate(${position.x + dx} ${position.y + dy})`);
      start.dx = dx; start.dy = dy;
    });
    group.addEventListener('pointerup', event => {
      if (!start) return;
      const moved = start.moved;
      const {dx = 0, dy = 0} = start;
      start = null;
      group.releasePointerCapture(event.pointerId);
      if (moved) this.session.moveNode(node.id, position.x + dx, position.y + dy);
      else open();
    });
  }

  renderChecks() {
    const session = this.session;
    const list = this.el('checks');
    const diagnostics = session.diagnostics();
    if (!session.draft) {
      put(list, h('li', {class: 'hint', text: 'No editable draft.'}));
      return;
    }
    if (!diagnostics.length) {
      put(list, h('li', {class: 'hint', text: 'No local findings. This does not mean the document is valid.'}));
      return;
    }
    put(list, ...diagnostics.slice(0, 256).map((diagnostic, n) => h('li', {class: diagnostic.severity},
      h('button', {type: 'button', id: `check-${n}`, onclick: () => this.navigate(diagnostic.segments)},
        h('strong', {text: `${diagnostic.severity === 'info' ? 'Note' : 'Check'} · ${diagnostic.code}`}),
        visibleText(diagnostic.message), h('span', {class: 'visually-hidden', text: ` at ${visibleText(renderPath(diagnostic.segments))}`})))));
  }

  /** Opens the form containing an authored path and focuses the closest control. */
  navigate(segments) {
    const path = segments[0] === 'composite' ? segments.slice(1) : segments;
    if (path[0] === 'components' && typeof path[1] === 'number') this.session.selection = {kind: 'component', index: path[1]};
    else if (path[0] === 'bindings' && typeof path[1] === 'number') this.session.selection = {kind: 'binding', index: path[1]};
    else if (path[0] === 'limits' || path[0] === 'workflowFromHeight') this.session.selection = {kind: 'limits', index: null};
    this.pendingPath = path;
    if (this.tab !== 'form') this.showTab('form');
    else this.render('navigate');
  }

  focusPath(path) {
    let best = null; let bestLength = -1;
    for (const element of document.querySelectorAll('#panel-form [data-path]')) {
      const candidate = JSON.parse(element.dataset.path);
      if (candidate.length > path.length || candidate.length <= bestLength) continue;
      if (candidate.every((segment, n) => segment === path[n])) { best = element; bestLength = candidate.length; }
    }
    const target = best?.matches('input,select,textarea,button') ? best
      : best?.querySelector('input,select,textarea,button:not(:disabled)') ?? this.el('editor');
    target.focus();
  }

  renderExport() {
    const session = this.session;
    const panel = this.el('export-panel');
    const exportable = session.mode === 'editable';
    const reason = session.mode === 'read-only' ? 'Read-only documents can only be downloaded as the original.'
      : session.mode === 'text-invalid' ? 'Fix or revert the YAML text to export.' : null;
    const blueprint = session.document.origin === 'blueprint' && Boolean(session.document.blueprint);
    put(panel,
      h('h2', {id: 'export-heading', text: 'Export'}),
      h('div', {class: 'export-actions'},
        h('button', {type: 'button', id: 'download-document', text: `Download ${EXPORT_NAMES.document}`, disabled: !exportable,
          'aria-describedby': reason ? 'export-reason' : null, onclick: () => this.downloadDocument()}),
        blueprint ? h('button', {type: 'button', class: 'secondary', id: 'download-blueprint',
          text: `Download ${EXPORT_NAMES.blueprint}`, disabled: !exportable, onclick: () => {
            try {
              this.services.download(session.exportBlueprint());
              this.announce(`Downloaded ${EXPORT_NAMES.blueprint}; only the selected composite changed.`);
            } catch (error) {
              this.announce(error instanceof SessionError ? error.message : `Studio could not prove the rest of the blueprint `
                + `stays unchanged (${error.message}). Download ${EXPORT_NAMES.document} and replace the composite yourself.`, true);
            }
          }}) : null,
        session.document.original || session.document.blueprint ? h('button', {type: 'button', class: 'secondary', id: 'download-original',
          text: 'Download original', onclick: () => this.services.download(session.originalDocument())}) : null),
      reason ? h('p', {id: 'export-reason', class: 'note', text: reason}) : null,
      h('p', {class: 'note'}, 'Next, validate with the version-matched CLI and import its report: see the ',
        h('button', {type: 'button', class: 'link-button', id: 'open-validate', text: 'Validate tab',
          onclick: () => { this.showTab('validate'); this.el('tab-validate').focus(); }}), '.'),
      blueprint ? h('p', {class: 'note', text: 'A composite inside a blueprint is validated by project rendering, not by Studio.'}) : null);
  }
}

export {fileText};
