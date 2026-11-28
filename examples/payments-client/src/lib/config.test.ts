import { describe, expect, it } from 'vitest';
import { requestHeaders } from './api';
import { resolveConfig } from './config';

const page = 'https://pay.example/app/?x=1';
const deployed = { nodeUrl: 'https://node.example/api/v1/', apiKey: 'secret', devkitWalletSdkUrl: '' };

describe('client configuration', () => {
  it('reads the node and the DevKit SDK only from the deployed file outside the dev server', () => {
    const config = resolveConfig(deployed, '?node=https://evil.example/api&devkit=https://evil.example/x.js&chain=c2',
      page, false);
    expect(config.nodeUrl).toBe('https://node.example/api/v1');
    expect(config.devkitWalletSdkUrl).toBe('');
    expect(config.apiKey).toBe('secret');
    expect(config.chainId).toBe('c2');
  });

  it('honours URL overrides on the dev server, but never sends the key to another node', () => {
    const other = resolveConfig(deployed, '?node=https://evil.example/api&devkit=http://localhost:10000/w.js', page,
      true);
    expect(other.nodeUrl).toBe('https://evil.example/api');
    expect(other.devkitWalletSdkUrl).toBe('http://localhost:10000/w.js');
    expect(other.apiKey).toBe('');
    expect(resolveConfig(deployed, '?node=https://node.example/api/v1', page, true).apiKey).toBe('secret');
  });

  it('ignores non-string values and keeps the defaults', () => {
    const config = resolveConfig({ nodeUrl: 7 as unknown as string }, '', page, false);
    expect(config.nodeUrl).toBe('/api/v1');
    expect(config.maxDepositFeeLovelace).toBe('2000000');
  });

  it('attaches the API key only to the configured node origin', () => {
    expect(requestHeaders('https://node.example/api/v1/x', 'https://node.example', 'k', true)['X-API-Key']).toBe('k');
    expect(requestHeaders('https://evil.example/x', 'https://node.example', 'k', false)['X-API-Key']).toBeUndefined();
    expect(requestHeaders('https://node.example/x', 'https://node.example', '', false)['X-API-Key']).toBeUndefined();
  });
});
