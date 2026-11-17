"""Generates the Yano X master architecture diagram.

Usage, from the repository root:

    python3 docs/architecture/generate_diagram.py docs/architecture/yano-x-architecture.svg
    node docs/architecture/render_png.cjs

Edit the facts here rather than in the generated SVG, then regenerate both files.
"""
import sys
from html import escape

W = 1800
FONT = "Inter, 'Segoe UI', 'Helvetica Neue', Helvetica, Arial, sans-serif"
MONO = "'JetBrains Mono', 'SF Mono', Menlo, Consolas, monospace"

C = dict(
    bg="#0a1216", panel="#101b21", panel2="#15242c", line="#2b3b43", wire="#8aa3ae",
    text="#eef4f5", muted="#a9bcc4", dim="#7d929b",
    host="#dbe6ea", hostbg="#17252c",
    client="#8eb2ff", clientbg="#16233d",
    x="#9af0d4", xbg="#0f2c25",
    lead="#f6c56b", leadbg="#33270f",
    fx="#f5a3c7", fxbg="#331727",
    ext="#c6adff", extbg="#221b3c",
    ada="#73b6ff", adabg="#0f2645",
    store="#b9c9d0", storebg="#121e24",
)

out = []


def e(s):
    return escape(s, quote=True)


def rect(x, y, w, h, stroke, fill, r=10, sw=1.4, dash=None, op=1.0):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    out.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" rx="{r}" fill="{fill}" '
               f'fill-opacity="{op}" stroke="{stroke}" stroke-width="{sw}"{d}/>')


def rich(s):
    """**bold** and `mono` spans inside one text line."""
    res, i = "", 0
    while i < len(s):
        if s.startswith("**", i):
            j = s.index("**", i + 2)
            res += f'<tspan font-weight="700">{e(s[i + 2:j])}</tspan>'
            i = j + 2
        elif s[i] == "`":
            j = s.index("`", i + 1)
            res += f'<tspan font-family="{e(MONO)}" font-size="0.92em">{e(s[i + 1:j])}</tspan>'
            i = j + 1
        else:
            j = min([k for k in (s.find("**", i), s.find("`", i)) if k != -1] or [len(s)])
            res += e(s[i:j])
            i = j
    return res


def text(x, y, s, size=13, color=None, weight=400, anchor="start", mono=False, italic=False):
    fam = MONO if mono else FONT
    st = ' font-style="italic"' if italic else ""
    out.append(f'<text x="{x:.1f}" y="{y:.1f}" font-family="{e(fam)}" font-size="{size}" font-weight="{weight}" '
               f'fill="{color or C["text"]}" text-anchor="{anchor}"{st}>{rich(s)}</text>')


def lines(x, y, rows, size=12.5, color=None, lh=None, **kw):
    lh = lh or size * 1.42
    for k, r in enumerate(rows):
        text(x, y + k * lh, r, size, color, **kw)


def badge(x, y, n, color=C["lead"]):
    out.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="11" fill="{color}" stroke="{C["bg"]}" stroke-width="2"/>')
    text(x, y + 4.5, str(n), 12.5, "#0a1216", 800, "middle")


def arrow(pts, color=C["wire"], sw=1.6, dash=None, head=True, both=False):
    d = " ".join(f"{'M' if i == 0 else 'L'}{x:.1f},{y:.1f}" for i, (x, y) in enumerate(pts))
    da = f' stroke-dasharray="{dash}"' if dash else ""
    key = color.lstrip("#")
    mk = (f' marker-end="url(#ah-{key})"' if head else "") + (f' marker-start="url(#as-{key})"' if both else "")
    out.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{sw}"{da}{mk}/>')


def chip(x, y, s, color, size=10.5):
    w = len(s) * size * 0.58 + 16
    rect(x - w / 2, y - size - 4, w, size + 10, color, C["bg"], r=8, sw=1)
    text(x, y, s, size, color, 700, "middle")


