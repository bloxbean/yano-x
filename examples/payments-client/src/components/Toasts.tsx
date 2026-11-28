import clsx from 'clsx';
import { AlertTriangle, CheckCircle2, Info, X, XCircle } from 'lucide-react';
import { usePayments } from '../state';

/** Status toasts; the region is polite so a screen reader announces outcomes without interrupting. */
export function Toasts() {
  const { toasts, dismissToast } = usePayments();
  return (
    <div aria-live="polite" role="status" data-testid="toasts"
      className="pointer-events-none fixed inset-x-0 bottom-4 z-50 flex flex-col items-center gap-2 px-4 sm:items-end sm:pr-6">
      {toasts.map((toast) => {
        const Icon = { accent: CheckCircle2, rose: XCircle, amber: AlertTriangle, default: Info }[toast.tone];
        return (
          <div key={toast.id}
            className={clsx('rise pointer-events-auto flex w-full max-w-sm items-start gap-2.5 rounded-2xl border bg-surface px-3.5 py-3 shadow-[0_18px_40px_-22px_rgba(11,17,21,0.45)]',
              { accent: 'border-accent/30', rose: 'border-rose/30', amber: 'border-amber/30', default: 'border-line' }[toast.tone])}>
            <Icon size={17} className={clsx('mt-0.5 shrink-0',
              { accent: 'text-accent', rose: 'text-rose', amber: 'text-amber', default: 'text-muted' }[toast.tone])} />
            <div className="min-w-0 flex-1">
              <div className="text-sm font-semibold">{toast.title}</div>
              {toast.detail && <div className="text-xs text-muted">{toast.detail}</div>}
            </div>
            <button type="button" onClick={() => dismissToast(toast.id)} aria-label="Dismiss"
              className="rounded-md p-0.5 text-faint hover:bg-ink/5 hover:text-ink"><X size={14} /></button>
          </div>
        );
      })}
    </div>
  );
}
