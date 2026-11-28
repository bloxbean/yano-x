import clsx from 'clsx';
import { ChevronDown, Compass, Globe2, LogOut, UserRound, Wallet } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { addressToBech32 } from '../lib/bech32';
import { short } from '../lib/display';
import { errorMessage } from '../lib/errors';
import { PAYMENT_KEY } from '../lib/intents';
import { explorerHref, type Route } from '../route';
import { usePayments } from '../state';
import { Button, Copy, ErrorBox, Facts, inputClass, LiveDot, Logo, Modal } from '../ui';
import { WalletPicker } from './WalletPicker';

/** The network the chain's intent domain binds, as a label (the configured one wins). */
export function networkLabel(label: string, domain: { addressNetworkId: number; networkMagic: number }): string {
  if (label) return label;
  return domain.addressNetworkId === 1 ? `Mainnet · magic ${domain.networkMagic}`
    : `Testnet · magic ${domain.networkMagic}`;
}

/** The two views: the wallet and the public explorer. */
function Nav({ route }: { route: Route }) {
  const items = [
    { href: '#/', label: 'Wallet', icon: Wallet, active: route.view === 'wallet', testId: 'nav-wallet' },
    { href: explorerHref, label: 'Explorer', icon: Compass, active: route.view === 'explorer', testId: 'nav-explorer' },
  ];
  return (
    <nav aria-label="Views" className="flex shrink-0 items-center gap-1 rounded-xl border border-line bg-surface p-0.5">
      {items.map((item) => (
        <a key={item.href} href={item.href} aria-current={item.active ? 'page' : undefined} data-testid={item.testId}
          aria-label={item.label} title={item.label}
          className={clsx('flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-semibold transition',
            item.active ? 'bg-raised text-ink shadow-inner' : 'text-muted hover:text-ink')}>
          <item.icon size={14} className={item.active ? 'text-accent' : ''} aria-hidden />
          <span className="hidden sm:inline">{item.label}</span>
        </a>
      ))}
    </nav>
  );
}

export function Header({ route }: { route: Route }) {
  const { config, info, bootError, wallet, account, wallets, connect, rescanWallets, walletSdkError } = usePayments();
  const [picker, setPicker] = useState(false);
  const [details, setDetails] = useState(false);
  return (
    <header className="sticky top-0 z-30 border-b border-line/70 bg-surface/85 backdrop-blur-md">
      <div className="mx-auto flex max-w-7xl items-center gap-3 px-4 py-3 sm:gap-4 sm:px-6">
        <a href="#/" className="flex min-w-0 items-center gap-3" aria-label="Yano X Payments, home">
          <Logo size={34} />
          <span className="flex min-w-0 flex-col items-start leading-tight">
            <span className="font-display text-lg font-bold tracking-tight">
              Yano <span className="text-lime">X</span>
              <span className="ml-2 font-sans text-sm font-medium text-muted">Payments</span>
            </span>
            <span className="hidden truncate text-[11px] text-faint sm:block">
              {info ? `asset-payments · ${info.chainId}` : bootError ? 'No payments chain' : 'Connecting…'}
            </span>
          </span>
        </a>
        <Nav route={route} />
        <div className="ml-auto flex min-w-0 items-center gap-2">
          {info && config && (
            <span className="hidden items-center gap-1.5 rounded-full border border-amber/30 bg-amber/10 px-3 py-1 text-[11px] font-medium text-amber md:inline-flex"
              title={`The chain's intent domain: network magic ${info.intentDomain.networkMagic}, address network id ${info.intentDomain.addressNetworkId}. A wallet on another network is refused.`}>
              <Globe2 size={12} /> {networkLabel(config.networkLabel, info.intentDomain)}
            </span>
          )}
          <span data-testid="chain-badge"
            className="hidden items-center gap-1.5 rounded-full border border-line bg-surface px-3 py-1 text-[11px] text-muted sm:inline-flex"
            title="The committed app-ledger height this client reads at">
            {info ? <><LiveDot /> height <b className="font-mono font-medium text-ink">{info.committed.height}</b></>
              : bootError ? 'no payments chain' : 'connecting…'}
          </span>
          {wallet && account ? (
            <AccountChip onDetails={() => setDetails(true)} />
          ) : (
            <Button onClick={() => { rescanWallets(); setPicker(true); }} disabled={!info} data-testid="connect-wallet">
              <Wallet size={15} /> <span>Connect<span className="hidden sm:inline"> wallet</span></span>
            </Button>
          )}
        </div>
      </div>
      {picker && <WalletPicker wallets={wallets} onConnect={connect} onClose={() => setPicker(false)}
        sdkError={walletSdkError} />}
      {details && <AccountSheet onClose={() => setDetails(false)} />}
    </header>
  );
}

