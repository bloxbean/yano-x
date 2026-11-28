import { useQuery } from '@tanstack/react-query';
import { ArrowUpFromLine, CheckCircle2, ChevronDown, Coins, Rows3, UserPlus, UserRound } from 'lucide-react';
import { useState } from 'react';
import { newestFirst } from '../../lib/chain';
import { involves } from '../../lib/explorer';
import { usePayments } from '../../state';
import { Badge, Button, EmptyState, ErrorBox, Skeleton } from '../../ui';
import { BalanceCard } from '../Balances';
import { AccountId, OwnerAddress, ownerLabel, useNetworkId } from '../ids';
import { ClaimCard } from '../Withdrawals';
import { Panel } from './Explorer';
import { useBlocks, useTip } from './hooks';
import { LiveTxRow, TxList } from './TxRow';

const SCAN = 50;
const MAX_SCAN = 500;

/**
 * One account: its registration, balances (each with its proof), withdrawals, and the transactions that name it. The
 * node has no per-account message index (messages are sent by member keys), so the transactions come from scanning
 * recent blocks here, 50 at a time.
 */
export function AccountPage({ accountId }: { accountId: string }) {
  const { api, info } = usePayments();
  const view = useQuery({
    queryKey: ['x-account', api?.chainId, accountId],
    queryFn: () => api!.account(accountId),
    enabled: !!api,
    refetchInterval: 10_000,
  });
  const withdrawals = useQuery({
    queryKey: ['x-withdrawals', api?.chainId, accountId],
    queryFn: () => api!.withdrawals(accountId),
    enabled: !!api,
    refetchInterval: 15_000,
  });
  const registered = !!view.data?.registered;
  const networkId = useNetworkId();
  return (
    <div className="space-y-6" data-testid="account-page">
      <section className="rise rounded-3xl border border-line bg-surface p-5 sm:p-6">
        <div className="flex items-start gap-3">
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-accent/10 text-accent"><UserRound size={18} /></span>
          <div className="min-w-0">
            <div className="text-[11px] font-medium text-faint">Account id</div>
            <AccountId id={accountId} networkId={networkId} hex="line" testId="explorer-account-id" />
            <div className="mt-2 flex flex-wrap gap-1.5">
              {view.data ? (
                <Badge tone={registered ? 'accent' : 'amber'} data-testid="explorer-registration">
                  {registered ? <CheckCircle2 size={12} /> : <UserPlus size={12} />}
                  {registered ? 'Registered' : 'Not registered'}
                </Badge>
              ) : view.isLoading ? <Skeleton className="h-5 w-24" /> : null}
              {view.data?.owner && (
                <Badge title={`owner key hash ${view.data.owner.keyHash}`}>owner: {view.data.owner.credentialKind.toLowerCase().replace('_', ' ')}</Badge>
              )}
              {view.data && <Badge>at height {view.data.committed.height}</Badge>}
            </div>
            {view.data?.owner && (
              <div className="mt-3 min-w-0" data-testid="explorer-owner">
                <div className="text-[11px] font-medium text-faint">{ownerLabel(view.data.owner)}</div>
                <OwnerAddress owner={view.data.owner} networkId={networkId} testId="explorer-owner-address" />
              </div>
            )}
          </div>
        </div>
      </section>
      {view.isError && <ErrorBox>Cannot read the account: {(view.error as Error).message}</ErrorBox>}
      <section className="space-y-3" aria-labelledby="x-balances">
        <h2 id="x-balances" className="flex items-center gap-2 text-lg font-semibold"><Coins size={18} className="text-accent" /> Balances</h2>
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3" data-testid="explorer-balances">
          {view.isLoading && [0, 1].map((i) => <Skeleton key={i} className="h-48" />)}
          {view.data?.balances.map((balance) => (
            <BalanceCard key={balance.vaultId} balance={balance} view={view.data}
              vault={info?.vaults.find((vault) => vault.vaultId === balance.vaultId)} />
          ))}
        </div>
        {view.data && view.data.balances.length === 0 && (
          <EmptyState icon={<Coins size={18} />} title="No balances">This account holds nothing on the ledger.</EmptyState>
        )}
      </section>
      <AccountTransactions accountId={accountId} />
      <section className="space-y-3" aria-labelledby="x-withdrawals">
        <h2 id="x-withdrawals" className="flex items-center gap-2 text-lg font-semibold">
          <ArrowUpFromLine size={18} className="text-accent" /> Withdrawals</h2>
        <div className="space-y-3" data-testid="explorer-withdrawals">
          {withdrawals.isLoading && <Skeleton className="h-24 w-full" />}
          {withdrawals.isError && <ErrorBox>Cannot read the withdrawals: {(withdrawals.error as Error).message}</ErrorBox>}
          {withdrawals.data?.withdrawals.map((claim) => <ClaimCard key={claim.claimId} claim={claim} />)}
          {withdrawals.data && withdrawals.data.withdrawals.length === 0 && (
            <EmptyState icon={<ArrowUpFromLine size={18} />} title="No withdrawals" />
          )}
        </div>
      </section>
    </div>
  );
}

function AccountTransactions({ accountId }: { accountId: string }) {
  const tip = useTip();
  const [floor, setFloor] = useState<number | null>(null);
  const tipHeight = tip.data?.height ?? 0;
  const low = Math.max(1, floor ?? tipHeight - SCAN + 1, tipHeight - MAX_SCAN + 1);
  const heights: number[] = [];
  for (let height = tipHeight; height >= low && tipHeight > 0; height--) heights.push(height);
  const scan = useBlocks(heights);
  const timestamps = new Map<number, number>();
  scan.blocks.forEach((block) => block && timestamps.set(block.height, block.timestamp));
  const rows = scan.decoded.flatMap((txs) => txs ?? []).filter((tx) => involves(tx, accountId)).sort(newestFirst);
  return (
    <Panel title="Transactions" icon={<Rows3 size={17} className="text-accent" />} data-testid="account-transactions"
      aside={tipHeight > 0 && <span className="text-[11px] text-faint" data-testid="scan-range">
        blocks #{low}–#{tipHeight} scanned{scan.loading ? '…' : ''} · as reported by the node</span>}>
      <TxList data-testid="tx-list">
        {rows.map((tx) => <LiveTxRow key={tx.key} tx={tx} timestamp={timestamps.get(tx.height)} />)}
        {scan.loading && <li className="px-4 py-3"><Skeleton className="h-10 w-full" /></li>}
      </TxList>
      {!scan.loading && rows.length === 0 && tipHeight > 0 && (
        <div className="p-4"><EmptyState icon={<Rows3 size={18} />} title="No transactions in the scanned blocks">
          Signed intents, transfers to this account and deposits crediting it are listed here.</EmptyState></div>
      )}
      {scan.errors > 0 && <div className="px-4 pb-3"><ErrorBox>{scan.errors} block(s) could not be read.</ErrorBox></div>}
      {low > 1 && (
        <div className="border-t border-line/70 p-2">
          <Button variant="subtle" className="w-full text-xs" data-testid="account-scan-older" busy={scan.loading}
            disabled={tipHeight - low + 1 >= MAX_SCAN} onClick={() => setFloor(Math.max(1, low - SCAN))}>
            <ChevronDown size={14} /> {tipHeight - low + 1 >= MAX_SCAN ? `Scanned the newest ${MAX_SCAN} blocks`
              : `Scan ${SCAN} older blocks`}
          </Button>
        </div>
      )}
    </Panel>
  );
}
