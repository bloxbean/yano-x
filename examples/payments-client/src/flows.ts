/**
 * The client's actions, independent of the UI (#109 §3.1, ADR-059 D13/D14). Every app-ledger action is an
 * IntentEnvelopeV1 that this client encodes, cross-checks with the node's encoder, shows in a review, has signed with
 * CIP-30 signData (or a session key), pre-checks with the payments domain API and submits through the node's ordinary
 * message route. L1 deposits are built by the domain API, decoded and checked here, signed with signTx and submitted by
 * the wallet. The UI passes a {@link Reviewer} that shows the review sheet and resolves only when the user approves.
 */
import type { AccountView, Balance, GrantView, Info, PaymentsApi, VaultView } from './lib/api';
import { formatUnits, toBaseUnits } from './lib/amounts';
import { addressToBech32 } from './lib/bech32';
import type { ClientConfig } from './lib/config';
import { depositDatum, verifyDeposit, type DecodedDeposit } from './lib/deposit';
import { assetIdText, short, vaultLabel } from './lib/display';
import { fromHex, randomBytes, toHex } from './lib/hex';
import { accountIdBech32, ownerAddress, parseAccountId, parseAddress, parseRecipient, recipientAccountId,
  shortId } from './lib/ids';
import { bodyFromJson, credentialFromAddress, encodeEnvelope, INTENT_TOPIC, intentId, ownerMessage, PAYMENT_KEY,
  sessionMessage, type Action, type Authorizer, type Credential, type IntentDomain } from './lib/intents';
import { proofMismatch, type Fact } from './lib/proof';
import { recipientFields, recipientProblem, resolveRecipient, type ResolvedRecipient } from './lib/recipient';
import { deleteSession, generateSessionKey, isStale, listSessions, MAX_SESSION_VALIDITY_MS, saveSession,
  signWithSession, type StoredSession } from './lib/session';
import { assembleSignedTx } from './lib/tx';
import { addressBytes, signEnvelope, type ConnectedWallet } from './lib/wallet';

export type Field = [label: string, value: string];

export interface ReviewRequest {
  /** Owner intents and L1 deposits. Session payments are never reviewed: the user authorized them in advance. */
  kind: 'intent' | 'deposit';
  title: string;
  fields: Field[];
  /** The exact bytes the wallet signs: the envelope, or the unsigned transaction. */
  bytesHex: string;
}

/** Shows the review and resolves when the user approves; rejects (with Cancelled) otherwise. */
export type Reviewer = (request: ReviewRequest) => Promise<void>;

export interface FlowContext {
  config: ClientConfig;
  api: PaymentsApi;
  info: Info;
  wallet: ConnectedWallet;
  /** The account acted for: the credential's own, or one it owns after an owner rotation. */
  account: Credential;
  /** The connected wallet's credential. */
  credential: Credential;
  /** The account's latest view, for the ownership check (undefined while it loads). */
  view: AccountView | undefined;
  review: Reviewer;
}

export interface Submitted {
  intentId: string;
  action: Action;
  title: string;
  fields: Field[];
  notAfterMs: number;
  /** A revoke names its grant, so an accepted receipt can delete the local key. */
  grantId?: string;
}

// ------------------------------------------------------------------ helpers

/** Whether the credential owns the account acted for (always, until the account is registered). */
export function ownsAccount(view: AccountView | undefined, credential: Credential | null): boolean {
  if (!view?.registered || !view.owner || !credential) return true;
  return view.owner.keyHash === credential.keyHash
    && view.owner.credentialKind === (credential.kind === PAYMENT_KEY ? 'PAYMENT_KEY' : 'STAKE_KEY');
}

export function decimalsOf(info: Info, vaultId: string): number {
  return info.vaults.find((vault) => vault.vaultId === vaultId)?.decimals ?? 0;
}

export function vaultById(info: Info, id: string): VaultView {
  const vault = info.vaults.find((entry) => entry.vaultId === id);
  if (!vault) throw new Error('unknown vault');
  return vault;
}

