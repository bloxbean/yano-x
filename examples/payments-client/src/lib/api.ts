/**
 * The payments domain API (/api/v1/plugins/org.yanoproject.x.asset-payments/chains/{chain}/payments/...) and the two
 * host routes the client also needs: the ordinary message SUBMIT route and the native state proof route.
 */
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

export class PaymentsApi {
  constructor(readonly nodeUrl: string, readonly chainId: string, readonly apiKey = '') {}

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
    const response = await fetch(`${this.nodeUrl}/app-chain/chains/${encodeURIComponent(this.chainId)}/messages`, {
      method: 'POST', headers: this.headers(true), body: JSON.stringify({ topic, bodyHex })
    });
    return this.json(response);
  }

  /** The native state proof at the height a view was read at, and the node's verifier over it. */
  async proof(ref: ProofRef): Promise<{ proof: Record<string, unknown>; verification: Record<string, unknown> }> {
    const proof = await this.json<Record<string, unknown>>(await fetch(`${this.nodeUrl}/${ref.proofPath}`,
      { headers: this.headers(false) }));
    const present = proof.presence !== 'ABSENT';
    const verification = await this.json<Record<string, unknown>>(await fetch(
      `${this.nodeUrl}/app-chain/chains/${encodeURIComponent(this.chainId)}/proof/verify`, {
        method: 'POST', headers: this.headers(true), body: JSON.stringify({
          mode: present ? 'inclusion' : 'exclusion', profile: proof.profile, presence: proof.presence,
          expectedRootHex: proof.stateRoot, keyHex: proof.key, proofWireHex: proof.proofWireHex,
          ...(present ? { valueHex: proof.valueHex } : {})
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
    return this.json<T>(await fetch(this.base + path, { headers: this.headers(false) }));
  }

  private async post<T>(path: string, body: unknown): Promise<T> {
    return this.json<T>(await fetch(this.base + path, {
      method: 'POST', headers: this.headers(true), body: JSON.stringify(body)
    }));
  }

  private headers(json: boolean): Record<string, string> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (json) headers['Content-Type'] = 'application/json';
    if (this.apiKey) headers['X-API-Key'] = this.apiKey;
    return headers;
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
  const response = await fetch(`${nodeUrl}/app-chain/chains`);
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
