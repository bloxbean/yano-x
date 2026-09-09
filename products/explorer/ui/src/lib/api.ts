import type {
  ActiveConnection,
  BlockDetail,
  BlockPage,
  ChainView,
  ContentMeta,
  MessageView,
  RowBundle,
  SearchHit,
  StateBundle,
  SubjectSummary,
  SubjectView
} from './types';

const REQUEST_TIMEOUT_MS = 20_000;
const MAX_RESPONSE_BYTES = 48 * 1024 * 1024;

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly path = ''
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** The explorer service routes the console reads (ADR-050 §2.4); GET only, nothing is written. */
export class ExplorerApi {
  constructor(
    readonly connection: ActiveConnection,
    private readonly fetcher: typeof fetch = fetch
  ) {}

  chains(): Promise<ChainView[]> {
    return this.get('/chains');
  }

  chain(chainId: string): Promise<ChainView> {
    return this.get(chainPath(chainId, ''));
  }

  blocks(chainId: string, from = 0, limit = 20): Promise<BlockPage> {
    return this.get(chainPath(chainId, `/blocks?from=${encodeURIComponent(String(from))}&limit=${encodeURIComponent(String(limit))}`));
  }

  block(chainId: string, height: number): Promise<BlockDetail> {
    return this.get(chainPath(chainId, `/blocks/${encodeURIComponent(String(height))}`));
  }

  message(chainId: string, messageId: string): Promise<MessageView> {
    return this.get(chainPath(chainId, `/messages/${segment(messageId)}`));
  }

  rowProof(chainId: string, messageId: string): Promise<RowBundle> {
    return this.get(chainPath(chainId, `/messages/${segment(messageId)}/proof`));
  }

  search(chainId: string, query: string): Promise<SearchHit[]> {
    return this.get(chainPath(chainId, `/search?q=${encodeURIComponent(query)}`));
  }

  subjects(chainId: string, module = '', prefix = '', limit = 50): Promise<SubjectSummary[]> {
    return this.get(chainPath(chainId, `/subjects?module=${encodeURIComponent(module)}&prefix=${encodeURIComponent(prefix)}&limit=${limit}`));
  }

  subject(chainId: string, module: string, subject: string, check = true): Promise<SubjectView> {
    return this.get(chainPath(chainId, `/subjects/${segment(module)}/${segment(subject)}?check=${check}`));
  }

  stateProof(chainId: string, module: string, subject: string, height?: number): Promise<StateBundle> {
    const suffix = height ? `?height=${encodeURIComponent(String(height))}` : '';
    return this.get(chainPath(chainId, `/subjects/${segment(module)}/${segment(subject)}/proof${suffix}`));
  }

  contentMeta(chainId: string, sha256: string): Promise<ContentMeta> {
    return this.get(chainPath(chainId, `/content/${segment(sha256)}?meta=1`));
  }

  contentUrl(chainId: string, sha256: string): string {
    return `${this.connection.serviceUrl}${chainPath(chainId, `/content/${segment(sha256)}`)}`;
  }

  private async get<T>(path: string): Promise<T> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      const response = await this.fetcher(`${this.connection.serviceUrl}${path}`, {
        method: 'GET',
        headers: { Accept: 'application/json' },
        cache: 'no-store',
        credentials: 'omit',
        redirect: 'error',
        signal: controller.signal
      });
      const length = Number(response.headers.get('content-length') ?? '0');
      if (length > MAX_RESPONSE_BYTES) throw new ApiError('The service response is too large', response.status, path);
      const text = await response.text();
      if (text.length > MAX_RESPONSE_BYTES) throw new ApiError('The service response is too large', response.status, path);
      let body: unknown = null;
      try {
        body = text ? JSON.parse(text) : null;
      } catch {
        throw new ApiError('The service returned malformed JSON', response.status, path);
      }
      if (!response.ok) {
        const detail = isRecord(body) && typeof body.error === 'string' ? body.error : `HTTP ${response.status}`;
        throw new ApiError(detail, response.status, path);
      }
      return body as T;
    } catch (cause) {
      if (cause instanceof ApiError) throw cause;
      if (cause instanceof DOMException && cause.name === 'AbortError') {
        throw new ApiError('The service did not respond in time', 0, path);
      }
      throw new ApiError(cause instanceof Error ? cause.message : 'The service is unreachable', 0, path);
    } finally {
      clearTimeout(timer);
    }
  }
}

function chainPath(chainId: string, suffix: string): string {
  return `/chains/${segment(chainId)}${suffix}`;
}

export function segment(value: string): string {
  if (!value || value.length > 512 || value.includes('/') || value.includes('\\')) {
    throw new ApiError('Invalid path segment', 0);
  }
  return encodeURIComponent(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
