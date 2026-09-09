import { describe, expect, it, vi } from 'vitest';
import { ApiError, YanoApi } from './api';
import type { ActiveConnection } from './types';

const connection: ActiveConnection = {
  endpointId: 'test',
  nodeUrl: 'https://node.example.com',
  apiPrefix: '/api/v1',
  apiBase: 'https://node.example.com/api/v1',
  apiKey: 'memory-only',
  label: 'Test node'
};

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' }
  });
}

describe('Yano Attest API client', () => {
  it('discovers only chains that run doc-trail', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (input) => {
      const url = String(input);
      if (url.endsWith('/app-chain/chains')) {
        return json([
          { chainId: 'documents', tipHeight: 8, stateRoot: 'aa'.repeat(32) },
          { chainId: 'orders', tipHeight: 9, stateRoot: 'bb'.repeat(32) },
          { chainId: 'standalone', tipHeight: 3, stateRoot: 'cc'.repeat(32) },
          { chainId: 'composite', tipHeight: 4, stateRoot: 'dd'.repeat(32) }
        ]);
      }
      if (url.endsWith('/chains/documents/status')) return json({ stateMachine: 'doc-trail' });
      if (url.endsWith('/chains/orders/status')) return json({ stateMachine: 'ordered-log' });
      if (url.endsWith('/chains/standalone/status')) {
        return json({ capabilityManifest: {
          schemaVersion: 1, applicationId: 'doc-trail', applicationVersion: '1', manifestDigest: 'd',
          components: [{ id: 'doc-trail', version: '1', stateNamespace: 'application/v1' }]
        }});
      }
      return json({ stateMachine: 'composite', capabilityManifest: {
        schemaVersion: 1, applicationId: 'x', applicationVersion: '1', manifestDigest: 'd',
        components: [{ id: 'doc-trail', version: '1', stateNamespace: 'documents/v1' }]
      }});
    });
    const result = await new YanoApi(connection, fetcher).discoverChains();
    expect(result.map((chain) => chain.summary.chainId)).toEqual(['documents', 'standalone']);
  });

  it('submits canonical appends with the scoped API key', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(json(
      { messageId: 'ab'.repeat(32), chainId: 'documents', topic: 'doc-trail.command.v1' }, 202));
    const api = new YanoApi(connection, fetcher);
    const result = await api.submitMessage('documents', 'doc-trail.command.v1', '8300');
    expect(result.messageId).toBe('ab'.repeat(32));
    const [url, init] = fetcher.mock.calls[0];
    expect(url).toBe('https://node.example.com/api/v1/app-chain/chains/documents/messages');
    expect(init?.method).toBe('POST');
    expect(init?.body).toBe(JSON.stringify({ topic: 'doc-trail.command.v1', bodyHex: '8300' }));
    expect(new Headers(init?.headers).get('X-API-Key')).toBe('memory-only');
    expect(init?.credentials).toBe('omit');
  });

  it('treats 404 as pending for finalized message, state proof, and anchor lookups', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => json({ error: 'No finalized message' }, 404));
    const api = new YanoApi(connection, fetcher);
    expect(await api.finalizedMessage('documents', 'ab'.repeat(32))).toBeNull();
    expect(await api.stateProof('documents', '652f', 3)).toBeNull();
    expect(await api.anchorCommitment('documents')).toBeNull();
    expect(String(fetcher.mock.calls[1][0])).toBe(
      'https://node.example.com/api/v1/app-chain/chains/documents/state/proof/652f?height=3');
  });

  it('surfaces other failures as ApiError with the node message', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(json({ error: 'chain unavailable', code: 'DOWN' }, 503));
    await expect(new YanoApi(connection, fetcher).chainStatus('documents')).rejects.toMatchObject({
      name: 'ApiError', status: 503, code: 'DOWN', message: 'chain unavailable'
    } satisfies Partial<ApiError>);
  });

  it('rejects invalid JSON bodies', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response('<html>', { status: 200 }));
    await expect(new YanoApi(connection, fetcher).nodeStatus()).rejects.toThrow('invalid JSON');
  });
});
