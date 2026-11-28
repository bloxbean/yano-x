import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, CheckCircle2 } from 'lucide-react';
import { useMemo } from 'react';
import { errorMessage } from '../lib/errors';
import { parseRecipient, type Recipient } from '../lib/ids';
import { recipientProblem, recipientSummary, resolveRecipient, type ResolvedRecipient } from '../lib/recipient';
import { usePayments } from '../state';
import { ErrorBox, Skeleton } from '../ui';
import { AccountId, useNetworkId } from './ids';

export interface RecipientState {
  recipient?: Recipient;
  resolved?: ResolvedRecipient;
  loading: boolean;
  /** Why this recipient cannot be paid (parse, network, node or registration), or null. */
  problem: string | null;
}

/**
 * A typed or linked recipient, parsed here (checksum, network) and resolved with the node: the account it names or
 * its address derives to, and whether that account is registered. The transfer flow resolves it again before review.
 */
export function useRecipient(text: string): RecipientState {
  const { api, account } = usePayments();
  const networkId = useNetworkId();
  const parsed = useMemo((): { recipient?: Recipient; error?: string } => {
    if (!text.trim()) return {};
    try {
      return { recipient: parseRecipient(text, networkId) };
    } catch (failure) {
      return { error: errorMessage(failure) ?? 'not a recipient' };
    }
  }, [text, networkId]);
  const resolved = useQuery({
    queryKey: ['recipient', api?.chainId, parsed.recipient?.kind, parsed.recipient?.kind === 'account'
      ? parsed.recipient.accountId : parsed.recipient?.addressHex],
    queryFn: () => resolveRecipient(api!, parsed.recipient!),
    enabled: !!api && !!parsed.recipient,
    staleTime: 5_000,
    retry: false,
  });
  const problem = parsed.error ?? (resolved.error ? errorMessage(resolved.error)
    : resolved.data ? recipientProblem(resolved.data, account?.accountId) : null);
  return { recipient: parsed.recipient, resolved: resolved.data, loading: !!parsed.recipient && resolved.isLoading,
    problem };
}

/** What the recipient resolves to, before review: the account (bech32 and hex) and its registration. */
export function RecipientCheck({ state }: { state: RecipientState }) {
  const networkId = useNetworkId();
  if (state.problem) return <ErrorBox data-testid="recipient-problem">{state.problem}</ErrorBox>;
  if (state.loading) return <Skeleton className="h-14 w-full" />;
  const resolved = state.resolved;
  if (!resolved) return null;
  return (
    <div className="rise space-y-1.5 rounded-xl border border-accent/25 bg-mint/15 px-3 py-2.5 text-sm" data-testid="recipient-check">
      <div className="flex items-center gap-1.5 text-xs font-medium text-accent-strong" data-testid="recipient-status">
        {resolved.registered ? <CheckCircle2 size={13} /> : <AlertTriangle size={13} />}
        {recipientSummary(resolved, networkId)}
      </div>
      {resolved.recipient.kind === 'address' && (
        <div className="text-[11px] text-faint">Address{' '}
          <span className="break-all font-mono" data-testid="recipient-address">{resolved.recipient.address}</span></div>
      )}
      <AccountId id={resolved.accountId} networkId={networkId} hex="line" testId="recipient-account" />
    </div>
  );
}
