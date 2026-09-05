import type { ActiveConnection, RuntimeConfig, RuntimeEndpoint } from './types';

const MAX_ENDPOINTS = 8;
const MAX_TEXT = 256;
export const RUNTIME_CONFIG_FILE = 'explorer-ui-config.json';

type PublicEnvironment = {
  DEV?: boolean;
  VITE_EXPLORER_SERVICE_URL?: string;
};

export function resolveServiceDefault(environment: PublicEnvironment): string {
  return environment.VITE_EXPLORER_SERVICE_URL?.trim() || 'http://localhost:8490';
}

export const SERVICE_DEFAULT = resolveServiceDefault(import.meta.env);

export const DEFAULT_RUNTIME_CONFIG: RuntimeConfig = {
  schemaVersion: 1,
  productId: 'explorer',
  serviceUrl: '',
  endpoints: [],
  defaultChainId: '',
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
  if (!isRecord(value) || value.schemaVersion !== 1 || value.productId !== 'explorer') {
    throw new Error('The Explorer runtime configuration has an unsupported identity');
  }
  const endpoints = Array.isArray(value.endpoints) ? value.endpoints : [];
  if (endpoints.length > MAX_ENDPOINTS) {
    throw new Error('The Explorer runtime configuration has too many endpoints');
  }
  const serviceUrl = value.serviceUrl === undefined ? '' : boundedString(value.serviceUrl, 'serviceUrl', true);
  if (serviceUrl) normalizeServiceUrl(serviceUrl);
  const allow = value.allowServiceOverride === undefined ? true : value.allowServiceOverride;
  if (typeof allow !== 'boolean') throw new Error('allowServiceOverride must be a boolean');
  return {
    schemaVersion: 1,
    productId: 'explorer',
    serviceUrl,
    endpoints: endpoints.map(parseEndpoint),
    defaultChainId: value.defaultChainId === undefined ? '' : boundedString(value.defaultChainId, 'defaultChainId', true),
    allowServiceOverride: allow
  };
}

/** Base URL of an explorer service; a path is allowed, credentials and queries are not. */
export function normalizeServiceUrl(value: string, origin = globalThis.location?.origin): string {
  const trimmed = value.trim().replace(/\/+$/, '');
  if (!trimmed) throw new Error('Enter the URL of a yano-explorer service');
  const parsed = new URL(trimmed, origin);
  if (parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error('The service URL must not contain credentials, query parameters, or a fragment');
  }
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(parsed.hostname);
  if (parsed.protocol !== 'https:' && !(loopback && parsed.protocol === 'http:')) {
    throw new Error('Use HTTPS for remote services; HTTP is allowed only on loopback');
  }
  return parsed.origin + parsed.pathname.replace(/\/+$/, '');
}

export function createConnection(serviceUrl: string, endpointId = 'manual', label = 'Explorer service'): ActiveConnection {
  return {
    endpointId: boundedString(endpointId, 'endpointId'),
    serviceUrl: normalizeServiceUrl(serviceUrl),
    label: boundedString(label, 'label')
  };
}

export function connectionFromEndpoint(endpoint: RuntimeEndpoint): ActiveConnection {
  return createConnection(endpoint.serviceUrl, endpoint.id, endpoint.label || endpoint.id);
}

function parseEndpoint(value: unknown): RuntimeEndpoint {
  if (!isRecord(value)) throw new Error('Every configured endpoint must be an object');
  return {
    id: boundedString(value.id, 'endpoint id'),
    serviceUrl: normalizeServiceUrl(boundedString(value.serviceUrl, 'serviceUrl')),
    ...(value.label == null ? {} : { label: boundedString(value.label, 'label') })
  };
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
