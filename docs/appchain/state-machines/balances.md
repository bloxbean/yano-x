# balances

`balances` is a simple, non-negative account ledger. A configured minter
creates units, and each member can spend only from its own account. Every
balance can be proved against the state root.

These are application credits, not Cardano ada or native assets. The machine
creates no Cardano transactions, holds no custody, and charges no fees.

## At a glance

| | |
|---|---|
| Machine id | `balances` |
| Maturity | stable |
| Commands | `[0, account, amount]` MINT; `[1, account, amount]` TRANSFER; `amount` is a positive integer |
| Setting | `machines.balances.minter`: a member public key as 64 hex characters, or empty |
| State | `b/<account>` → the amount as `BigInteger.toByteArray()`; a zero balance deletes the key |
| Proof subject | `account-balance-v1`: coordinate `account`; claims `exact`, `minimum`, `maximum` |
| Result codes | `BALANCE_NOT_MINTER`, `BALANCE_INSUFFICIENT`; `BALANCE_EVENT_RANGE` in composites |
| Events | `balances.minted.v1`, `balances.transferred.v1`, in composites only |

## How it works

<!-- illustration: balances-ledger -->
1. **Mint to B.** The minter mints 200 to member B's account, which is named by
   B's public key in lowercase hex. It is stored as `00c8`.
2. **Transfer to alice.** B transfers 50 to `alice`. The transfer debits B's own
   account; `alice` can receive but can never spend.
3. **Drain to zero.** B transfers its last 150. B's key is deleted.
<!-- /illustration -->

The rules:

- **MINT** credits `account`. If a minter is configured, a MINT from any other
  member is rejected with `BALANCE_NOT_MINTER`. With no minter, any member can
  mint.
- **TRANSFER** debits the sender's own account and credits `account`. The
  sender's account is its 32-byte member public key in lowercase hex. A
  transfer larger than that balance is rejected with `BALANCE_INSUFFICIENT`.
  Balances never go negative.
- **Accounts that are not member keys are receive-only.** No member can sign as
  `alice`, so units sent there cannot move again.
- **Account names are exact text.** A key written in uppercase hex is a
  different account from the member's own, so units minted there are stranded.
- **Storage.** The value is `BigInteger.toByteArray()`: minimal two's
  complement, so 200 is stored as `00c8` and 50 as `32`. Readers decode it as
  an unsigned number. A balance of zero deletes the key.

On a standalone chain a rejected command is a final no-op. Inside a
declarative composite it rejects the whole cascade.

## When to use it

Use `balances` for simple member-owned units: netting or settlement inputs,
loyalty or service credits, prepaid usage units, and demos that need a
deterministic mint and transfer.

Write a custom state machine when accounts belong to identities other than
member nodes, minting needs several roles or approvals, you need more than one
denomination, transfers need holds or swaps, or settlement on Cardano is part
of the transition.

## Configure

There is no `balances` recipe and no `balances` chain in the stock cluster.
Add a chain entry, with the three
[state-identity settings](README.md#before-you-configure-one) and a fresh
genesis id:

```yaml
yano:
  app-chain:
    chains[3]:
      chain-id: "credits-chain"
      state-machine: balances
      state:
        commitment-profile: mpf-blake2b256-v1
        format-fingerprint: 91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b
        genesis-id: <64 lowercase hex characters, unique to this chain>
      membership:
        mode: governed
      machines:
        balances:
          minter: <64-hex member public key>
```

An empty `minter` lets every member mint; use that only when it is the
intended governance. The minter is a consensus setting.

## Submit through REST

The helper encodes commands. This mints 10 units to `alice`; `830065616c6963650a`
is CBOR `[0, "alice", 10]`:

```bash
TOOL=docs/appchain/tutorials/tools/stdlib_command.py
MINT_HEX=$(python3 "$TOOL" balances mint alice 10)   # 830065616c6963650a

curl -sS -X POST \
  http://127.0.0.1:7070/api/v1/app-chain/chains/credits-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"balances.command.v1\",\"bodyHex\":\"$MINT_HEX\"}" | jq .
```

Send it through the minter's node. Add `-H "X-API-Key: ..."` when API
authentication is enabled.

Prove the balance of `alice`; the state key `b/alice` is `622f616c696365` in hex:

```bash
curl -sS \
  http://127.0.0.1:7070/api/v1/app-chain/chains/credits-chain/state/proof/622f616c696365 \
  | jq '{committedHeight, stateRoot, presence, valueHex}'
```

For this mint, `valueHex` is `0a`.

## Submit from Java

A member transfers from its own account, so it needs a balance first. This
example mints to the submitting node's account, then transfers from it:

```java
AppChainClient raw = AppChainClient.builder("http://127.0.0.1:7070/api/v1")
        .chainId("credits-chain")
        .build();
StdlibAppChainClient balances = new StdlibAppChainClient(raw);

// The member public key of the node at port 7070, in lowercase hex. It is the
// `sender` of any message finalized through that node.
String nodeAccount = "<64-hex member public key>";

balances.mint(nodeAccount, BigInteger.valueOf(100)); // needs this node to be the minter
// ...wait until the mint is final...
balances.transfer("customer-42", BigInteger.TEN);    // debits nodeAccount

balances.balance("customer-42").ifPresent(state -> System.out.println(state.value()));
```

`GET /api/v1/app-chain/chains/credits-chain/messages/{messageId}` returns a
finalized message with its `sender`. A message id proves only that the command
was accepted; read the balance after the command is final. With the Spring
starter, `StdlibAppChainTemplate` offers `mint`, `transfer` and `balance` with
the same semantics.

## Advanced

### Composites

The composite kernel reports amounts as signed 64-bit integers in its events.
A command whose amount or resulting balances do not fit is rejected with
`BALANCE_EVENT_RANGE`; it is never rounded. Standalone `balances` keeps
arbitrary-precision balances.

### Admission-rule views and facts

In a declarative composite, admission rules can read this machine's state and
post-state (ADR-031.4, see [admission rules](../bindings/07-admission-rules.md)):

- **Value view** (namespace `""`, key: the account id text; a sender's own
  account is `{fn: hex, args: [{context: sender}]}`): `balance`. A zero balance
  deletes the account's key, so an absent read means a zero balance.
- **Post-state facts**, after an approved command and equal to its event:
  `balanceAfter` for a mint; `fromBalanceAfter` and `toBalanceAfter` for a
  transfer. `examples/bindings/balances-holding-cap.yaml` caps a recipient's
  balance with `facts.toBalanceAfter <= params.cap`.

## Customization boundary

Account naming, authorization, arithmetic and the deletion of zero balances are
consensus rules. Configuration selects only the minter. Write a versioned
plugin for more asset types, role-aware ownership, fees, locks or settlement.

## Related documentation

- [Choose a stock state machine](../tutorials/03-stock-state-machines.md)
- [State machines](README.md)
- [Java app ledger client](../../../sdk/client/README.md)