/** The chain's address network id, which every shown and typed identifier is checked against. */
export function networkOf(info: Pick<Info, 'intentDomain'>): number {
  return info.intentDomain.addressNetworkId;
}

/**
 * A typed, pasted or linked recipient resolved with the node and checked: it must be registered, an address's key must
 * be its account's current owner, and it must not be the payer. Throws the readable reason otherwise, before any
 * review or signature.
 */
export async function checkedRecipient(ctx: Pick<FlowContext, 'api' | 'info' | 'account'>, text: string):
  Promise<ResolvedRecipient> {
  const resolved = await resolveRecipient(ctx.api, parseRecipient(text, networkOf(ctx.info)));
  const problem = recipientProblem(resolved, ctx.account.accountId);
  if (problem) throw new Error(problem);
  return resolved;
}

/** The fields every owner-signed intent's review adds: who acts, on which chain and genesis, until when. */
export function intentReviewFields(fields: Field[], accountId: string, domain: IntentDomain, notAfterMs: number,
                                  id: string): Field[] {
  return [...fields, ['Account', accountIdBech32(accountId, domain.addressNetworkId)], ['Account (hex)', accountId],
    ['Chain (intent domain)', `${domain.chainId}, application ${domain.applicationId}`],
    ['Genesis id', domain.genesisId],
    ['Network', `magic ${domain.networkMagic}, address network id ${domain.addressNetworkId}`],
    ['Expires', new Date(notAfterMs).toLocaleTimeString()], ['Intent id', id]];
}

export function actionTitle(action: Action): string {
  return {
    REGISTER_ACCOUNT: 'Register account', TRANSFER: 'Send', AUTHORIZE_SESSION: 'Authorize session',
    REVOKE_SESSION: 'Revoke session', REQUEST_WITHDRAWAL: 'Withdraw to L1', ROTATE_OWNER: 'Rotate owner'
  }[action] ?? action;
}

// ------------------------------------------------------------------ the intent pipeline

/**
 * Encodes the intent locally, cross-checks the bytes with the node's encoder, gets the user's approval, signs, has
 * the domain API pre-check the signed message, and submits it through the host's SUBMIT route.
 */
export async function runIntent(ctx: FlowContext, action: Action, bodyJson: Record<string, unknown>, fields: Field[],
                                session?: StoredSession): Promise<Submitted> {
  const { api, info, wallet, account } = ctx;
  if (!ownsAccount(ctx.view, ctx.credential)) throw new Error('this wallet does not own the account');
  const body = bodyFromJson(action, bodyJson);
  const notAfterMs = Date.now() + Math.max(30_000, Math.min(300_000, info.maxIntentTtlMs - 60_000));
  const nonce = toHex(randomBytes(16));
  const authorizer: Authorizer = session ? { kind: 'SESSION', grantId: session.grantId } : { kind: 'OWNER' };
  const envelope = encodeEnvelope({ domain: info.intentDomain, action, accountId: account.accountId, authorizer,
    nonce, notAfterMs, body });
  const id = intentId(envelope);
  const remote = await api.encodeIntent({ action, accountId: account.accountId, authorizer, nonce, notAfterMs,
    body: bodyJson });
  if (remote.envelopeHex !== toHex(envelope) || remote.intentId !== id) {
    throw new Error('the node encodes this intent differently from this client; refusing to sign');
  }
  let wrapped: Uint8Array;
  let prepare: Record<string, unknown>;
  if (session) {
    const signature = await signWithSession(session.privateKey, envelope);
    wrapped = sessionMessage(envelope, signature);
    prepare = { kind: 'SESSION', envelopeHex: toHex(envelope), signatureHex: toHex(signature) };
  } else {
    await ctx.review({ kind: 'intent', title: actionTitle(action),
      fields: intentReviewFields(fields, account.accountId, info.intentDomain, notAfterMs, id), bytesHex: toHex(envelope) });
    const signed = await signEnvelope(wallet, envelope);
    wrapped = ownerMessage(fromHex(signed.signature), fromHex(signed.key));
    prepare = { kind: 'OWNER', coseSign1Hex: signed.signature, coseKeyHex: signed.key };
  }
  const prepared = await api.prepareMessage(prepare);
  if (prepared.bodyHex !== toHex(wrapped) || prepared.intentId !== id) {
    throw new Error('the prepared message differs from the signed intent; refusing to submit');
  }
  await api.submit(INTENT_TOPIC, prepared.bodyHex);
  return { intentId: id, action, title: actionTitle(action), fields, notAfterMs };
}

