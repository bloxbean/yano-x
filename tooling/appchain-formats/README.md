# App-chain setup formats

The three files of the app-chain setup ceremony (ADR-056) and their identities:

- `<app>.draft.yaml`: the coordinator's editable definition, with an invite code.
- `<operator>.member`: an operator's public key and advertised addresses, signed by that key.
- `<app>.chain`: the sealed genesis section plus an unhashed peers section.

This library has no node dependency. The `yano.sh appchain` commands live in
`tooling/appchain-cli`.