def card(x, y, w, h, title, rows, k, tsize=14, rsize=12, badge_n=None):
    rect(x, y, w, h, C[k], C[k + "bg"], r=10)
    text(x + 14, y + 24, title, tsize, C[k], 700)
    lines(x + 14, y + 24 + rsize * 1.7, rows, rsize, C["muted"], lh=rsize * 1.38)
    if badge_n:
        badge(x + w - 4, y + 4, badge_n)


def plug(x, y, w, h, bundle, rows, k="x"):
    rect(x, y, w, h, C[k], C[k + "bg"], r=8, sw=1.1)
    text(x + 11, y + 19, bundle, 12.5, C[k], 700, mono=True)
    lines(x + 11, y + 37, rows, 11.5, C["muted"], lh=15)


# ------------------------------------------------------------------ title
text(28, 46, "Yano X — Master Architecture", 30, C["text"], 800)
text(28, 72, "How a Yano X app ledger runs end to end: the Yano host (bloxbean/yano), the Yano X JVM runtime plugins "
     "loaded into it, and the libraries, tools and distribution built in this repository.", 14.5, C["muted"])
text(W - 28, 46, "C4 container view · one member node shown", 13, C["dim"], 600, "end")
text(W - 28, 68, "dependency direction: yano-x → yano", 13, C["x"], 600, "end", mono=True)

# ------------------------------------------------------------------ actors
AY, AH = 96, 84
NX, NY, NW = 24, 222, 1356
actors = [
    ("Applications", ["`yano-x-client` · Spring Boot starter · REST / SSE",
                      "sign messages · query state · verify proofs"]),
    ("Business actors", ["role-signed commands (Ed25519 actor keys)",
                         "organizations · roles · approval policies"]),
    ("Developers & operators", ["`appchain` CLI · App-Chain Studio · `yano-x-deploy`",
                                "showcase.sh · console `/ui` · plugin bundles"]),
    ("Auditors & verifiers", ["finality certificates · MPF / JMT proofs",
                              "evidence bundles · Cardano anchor datum"]),
]
aw = (NW - 3 * 20) / 4
for i, (t, rows) in enumerate(actors):
    ax = NX + i * (aw + 20)
    card(ax, AY, aw, AH, t, rows, "client", rsize=12.5)
    arrow([(ax + aw / 2, AY + AH + 2), (ax + aw / 2, NY - 3)], C["client"], both=(i == 3))
chip(NX + aw / 2 + 92, 206, "1 · submit signed message", C["client"])
chip(NX + 3 * (aw + 20) + aw / 2 + 74, 206, "7 · query & verify", C["client"])
chip(NX + 2 * (aw + 20) + aw / 2 + 74, 206, "configure · operate", C["client"])
chip(NX + 1 * (aw + 20) + aw / 2 + 70, 206, "authorize by role", C["client"])

# ------------------------------------------------------------------ node container geometry
IX, IW = NX + 16, NW - 32
yA = NY + 40                       # API strip
yP, hP = yA + 50 + 26, 92          # pipeline
yC, hC = yP + hP + 26, 128         # core services
yS, hS = yC + hC + 16, 58          # storage
yK, hK = yS + hS + 30, 56          # plugin catalog
yX = yK + hK + 16                  # plugin region
ph, pg = 58, 7
hX = 40 + 4 * ph + 3 * pg + 14
NH = yX + hX + 14 - NY
rect(NX, NY, NW, NH, C["host"], C["panel"], r=16, sw=1.6)
text(NX + 18, NY + 26, "Yano node — `yano.jar` (Quarkus, JVM) · runs on every app-ledger member", 16, C["host"], 800)
text(NX + NW - 18, NY + 25, "upstream host: bloxbean/yano", 12.5, C["dim"], 600, "end")

# API strip
api = [
    ("App-chain REST  `/api/v1/app-chain/chains/{id}/…`",
     "`messages` · `query/{path}` · `state/proof` · `evidence` · `proof-subjects` · `stream` (SSE)"),
    ("Plugin domain APIs", "`/api/v1/plugins/{bundleId}/{path}`"),
    ("Operations", "console `/ui` · `/q/health` · `/q/metrics` · admin"),
]
wsA = [IW * 0.52, IW * 0.235, IW * 0.245 - 20]
x = IX
for (t, s), w in zip(api, wsA):
    rect(x, yA, w, 50, C["host"], C["hostbg"], r=8, sw=1)
    text(x + 12, yA + 20, t, 13, C["host"], 700)
    text(x + 12, yA + 39, s, 11.8, C["muted"])
    x += w + 10