// ------------------------------------------------------------------ flows

export async function register(ctx: FlowContext): Promise<Submitted> {
  const { account } = ctx;
  const owner = ownerAddress({ credentialKind: account.kind, keyHash: account.keyHash }, networkOf(ctx.info));
  return runIntent(ctx, 'REGISTER_ACCOUNT', {}, [[owner.label, owner.address],
    ['Owner key hash', `${account.kind === PAYMENT_KEY ? 'payment' : 'stake'} key ${account.keyHash}`]]);
}

/** A transfer's review rows: the amount, the asset, and the recipient as entered and as the account paid. */
export function transferFields(vault: VaultView, amount: bigint, to: ResolvedRecipient, networkId: number): Field[] {
  return [['Send', `${formatUnits(amount, vault.decimals)} ${vaultLabel(vault)}`],
    ['Amount (base units)', amount.toString()], ['Asset id', assetIdText(vault)], ...recipientFields(to, networkId)];
}

export interface TransferInput {
  vaultId: string;
  to: string;
  amount: string;
  /** Extra review fields, e.g. a payment request's unverified memo. */
  extra?: Field[];
}

export async function transfer(ctx: FlowContext, input: TransferInput): Promise<Submitted> {
  const vault = vaultById(ctx.info, input.vaultId);
  const amount = toBaseUnits(input.amount, vault.decimals);
  return transferBase(ctx, vault.vaultId, input.to, amount, input.extra);
}

/** A transfer of an exact base-unit amount (a payment request) to a recipient as typed or linked. */
export async function transferBase(ctx: FlowContext, vaultId: string, toText: string, amount: bigint,
                                   extra: Field[] = []): Promise<Submitted> {
  const vault = vaultById(ctx.info, vaultId);
  const to = await checkedRecipient(ctx, toText);
  return runIntent(ctx, 'TRANSFER', { asset: { policyId: vault.asset.policyId, assetName: vault.asset.assetName },
    toAccount: to.accountId, amount: amount.toString() },
  [...transferFields(vault, amount, to, networkOf(ctx.info)), ...extra]);
}

export interface SessionInput {
  vaultId: string;
  perPayment: string;
  total: string;
  minutes: string;
  recipient: string;
}

export interface Authorized extends Submitted {
  /** False when IndexedDB is unavailable: the key lives only until this tab closes. */
  stored: boolean;
}

export async function authorizeSession(ctx: FlowContext, input: SessionInput): Promise<Authorized> {
  const { info, account } = ctx;
  const vault = vaultById(info, input.vaultId);
  const per = toBaseUnits(input.perPayment, vault.decimals);
  const total = toBaseUnits(input.total, vault.decimals);
  const minutes = Number(input.minutes);
  if (!Number.isFinite(minutes) || minutes <= 0) throw new Error('validity must be a positive number of minutes');
  if (minutes * 60_000 > MAX_SESSION_VALIDITY_MS) {
    throw new Error(`a session is valid for at most ${MAX_SESSION_VALIDITY_MS / 60_000} minutes`);
  }
  const recipientText = input.recipient.trim();
  const recipients = recipientText ? [recipientAccountId(parseRecipient(recipientText, networkOf(info)))] : [];
  const key = await generateSessionKey();
  const notAfterMs = Date.now() + Math.round(minutes * 60_000);
  const submitted = await runIntent(ctx, 'AUTHORIZE_SESSION', { sessionKey: toHex(key.publicKey),
    actions: ['TRANSFER'], limits: [{ vaultId: vault.vaultId, perPayment: per.toString(), cumulative: total.toString() }],
    recipients, notAfterMs }, [['Session key', toHex(key.publicKey)], ['May do', 'TRANSFER only (never withdraw)'],
    ['Limit', `${formatUnits(per, vault.decimals)} per payment, ${formatUnits(total, vault.decimals)} in total `
      + vaultLabel(vault)], ['Limit (base units)', `${per} per payment, ${total} in total`],
    ['Vault id', vault.vaultId], ['Asset id', assetIdText(vault)],
    ['Recipients', recipients.length ? recipients.map((id) => `${accountIdBech32(id, networkOf(info))} (hex ${id})`)
      .join(', ') : 'any registered account'],
    ['Valid until', new Date(notAfterMs).toLocaleString()]]);
  // The grant id is the AUTHORIZE_SESSION intent id.
  const stored = await saveSession({ id: `${info.chainId}/${account.accountId}/${submitted.intentId}`,
    chainId: info.chainId, accountId: account.accountId, grantId: submitted.intentId,
    publicKeyHex: toHex(key.publicKey), privateKey: key.privateKey, notAfterMs, createdAtMs: Date.now() });
  return { ...submitted, stored };
}

