import { describe, expect, it } from 'vitest';
import { ACCOUNT } from '../test/fixtures';
import { bech32Encode } from './bech32';
import { fromHex, toHex } from './hex';
import {
  accountHrp, accountIdBech32, enterpriseAddress, ownerAddress, parseAccountId, parseAddress, parseRecipient,
  recipientAccountId, rewardAddress, shortId
} from './ids';

// Independently computed with the BIP-173 reference implementation (sipa/bech32, Python).
const ACCOUNT_TEST = 'acct_test1vcrt0df7w82f374q78707ggk70cs63djxtrmt57cy92g7suhzfu';
const ACCOUNT_MAIN = 'acct1vcrt0df7w82f374q78707ggk70cs63djxtrmt57cy92g7m6jftg';

// CIP-19's test vectors: the payment key hash and stake key hash of its examples, on both networks.
const PAYMENT_KEY_HASH = '9493315cd92eb5d8c4304e67b7e16ae36d61d34502694657811a2c8e';
const STAKE_KEY_HASH = '337b62cfff6403a06a3acbc34f8c46003c69fe79a3628cefa9c47251';
const CIP19 = {
  enterpriseMainnet: 'addr1vx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzers66hrl8',
  enterpriseTestnet: 'addr_test1vz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzerspjrlsz',
  rewardMainnet: 'stake1uyehkck0lajq8gr28t9uxnuvgcqrc6070x3k9r8048z8y5gh6ffgw',
  rewardTestnet: 'stake_test1uqehkck0lajq8gr28t9uxnuvgcqrc6070x3k9r8048z8y5gssrtvn',
  baseMainnet: 'addr1qx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3n0d3vllmyqwsx5wktcd8cc3sq835lu7drv2xwl2wywfgse35a3x',
};

describe('account ids as bech32', () => {
  it('uses acct on mainnet and acct_test on any testnet', () => {
    expect(accountHrp(1)).toBe('acct');
    expect(accountHrp(0)).toBe('acct_test');
    expect(accountHrp(2)).toBe('acct_test');
    expect(accountIdBech32(ACCOUNT.accountId, 0)).toBe(ACCOUNT_TEST);
    expect(accountIdBech32(ACCOUNT.accountId, 1)).toBe(ACCOUNT_MAIN);
    expect(accountIdBech32('00'.repeat(28), 0)).toBe('acct_test1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqvq5f73');
  });

  it('round-trips through the parser in both forms', () => {
    expect(parseAccountId(ACCOUNT_TEST, 0)).toBe(ACCOUNT.accountId);
    expect(parseAccountId(ACCOUNT_MAIN, 1)).toBe(ACCOUNT.accountId);
    expect(parseAccountId(ACCOUNT_TEST.toUpperCase(), 0)).toBe(ACCOUNT.accountId);
    expect(parseAccountId(` ${ACCOUNT.accountId.toUpperCase()} `, 0)).toBe(ACCOUNT.accountId);
    expect(parseAccountId(`0x${ACCOUNT.accountId}`, 1)).toBe(ACCOUNT.accountId);
    for (let i = 0; i < 50; i++) {
      const hex = toHex(Uint8Array.from({ length: 28 }, (_, j) => (i * 31 + j * 7) & 0xff));
      for (const network of [0, 1]) expect(parseAccountId(accountIdBech32(hex, network), network)).toBe(hex);
    }
  });

  it('refuses the other network\'s id, a bad checksum, mixed case and the wrong length', () => {
    expect(() => parseAccountId(ACCOUNT_MAIN, 0)).toThrow('This is a mainnet account id; this ledger is on testnet.');
    expect(() => parseAccountId(ACCOUNT_TEST, 1)).toThrow('This is a testnet account id; this ledger is on mainnet.');
    expect(() => parseAccountId(ACCOUNT_TEST.slice(0, -1) + 'q', 0)).toThrow(/bad checksum/);
    expect(() => parseAccountId('acct_test1VCRT0df7w82f374q78707ggk70cs63djxtrmt57cy92g7suhzfu', 0)).toThrow(/mixes upper and lower/);
    expect(() => parseAccountId(bech32Encode('acct_test', fromHex('cd'.repeat(29))), 0)).toThrow(/28 bytes; this one decodes to 29/);
    expect(() => parseAccountId(bech32Encode('acct_test', fromHex('cd'.repeat(27))), 0)).toThrow(/decodes to 27/);
    expect(() => parseAccountId('cd'.repeat(27), 0)).toThrow(/56 characters/);
    expect(() => parseAccountId(CIP19.enterpriseTestnet, 0)).toThrow(/address, not an account id/);
    expect(() => parseAccountId(bech32Encode('pool', fromHex('cd'.repeat(28))), 0)).toThrow(/"pool1…" is not an account id/);
    expect(() => parseAccountId('acct_test1bio', 0)).toThrow(/not a well-formed|character/);
    expect(() => parseAccountId('', 0)).toThrow(/Enter an account id/);
    expect(() => parseAccountId('hello', 0)).toThrow(/Not an account id/);
  });

  it('shortens bech32 after the separator and hex around the middle', () => {
    expect(shortId(ACCOUNT_TEST, 6)).toBe('acct_test1vcrt0d…suhzfu');
    expect(shortId('ab'.repeat(28), 4)).toBe('abab…abab');
    expect(shortId('acct1qqq')).toBe('acct1qqq');
  });
});

