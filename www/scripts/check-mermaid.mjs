// Catch mermaid diagrams that will render as a "Syntax error" box.
//
// This fast source check does not run Mermaid. Actual parsing and rendering
// are checked by npm run check:browser before deployment. This lint covers
// the constructs that have actually broken diagrams here, run after the
// importer so it covers imported pages too.
//
// It is not a mermaid parser and does not pretend to be one. Its job is to stop
// a known-bad pattern from shipping silently; anything subtler still needs a
// look in the browser.
//
//   node scripts/check-mermaid.mjs

import fs from 'node:fs/promises';
import path from 'node:path';
import { CONTENT_ROOT, DOCSITE_ROOT } from './repo-sources.mjs';

// Diagram types mermaid recognises on the opening line.
const DIAGRAM_TYPES = [
  'flowchart', 'graph', 'sequenceDiagram', 'classDiagram', 'stateDiagram',
  'stateDiagram-v2', 'erDiagram', 'journey', 'gantt', 'pie', 'quadrantChart',
  'requirementDiagram', 'gitGraph', 'mindmap', 'timeline', 'zenuml', 'sankey',
  'sankey-beta', 'xychart', 'xychart-beta', 'block', 'block-beta', 'packet',
  'packet-beta', 'kanban', 'architecture', 'architecture-beta', 'radar',
  'treemap', 'C4Context', 'C4Container', 'C4Component', 'C4Dynamic',
];

/**
 * `participant X as Some Label` is parsed as a bare token stream, so an
 * unquoted bracket or separator in the alias is a hard parse error. This is the
 * one that bit us: `participant Ingress as Any member (ingress)`.
 */
const ALIAS = /^\s*(participant|actor)\s+\S+\s+as\s+(.+?)\s*$/;
const ALIAS_FORBIDDEN = /[()[\]{};:,]/;

/**
 * `;` is a statement separator in mermaid, so a semicolon inside sequence
 * message text silently splits the line into a message plus a stray statement.
 * `Members->>Members: Commit; tip advances` fails exactly this way.
 */
const SEQ_MESSAGE = /^\s*\S+\s*--?>>?[+-]?\s*\S+\s*:\s*(.+)$/;

async function* walk(dir) {
  let entries;
  try {
    entries = await fs.readdir(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* walk(full);
    else if (/\.mdx?$/.test(entry.name)) yield full;
  }
}

/** Extract every ```mermaid fence with its starting line number. */
function mermaidBlocks(text) {
  const blocks = [];
  const lines = text.split('\n');
  let open = null;
  for (let i = 0; i < lines.length; i += 1) {
    const fence = lines[i].match(/^\s*```\s*(\w+)?\s*$/);
    if (!fence) continue;
    if (open === null) {
      if (fence[1] === 'mermaid') open = i;
    } else {
      blocks.push({ startLine: open + 1, body: lines.slice(open + 1, i) });
      open = null;
    }
  }
  return blocks;
}

function lintBlock({ startLine, body }, rel, problems) {
  const first = body.find((l) => l.trim() !== '')?.trim() ?? '';
  const type = first.split(/[\s({]/)[0];
  if (!DIAGRAM_TYPES.includes(type)) {
    problems.push(
      `${rel}:${startLine + 1}  unrecognised diagram type "${type || '(empty)'}" ` +
      `— mermaid will not render this block`,
    );
  }

  const isSequence = type === 'sequenceDiagram';

  body.forEach((line, offset) => {
    if (isSequence) {
      const msg = line.match(SEQ_MESSAGE);
      if (msg && msg[1].includes(';')) {
        problems.push(
          `${rel}:${startLine + offset + 1}  sequence message text contains ";" ` +
          `— mermaid treats it as a statement separator (got: ${msg[1].trim()})`,
        );
      }
    }

    const m = line.match(ALIAS);
    if (!m) return;
    const alias = m[2];
    if (alias.startsWith('"') && alias.endsWith('"')) return;
    const bad = alias.match(ALIAS_FORBIDDEN);
    if (bad) {
      problems.push(
        `${rel}:${startLine + offset + 1}  ${m[1]} alias contains "${bad[0]}" ` +
        `— mermaid cannot parse an unquoted alias with brackets or separators ` +
        `(got: ${alias})`,
      );
    }
  });
}

async function main() {
  const problems = [];
  let files = 0;
  let blocks = 0;

  for await (const file of walk(CONTENT_ROOT)) {
    const text = await fs.readFile(file, 'utf8');
    const found = mermaidBlocks(text);
    if (found.length === 0) continue;
    files += 1;
    blocks += found.length;
    const rel = path.relative(DOCSITE_ROOT, file);
    for (const block of found) lintBlock(block, rel, problems);
  }

  if (problems.length > 0) {
    console.error(`\n[check-mermaid] ${problems.length} problem(s):\n`);
    for (const p of problems) console.error(`  ${p}`);
    console.error('');
    process.exit(1);
  }

  console.log(`[check-mermaid] ${blocks} diagram(s) across ${files} page(s) pass source lint (run check:browser for rendering).`);
}

await main();
