/**
 * Human-readable text for the codes the asset-payments ledger and its domain API return: receipt rejection reasons,
 * admission refusals and API error codes. The code itself is always shown next to the text, since it is what the
 * ledger decided; the text only explains it.
 */
import { ApiError } from './api';

const MESSAGES: Record<string, string> = {
  // Receipt rejections (the state machine decided the intent).
  SESSION_LIMIT: 'The payment is over the session\'s per-payment or total limit.',
  GRANT_EXPIRED: 'The session has expired. Authorize a new one.',
  UNKNOWN_GRANT: 'The session is not on the ledger: it was revoked, replaced or never accepted.',
  ACTION_NOT_DELEGABLE: 'A session key may only send transfers; this action needs the owner\'s signature.',
  TOO_MANY_GRANTS: 'This account has the maximum number of active sessions. Revoke one first.',
  INSUFFICIENT_BALANCE: 'The available balance is too low for this amount.',
  INSUFFICIENT_MIN_ADA_CREDIT: 'The account\'s min-ADA credit for this asset is lower than the amount requested.',
  BELOW_MINIMUM: 'The amount is below the ledger\'s minimum for this action.',
  BALANCE_OVERFLOW: 'The recipient\'s balance would overflow.',
  SELF_TRANSFER: 'An account cannot send to itself.',
  UNKNOWN_RECIPIENT: 'The recipient account is not registered on this ledger.',
  INFEASIBLE_RECIPIENT: 'The withdrawal address cannot be paid on L1.',
  UNKNOWN_ASSET: 'This asset is not in the ledger\'s registry.',
  UNKNOWN_VAULT: 'This vault is not in the ledger\'s registry.',
  ASSET_RETIRED: 'This asset is retired; it accepts no more actions.',
  TRANSFERS_PAUSED: 'Transfers of this asset are paused by governance.',
  DEPOSITS_PAUSED: 'Deposits of this asset are paused by governance.',
  WITHDRAWALS_DISABLED: 'Withdrawals are disabled on this ledger (no payout observer).',
  VAULT_LOVELACE_CAP: 'The min-ADA lovelace requested is over the payout cap.',
  OWNER_ONLY: 'Only the account owner can do this.',
  SAME_OWNER: 'The new owner is the current owner.',
  ACCOUNT_NOT_REGISTERED: 'The account is not registered yet. Register it first.',
  BAD_BODY: 'The ledger could not decode the intent body.',
  INVALID_COMMAND: 'The ledger refused the command as malformed.',
  UNSUPPORTED_ACTION: 'The ledger does not support this action.',
  // Admission refusals (the intent never reached the state machine).
  REPLAYED: 'This intent was already submitted.',
  UNAUTHENTICATED: 'The signature does not authorize this account.',
  WRONG_DOMAIN: 'The intent is bound to another chain or network.',
  INVALID_INTENT: 'The intent is malformed or has expired.',
  NOT_AUTHORIZED: 'The signer is not allowed to act for this account.',
  DOMAIN_HALTED: 'The payments domain is halted; only exits are possible.',
  // Domain API errors.
  VAULT_NOT_ACTIVE: 'The vault is not active yet: the L1 tip has not reached its activation slot.',
  L1_TIP_UNKNOWN: 'The node does not know the Cardano tip yet. Wait until it has synced, then retry.',
  L1_PARAMETERS_UNAVAILABLE: 'The node has no Cardano protocol parameters yet. Wait until it has synced, then retry.',
  DATUM_TOO_LARGE: 'The deposit datum is larger than the deposit observer reads.',
  CANNOT_BUILD: 'The node could not build a deposit from this wallet\'s UTxOs (not enough funds?).',
  INVALID_ADDRESS: 'That is not a valid Cardano address.',
  UNSUPPORTED_ADDRESS: 'This address type cannot own an account or receive a payout.',
  WRONG_NETWORK: 'The address is for another network.',
  INVALID_ACCOUNT_ID: 'An account id is 28 bytes of hex.',
  INVALID_QUANTITY: 'The amount is not a valid quantity.',
  INVALID_LIMIT: 'The session limit is not valid.',
  CHAIN_NOT_FOUND: 'The node hosts no chain with this id.',
  NOT_A_PAYMENTS_CHAIN: 'This chain is not an asset-payments ledger.',
  L1_SUBMIT: 'The Cardano node refused the transaction.',
  // Deposit records (the deposit observer decided the output).
  NOT_A_DEPOSIT: 'The output is not a deposit.',
  FLOOR_TOP_UP: 'The output tops up the vault\'s floor and credits nobody.',
  DATUM_MALFORMED: 'The output\'s deposit datum is malformed.',
  UNREGISTERED_ASSET: 'The output carries an asset the registry does not know.',
  MIXED_ASSETS: 'The output carries more than one asset.',
  ZERO_AMOUNT: 'The output deposits nothing.',
  UNKNOWN_BENEFICIARY: 'The beneficiary account was not registered when the deposit was observed.',
  INCONSISTENT_VALUE: 'The output\'s value is inconsistent.'
};

/** The explanation of a ledger or API code, or undefined for a code this client does not know. */
export function describeCode(code: string | undefined | null): string | undefined {
  return code ? MESSAGES[code] : undefined;
}

/** Thrown when the user closes a review without signing; never shown as an error. */
export class Cancelled extends Error {
  constructor() {
    super('cancelled');
  }
}

/** A readable message for any error a flow throws (null for a user cancel). */
export function errorMessage(error: unknown): string | null {
  if (error instanceof Cancelled) return null;
  if (error instanceof ApiError) {
    const known = describeCode(error.code);
    return known ? `${known} (${error.code})` : `${error.code}: ${error.message}`;
  }
  const text = error instanceof Error ? error.message : String(error);
  // CIP-30 wallets reject with {code, info} objects rather than Errors.
  if (typeof error === 'object' && error !== null && 'info' in error) {
    return `The wallet refused: ${String((error as { info: unknown }).info)}`;
  }
  return text;
}