badge(IX + 4, yA + 4, 7)

# pipeline
text(IX + (IW - 4 * 26) / 5 + 26, yP - 8, "CONSENSUS PIPELINE — identical on every member", 11.5, C["lead"], 700)
pipe = [
    ("Admit", ["Ed25519 envelope check · size / TTL", "`validateForBlock` at next height", "429 when the pool is full"], 1),
    ("Message pool", ["`AppMsgPool` · per-chain topics", "reserved `~consensus` `~l1` `~fx`", "gossip to peers (N2N 100)"], 2),
    ("Propose", ["leader(height, view)", "`fixed` | `rotating` sequencer", "ordered app block v3"], 3),
    ("Execute & vote", ["every member re-executes", "byte-identical `stateRoot`", "PREPARE → PreparedQC → COMMIT"], 4),
    ("Finalize & commit", ["threshold `FinalityCert` → APP_FINAL", "hash-linked via `prevHash`", "one atomic RocksDB WriteBatch"], 5),
]
gap = 26
pw = (IW - 4 * gap) / 5
PX = [IX + i * (pw + gap) for i in range(5)]
for i, (t, rows, n) in enumerate(pipe):
    rect(PX[i], yP, pw, hP, C["lead"], C["leadbg"], r=10)
    text(PX[i] + 12, yP + 23, t, 14, C["lead"], 700)
    lines(PX[i] + 12, yP + 44, rows, 11.8, C["muted"], lh=16)
    badge(PX[i] + pw - 4, yP + 4, n)
    if i < 4:
        arrow([(PX[i] + pw + 2, yP + hP / 2), (PX[i + 1] - 3, yP + hP / 2)], C["lead"])
arrow([(IX + pw / 2, yA + 50), (IX + pw / 2, yP - 3)], C["client"])

# core services
cw = (IW - 3 * 18) / 4
CX = [IX + i * (cw + 18) for i in range(4)]
core = [
    ("State machine SPI", ["`AppStateMachine`: `validateForBlock` · `apply`", "`query` · `onEffectResult` · transition kernel",
                           "deterministic: no clock, I/O or randomness", "host built-in: `ordered-log`; others = plugins"],
     "host", None),
    ("Authenticated state & proofs", ["`mpf-blake2b256-v1` | `jmt-blake2b256-v1`",
                                      "identity: profile · fingerprint · genesis-id", "inclusion / exclusion proofs · Proof Lab",
                                      "evidence bundles · block message roots"], "host", 6),
    ("Effect runtime", ["intent recorded in consensus outbox", "finality gate: APP_FINAL | L1_ANCHORED",
                        "node-local executor runs it (at-least-once)", "result re-enters as `~fx/result` (exactly once)"],
     "fx", 8),
    ("Anchor & L1 view", ["anchors the certified state root", "metadata label 7014, or script UTxO",
                          "L1 observers publish `~l1/<id>` topics", "followers re-derive, fail closed"], "ada", 10),
]
for i, (t, rows, k, n) in enumerate(core):
    card(CX[i], yC, cw, hC, t, rows, k, tsize=14, rsize=11.8, badge_n=n)

# pipeline -> SPI (execute) ; finalize -> authenticated state
ex_x = PX[3] + pw * 0.3
arrow([(ex_x, yP + hP), (ex_x, yP + hP + 8), (CX[0] + cw * 0.6, yP + hP + 8), (CX[0] + cw * 0.6, yC - 3)], C["lead"])
fin_x = PX[4] + pw * 0.4
arrow([(fin_x, yP + hP), (fin_x, yP + hP + 18), (CX[1] + cw * 0.7, yP + hP + 18), (CX[1] + cw * 0.7, yC - 3)],
      C["lead"])

