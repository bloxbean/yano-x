/**
 * Reference web payment client for the asset-payments app ledger (#109 §3.1, ADR-059 D13/D14). Every app-ledger action
 * is an IntentEnvelopeV1 the user reviews here, signs with CIP-30 signData (or a session key), pre-checks with the
 * payments domain API, and submits through the node's ordinary message route. L1 deposits are built by the domain
 * API, signed with signTx and submitted by the wallet.
 */
import { ApiError, discoverChain, PaymentsApi, type AccountView, type GrantView, type Info, type ProofRef,
  type VaultView } from './lib/api';
import { formatUnits, toBaseUnits } from './lib/amounts';
import { addressToBech32 } from './lib/bech32';
import { loadConfig, loadScript, type ClientConfig } from './lib/config';
import { fromHex, randomBytes, toHex } from './lib/hex';
import { bodyFromJson, credentialFromAddress, encodeEnvelope, INTENT_TOPIC, intentId, ownerMessage,
  sessionMessage, type Action, type Authorizer, type Credential } from './lib/intents';
import { deleteSession, ed25519Supported, generateSessionKey, listSessions, saveSession, signWithSession,
  type StoredSession } from './lib/session';
import { assembleSignedTx } from './lib/tx';
import { addressBytes, connectWallet, installedWallets, signEnvelope, UnsupportedWalletError,
  type ConnectedWallet } from './lib/wallet';

let config: ClientConfig;
let api: PaymentsApi;
let info: Info;
let wallet: ConnectedWallet | null = null;
let account: Credential | null = null;
let view: AccountView | null = null;
let grants: GrantView[] = [];
let localSessions: StoredSession[] = [];

interface Activity {
  intentId: string;
  action: string;
  summary: string;
  notAfterMs: number;
  status: string;
  outcome?: string;
  proof?: ProofRef;
  verified?: string;
}

const activity: Activity[] = [];

const $ = <T extends HTMLElement = HTMLElement>(id: string): T => document.getElementById(id) as T;

function h(tag: string, attrs: Record<string, string> = {}, ...children: (Node | string | null)[]): HTMLElement {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (key === 'class') node.className = value;
    else node.setAttribute(key, value);
  }
  for (const child of children) if (child !== null) node.append(child);
  return node;
}

function short(hex: string, keep = 10): string {
  return hex.length <= 2 * keep + 3 ? hex : `${hex.slice(0, keep)}...${hex.slice(-keep)}`;
}

function message(error: unknown): string {
  if (error instanceof ApiError) return `${error.code}: ${error.message}`;
  return error instanceof Error ? error.message : String(error);
}

function vaultLabel(vault: VaultView): string {
  return vault.ticker || (vault.asset.ada ? 'ADA' : short(vault.asset.unit, 8));
}

function decimalsOf(vaultId: string): number {
  return info.vaults.find((vault) => vault.vaultId === vaultId)?.decimals ?? 0;
}

// ------------------------------------------------------------------ startup

async function start(): Promise<void> {
  config = await loadConfig(window.location);
  try {
    const chainId = config.chainId || await discoverChain(config.nodeUrl, config.apiKey);
    api = new PaymentsApi(config.nodeUrl, chainId, config.apiKey);
    info = await api.info();
  } catch (error) {
    $('chain-badge').textContent = 'no payments chain';
    $('chain-info').append(h('p', { class: 'error' }, `Cannot reach an asset-payments chain at ${config.nodeUrl}: `
      + message(error)));
    return;
  }
  renderChain();
  wireForms();
  if (config.devkitWalletSdkUrl) {
    window.addEventListener('yacidevkit:ready', renderWallets);
    await loadScript(config.devkitWalletSdkUrl).catch((error) => showWalletError(message(error)));
  }
  renderWallets();
  // Wallet extensions inject asynchronously after page load.
  for (const delay of [300, 1000, 2500]) setTimeout(renderWallets, delay);
  if (!(await ed25519Supported())) {
    const note = $('session-support');
    note.hidden = false;
    note.textContent = 'This browser lacks WebCrypto Ed25519, so session keys are unavailable here. Owner-signed '
      + 'actions still work.';
    ($('session-go') as HTMLButtonElement).disabled = true;
  }
  setInterval(pollActivity, 2_000);
  updateSections();
}

