/**
 * The identifiers a person sees and types, on the chain's network (its intent domain's address network id: 1 is
 * mainnet, anything else a testnet):
 *
 *   account id     blake2b-224("acct\0" || initial credential kind || key hash), ADR-059 D14a: 28 bytes, stable across
 *                  owner rotation, not an address. Shown as bech32 with HRP "acct" (mainnet) or "acct_test" (testnets),
 *                  the BIP-173 codec CIP-19 addresses use; 56 hex characters are accepted too.
 *   owner          the account's current owner credential, shown as an L1 address on the chain's network: a payment
 *                  key as an enterprise address (CIP-19 type 6, addr…v…), a stake key as a reward address (type 14).
 *   address        any Cardano address a recipient's key signs with; it pays the account that key's credential
 *                  derives to.
 *
 * Every parser here refuses an id or address of the other network with a reason a person can act on. Nothing here
 * talks to a node; lib/recipient.ts resolves an address with one.
 */
import { Bech32Error, bech32Decode, bech32Encode, addressToBech32 } from './bech32';
import { fromHex, toHex } from './hex';
import { credentialFromAddress, PAYMENT_KEY, type Credential } from './intents';

export const MAINNET_ID = 1;

export class IdError extends Error {}

export function isMainnet(networkId: number): boolean {
  return networkId === MAINNET_ID;
}

export function networkName(networkId: number): 'mainnet' | 'testnet' {
  return isMainnet(networkId) ? 'mainnet' : 'testnet';
}

export function accountHrp(networkId: number): 'acct' | 'acct_test' {
  return isMainnet(networkId) ? 'acct' : 'acct_test';
}

const ACCOUNT_HRPS: Record<string, number> = { acct: 1, acct_test: 0 };
const ADDRESS_HRPS: Record<string, number> = { addr: 1, addr_test: 0, stake: 1, stake_test: 0 };

/** An account id (28 bytes of hex) as bech32 on the chain's network. */
export function accountIdBech32(accountId: string, networkId: number): string {
  return bech32Encode(accountHrp(networkId), fromHex(accountId, 28));
}

/** A bech32 string shortened after its separator ("acct_test1qyr8…k3x9wz"), or a hex string around its middle. */
export function shortId(text: string, keep = 6): string {
  const separator = text.lastIndexOf('1');
  if (/^[a-z_]+1[02-9ac-hj-np-z]+$/.test(text) && separator > 0) {
    const data = text.slice(separator + 1);
    return data.length <= 2 * keep + 1 ? text : `${text.slice(0, separator + 1)}${data.slice(0, keep)}…${data.slice(-keep)}`;
  }
  return text.length <= 2 * keep + 3 ? text : `${text.slice(0, keep)}…${text.slice(-keep)}`;
}

function bech32Problem(error: unknown, what: string): IdError {
  if (error instanceof Bech32Error) {
    switch (error.reason) {
      case 'checksum': return new IdError(`That ${what} has a bad checksum; check it for a typo.`);
      case 'mixed-case': return new IdError(`That ${what} mixes upper and lower case; bech32 is one case only.`);
      case 'character': return new IdError(`That ${what} has a character bech32 does not use.`);
      default: return new IdError(`That is not a well-formed ${what}.`);
    }
  }
  return new IdError(`That is not a well-formed ${what}.`);
}

/** Whether the text looks like it was meant as bech32 (a prefix, the separator, data), as opposed to hex. */
function looksBech32(text: string): boolean {
  return /^[a-z_]+1[0-9a-z]+$/i.test(text);
}

/**
 * An account id typed or linked as bech32 (acct1… / acct_test1… on this chain's network) or as 56 hex characters
 * (an optional 0x); returns it as lowercase hex.
 */
export function parseAccountId(text: string, networkId: number): string {
  const value = text.trim();
  if (!value) throw new IdError('Enter an account id.');
  const hex = value.replace(/^0x/i, '');
  if (/^[0-9a-f]+$/i.test(hex)) {
    if (hex.length !== 56) throw new IdError(`A hex account id is 56 characters (28 bytes); this has ${hex.length}.`);
    return hex.toLowerCase();
  }
  if (!looksBech32(value)) throw new IdError(`Not an account id: expected ${accountHrp(networkId)}1… or 56 hex characters.`);
  let decoded: { prefix: string; bytes: Uint8Array };
  try {
    decoded = bech32Decode(value);
  } catch (error) {
    throw bech32Problem(error, 'account id');
  }
  const idNetwork = ACCOUNT_HRPS[decoded.prefix];
  if (idNetwork === undefined) {
    if (decoded.prefix in ADDRESS_HRPS) throw new IdError('That is a Cardano address, not an account id.');
    throw new IdError(`"${decoded.prefix}1…" is not an account id; expected ${accountHrp(networkId)}1….`);
  }
  if (isMainnet(idNetwork) !== isMainnet(networkId)) {
    throw new IdError(`This is a ${networkName(idNetwork)} account id; this ledger is on ${networkName(networkId)}.`);
  }
  if (decoded.bytes.length !== 28) {
    throw new IdError(`An account id is 28 bytes; this one decodes to ${decoded.bytes.length}.`);
  }
  return toHex(decoded.bytes);
}

/** The network nibble of a Shelley address header, or null for a Byron (type 8) address, which has none. */
export function addressNetwork(address: Uint8Array): number | null {
  if (address.length === 0) throw new IdError('An address is not empty.');
  return address[0] >> 4 === 8 ? null : address[0] & 0x0f;
}

/** Refuses a Shelley address of the other network. */
export function checkAddressNetwork(address: Uint8Array, networkId: number): void {
  const network = addressNetwork(address);
  if (network !== null && isMainnet(network) !== isMainnet(networkId)) {
    throw new IdError(`This is a ${networkName(network)} address; this ledger is on ${networkName(networkId)}.`);
  }
}

