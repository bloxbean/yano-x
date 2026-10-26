# App-chain setup CLI

`yano.sh appchain` from the Yano X JVM distribution: create an application, collect member cards,
seal it, join it, run it and grow it (ADR-056). Start with `yano.sh appchain try`.

Verbs the setup ceremony does not cover (`recipes`, `capabilities`, `bindings`, `state`, `role`,
`plugin`, `metadata`, `config`, `authenticated-map`, `eutxo`) are passed to `tooling/devtools`.
