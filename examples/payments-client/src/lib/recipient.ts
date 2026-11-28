/**
 * Resolving a recipient with the node, conservatively. An address pays the account its key's credential derives to
 * (ADR-059 D14a). The client derives that account itself and follows the node's addresses/{address} answer only when
 * it is that account, or when the account the node names is currently owned by exactly this address's key. A
 * derived account whose owner rotated to another key is not this address's any more, and an account whose key rotated
 * in is not reachable by derivation; both are refused with a reason, so the payer asks for the account id instead.
 */
import { describeCode } from './errors';
import { accountIdBech32, shortId, type Recipient } from './ids';
import { PAYMENT_KEY, type Credential } from './intents';

/** The node reads a resolution needs (a PaymentsApi). */
export interface RecipientApi {
  address(addressHex: string): Promise<{ accountId: string; networkMatches: boolean }>;
  account(accountId: string): Promise<{ registered: boolean; owner?: { credentialKind: string; keyHash: string };
    committed?: { height: number } }>;
}

export class RecipientError extends Error {}

function ownedBy(owner: { credentialKind: string; keyHash: string } | undefined, credential: Credential): boolean {
  return !!owner && owner.keyHash === credential.keyHash
    && owner.credentialKind === (credential.kind === PAYMENT_KEY ? 'PAYMENT_KEY' : 'STAKE_KEY');
}

/**
 * The account an address resolves to: the node's answer, checked against the account derived here. Returns whether
 * it is the derived one; a different one is followed only if its committed owner is this address's key.
 */
export async function resolveAddress(api: Pick<RecipientApi, 'address'> & Partial<Pick<RecipientApi, 'account'>>,
                                     addressHex: string, credential: Credential):
  Promise<{ accountId: string; derived: boolean }> {
  const view = await api.address(addressHex);
  if (!/^[0-9a-f]{56}$/.test(view.accountId)) throw new RecipientError('The node returned a malformed account id.');
  if (!view.networkMatches) throw new RecipientError('That address is for another network than this chain.');
  if (view.accountId === credential.accountId) return { accountId: view.accountId, derived: true };
  const named = api.account ? await api.account(view.accountId).catch(() => undefined) : undefined;
  if (named?.registered && ownedBy(named.owner, credential)) return { accountId: view.accountId, derived: false };
  throw new RecipientError('The node\'s account for this address is not the one it derives to, and that account\'s '
    + 'current owner is not this address\'s key; refusing to follow it.');
}

export interface ResolvedRecipient {
  recipient: Recipient;
  accountId: string;
  registered: boolean;
  /** For an address: whether the account's committed owner is the address's key. */
  ownerMatches?: boolean;
  /** The committed height the registration was read at. */
  height?: number;
}

/** The recipient's account and its registration, read from the node at its committed height. */
export async function resolveRecipient(api: RecipientApi, recipient: Recipient): Promise<ResolvedRecipient> {
  if (recipient.kind === 'account') {
    const view = await api.account(recipient.accountId);
    return { recipient, accountId: recipient.accountId, registered: !!view.registered, height: view.committed?.height };
  }
  const { accountId } = await resolveAddress(api, recipient.addressHex, recipient.credential);
  const view = await api.account(accountId);
  return { recipient, accountId, registered: !!view.registered, height: view.committed?.height,
    ownerMatches: !!view.registered && ownedBy(view.owner, recipient.credential) };
}

/** Why a resolved recipient cannot be paid by this account, or null. */
export function recipientProblem(resolved: ResolvedRecipient, payer?: string): string | null {
  if (!resolved.registered) {
    return resolved.recipient.kind === 'address'
      ? `${describeCode('UNKNOWN_RECIPIENT')} No registered account derives from this address; if its key owns an `
        + 'account after an owner rotation, pay that account by its id.'
      : describeCode('UNKNOWN_RECIPIENT')!;
  }
  if (resolved.recipient.kind === 'address' && !resolved.ownerMatches) {
    return 'This address\'s key no longer owns the account it derives to (its owner rotated). Ask the recipient for '
      + 'their account id.';
  }
  if (payer && resolved.accountId === payer) return describeCode('SELF_TRANSFER')!;
  return null;
}

/** The one-line summary a form shows before review. */
export function recipientSummary(resolved: ResolvedRecipient, networkId: number): string {
  const id = shortId(accountIdBech32(resolved.accountId, networkId));
  if (!resolved.registered) return `${id} · not registered`;
  return resolved.recipient.kind === 'address'
    ? `address resolves to account ${id} (current owner matches)`
    : `account ${id} · registered`;
}

/** The review rows naming the recipient: what was typed, and the account paid as bech32 and as hex. */
export function recipientFields(resolved: ResolvedRecipient, networkId: number): [string, string][] {
  const rows: [string, string][] = [];
  if (resolved.recipient.kind === 'address') rows.push(['To address (as entered)', resolved.recipient.address]);
  rows.push(['To account', accountIdBech32(resolved.accountId, networkId)], ['To account (hex)', resolved.accountId]);
  rows.push(['Recipient check', `registered${resolved.height !== undefined ? ` at height ${resolved.height}` : ''}`
    + (resolved.recipient.kind === 'address' ? '; the address\'s key is the account\'s current owner' : '')]);
  return rows;
}