/** A transfer signed by the browser's session key: no wallet prompt and no review (the grant bounds it). */
export async function sessionPay(ctx: FlowContext, session: StoredSession, vaultId: string, toText: string,
                                 amountText: string): Promise<Submitted> {
  const vault = vaultById(ctx.info, vaultId);
  const amount = toBaseUnits(amountText, vault.decimals);
  const to = await checkedRecipient(ctx, toText);
  return runIntent(ctx, 'TRANSFER', { asset: { policyId: vault.asset.policyId, assetName: vault.asset.assetName },
    toAccount: to.accountId, amount: amount.toString() }, [['Send', `${formatUnits(amount, vault.decimals)} ${vaultLabel(vault)}`],
    ['Amount (base units)', amount.toString()], ['To account', accountIdBech32(to.accountId, networkOf(ctx.info))],
    ['To account (hex)', to.accountId], ['Signed by', `session ${short(session.grantId)}`]],
  session);
}

export async function revokeSession(ctx: FlowContext, grantId: string): Promise<Submitted> {
  return { ...await runIntent(ctx, 'REVOKE_SESSION', { grantId }, [['Revoke grant', grantId]]), grantId };
}

export interface WithdrawInput {
  vaultId: string;
  amount: string;
  all: boolean;
  /** Min-ADA credit to spend with a token withdrawal, in lovelace. */
  lovelace: string;
  to: string;
}

export async function withdraw(ctx: FlowContext, input: WithdrawInput, balance: Balance | undefined):
  Promise<Submitted> {
  const { info } = ctx;
  const vault = vaultById(info, input.vaultId);
  const amount = input.all ? BigInt(balance?.available ?? '0') : toBaseUnits(input.amount, vault.decimals);
  const vaultLovelace = vault.asset.ada ? 0n : BigInt(input.lovelace.trim() || '0');
  const recipient = parseAddress(input.to, networkOf(info));
  if (vault.asset.ada && amount < BigInt(info.minAdaPayout)) {
    throw new Error(`an ADA withdrawal is at least ${formatUnits(info.minAdaPayout, 6)} ADA`);
  }
  return runIntent(ctx, 'REQUEST_WITHDRAWAL', { vaultId: vault.vaultId, recipient: toHex(recipient),
    amount: amount.toString(), vaultLovelace: vaultLovelace.toString() },
  [['Withdraw', `${formatUnits(amount, vault.decimals)} ${vaultLabel(vault)}` + (vaultLovelace > 0n
    ? ` + ${formatUnits(vaultLovelace, 6)} ADA min-ADA credit` : '')],
  ['Amount (base units)', `${amount}` + (vaultLovelace > 0n ? ` + ${vaultLovelace} lovelace` : '')],
  ['Vault id', vault.vaultId], ['Asset id', assetIdText(vault)], ['To L1 address', addressToBech32(recipient)]]);
}

// ------------------------------------------------------------------ L1 deposit

export interface DepositInput {
  vaultId: string;
  amount: string;
  /** Lovelace sent with a token (its min-ADA credit). */
  lovelace: string;
}

