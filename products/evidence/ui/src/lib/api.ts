import { selectAdapter } from './adapters';
import type {
  ActiveConnection,
  AppChainStatus,
  BlockPage,
  ChainSummary,
  DiscoveredChain,
  FinalizedBlock,
  FinalizedMessage,
  MessageSubmitResult,
  NodeConfig,
  NodeStatus,
  StateProofEnvelope
} from './types';

const REQUEST_TIMEOUT_MS = 20_000;
const MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

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
    return this.get(chainPath(chainId, '/status'));
  }

  /** Chains whose composite carries both role components, tagged with their release adapter. */
  async discoverChains(): Promise<DiscoveredChain[]> {
    const summaries = await this.chains();
    const discovered = await Promise.all(summaries.map(async (summary) => {
      const status = await this.chainStatus(summary.chainId);
      const adapter = selectAdapter(status);
      return adapter ? { summary, status, adapter } satisfies DiscoveredChain : null;
    }));
    return discovered.filter((chain): chain is DiscoveredChain => chain !== null);
  }

  submitMessage(chainId: string, topic: string, bodyHex: string): Promise<MessageSubmitResult> {
    return this.post(chainPath(chainId, '/messages'), { topic, bodyHex });
  }

  /** Finalized position of a message, or null while it is still pending. */
  finalizedMessage(chainId: string, messageId: string): Promise<FinalizedMessage | null> {
    return this.optional(chainPath(chainId, `/messages/${segment(messageId)}`));
  }

  /**
   * Height-pinned or latest state proof for a physical key; null when the node retains none.
   * Every record the desk shows is read this way (ADR-048 §3), never through the query router.
   */
  stateProof(chainId: string, keyHex: string, height?: number): Promise<StateProofEnvelope | null> {
    const suffix = height === undefined ? '' : `?height=${height}`;
    return this.optional(chainPath(chainId, `/state/proof/${segment(keyHex)}${suffix}`));
  }

  block(chainId: string, height: number): Promise<FinalizedBlock | null> {
    return this.optional(chainPath(chainId, `/blocks/${height}`));
  }

  blocks(chainId: string, from: number, limit: number): Promise<BlockPage> {
    return this.get(chainPath(chainId, `/blocks?from=${from}&limit=${limit}`));
  }

  private async optional<T>(path: string): Promise<T | null> {
    try {
      return await this.get<T>(path);
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null;
      throw error;
    }
  }

  private get<T>(path: string): Promise<T> {
    return this.request(path);
  }

  private post<T>(path: string, body: unknown): Promise<T> {
    return this.request(path, {
      method: 'POST',
      body: JSON.stringify(body),
      headers: { 'Content-Type': 'application/json' }
    });
  }

  private async request<T>(path: string, init: RequestInit = {}, maxBytes = MAX_RESPONSE_BYTES): Promise<T> {
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
    if (text.length > maxBytes) {
      throw new ApiError('The Yano node returned an oversized response', response.status, 'TOO_LARGE', path);
    }
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

function chainPath(chainId: string, suffix: string): string {
  return `/app-chain/chains/${segment(chainId)}${suffix}`;
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
