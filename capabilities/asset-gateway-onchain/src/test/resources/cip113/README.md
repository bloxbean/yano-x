# Pinned CIP-113 blueprint (test input)

`plutus.json` is the compiled blueprint of
[cardano-foundation/cip113-programmable-tokens](https://github.com/cardano-foundation/cip113-programmable-tokens)
at commit `6b75ba32` (package version 0.0.1, Aiken v1.1.23, Plutus V3), copied unchanged. It is the exact
blueprint that cardano-client-lib 0.8.0-pre8 vendors in its integration tests.

- sha256: `b6c8cb096a15e02f1b9c719fb8c617b624c1aa7719f2258d45faa3e8f144e7b9` (checked by `Cip113Blueprint`)
- license: Apache-2.0, as declared in the blueprint's preamble and the upstream repository

ADR-061 §10 P2 composes the custody scripts with these scripts (PLB, the dispatcher and the transfer delegate),
applied with a devnet fixture deployment's parameters, and evaluates every script of each vault transaction.