/**
 * An L1 address typed as bech32 (addr… / stake…) or as raw hex, on this chain's network: the prefix and the header's
 * network must agree with each other and with the chain.
 */
export function parseAddress(text: string, networkId: number): Uint8Array {
  const value = text.trim();
  if (!value) throw new IdError('Enter an address.');
  let address: Uint8Array;
  if (/^[0-9a-f]+$/i.test(value)) {
    if (value.length % 2 !== 0 || value.length < 2 || value.length > 256) throw new IdError('That is not a raw address in hex.');
    address = fromHex(value);
  } else {
    if (!looksBech32(value)) throw new IdError('Not a Cardano address: expected addr…, stake… or raw hex.');
    let decoded: { prefix: string; bytes: Uint8Array };
    try {
      decoded = bech32Decode(value);
    } catch (error) {
      throw bech32Problem(error, 'address');
    }
    const prefixNetwork = ADDRESS_HRPS[decoded.prefix];
    if (prefixNetwork === undefined) {
      if (decoded.prefix in ACCOUNT_HRPS) throw new IdError('That is an account id, not a Cardano address.');
      throw new IdError(`"${decoded.prefix}1…" is not a Cardano address prefix.`);
    }
    address = decoded.bytes;
    const header = addressNetwork(address);
    const reward = address[0] >> 4 === 14 || address[0] >> 4 === 15;
    if (header === null || isMainnet(header) !== isMainnet(prefixNetwork) || reward !== decoded.prefix.startsWith('stake')) {
      throw new IdError('That address\'s prefix does not match its header.');
    }
    if (isMainnet(prefixNetwork) !== isMainnet(networkId)) {
      throw new IdError(`This is a ${networkName(prefixNetwork)} address; this ledger is on ${networkName(networkId)}.`);
    }
  }
  checkAddressNetwork(address, networkId);
  return address;
}

/** CIP-19 type 6: header 0x60 | network, then the payment key hash. */
export function enterpriseAddress(keyHash: string, networkId: number): Uint8Array {
  return Uint8Array.of(0x60 | (networkId & 0x0f), ...fromHex(keyHash, 28));
}

/** CIP-19 type 14: header 0xe0 | network, then the stake key hash. */
export function rewardAddress(keyHash: string, networkId: number): Uint8Array {
  return Uint8Array.of(0xe0 | (networkId & 0x0f), ...fromHex(keyHash, 28));
}

export interface OwnerAddress {
  kind: 'PAYMENT_KEY' | 'STAKE_KEY';
  address: string;
  label: string;
  hint: string;
}

/**
 * An owner credential (an account view's owner, a rotation's new owner, a signer's credential) as the L1 address of
 * its key alone on the chain's network.
 */
export function ownerAddress(owner: { credentialKind: string | number; keyHash: string }, networkId: number): OwnerAddress {
  const payment = owner.credentialKind === 'PAYMENT_KEY' || owner.credentialKind === PAYMENT_KEY;
  if (!payment && owner.credentialKind !== 'STAKE_KEY' && owner.credentialKind !== 1) {
    throw new IdError(`unknown credential kind ${String(owner.credentialKind)}`);
  }
  return payment
    ? { kind: 'PAYMENT_KEY', address: addressToBech32(enterpriseAddress(owner.keyHash, networkId)),
      label: 'Owner (payment key)', hint: 'The owner key\'s address without a stake part (an enterprise address).' }
    : { kind: 'STAKE_KEY', address: addressToBech32(rewardAddress(owner.keyHash, networkId)),
      label: 'Owner (stake key)', hint: 'The owner stake key\'s reward address.' };
}

/** What a recipient field holds: an account id, or an address whose key's credential derives to an account. */
export type Recipient =
  | { kind: 'account'; accountId: string; typed: string }
  | { kind: 'address'; addressHex: string; address: string; credential: Credential; typed: string };

/**
 * A recipient typed, pasted or linked: an account id (bech32 or hex) or a Cardano address (bech32 or raw hex) on this
 * chain's network. Local only: the address's account is derived here, and lib/recipient.ts asks the node.
 */
export function parseRecipient(text: string, networkId: number): Recipient {
  const typed = text.trim();
  if (!typed) throw new IdError('Enter a recipient: an account id or a Cardano address.');
  const hex = typed.replace(/^0x/i, '');
  const accountLike = /^[0-9a-f]{56}$/i.test(hex) || /^acct(_test)?1/i.test(typed);
  if (accountLike) return { kind: 'account', accountId: parseAccountId(typed, networkId), typed };
  if (/^[0-9a-f]+$/i.test(hex) && hex.length < 58) {
    throw new IdError('Hex input is a 56-character account id or a raw address of at least 29 bytes.');
  }
  if (!/^[0-9a-f]+$/i.test(hex) && !/^(addr|stake)(_test)?1/i.test(typed)) {
    throw new IdError(`Not a recipient: expected ${accountHrp(networkId)}1…, an addr… / stake… address or 56 hex characters.`);
  }
  const bytes = parseAddress(/^[0-9a-f]+$/i.test(hex) ? hex : typed, networkId);
  let credential: Credential;
  try {
    credential = credentialFromAddress(bytes);
  } catch (error) {
    throw new IdError(`This address has no key that can own an account (${error instanceof Error ? error.message : 'unsupported'}).`);
  }
  return { kind: 'address', addressHex: toHex(bytes), address: addressToBech32(bytes), credential, typed };
}

/** The account a parsed recipient names or derives to (before the node is asked). */
export function recipientAccountId(recipient: Recipient): string {
  return recipient.kind === 'account' ? recipient.accountId : recipient.credential.accountId;
}
