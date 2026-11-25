# Application-ledger anchor on-chain artifacts

The released on-chain identities of the app-chain script anchor: the spending
validator that advances the anchor UTxO and the one-shot thread-NFT minting
policy. `framework/cardano` loads them as its built-in script-anchor artifacts.

## Artifact

`org.yanoproject.x:yano-x-appchain-anchor-onchain` (`onchain-artifact`). It replaces
Yano's former `org.yanoproject:yano-appchain-anchor-onchain`; see the
[ADR-064 migration notes](../../docs/appchain/MIGRATION-ADR-064.md).

## Key contents

- `src/main/resources/META-INF/plutus/AnchorValidator.plutus.json` and
  `AnchorThreadPolicy.plutus.json`: the checked, Aiken-compiled artifacts that ship.
  The ordinary build and publication never recompile or regenerate them.
- `src/main/resources/META-INF/plutus/AnchorValidatorV2.plutus.json`: the opt-in
  anchor v2 validator (ADR-059 framework addition 5; ABI
  `framework/cardano-api/src/main/cddl/appchain/anchor-v2.cddl`), selected by
  `anchor-profile.version: 2`. It uses the same thread policy. Adding it did not
  change the v1 artifacts or their hashes.
- [`aiken/`](aiken/README.md): the Aiken source (`validators/anchor.ak`), blueprint
  and text-envelope artifacts, with rebuild instructions. The source JAR includes it.
- `org.yanoproject.x.framework.cardano.onchain.AnchorValidator`,
  `AnchorValidatorV2` and `AnchorThreadPolicy`: the interchangeable Julc (Java)
  implementation of the same ABIs (`anchor-v1.cddl`, `anchor-v2.cddl`). Julc
  dependencies are `compileOnly`; the runtime uses the checked artifacts, not
  these sources.

The validator requires exactly one continuing output with the thread NFT and an
inline v1 datum, a strictly increasing height, an unchanged chain id, and
signatures from at least the input datum's threshold of member keys.

The v2 validator adds two rules. A pure heartbeat may keep the height when the
block hash, state root, members and threshold are unchanged and `updatedAtMs`
strictly increases; every other advance must raise the height. `updatedAtMs`
must equal the finite validity upper bound, and the validity window may be at
most the `maxWindowMs` parameter wide.

## Dependencies / boundaries

No runtime dependencies. `framework/cardano` pulls it in as `runtimeOnly`; the
standalone root and `framework/core` must not.

## Build & test

```bash
./gradlew :framework:cardano-onchain:test
./gradlew :framework:cardano-onchain:test -Djulc.source-check=true
```

The default suite executes the shipped artifacts on `julc-vm-java` against the
conformance vectors. `-Djulc.source-check=true` additionally compiles the Java
sources and runs the same vectors, a drift check for when artifacts are
regenerated. `frameworkCheck` includes `test`. Add the Yano inputs from
[Build and test](../../docs/BUILD_AND_TEST.md) when the pinned Yano version is
local or staged.
