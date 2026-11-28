import { Compass, Info as InfoIcon, Search } from 'lucide-react';
import { Component, useState, type FormEvent, type ReactNode } from 'react';
import { errorMessage } from '../../lib/errors';
import { parseSearch, resolveSearch } from '../../lib/search';
import { accountHref, blockHref, explorerHref, navigate, txHref, type Route } from '../../route';
import { usePayments } from '../../state';
import { Badge, Button, EmptyState, ErrorBox, inputClass } from '../../ui';
import { AccountPage } from './AccountPage';
import { BlockPage } from './BlockPage';
import { useDecodeContext } from './hooks';
import { Overview } from './Overview';
import { TxPage } from './TxPage';

/** The public explorer: every block and message of the payments chain, readable without a wallet. */
export function Explorer({ route }: { route: Extract<Route, { view: 'explorer' }> }) {
  const { info } = usePayments();
  return (
    <div className="space-y-6" data-testid="explorer">
      <section className="rise flex flex-wrap items-end justify-between gap-4 pt-2">
        <div className="min-w-0">
          <a href={explorerHref} className="text-xs font-semibold uppercase tracking-[0.18em] text-accent-strong hover:underline">
            Explorer · {info?.chainId}</a>
          <h1 className="mt-1 text-3xl font-bold sm:text-4xl">{TITLES[route.page]}</h1>
        </div>
        <SearchBox />
      </section>
      <AsReported />
      <PageBoundary key={JSON.stringify(route)}>
        {route.page === 'overview' && <Overview />}
        {route.page === 'block' && <BlockPage height={route.height} />}
        {route.page === 'tx' && <TxPage messageId={route.messageId} />}
        {route.page === 'account' && <AccountPage accountId={route.accountId} />}
      </PageBoundary>
      {route.page === 'not-found' && (
        <EmptyState icon={<Compass size={18} />} title="Nothing here">
          <span className="break-all">No explorer page at {route.path}.</span>{' '}
          <a className="font-medium text-accent-strong hover:underline" href={explorerHref}>Back to the overview</a>
        </EmptyState>
      )}
    </div>
  );
}

/** A node response a view cannot render (a malformed amount, say) fails that page only, with the reason. */
class PageBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  render() {
    return this.state.error
      ? <ErrorBox data-testid="explorer-page-error">This page could not show the node's data: {this.state.error.message}</ErrorBox>
      : this.props.children;
  }
}

const TITLES: Record<Extract<Route, { view: 'explorer' }>['page'], string> = {
  overview: 'Chain activity', block: 'Block', tx: 'Transaction', account: 'Account', 'not-found': 'Not found',
};

/** The trust note every explorer page carries. */
function AsReported() {
  return (
    <p className="flex items-start gap-2 rounded-2xl border border-amber/25 bg-amber/5 px-4 py-2.5 text-xs text-muted"
      data-testid="as-reported">
      <InfoIcon size={14} className="mt-0.5 shrink-0 text-amber" />
      <span>Blocks and messages are shown <b className="font-semibold text-ink">as reported by the node</b> and decoded
        here. An outcome or balance counts as verified only after its <b className="font-semibold text-ink">Verify</b> check
        passes: the record's state proof, checked against the state root at its height.</span>
    </p>
  );
}

export function SearchBox({ compact }: { compact?: boolean }) {
  const { api } = usePayments();
  const ctx = useDecodeContext();
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    const query = parseSearch(text);
    if (query.kind === 'invalid') {
      setError(query.reason);
      return;
    }
    if (!api) return;
    setBusy(true);
    try {
      const target = await resolveSearch(api, query, ctx);
      const href = target.page === 'block' ? blockHref(target.height) : target.page === 'tx' ? txHref(target.messageId)
        : accountHref(target.accountId);
      if (href) {
        navigate(href);
        setText('');
      }
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  };
  return (
    <form role="search" onSubmit={(event) => void submit(event)} className={compact ? 'w-full' : 'w-full sm:w-[440px]'}>
      <label htmlFor="explorer-search" className="sr-only">Search the chain</label>
      <div className="flex gap-2">
        <div className="relative min-w-0 flex-1">
          <Search size={15} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-faint" />
          <input id="explorer-search" data-testid="explorer-search" className={`${inputClass} pl-9 font-mono text-[13px]`}
            value={text} onChange={(event) => { setText(event.target.value); setError(null); }} autoComplete="off"
            spellCheck={false} placeholder="Height, tx or intent id, account, address" />
        </div>
        <Button type="submit" variant="ghost" busy={busy} data-testid="explorer-search-go">Search</Button>
      </div>
      {error && <div className="mt-2"><ErrorBox data-testid="search-error">{error}</ErrorBox></div>}
    </form>
  );
}

/** A panel with a title row, in the explorer's card style. */
export function Panel({ title, icon, aside, children, ...rest }: {
  title: ReactNode; icon?: ReactNode; aside?: ReactNode; children: ReactNode; 'data-testid'?: string;
}) {
  return (
    <section {...rest} className="rise overflow-hidden rounded-2xl border border-line bg-surface shadow-[0_1px_2px_rgba(11,17,21,0.04)]">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line/70 px-4 py-3">
        <h2 className="flex items-center gap-2 text-base font-semibold">{icon}{title}</h2>
        {aside}
      </div>
      {children}
    </section>
  );
}

export function StreamBadge({ mode }: { mode: 'connecting' | 'live' | 'polling' }) {
  return mode === 'live'
    ? <Badge tone="accent" data-testid="stream-mode" title="New messages arrive over the node's SSE stream">
      <span className="pulse-dot inline-block h-1.5 w-1.5 rounded-full bg-accent" /> Live</Badge>
    : <Badge tone={mode === 'polling' ? 'amber' : 'default'} data-testid="stream-mode"
      title={mode === 'polling' ? 'The SSE stream is unavailable; polling every 3 s' : 'Connecting to the SSE stream'}>
      {mode === 'polling' ? 'Polling' : 'Connecting…'}</Badge>;
}
