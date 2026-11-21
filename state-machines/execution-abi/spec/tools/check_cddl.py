"""Check the golden vectors against the ABI CDDL with an independent CDDL tool.

Uses cddl-rs (https://github.com/anweiss/cddl), which shares no code with the Java, JavaScript or
Rust codecs. Install it anywhere and pass its path:

    cargo install cddl --version 0.10.7 --locked --root /tmp/cddl-tool
    python3 spec/tools/check_cddl.py /tmp/cddl-tool/bin/cddl

What is checked:
- every valid vector matches its CDDL type;
- every invalid vector whose error is `schema` or `unsupported-type` does not match its type;
- a few controls (single-byte mutations of valid vectors) do not match, so a tool that accepts
  everything cannot pass.

Invalid vectors with a CBOR encoding error (non-shortest heads, indefinite lengths, unsorted or
duplicate keys, trailing bytes) are reported but not judged. CDDL describes the data model, not the
encoding, and canonical encoding is enforced by the decoders.

cddl-rs 0.10.7 does not anchor `.regexp` patterns. RFC 8610 section 3.8.3 uses XSD regular
expressions, which are anchored at both ends, so this script anchors every pattern before giving the
CDDL to the tool. The spec file itself keeps XSD semantics.
"""
import json
import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = ROOT / "spec" / "guest-abi-v1.cddl"
VECTORS = ROOT / "spec" / "vectors" / "abi-v1.json"
ENCODING_ERRORS = {"non-canonical-int", "indefinite-length", "trailing-bytes", "map-key-order", "duplicate-key"}

# (name, type, base vector, byte offset, new byte): each must not match.
CONTROLS = [
    ("abi version 2", "apply-message-input", "apply-message-input", 2, 0x02),
    ("receipt kind 6", "receipt", "receipt", 7, 0x06),
    ("transition status 2", "transition-outcome", "transition-ok", 2, 0x02),
    ("query status 2", "query-outcome", "query-payload", 2, 0x02),
    ("descriptor version 2", "execution-descriptor", "execution-descriptor", 2, 0x02),
    ("effect type as bytes", "effect-intent", "effect-intent-minimal", 2, 0x4F),
]


def anchored(cddl: str) -> str:
    return re.sub(r'\.regexp "([^"]*)"', lambda m: f'.regexp "^(?:{m.group(1)})$"', cddl)


def main() -> int:
    tool = sys.argv[1]
    cddl = anchored(SPEC.read_text())
    vectors = json.loads(VECTORS.read_text())
    valid = {v["name"]: v for v in vectors["valid"]}
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        work = pathlib.Path(tmp)

        def matches(vtype: str, data: bytes) -> bool:
            schema = work / f"{vtype}.cddl"
            if not schema.exists():
                # cddl-rs validates against the first rule of the document.
                schema.write_text(f"root = {vtype}\n\n{cddl}")
            item = work / "item.cbor"
            item.write_bytes(data)
            run = subprocess.run([tool, "validate", "--cddl", str(schema), "--cbor", str(item)],
                                 capture_output=True, text=True)
            return run.returncode == 0 and "is successful" in run.stdout + run.stderr

        def report(label: str, matched: bool, expected) -> None:
            nonlocal failures
            verdict = "-" if expected is None else ("ok" if matched == expected else "FAIL")
            failures += verdict == "FAIL"
            print(f"{verdict:4} {label:58} {'matches' if matched else 'does not match'}")

        for v in vectors["valid"]:
            report(f"valid {v['name']}", matches(v["type"], bytes.fromhex(v["cbor"])), True)
        for v in vectors["invalid"]:
            expected = None if v["error"] in ENCODING_ERRORS else False
            report(f"invalid {v['name']} ({v['error']})", matches(v["type"], bytes.fromhex(v["cbor"])), expected)
        for name, vtype, base, offset, value in CONTROLS:
            data = bytearray(bytes.fromhex(valid[base]["cbor"]))
            data[offset] = value
            report(f"control {name}", matches(vtype, bytes(data)), False)
    print("RESULT", "FAIL" if failures else "PASS")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
