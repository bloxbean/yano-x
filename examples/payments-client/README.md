# Payments reference client

A small web client for the `asset-payments` app ledger (issue #109 §3.1 and §7,
ADR-059 D13 and D14). It uses only standard CIP-30 calls, so an existing Cardano
wallet can register an account, deposit from L1, transfer, authorize and revoke a
session key, pay with that session key without a wallet prompt, and request
withdrawals. You don't need a Yano X wallet, and your Cardano keys stay in your
wallet.

| Operation | What the wallet does | How it reaches the ledger |
|---|---|---|
| Register | CIP-30 `signData` (CIP-8) | message on `asset-payments.intents` |
| Deposit | CIP-30 `signTx`, then `submitTx` | L1 transaction to the vault, credited by the deposit observer |
| Transfer | `signData` | message |
| Authorize or revoke a session | `signData` | message |
| Pay with a session | nothing: the browser's session key signs | message |
| Withdraw | `signData` | message; settlement pays it on L1 |

Network names are never hard-coded. The client reads the chain's intent domain
from the node and refuses a wallet whose `getNetworkId()` doesn't match the
chain's address network id. The same build works against a devnet, preview,
preprod or mainnet node.

## How an action is signed

1. The client encodes the `IntentEnvelopeV1` itself (`src/lib/intents.ts`). The
   encoder is tested byte for byte against the Java vectors in
   `ledgers/asset-payments/ledger/src/test/resources/asset-payments/intent-vectors-v1.json`.
2. It asks the node's `intents/encode` route to encode the same intent, and
   refuses to sign if the two encodings differ.
3. It shows a review dialog with the fields that matter: action, asset, amount,
   recipient and expiry. The exact bytes are under a disclosure.
   Wallets show a `signData` payload as hex, not as a Cardano transaction. Check
   the fields in this dialog before you approve.
4. The wallet signs (`signData(address, envelopeHex)`). Before submitting, the
   client checks that the COSE_Sign1 payload is exactly the envelope and that its
   protected `address` is the account's signing address.
5. The `intents/message` route wraps the signature as a `PaymentsMessageV1` and
   pre-checks it the way the ledger's admission will (advisory, against this
   node's committed state and clock). The client compares the wrapped bytes with
   its own wrapping.
6. The client submits through the host's ordinary SUBMIT route,
   `POST /api/v1/app-chain/chains/{chain}/messages`, where the ledger's admission
   authorizes the intent again. The domain API can't submit: the host gives
   domain APIs no submission.
7. The client polls `receipts/{intentId}` until the receipt is committed. A
   receipt is `ACCEPTED` or `REJECTED` with a reason. An intent that never
   executes has no receipt and expires.

Session keys are WebCrypto Ed25519 keys with a non-extractable private key. They
are kept in this browser profile's IndexedDB. A grant allows only `TRANSFER`,
within its per-payment and total limits, until its expiry or until the owner
revokes it. A session can never withdraw.

## Payments domain API

The API is served by the `org.yanoproject.x.asset-payments` bundle at
`/api/v1/plugins/org.yanoproject.x.asset-payments/chains/{chain}/payments/`.
Every route is `READ`. Amounts are decimal strings. Every fact carries its state
key, value and `proofPath`: the host's native proof route, relative to `/api/v1/`,
pinned to the committed height the answer was read at.

