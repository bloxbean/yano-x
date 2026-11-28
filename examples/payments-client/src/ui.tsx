/**
 * UI primitives. Card, Badge, Button, Spinner, LiveDot, Mono and Logo are adapted from the Yano X client showcase
 * (yano-x-demo-client/src/main/webui/src/ui.tsx), so the two apps share one look; Badge and Button text use the
 * stronger accent shades for contrast. Field, Modal, Skeleton, EmptyState, Copy and Facts are this client's own, in
 * the same style.
 */
import clsx from 'clsx';
import { Check, Copy as CopyIcon, X } from 'lucide-react';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';

export type Tone = 'default' | 'accent' | 'lime' | 'amber' | 'rose' | 'cyan' | 'violet';

export function Card({ className, children, ...rest }: { className?: string; children: ReactNode;
  'data-testid'?: string }) {
  return (
    <div {...rest}
      className={clsx('rounded-2xl border border-line bg-surface shadow-[0_1px_2px_rgba(11,17,21,0.04)]', className)}>
      {children}
    </div>
  );
}

export function Badge({ children, tone = 'default', className, title, ...rest }: {
  children: ReactNode; tone?: Tone; className?: string; title?: string; 'data-testid'?: string;
}) {
  const tones: Record<Tone, string> = {
    default: 'border-line bg-ink/5 text-muted',
    accent: 'border-accent/30 bg-accent/10 text-accent-strong',
    lime: 'border-lime/30 bg-lime/10 text-lime-strong',
    amber: 'border-amber/30 bg-amber/10 text-amber',
    rose: 'border-rose/30 bg-rose/10 text-rose',
    cyan: 'border-cyan/30 bg-cyan/10 text-cyan',
    violet: 'border-violet/30 bg-violet/10 text-violet',
  };
  return (
    <span title={title} {...rest}
      className={clsx('inline-flex items-center gap-1 whitespace-nowrap rounded-full border px-2 py-0.5 text-[11px] font-medium',
        tones[tone], className)}>
      {children}
    </span>
  );
}

export function Button({ children, onClick, variant = 'primary', disabled, type = 'button', className, title, busy,
  ...rest }: {
  children: ReactNode;
  onClick?: () => void;
  variant?: 'primary' | 'ghost' | 'subtle' | 'danger';
  disabled?: boolean;
  type?: 'button' | 'submit';
  className?: string;
  title?: string;
  /** Shows a spinner and disables the button (a signing or network step is running). */
  busy?: boolean;
  'data-testid'?: string;
  'aria-label'?: string;
}) {
  const variants = {
    primary: 'bg-accent text-white hover:bg-accent-strong shadow-[0_8px_20px_-10px_rgba(19,134,106,0.6)]',
    ghost: 'border border-line bg-surface text-ink hover:border-accent/50 hover:text-accent-strong',
    subtle: 'text-muted hover:text-ink hover:bg-ink/5',
    danger: 'border border-rose/30 bg-surface text-rose hover:bg-rose/5',
  };
  return (
    <button type={type} onClick={onClick} disabled={disabled || busy} title={title} aria-busy={busy || undefined} {...rest}
      className={clsx('inline-flex items-center justify-center gap-2 rounded-xl px-4 py-2 text-sm font-semibold transition',
        'disabled:cursor-not-allowed disabled:opacity-50', variants[variant], className)}>
      {busy && <Spinner />}
      {children}
    </button>
  );
}

export function Spinner({ className }: { className?: string }) {
  return (
    <span className={clsx('inline-block h-3.5 w-3.5 shrink-0 animate-spin rounded-full border-2 border-current border-t-transparent',
      className)} aria-hidden />
  );
}

export function LiveDot({ tone = 'accent' }: { tone?: 'accent' | 'amber' | 'rose' }) {
  const color = { accent: 'bg-accent', amber: 'bg-amber', rose: 'bg-rose' }[tone];
  return <span className={clsx('pulse-dot inline-block h-2 w-2 shrink-0 rounded-full', color)} aria-hidden />;
}

export function Mono({ children, className, title, ...rest }: { children: ReactNode; className?: string; title?: string;
  'data-testid'?: string }) {
  return <span title={title} {...rest} className={clsx('font-mono text-[12px] text-muted', className)}>{children}</span>;
}

/** Brand mark on its dark tile (the plane is off-white/lime, so it needs the tile on a light page). */
export function Logo({ size = 32 }: { size?: number }) {
  return <img src="/favicon.svg" width={size} height={size} alt="" className="rounded-[22%] shadow-sm" />;
}

export const inputClass = 'w-full rounded-xl border border-line bg-surface px-3 py-2 text-sm text-ink outline-none transition '
  + 'placeholder:text-faint focus:border-accent/60 focus:ring-2 focus:ring-accent/15 disabled:bg-surface-2 disabled:text-muted';

/** A labelled form field; the label wraps the control so it is announced with it. */
export function Field({ label, hint, optional, children, className }: {
  label: ReactNode; hint?: ReactNode; optional?: boolean; children: ReactNode; className?: string;
}) {
  return (
    <label className={clsx('block', className)}>
      <span className="mb-1.5 flex items-baseline justify-between gap-2 text-sm font-medium">
        {label}{optional && <span className="text-[11px] font-normal text-faint">optional</span>}
      </span>
      {children}
      {hint && <span className="mt-1 block text-[11px] text-faint">{hint}</span>}
    </label>
  );
}