function renderChain(): void {
  const domain = info.intentDomain;
  $('chain-badge').textContent = `${config.networkLabel || `network id ${domain.addressNetworkId}`} · `
    + `${info.chainId} · height ${info.committed.height}`;
  const list = h('table', {}, h('tr', {}, h('th', {}, 'Asset'), h('th', {}, 'Vault id'), h('th', {}, 'Status'),
    h('th', {}, 'Vault address')));
  for (const vault of info.vaults) {
    list.append(h('tr', {}, h('td', {}, `${vaultLabel(vault)} (${vault.decimals} dp)`),
      h('td', { class: 'mono' }, short(vault.vaultId)), h('td', {}, vault.status),
      h('td', { class: 'mono' }, vault.address)));
  }
  $('chain-info').replaceChildren(
    h('p', { class: 'hint' }, `Intent domain: magic ${domain.networkMagic}, address network ${domain.addressNetworkId}, `
      + `genesis ${short(domain.genesisId)}, application ${domain.applicationId}. Withdrawals `
      + (info.withdrawalsEnabled ? 'enabled' : 'disabled (no payout observer)') + '.'),
    list);
  for (const id of ['deposit-vault', 'transfer-vault', 'session-vault', 'withdraw-vault']) {
    const select = $<HTMLSelectElement>(id);
    select.replaceChildren(...info.vaults.map((vault) => h('option', { value: vault.vaultId }, vaultLabel(vault))));
  }
  toggleTokenFields();
}

function renderWallets(): void {
  const wallets = installedWallets();
  const list = $('wallet-list');
  if (wallets.length === 0) {
    list.replaceChildren(h('span', { class: 'hint' }, 'No CIP-30 wallet found in this page. Install a wallet with '
      + 'signData support (Yano Wallet on devnet, Eternl or Lace on testnets), or set devkitWalletSdkUrl for Yaci '
      + 'DevKit.'));
    return;
  }
  list.replaceChildren(...wallets.map((descriptor) => {
    const button = h('button', { class: 'wallet-button secondary', 'data-testid': `wallet-${descriptor.key}` },
      descriptor.icon ? h('img', { src: descriptor.icon, alt: '' }) : null, `Connect ${descriptor.name}`);
    button.addEventListener('click', () => connect(descriptor));
    return button;
  }));
}

function showWalletError(text: string): void {
  const node = $('wallet-error');
  node.hidden = !text;
  node.textContent = text;
}

async function connect(descriptor: ReturnType<typeof installedWallets>[number]): Promise<void> {
  showWalletError('');
  wallet = null;
  account = null;
  try {
    wallet = await connectWallet(descriptor, info.intentDomain.addressNetworkId,
      $<HTMLInputElement>('use-stake-key').checked);
    account = credentialFromAddress(wallet.signingAddress);
  } catch (error) {
    wallet = null;
    showWalletError(error instanceof UnsupportedWalletError ? `Unsupported wallet: ${error.message}`
      : `Cannot connect: ${message(error)}`);
    updateSections();
    return;
  }
  $<HTMLInputElement>('withdraw-to').value = addressToBech32(wallet.changeAddress);
  $('wallet-info').replaceChildren(h('dl', {},
    h('dt', {}, 'Wallet'), h('dd', {}, wallet.name),
    h('dt', {}, 'Signing address'), h('dd', { class: 'mono', 'data-testid': 'signing-address' },
      addressToBech32(wallet.signingAddress)),
    h('dt', {}, 'Credential'), h('dd', {}, `${account.kind === 0 ? 'payment key' : 'stake key'} `
      + short(account.keyHash)),
    h('dt', {}, 'Account id'), h('dd', { class: 'mono', 'data-testid': 'account-id' }, account.accountId)));
  await refresh();
}

// ------------------------------------------------------------------ account views

async function refresh(): Promise<void> {
  if (!account) return;
  try {
    info = await api.info();
    $('chain-badge').textContent = $('chain-badge').textContent!.replace(/height \d+/, `height ${info.committed.height}`);
    view = await api.account(account.accountId);
    grants = view.registered ? (await api.sessions(account.accountId)).grants : [];
    localSessions = await listSessions(info.chainId, account.accountId);
    renderAccount();
    renderSessions();
    if (view.registered) await renderWithdrawals();
  } catch (error) {
    $('account-info').replaceChildren(h('p', { class: 'error' }, message(error)));
  }
  updateSections();
}

