import clsx from 'clsx';
import { Blocks, ChevronDown, Rows3 } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { newestFirst, type BlockSummary } from '../../lib/chain';
import { short } from '../../lib/display';
import type { DecodedTx } from '../../lib/explorer';
import { ago } from '../../lib/txrow';
import { blockHref } from '../../route';
import { usePayments } from '../../state';
import { Button, Copy, EmptyState, ErrorBox, LiveDot, Skeleton } from '../../ui';
import { Panel, StreamBadge } from './Explorer';
import { useBlockPage, useBlocks, useDecoded, useLiveStream, useTip } from './hooks';
import { LiveTxRow, TxList } from './TxRow';

const PAGE = 10;
const MAX_BLOCKS = 100;
const MAX_TX_BLOCKS = 200;
const TX_PAGE = 25;

/** Keys that appeared after the list first rendered: those rows animate in. */
function useFresh(keys: string[]): Set<string> {
  const seen = useRef<Set<string> | null>(null);
  const fresh = new Set<string>();
  if (seen.current) for (const key of keys) if (!seen.current.has(key)) fresh.add(key);
  useEffect(() => {
    if (keys.length === 0) return;
    if (!seen.current) seen.current = new Set();
    for (const key of keys) seen.current.add(key);
  });
  return fresh;
}

export function Overview() {
  const live = useLiveStream();
  return (
    <div className="space-y-6">
      <Stats mode={live.mode} />
      <div className="grid gap-6 xl:grid-cols-[minmax(0,360px)_minmax(0,1fr)]">
        <LatestBlocks mode={live.mode} />
        <LatestTransactions mode={live.mode} streamed={live.messages} />
      </div>
    </div>
  );
}

function Stat({ label, children, testId }: { label: string; children: ReactNode; testId?: string }) {
  return (
    <div className="min-w-0 rounded-2xl border border-line bg-surface px-4 py-3" data-testid={testId}>
      <div className="text-[11px] font-medium text-faint">{label}</div>
      <div className="mt-1 flex min-w-0 items-center gap-1.5 font-mono text-lg text-ink">{children}</div>
    </div>
  );
}

function Stats({ mode }: { mode: 'connecting' | 'live' | 'polling' }) {
  const { info, now } = usePayments();
  const tip = useTip(mode);
  const latest = useBlockPage(null, 1, mode);
  const last = latest.data?.blocks.at(-1);
  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6" data-testid="explorer-stats">
      <Stat label="Tip height" testId="stat-tip">
        {tip.data ? <><LiveDot /><span data-testid="tip-height">{tip.data.height}</span></> : <Skeleton className="h-6 w-12" />}
      </Stat>
      <Stat label="Blocks">{tip.data ? tip.data.height : <Skeleton className="h-6 w-12" />}</Stat>
      <Stat label="Last block">
        {last ? <span className="font-sans text-base" title={new Date(last.timestamp).toISOString()}>{ago(last.timestamp, now)}</span>
          : <Skeleton className="h-6 w-16" />}
      </Stat>
      <Stat label="State root">
        {tip.data ? <><span className="truncate text-sm" title={tip.data.stateRoot}>{short(tip.data.stateRoot, 6)}</span>
          <Copy value={tip.data.stateRoot} label="Copy state root" /></> : <Skeleton className="h-6 w-28" />}
      </Stat>
      <Stat label="Vaults">{info?.vaults.length ?? '—'}</Stat>
      <Stat label="Updates"><span className="font-sans"><StreamBadge mode={mode} /></span></Stat>
      {tip.isError && <div className="col-span-full"><ErrorBox>Cannot read the chain tip: {(tip.error as Error).message}</ErrorBox></div>}
    </div>
  );
}

export function BlockItem({ block, now, fresh }: { block: BlockSummary; now: number; fresh?: boolean }) {
  return (
    <li className={clsx('flex items-center gap-3 px-4 py-2.5 transition-colors hover:bg-surface-2/70', fresh && 'row-fresh')}
      data-testid="block-row">
      <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-accent/10 text-accent"><Blocks size={16} /></span>
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline justify-between gap-2">
          <a href={blockHref(block.height)} className="font-mono text-sm font-semibold text-accent-strong hover:underline"
            data-testid="block-link">#{block.height}</a>
          <span className="text-[11px] text-faint" title={new Date(block.timestamp).toISOString()}>{ago(block.timestamp, now)}</span>
        </div>
        <div className="flex flex-wrap items-center gap-x-3 text-[11px] text-muted">
          <span>{block.messageCount} msg{block.messageCount === 1 ? '' : 's'}</span>
          <span title="Finality certificate signatures">{block.certSignatures} sig{block.certSignatures === 1 ? '' : 's'}</span>
          <span className="font-mono text-faint" title={`State root ${block.stateRoot}`}>root {short(block.stateRoot, 4)}</span>
        </div>
      </div>
    </li>
  );
}