function AccountChip({ onDetails }: { onDetails: () => void }) {
  const { wallet, account, disconnect } = usePayments();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent | KeyboardEvent) => {
      if (event instanceof KeyboardEvent ? event.key === 'Escape' : !ref.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', close);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', close);
    };
  }, [open]);
  if (!wallet || !account) return null;
  return (
    <div className="relative" ref={ref}>
      <button type="button" onClick={() => setOpen(!open)} aria-expanded={open} aria-haspopup="menu"
        data-testid="account-chip"
        className="flex items-center gap-2 rounded-xl border border-line bg-surface px-2.5 py-1.5 text-xs transition hover:border-accent/50">
        <span className="grid h-6 w-6 place-items-center rounded-lg bg-accent text-[10px] font-bold text-white">
          {wallet.name.slice(0, 1).toUpperCase()}
        </span>
        <span className="hidden flex-col items-start leading-tight sm:flex">
          <span className="font-medium text-ink">{wallet.name}</span>
          <span className="font-mono text-[10px] text-faint">{short(account.accountId, 6)}</span>
        </span>
        <ChevronDown size={14} className="text-faint" />
      </button>
      {open && (
        <div role="menu" className="rise absolute right-0 z-40 mt-2 w-56 overflow-hidden rounded-2xl border border-line bg-surface p-1 shadow-[0_18px_40px_-22px_rgba(11,17,21,0.45)]">
          <button role="menuitem" type="button" onClick={() => { setOpen(false); onDetails(); }} data-testid="account-details"
            className="flex w-full items-center gap-2 rounded-xl px-3 py-2 text-left text-sm hover:bg-ink/5">
            <UserRound size={15} className="text-accent" /> Account details
          </button>
          <button role="menuitem" type="button" onClick={() => { setOpen(false); disconnect(); }} data-testid="disconnect"
            className="flex w-full items-center gap-2 rounded-xl px-3 py-2 text-left text-sm text-rose hover:bg-rose/5">
            <LogOut size={15} /> Disconnect
          </button>
        </div>
      )}
    </div>
  );
}

/** The connected wallet, its credential, and acting for another account after an owner rotation. */
function AccountSheet({ onClose }: { onClose: () => void }) {
  const { wallet, credential, account, chooseAccount } = usePayments();
  const [choice, setChoice] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!wallet || !credential || !account) return null;
  return (
    <Modal title="Account" icon={<UserRound size={18} />} onClose={onClose} data-testid="account-sheet"
      subtitle={`Connected with ${wallet.name}`}>
      <div className="space-y-4">
        <Facts rows={[
          ['Wallet', wallet.name],
          ['Signing address', <span className="break-all font-mono text-[12px]" data-testid="signing-address">
            {addressToBech32(wallet.signingAddress)}</span>],
          ['Credential', `${credential.kind === PAYMENT_KEY ? 'payment key' : 'stake key'} ${credential.keyHash}`],
          ['Account id', <span className="flex items-start gap-1"><span className="break-all font-mono text-[12px]">
            {account.accountId}</span><Copy value={account.accountId} label="Copy account id" /></span>],
          ...(wallet.quirks.length ? [['Wallet quirks', wallet.quirks.join(', ')] as [string, string]] : []),
        ]} />
        <form className="space-y-2" onSubmit={(event) => {
          event.preventDefault();
          setBusy(true);
          setError(null);
          chooseAccount(choice).then(onClose, (failure) => setError(errorMessage(failure))).finally(() => setBusy(false));
        }}>
          <label className="block text-sm font-medium" htmlFor="account-choose">Act for another account</label>
          <p className="text-[11px] text-faint">After an owner rotation the account keeps its id while this wallet owns
            it. The client acts for it only if its committed owner is this wallet's key. Leave empty for this wallet's
            own account.</p>
          <div className="flex flex-col gap-2 sm:flex-row">
            <input id="account-choose" className={`${inputClass} font-mono`} value={choice} data-testid="account-choose"
              onChange={(event) => setChoice(event.target.value)} placeholder="existing account id (56 hex)" />
            <Button type="submit" variant="ghost" busy={busy} data-testid="account-use">Use this account</Button>
          </div>
          {error && <ErrorBox>{error}</ErrorBox>}
        </form>
      </div>
    </Modal>
  );
}