function renderAccount(): void {
  if (!view) return;
  $('account-info').replaceChildren(h('p', { 'data-testid': 'registration' }, view.registered
    ? `Registered (owner ${view.owner?.credentialKind}). State at height ${view.committed.height}.`
    : 'Not registered yet. Register once; deposits to an unregistered account are not credited.'));
  ($('register') as HTMLButtonElement).disabled = view.registered;
  const table = $('balances');
  table.replaceChildren(h('tr', {}, h('th', {}, 'Asset'), h('th', {}, 'Available'), h('th', {}, 'Reserved'),
    h('th', {}, 'Min-ADA credit'), h('th', {}, 'Proof')));
  for (const balance of view.balances) {
    const cell = h('td', {});
    const button = h('button', { class: 'secondary' }, 'Verify');
    button.addEventListener('click', () => verify(balance.proof, view!.committed.stateRoot, cell));
    cell.append(button);
    table.append(h('tr', { 'data-testid': `balance-${balance.vaultId}` },
      h('td', {}, balance.ticker || (balance.asset.ada ? 'ADA' : short(balance.asset.unit, 8))),
      h('td', { 'data-testid': 'available' }, formatUnits(balance.available, balance.decimals)),
      h('td', { 'data-testid': 'reserved' }, formatUnits(balance.reserved, balance.decimals)),
      h('td', {}, formatUnits(balance.minAdaCredit, 6)),
      cell));
  }
}

/** Fetches the native proof at the view's height and checks it binds the shown value and root. */
async function verify(ref: ProofRef, viewRoot: string, cell: HTMLElement): Promise<string> {
  cell.textContent = 'verifying...';
  try {
    const { proof, verification } = await api.proof(ref);
    const valueMatches = (proof.valueHex ?? null) === ref.valueHex;
    const rootMatches = proof.committedHeight !== ref.height || proof.stateRoot === viewRoot;
    const verdict = verification.valid === true && valueMatches && rootMatches
      ? `verified at height ${proof.committedHeight} (root ${short(String(proof.stateRoot), 6)})`
      : `NOT verified (valid=${verification.valid}, value ${valueMatches ? 'matches' : 'differs'})`;
    cell.textContent = verdict;
    cell.className = verdict.startsWith('verified') ? 'ok' : 'bad';
    return verdict;
  } catch (error) {
    cell.textContent = `proof unavailable: ${message(error)}`;
    cell.className = 'bad';
    return cell.textContent;
  }
}

function renderSessions(): void {
  const container = $('sessions');
  if (grants.length === 0 && localSessions.length === 0) {
    container.replaceChildren(h('p', { class: 'hint' }, 'No session grants.'));
    return;
  }
  const table = h('table', {}, h('tr', {}, h('th', {}, 'Grant'), h('th', {}, 'Limits (used / total)'),
    h('th', {}, 'Expires'), h('th', {}, 'Key here'), h('th', {}, 'Pay with session'), h('th', {}, '')));
  for (const grant of grants) {
    const local = localSessions.find((session) => session.grantId === grant.grantId);
    const limit = grant.limits[0];
    const decimals = limit ? decimalsOf(limit.vaultId) : 0;
    const to = h('input', { placeholder: 'account id or address', 'data-testid': 'session-pay-to' });
    const amount = h('input', { placeholder: 'amount', 'data-testid': 'session-pay-amount' }) as HTMLInputElement;
    const pay = h('button', { 'data-testid': 'session-pay' }, 'Pay (no wallet prompt)') as HTMLButtonElement;
    pay.disabled = !local || !limit;
    pay.addEventListener('click', () => guarded(pay, () => sessionPay(local!, limit.vaultId,
      (to as HTMLInputElement).value, amount.value)));
    const revoke = h('button', { class: 'secondary', 'data-testid': 'session-revoke' }, 'Revoke (signData)');
    revoke.addEventListener('click', () => guarded(revoke as HTMLButtonElement, () => revokeSession(grant.grantId)));
    table.append(h('tr', { 'data-testid': `grant-${grant.grantId}` },
      h('td', { class: 'mono' }, short(grant.grantId)),
      h('td', { 'data-testid': 'grant-usage' }, grant.limits.map((entry) =>
        `${formatUnits(entry.used, decimalsOf(entry.vaultId))} / ${formatUnits(entry.cumulative, decimals)}`
        + ` (max ${formatUnits(entry.perPayment, decimals)} each)`).join(', ')),
      h('td', {}, new Date(grant.notAfterMs).toLocaleString()),
      h('td', {}, local ? 'yes' : 'no'),
      h('td', {}, to, amount, pay),
      h('td', {}, revoke)));
  }
  for (const session of localSessions.filter((s) => !grants.some((grant) => grant.grantId === s.grantId))) {
    table.append(h('tr', {}, h('td', { class: 'mono' }, short(session.grantId)),
      h('td', { colspan: '5', class: 'hint' }, 'local key; its grant is pending, revoked or expired')));
  }
  container.replaceChildren(table);
}

