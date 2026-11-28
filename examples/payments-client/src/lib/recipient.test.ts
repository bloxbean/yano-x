import { describe, expect, it, vi } from 'vitest';
import { ACCOUNT } from '../test/fixtures';
import { bech32Encode } from './bech32';
import { fromHex } from './hex';
import { parseRecipient } from './ids';
import { credentialFromAddress } from './intents';
import { recipientFields, recipientProblem, recipientSummary, resolveAddress, resolveRecipient,
  type RecipientApi } from './recipient';

const ADDRESS = bech32Encode('addr_test', fromHex(ACCOUNT.addressHex));
const CREDENTIAL = credentialFromAddress(fromHex(ACCOUNT.addressHex));
const OWNER = { credentialKind: 'PAYMENT_KEY', keyHash: ACCOUNT.keyHash };
const OTHER = 'ee'.repeat(28);

function fakeApi(overrides: Partial<RecipientApi> = {}): RecipientApi {
  return {
    address: vi.fn(async () => ({ accountId: ACCOUNT.accountId, networkMatches: true })),
    account: vi.fn(async () => ({ registered: true, owner: OWNER, committed: { height: 9 } })),
    ...overrides,
  };
}

describe('resolving an address', () => {
  it('follows the node when it names the account derived here', async () => {
    expect(await resolveAddress(fakeApi(), ACCOUNT.addressHex, CREDENTIAL)).toEqual({ accountId: ACCOUNT.accountId, derived: true });
  });

  it('follows another account only when its current owner is the address\'s key', async () => {
    const elsewhere = fakeApi({ address: vi.fn(async () => ({ accountId: OTHER, networkMatches: true })) });
    expect(await resolveAddress(elsewhere, ACCOUNT.addressHex, CREDENTIAL)).toEqual({ accountId: OTHER, derived: false });
    const notOwned = fakeApi({ address: vi.fn(async () => ({ accountId: OTHER, networkMatches: true })),
      account: vi.fn(async () => ({ registered: true, owner: { credentialKind: 'PAYMENT_KEY', keyHash: '11'.repeat(28) } })) });
    await expect(resolveAddress(notOwned, ACCOUNT.addressHex, CREDENTIAL)).rejects.toThrow(/not the one it derives to/);
    const stakeOwner = fakeApi({ address: vi.fn(async () => ({ accountId: OTHER, networkMatches: true })),
      account: vi.fn(async () => ({ registered: true, owner: { credentialKind: 'STAKE_KEY', keyHash: ACCOUNT.keyHash } })) });
    await expect(resolveAddress(stakeOwner, ACCOUNT.addressHex, CREDENTIAL)).rejects.toThrow(/current owner/);
  });

  it('refuses a malformed answer and another network', async () => {
    await expect(resolveAddress(fakeApi({ address: vi.fn(async () => ({ accountId: '<x>', networkMatches: true })) }),
      ACCOUNT.addressHex, CREDENTIAL)).rejects.toThrow(/malformed/);
    await expect(resolveAddress(fakeApi({ address: vi.fn(async () => ({ accountId: ACCOUNT.accountId, networkMatches: false })) }),
      ACCOUNT.addressHex, CREDENTIAL)).rejects.toThrow(/another network/);
  });
});

describe('resolving a recipient', () => {
  it('reads an account id\'s registration', async () => {
    const api = fakeApi();
    const resolved = await resolveRecipient(api, parseRecipient(ACCOUNT.accountId, 0));
    expect(resolved).toMatchObject({ accountId: ACCOUNT.accountId, registered: true, height: 9 });
    expect(api.address).not.toHaveBeenCalled();
    expect(recipientProblem(resolved)).toBeNull();
    expect(recipientProblem(resolved, ACCOUNT.accountId)).toMatch(/cannot send to itself/);
    expect(recipientSummary(resolved, 0)).toMatch(/^account acct_test1vcrt0d…suhzfu · registered$/);
  });

  it('pays an address\'s account only while the address\'s key owns it', async () => {
    const resolved = await resolveRecipient(fakeApi(), parseRecipient(ADDRESS, 0));
    expect(resolved).toMatchObject({ accountId: ACCOUNT.accountId, registered: true, ownerMatches: true });
    expect(recipientProblem(resolved)).toBeNull();
    expect(recipientSummary(resolved, 0)).toBe('address resolves to account acct_test1vcrt0d…suhzfu (current owner matches)');
    expect(recipientFields(resolved, 0)).toEqual([
      ['To address (as entered)', ADDRESS],
      ['To account', 'acct_test1vcrt0df7w82f374q78707ggk70cs63djxtrmt57cy92g7suhzfu'],
      ['To account (hex)', ACCOUNT.accountId],
      ['Recipient check', 'registered at height 9; the address\'s key is the account\'s current owner'],
    ]);

    const rotated = await resolveRecipient(fakeApi({ account: vi.fn(async () => ({ registered: true,
      owner: { credentialKind: 'PAYMENT_KEY', keyHash: '22'.repeat(28) } })) }), parseRecipient(ADDRESS, 0));
    expect(rotated.ownerMatches).toBe(false);
    expect(recipientProblem(rotated)).toMatch(/no longer owns the account/);
  });

  it('refuses an unregistered recipient with the ledger\'s reason', async () => {
    const api = fakeApi({ account: vi.fn(async () => ({ registered: false })) });
    expect(recipientProblem(await resolveRecipient(api, parseRecipient(ACCOUNT.accountId, 0))))
      .toBe('The recipient account is not registered on this ledger.');
    expect(recipientProblem(await resolveRecipient(api, parseRecipient(ADDRESS, 0))))
      .toMatch(/^The recipient account is not registered on this ledger\. No registered account derives from this address/);
  });
});