function LatestBlocks({ mode }: { mode: 'connecting' | 'live' | 'polling' }) {
  const { now } = usePayments();
  const [count, setCount] = useState(PAGE);
  const page = useBlockPage(null, count, mode);
  const blocks = useMemo(() => [...(page.data?.blocks ?? [])].sort((a, b) => b.height - a.height), [page.data]);
  const fresh = useFresh(blocks.map((block) => String(block.height)));
  const oldest = blocks.at(-1)?.height ?? 1;
  return (
    <Panel title="Latest blocks" icon={<Blocks size={17} className="text-accent" />} data-testid="latest-blocks"
      aside={<span className="text-[11px] text-faint">as reported by the node</span>}>
      {page.isError && <div className="p-4"><ErrorBox>Cannot read blocks: {(page.error as Error).message}</ErrorBox></div>}
      <ul className="divide-y divide-line/70">
        {page.isLoading && [0, 1, 2, 3].map((i) => <li key={i} className="px-4 py-3"><Skeleton className="h-9 w-full" /></li>)}
        {blocks.map((block) => <BlockItem key={block.height} block={block} now={now} fresh={fresh.has(String(block.height))} />)}
      </ul>
      {page.data && blocks.length === 0 && <div className="p-4"><EmptyState icon={<Blocks size={18} />} title="No blocks yet" /></div>}
      {oldest > 1 && (
        <div className="border-t border-line/70 p-2">
          <Button variant="subtle" className="w-full text-xs" data-testid="blocks-older" busy={page.isFetching && count > blocks.length}
            disabled={count >= MAX_BLOCKS} onClick={() => setCount((n) => Math.min(MAX_BLOCKS, n + PAGE))}
            title={count >= MAX_BLOCKS ? 'Open a block or search a height to go further back' : undefined}>
            <ChevronDown size={14} /> {count >= MAX_BLOCKS ? 'Open a block to page further back' : 'Load older blocks'}
          </Button>
        </div>
      )}
    </Panel>
  );
}

function LatestTransactions({ mode, streamed }: { mode: 'connecting' | 'live' | 'polling';
  streamed: Parameters<typeof useDecoded>[0] }) {
  const tip = useTip(mode);
  const summaries = useBlockPage(null, PAGE, mode);
  const [blockCount, setBlockCount] = useState(PAGE);
  const [shown, setShown] = useState(TX_PAGE);
  // The tip a streamed message proves (its block is finalized) or the polled one, whichever is newer.
  const tipHeight = Math.max(tip.data?.height ?? 0, ...streamed.map((message) => message.height));
  const low = Math.max(1, tipHeight - blockCount + 1);
  const heights = useMemo(() => {
    const out: number[] = [];
    for (let height = tipHeight; height >= low && tipHeight > 0; height--) out.push(height);
    return out;
  }, [tipHeight, low]);
  const loaded = useBlocks(heights);
  const live = useDecoded(streamed);
  const timestamps = new Map<number, number>();
  for (const block of summaries.data?.blocks ?? []) timestamps.set(block.height, block.timestamp);
  loaded.blocks.forEach((block) => block && timestamps.set(block.height, block.timestamp));
  const rows = useMemo(() => {
    const byKey = new Map<string, DecodedTx>();
    for (const txs of loaded.decoded) for (const tx of txs ?? []) byKey.set(tx.key, tx);
    for (const tx of live) if (tx.height >= low && !byKey.has(tx.key)) byKey.set(tx.key, tx);
    return [...byKey.values()].sort(newestFirst);
  }, [loaded.decoded, live, low]);
  const visible = rows.slice(0, shown);
  const fresh = useFresh(visible.map((tx) => tx.key));
  const more = rows.length > shown || low > 1;
  return (
    <Panel title="Latest transactions" icon={<Rows3 size={17} className="text-accent" />} data-testid="latest-transactions"
      aside={<span className="flex items-center gap-2 text-[11px] text-faint"><StreamBadge mode={mode} /> as reported by the node</span>}>
      <TxList data-testid="tx-list">
        {visible.map((tx) => <LiveTxRow key={tx.key} tx={tx} timestamp={timestamps.get(tx.height)} fresh={fresh.has(tx.key)} />)}
        {loaded.loading && visible.length < shown && [0, 1, 2].map((i) => (
          <li key={`s${i}`} className="px-4 py-3"><Skeleton className="h-10 w-full" /></li>))}
      </TxList>
      {!loaded.loading && rows.length === 0 && tip.data && (
        <div className="p-4"><EmptyState icon={<Rows3 size={18} />} title="No transactions yet">
          Messages appear here as the chain finalizes them.</EmptyState></div>
      )}
      {loaded.errors > 0 && <div className="px-4 pb-3"><ErrorBox>{loaded.errors} block(s) could not be read.</ErrorBox></div>}
      {more && (
        <div className="border-t border-line/70 p-2">
          <Button variant="subtle" className="w-full text-xs" data-testid="tx-older" busy={loaded.loading}
            disabled={rows.length <= shown && blockCount >= MAX_TX_BLOCKS}
            onClick={() => {
              setShown((n) => n + TX_PAGE);
              if (rows.length < shown + TX_PAGE) setBlockCount((n) => Math.min(MAX_TX_BLOCKS, n + PAGE));
            }}>
            <ChevronDown size={14} /> Load older transactions
          </Button>
        </div>
      )}
    </Panel>
  );
}