describe('owner credentials as L1 addresses (CIP-19 vectors)', () => {
  it('writes a payment key as an enterprise address and a stake key as a reward address', () => {
    expect(toHex(enterpriseAddress(PAYMENT_KEY_HASH, 1))).toBe(`61${PAYMENT_KEY_HASH}`);
    expect(toHex(rewardAddress(STAKE_KEY_HASH, 0))).toBe(`e0${STAKE_KEY_HASH}`);
    expect(ownerAddress({ credentialKind: 'PAYMENT_KEY', keyHash: PAYMENT_KEY_HASH }, 1).address).toBe(CIP19.enterpriseMainnet);
    expect(ownerAddress({ credentialKind: 'PAYMENT_KEY', keyHash: PAYMENT_KEY_HASH }, 0).address).toBe(CIP19.enterpriseTestnet);
    expect(ownerAddress({ credentialKind: 'STAKE_KEY', keyHash: STAKE_KEY_HASH }, 1).address).toBe(CIP19.rewardMainnet);
    expect(ownerAddress({ credentialKind: 1, keyHash: STAKE_KEY_HASH }, 0).address).toBe(CIP19.rewardTestnet);
    expect(ownerAddress({ credentialKind: 0, keyHash: PAYMENT_KEY_HASH }, 0)).toMatchObject({ kind: 'PAYMENT_KEY',
      label: 'Owner (payment key)' });
    expect(ownerAddress({ credentialKind: 'STAKE_KEY', keyHash: STAKE_KEY_HASH }, 0).label).toBe('Owner (stake key)');
    expect(() => ownerAddress({ credentialKind: 'SCRIPT', keyHash: PAYMENT_KEY_HASH }, 0)).toThrow(/credential kind/);
  });
});

describe('typed addresses', () => {
  it('accepts an address of this network, as bech32 or raw hex', () => {
    expect(toHex(parseAddress(CIP19.enterpriseTestnet, 0))).toBe(`60${PAYMENT_KEY_HASH}`);
    expect(toHex(parseAddress(CIP19.baseMainnet, 1)).slice(0, 2)).toBe('01');
    expect(toHex(parseAddress(`e0${STAKE_KEY_HASH}`, 0))).toBe(`e0${STAKE_KEY_HASH}`);
  });

  it('refuses the other network, a bad checksum, an account id and a prefix that contradicts the header', () => {
    expect(() => parseAddress(CIP19.enterpriseMainnet, 0)).toThrow('This is a mainnet address; this ledger is on testnet.');
    expect(() => parseAddress(CIP19.rewardTestnet, 1)).toThrow('This is a testnet address; this ledger is on mainnet.');
    expect(() => parseAddress(`61${PAYMENT_KEY_HASH}`, 0)).toThrow(/mainnet address/);
    expect(() => parseAddress(CIP19.enterpriseTestnet.slice(0, -1) + 'q', 0)).toThrow(/bad checksum/);
    expect(() => parseAddress(ACCOUNT_TEST, 0)).toThrow(/account id, not a Cardano address/);
    expect(() => parseAddress(bech32Encode('addr_test', fromHex(`61${PAYMENT_KEY_HASH}`)), 0)).toThrow(/prefix does not match/);
    expect(() => parseAddress(bech32Encode('addr_test', fromHex(`e0${STAKE_KEY_HASH}`)), 0)).toThrow(/prefix does not match/);
    expect(() => parseAddress('abc', 0)).toThrow(/raw address/);
  });
});

describe('recipients', () => {
  it('reads an account id in either form, or an address with the account it derives to', () => {
    expect(parseRecipient(ACCOUNT_TEST, 0)).toEqual({ kind: 'account', accountId: ACCOUNT.accountId, typed: ACCOUNT_TEST });
    expect(parseRecipient(ACCOUNT.accountId, 0)).toMatchObject({ kind: 'account', accountId: ACCOUNT.accountId });
    const address = bech32Encode('addr_test', fromHex(ACCOUNT.addressHex));
    const recipient = parseRecipient(address, 0);
    expect(recipient).toMatchObject({ kind: 'address', addressHex: ACCOUNT.addressHex, address });
    expect(recipientAccountId(recipient)).toBe(ACCOUNT.accountId);
    expect(recipientAccountId(parseRecipient(ACCOUNT.addressHex, 0))).toBe(ACCOUNT.accountId);
    expect(recipientAccountId(parseRecipient(`0x${ACCOUNT.addressHex}`, 0))).toBe(ACCOUNT.accountId);
  });

  it('refuses another network, a script address and anything else, with the reason', () => {
    expect(() => parseRecipient(ACCOUNT_MAIN, 0)).toThrow(/mainnet account id; this ledger is on testnet/);
    expect(() => parseRecipient(CIP19.enterpriseMainnet, 0)).toThrow(/mainnet address; this ledger is on testnet/);
    // CIP-19 type 7: a script enterprise address has no key that can own an account.
    expect(() => parseRecipient('addr1w8phkx6acpnf78fuvxn0mkew3l0fd058hzquvz7w36x4gtcyjy7wx', 1)).toThrow(/no key that can own/);
    expect(() => parseRecipient('', 0)).toThrow(/Enter a recipient/);
    expect(() => parseRecipient('ab'.repeat(20), 0)).toThrow(/56-character account id/);
    expect(() => parseRecipient('pool1xyz', 0)).toThrow(/Not a recipient/);
  });
});
