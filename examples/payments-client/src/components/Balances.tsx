import { ArrowDownToLine, Coins, Info as InfoIcon } from 'lucide-react';
import type { AccountView, Balance, VaultView } from '../lib/api';
import { formatUnits } from '../lib/amounts';
import { assetIdText, factOf, vaultLabel, vaultStatus } from '../lib/display';
import { Badge, Button, Card, EmptyState, Skeleton } from '../ui';
import { ProofCheck } from './common';

/** One asset's balance: available, reserved for withdrawals, and the min-ADA credit, with its proof. */
export function BalanceCard({ balance, view, vault }: { balance: Balance; view: AccountView; vault?: VaultView }) {
  const partial = view.reservedFrom?.complete === false;
  const status = vaultStatus(balance.status);
  const trust = vault?.trustLabel;
  return (
    <Card className="rise relative overflow-hidden p-5" data-testid={`balance-${balance.vaultId}`}>
      <div className="pointer-events-none absolute -right-16 -top-16 h-40 w-40 rounded-full bg-gradient-to-br from-accent/15 to-transparent blur-2xl" />
      <div className="relative flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-accent/10 font-display text-sm font-bold text-accent-strong">
            {vaultLabel(balance).slice(0, 4)}
          </span>
          <div className="min-w-0">
            <div className="font-display text-lg font-semibold leading-tight">{vaultLabel(balance)}</div>
            <div className="truncate font-mono text-[10px] text-faint" title={assetIdText(balance)}>{assetIdText(balance)}</div>
          </div>
        </div>
        <div className="flex flex-wrap justify-end gap-1">
          <Badge tone={status.tone} title={`Registry status ${balance.status}`}>{status.label}</Badge>
          {trust && <Badge tone="cyan">{trust}</Badge>}
        </div>
      </div>
      <div className="relative mt-4">
        <div className="text-[11px] text-faint">Available</div>
        <div className="font-display text-3xl font-bold tracking-tight">
          <span data-testid="available">{formatUnits(balance.available, balance.decimals)}</span>
          <span className="ml-1.5 text-base font-semibold text-muted">{vaultLabel(balance)}</span>
        </div>
      </div>
      <dl className="relative mt-3 grid grid-cols-2 gap-2 text-sm">
        <div className="rounded-xl border border-line/70 bg-surface-2 px-3 py-2">
          <dt className="flex items-center gap-1 text-[11px] text-faint">Reserved
            {partial && <Badge tone="amber" className="ml-1 px-1.5 py-0 text-[10px]"
              title="Not every claim was scanned; the reserved total may be higher.">partial</Badge>}
          </dt>
          <dd className="font-mono text-ink" data-testid="reserved" title={partial ? 'not every claim was scanned' : undefined}>
            {formatUnits(balance.reserved, balance.decimals)}{partial ? ' (partial)' : ''}
          </dd>
        </div>
        <div className="rounded-xl border border-line/70 bg-surface-2 px-3 py-2">
          <dt className="text-[11px] text-faint" title="Lovelace deposited with tokens, withdrawable with them">Min-ADA credit</dt>
          <dd className="font-mono text-ink" data-testid="min-ada">{formatUnits(balance.minAdaCredit, 6)} ADA</dd>
        </div>
      </dl>
      <div className="relative mt-4 border-t border-line/70 pt-3">
        <ProofCheck fact={factOf(balance.proof, view.committed)} />
      </div>
    </Card>
  );
}

export function Balances({ view, vaults, loading, onDeposit }: {
  view: AccountView | undefined; vaults: VaultView[]; loading: boolean; onDeposit: () => void;
}) {
  return (
    <section className="space-y-3" aria-labelledby="balances-title">
      <div className="flex items-center justify-between gap-3">
        <h2 id="balances-title" className="flex items-center gap-2 text-lg font-semibold"><Coins size={18} className="text-accent" /> Balances</h2>
        {view && <span className="text-[11px] text-faint">at height <b className="font-mono text-muted">{view.committed.height}</b></span>}
      </div>
      <div className="grid gap-4 sm:grid-cols-2" data-testid="balances">
        {loading && !view && [0, 1].map((i) => (
          <Card key={i} className="space-y-3 p-5"><Skeleton className="h-10 w-32" /><Skeleton className="h-8 w-40" />
            <Skeleton className="h-12 w-full" /></Card>
        ))}
        {view?.balances.map((balance) => (
          <BalanceCard key={balance.vaultId} balance={balance} view={view}
            vault={vaults.find((vault) => vault.vaultId === balance.vaultId)} />
        ))}
      </div>
      {view && view.balances.length === 0 && (
        <EmptyState icon={<Coins size={18} />} title="No balances yet">
          <p>Deposit an asset from Cardano to start. It is credited once the L1 transaction is stable.</p>
          <Button className="mt-3" onClick={onDeposit}><ArrowDownToLine size={15} /> Deposit</Button>
        </EmptyState>
      )}
      {view && view.balances.length > 0 && (
        <p className="flex items-start gap-1.5 text-[11px] text-faint">
          <InfoIcon size={12} className="mt-0.5 shrink-0" />
          Tickers and decimals are the registry's display metadata; the asset id under each ticker is what the ledger
          holds. Verify checks the balance's state proof at the height shown.
        </p>
      )}
    </section>
  );
}
