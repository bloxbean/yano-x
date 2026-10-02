# Illustration data reference

Each file in `data/` default-exports one illustration. The file name must be
`<id>.mjs`; adding the file registers it. A page shows it with
`<!-- illustration: <id> -->` (see the www README for page markers and the
authoring checklist). `render.mjs` turns the data into complete HTML at build
time; `client.js` adds controls.

## Common fields

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Matches the file name; lowercase words joined by hyphens. |
| `type` | yes | `diagram`, `steps`, or `chooser`. |
| `title` | yes | Short name shown in the panel bar. |
| `tag` | no | Badge text. Defaults to `Illustrative data`; use `Concept` for structure-only diagrams and `Decision aid` for choosers. |
| `intro` | no | Text after "What you're seeing:". |
| `caption` | no | A sentence or two under a diagram or chooser. |
| `legend` | no | `[[kind, label], …]` using the kinds below. |
| `sources` | yes | `[{ repo: 'yano-x' \| 'yano', path, anchors: [ … ] }]`. Every anchor is an exact identifier or phrase that must appear in that file (whitespace-normalized). Prefer contract names: operations, result codes, event names, config keys, CLI flags. |

Text fields allow `**bold**` and `` `code` `` only.

Kinds (actor colours, the same on every page): `actor` (organization or
person), `client` (application), `member`, `core`, `ledger`, `leader`,
`runtime` (node-local execution), `external`, `cardano`, `final`, `fail`.

## `diagram`: block diagrams and explorers

```js
{
  zones: [{ id, kind, label, contains: [blockIds] }],
  blocks: [{ id, label, sub?, kind, detail?, link?: { label, href } }],
  edges: [{ from, to, label?, style?: 'dashed' }],   // from/to may name a zone
  hint?: 'Select a block to see what it does.',        // shown when blocks have details
  layouts: {
    wide: { width: 760, height, zones: { id: [x, y, w, h] }, blocks: { id: [x, y, w, h] },
            edges?: { 'from->to': { fromSide, toSide, fromAt, toAt, via, labelAt, label: false } },
            labels?: { blockId: { label, sub } } },
    narrow?: { width: 380, … },                         // shown in containers under 560px
  },
}
```

- Lay out the wide view at about 760 units wide so 14-unit labels render near
  their CSS size in the 48rem column; add a vertical `narrow` layout (about 380
  wide) whenever the wide one has more than three columns.
- Labels use 14 units (sub-lines 12). The test allows about 8.4 units per label
  character and 6.7 per sub character, plus 12 units of padding; use `\n` or a
  per-layout `labels` override when a label is long.
- Sides are `l`, `r`, `t`, `b`. Routes are orthogonal with rounded corners; use
  `via` points to steer around blocks.
- Blocks with `detail` become explorable: keyboard- and click-selectable, with a
  detail panel. Without JavaScript the details render as a list.

## `steps`: step-throughs and rule simulators

```js
{
  lanes: [{ id, label, note?, kind }],     // [] for a caption-only walkthrough
  views?: [{ id: 'default', label }, { id, label, focus: [laneIds] }],
  scenarios: [{
    id, label, summary?,
    steps: [{
      title, text,
      viewText?: { viewId: text },
      wires?: [{ from, to, label, tone? }],                 // at most two per step reads well
      cards?: { laneId: { title, detail?, tone? } | null }, // a card persists until replaced or null
      focus?: [laneIds],                                    // default: lanes with cards or wires
      command?: './yano.sh appchain …',
      checks?: [{ label, ok: true | false | null, code? }], // evaluation order; null = not reached
      state?: { caption?, columns: [], rows: [[]], highlight?: [rowIndex] },
    }],
  }],
}
```

- The first scenario is the main story; others appear as "What if" chips.
  Use them for "try to break it" cases, each ending in the exact rule that
  fails and its code from the source.
- Tones: `ok`, `pending`, `leader`, `cardano`, `final`, `fail`.
- A `docs/` page that uses a step-through wraps a numbered fallback list whose
  `**Title.**` items equal the first scenario's step titles, then closes with
  `<!-- /illustration -->`. The test enforces the match.

## `chooser`: decision trees

```js
{
  start: 'nodeId',
  nodes: {
    q1: { question, help?, options: [{ label, next }] },          // two or more options
    r1: { result: { title, text?, facts?: [[k, v]], links?: [{ label, href }] } },
  },
}
```

The tree must be acyclic, and every node must be reachable. Without
JavaScript the whole tree renders as an outline.