export interface DepositSubmitted {
  vaultId: string;
  txId: string;
  outputIndex: number;
  /** The decoded amount, in display units with the ticker. */
  label: string;
  submittedAtMs: number;
}

/** The deposit review: every value decoded from the transaction itself, not from the API's summary. */
export function depositReviewFields(vault: VaultView, decoded: DecodedDeposit, accountId: string,
                                    refundAddress: Uint8Array, networkId: number): Field[] {
  const amount = vault.asset.ada ? `${formatUnits(decoded.quantity, 6)} ADA`
    : `${formatUnits(decoded.quantity, vault.decimals)} ${vaultLabel(vault)}`;
  return [['Deposit', amount], ['Amount (base units)', decoded.quantity.toString()], ['Asset id', assetIdText(vault)],
    ['Lovelace in the vault output', formatUnits(decoded.outputLovelace, 6) + ' ADA'],
    ['To vault', addressToBech32(addressBytes(vault.address))],
    ['Credited to (datum)', accountIdBech32(accountId, networkId)], ['Credited to (hex)', accountId],
    ['Refund address (datum)', addressToBech32(refundAddress)],
    ['Inputs', `${decoded.inputs.length}, all from this wallet`],
    ['Change back to you', formatUnits(decoded.change, 6) + ' ADA'],
    ['Fee', formatUnits(decoded.fee, 6) + ' ADA'], ['Valid until slot', decoded.ttl.toString()],
    ['Transaction id', decoded.transactionId]];
}

/**
 * Builds the deposit with the domain API, decodes and checks the unsigned transaction, shows the decoded review,
 * then has the wallet sign (signTx) and submit it. {@code onStep} reports progress for the status line.
 */
export async function deposit(ctx: FlowContext, input: DepositInput, onStep: (text: string) => void = () => {}):
  Promise<DepositSubmitted> {
  const { api, info, wallet, account, config } = ctx;
  const vault = vaultById(info, input.vaultId);
  // An ADA amount is converted at lovelace's 6 decimals, whatever the registry's display metadata says.
  const quantity = toBaseUnits(input.amount, vault.asset.ada ? 6 : vault.decimals);
  const lovelace = vault.asset.ada ? 0n : BigInt(input.lovelace.trim() || '0');
  onStep('Reading your UTxOs…');
  const utxos = await wallet.api.getUtxos();
  if (!utxos || utxos.length === 0) throw new Error('the wallet reports no UTxOs');
  onStep('Building the unsigned deposit…');
  const built = await api.buildDeposit({ vaultId: vault.vaultId, quantity: quantity.toString(),
    lovelace: lovelace.toString(), changeAddress: toHex(wallet.changeAddress), utxos,
    beneficiaryAccount: account.accountId });
  // The node built the transaction; this client decides what it does. The vault address is the registry's, read from
  // the configured node, and the datum is recomputed here.
  onStep('Checking the transaction…');
  const refund = credentialFromAddress(wallet.changeAddress);
  if (refund.kind !== PAYMENT_KEY) throw new Error('the change address has no payment key');
  const decoded = verifyDeposit(built.unsignedTxCborHex, {
    walletUtxos: utxos, changeAddress: wallet.changeAddress, vaultAddress: addressBytes(vault.address),
    asset: vault.asset, quantity, lovelace, datum: depositDatum(account.accountId, wallet.changeAddress,
      refund.keyHash), maxFee: BigInt(config.maxDepositFeeLovelace)
  });
  if (decoded.transactionId !== built.transactionId || decoded.outputIndex !== built.outputIndex) {
    throw new Error("the node's summary does not match the transaction it built; refusing to sign");
  }
  onStep('Review the decoded transaction…');
  await ctx.review({ kind: 'deposit', title: 'L1 deposit (decoded from the transaction)',
    fields: depositReviewFields(vault, decoded, account.accountId, wallet.changeAddress, networkOf(info)),
    bytesHex: built.unsignedTxCborHex });
  onStep('Waiting for the wallet to sign (signTx)…');
  const witnessSet = await wallet.api.signTx(built.unsignedTxCborHex, false);
  const signed = toHex(assembleSignedTx(fromHex(built.unsignedTxCborHex), fromHex(witnessSet)));
  onStep('Submitting to Cardano…');
  if (wallet.api.submitTx) await wallet.api.submitTx(signed);
  else await api.submitTx(signed);
  const label = vault.asset.ada ? `${formatUnits(decoded.quantity, 6)} ADA`
    : `${formatUnits(decoded.quantity, vault.decimals)} ${vaultLabel(vault)}`;
  return { vaultId: vault.vaultId, txId: decoded.transactionId, outputIndex: decoded.outputIndex, label,
    submittedAtMs: Date.now() };
}

