# EUTxO ZK devnet end-to-end test

`EutxoZkRollupDevnetE2ETest` and its `EutxoZkDevnetTestProfile` ran in
`:fixtures:eutxo-e2e` (`./gradlew :fixtures:eutxo-e2e:e2eTest`) against a devnet
node started from the packaged distribution, with `yano-x-eutxo-zk-runtime-bundle`
installed from `optional-plugins/` in place of the standard eUTxO ledger bundle.

They moved here when the ZK modules were excluded from the build. To run them
again, build the ZK modules, move both files back to
`fixtures/eutxo-e2e/src/test/java/org/yanoproject/app/e2e/`, and restore the
fixture's test dependencies on `:ledgers:eutxo-zk:onchain`,
`:ledgers:eutxo-zk:zeroj`, `libs.zeroj.circuit.lib` and `libs.zeroj.onchain.julc`,
plus the `zeroj.allowInsecureTrustedSetup` system property on `e2eTest`.