# storage row (under SPI, state, effects) + node core (under anchor)
stores = [("chainstate/", "Cardano L1 state · authoritative"),
          ("appchain-chainstate/", "per chain: blocks · trie · fx · authoritative"),
          ("appchain-indexers/", "derived read models · rebuildable")]
for i, (t, s) in enumerate(stores):
    rect(CX[i], yS, cw, hS, C["store"], C["storebg"], r=8, sw=1, dash="5 4" if i == 2 else None)
    text(CX[i] + 12, yS + 23, t, 12.5, C["store"], 700, mono=True)
    text(CX[i] + 12, yS + 42, s, 11.5, C["dim"])
text(IX + 2, yS + hS + 17, "Per-node RocksDB stores · app-ledger state lives beside, never inside, L1 chainstate · "
     "derived indexes never advance past authoritative state", 11, C["dim"], italic=True)
arrow([(CX[1] + cw * 0.3, yC + hC), (CX[1] + cw * 0.3, yS - 3)], C["store"])
rect(CX[3], yS, cw, hS, C["ada"], C["adabg"], r=8, sw=1)
text(CX[3] + 12, yS + 23, "Cardano node core", 13, C["ada"], 700)
text(CX[3] + 12, yS + 42, "chain sync · ledger state · N2N :13337", 11.5, C["muted"])
arrow([(CX[3] + cw * 0.5, yS - 1), (CX[3] + cw * 0.5, yC + hC + 3)], C["ada"], dash="4 3")

# plugin catalog band
rect(IX, yK, IW, hK, C["x"], C["panel2"], r=10, sw=1.3)
text(IX + 14, yK + 22, "Plugin catalog — `PluginProviderRegistry`", 14, C["x"], 700)
text(IX + 14, yK + 43, "`plugins/` (`yano.plugins.directory`) → schema-v1 manifest → yanoApi check → isolated validation "
     "→ one parent-first loader → dependency order → catalog fingerprint", 11.8, C["muted"])
tiers = [("CONSENSUS", C["x"]), ("PRIVILEGED_LOCAL", C["fx"]), ("AUXILIARY_LOCAL", C["store"])]
tx = IX + 400
text(tx, yK + 22, "trust tiers", 11, C["dim"], 600)
tx += 70
for t, col in tiers:
    w = len(t) * 7.2 + 16
    rect(tx, yK + 8, w, 20, col, C["bg"], r=10, sw=1)
    text(tx + w / 2, yK + 22, t, 10.5, col, 700, "middle", mono=True)
    tx += w + 8
# effect runtime resolves executors through the catalog (route in the gap left of the anchor column)
gx = CX[3] - 9
arrow([(CX[2] + cw * 0.75, yC + hC), (CX[2] + cw * 0.75, yC + hC + 8), (gx, yC + hC + 8), (gx, yX + 50 + ph / 2),
       (CX[3] - 2, yX + 50 + ph / 2)], C["fx"], dash="5 4")

# Yano X plugins region
rect(IX, yX, IW, hX, C["x"], C["xbg"], r=12, sw=1.6, op=0.6)
text(IX + 14, yX + 24, "Yano X runtime plugin bundles — 15 dependency-complete JARs in `plugins/`", 15, C["x"], 800)
text(IX + IW - 14, yX + 23, "this repository · JVM-only · chain selects one with `yano.app-chain.chains[i].state-machine`",
     12, C["x"], 600, "end")
