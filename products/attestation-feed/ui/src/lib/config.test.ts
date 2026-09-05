import { describe, expect, it } from 'vitest';
import { normalizeServiceUrl, parseRuntimeConfig, resolveConnectionDefaults } from './config';

describe('runtime configuration', () => {
  it('normalizes portal and gateway URLs', () => {
    expect(normalizeServiceUrl('http://127.0.0.1:8680/')).toBe('http://127.0.0.1:8680');
    expect(normalizeServiceUrl('https://feeds.example.com/feed/')).toBe('https://feeds.example.com/feed');
    expect(() => normalizeServiceUrl('http://feeds.example.com')).toThrow('Use HTTPS');
    expect(() => normalizeServiceUrl('https://user:pw@feeds.example.com')).toThrow('credentials');
    expect(() => normalizeServiceUrl('   ', 'gateway')).toThrow('gateway');
  });

  it('parses the runtime configuration and refuses foreign identities', () => {
    const config = parseRuntimeConfig({
      schemaVersion: 1, productId: 'attestation-feed', serviceUrl: 'https://feeds.example.com',
      gatewayUrl: '', allowServiceOverride: false
    });
    expect(config.serviceUrl).toBe('https://feeds.example.com');
    expect(config.allowServiceOverride).toBe(false);
    expect(() => parseRuntimeConfig({ schemaVersion: 1, productId: 'dpp' })).toThrow('identity');
    expect(() => parseRuntimeConfig({ schemaVersion: 1, productId: 'attestation-feed', allowServiceOverride: 'yes' }))
      .toThrow('allowServiceOverride');
    expect(() => parseRuntimeConfig({ schemaVersion: 1, productId: 'attestation-feed', serviceUrl: 'http://x.example', allowServiceOverride: true }))
      .toThrow('Use HTTPS');
  });

  it('provides local development defaults', () => {
    expect(resolveConnectionDefaults({ DEV: true })).toEqual({
      serviceUrl: 'http://localhost:8680',
      gatewayUrl: 'http://localhost:8690'
    });
    expect(resolveConnectionDefaults({ VITE_FEED_SERVICE_URL: 'https://p.example' }).serviceUrl).toBe('https://p.example');
  });
});
