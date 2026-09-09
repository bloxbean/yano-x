import {
  EUTXO_BRIDGE_BUNDLE_ID,
  EUTXO_BUNDLE_ID,
  EUTXO_INDEX_BUNDLE_ID,
  isEutxoChain
} from './model';
import type {
  ActiveConnection,
  AnchorCommitment,
  AppChainStatus,
  ChainSummary,
  DepositAssembleResponse,
  DepositBuildResponse,
  DiscoveredChain,
  EutxoBridgeInfo,
  EutxoDeposit,
  EutxoIndexEnvelope,
  EutxoIndexPage,
  EutxoIndexedAccount,
  EutxoIndexStatus,
  EutxoLineage,
  EutxoTransactionDetail,
  EutxoTransactionPage,
  EutxoValidityBatch,
  EutxoWithdrawal,
  L1Transaction,
  L1TransactionUtxos,
  L2BuildResponse,
  MessageSubmitResult,
  NodeConfig,
  NodeStatus
} from './types';

const REQUEST_TIMEOUT_MS = 20_000;

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code = '',
    readonly path = ''
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export class YanoApi {
  constructor(
    readonly connection: ActiveConnection,
    private readonly fetcher: typeof fetch = fetch
  ) {}

  nodeConfig(): Promise<NodeConfig> {
    return this.get('/node/config');
  }

  nodeStatus(): Promise<NodeStatus> {
    return this.get('/node/status');
  }

  chains(): Promise<ChainSummary[]> {
    return this.get('/app-chain/chains');
  }

  chainStatus(chainId: string): Promise<AppChainStatus> {
    return this.get(`/app-chain/chains/${segment(chainId)}/status`);
  }

  async discoverChains(): Promise<DiscoveredChain[]> {
    const summaries = await this.chains();
    const discovered: Array<DiscoveredChain | null> = await Promise.all(
      summaries.map(async (summary): Promise<DiscoveredChain | null> => {
      const status = await this.chainStatus(summary.chainId);
      if (!isEutxoChain(status)) return null;
      try {
        const bridge = await this.bridgeInfo(summary.chainId);
        return { summary, status, bridge, bridgeState: 'available' } satisfies DiscoveredChain;
      } catch (error) {
        const disabled = error instanceof ApiError && error.status === 404;
        return {
          summary,
          status,
          bridge: null,
          bridgeState: disabled ? 'disabled' : 'unavailable'
        } satisfies DiscoveredChain;
      }
      })
    );
    return discovered.filter((chain): chain is DiscoveredChain => chain !== null);
  }

  bridgeInfo(chainId: string): Promise<EutxoBridgeInfo> {
    return this.pluginGet(
      EUTXO_BRIDGE_BUNDLE_ID,
      `chains/${segment(chainId)}/bridge/info`
    );
  }

  buildDeposit(
    chainId: string,
    depositorAddress: string,
    l2OwnerAddress: string,
    lovelace: number
  ): Promise<DepositBuildResponse> {
    return this.pluginPost(
      EUTXO_BRIDGE_BUNDLE_ID,
      `chains/${segment(chainId)}/bridge/deposit/build`,
      { depositorAddress, l2OwnerAddress, lovelace }
    );
  }

  assembleDeposit(
    chainId: string,
    unsignedTxCborHex: string,
    witnessSetCborHex: string
  ): Promise<DepositAssembleResponse> {
    return this.pluginPost(
      EUTXO_BRIDGE_BUNDLE_ID,
      `chains/${segment(chainId)}/bridge/deposit/assemble`,
      { unsignedTxCborHex, witnessSetCborHex }
    );
  }

  buildTransfer(
    chainId: string,
    fromAddress: string,
    toAddress: string,
    lovelace: number
  ): Promise<L2BuildResponse> {
    return this.pluginPost(
      EUTXO_BRIDGE_BUNDLE_ID,
      `chains/${segment(chainId)}/bridge/transfer/build`,
      { fromAddress, toAddress, lovelace }
    );
  }

  buildWithdrawal(
    chainId: string,
    fromAddress: string,
    payoutAddress: string,
    lovelace: number
  ): Promise<L2BuildResponse> {
    return this.pluginPost(
      EUTXO_BRIDGE_BUNDLE_ID,
      `chains/${segment(chainId)}/bridge/claim/build`,
      { fromAddress, payoutAddress, lovelace }
    );
  }

  submitL1Transaction(signedTxCborHex: string): Promise<string> {
    return this.request('/tx/submit', {
      method: 'POST',
      body: signedTxCborHex,
      headers: { 'Content-Type': 'text/plain' }
    });
  }

  submitL2Transaction(
    chainId: string,
    topic: string,
    signedTxCborHex: string
  ): Promise<MessageSubmitResult> {
    return this.post(`/app-chain/chains/${segment(chainId)}/messages`, {
      topic,
      bodyHex: signedTxCborHex
    });
  }

  committedTransactions(chainId: string, limit = 25, before = 0): Promise<EutxoTransactionPage> {
    return this.pluginGet(EUTXO_BUNDLE_ID, 'transactions', { chain: chainId, limit, before });
  }

  committedTransaction(chainId: string, transactionId: string): Promise<EutxoTransactionDetail> {
    return this.pluginGet(EUTXO_BUNDLE_ID, `transactions/${segment(transactionId)}`, {
      chain: chainId
    });
  }

  indexStatus(chainId: string): Promise<EutxoIndexEnvelope<EutxoIndexStatus>> {
    return this.indexGet(chainId, 'status');
  }

  indexedTransactions(
    chainId: string,
    limit = 25,
    cursor = ''
  ): Promise<EutxoIndexEnvelope<EutxoIndexPage<EutxoTransactionDetail['data']>>> {
    return this.indexGet(chainId, 'transactions', { limit, ...(cursor ? { cursor } : {}) });
  }

  indexedTransaction(
    chainId: string,
    transactionId: string
  ): Promise<EutxoIndexEnvelope<EutxoTransactionDetail['data']>> {
    return this.indexGet(chainId, `transactions/${segment(transactionId)}`);
  }

  indexedAccount(
    chainId: string,
    address: string
  ): Promise<EutxoIndexEnvelope<EutxoIndexedAccount>> {
    return this.indexGet(chainId, `accounts/${segment(address)}`);
  }

  deposits(
    chainId: string,
    limit = 25,
    cursor = ''
  ): Promise<EutxoIndexEnvelope<EutxoIndexPage<EutxoDeposit>>> {
    return this.indexGet(chainId, 'bridge/deposits', { limit, ...(cursor ? { cursor } : {}) });
  }

  withdrawals(
    chainId: string,
    limit = 25,
    cursor = ''
  ): Promise<EutxoIndexEnvelope<EutxoIndexPage<EutxoWithdrawal>>> {
    return this.indexGet(chainId, 'bridge/withdrawals', { limit, ...(cursor ? { cursor } : {}) });
  }

  lineage(
    chainId: string,
    transactionId: string,
    outputIndex: number,
    depth = 2
  ): Promise<EutxoIndexEnvelope<EutxoLineage>> {
    return this.indexGet(
      chainId,
      `lineage/outpoints/${segment(transactionId)}/${outputIndex}`,
      { depth }
    );
  }

  validityBatches(
    chainId: string,
    limit = 25,
    cursor = ''
  ): Promise<EutxoIndexEnvelope<EutxoIndexPage<EutxoValidityBatch>>> {
    return this.indexGet(chainId, 'validity/batches', { limit, ...(cursor ? { cursor } : {}) });
  }

  anchorCommitment(chainId: string): Promise<AnchorCommitment> {
    return this.get(`/app-chain/chains/${segment(chainId)}/anchor/commitment`);
  }

  proofPackage(chainId: string, messageId: string): Promise<unknown> {
    return this.get(
      `/app-chain/chains/${segment(chainId)}/messages/${segment(messageId)}/proof-package`
    );
  }

  l1Transaction(transactionId: string): Promise<L1Transaction> {
    return this.get(`/txs/${segment(transactionId)}`);
  }

  l1TransactionUtxos(transactionId: string): Promise<L1TransactionUtxos> {
    return this.get(`/txs/${segment(transactionId)}/utxos`);
  }

  private indexGet<T>(
    chainId: string,
    path: string,
    query: Record<string, string | number> = {}
  ): Promise<T> {
    return this.pluginGet(EUTXO_INDEX_BUNDLE_ID, `index/v1/${path}`, {
      chain: chainId,
      ...query
    });
  }

  private pluginGet<T>(
    bundleId: string,
    path: string,
    query: Record<string, string | number> = {}
  ): Promise<T> {
    return this.get(`/plugins/${segment(bundleId)}/${path}`, query);
  }

  private pluginPost<T>(bundleId: string, path: string, body: unknown): Promise<T> {
    return this.post(`/plugins/${segment(bundleId)}/${path}`, body);
  }

  private get<T>(path: string, query: Record<string, string | number> = {}): Promise<T> {
    const suffix = new URLSearchParams(
      Object.entries(query).map(([key, value]) => [key, String(value)])
    ).toString();
    return this.request(`${path}${suffix ? `?${suffix}` : ''}`);
  }

  private post<T>(path: string, body: unknown): Promise<T> {
    return this.request(path, {
      method: 'POST',
      body: JSON.stringify(body),
      headers: { 'Content-Type': 'application/json' }
    });
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    const headers = new Headers(init.headers);
    headers.set('Accept', 'application/json');
    if (this.connection.apiKey) headers.set('X-API-Key', this.connection.apiKey);
    let response: Response;
    try {
      response = await this.fetcher(`${this.connection.apiBase}${path}`, {
        ...init,
        headers,
        signal: controller.signal,
        credentials: 'omit',
        redirect: 'error',
        referrerPolicy: 'no-referrer'
      });
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw new ApiError('The Yano node did not respond within 20 seconds', 0, 'TIMEOUT', path);
      }
      throw new ApiError(
        'The Yano node could not be reached. Check its URL, TLS certificate, and CORS policy.',
        0,
        'NETWORK_ERROR',
        path
      );
    } finally {
      clearTimeout(timeout);
    }
    const text = await response.text();
    if (!response.ok) {
      const failure = parseFailure(text);
      throw new ApiError(failure.message, response.status, failure.code, path);
    }
    if (!text) return undefined as T;
    try {
      return JSON.parse(text) as T;
    } catch {
      throw new ApiError('The Yano node returned an invalid JSON response', response.status, '', path);
    }
  }
}

function segment(value: string): string {
  return encodeURIComponent(value);
}

function parseFailure(text: string): { message: string; code: string } {
  try {
    const value = JSON.parse(text) as Record<string, unknown>;
    const message = typeof value.error === 'string'
      ? value.error
      : typeof value.message === 'string'
        ? value.message
        : 'The Yano request failed';
    return { message, code: typeof value.code === 'string' ? value.code : '' };
  } catch {
    return { message: text.slice(0, 500) || 'The Yano request failed', code: '' };
  }
}
