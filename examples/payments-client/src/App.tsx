import { Info, ServerCrash } from 'lucide-react';
import { Dashboard } from './components/Dashboard';
import { Header } from './components/Header';
import { PendingReview } from './components/ReviewSheet';
import { Toasts } from './components/Toasts';
import { Welcome } from './components/Welcome';
import { Explorer } from './components/explorer/Explorer';
import { useRoute, type Route } from './route';
import { PaymentsProvider, usePayments } from './state';
import { Card, Logo, Skeleton } from './ui';

function Main({ route }: { route: Route }) {
  const { info, bootError, wallet } = usePayments();
  if (bootError && !info) {
    return (
      <Card className="mx-auto mt-10 max-w-xl p-6" data-testid="boot-error">
        <div className="flex items-center gap-2 font-semibold text-rose"><ServerCrash size={18} /> No payments chain</div>
        <p className="mt-2 break-words text-sm text-muted" role="alert">{bootError}</p>
        <p className="mt-3 text-xs text-faint">Check nodeUrl and chainId in payments-client.json, or that the node is
          running and hosts an asset-payments chain.</p>
      </Card>
    );
  }
  if (!info) {
    return (
      <div className="space-y-6 pt-4" aria-busy="true" aria-label="Loading the chain">
        <Skeleton className="h-6 w-48" /><Skeleton className="h-14 w-3/4" /><Skeleton className="h-5 w-2/3" />
        <div className="grid gap-4 sm:grid-cols-3"><Skeleton className="h-32" /><Skeleton className="h-32" /><Skeleton className="h-32" /></div>
      </div>
    );
  }
  // The explorer is public chain data: it needs no wallet.
  if (route.view === 'explorer') return <Explorer route={route} />;
  return wallet ? <Dashboard info={info} /> : <Welcome info={info} />;
}

function Footer() {
  return (
    <footer className="mx-auto mt-16 max-w-7xl border-t border-line/60 px-4 py-8 text-xs text-faint sm:px-6">
      <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
        <span className="flex items-center gap-2"><Logo size={16} /> Yano X — app ledgers that settle on Cardano</span>
        <a className="hover:text-accent-strong" href="https://yano-x.io" target="_blank" rel="noreferrer">yano-x.io</a>
        <a className="hover:text-accent-strong" href="https://github.com/bloxbean/yano-x" target="_blank" rel="noreferrer">GitHub</a>
        <span className="flex items-center gap-1.5"><Info size={12} /> Every action is reviewed here, signed in your
          wallet, and re-authorized by the ledger's admission.</span>
      </div>
    </footer>
  );
}

export default function App() {
  const route = useRoute();
  return (
    <PaymentsProvider>
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-3 focus:z-50 focus:rounded-lg focus:bg-surface focus:px-3 focus:py-2 focus:text-sm">
        Skip to content</a>
      <Header route={route} />
      <main id="main" className="mx-auto max-w-7xl px-4 pt-6 sm:px-6">
        <Main route={route} />
      </main>
      <Footer />
      <PendingReview />
      <Toasts />
    </PaymentsProvider>
  );
}
