/**
 * The payments domain API (/api/v1/plugins/org.yanoproject.x.asset-payments/chains/{chain}/payments/...) and the two
 * host routes the client also needs: the ordinary message SUBMIT route and the native state proof route.
 */
import { proofPath, type Fact, type NativeProof } from './proof';

export const BUNDLE = 'org.yanoproject.x.asset-payments';

export interface Committed {
  height: number;
  stateRoot: string;
}

export interface ProofRef {
  keyHex: string;
  valueHex: string | null;
  height: number;
  proofPath: string;
}

export interface AssetView {
  policyId: string;
  assetName: string;
  unit: string;
  ada: boolean;
}

export interface VaultView {
  vaultId: string;
  asset: AssetView;
  ticker: string;
  decimals: number;
  status: string;
  address: string;
  depositsEnabled: boolean;
  transfersEnabled: boolean;
  proof: ProofRef;
}

export interface Info {
  chainId: string;
  committed: Committed;
  intentTopic: string;
  intentDomain: { networkMagic: number; addressNetworkId: number; chainId: string; genesisId: string;
    applicationId: string };
  maxIntentTtlMs: number;
  withdrawalsEnabled: boolean;
  minAdaPayout: string;
  payoutLovelaceCap: string;
  vaults: VaultView[];
}

export interface Balance extends Omit<VaultView, 'address' | 'depositsEnabled' | 'transfersEnabled'> {
  available: string;
  minAdaCredit: string;
  reserved: string;
  reservedMinAda: string;
}

export interface AccountView {
  accountId: string;
  committed: Committed;
  registered: boolean;
  owner?: { credentialKind: string; keyHash: string };
  balances: Balance[];
  proof: ProofRef;
  /** Whether the reserved totals covered every claim (the API scans a bounded number of pages). */
  reservedFrom?: { committedHeight: number; complete: boolean; claimsScanned: number };
}

export interface GrantView {
  grantId: string;
  sessionKey: string;
  actions: string[];
  notAfterMs: number;
  limits: { vaultId: string; perPayment: string; cumulative: string; used: string; remaining: string }[];
  recipients: string[];
  proof: ProofRef;
}

export interface WithdrawalView {
  claimId: string;
  sequence: number | null;
  vaultId: string;
  asset?: AssetView;
  amount: string;
  vaultLovelace: string;
  recipientHex: string;
  recipientAddress: string | null;
  reservedHeight: number;
  status: string;
  lifecycle: string;
  l1TxId: string | null;
  proofs: ProofRef[];
}

export interface ReceiptView {
  intentId: string;
  committed: Committed;
  found: boolean;
  proof: ProofRef;
  receipt?: { accountId: string; action: string; amount: string; outcome: string; reason: string; height: number };
  withdrawals?: WithdrawalView[];
}

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

/** The page's own URL, which a relative node URL resolves against (none in unit tests). */
function pageUrl(): string {
  return globalThis.location?.href ?? 'http://localhost/';
}

/** Request headers; the API key goes only to {@code nodeOrigin}, the configured node. */
export function requestHeaders(url: string, nodeOrigin: string, apiKey: string, json: boolean): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (json) headers['Content-Type'] = 'application/json';
  if (apiKey && new URL(url, pageUrl()).origin === nodeOrigin) headers['X-API-Key'] = apiKey;
  return headers;
}

export class PaymentsApi {
  private readonly nodeOrigin: string;

  constructor(readonly nodeUrl: string, readonly chainId: string, private readonly apiKey = '') {
    this.nodeOrigin = new URL(nodeUrl, pageUrl()).origin;
  }

  private get base(): string {
    return `${this.nodeUrl}/plugins/${BUNDLE}/chains/${encodeURIComponent(this.chainId)}/payments/`;
  }

  info = () => this.get<Info>('info');
  account = (accountId: string) => this.get<AccountView>(`accounts/${accountId}`);
  sessions = (accountId: string) => this.get<{ grants: GrantView[]; committed: Committed }>(
    `accounts/${accountId}/sessions`);
  withdrawals = (accountId: string) => this.get<{ withdrawals: WithdrawalView[]; nextBefore: number | null }>(
    `accounts/${accountId}/withdrawals?limit=20`);
  receipt = (intentId: string) => this.get<ReceiptView>(`receipts/${intentId}`);
  withdrawal = (claimId: string) => this.get<{ found: boolean; withdrawal?: WithdrawalView }>(`withdrawals/${claimId}`);
  depositInstructions = (vaultId: string, addressHex: string, accountId: string) =>
    this.get<Record<string, unknown>>(`vaults/${vaultId}/deposit-instructions?address=${addressHex}`
      + `&account=${accountId}`);
  deposit = (vaultId: string, txId: string, index: number) =>
    this.get<{ found: boolean; deposit?: { status: string; reason: string; creditedAmount: string } }>(
      `vaults/${vaultId}/deposits/${txId}/${index}`);
  buildDeposit = (request: Record<string, unknown>) => this.post<{
    unsignedTxCborHex: string; transactionId: string; fee: string; outputIndex: number; depositId: string;
    quantity: string; outputLovelace: string;
  }>('deposits/build', request);
  encodeIntent = (request: Record<string, unknown>) =>
    this.post<{ envelopeHex: string; intentId: string }>('intents/encode', request);
  prepareMessage = (request: Record<string, unknown>) =>
    this.post<{ topic: string; bodyHex: string; intentId: string }>('intents/message', request);

