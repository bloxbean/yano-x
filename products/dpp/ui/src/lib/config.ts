import type { RuntimeConfig } from './types';

const MAX_TEXT = 256;
export const RUNTIME_CONFIG_FILE = 'dpp-ui-config.json';

export interface ConnectionDefaults {
  serviceUrl: string;
  gatewayUrl: string;
}

type PublicEnvironment = {
  DEV?: boolean;
  VITE_DPP_SERVICE_URL?: string;
  VITE_DPP_GATEWAY_URL?: string;
};

export function resolveConnectionDefaults(environment: PublicEnvironment): ConnectionDefaults {
  return {
    serviceUrl: environment.VITE_DPP_SERVICE_URL?.trim() || 'http://localhost:8580',
    gatewayUrl: environment.VITE_DPP_GATEWAY_URL?.trim() || 'http://localhost:8590'
  };
}

export const CONNECTION_DEFAULTS = resolveConnectionDefaults(import.meta.env);

export const DEFAULT_RUNTIME_CONFIG: RuntimeConfig = {
  schemaVersion: 1,
  productId: 'dpp',
  serviceUrl: '',
  gatewayUrl: '',
  allowServiceOverride: true
};

export async function loadRuntimeConfig(fetcher: typeof fetch = fetch): Promise<RuntimeConfig> {
  try {
    const url = new URL(RUNTIME_CONFIG_FILE, document.baseURI);
    const response = await fetcher(url, {
      cache: 'no-store',
      credentials: 'same-origin',
      redirect: 'error',
      headers: { Accept: 'application/json' }
    });
    if (!response.ok) return DEFAULT_RUNTIME_CONFIG;
    return parseRuntimeConfig(await response.json());
  } catch {
    return DEFAULT_RUNTIME_CONFIG;
  }
}

export function parseRuntimeConfig(value: unknown): RuntimeConfig {
  if (!isRecord(value) || value.schemaVersion !== 1 || value.productId !== 'dpp') {
    throw new Error('The DPP starter runtime configuration has an unsupported identity');
  }
  const serviceUrl = value.serviceUrl === undefined ? '' : boundedString(value.serviceUrl, 'serviceUrl', true);
  const gatewayUrl = value.gatewayUrl === undefined ? '' : boundedString(value.gatewayUrl, 'gatewayUrl', true);
  if (serviceUrl) normalizeServiceUrl(serviceUrl, 'portal');
  if (gatewayUrl) normalizeServiceUrl(gatewayUrl, 'gateway');
  if (typeof value.allowServiceOverride !== 'boolean') {
    throw new Error('allowServiceOverride must be a boolean');
  }
  return { schemaVersion: 1, productId: 'dpp', serviceUrl, gatewayUrl, allowServiceOverride: value.allowServiceOverride };
}

/** Base URL of the portal or gateway; a path is allowed, credentials and queries are not. */
export function normalizeServiceUrl(value: string, what = 'portal', origin = globalThis.location?.origin): string {
  const trimmed = value.trim().replace(/\/+$/, '');
  if (!trimmed) throw new Error(`Enter the URL of the ${what}`);
  const parsed = new URL(trimmed, origin);
  if (parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error(`The ${what} URL must not contain credentials, query parameters, or a fragment`);
  }
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(parsed.hostname);
  if (parsed.protocol !== 'https:' && !(loopback && parsed.protocol === 'http:')) {
    throw new Error(`Use HTTPS for a remote ${what}; HTTP is allowed only on loopback`);
  }
  return parsed.origin + parsed.pathname.replace(/\/+$/, '');
}

function boundedString(value: unknown, field: string, empty = false): string {
  if (typeof value !== 'string' || value.length > MAX_TEXT || (!empty && value.trim().length === 0)) {
    throw new Error(`${field} must be a bounded string`);
  }
  return value.trim();
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