async function renderWithdrawals(): Promise<void> {
  if (!account) return;
  const { withdrawals } = await api.withdrawals(account.accountId);
  const table = $('withdrawals');
  table.replaceChildren(h('tr', {}, h('th', {}, 'Claim'), h('th', {}, 'Amount'), h('th', {}, 'Recipient'),
    h('th', {}, 'Lifecycle'), h('th', {}, 'L1 payout')));
  for (const claim of withdrawals) {
    const decimals = decimalsOf(claim.vaultId);
    const tx = claim.l1TxId
      ? (config.explorerTxUrl ? h('a', { href: config.explorerTxUrl.replace('{tx}', claim.l1TxId), target: '_blank' },
        short(claim.l1TxId)) : h('span', { class: 'mono' }, claim.l1TxId))
      : h('span', { class: 'hint' }, claim.lifecycle === 'SETTLING' ? 'settlement batch open' : 'awaiting settlement');
    table.append(h('tr', { 'data-testid': `withdrawal-${claim.claimId}` },
      h('td', { class: 'mono' }, short(claim.claimId)),
      h('td', {}, `${formatUnits(claim.amount, decimals)}` + (claim.vaultLovelace !== '0'
        ? ` + ${formatUnits(claim.vaultLovelace, 6)} ADA` : '')),
      h('td', { class: 'mono' }, short(claim.recipientAddress ?? claim.recipientHex, 12)),
      h('td', { 'data-testid': 'lifecycle' }, claim.lifecycle),
      h('td', {}, tx)));
  }
}

function updateSections(): void {
  for (const section of document.querySelectorAll<HTMLElement>('.needs-wallet')) {
    section.classList.toggle('disabled', !wallet);
  }
  for (const section of document.querySelectorAll<HTMLElement>('.needs-account')) {
    section.classList.toggle('disabled', !wallet || !view?.registered);
  }
}

// ------------------------------------------------------------------ the intent pipeline

function review(title: string, fields: [string, string][], bytesHex: string): Promise<void> {
  const dialog = $<HTMLDialogElement>('review');
  $('review-title').textContent = title;
  $('review-fields').replaceChildren(...fields.flatMap(([label, value]) => [h('dt', {}, label), h('dd', {}, value)]));
  $('review-bytes').textContent = bytesHex;
  dialog.showModal();
  return new Promise((resolve, reject) => {
    const done = (approved: boolean) => {
      $('review-sign').onclick = null;
      $('review-cancel').onclick = null;
      dialog.oncancel = null;
      if (dialog.open) dialog.close();
      if (approved) resolve();
      else reject(new Error('cancelled'));
    };
    $('review-sign').onclick = () => done(true);
    $('review-cancel').onclick = () => done(false);
    // Escape closes a modal dialog without a click; treat it as a cancel.
    dialog.oncancel = () => done(false);
  });
}

/**
 * Encodes the intent locally, cross-checks the bytes with the node's encoder, gets the user's approval, signs, has
 * the domain API pre-check the signed message, and submits it through the host's SUBMIT route. Returns the IntentId.
 */