export function ErrorBox({ children, ...rest }: { children: ReactNode; 'data-testid'?: string }) {
  return (
    <div role="alert" {...rest} className="rounded-xl border border-rose/30 bg-rose/10 px-3 py-2 text-sm text-rose">
      {children}
    </div>
  );
}

/**
 * A modal sheet on the native <dialog>: the browser traps focus, Escape closes it (as a cancel), and focus returns
 * to the opener. Mounted only while open.
 */
export function Modal({ title, subtitle, icon, onClose, children, footer, wide, ...rest }: {
  title: ReactNode; subtitle?: ReactNode; icon?: ReactNode; onClose: () => void; children: ReactNode;
  footer?: ReactNode; wide?: boolean; 'data-testid'?: string;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog || dialog.open) return;
    const opener = document.activeElement as HTMLElement | null;
    if (typeof dialog.showModal === 'function') dialog.showModal();
    else dialog.setAttribute('open', '');
    return () => {
      if (dialog.open && typeof dialog.close === 'function') dialog.close();
      opener?.focus?.();
    };
  }, []);
  return (
    <dialog ref={ref} aria-labelledby={titleId} {...rest}
      onCancel={(event) => { event.preventDefault(); onClose(); }}
      className={clsx('m-auto max-h-[calc(100dvh-32px)] w-[calc(100%-32px)] overflow-hidden rounded-3xl border border-line bg-surface p-0 text-ink',
        'shadow-[0_30px_80px_-30px_rgba(11,17,21,0.45)]', wide ? 'max-w-2xl' : 'max-w-lg')}>
      <div className="flex max-h-[calc(100dvh-32px)] flex-col">
        <div className="flex items-start justify-between gap-3 border-b border-line/70 px-5 py-4">
          <div className="flex min-w-0 items-start gap-3">
            {icon && <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-accent/10 text-accent">{icon}</span>}
            <div className="min-w-0">
              <h2 id={titleId} className="text-lg font-semibold">{title}</h2>
              {subtitle && <p className="text-xs text-muted">{subtitle}</p>}
            </div>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" data-testid="modal-close"
            className="rounded-lg p-1.5 text-muted transition hover:bg-ink/5 hover:text-ink">
            <X size={18} />
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div>
        {footer && <div className="flex flex-wrap items-center justify-end gap-2 border-t border-line/70 bg-surface-2/60 px-5 py-3">{footer}</div>}
      </div>
    </dialog>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={clsx('skeleton rounded-lg', className)} aria-hidden />;
}

export function EmptyState({ icon, title, children }: { icon: ReactNode; title: ReactNode; children?: ReactNode }) {
  return (
    <div className="rounded-2xl border border-dashed border-line bg-surface px-6 py-8 text-center">
      <div className="mx-auto grid h-10 w-10 place-items-center rounded-xl bg-ink/5 text-faint">{icon}</div>
      <div className="mt-2 text-sm font-medium">{title}</div>
      {children && <div className="mx-auto mt-1 max-w-sm text-xs text-muted">{children}</div>}
    </div>
  );
}

/** Copies a value and confirms it for a moment. */
export function Copy({ value, label = 'Copy' }: { value: string; label?: string }) {
  const [done, setDone] = useState(false);
  return (
    <button type="button" aria-label={`${label} to the clipboard`} title={label}
      onClick={() => {
        void navigator.clipboard?.writeText(value).then(() => {
          setDone(true);
          setTimeout(() => setDone(false), 1_200);
        }, () => {});
      }}
      className="inline-grid h-6 w-6 shrink-0 place-items-center rounded-md text-faint transition hover:bg-ink/5 hover:text-ink">
      {done ? <Check size={13} className="text-accent" /> : <CopyIcon size={13} />}
    </button>
  );
}

/** Long hex values, and bech32 ids and addresses, are set in mono and wrap anywhere. */
const MONO_VALUE = /^(?:[0-9a-f.]{40,}|[a-z_]+1[02-9ac-hj-np-z]{30,})$/;

/** A label/value grid (the showcase's FactsGrid); long hex values wrap instead of overflowing. */
export function Facts({ rows, className, ...rest }: { rows: [string, ReactNode][]; className?: string;
  'data-testid'?: string }) {
  return (
    <dl {...rest} className={clsx('grid grid-cols-[minmax(0,auto)_minmax(0,1fr)] gap-x-4 gap-y-1.5 rounded-xl border border-line/70 bg-surface-2 px-3 py-2.5 text-sm',
      className)}>
      {rows.map(([label, value], i) => (
        <div key={label + i} className="contents">
          <dt className="text-xs text-faint">{label}</dt>
          <dd className={clsx('min-w-0 break-words',
            typeof value === 'string' && MONO_VALUE.test(value) ? 'break-all font-mono text-[12px] text-muted' : 'text-ink')}>
            {value}
          </dd>
        </div>
      ))}
    </dl>
  );
}