| Method and path | Returns |
|---|---|
| `GET info` | Intent domain, topic, TTL, vaults (asset id, ticker, decimals, status, address), withdrawal settings, domain status |
| `GET accounts/{accountId}` | Owner credential; per vault: available, reserved, reserved min-ADA, min-ADA credit, each with its proof |
| `GET accounts/{accountId}/sessions` | Grants with key, actions, limits, used and remaining amounts, expiry |
| `GET accounts/{accountId}/withdrawals?before=&limit=` | The account's claims, newest first: amount, recipient, `RESERVED` / `SETTLING` / `PAID` / `PAID_MISMATCH`, L1 payout tx id |
| `GET addresses/{addressHex}` | Credential kind, key hash and the account id it owns |
| `GET receipts/{intentId}` | Receipt (outcome, reason, height) or `found: false`; for an accepted withdrawal, its claim |
| `GET withdrawals/{claimId}` | One claim and its lifecycle |
| `GET vaults/{vaultId}/deposit-instructions?address=&account=` | Vault address and exact inline datum. Refused while the beneficiary is unregistered. |
| `GET vaults/{vaultId}/deposits/{txId}/{index}` | Deposit record: `CREDITED`, `UNCREDITED` with a reason, and so on |
| `POST deposits/build` | Unsigned deposit transaction CBOR built from the wallet's `getUtxos()` |
| `POST intents/encode` | Canonical envelope bytes and intent id for a JSON-described intent |
| `POST intents/message` | `PaymentsMessageV1` body for a `signData` result or a session signature, after an advisory authorization pre-check |

`SETTLING` means the claim is in a settlement batch whose result isn't recorded
yet. It is telemetry, not a consensus status. `PAID` is set only by the payout
observer, and the L1 payout transaction id comes with it.

The `deposits/build` body is
`{vaultId, quantity, lovelace?, changeAddress, utxos: [cip30 utxo hex], beneficiaryAccount?}`.
Fees use the linear fee rule, priced with the node's current L1 protocol
parameters, and count one witness per payment key of the inputs actually spent.
The transaction is valid until two hours of slots past the node's L1 tip. The
route refuses to build without a known tip (`L1_TIP_UNKNOWN`) or protocol
parameters (`L1_PARAMETERS_UNAVAILABLE`).

`deposit-instructions` and `deposits/build` refuse a vault whose
`activeFromSlot` the L1 tip hasn't reached (`VAULT_NOT_ACTIVE`), and a datum
larger than the deposit observer reads (`observers.<id>.datum.max-bytes` and
`datum.max-depth`, `DATUM_TOO_LARGE`). `info` reports both bounds as
`depositDatumBounds`.

An accepted withdrawal's receipt names its claim through the ledger's
`intent-claim/<IntentId>` entry, with that entry's proof as `intentLinkProof`.

## Run it

You need Node 22 and npm.

```bash
cd examples/payments-client
npm ci --legacy-peer-deps
npm test                    # unit tests, including the Java intent vectors
PAYMENTS_NODE_URL=http://localhost:7470 npm run dev   # http://localhost:5174
```

The dev server proxies `/api` to `PAYMENTS_NODE_URL`, so the node needs no CORS
setup. Configuration lives in `public/payments-client.json`, and URL parameters
override it:

| Key | URL parameter | Meaning |
|---|---|---|
| `nodeUrl` | `node` | Node API base. The default is `/api/v1` (proxied). |
| `chainId` | `chain` | Chain id. If empty, the first hosted payments chain is used. |
| `apiKey` | | Topic-scoped submit key, when the node requires one (`yano.app-chain.api.keys=key=asset-payments.intents`) |
| `devkitWalletSdkUrl` | `devkit` | Loads Yaci DevKit's wallet SDK. Devnet only. |
| `explorerTxUrl` | | L1 explorer link template, with `{tx}` |
| `networkLabel` | | Display label only |

### Devnet with Yaci DevKit

1. Start Yaci DevKit (`devkit start`, then `create-node` in the DevKit shell).
   Its built-in CIP-30 wallet runs at <http://localhost:10000/wallet> with
   pre-funded devnet accounts. Its SDK injects `window.cardano.yacidevkit`.