async function runIntent(action: Action, bodyJson: Record<string, unknown>, fields: [string, string][],
                         session?: StoredSession): Promise<string> {
  if (!wallet || !account) throw new Error('connect a wallet first');
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
    await review(action.replace('_', ' ').toLowerCase(), [...fields,
      ['Expires', new Date(notAfterMs).toLocaleTimeString()], ['Intent id', id]], toHex(envelope));
    const signed = await signEnvelope(wallet, envelope);
    wrapped = ownerMessage(fromHex(signed.signature), fromHex(signed.key));
    prepare = { kind: 'OWNER', coseSign1Hex: signed.signature, coseKeyHex: signed.key };
  }
  const prepared = await api.prepareMessage(prepare);
  if (prepared.bodyHex !== toHex(wrapped) || prepared.intentId !== id) {
    throw new Error('the prepared message differs from the signed intent; refusing to submit');
  }
  await api.submit(INTENT_TOPIC, prepared.bodyHex);
  activity.unshift({ intentId: id, action, summary: fields.map(([key, value]) => `${key}: ${value}`).join('; '),
    notAfterMs, status: 'submitted' });
  renderActivity();
  return id;
}

async function pollActivity(): Promise<void> {
  let changed = false;
  for (const entry of activity.filter((item) => item.status === 'submitted')) {
    try {
      const receipt = await api.receipt(entry.intentId);
      if (receipt.found && receipt.receipt) {
        entry.status = 'final';
        entry.outcome = receipt.receipt.outcome === 'ACCEPTED' ? `ACCEPTED at height ${receipt.receipt.height}`
          : `REJECTED ${receipt.receipt.reason}`;
        entry.proof = receipt.proof;
        changed = true;
        await onFinal(entry, receipt.receipt.outcome === 'ACCEPTED');
      } else if (Date.now() > entry.notAfterMs + 30_000) {
        entry.status = 'expired';
        entry.outcome = 'not executed before it expired (refused at admission or never sequenced)';
        changed = true;
      }
    } catch {
      // Keep polling.
    }
  }
  if (changed) renderActivity();
}

async function onFinal(entry: Activity, accepted: boolean): Promise<void> {
  if (entry.action === 'REVOKE_SESSION' && accepted) {
    const grantId = /grant: ([0-9a-f]{64})/.exec(entry.summary)?.[1];
    const local = localSessions.find((session) => session.grantId === grantId);
    if (local) await deleteSession(local.id);
  }
  await refresh();
}

function renderActivity(): void {
  const table = $('receipts');
  table.replaceChildren(h('tr', {}, h('th', {}, 'Action'), h('th', {}, 'Intent id'), h('th', {}, 'Details'),
    h('th', {}, 'Receipt'), h('th', {}, 'Proof')));
  for (const entry of activity) {
    const proofCell = h('td', {});
    if (entry.proof) {
      if (entry.verified) {
        proofCell.textContent = entry.verified;
      } else {
        const button = h('button', { class: 'secondary' }, 'Verify');
        button.addEventListener('click', async () => {
          entry.verified = await verify(entry.proof!, '', proofCell);
        });
        proofCell.append(button);
      }
    }
    table.append(h('tr', { 'data-testid': `receipt-${entry.intentId}` }, h('td', {}, entry.action),
      h('td', { class: 'mono' }, short(entry.intentId)), h('td', {}, entry.summary),
      h('td', { 'data-testid': 'outcome', class: entry.outcome?.startsWith('ACCEPTED') ? 'ok'
        : entry.outcome ? 'bad' : '' }, entry.outcome ?? 'pending'),
      proofCell));
  }
}

// ------------------------------------------------------------------ flows

function recipientAccount(text: string): string {
  const value = text.trim();
  if (/^[0-9a-fA-F]{56}$/.test(value)) return value.toLowerCase();
  return credentialFromAddress(addressBytes(value)).accountId;
}

function vaultById(id: string): VaultView {
  const vault = info.vaults.find((entry) => entry.vaultId === id);
  if (!vault) throw new Error('unknown vault');
  return vault;
}

async function register(): Promise<void> {
  await runIntent('REGISTER_ACCOUNT', {}, [['Account', account!.accountId],
    ['Owner', `${account!.kind === 0 ? 'payment' : 'stake'} key ${account!.keyHash}`]]);
}

