import { ChevronRight, Wallet } from 'lucide-react';
import { useState } from 'react';
import { errorMessage } from '../lib/errors';
import { DEVKIT_WALLET, UnsupportedWalletError, type WalletDescriptor } from '../lib/wallet';
import { EmptyState, ErrorBox, Modal, Spinner } from '../ui';

const HINTS: Record<string, string> = {
  yano: 'Yano Wallet (desktop, backed by DevKit)',
  [DEVKIT_WALLET]: 'Yaci DevKit wallet (devnet only)',
  eternl: 'Eternl',
  lace: 'Lace',
};

export function connectErrorText(error: unknown): string {
  return error instanceof UnsupportedWalletError ? `Unsupported wallet: ${error.message}`
    : `Cannot connect: ${errorMessage(error)}`;
}

/** Lists the CIP-30 wallets injected into this page. A wallet without signData is refused with a clear message. */
export function WalletPicker({ wallets, onConnect, onClose, sdkError }: {
  wallets: WalletDescriptor[];
  onConnect: (descriptor: WalletDescriptor, useStakeKey: boolean) => Promise<void>;
  onClose: () => void;
  sdkError?: string | null;
}) {
  const [useStakeKey, setUseStakeKey] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const connect = async (descriptor: WalletDescriptor) => {
    setBusy(descriptor.key);
    setError(null);
    try {
      await onConnect(descriptor, useStakeKey);
      onClose();
    } catch (failure) {
      setError(connectErrorText(failure));
    } finally {
      setBusy(null);
    }
  };

  return (
    <Modal title="Connect a wallet" icon={<Wallet size={18} />} onClose={onClose} data-testid="wallet-picker"
      subtitle="Any CIP-30 wallet with signData. Your Cardano keys stay in the wallet.">
      <div className="space-y-4">
        <div className="grid gap-2" data-testid="wallet-list">
          {wallets.length === 0 && (
            <EmptyState icon={<Wallet size={18} />} title="No CIP-30 wallet found in this page">
              Install a wallet with signData support (Yano Wallet on devnet, Eternl or Lace on testnets), or set
              devkitWalletSdkUrl for Yaci DevKit.
            </EmptyState>
          )}
          {wallets.map((descriptor) => (
            <button key={descriptor.key} type="button" data-testid={`wallet-${descriptor.key}`}
              disabled={busy !== null} onClick={() => void connect(descriptor)}
              className="group flex w-full items-center gap-3 rounded-xl border border-line bg-surface px-3 py-2.5 text-left transition hover:border-accent/50 hover:bg-accent/5 disabled:opacity-60">
              {descriptor.icon
                ? <img src={descriptor.icon} alt="" className="h-8 w-8 rounded-lg" />
                : <span className="grid h-8 w-8 place-items-center rounded-lg bg-ink/5 text-muted"><Wallet size={16} /></span>}
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-semibold">{descriptor.name}</span>
                <span className="block truncate text-[11px] text-faint">
                  {HINTS[descriptor.key] ?? 'CIP-30 wallet'} · window.cardano.{descriptor.key}
                </span>
              </span>
              {busy === descriptor.key ? <Spinner className="text-accent" />
                : <ChevronRight size={16} className="text-faint transition group-hover:text-accent" />}
            </button>
          ))}
        </div>
        <label className="flex items-start gap-2 text-sm text-muted">
          <input type="checkbox" checked={useStakeKey} onChange={(event) => setUseStakeKey(event.target.checked)}
            data-testid="use-stake-key" className="mt-0.5 h-4 w-4 accent-[var(--color-accent)]" />
          <span>Sign with the stake key (reward address) instead of the payment key</span>
        </label>
        {sdkError && <ErrorBox>Cannot load the DevKit wallet SDK: {sdkError}</ErrorBox>}
        {error && <ErrorBox data-testid="wallet-error">{error}</ErrorBox>}
      </div>
    </Modal>
  );
}
