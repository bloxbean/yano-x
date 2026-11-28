import { describe, expect, it, vi } from 'vitest';
import { WITHDRAW_INTENT_ID, WITHDRAW_MESSAGE } from '../test/explorer-fixtures';
import { ACCOUNT } from '../test/fixtures';
import { ApiError } from './api';
import { bech32Encode } from './bech32';
import type { Block } from './chain';
import { contextOf } from './explorer';
import { fromHex } from './hex';
import { parseSearch, resolveSearch, type SearchApi } from './search';

const CTX = contextOf({ intentTopic: 'asset-payments.intents', depositObserverId: 'deposits' });
const BECH32 = bech32Encode('addr_test', fromHex(ACCOUNT.addressHex));

describe('parsing a search', () => {
  it('recognizes heights, ids, account ids and addresses', () => {
    expect(parseSearch(' 42 ')).toEqual({ kind: 'height', height: 42 });
    expect(parseSearch('#7')).toEqual({ kind: 'height', height: 7 });
    expect(parseSearch('AB'.repeat(32))).toEqual({ kind: 'id', hex: 'ab'.repeat(32) });
    expect(parseSearch(`0x${'cd'.repeat(28)}`)).toEqual({ kind: 'account', accountId: 'cd'.repeat(28) });
    expect(parseSearch(BECH32)).toEqual({ kind: 'address', addressHex: ACCOUNT.addressHex });
    expect(parseSearch(ACCOUNT.addressHex)).toEqual({ kind: 'address', addressHex: ACCOUNT.addressHex });
  });

  it('refuses everything else with a reason', () => {
    for (const input of ['', 'hello', 'abc', '9'.repeat(30), `${BECH32.slice(0, -1)}q`, 'x'.repeat(300), 'ab'.repeat(20), 'ab'.repeat(65)]) {
      const query = parseSearch(input);
      expect(query.kind, input).toBe('invalid');
    }
  });
});

function fakeApi(overrides: Partial<SearchApi> = {}): SearchApi {
  return {
    message: vi.fn(async () => { throw new ApiError(404, '404', 'no such message'); }),
    receipt: vi.fn(async () => ({ found: false })),
    block: vi.fn(async (): Promise<Block> => { throw new Error('unused'); }),
    address: vi.fn(async () => ({ accountId: ACCOUNT.accountId, networkMatches: true })),
    ...overrides,
  };
}

describe('resolving a search', () => {
  it('opens a message by its id', async () => {
    const api = fakeApi({ message: vi.fn(async (id: string) => ({ messageId: id })) });
    expect(await resolveSearch(api, parseSearch('ab'.repeat(32)), CTX)).toEqual({ page: 'tx', messageId: 'ab'.repeat(32) });
  });

  it('finds an intent through its receipt height and the envelope hash computed here', async () => {
    const block = { height: 10, messages: [WITHDRAW_MESSAGE] } as unknown as Block;
    const api = fakeApi({ receipt: vi.fn(async () => ({ found: true, receipt: { height: 10 } })),
      block: vi.fn(async () => block) });
    expect(await resolveSearch(api, parseSearch(WITHDRAW_INTENT_ID), CTX))
      .toEqual({ page: 'tx', messageId: WITHDRAW_MESSAGE.messageId });
    expect(api.block).toHaveBeenCalledWith(10);
  });

  it('says so when neither a message nor an intent has the id', async () => {
    await expect(resolveSearch(fakeApi(), parseSearch('ab'.repeat(32)), CTX)).rejects.toThrow(/No message or intent/);
  });

  it('resolves an address to its account, and refuses an account the address does not derive to', async () => {
    expect(await resolveSearch(fakeApi(), parseSearch(BECH32), CTX)).toEqual({ page: 'account', accountId: ACCOUNT.accountId });
    const lying = fakeApi({ address: vi.fn(async () => ({ accountId: 'ee'.repeat(28), networkMatches: true })) });
    await expect(resolveSearch(lying, parseSearch(BECH32), CTX)).rejects.toThrow(/derives/);
    const otherNetwork = fakeApi({ address: vi.fn(async () => ({ accountId: ACCOUNT.accountId, networkMatches: false })) });
    await expect(resolveSearch(otherNetwork, parseSearch(BECH32), CTX)).rejects.toThrow(/another network/);
  });
});