/** How long the client follows a deposit before telling the user to check later. */
export const DEPOSIT_DEADLINE_MS = 10 * 60_000;

// ------------------------------------------------------------------ proofs and sessions

export interface Verdict {
  ok: boolean;
  text: string;
}

/**
 * Fetches the native proof of a shown fact and checks it is for exactly that key, value, height and root, and that
 * the node's verifier accepts it against the view's root.
 */
export async function verifyFact(api: PaymentsApi, fact: Fact): Promise<Verdict> {
  try {
    const { proof, verification } = await api.proof(fact);
    const mismatch = proofMismatch(fact, proof);
    if (verification.valid === true && mismatch === null) {
      return { ok: true, text: `verified at height ${fact.height} (root ${short(fact.stateRoot, 6)})` };
    }
    return { ok: false, text: `NOT verified (${mismatch ?? 'the verifier refused the proof'})` };
  } catch (error) {
    return { ok: false, text: `proof unavailable: ${error instanceof Error ? error.message : String(error)}` };
  }
}

export interface SessionsState {
  grants: GrantView[];
  local: StoredSession[];
}

/**
 * The account's committed grants and this browser's session keys, after deleting local keys that can never pay again
 * (expired, rejected, revoked or never executed).
 */
export async function loadSessions(api: PaymentsApi, info: Info, accountId: string): Promise<SessionsState> {
  const sessions = await api.sessions(accountId);
  const grants = sessions.grants;
  const committed = new Set(grants.map((grant) => grant.grantId));
  const local: StoredSession[] = [];
  for (const session of await listSessions(info.chainId, accountId)) {
    let receiptHeight: number | undefined;
    if (!committed.has(session.grantId) && session.notAfterMs > Date.now()) {
      // The grant id is the AUTHORIZE_SESSION intent id: its receipt says whether that intent was decided.
      receiptHeight = await api.receipt(session.grantId).then((receipt) => receipt.receipt?.height, () => undefined);
    }
    if (isStale(session, committed, sessions.committed.height, receiptHeight, Date.now(), info.maxIntentTtlMs)) {
      await deleteSession(session.id);
    } else {
      local.push(session);
    }
  }
  return { grants, local };
}

/** After an accepted revoke: the local key of that grant can never pay again. */
export async function forgetSession(chainId: string, accountId: string, grantId: string): Promise<void> {
  const local = (await listSessions(chainId, accountId)).find((session) => session.grantId === grantId);
  if (local) await deleteSession(local.id);
}

/**
 * Acts for an existing account instead of the credential's own: after ROTATE_OWNER the account keeps its id while a
 * new credential owns it. The account's committed owner must be the connected credential.
 */
export async function chooseAccount(api: PaymentsApi, credential: Credential, text: string, networkId: number):
  Promise<Credential> {
  const id = text.trim() ? parseAccountId(text, networkId) : credential.accountId;
  if (id !== credential.accountId) {
    const chosen = await api.account(id);
    const kind = credential.kind === PAYMENT_KEY ? 'PAYMENT_KEY' : 'STAKE_KEY';
    if (!chosen.registered || chosen.owner?.keyHash !== credential.keyHash || chosen.owner?.credentialKind !== kind) {
      throw new Error(`account ${shortId(accountIdBech32(id, networkId))} is not owned by this wallet's ${kind === 'PAYMENT_KEY' ? 'payment' : 'stake'}`
        + ' key at the committed height');
    }
  }
  return { ...credential, accountId: id };
}
