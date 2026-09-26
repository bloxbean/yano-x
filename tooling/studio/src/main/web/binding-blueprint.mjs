/**
 * Selecting and replacing one chain's `composite` value inside an `appchain.yaml` blueprint (ADR-031.2 §7, C8).
 *
 * Studio never rewrites a blueprint wholesale. It replaces only the source span of the selected chain's composite
 * value, keeps every other byte, then re-parses the result and proves that every other node is unchanged and that
 * the composite reads back as the draft. If any condition fails, it refuses and the caller offers the composite
 * document alone. The blueprint is intent: project rendering remains authoritative for the lock and identities.
 */
import {draftFromTree,emitDocument} from './binding-draft.mjs';
import {mapEntry,parseYaml,typedTree} from './studio-yaml.mjs';

export const MAX_BLUEPRINT_CHARACTERS = 1024 * 1024;

export class BlueprintError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'BlueprintError';
    this.code = code;
  }
}

function chains(root) {
  const spec = mapEntry(root, 'spec')?.value;
  const list = mapEntry(spec, 'chains')?.value;
  if (list?.kind !== 'seq') throw new BlueprintError('BLUEPRINT_SHAPE', 'The blueprint has no spec.chains list');
  return list.items;
}

/**
 * Lists the chains whose recipe is `declarative-composite` and that carry a composite value.
 *
 * @returns {{text, root, lineEnding, composites: Array<{chainIndex, chainId, node}>}}
 */
export function readBlueprint(text) {
  if (typeof text !== 'string' || text.length > MAX_BLUEPRINT_CHARACTERS) {
    throw new BlueprintError('BLUEPRINT_TOO_LARGE', 'Blueprints are limited to 1 MiB');
  }
  const {root, lineEnding} = parseYaml(text, {maxCharacters: MAX_BLUEPRINT_CHARACTERS});
  const items = chains(root);
  const ids = new Set();
  const composites = [];
  items.forEach((chain, chainIndex) => {
    const id = mapEntry(chain, 'chainId')?.value;
    const chainId = id?.kind === 'scalar' && id.type === 'text' ? id.value : null;
    if (chainId === null || ids.has(chainId)) {
      throw new BlueprintError('BLUEPRINT_CHAIN_IDS', 'Every chain needs a unique text chainId');
    }
    ids.add(chainId);
    const recipe = mapEntry(chain, 'recipe')?.value;
    const composite = mapEntry(chain, 'composite')?.value;
    if (composite && recipe?.kind === 'scalar' && recipe.value === 'declarative-composite') {
      composites.push({chainIndex, chainId, node: composite});
    }
  });
  return {text, root, lineEnding, composites};
}

/** The draft for one listed composite (a blueprint composite has no `composite:` wrapper). */
export function draftForChain(blueprint, chainIndex) {
  const entry = blueprint.composites.find(value => value.chainIndex === chainIndex);
  if (!entry) throw new BlueprintError('BLUEPRINT_CHAIN', 'That chain has no declarative composite');
  return draftFromTree(entry.node);
}

function replaceComposite(tree, chainIndex, replacement) {
  const copy = structuredClone(tree);
  const spec = copy.map.find(([key]) => key === 'spec')[1];
  const list = spec.map.find(([key]) => key === 'chains')[1];
  const chain = list.seq[chainIndex];
  chain.map.find(([key]) => key === 'composite')[1] = replacement;
  return copy;
}

/**
 * Replaces one chain's composite value with the draft and verifies the result.
 *
 * @throws {BlueprintError} when the splice cannot be proven to leave every other node unchanged
 * @returns {string} blueprint text with only that value replaced
 */
export function spliceComposite(blueprint, chainIndex, draft) {
  const entry = blueprint.composites.find(value => value.chainIndex === chainIndex);
  if (!entry) throw new BlueprintError('BLUEPRINT_CHAIN', 'That chain has no declarative composite');
  const text = blueprint.text;
  const node = entry.node;
  const ending = blueprint.lineEnding;
  const body = emitDocument({...draft, wrapped: false}, {lineEnding: '\n'}).replace(/\n$/, '').split('\n');
  let replaced;
  if (node.style === 'block') {
    // Replace whole lines from the first to the last line of the value, re-indented at its column.
    const lineStart = text.lastIndexOf('\n', node.start - 1) + 1;
    const indent = ' '.repeat(node.start - lineStart);
    let lineEnd = text.indexOf('\n', node.end);
    if (lineEnd < 0) lineEnd = text.length;
    else if (text[lineEnd - 1] === '\r') lineEnd -= 1;
    replaced = text.slice(0, lineStart) + body.map(line => indent + line).join(ending) + text.slice(lineEnd);
  } else {
    // A flow value becomes a block on the following lines, indented deeper than its key.
    const lineStart = text.lastIndexOf('\n', node.start - 1) + 1;
    const keyIndent = /^ */.exec(text.slice(lineStart))[0].length;
    const indent = ' '.repeat(keyIndent + 2);
    let before = node.start;
    while (before > lineStart && text[before - 1] === ' ') before -= 1;
    replaced = text.slice(0, before) + ending + body.map(line => indent + line).join(ending) + text.slice(node.end);
  }
  let reread;
  try { reread = readBlueprint(replaced); }
  catch (error) { throw new BlueprintError('BLUEPRINT_SPLICE_UNVERIFIED', `The spliced blueprint does not re-read: ${error.message}`); }
  const splicedEntry = reread.composites.find(value => value.chainIndex === chainIndex);
  const expectedTree = typedTree(parseYaml(emitDocument({...draft, wrapped: false})).root);
  if (!splicedEntry || JSON.stringify(typedTree(splicedEntry.node)) !== JSON.stringify(expectedTree)) {
    throw new BlueprintError('BLUEPRINT_SPLICE_UNVERIFIED', 'The spliced composite does not read back as the draft');
  }
  const otherBefore = replaceComposite(typedTree(blueprint.root), chainIndex, null);
  const otherAfter = replaceComposite(typedTree(reread.root), chainIndex, null);
  if (JSON.stringify(otherBefore) !== JSON.stringify(otherAfter)) {
    throw new BlueprintError('BLUEPRINT_SPLICE_UNVERIFIED', 'The splice would change another blueprint field');
  }
  return replaced;
}
