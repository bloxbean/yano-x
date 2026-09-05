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
  });

  it('provides useful local defaults with optional development overrides', () => {
    expect(resolveConnectionDefaults({ DEV: true })).toEqual({
      nodeUrl: 'http://localhost:7070',
      apiPrefix: '/api/v1',
      apiKey: ''
    });
    expect(resolveConnectionDefaults({
      DEV: true,
      VITE_YANO_NODE_URL: ' http://127.0.0.1:7170 ',
      VITE_YANO_API_PREFIX: ' /custom/api ',
      VITE_YANO_API_KEY: 'local-only'
    })).toEqual({
      nodeUrl: 'http://127.0.0.1:7170',
      apiPrefix: '/custom/api',
      apiKey: 'local-only'
    });
  });

  it('never embeds an environment API key in a production build', () => {
    expect(resolveConnectionDefaults({ DEV: false, VITE_YANO_API_KEY: 'do-not-publish' }).apiKey)
      .toBe('');
  });

  it('rejects URL-carried credentials and endpoint redirection', () => {
    expect(() => normalizeNodeUrl('https://user:pass@node.example.com')).toThrow('credentials');
    expect(() => normalizeNodeUrl('https://node.example.com?target=other')).toThrow('query');
    expect(() => normalizeNodeUrl('https://node.example.com/api/v1')).toThrow('origin');
  });

  it('parses a deployment-supplied endpoint catalog', () => {
    const parsed = parseRuntimeConfig({
      schemaVersion: 1,
      productId: 'eutxo',
      endpoints: [{ id: 'primary', nodeUrl: 'https://node.example.com', apiPrefix: '/api/v1' }],
      defaultChainId: 'payments',
      expectedNetwork: 'PREPROD',
      allowEndpointOverride: true
    });
    expect(parsed.expectedNetwork).toBe('preprod');
    expect(parsed.endpoints[0].nodeUrl).toBe('https://node.example.com');
    expect(normalizeApiPrefix('/custom/api/')).toBe('/custom/api');
  });
});
