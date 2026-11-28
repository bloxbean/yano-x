import { describe, expect, it } from 'vitest';
import { accountHref, blockHref, parseRoute, txHref } from './route';

// accountIdBech32('cd' × 28) on each network, from the BIP-173 reference implementation.
const CD_TEST = 'acct_test1ehxumnwdehxumnwdehxumnwdehxumnwdehxumnwdehxu6zx4ljj';
const CD_MAIN = 'acct1ehxumnwdehxumnwdehxumnwdehxumnwdehxumnwdehxu6fqs5sx';

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

  it('opens an account by its bech32 id, naming the id\'s network, and still by an old hex link', () => {
    expect(parseRoute(`#/explorer/account/${CD_TEST}`))
      .toEqual({ view: 'explorer', page: 'account', accountId: 'cd'.repeat(28), idNetwork: 0 });
    expect(parseRoute(`#/explorer/account/${CD_MAIN}`))
      .toEqual({ view: 'explorer', page: 'account', accountId: 'cd'.repeat(28), idNetwork: 1 });
    expect(parseRoute(`#/explorer/account/${CD_TEST.toUpperCase()}`)).toMatchObject({ accountId: 'cd'.repeat(28), idNetwork: 0 });
    expect(parseRoute(`#/explorer/account/${'cd'.repeat(28)}`)).not.toHaveProperty('idNetwork');
    expect(parseRoute(`#/explorer/account/${CD_TEST.slice(0, -1)}q`)).toMatchObject({ page: 'not-found',
      reason: expect.stringMatching(/not valid bech32/) });
    expect(parseRoute(`#/explorer/account/acct_test1Ehxumnwdehxumnwdehxumnwdehxumnwdehxumnwdehxu6zx4ljj`))
      .toMatchObject({ page: 'not-found' });
  });

  it('builds links only from well-formed values', () => {
    expect(blockHref(3)).toBe('#/explorer/block/3');
    expect(blockHref(-1)).toBeUndefined();
    expect(txHref('javascript:alert(1)')).toBeUndefined();
    expect(accountHref('cd'.repeat(28), 0)).toBe(`#/explorer/account/${CD_TEST}`);
    expect(accountHref('cd'.repeat(28), 1)).toBe(`#/explorer/account/${CD_MAIN}`);
    expect(accountHref('cd', 0)).toBeUndefined();
    expect(accountHref(CD_TEST, 0)).toBeUndefined();
  });
});