  /** The host's ordinary SUBMIT route: the ledger's admission authorizes the intent again here. */
  async submit(topic: string, bodyHex: string): Promise<{ messageId: string }> {
    const url = `${this.nodeUrl}/app-chain/chains/${encodeURIComponent(this.chainId)}/messages`;
    const response = await fetch(url, {
      method: 'POST', headers: this.headers(url, true), body: JSON.stringify({ topic, bodyHex })
    });
    return this.json(response);
  }

  /**
   * The native state proof of a fact at the height its view was read at, fetched from a route built from the fact
   * itself, and the node's verifier run against the view's own root, key and value (not the proof's).
   */
  async proof(fact: Fact): Promise<{ proof: NativeProof & Record<string, unknown>; verification: Record<string, unknown> }> {
    const proofUrl = `${this.nodeUrl}/${proofPath(this.chainId, fact)}`;
    const proof = await this.json<NativeProof & Record<string, unknown>>(await fetch(proofUrl,
      { headers: this.headers(proofUrl, false) }));
    const present = fact.valueHex !== null;
    const verifyUrl = `${this.nodeUrl}/app-chain/chains/${encodeURIComponent(this.chainId)}/proof/verify`;
    const verification = await this.json<Record<string, unknown>>(await fetch(verifyUrl, {
      method: 'POST', headers: this.headers(verifyUrl, true), body: JSON.stringify({
        mode: present ? 'inclusion' : 'exclusion', profile: proof.profile, presence: present ? 'PRESENT' : 'ABSENT',
        expectedRootHex: fact.stateRoot, keyHex: fact.keyHex, proofWireHex: proof.proofWireHex,
        ...(present ? { valueHex: fact.valueHex } : {})
      })
    }));
    return { proof, verification };
  }

  /** Fallback L1 submission through the node's Cardano API when the wallet has no submitTx. */
  async submitTx(signedTxHex: string): Promise<string> {
    const bytes = Uint8Array.from(signedTxHex.match(/../g)!.map((pair) => parseInt(pair, 16)));
    const response = await fetch(`${this.nodeUrl}/tx/submit`, {
      method: 'POST', headers: { 'Content-Type': 'application/cbor' }, body: bytes
    });
    const text = await response.text();
    if (!response.ok) throw new ApiError(response.status, 'L1_SUBMIT', text);
    return text.replace(/"/g, '').trim();
  }

  private async get<T>(path: string): Promise<T> {
    return this.json<T>(await fetch(this.base + path, { headers: this.headers(this.base + path, false) }));
  }

  private async post<T>(path: string, body: unknown): Promise<T> {
    return this.json<T>(await fetch(this.base + path, {
      method: 'POST', headers: this.headers(this.base + path, true), body: JSON.stringify(body)
    }));
  }

  private headers(url: string, json: boolean): Record<string, string> {
    return requestHeaders(url, this.nodeOrigin, this.apiKey, json);
  }

  private async json<T>(response: Response): Promise<T> {
    const text = await response.text();
    let body: Record<string, unknown> = {};
    try {
      body = text ? JSON.parse(text) : {};
    } catch {
      // A non-JSON body is reported as text below.
    }
    if (!response.ok) {
      const code = String(body.error ?? body.code ?? response.status);
      const message = String(body.message ?? body.error ?? body.details ?? text ?? response.statusText);
      throw new ApiError(response.status, code, message);
    }
    return body as T;
  }
}

/** The first hosted chain whose payments/info answers, when no chain id is configured. */
export async function discoverChain(nodeUrl: string, apiKey: string): Promise<string> {
  const url = `${nodeUrl}/app-chain/chains`;
  // A node that requires an API key requires it here too.
  const response = await fetch(url, { headers: requestHeaders(url, new URL(nodeUrl, pageUrl()).origin, apiKey,
    false) });
  if (!response.ok) throw new Error(`cannot list the node's app chains (${response.status})`);
  const chains = (await response.json()) as { chainId: string }[];
  for (const chain of chains) {
    try {
      await new PaymentsApi(nodeUrl, chain.chainId, apiKey).info();
      return chain.chainId;
    } catch {
      // Not a payments chain.
    }
  }
  throw new Error('no asset-payments chain is hosted by this node');
}
