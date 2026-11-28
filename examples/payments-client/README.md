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

An **Explorer** tab shows every block and transaction on the chain to anyone,
without a wallet (see [Explorer](#explorer)).

Network names are never hard-coded. The client reads the chain's intent domain
from the node and refuses a wallet whose `getNetworkId()` doesn't match the
chain's address network id. The same build works against a devnet, preview,
preprod or mainnet node.

## Screens

The client is a single-page React app styled as a sibling of the Yano X client
showcase: the same light Yano X brand tokens, type (Inter, Space Grotesk,
JetBrains Mono), cards, badges and header.

| Screen | What it shows |
|---|---|
| Welcome | The chain, its network badge (from the intent domain) and the assets in the vault registry, with a Connect button. |
| Wallet picker | Every CIP-30 wallet injected into the page (`yano`, `yacidevkit`, Eternl, Lace, ...), the stake-key option, and the "Unsupported wallet" message for a wallet without `signData` or on another network. |
| Onboarding | Registration as one step: sign a message, no L1 transaction and no fee. |
| Dashboard | The account, per-asset balance cards (available, reserved with a "partial" badge, min-ADA credit, registry status, the full asset id and a **Verify proof** button), action buttons, and tabs for sessions, withdrawals and the ledger (intent domain and vault registry, each vault with its proof). |
| Activity | Every action this tab submitted, followed to its receipt (`ACCEPTED`, `REJECTED <reason>` with a readable explanation, or `EXPIRED`) or deposit record, with a **Verify receipt** button. |
| Deposit | Asset and amount, then the review decoded from the transaction, `signTx`, and a status that follows the deposit to `CREDITED`. |
| Send | Recipient (account id or address), asset and amount, then the review. |
| Session | Limits and expiry, one wallet prompt. The session card shows used and remaining limits, pays without a prompt, and has **Revoke**. |
| Withdraw | Amount (or everything available), destination address and the `RESERVED` -> `SETTLING` -> `PAID` tracker, with the L1 payout tx link. |
| Pay request | Paste a payment request link (or its QR payload) to preview and pay it, or make a link for your own account. |
| Review sheet | The last step before every wallet prompt (see below). |
| Explorer | Public chain data with no wallet: the latest blocks and transactions, block, transaction and account pages, and search (see [Explorer](#explorer)). |

| | |
|---|---|
| ![Welcome](docs/screenshots/01-welcome.png) | ![Wallet picker with the unsupported-wallet message](docs/screenshots/02-wallet-picker.png) |
| ![Onboarding](docs/screenshots/03-onboarding.png) | ![Register review](docs/screenshots/04-review-register.png) |
| ![Dashboard](docs/screenshots/10-dashboard.png) | ![Withdrawals](docs/screenshots/11-withdrawals.png) |
| ![Deposit review, decoded from the transaction](docs/screenshots/06-review-deposit.png) | ![Deposit credited](docs/screenshots/07-deposit-credited.png) |
| ![Transfer review](docs/screenshots/08-review-transfer.png) | ![Authorize a session](docs/screenshots/09-session-authorize.png) |
| ![Withdraw](docs/screenshots/12-withdraw.png) | ![Pay request](docs/screenshots/13-pay-request.png) |

![Phone width](docs/screenshots/14-phone.png)

### Payment requests

A payment request is a link that asks for an exact amount of one asset to one
account on one chain:

```
<client>#/pay?v=1&chain=<chainId>&to=<accountId>&asset=<lovelace | policyId.assetNameHex>&amount=<base units>&memo=<text>
```

The amount is in base units and the asset is the full asset id, so a link
doesn't depend on the node's display metadata. The client refuses a link for
another chain, for an asset this ledger has no vault for, or for your own
account. Paying it is an ordinary owner-signed transfer with the usual review.
The memo is the requester's own text: it appears as "unverified" and isn't
signed. Opening the client with such a link opens the pay sheet once the
account is registered.

## Explorer

The **Explorer** tab (`#/explorer`, next to **Wallet** in the header) is a block
explorer for the payments chain. It reads only public chain data, so it works
without connecting a wallet.

| Page | What it shows |
|---|---|
| Overview (`#/explorer`) | Tip height, block count, last block time, state root and vault count. A live list of the latest blocks (height, age, messages, certificate signatures, short state root) and a list of the latest transactions that streams new messages as they are finalized. Both have **Load older**. |
| Block (`#/explorer/block/{height}`) | The header as the node reports it (time, state root, messages root, previous block hash, proposer, signatures), previous/next, and its decoded transactions. |
| Transaction (`#/explorer/tx/{messageId}`) | Every decoded field (intent id, signer, recipient, amount in display and base units, asset id, vault, L1 address, session terms, nonce, expiry, intent domain), the message's position, topic and sender, the raw body and signed envelope (collapsible), and the receipt or deposit record with **Verify** and any withdrawal claim with its L1 payout link. |
| Account (`#/explorer/account/{accountId}`) | Registration and owner, balances with **Verify proof**, the account's transactions, and its withdrawals. |

Each transaction row shows its kind (Register, Transfer, Session authorize,
Session pay, Revoke session, Withdraw, Rotate owner, Deposit (L1), Payout (L1),
L1 observation, Governance), from and to, the amount in display units, the block
height and index, the age, the outcome (Accepted, Rejected with its code and a
readable reason, Credited, Uncredited) and, for deposits and payouts, the L1
transaction (linked through `explorerTxUrl` when it is a plain 64-hex id). A
message that does not decode is an **Unknown message** with its topic, size and
the reason.

The search box takes a block height, a message id or intent id (64 hex), an
account id (56 hex), or a Cardano address (bech32 or raw hex). An intent id is
found through its receipt's height and the message there whose envelope hashes
to it. An address resolves through `addresses/{addressHex}`, and the client
derives the account id itself as well and refuses a node that names another one.

| | |
|---|---|
| ![Explorer overview](docs/screenshots/explorer-overview.png) | ![A block](docs/screenshots/explorer-block.png) |
| ![A transaction with its receipt verified](docs/screenshots/explorer-tx.png) | ![An account](docs/screenshots/explorer-account.png) |

### How the explorer treats node data

Everything the explorer shows comes from the node and is untrusted:

- **As reported by the node.** Every page says so. Blocks, messages and their
  order are the node's report. The client decodes message bodies itself; it
  doesn't take a decoded form from the node.
- **Verified only by a proof.** An outcome counts as verified only after its
  **Verify** check passes. The outcome is read from the receipt or deposit record
  *value* that the state proof covers, not from the view's JSON (a view whose
  JSON disagrees with its value is shown as **Inconsistent**). The record's key
  is derived from the message itself (`ap/receipt/` plus the intent id computed
  from the signed envelope, `ap/deposit/` plus the claim's deposit id), so a
  node can't prove some other record in its place. Verify then runs the same
  proof check as the wallet view, at the view's height and root.
- **Bounded decoding.** The decoder reads only the ledger's own forms, refuses a
  body over 64 KiB or nested deeper than 8 levels, and never throws: anything
  else becomes an unknown message. Node responses are checked field by field
  (heights, hex ids, string lengths), and a page that still can't render the
  node's data fails alone, behind an error boundary.
- **Text only.** Node strings are rendered as text, never as HTML. Internal
  links are built from checked heights and hex ids only.

### Endpoints the explorer reads

All under `/api/v1`, chain-scoped:

| Endpoint | Used for |
|---|---|
| `GET app-chain/chains/{chain}/tip` | Tip height and state root (polled every 3 s, every 15 s while the stream is live) |
| `GET app-chain/chains/{chain}/blocks?limit=` | Latest blocks and the last block time |
| `GET app-chain/chains/{chain}/blocks/{height}` | Block pages, and the messages behind the transaction lists (cached: a finalized block never changes) |
| `GET app-chain/chains/{chain}/messages/{messageId}` | Transaction pages and search |
| `GET app-chain/chains/{chain}/stream` | The live transaction list (SSE `app-message` events), with polling as the fallback |
| `GET …/payments/info` | Vaults (tickers, decimals) and the deposit observer's topic |
| `GET …/payments/receipts/{intentId}` | Intent outcomes |
| `GET …/payments/vaults/{vaultId}/deposits/{txId}/{index}` | Deposit outcomes |
| `GET …/payments/accounts/{accountId}` and `…/withdrawals` | The account page |
| `GET …/payments/addresses/{addressHex}` | Address search |
| `GET app-chain/chains/{chain}/state/proof/{key}?height=`, `POST …/proof/verify` | **Verify** |

The node has no per-account message index (every message is sent by a member
key, so `messages/by-sender` doesn't find an account), so the account page scans
the newest 50 blocks and offers **Scan 50 older blocks**, up to 500. The latest
blocks list pages up to 100 blocks; further back, open a block and use
previous/next, or search a height.

## Stack and layout

React 19, Vite, Tailwind CSS 4 (`@tailwindcss/vite`), TanStack Query,
lucide-react and clsx, with TypeScript, at the exact versions in
`package.json`. The UI never decides what is signed: that logic is plain
TypeScript.

| Path | Role |
|---|---|
| `src/lib/` | The tested logic: the intent encoder, CBOR, COSE, deposit verification, CIP-30 wallet access, session keys, proof binding, configuration and the API client. Also readable error codes (`errors.ts`) and payment request links (`payrequest.ts`). |
| `src/flows.ts` | Every action, independent of the UI: the intent pipeline (encode, cross-check, review, sign, pre-check, submit), deposit build and verification, sessions, proofs. |
| `src/state.tsx` | Configuration, chain, wallet and account state, the polled views, the activity this tab submitted, toasts and the pending review. |
| `src/components/` | Screens, flow sheets and the review sheet. |
| `src/components/explorer/` | The Explorer: overview, block, transaction and account pages, transaction rows, search, and its queries and SSE stream (`hooks.ts`). Its decoder, chain-response checks, row mapping and search live in `src/lib/` (`explorer.ts`, `chain.ts`, `txrow.ts`, `search.ts`). |
| `src/route.ts` | The hash routes: the wallet (`#/`, `#/pay?…`) and the explorer (`#/explorer…`). |
| `src/ui.tsx`, `src/index.css` | UI primitives and design tokens, adapted from the client showcase. |

Every read goes through TanStack Query. Receipts, deposit records and
withdrawals keep polling, also in a background tab, until they reach a terminal
state (a receipt, a deposit record, `PAID`), so a status never stops half way.

## How an action is signed

1. The client encodes the `IntentEnvelopeV1` itself (`src/lib/intents.ts`). The
   encoder is tested byte for byte against the Java vectors in
   `ledgers/asset-payments/ledger/src/test/resources/asset-payments/intent-vectors-v1.json`.
2. It asks the node's `intents/encode` route to encode the same intent, and
   refuses to sign if the two encodings differ.
3. It shows the review sheet with the fields that matter: action, the amount in
   display units and in base units, the full asset id (`policyId.assetName`
   hex), the recipient, the account, the chain id, genesis id and network of the
   intent domain, and the expiry. The base units and asset id are what the
   ledger executes, so a node that lies about a ticker or decimals shows up here.
   The exact bytes are under a disclosure.
   Wallets show a `signData` payload as hex, not as a Cardano transaction. Check
   the fields in this sheet before you approve. Values are never shortened in
   the review.
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
   receipt is `ACCEPTED` or `REJECTED` with a reason, shown with a readable
   explanation (`src/lib/errors.ts`). An intent that never executes has no
   receipt and expires.

A session payment skips steps 3 and 4: the browser's session key signs, with no
review and no wallet prompt, and the grant's limits bound it.

Session keys are WebCrypto Ed25519 keys with a non-extractable private key. They
are kept in this browser profile's IndexedDB. A grant allows only `TRANSFER`,
within its per-payment and total limits, until its expiry (at most 24 hours in
this client) or until the owner revokes it. A session can never withdraw. The
client deletes a local key once its grant has expired, was rejected or revoked,
or its authorizing intent expired unexecuted.

## How a deposit is checked

The domain API builds the unsigned deposit, but the client doesn't trust it. Before
the wallet's `signTx`, `src/lib/deposit.ts` decodes the CBOR and refuses it
unless:

- every input is one of the wallet's own `getUtxos()` UTxOs;
- exactly one output pays the vault address from the vault registry the client
  read at startup from the configured node, not the build response;
- that output carries the exact `DepositDatumV1` the client computes itself
  (beneficiary account, refund address and refund key hash), and exactly the
  vault's asset in the requested quantity;
- every other output pays the wallet's change address, with no datum or script;
- the body has only inputs, outputs, fee, TTL and validity start: no
  certificates, withdrawals, mints, collateral or required signers;
- the fee is at most `maxDepositFeeLovelace` and a TTL is set.

The client computes the transaction id itself. The review shows the decoded
values, not the API's summary.

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
npm test                    # unit and component tests, including the Java intent vectors
npm run check               # tsc --noEmit
npm run build               # type-check and build into dist/
PAYMENTS_NODE_URL=http://localhost:7470 npm run dev   # http://localhost:5174
```

`--legacy-peer-deps` works around an npm 10 resolver error on this dependency
set; it changes no version. Set `PAYMENTS_CLIENT_PORT` to use another port than
5174.

The dev server proxies `/api` to `PAYMENTS_NODE_URL`, so the node needs no CORS
setup. Configuration lives in the deployed `public/payments-client.json`. A built
client reads the node and the DevKit SDK only from that file. Only the dev
server (`npm run dev`) honours the `node` and `devkit` URL parameters, because a
link that sets them could point the page at another node or load a script next
to the stored session keys.

| Key | URL parameter | Meaning |
|---|---|---|
| `nodeUrl` | `node` (dev server only) | Node API base. The default is `/api/v1` (proxied). |
| `chainId` | `chain` | Chain id. If empty, the first hosted payments chain is used. |
| `apiKey` | | Topic-scoped submit key, when the node requires one (`yano.app-chain.api.keys=key=asset-payments.intents`). It is sent only to `nodeUrl`'s origin, including on chain discovery, and never to a node a URL parameter chose. |
| `devkitWalletSdkUrl` | `devkit` (dev server only) | Loads Yaci DevKit's wallet SDK. Devnet only. |
| `explorerTxUrl` | | L1 explorer link template, with `{tx}` |
| `networkLabel` | | Display label only |
| `maxDepositFeeLovelace` | | The highest deposit fee the client lets a wallet sign. The default is `2000000`. |

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
   `http://localhost:5174/?devkit=http://localhost:10000/wallet-sdk.js` on the dev
   server. For a built client, set `devkitWalletSdkUrl` in `payments-client.json`
   instead.
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

## Tests

`npm test` runs the unit tests (vitest) and the component tests
(Testing Library with jsdom): the intent encoder against the Java vectors,
deposit verification, the proof binding, session pruning, configuration,
payment request links and error texts; the intent pipeline refusing an intent
the node encodes differently or a prepared message that isn't the signed one;
the review sheet showing the decoded values in full (base units, asset id,
chain, genesis); and the wallet picker's unsupported-wallet messages. For the
explorer: the message decoder on every intent kind from the Java vectors, a
deposit and a withdrawal captured from a devnet chain, payouts and governance;
malformed, oversized and deeply nested input; outcomes bound to the record
value; the row mapping; chain-response checks; routes; search parsing and
resolution; and component tests of the transaction row and the search box.

## End-to-end test

`e2e/payments.spec.ts` drives the client with the DevKit wallet. It covers
registering two accounts, a 25 ADA deposit (`signTx`) credited by the observer,
a transfer, a payment request made by one account and paid by the other, a
session (one payment without a wallet prompt, one refused with
`SESSION_LIMIT`, then a revoke), a withdrawal shown as reserved, verified
balance and receipt proofs, and the unsupported-wallet message. It skips itself
when DevKit or the node isn't reachable. Wait until the node has caught up with
DevKit's L1 tip before you run it (compare `GET /api/v1/node/tip` with DevKit's
tip); a node that is still syncing refuses deposits with `L1_TIP_UNKNOWN` or
credits them late.

```bash
npx playwright install chromium   # once
PAYMENTS_NODE_URL=http://localhost:7470 npm run test:e2e
PAYMENTS_NODE_URL=http://localhost:7470 npm run screenshots   # refreshes docs/screenshots
```

The screenshot run (`e2e/screenshots.spec.ts`) expects the E2E to have run
first, so DevKit accounts 0 and 1 are registered; it leaves account 2
unregistered for the onboarding screen.

`e2e/explorer.spec.ts` opens the Explorer without a wallet: it sees blocks and
transactions on the overview, opens a block and a transaction (raw body, receipt
proof verified), follows the signer to its account page, and searches by height,
account id and address. It needs some activity on the chain, so run
`payments.spec.ts` first on a fresh chain. It doesn't change the chain. To
refresh only the explorer screenshots:

```bash
PAYMENTS_NODE_URL=http://localhost:7470 npx playwright test e2e/explorer.spec.ts
PAYMENTS_NODE_URL=http://localhost:7470 npm run screenshots -- -g explorer
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
payload. For the DevKit wallet (`window.cardano.yacidevkit`) only, the client
detects this from the wallet's own output, retries once with the arguments
swapped, and checks the result like any other signature. Any other mismatch is
refused, and nothing is submitted.

A wallet without `signData` gets an "Unsupported wallet" message. The client has
no weaker fallback. Script addresses and Byron addresses have no key a CIP-30
wallet signs with, so they can't own an account.

## Limits of this client

- Proofs: **Verify** fetches the native proof of the shown key at the view's
  committed height, from a route the client builds itself. It requires the
  proof's key, value, height and state root to be the view's, and asks the
  node's `proof/verify` route to verify it against the view's root. A proof at
  another height isn't verified. For an independent check, verify the proof
  yourself with `sdk/proof-verifier`, against a root you trust (for example the
  L1 anchor), rather than against the node that served it.
- The account view sums reserved amounts from up to 8 pages of 64 matching
  claims, at most 2,048 claims scanned per page. `reservedFrom.complete` says
  whether that covered every claim; the client labels an incomplete total
  "partial".
- Explorer: blocks and messages are the node's report. Only an outcome or
  balance whose **Verify** passed is checked, and only against the node's own
  verifier and state root (see the proofs item above). The account page finds
  transactions by scanning recent blocks, not through an index. The live list
  uses SSE, which can't send the API key header; with `apiKey` set on a node
  that requires it, the list falls back to polling.
- Activity lives in this browser tab's memory: a reload starts an empty feed
  (balances, sessions and withdrawals are read from the ledger again).
- A payment request is shown as a link to copy; the client doesn't draw QR
  codes (any QR generator can encode the link).
- No owner rotation UI. `ROTATE_OWNER` needs two wallets to sign the same
  envelope; the encoder and the API support it. After a rotation, connect the new
  owner's wallet, open **Account details** from the account menu and enter the
  account id under **Act for another account**. The client
  acts for it only if the account's committed owner is the connected credential.
