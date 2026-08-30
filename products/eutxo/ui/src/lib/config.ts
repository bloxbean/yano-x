import type { ActiveConnection, RuntimeConfig, RuntimeEndpoint } from './types';

const MAX_ENDPOINTS = 8;
const MAX_TEXT = 256;

export interface ConnectionDefaults {
  nodeUrl: string;
  apiPrefix: string;
  apiKey: string;
}

type PublicEnvironment = {
  DEV?: boolean;
  VITE_YANO_NODE_URL?: string;
  VITE_YANO_API_PREFIX?: string;
  VITE_YANO_API_KEY?: string;
};

export function resolveConnectionDefaults(environment: PublicEnvironment): ConnectionDefaults {
  return {
    nodeUrl: environment.VITE_YANO_NODE_URL?.trim() || 'http://localhost:7070',
    apiPrefix: environment.VITE_YANO_API_PREFIX?.trim() || '/api/v1',
    // A Vite variable is sent to the browser. Only permit this convenience on the local dev server.
    apiKey: environment.DEV ? environment.VITE_YANO_API_KEY ?? '' : ''
  };
}

export const CONNECTION_DEFAULTS = resolveConnectionDefaults(import.meta.env);

export const DEFAULT_RUNTIME_CONFIG: RuntimeConfig = {
  schemaVersion: 1,
  productId: 'eutxo',
  endpoints: [],
  defaultChainId: '',
  expectedNetwork: '',
  allowEndpointOverride: true
};

export async function loadRuntimeConfig(fetcher: typeof fetch = fetch): Promise<RuntimeConfig> {
  try {
    const url = new URL('eutxo-ui-config.json', document.baseURI);
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
  if (!isRecord(value) || value.schemaVersion !== 1 || value.productId !== 'eutxo') {
    throw new Error('The EUTxO UI runtime configuration has an unsupported identity');
  }
  if (!Array.isArray(value.endpoints) || value.endpoints.length > MAX_ENDPOINTS) {
    throw new Error('The EUTxO UI runtime configuration has too many endpoints');
  }
  const endpoints = value.endpoints.map(parseEndpoint);
  const defaultChainId = boundedString(value.defaultChainId, 'defaultChainId', true);
  const expectedNetwork = boundedString(value.expectedNetwork, 'expectedNetwork', true).toLowerCase();
  if (typeof value.allowEndpointOverride !== 'boolean') {
    throw new Error('allowEndpointOverride must be a boolean');
  }
  return {
    schemaVersion: 1,
    productId: 'eutxo',
    endpoints,
    defaultChainId,
    expectedNetwork,
    allowEndpointOverride: value.allowEndpointOverride
  };
}

export function normalizeNodeUrl(value: string, origin = globalThis.location?.origin): string {
  const trimmed = value.trim();
  if (!trimmed) throw new Error('Enter the HTTPS URL of a Yano node');
  const parsed = new URL(trimmed, origin);
  if (parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error('The node URL must not contain credentials, query parameters, or a fragment');
  }
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(parsed.hostname);
  if (parsed.protocol !== 'https:' && !(loopback && parsed.protocol === 'http:')) {
    throw new Error('Use HTTPS for remote Yano nodes; HTTP is allowed only on loopback');
  }
  if (parsed.pathname !== '/' && parsed.pathname !== '') {
    throw new Error('Enter the node origin without an API path');
  }
  return parsed.origin;
}

export function normalizeApiPrefix(value: string): string {
  const trimmed = value.trim().replace(/\/$/, '');
  if (!/^\/(?:[A-Za-z0-9._~-]+(?:\/[A-Za-z0-9._~-]+)*)?$/.test(trimmed)
      || trimmed.length > MAX_TEXT) {
    throw new Error('The API prefix must be a canonical absolute path');
  }
  return trimmed || '/api/v1';
}

export function createConnection(
  nodeUrl: string,
  apiPrefix = '/api/v1',
  apiKey = '',
  endpointId = 'manual',
  label = 'Custom node'
): ActiveConnection {
  const normalizedNode = normalizeNodeUrl(nodeUrl);
  const normalizedPrefix = normalizeApiPrefix(apiPrefix);
  if (apiKey.length > 8192) throw new Error('The API key is too long');
  return {
    endpointId: boundedString(endpointId, 'endpointId'),
    nodeUrl: normalizedNode,
    apiPrefix: normalizedPrefix,
    apiBase: `${normalizedNode}${normalizedPrefix}`,
    apiKey,
    label: boundedString(label, 'label')
  };
}

export function connectionFromEndpoint(endpoint: RuntimeEndpoint): ActiveConnection {
  return createConnection(
    endpoint.nodeUrl,
    endpoint.apiPrefix,
    '',
    endpoint.id,
    endpoint.label || endpoint.id
  );
}

function parseEndpoint(value: unknown): RuntimeEndpoint {
  if (!isRecord(value)) throw new Error('Every configured endpoint must be an object');
  return {
    id: boundedString(value.id, 'endpoint id'),
    nodeUrl: normalizeNodeUrl(boundedString(value.nodeUrl, 'nodeUrl')),
    apiPrefix: normalizeApiPrefix(boundedString(value.apiPrefix, 'apiPrefix')),
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
