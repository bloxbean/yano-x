# Why these tests live in Yano's `org.yanoproject.runtime.plugins` package

These are the application-ledger facade and catalog tests that moved from Yano with ADR-064. ADR-064 I4
requires them to pass unchanged, and they exercise package-private Yano runtime internals
(`PluginKinds`, `PluginSpiFacades`, `CatalogPluginProviderRegistry`, ...) through the real production
bindings. They therefore stay in Yano's package (a test-scope split package against the `yano-runtime`
jar) instead of being rewritten against a narrower public surface.

This is deliberate test-scope coupling to Yano internals. When Yano renames or moves any internal these
tests use, update them here in the same Yano version bump. Tests that use no Yano internals belong in
`org.yanoproject.x.framework.cardano` instead.