async function deposit(): Promise<void> {
  const vault = vaultById($<HTMLSelectElement>('deposit-vault').value);
  const quantity = toBaseUnits($<HTMLInputElement>('deposit-amount').value, vault.decimals);
  const status = $('deposit-status');
  status.textContent = 'reading your UTxOs...';
  const utxos = await wallet!.api.getUtxos();
  if (!utxos || utxos.length === 0) throw new Error('the wallet reports no UTxOs');
  status.textContent = 'building the unsigned deposit...';
  const built = await api.buildDeposit({ vaultId: vault.vaultId, quantity: quantity.toString(),
    lovelace: vault.asset.ada ? '0' : $<HTMLInputElement>('deposit-lovelace').value.trim(),
    changeAddress: toHex(wallet!.changeAddress), utxos, beneficiaryAccount: account!.accountId });
  await review('L1 deposit', [['Deposit', `${formatUnits(built.quantity, vault.decimals)} ${vaultLabel(vault)}`],
    ['Lovelace in the output', formatUnits(built.outputLovelace, 6) + ' ADA'], ['To vault', vault.address],
    ['Credited to', account!.accountId], ['Fee', formatUnits(built.fee, 6) + ' ADA'],
    ['Transaction id', built.transactionId]], built.unsignedTxCborHex);
  status.textContent = 'waiting for the wallet to sign (signTx)...';
  const witnessSet = await wallet!.api.signTx(built.unsignedTxCborHex, false);
  const signed = toHex(assembleSignedTx(fromHex(built.unsignedTxCborHex), fromHex(witnessSet)));
  status.textContent = 'submitting...';
  const txId = wallet!.api.submitTx ? await wallet!.api.submitTx(signed) : await api.submitTx(signed);
  status.replaceChildren(h('span', { 'data-testid': 'deposit-tx' }, `Submitted ${txId || built.transactionId}. `),
    h('span', { id: 'deposit-state', 'data-testid': 'deposit-state' },
      'waiting for L1 stability and the next app-ledger block...'));
  const deadline = Date.now() + 10 * 60_000;
  const poll = async () => {
    try {
      const record = await api.deposit(vault.vaultId, built.transactionId, built.outputIndex);
      if (record.found && record.deposit) {
        $('deposit-state').textContent = record.deposit.status === 'CREDITED'
          ? `CREDITED ${formatUnits(record.deposit.creditedAmount, vault.decimals)} ${vaultLabel(vault)}`
          : `${record.deposit.status} (${record.deposit.reason})`;
        await refresh();
        return;
      }
    } catch {
      // Not committed yet, or the node is briefly unavailable: keep polling.
    }
    if (Date.now() < deadline) setTimeout(poll, 3_000);
    else $('deposit-state').textContent = 'not credited within 10 minutes; check the deposit record later';
  };
  setTimeout(poll, 3_000);
}

async function transfer(): Promise<void> {
  const vault = vaultById($<HTMLSelectElement>('transfer-vault').value);
  const to = recipientAccount($<HTMLInputElement>('transfer-to').value);
  const amount = toBaseUnits($<HTMLInputElement>('transfer-amount').value, vault.decimals);
  await runIntent('TRANSFER', { asset: { policyId: vault.asset.policyId, assetName: vault.asset.assetName },
    toAccount: to, amount: amount.toString() }, [['Send', `${formatUnits(amount, vault.decimals)} ${vaultLabel(vault)}`],
    ['To account', to]]);
}

async function authorizeSession(): Promise<void> {
  const vault = vaultById($<HTMLSelectElement>('session-vault').value);
  const per = toBaseUnits($<HTMLInputElement>('session-per').value, vault.decimals);
  const total = toBaseUnits($<HTMLInputElement>('session-total').value, vault.decimals);
  const minutes = Number($<HTMLInputElement>('session-minutes').value);
  if (!Number.isFinite(minutes) || minutes <= 0) throw new Error('validity must be a positive number of minutes');
  const recipientText = $<HTMLInputElement>('session-recipient').value.trim();
  const recipients = recipientText ? [recipientAccount(recipientText)] : [];
  const key = await generateSessionKey();
  const notAfterMs = Date.now() + Math.round(minutes * 60_000);
  const grantId = await runIntent('AUTHORIZE_SESSION', { sessionKey: toHex(key.publicKey), actions: ['TRANSFER'],
    limits: [{ vaultId: vault.vaultId, perPayment: per.toString(), cumulative: total.toString() }], recipients,
    notAfterMs }, [['Session key', toHex(key.publicKey)], ['May do', 'TRANSFER only (never withdraw)'],
    ['Limit', `${formatUnits(per, vault.decimals)} per payment, ${formatUnits(total, vault.decimals)} in total `
      + vaultLabel(vault)], ['Recipients', recipients.length ? recipients.join(', ') : 'any registered account'],
    ['Valid until', new Date(notAfterMs).toLocaleString()]]);
  await saveSession({ id: `${info.chainId}/${account!.accountId}/${grantId}`, chainId: info.chainId,
    accountId: account!.accountId, grantId, publicKeyHex: toHex(key.publicKey), privateKey: key.privateKey,
    notAfterMs, createdAtMs: Date.now() });
  localSessions = await listSessions(info.chainId, account!.accountId);
  renderSessions();
}

