# Cardano History CLI

`yano-cardano-history`, a dependency-light command-line tool over the
[Cardano History client](../client/README.md): status and epoch listing,
typed queries, proof-bundle export, offline verification, and rendering of
preset configuration. Usage examples, exit codes, and trust labels are in
[Cardano History](../../../docs/appchain/CARDANO_HISTORY.md#java-client-and-cli).

## Artifact

`org.yanoproject.x:yano-x-cardano-history-cli` (tool). Not released to Maven;
the Yano X JVM distribution ships it under `tools/`.

## Key contents

`org.yanoproject.x.history.cli.CardanoHistoryCli` is the only class. Commands:
`status`, `epochs`, `query params|stake|drep|proposal`,
`proof params|stake`, `verify --bundle FILE [--trusted-root FILE]`, and
`config render --preset <preset>`. Every network command needs explicit
`--url` and `--chain` options. Exit codes: `0` success, `2` usage, `3`
unavailable or incomplete data, `4` invalid, `5` trie proof valid but the
Cardano anchor not independently checked.

## Dependencies

`:products:cardano-history:client` and Jackson only.

## Build and run

```bash
./gradlew :products:cardano-history:cli:test
./gradlew :products:cardano-history:cli:installDist   # build/install/yano-cardano-history
./gradlew :products:cardano-history:cli:run --args='--help'
```

See [Build and test Yano X](../../../docs/BUILD_AND_TEST.md) for the
required Yano version arguments.
