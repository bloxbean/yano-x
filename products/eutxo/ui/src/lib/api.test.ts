import { describe, expect, it, vi } from 'vitest';
import { YanoApi } from './api';
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

describe('Yano EUTxO API client', () => {
  it('uses the manifested bridge route and scoped API key', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(json({
      chainId: 'payments', vaultAddress: 'vault', vaultScriptHash: 'hash',
      withdrawalAddress: 'withdrawal', bridgeEpoch: 0, maxDepositLovelace: 20_000_000,
      withdrawalsPaused: false, stabilityDepth: 10
    }));
    const api = new YanoApi(connection, fetcher);
    await api.bridgeInfo('payments');
    expect(fetcher).toHaveBeenCalledOnce();
    const [url, init] = fetcher.mock.calls[0];
    expect(url).toBe(
      'https://node.example.com/api/v1/plugins/'
      + 'org.yanoproject.x.eutxo.bridge.cardano/'
      + 'chains/payments/bridge/info'
    );
    expect(new Headers(init?.headers).get('X-API-Key')).toBe('memory-only');
  });

  it('discovers only capability-manifested EUTxO chains', async () => {
    const fetcher = vi.fn<typeof fetch>().mockImplementation(async (input) => {
      const url = String(input);
      if (url.endsWith('/app-chain/chains')) {
        return json([
          { chainId: 'payments', tipHeight: 8, stateRoot: 'aa'.repeat(32) },
          { chainId: 'orders', tipHeight: 9, stateRoot: 'bb'.repeat(32) }
        ]);
      }
      if (url.endsWith('/chains/payments/status')) {
        return json({ capabilityManifest: {
          schemaVersion: 1, applicationId: 'payments', applicationVersion: '1',
          manifestDigest: 'cc', components: [{ id: 'eutxo-ledger', version: '1' }]
        }});
      }
      if (url.endsWith('/chains/orders/status')) {
        return json({ capabilityManifest: {
          schemaVersion: 1, applicationId: 'orders', applicationVersion: '1',
          manifestDigest: 'dd', components: [{ id: 'ordered-log', version: '1' }]
        }});
      }
      return json({
        chainId: 'payments', vaultAddress: 'vault', vaultScriptHash: 'hash',
        withdrawalAddress: 'withdrawal', bridgeEpoch: 0, maxDepositLovelace: 20_000_000,
        withdrawalsPaused: false, stabilityDepth: 10
      });
    });
    const result = await new YanoApi(connection, fetcher).discoverChains();
    expect(result.map((chain) => chain.summary.chainId)).toEqual(['payments']);
    expect(result[0].bridgeState).toBe('available');
  });

  it('pins build, assembly, and submission to the selected endpoint', async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(json({
        chainId: 'payments', unsignedTxCborHex: '01', transactionId: 'aa'.repeat(32),
        fromAddress: 'from', toAddress: 'to', lovelace: 1_000_000, submitTopic: 'eutxo.tx'
      }))
      .mockResolvedValueOnce(json({ signedTxCborHex: '02', transactionId: 'aa'.repeat(32) }))
      .mockResolvedValueOnce(json({ messageId: 'bb'.repeat(32), chainId: 'payments', topic: 'eutxo.tx' }));
    const api = new YanoApi(connection, fetcher);
    const built = await api.buildTransfer('payments', 'from', 'to', 1_000_000);
    const signed = await api.assembleDeposit('payments', built.unsignedTxCborHex, 'witness');
    await api.submitL2Transaction('payments', built.submitTopic, signed.signedTxCborHex);
    expect(fetcher.mock.calls.map(([url]) => String(url))).toEqual([
      expect.stringContaining('/chains/payments/bridge/transfer/build'),
      expect.stringContaining('/chains/payments/bridge/deposit/assemble'),
      'https://node.example.com/api/v1/app-chain/chains/payments/messages'
    ]);
  });
});
