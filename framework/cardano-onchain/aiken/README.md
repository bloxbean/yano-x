# App-chain script anchor — Aiken implementation (opt-in)

The source of the supported clean-baseline anchor validators
(ADR app-layer/031; ABI: `framework/cardano-api/src/main/cddl/appchain/anchor-v1.cddl`).
The checked artifacts are executed by the `framework:cardano-onchain` conformance suite on
`julc-vm-java`.

Both the consumed and continuing datum must have the exact
`Constr(0, [11 fields])` v1 shape. The validators bind chain genesis, application ID,
commitment profile and format fingerprint in addition to a `chain-id` of 1..128 bytes,
a non-negative height, 32-byte block hash and state
root, 1..32 strictly sorted unique 32-byte member keys, and a threshold in the
member range. Plutus exposes `chain-id` only as bytes, so the on-chain scripts
cannot prove that it is UTF-8 or reject an embedded NUL. The public/off-chain
`AnchorDatumV1` codec enforces canonical UTF-8 and rejects NUL before encoding
or after decoding.

## Anchor v2 (opt-in)

`validators/anchor_v2.ak` is the ADR-059 anchor v2 validator
(ABI: `framework/cardano-api/src/main/cddl/appchain/anchor-v2.cddl`). Its datum is
the v1 datum with version 2 and an appended `updated_at_ms`. It allows a pure
heartbeat at the same height and binds `updated_at_ms` to the validity upper bound
within a `max_window_ms` window (second parameter). It reuses the v1
`anchor_thread` policy. A chain selects it with `anchor-profile.version: 2`; the
runtime then loads `META-INF/plutus/AnchorValidatorV2.plutus.json`
(`artifacts/anchor-validator-v2.plutus.json`). Adding it left every v1 blueprint
entry and hash unchanged.

## Using it

Point the anchor config at the checked-in artifacts (script hash and address
always derive from the configured artifact, never from source):

```yaml
yano:
  app-chain:
    anchor:
      mode: script
      script:
        validator: file:/path/to/artifacts/anchor-validator.plutus.json
        thread-policy: file:/path/to/artifacts/thread-policy.plutus.json
```

## Building

Requires the [Aiken](https://aiken-lang.org) toolchain (compiled with
v1.1.21, stdlib v2.2.0). The checked-in artifacts are what ships — rebuild
only when the source changes, and re-run the conformance suite:

```bash
aiken build                       # regenerates plutus.json
# re-extract artifacts/ (double-CBOR cborHex envelopes)
# then, from the Yano-X repository root: ./gradlew :framework:cardano-onchain:test
```

`artifacts/*.plutus.json` wrap the blueprint `compiledCode` (single-CBOR flat
UPLC) in one more CBOR byte-string layer, matching the Cardano text-envelope
`cborHex` convention the runtime artifact loader expects.

## Sizes (informational)

| script | checked size |
|---|---:|
| anchor validator (spend) | 1929 B |
| anchor validator v2 (spend) | 2285 B |
| thread policy (mint) | 362 B |

Values are the checked artifact JSON `sizeBytes` fields. They are identity and
deployment diagnostics, not execution-budget measurements; the shared
conformance suite separately checks both implementations against Cardano's
per-transaction CPU and memory limits.
