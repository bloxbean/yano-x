/**
 * Runtime configuration, from the deployed /payments-client.json (and, on the dev server only, URL parameters).
 * Nothing here names a network: the wallet's network is checked against the chain's own intent domain, so the same
 * build runs on a devnet, preview, preprod or mainnet node.
 */
export interface ClientConfig {
  /** The Yano X node's API base, e.g. /api/v1 (proxied) or https://node.example/api/v1. */
  nodeUrl: string;
  /** The asset-payments chain id; empty picks the first hosted chain that answers payments/info. */
  chainId: string;
  /** Optional topic-scoped submit key (yano.app-chain.api.keys=key=asset-payments.intents), for nodeUrl only. */
  apiKey: string;
  /** Loads Yaci DevKit's CIP-30 wallet SDK, e.g. http://localhost:10000/wallet-sdk.js (devnet only). */
  devkitWalletSdkUrl: string;
  /** Optional explorer link template for L1 transactions, with {tx}. */
  explorerTxUrl: string;
  /** Display label only. */
  networkLabel: string;
  /** The highest L1 deposit fee, in lovelace, this client lets a wallet sign. */
  maxDepositFeeLovelace: string;
}

const DEFAULTS: ClientConfig = {
  nodeUrl: '/api/v1',
  chainId: '',
  apiKey: '',
  devkitWalletSdkUrl: '',
  explorerTxUrl: '',
  networkLabel: '',
  maxDepositFeeLovelace: '2000000'
};

/**
 * Reads the deployed /payments-client.json. The node and the DevKit wallet SDK come only from that file: a link with
 * ?node= could point the page (and its API key) at another node, and ?devkit= would load any script into this
 * origin, next to its session keys. The dev server (import.meta.env.DEV) still honours both, for local work.
 */
export async function loadConfig(location: Location, allowUrlOverrides = import.meta.env.DEV): Promise<ClientConfig> {
  let file: Partial<ClientConfig> = {};
  try {
    const response = await fetch('payments-client.json', { cache: 'no-store' });
    if (response.ok) file = (await response.json()) as Partial<ClientConfig>;
  } catch {
    // The defaults serve a proxied local node.
  }
  return resolveConfig(file, location.search, location.href, allowUrlOverrides);
}

export function resolveConfig(file: Partial<ClientConfig>, search: string, pageUrl: string,
                              allowUrlOverrides: boolean): ClientConfig {
  const config: ClientConfig = { ...DEFAULTS };
  for (const key of Object.keys(DEFAULTS) as (keyof ClientConfig)[]) {
    if (typeof file[key] === 'string') config[key] = file[key] as string;
  }
  const deployedNode = config.nodeUrl;
  const params = new URLSearchParams(search);
  if (params.get('chain')) config.chainId = params.get('chain')!;
  if (allowUrlOverrides) {
    if (params.get('node')) config.nodeUrl = params.get('node')!;
    if (params.get('devkit')) config.devkitWalletSdkUrl = params.get('devkit')!;
  }
  config.nodeUrl = config.nodeUrl.replace(/\/+$/, '');
  // The API key belongs to the deployed node; it is never sent to a node a URL parameter chose.
  if (originOf(config.nodeUrl, pageUrl) !== originOf(deployedNode, pageUrl)) config.apiKey = '';
  return config;
}

/** The origin a URL (absolute or relative to the page) resolves to. */
export function originOf(url: string, pageUrl: string): string {
  return new URL(url, pageUrl).origin;
}

export function loadScript(url: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = url;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`cannot load ${url}`));
    document.head.appendChild(script);
  });
}
