# Cardano integration fixtures

`devnet-protocol-param.json` is an exact byte copy of
`bloxbean/yano`'s `app/config/network/devnet/protocol-param.json` at commit
`1defb5bb9e59fd6fe5fc2b9c8161bc3a3d4734ff`. SHA-256: `0f78d85ebdf025e17eb32a2fc1767b933c4e7869f1032cbce7bf6bae102c9fea`.

`ScriptAnchorServiceTest` loads these complete Plutus V3 protocol parameters
through the actual selected Yano `ProtocolParamsMapper`, preserving pricing and
script-evaluation coverage without requiring a sibling source checkout.