gy = yX + 50
RY = [gy + i * (ph + pg) for i in range(4)]
colw = (3 * cw + 2 * 18 - 2 * 10) / 3
cons = [
    ("stdlib", ["kv-registry · approvals · balances", "doc-trail · authenticated-map · epoch-*"]),
    ("composite", ["declarative-composite: YAML → IR", "governed profiles · admission rules"]),
    ("role-workflow", ["domain-actors · role-approvals", "governed-role-approvals"]),
    ("evidence-registry", ["evidence-registry state machine", "topic `evidence.command.v1`"]),
    ("evidence-profile", ["`composite` presets: evidence-v1-gated", "evidence-v1 · role-evidence"]),
    ("cardano-history", ["provable L1 params · stake", "governance, from epoch observers"]),
    ("eutxo", ["eutxo-ledger: Conway-shaped eUTxO", "Plutus V3 via Scalus · domain API"]),
    ("authenticated-map-validators", ["map value validator `gs1-gtin-v1`", "pinned in chain genesis"]),
    ("eutxo-indexer-jdbc", ["local read model → `appchain-indexers/`", "replayable · health · metrics"]),
    ("showcase", ["showcase-composite · document-review", "demo-only machines + outbox"]),
]
text(IX + 14, gy - 8, "app-state-machine · authenticated-map-validator · l1-epoch-observer · local-read-model",
     11, C["x"], 600, mono=True)
for i, (b, rows) in enumerate(cons):
    r, c = divmod(i, 3)
    if i == 9:
        r, c = 3, 0
    k = "store" if b == "eutxo-indexer-jdbc" else "x"
    plug(IX + c * (colw + 10), RY[r], colw, ph, b, rows, k)
# 4th row: showcase sits at col 0, the extra slots carry notes
nx0 = IX + (colw + 10)
rect(nx0, RY[3], 2 * colw + 10, ph, C["x"], C["bg"], r=8, sw=1, dash="4 4", op=0.6)
lines(nx0 + 12, RY[3] + 23, ["Each bundle: one schema-v1 manifest · `yanoApi` range · dependencies · "
                             "`{kind, name, provider}` contributions",
                             "Composites: many components under one state root · bindings wire stock "
                             "machines without Java"], 11.3, C["muted"], lh=17)
# connectors column (col 3)
ex = CX[3]
text(ex, gy - 8, "effect-executor · l1-observer · sink", 11, C["fx"], 600, mono=True)
fxs = [("kafka", ["`kafka.publish` executor", "Kafka finalized-block sink"], "fx"),
       ("objectstore-s3 · ipfs", ["`object.put` create-only, SHA-256", "`ipfs.pin` via Kubo RPC"], "fx"),
       ("effects-cardano", ["`cardano.payment` L1 payment tx", "effect id in tx metadata"], "fx"),
       ("eutxo-bridge-cardano", ["`l1.settlement` executor · vault", "deposit / withdrawal observers"], "ada")]
for i, (b, rows, k) in enumerate(fxs):
    plug(ex, RY[i], cw, ph, b, rows, k)
badge(ex + cw - 4, RY[0] + 4, 9)

# ------------------------------------------------------------------ right column
RX, RW = 1410, W - 24 - 1410
# peers
hPeer = yP + hP - NY
rect(RX, NY, RW, hPeer, C["host"], C["hostbg"], r=14, sw=1.4)
text(RX + 16, NY + 27, "Peer members (n nodes)", 15, C["host"], 800)
lines(RX + 16, NY + 51, ["same `yano.jar`, same plugin bundles, same genesis", "N2N 100 · app-message gossip",
                         "consensus votes · QCs · FinalityCert", "N2N 103 · app-chain sync / catch-up",
                         "threshold t of n, BFT quorum (2t − n > f)", "each member re-verifies every block"],
      12, C["muted"], lh=18)
arrow([(PX[4] + pw + 2, yP + hP / 2), (RX - 3, yP + hP / 2)], C["host"], both=True)

# cardano
yD = yP + hP + 12
hD = yK + hK - yD
rect(RX, yD, RW, hD, C["ada"], C["adabg"], r=14, sw=1.4)
text(RX + 16, yD + 26, "Cardano L1", 15, C["ada"], 800)
text(RX + RW - 16, yD + 26, "mainnet · preprod · devnet", 11, C["dim"], 600, "end")
sections = [
    ("Anchors", ["metadata tx, or script UTxO + state-thread", "NFT checked by `AnchorValidator`"], yC + 46),
    ("Observations", ["deposits · metadata labels · epoch", "params, stake, governance"], yC + 102),
    ("Chain sync & submission", ["blocks into L1 `chainstate`", "anchor · payment · settlement txs"], yS + 18),
    ("On-chain verification", ["`proof-onchain` MPF verifiers · eUTxO", "bridge validators (Julc → Plutus V3)"],
     yS + 84),
]
for t, rows, sy in sections:
    text(RX + 16, sy, t, 12.5, C["ada"], 700)
    lines(RX + 16, sy + 17, rows, 11.5, C["muted"], lh=15)