2. Build and unpack the JVM distribution, then start one Yano X node that
   follows DevKit's cardano-node and hosts an asset-payments chain:

   ```bash
   ./gradlew :distribution:jvm:yanoXJvmDistZip -PskipSigning=true
   unzip distribution/jvm/build/distributions/yano-x-jvm-<version>.zip -d /tmp/payments
   DIST=/tmp/payments/yano-x-jvm-<version> RUN=/tmp/payments/run \
     examples/payments-client/e2e/devkit-node.sh start
   DIST=... RUN=... examples/payments-client/e2e/devkit-node.sh status
   ```

   The script copies DevKit's genesis files out of its container
   (`DEVKIT_CONTAINER`, `node1-yaci-cli-1` by default). It runs a single-member
   chain with an ADA vault and a display-only USDM vault. The vault script isn't
   deployed, so devnet ADA sent to the vault stays there. A payout observer id is
   configured but no observer runs, so withdrawals reserve and stay `RESERVED`.
   Paying them needs the full gateway deployment (`qa/harness/asset-gateway`).
3. Open
   `http://localhost:5174/?devkit=http://localhost:10000/wallet-sdk.js`.
   The DevKit wallet uses its active account. To act as another account, switch
   accounts on the wallet page (or
   `PUT /api/v1/wallet/active-account {"accountId": "1"}`) and connect again.

### Yano Wallet

[Yano Wallet](https://devkit.yaci.xyz/yano-wallet) is a desktop wallet backed by
DevKit's Yaci Store. Its connector extension injects `window.cardano.yano`, and
the client lists it like any other CIP-30 wallet. Point the wallet at the same
DevKit, then open the client without the `devkit` parameter.

### Preview and preprod (later)

Run a node against the public network with a funded gateway deployment. Serve
the client from the same origin as the node's API, or set `nodeUrl` and allow
the client's origin in the node's CORS configuration. Eternl and Lace inject
`window.cardano.eternl` and `window.cardano.lace`.

## End-to-end test

`e2e/payments.spec.ts` drives the client with the DevKit wallet. It covers
registering two accounts, a 25 ADA deposit (`signTx`) credited by the observer,
a transfer, a session (one payment without a wallet prompt, one refused with
`SESSION_LIMIT`, then a revoke), a withdrawal shown as reserved, a verified
balance proof, and the unsupported-wallet message. It skips itself when DevKit
or the node isn't reachable.

```bash
npx playwright install chromium   # once
PAYMENTS_NODE_URL=http://localhost:7470 npm run test:e2e
```

## Wallet compatibility

Every entry is **untested** until the interoperability track runs it. Record the
wallet version, the credential that signed (payment or stake key), and how
rejection, hardware and mobile behave.

| Wallet | Version | `signData` (payment key) | `signData` (stake key) | `signTx` deposit | User rejection | Hardware | Mobile | Status |
|---|---|---|---|---|---|---|---|---|
| Yaci DevKit wallet | 0.12.0 | swapped arguments (see below) | | | n/a | n/a | n/a | untested |
| Yano Wallet | | | | | | | | untested |
| Eternl | | | | | | | | untested |
| Lace | | | | | | | | untested |
| Yoroi | | | | | | | | untested |

Known quirk: Yaci DevKit 0.12.0's wallet backend (`/api/v1/wallet/sign-data`)
puts the payload in the protected `address` header and signs the address as the
payload. The client detects this from the wallet's own output, retries once with
the arguments swapped, and checks the result like any other signature. Any
other mismatch is refused, and nothing is submitted.

A wallet without `signData` gets an "Unsupported wallet" message. The client has
no weaker fallback. Script addresses and Byron addresses have no key a CIP-30
wallet signs with, so they can't own an account.

## Limits of this client

- Proofs: **Verify** fetches the native proof at the view's height. It checks
  that the proof's value and root are the ones shown, and asks the node's
  `proof/verify` route to verify it. For an independent check, verify the proof
  yourself with `sdk/proof-verifier`, against a root you trust (for example the
  L1 anchor), rather than against the node that served it.
- The account view sums reserved amounts from up to 8 pages of 64 matching
  claims, at most 2,048 claims scanned per page. `reservedFrom.complete` says
  whether that covered every claim.
- No owner rotation UI. `ROTATE_OWNER` needs two wallets to sign the same
  envelope; the encoder and the API support it.