async function sessionPay(session: StoredSession, vaultId: string, toText: string, amountText: string): Promise<void> {
  const vault = vaultById(vaultId);
  const to = recipientAccount(toText);
  const amount = toBaseUnits(amountText, vault.decimals);
  await runIntent('TRANSFER', { asset: { policyId: vault.asset.policyId, assetName: vault.asset.assetName },
    toAccount: to, amount: amount.toString() }, [['Send', `${formatUnits(amount, vault.decimals)} ${vaultLabel(vault)}`],
    ['To account', to], ['Signed by', `session ${short(session.grantId)}`]], session);
}

async function revokeSession(grantId: string): Promise<void> {
  await runIntent('REVOKE_SESSION', { grantId }, [['Revoke grant', grantId]]);
}

async function withdraw(): Promise<void> {
  const vault = vaultById($<HTMLSelectElement>('withdraw-vault').value);
  const balance = view?.balances.find((entry) => entry.vaultId === vault.vaultId);
  const all = $<HTMLInputElement>('withdraw-all').checked;
  const amount = all ? BigInt(balance?.available ?? '0') : toBaseUnits($<HTMLInputElement>('withdraw-amount').value,
    vault.decimals);
  const vaultLovelace = vault.asset.ada ? 0n : BigInt($<HTMLInputElement>('withdraw-lovelace').value.trim() || '0');
  const recipient = addressBytes($<HTMLInputElement>('withdraw-to').value);
  if (vault.asset.ada && amount < BigInt(info.minAdaPayout)) {
    throw new Error(`an ADA withdrawal is at least ${formatUnits(info.minAdaPayout, 6)} ADA`);
  }
  await runIntent('REQUEST_WITHDRAWAL', { vaultId: vault.vaultId, recipient: toHex(recipient),
    amount: amount.toString(), vaultLovelace: vaultLovelace.toString() },
  [['Withdraw', `${formatUnits(amount, vault.decimals)} ${vaultLabel(vault)}` + (vaultLovelace > 0n
    ? ` + ${formatUnits(vaultLovelace, 6)} ADA min-ADA credit` : '')], ['To L1 address', addressToBech32(recipient)]]);
}

function toggleTokenFields(): void {
  const depositVault = info.vaults.find((vault) => vault.vaultId === $<HTMLSelectElement>('deposit-vault').value);
  for (const node of document.querySelectorAll<HTMLElement>('.token-only')) node.hidden = !!depositVault?.asset.ada;
  const withdrawVault = info.vaults.find((vault) => vault.vaultId === $<HTMLSelectElement>('withdraw-vault').value);
  for (const node of document.querySelectorAll<HTMLElement>('.token-only-w')) node.hidden = !!withdrawVault?.asset.ada;
}

function showActionError(text: string): void {
  const node = $('action-error');
  node.hidden = !text;
  node.textContent = text;
}

async function guarded(button: HTMLButtonElement, action: () => Promise<void>): Promise<void> {
  button.disabled = true;
  showActionError('');
  try {
    await action();
  } catch (error) {
    if (message(error) !== 'cancelled') showActionError(message(error));
  } finally {
    button.disabled = false;
  }
}

function wireForms(): void {
  const bind = (id: string, action: () => Promise<void>) => {
    const button = $<HTMLButtonElement>(id);
    button.addEventListener('click', () => guarded(button, action));
  };
  bind('register', register);
  bind('refresh', refresh);
  bind('deposit-go', deposit);
  bind('transfer-go', transfer);
  bind('session-go', authorizeSession);
  bind('withdraw-go', withdraw);
  $('deposit-vault').addEventListener('change', toggleTokenFields);
  $('withdraw-vault').addEventListener('change', toggleTokenFields);
}

void start();