arrow([(CX[3] + cw + 2, yC + 42), (RX - 3, yC + 42)], C["ada"])
arrow([(RX - 2, yC + 98), (CX[3] + cw + 4, yC + 98)], C["ada"], dash="4 3")
arrow([(CX[3] + cw + 2, yS + hS / 2), (RX - 3, yS + hS / 2)], C["ada"], dash="4 3", both=True)

# external systems
yE = yX
hE = NY + NH - yE
rect(RX, yE, RW, hE, C["ext"], C["extbg"], r=14, sw=1.4)
text(RX + 16, yE + 27, "External systems", 15, C["ext"], 800)
lines(RX + 16, yE + 52, ["Kafka topics", "S3-compatible object storage", "IPFS / Kubo pinning",
                         "webhooks, ERP (host `webhook` executor)", "external claim / report executors",
                         "Cardano wallet for payments, settlement"], 12, C["muted"], lh=18)
text(RX + 16, yE + hE - 18, "only finalized effects · receivers must be idempotent", 11.5, C["ext"], 600, italic=True)
for i in range(3):
    arrow([(ex + cw + 2, RY[i] + ph / 2), (RX - 3, RY[i] + ph / 2)], C["fx"], dash="6 4")

# ------------------------------------------------------------------ bottom band
yB, hB = NY + NH + 22, 236
H = yB + hB + 46
out.insert(0, f'<rect width="{W}" height="{H}" fill="{C["bg"]}"/>')
lw = 640
rect(NX, yB, lw, hB, C["client"], C["panel"], r=14, sw=1.3)
text(NX + 16, yB + 28, "Libraries, SDKs & tools — not runtime plugins", 15, C["client"], 800)
groups = [
    ("SDK", ["`yano-x-client` · REST, SSE, `ProofVerifier`", "`proof-contracts` · `integration-contracts`",
             "`effects-testkit` · `spring-boot-starter`"]),
    ("Contracts", ["stdlib · composite · role-workflow", "eutxo client / testkit · evidence client",
                   "canonical CBOR · CDDL · golden vectors"]),
    ("Tools", ["`appchain` CLI: init · recipes · bindings · doctor", "`yano-x-deploy`: OpenTofu + Ansible",
               "App-Chain Studio · `yano-cardano-history`"]),
    ("On-chain (Julc → Plutus V3)", ["`proof-onchain` MPF verifiers", "eUTxO `bridge-onchain` validators",
                                     "cardano-history predicates"]),
]
for i, (t, rows) in enumerate(groups):
    r, c = divmod(i, 2)
    gx2, gy2 = NX + 16 + c * 312, yB + 56 + r * 88
    text(gx2, gy2, t, 12.5, C["client"], 700)
    lines(gx2, gy2 + 18, rows, 11.5, C["muted"], lh=16)

bx0 = NX + lw + 20
bw = NX + NW - bx0
rect(bx0, yB, bw, hB, C["x"], C["panel"], r=14, sw=1.3)
text(bx0 + 16, yB + 28, "Build & ship", 15, C["x"], 800)
steps = [("44 Gradle modules", ["`config/artifacts-v1.json`", "`verifyArtifactInventory`"]),
         ("15 plugin bundles", ["Shadow JAR · one manifest", "no host API or host classes"]),
         ("yano-x-jvm ZIP", ["Yano JVM ZIP + `plugins/`", "`tools/` · `studio/` · showcase"])]
