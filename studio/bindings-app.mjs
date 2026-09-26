/**
 * Binding editor page bootstrap (ADR-031.2). Reads only Studio's own static assets (the bundled reference
 * catalog and starters) and saves files through ordinary browser downloads. There is no other network use, no
 * browser storage, no plugin loading and no submission path.
 */
import {importAuthoringCatalog} from './binding-catalog.mjs';
import {BindingEditor} from './binding-editor.mjs';
import {BindingSession} from './binding-session.mjs';

async function fetchBytes(path) {
  const response = await fetch(path, {cache: 'no-store', credentials: 'same-origin'});
  if (!response.ok) throw new Error(`Could not load ${path}`);
  return new Uint8Array(await response.arrayBuffer());
}

/** Saves exact bytes when a file is returned as imported (for example an original with a BOM), else UTF-8 text. */
function download({name, text, bytes = null}) {
  const url = URL.createObjectURL(new Blob([bytes ?? text], {type: 'text/plain;charset=utf-8'}));
  const anchor = Object.assign(document.createElement('a'), {href: url, download: name});
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  // Keep the object URL alive long enough for every browser to start the download.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

let bundledCatalog = null;
let problem = null;
try {
  bundledCatalog = importAuthoringCatalog(await fetchBytes('binding-authoring-catalog.json'));
} catch (error) {
  problem = `The bundled reference catalog could not be loaded (${error.message}). Import a catalog to get descriptors.`;
}
const session = new BindingSession(bundledCatalog
  ? {catalog: bundledCatalog, catalogOrigin: 'bundled', catalogName: 'Bundled reference catalog'} : {});
const editor = new BindingEditor(session, {download, fetchBytes, bundledCatalog});
if (problem) editor.announce(problem, true);
document.documentElement.dataset.ready = 'true';
