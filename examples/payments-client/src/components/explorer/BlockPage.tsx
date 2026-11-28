import { Blocks, ChevronLeft, ChevronRight, Rows3 } from 'lucide-react';
import { ApiError } from '../../lib/api';
import { ago } from '../../lib/txrow';
import { blockHref } from '../../route';
import { usePayments } from '../../state';
import { Copy, EmptyState, ErrorBox, Facts, Skeleton } from '../../ui';
import { Panel } from './Explorer';
import { decodeBlock, useBlock, useDecodeContext, useTip } from './hooks';
import { LiveTxRow, TxList } from './TxRow';

function HexFact({ value, label }: { value: string; label: string }) {
  return (
    <span className="flex items-start gap-1">
      <span className="min-w-0 break-all font-mono text-[12px] text-muted">{value}</span>
      <Copy value={value} label={`Copy ${label}`} />
    </span>
  );
}

/** One finalized block: its header as the node reports it, and its messages decoded. */
export function BlockPage({ height }: { height: number }) {
  const { now } = usePayments();
  const ctx = useDecodeContext();
  const block = useBlock(height);
  const tip = useTip();
  const missing = block.error instanceof ApiError && block.error.status === 404;
  const txs = block.data ? decodeBlock(block.data, ctx) : [];
  const data = block.data;
  return (
    <div className="space-y-6" data-testid="block-page">
      <nav className="flex flex-wrap items-center justify-between gap-2" aria-label="Block navigation">
        <span className="font-mono text-2xl font-semibold" data-testid="block-height">#{height}</span>
        <div className="flex gap-2">
          {height > 1 && <a href={blockHref(height - 1)} data-testid="block-prev"
            className="inline-flex items-center gap-1 rounded-xl border border-line bg-surface px-3 py-1.5 text-sm font-medium hover:border-accent/50 hover:text-accent-strong">
            <ChevronLeft size={15} /> #{height - 1}</a>}
          {(!tip.data || height < tip.data.height) && <a href={blockHref(height + 1)} data-testid="block-next"
            className="inline-flex items-center gap-1 rounded-xl border border-line bg-surface px-3 py-1.5 text-sm font-medium hover:border-accent/50 hover:text-accent-strong">
            #{height + 1} <ChevronRight size={15} /></a>}
        </div>
      </nav>
      {block.isLoading && <Skeleton className="h-48 w-full" />}
      {missing && (
        <EmptyState icon={<Blocks size={18} />} title={`No block at height ${height}`}>
          {tip.data ? `The chain's tip is at height ${tip.data.height}.` : 'The node has no finalized block there.'}
        </EmptyState>
      )}
      {block.isError && !missing && <ErrorBox>Cannot read the block: {(block.error as Error).message}</ErrorBox>}
      {data && (
        <>
          <Panel title="Header" icon={<Blocks size={17} className="text-accent" />}
            aside={<span className="text-[11px] text-faint">as reported by the node</span>}>
            <div className="p-4">
              <Facts data-testid="block-facts" rows={[
                ['Height', String(data.height)],
                ['Time', `${new Date(data.timestamp).toISOString()} (${ago(data.timestamp, now)})`],
                ['Chain', data.chainId],
                ['Messages', String(data.messages.length)],
                ['Certificate signatures', String(data.certSignatures)],
                ['State root', <HexFact value={data.stateRoot} label="state root" />],
                ['Messages root', <HexFact value={data.messagesRoot} label="messages root" />],
                ['Previous block hash', <HexFact value={data.prevHash} label="previous block hash" />],
                ['Proposer', <HexFact value={data.proposer} label="proposer key" />],
              ]} />
              <p className="mt-2 text-[11px] text-faint">The state root is the ledger state after this block; a record's
                Verify check on another page proves it against the root at its own height.</p>
            </div>
          </Panel>
          <Panel title={`Transactions (${txs.length})`} icon={<Rows3 size={17} className="text-accent" />}
            data-testid="block-transactions">
            <TxList data-testid="tx-list">
              {txs.map((tx) => <LiveTxRow key={tx.key} tx={tx} timestamp={data.timestamp} />)}
            </TxList>
            {txs.length === 0 && <div className="p-4"><EmptyState icon={<Rows3 size={18} />} title="No messages in this block" /></div>}
          </Panel>
        </>
      )}
    </div>
  );
}
