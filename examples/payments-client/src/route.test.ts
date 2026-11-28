import { describe, expect, it } from 'vitest';
import { accountHref, blockHref, parseRoute, txHref } from './route';

describe('routes', () => {
  it('keeps the wallet for #/ and payment request links', () => {
    expect(parseRoute('')).toEqual({ view: 'wallet' });
    expect(parseRoute('#/')).toEqual({ view: 'wallet' });
    expect(parseRoute('#/pay?to=ab&amount=1')).toEqual({ view: 'wallet' });
    expect(parseRoute('#/explorers')).toEqual({ view: 'wallet' });
  });

  it('parses explorer pages from checked values only', () => {
    expect(parseRoute('#/explorer')).toEqual({ view: 'explorer', page: 'overview' });
    expect(parseRoute('#/explorer/')).toEqual({ view: 'explorer', page: 'overview' });
    expect(parseRoute('#/explorer/block/12')).toEqual({ view: 'explorer', page: 'block', height: 12 });
    expect(parseRoute(`#/explorer/tx/${'ab'.repeat(32)}`)).toEqual({ view: 'explorer', page: 'tx', messageId: 'ab'.repeat(32) });
    expect(parseRoute(`#/explorer/account/${'cd'.repeat(28)}`))
      .toEqual({ view: 'explorer', page: 'account', accountId: 'cd'.repeat(28) });
    expect(parseRoute('#/explorer/tx/<script>').view).toBe('explorer');
    expect(parseRoute('#/explorer/tx/<script>')).toMatchObject({ page: 'not-found' });
    expect(parseRoute(`#/explorer/block/-1`)).toMatchObject({ page: 'not-found' });
  });

  it('builds links only from well-formed values', () => {
    expect(blockHref(3)).toBe('#/explorer/block/3');
    expect(blockHref(-1)).toBeUndefined();
    expect(txHref('javascript:alert(1)')).toBeUndefined();
    expect(accountHref('cd'.repeat(28))).toBe(`#/explorer/account/${'cd'.repeat(28)}`);
    expect(accountHref('cd')).toBeUndefined();
  });
});