sw_ = (bw - 32 - 2 * 26) / 3
for i, (t, rows) in enumerate(steps):
    sx = bx0 + 16 + i * (sw_ + 26)
    rect(sx, yB + 44, sw_, 74, C["x"], C["xbg"], r=8, sw=1)
    text(sx + 10, yB + 64, t, 12.5, C["x"], 700)
    lines(sx + 10, yB + 82, rows, 11, C["muted"], lh=15)
    if i < 2:
        arrow([(sx + sw_ + 2, yB + 81), (sx + sw_ + 23, yB + 81)], C["x"])
lines(bx0 + 16, yB + 144, [
    "Consumes one exact published Yano version and its matching JVM ZIP — no source, composite or sibling-task link.",
    "Libraries + `yano-x-bom` → Maven Central · bundles, CLIs, Studio and ZIPs → GitHub release.",
    "JVM-only: `verifyJvmOnlyBuild` rejects native-image tasks · Java 25 · MIT.",
    "Docs: `docs/` is the source of truth · `www/` publishes yano-x.io."], 11.8, C["muted"], lh=19)

rect(RX, yB, RW, hB, C["lead"], C["panel"], r=14, sw=1.3)
text(RX + 16, yB + 28, "End-to-end flow", 15, C["lead"], 800)
flow = ["Client signs and submits a message",
        "Admitted, pooled, gossiped to peers",
        "Leader proposes an ordered block",
        "Every member re-executes the plugin machine",
        "Votes reach threshold → FinalityCert",
        "Block and state root committed",
        "Clients query state and verify proofs",
        "Finalized effects run on executors",
        "Results return once as `~fx/result`",
        "Certified root anchored on Cardano"]
for i, s in enumerate(flow):
    fy = yB + 54 + i * 17.6
    text(RX + 22, fy, f"{i + 1}", 11.5, C["lead"], 800, "middle")
    text(RX + 36, fy, s, 11.5, C["muted"])

# ------------------------------------------------------------------ legend
yL = H - 20
items = [("Clients & actors", "client"), ("Yano host (upstream)", "host"), ("Consensus pipeline", "lead"),
         ("Yano X plugin bundles", "x"), ("Effects & connectors", "fx"), ("Cardano", "ada"),
         ("External systems", "ext"), ("Storage", "store")]
lx = 28
for t, k in items:
    rect(lx, yL - 11, 14, 14, C[k], C[k + "bg"], r=3, sw=1.2)
    text(lx + 20, yL + 1, t, 12, C["muted"])
    lx += 20 + len(t) * 6.6 + 22
arrow([(lx + 6, yL - 4), (lx + 46, yL - 4)], C["wire"])
text(lx + 52, yL + 1, "call / data", 12, C["muted"])
lx += 140
arrow([(lx, yL - 4), (lx + 40, yL - 4)], C["wire"], dash="6 4")
text(lx + 46, yL + 1, "asynchronous · after finality · resolved through the catalog", 12, C["muted"])

# ------------------------------------------------------------------ assemble
cols = {C[k] for k in ("wire", "client", "host", "lead", "x", "fx", "ext", "ada", "store")}
defs = "".join(
    f'<marker id="ah-{c.lstrip("#")}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" '
    f'orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="{c}"/></marker>'
    f'<marker id="as-{c.lstrip("#")}" viewBox="0 0 10 10" refX="1" refY="5" markerWidth="7" markerHeight="7" '
    f'orient="auto"><path d="M10,0 L0,5 L10,10 z" fill="{c}"/></marker>' for c in sorted(cols))
body = "\n".join(s for s in out if s)
svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" '
       f'role="img" aria-labelledby="t d">\n<title id="t">Yano X master architecture</title>\n'
       f'<desc id="d">Clients submit signed messages to a Yano node. The Yano host admits, pools, proposes, '
       f're-executes and finalizes app blocks with peer members, commits authenticated state, runs effects and '
       f'anchors roots on Cardano. Yano X runtime plugin bundles loaded through the plugin catalog provide the state '
       f'machines, composites, connectors, bridge and read models. Libraries, tools and the JVM distribution are '
       f'built in this repository.</desc>\n<defs>{defs}</defs>\n{body}\n</svg>\n')
open(sys.argv[1], "w").write(svg)
print(f"wrote {sys.argv[1]} ({W}x{H})")
