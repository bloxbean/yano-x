// Turn ```mermaid fences into `<pre class="mermaid">` blocks.
//
// This runs at the remark (mdast) stage, before Starlight's Expressive Code
// renderer sees the node, so mermaid source is never syntax-highlighted as if
// it were a programming language. The rendering itself happens client-side and
// is loaded lazily — see src/components/overrides/Head.astro.
//
// Deliberately dependency-free: a five-line tree walk avoids pulling in
// unist-util-visit for one traversal.

function escapeHtml(text) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function walk(node, parent) {
  if (!node || typeof node !== 'object') return;

  if (node.type === 'code' && node.lang === 'mermaid' && parent) {
    // mermaid reads textContent, so the escaped entities decode back to the
    // original diagram source in the browser.
    Object.assign(node, {
      type: 'html',
      value: `<pre class="mermaid" aria-label="Diagram">${escapeHtml(node.value)}</pre>`,
    });
    delete node.lang;
    delete node.meta;
    return;
  }

  for (const child of node.children ?? []) walk(child, node);
}

export default function remarkMermaid() {
  return (tree) => walk(tree, null);
}
