/**
 * The client's two views, by URL hash: the wallet (#/, and #/pay?… payment request links) and the public explorer
 * (#/explorer…). Explorer links are built here from checked values only (a height, 32-byte or 28-byte hex), never
 * from a node's text.
 */
import { useEffect, useState } from 'react';

export type Route =
  | { view: 'wallet' }
  | { view: 'explorer'; page: 'overview' }
  | { view: 'explorer'; page: 'block'; height: number }
  | { view: 'explorer'; page: 'tx'; messageId: string }
  | { view: 'explorer'; page: 'account'; accountId: string }
  | { view: 'explorer'; page: 'not-found'; path: string };

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#/, '').split('?')[0];
  if (path !== '/explorer' && !path.startsWith('/explorer/')) return { view: 'wallet' };
  const [, , page = '', value = '', ...rest] = path.split('/');
  if (page === '' && !value) return { view: 'explorer', page: 'overview' };
  if (rest.length === 0) {
    if (page === 'block' && /^\d{1,15}$/.test(value)) return { view: 'explorer', page: 'block', height: Number(value) };
    if (page === 'tx' && /^[0-9a-f]{64}$/.test(value)) return { view: 'explorer', page: 'tx', messageId: value };
    if (page === 'account' && /^[0-9a-f]{56}$/.test(value)) return { view: 'explorer', page: 'account', accountId: value };
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

export function accountHref(accountId: string | undefined): string | undefined {
  return accountId && /^[0-9a-f]{56}$/.test(accountId) ? `#/explorer/account/${accountId}` : undefined;
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
