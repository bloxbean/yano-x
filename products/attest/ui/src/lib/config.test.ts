import { describe, expect, it } from 'vitest';
import {
  createConnection,
  normalizeApiPrefix,
  normalizeNodeUrl,
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
    expect(normalizeNodeUrl('http://127.0.0.1:7070')).toBe('http://127.0.0.1:7070');
    expect(() => normalizeNodeUrl('http://node.example.com')).toThrow('Use HTTPS');
    expect(() => normalizeNodeUrl('https://node.example.com/api/v1')).toThrow('origin');
    expect(() => normalizeNodeUrl('https://user:pw@node.example.com')).toThrow('credentials');
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
    expect(normalizeApiPrefix('/custom/api')).toBe('/custom/api');
    expect(() => normalizeApiPrefix('')).toThrow('canonical');
    expect(() => normalizeApiPrefix('api/v1')).toThrow('canonical');
  });

  it('parses the attest runtime configuration strictly', () => {
    const parsed = parseRuntimeConfig({
      schemaVersion: 1, productId: 'attest', allowEndpointOverride: false,
      defaultChainId: 'documents', expectedNetwork: 'Preprod',
      endpoints: [{ id: 'primary', nodeUrl: 'https://node.example.com', apiPrefix: '/api/v1', label: 'Docs' }]
    });
    expect(parsed.expectedNetwork).toBe('preprod');
    expect(parsed.endpoints[0].label).toBe('Docs');
    expect(() => parseRuntimeConfig({ schemaVersion: 1, productId: 'eutxo', endpoints: [] })).toThrow('identity');
    expect(() => parseRuntimeConfig({
      schemaVersion: 1, productId: 'attest', endpoints: [], defaultChainId: '', expectedNetwork: '',
      allowEndpointOverride: 'yes'
    })).toThrow('boolean');
  });
});
