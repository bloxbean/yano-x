/**
 * Writes Studio round-trip cases for the Java compiler (ADR-031.2 M1 gate). For every first-party example and
 * corpus document this emits the document after a no-op import/export, after a graph-layout change, after a
 * binding reorder and after a component rename. The Java integration test compiles each file with the real
 * catalog and asserts which ones keep the original IR and profile.
 */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {importDocument} from '../main/web/binding-draft.mjs';
import {moveBinding,renameComponent} from '../main/web/binding-edit.mjs';
import {BindingSession} from '../main/web/binding-session.mjs';
import {draftForChain,readBlueprint,spliceComposite} from '../main/web/binding-blueprint.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../../../..');
const output = path.resolve(process.argv[2]);
fs.rmSync(output, {recursive: true, force: true});
fs.mkdirSync(output, {recursive: true});

const sources = [
  ...fs.readdirSync(path.join(repo, 'examples/bindings')).filter(name => name.endsWith('.yaml')).sort().map(name => ({name: `example-${name}`,
    file: path.join(repo, 'examples/bindings', name), context: exampleContext(name)})),
  ...fs.readdirSync(path.join(here, 'fixtures/corpus')).filter(name => name.endsWith('.yaml')).sort()
    .map(name => ({name: `corpus-${name}`, file: path.join(here, 'fixtures/corpus', name),
      context: name.startsWith('effects') ? 'effects' : 'tutorial'}))];

function exampleContext(name) {
  const recipe = name.replace(/\.yaml$/, '');
  return ['procurement', 'attestation', 'dpp-approval', 'feed-approval'].includes(recipe) ? `recipe:${recipe}` : 'tutorial';
}

const cases = [];
for (const source of sources) {
  const original = fs.readFileSync(source.file, 'utf8');
  const imported = importDocument(original);
  if (imported.state !== 'editable') throw new Error(`${source.name} is not editable: ${imported.error.message}`);
  const draft = imported.draft;
  const write = (suffix, text) => {
    const file = `${source.name}.${suffix}.yaml`;
    fs.writeFileSync(path.join(output, file), text);
    return file;
  };
  // Every export goes through the editor session, exactly as the page does.
  const session = () => {
    const value = new BindingSession();
    value.loadText(original, {name: source.name, origin: 'file'});
    value.acknowledgeFormatting();
    return value;
  };
  const canonical = session();
  canonical.edit(value => value, {structural: false});
  const files = {original: write('original', original), emitted: write('emitted', canonical.exportDocument().text)};
  // Graph gestures move every node through the session; the exported document must stay byte-identical.
  const moved = session();
  moved.edit(value => value, {structural: false});
  for (const [index, node] of moved.graph().nodes.entries()) moved.moveNode(node.id, 999 + index * 7, -42 - index);
  fs.writeFileSync(path.join(output, `${source.name}.layout.json`), moved.exportLayout().text);
  files.layoutMoved = write('layout-moved', moved.exportDocument().text);
  if (draft.bindings.length > 1) {
    const reordered = session();
    reordered.edit(value => moveBinding(value, 0, 1));
    files.reordered = write('reordered', reordered.exportDocument().text);
  }
  if (draft.components.length) {
    const renamed = session();
    renamed.edit(value => renameComponent(value, 0, `${value.components[0].id.slice(0, 50)}-renamed`));
    files.renamed = write('renamed', renamed.exportDocument().text);
  }
  cases.push({name: source.name, context: source.context, files});
}
// A blueprint whose composite is re-emitted by Studio: only that value may change, and Java must read it back.
const blueprintText = fs.readFileSync(path.join(here, 'fixtures/blueprint-two-chains.yaml'), 'utf8');
const blueprint = readBlueprint(blueprintText);
fs.writeFileSync(path.join(output, 'blueprint.original.yaml'), blueprintText);
fs.writeFileSync(path.join(output, 'blueprint.spliced.yaml'), spliceComposite(blueprint, 1, draftForChain(blueprint, 1)));
const blueprints = [{name: 'blueprint-two-chains', chainId: 'workflow', original: 'blueprint.original.yaml',
  spliced: 'blueprint.spliced.yaml'}];
fs.writeFileSync(path.join(output, 'manifest.json'), `${JSON.stringify({schema: 'yano-x-studio-roundtrip-v1', cases, blueprints}, null, 2)}\n`);
console.log(`wrote ${cases.length} round-trip cases to ${output}`);
