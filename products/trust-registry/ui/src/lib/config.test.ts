import { describe, expect, it } from 'vitest';
import {
  createConnection,
  normalizeApiPrefix,
  normalizeNodeUrl,
  normalizeServiceUrl,
  parseRuntimeConfig,
  resolveConnectionDefaults
} from './config';

describe('runtime connection configuration', () => {
  it('accepts portable HTTPS node origins', () => {
    const connection = createConnection('https://node.example.com/', '/api/v1/', 'secret');
    expect(connection.apiBase).toBe('https://node.example.com/api/v1');
    expect(connection.apiKey).toBe('secret');
  });

  it('allows HTTP only for local development', () => {
    expect(normalizeNodeUrl('http://127.0.0.1:7270')).toBe('http://127.0.0.1:7270');
    expect(() => normalizeNodeUrl('http://node.example.com')).toThrow('Use HTTPS');
    expect(() => normalizeNodeUrl('https://node.example.com/api/v1')).toThrow('origin');
    expect(() => normalizeNodeUrl('https://user:pw@node.example.com')).toThrow('credentials');
  });

  it('normalizes service URLs with an optional path', () => {
    expect(normalizeServiceUrl('http://127.0.0.1:8480/')).toBe('http://127.0.0.1:8480');
    expect(normalizeServiceUrl('https://registry.example.com/trust/')).toBe('https://registry.example.com/trust');
    expect(() => normalizeServiceUrl('http://registry.example.com')).toThrow('Use HTTPS');
  });

  it('provides useful local defaults with optional development overrides', () => {
    expect(resolveConnectionDefaults({ DEV: true })).toEqual({
      nodeUrl: 'http://localhost:7070',
      apiPrefix: '/api/v1',
      apiKey: ''
    });
    expect(resolveConnectionDefaults({ DEV: false, VITE_YANO_API_KEY: 'leak' }).apiKey).toBe('');
  });

  it('normalizes API prefixes', () => {
    expect(normalizeApiPrefix('/api/v1/')).toBe('/api/v1');
    expect(() => normalizeApiPrefix('api/v1')).toThrow('canonical');
  });

  it('parses the runtime configuration strictly', () => {
    const config = parseRuntimeConfig({
      schemaVersion: 1,
      productId: 'trust-registry',
      endpoints: [{ id: 'live', nodeUrl: 'http://127.0.0.1:7270', apiPrefix: '/api/v1', label: 'Live' }],
      defaultChainId: 'trust-registry-chain',
      expectedNetwork: 'DevNet',
      allowEndpointOverride: false,
      serviceUrl: 'http://127.0.0.1:8480'
    });
    expect(config.endpoints[0].nodeUrl).toBe('http://127.0.0.1:7270');
    expect(config.expectedNetwork).toBe('devnet');
    expect(config.serviceUrl).toBe('http://127.0.0.1:8480');
    expect(() => parseRuntimeConfig({ schemaVersion: 1, productId: 'evidence', endpoints: [] }))
      .toThrow('identity');
    expect(() => parseRuntimeConfig({
      schemaVersion: 1, productId: 'trust-registry', endpoints: [], defaultChainId: '',
      expectedNetwork: '', allowEndpointOverride: true, serviceUrl: 'http://registry.example.com'
    })).toThrow('Use HTTPS');
  });
});
