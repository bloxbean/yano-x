/**
 * The client's two views, by URL hash: the wallet (#/, and #/pay?… payment request links) and the public explorer
 * (#/explorer…). Explorer links are built here from checked values only (a height, 32-byte hex, a 28-byte account
 * id), never from a node's text. An account's canonical link uses its bech32 id (acct1… / acct_test1…); a hex link
 * still resolves.
 */
import { useEffect, useState } from 'react';
import { bech32Decode } from './lib/bech32';
import { toHex } from './lib/hex';
import { accountIdBech32 } from './lib/ids';

export type Route =
  | { view: 'wallet' }
  | { view: 'explorer'; page: 'overview' }
  | { view: 'explorer'; page: 'block'; height: number }
  | { view: 'explorer'; page: 'tx'; messageId: string }
  /** idNetwork: the network a bech32 id names (1 for acct, 0 for acct_test); absent for a hex id. */
  | { view: 'explorer'; page: 'account'; accountId: string; idNetwork?: number }
  | { view: 'explorer'; page: 'not-found'; path: string; reason?: string };

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#/, '').split('?')[0];
  if (path !== '/explorer' && !path.startsWith('/explorer/')) return { view: 'wallet' };
  const [, , page = '', value = '', ...rest] = path.split('/');
  if (page === '' && !value) return { view: 'explorer', page: 'overview' };
  if (rest.length === 0) {
    if (page === 'block' && /^\d{1,15}$/.test(value)) return { view: 'explorer', page: 'block', height: Number(value) };
    if (page === 'tx' && /^[0-9a-f]{64}$/.test(value)) return { view: 'explorer', page: 'tx', messageId: value };
    if (page === 'account' && /^[0-9a-f]{56}$/.test(value)) return { view: 'explorer', page: 'account', accountId: value };
    if (page === 'account' && /^acct(_test)?1[02-9ac-hj-np-z]+$/i.test(value)) {
      try {
        const { prefix, bytes } = bech32Decode(value);
        if (bytes.length === 28 && (prefix === 'acct' || prefix === 'acct_test')) {
          return { view: 'explorer', page: 'account', accountId: toHex(bytes), idNetwork: prefix === 'acct' ? 1 : 0 };
        }
      } catch {
        // A bad checksum or mixed case: not found, with the reason.
      }
      return { view: 'explorer', page: 'not-found', path: path.slice(0, 120),
        reason: 'This account id is not valid bech32 (a bad checksum, mixed case or the wrong length).' };
    }
  }
  return { view: 'explorer', page: 'not-found', path: path.slice(0, 120) };
}

export const explorerHref = '#/explorer';

export function blockHref(height: number): string | undefined {
  return Number.isSafeInteger(height) && height >= 0 ? `#/explorer/block/${height}` : undefined;
}

export function txHref(messageId: string | undefined): string | undefined {
  return messageId && /^[0-9a-f]{64}$/.test(messageId) ? `#/explorer/tx/${messageId}` : undefined;
}

/** The canonical link of an account: its bech32 id on the chain's network. */
export function accountHref(accountId: string | undefined, networkId: number): string | undefined {
  return accountId && /^[0-9a-f]{56}$/.test(accountId) ? `#/explorer/account/${accountIdBech32(accountId, networkId)}`
    : undefined;
}

export function navigate(href: string): void {
  window.location.hash = href.replace(/^#/, '');
}

/** The current route, following hash changes. */
export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onHash = () => {
      setRoute(parseRoute(window.location.hash));
      if (window.location.hash.startsWith('#/explorer')) window.scrollTo({ top: 0 });
    };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);
  return route;
}
