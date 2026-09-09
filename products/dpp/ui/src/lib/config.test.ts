import { describe, expect, it } from 'vitest';
import { normalizeServiceUrl, parseRuntimeConfig, resolveConnectionDefaults } from './config';

describe('runtime configuration', () => {
  it('normalizes portal and gateway URLs', () => {
    expect(normalizeServiceUrl('http://127.0.0.1:8580/')).toBe('http://127.0.0.1:8580');
    expect(normalizeServiceUrl('https://passports.example.com/dpp/')).toBe('https://passports.example.com/dpp');
    expect(() => normalizeServiceUrl('http://passports.example.com')).toThrow('Use HTTPS');
    expect(() => normalizeServiceUrl('https://user:pw@passports.example.com')).toThrow('credentials');
    expect(() => normalizeServiceUrl('   ', 'gateway')).toThrow('gateway');
  });

  it('parses the runtime configuration and refuses foreign identities', () => {
    const config = parseRuntimeConfig({
      schemaVersion: 1, productId: 'dpp', serviceUrl: 'https://passports.example.com',
      gatewayUrl: '', allowServiceOverride: false
    });
    expect(config.serviceUrl).toBe('https://passports.example.com');
    expect(config.allowServiceOverride).toBe(false);
    expect(() => parseRuntimeConfig({ schemaVersion: 1, productId: 'trust-registry' })).toThrow('identity');
    expect(() => parseRuntimeConfig({ schemaVersion: 1, productId: 'dpp', allowServiceOverride: 'yes' }))
      .toThrow('allowServiceOverride');
    expect(() => parseRuntimeConfig({ schemaVersion: 1, productId: 'dpp', serviceUrl: 'http://x.example', allowServiceOverride: true }))
      .toThrow('Use HTTPS');
  });

  it('provides local development defaults', () => {
    expect(resolveConnectionDefaults({ DEV: true })).toEqual({
      serviceUrl: 'http://localhost:8580',
      gatewayUrl: 'http://localhost:8590'
    });
    expect(resolveConnectionDefaults({ VITE_DPP_SERVICE_URL: 'https://p.example' }).serviceUrl).toBe('https://p.example');
  });
});
