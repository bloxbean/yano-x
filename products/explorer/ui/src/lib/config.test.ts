import { describe, expect, it } from 'vitest';
import { createConnection, normalizeServiceUrl, parseRuntimeConfig, resolveServiceDefault } from './config';

describe('runtime configuration', () => {
  it('accepts the shipped default file', () => {
    const config = parseRuntimeConfig({
      schemaVersion: 1, productId: 'explorer', serviceUrl: '', endpoints: [], defaultChainId: '', allowServiceOverride: true
    });
    expect(config.serviceUrl).toBe('');
    expect(config.allowServiceOverride).toBe(true);
  });

  it('rejects another product identity', () => {
    expect(() => parseRuntimeConfig({ schemaVersion: 1, productId: 'attest' })).toThrow(/identity/);
  });

  it('normalizes service URLs and refuses credentials', () => {
    expect(normalizeServiceUrl('http://localhost:8490/', 'http://localhost')).toBe('http://localhost:8490');
    expect(normalizeServiceUrl('https://explorer.example.com/base/', 'http://localhost')).toBe('https://explorer.example.com/base');
    expect(() => normalizeServiceUrl('http://explorer.example.com', 'http://localhost')).toThrow(/HTTPS/);
    expect(() => normalizeServiceUrl('https://user:pw@explorer.example.com', 'http://localhost')).toThrow(/credentials/);
  });

  it('builds a connection from a configured endpoint', () => {
    const config = parseRuntimeConfig({
      schemaVersion: 1, productId: 'explorer',
      endpoints: [{ id: 'local', serviceUrl: 'http://127.0.0.1:8490', label: 'Local' }]
    });
    expect(config.endpoints[0].serviceUrl).toBe('http://127.0.0.1:8490');
    expect(createConnection('http://127.0.0.1:8490').serviceUrl).toBe('http://127.0.0.1:8490');
    expect(resolveServiceDefault({})).toBe('http://localhost:8490');
  });
});
