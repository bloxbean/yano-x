import type { CertificationRequestDocument, GatewayActor, PassportDocument, PassportView, ReceiptDocument } from './types';

const REQUEST_TIMEOUT_MS = 30_000;
const MAX_RESPONSE_BYTES = 48 * 1024 * 1024;
export const TOKEN_HEADER = 'X-Gateway-Token';

export class ApiError extends Error {
  constructor(message: string, readonly status: number, readonly path = '') {
    super(message);
    this.name = 'ApiError';
  }
}

/** The public portal (ADR-051 §2.4): read-only, no secret. */
export class PortalApi {
  constructor(readonly baseUrl: string, private readonly fetcher: typeof fetch = fetch) {}

  health(): Promise<{ chainId: string; tipHeight: number; replayedHeight: number; prototype?: string }> {
    return request(this.fetcher, `${this.baseUrl}/healthz`, {});
  }

  passport(productId: string, height?: number): Promise<PassportView> {
    return request(this.fetcher, `${this.baseUrl}/passports/${encodeURIComponent(productId)}${heightQuery(height)}`, {});
  }

  proof(productId: string, height?: number): Promise<PassportDocument> {
    return request(this.fetcher, `${this.baseUrl}/passports/${encodeURIComponent(productId)}/proof${heightQuery(height)}`, {});
  }

  documentUrl(sha256: string): string {
    return `${this.baseUrl}/documents/${sha256}`;
  }
}

/** The operator gateway (ADR-051 §2.4); the token is held in memory by the caller. */
export class GatewayApi {
  constructor(readonly baseUrl: string, private readonly token: string, private readonly fetcher: typeof fetch = fetch) {}

  actors(): Promise<{ chainId: string; actors: GatewayActor[] }> {
    return request(this.fetcher, `${this.baseUrl}/operator/actors`, { headers: { [TOKEN_HEADER]: this.token } });
  }

  write(route: string, body: Record<string, unknown>): Promise<ReceiptDocument> {
    return this.post(route, body);
  }

  propose(body: Record<string, unknown>): Promise<{ proposalId: string; messageId: string; request: CertificationRequestDocument }> {
    return this.post('/operator/certifications/propose', body);
  }

  decide(approve: boolean, actorId: string, request: CertificationRequestDocument): Promise<{ messageId: string; decision: string; proposalId: string }> {
    return this.post(approve ? '/operator/certifications/approve' : '/operator/certifications/reject', { actorId, request });
  }

  apply(request: CertificationRequestDocument): Promise<ReceiptDocument> {
    return this.post('/operator/certifications/apply', { request });
  }

  private post<T>(route: string, body: Record<string, unknown>): Promise<T> {
    return request(this.fetcher, `${this.baseUrl}${route}`, {
      method: 'POST',
      headers: { [TOKEN_HEADER]: this.token, 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
  }
}

function heightQuery(height?: number): string {
  return height && height > 0 ? `?height=${height}` : '';
}

async function request<T>(fetcher: typeof fetch, url: string, init: RequestInit): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const response = await fetcher(url, {
      ...init,
      headers: { Accept: 'application/json', ...(init.headers ?? {}) },
      cache: 'no-store',
      credentials: 'omit',
      redirect: 'error',
      signal: controller.signal
    });
    const length = Number(response.headers.get('content-length') ?? 0);
    if (length > MAX_RESPONSE_BYTES) throw new ApiError('The response is too large', response.status, url);
    const text = await response.text();
    if (text.length > MAX_RESPONSE_BYTES) throw new ApiError('The response is too large', response.status, url);
    let parsed: unknown = null;
    try {
      parsed = text ? JSON.parse(text) : null;
    } catch {
      throw new ApiError('The response is not JSON', response.status, url);
    }
    if (!response.ok) {
      const message = parsed && typeof parsed === 'object' && 'error' in parsed
        ? String((parsed as { error: unknown }).error) : `Request failed with HTTP ${response.status}`;
      throw new ApiError(message, response.status, url);
    }
    return parsed as T;
  } catch (cause) {
    if (cause instanceof ApiError) throw cause;
    if (cause instanceof DOMException && cause.name === 'AbortError') {
      throw new ApiError('The request timed out', 0, url);
    }
    throw new ApiError(cause instanceof Error ? cause.message : 'The request failed', 0, url);
  } finally {
    clearTimeout(timer);
  }
}
