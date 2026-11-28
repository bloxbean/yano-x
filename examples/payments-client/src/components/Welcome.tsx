import { ArrowDownToLine, Coins, PenLine, Wallet } from 'lucide-react';
import { useState } from 'react';
import type { Info } from '../lib/api';
import { assetIdText, vaultLabel, vaultStatus } from '../lib/display';
import { usePayments } from '../state';
import { Badge, Button, Card, LiveDot } from '../ui';
import { WalletPicker } from './WalletPicker';

const STEPS = [
  { icon: Wallet, title: 'Connect a wallet', text: 'Any CIP-30 wallet with signData: Yano Wallet, Eternl, Lace, or the DevKit wallet on a devnet.' },
  { icon: PenLine, title: 'Register once', text: 'Sign a message to open your account. No L1 transaction, no fee.' },
  { icon: ArrowDownToLine, title: 'Deposit, send, withdraw', text: 'Move assets in from Cardano, pay in about a second, and withdraw back to L1.' },
];

/** Before a wallet is connected: what this is, the three steps, and the assets this ledger holds. */
export function Welcome({ info }: { info: Info }) {
  const { wallets, connect, rescanWallets, walletSdkError } = usePayments();
  const [picker, setPicker] = useState(false);
  return (
    <div className="space-y-10">
      <section className="rise grid gap-8 pt-4 lg:grid-cols-[1.2fr_1fr] lg:items-end">
        <div>
          <Badge tone="accent"><LiveDot /> Live on {info.chainId}</Badge>
          <h1 className="mt-4 text-4xl font-bold leading-[1.05] sm:text-5xl">
            Every asset, one account,{' '}
            <span className="bg-gradient-to-r from-accent to-lime bg-clip-text text-transparent">settled on Cardano.</span>
          </h1>
          <p className="mt-4 max-w-xl text-base text-muted">
            A reference wallet for the asset-payments app ledger. Deposit ADA and native tokens from Cardano, send them
            to any account with final receipts, pay from a limited session key without wallet prompts, and withdraw
            back to L1. Your Cardano keys stay in your wallet.
          </p>
          <div className="mt-6 flex flex-wrap gap-3">
            <Button onClick={() => { rescanWallets(); setPicker(true); }} data-testid="hero-connect">
              <Wallet size={15} /> Connect wallet
            </Button>
          </div>
        </div>
        <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-1">
          {STEPS.map((step, i) => (
            <div key={step.title} className="flex gap-3 rounded-2xl border border-line/70 bg-surface p-3.5">
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-accent/10 text-accent">
                <step.icon size={18} />
              </span>
              <div>
                <div className="text-sm font-semibold"><span className="text-faint">{i + 1}.</span> {step.title}</div>
                <div className="text-xs text-muted">{step.text}</div>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="space-y-4">
        <h2 className="text-sm font-semibold uppercase tracking-[0.18em] text-faint">Assets on this ledger</h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {info.vaults.map((vault) => {
            const status = vaultStatus(vault.status);
            return (
              <Card key={vault.vaultId} className="rise relative overflow-hidden p-5" data-testid={`vault-${vault.vaultId}`}>
                <div className="pointer-events-none absolute -right-16 -top-16 h-40 w-40 rounded-full bg-gradient-to-br from-accent/20 to-transparent blur-2xl" />
                <div className="relative flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="text-[11px] font-semibold uppercase tracking-[0.16em] text-accent-strong">
                      {vault.asset.ada ? 'Native asset' : 'Native token'}</div>
                    <h3 className="mt-1 text-xl font-semibold">{vaultLabel(vault)}</h3>
                  </div>
                  <Coins size={18} className="mt-1 text-faint" />
                </div>
                <p className="relative mt-2 break-all font-mono text-[11px] text-muted">{assetIdText(vault)}</p>
                <div className="relative mt-4 flex flex-wrap gap-1.5">
                  <Badge tone={status.tone}>{status.label}</Badge>
                  <Badge>{vault.decimals} decimals</Badge>
                  {vault.trustLabel && <Badge tone="cyan">{vault.trustLabel}</Badge>}
                </div>
              </Card>
            );
          })}
        </div>
      </section>
      {picker && <WalletPicker wallets={wallets} onConnect={connect} onClose={() => setPicker(false)}
        sdkError={walletSdkError} />}
    </div>
  );
}
