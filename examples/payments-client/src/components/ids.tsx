/**
 * How identifiers look: an account id as bech32 on the chain's network (its hex on a secondary line, in a details
 * disclosure or in the title), an owner credential as its L1 address. Every element carries data-bech32 / data-hex
 * hooks with the full values.
 */
import clsx from 'clsx';
import { accountIdBech32, ownerAddress, shortId } from '../lib/ids';
import { accountHref } from '../route';
import { usePayments } from '../state';
import { Copy } from '../ui';

/** The chain's address network id (0 until the chain info has loaded, which every view waits for). */
export function useNetworkId(): number {
  return usePayments().info?.intentDomain.addressNetworkId ?? 0;
}

/**
 * An account id: bech32 with a copy button; the hex below it (hex="line"), in a disclosure (hex="details") or only in
 * the title (hex="title"). link makes the bech32 the account's explorer link.
 */
export function AccountId({ id, networkId, hex = 'title', link, compact, className, testId = 'account-id-value' }: {
  id: string; networkId: number; hex?: 'line' | 'details' | 'title'; link?: boolean; compact?: boolean;
  className?: string; testId?: string;
}) {
  const bech32 = accountIdBech32(id, networkId);
  const text = compact ? shortId(bech32, 6) : bech32;
  const href = link ? accountHref(id, networkId) : undefined;
  const value = href
    ? <a href={href} data-testid={testId} data-bech32={bech32} data-hex={id} title={hex === 'title' ? `${bech32}\n${id}` : bech32}
      className="min-w-0 break-all font-mono text-[12px] text-accent-strong hover:underline">{text}</a>
    : <span data-testid={testId} data-bech32={bech32} data-hex={id} title={hex === 'title' ? `${bech32}\n${id}` : undefined}
      className="min-w-0 break-all font-mono text-[12px]">{text}</span>;
  return (
    <span className={clsx('flex min-w-0 flex-col gap-0.5', className)}>
      <span className="flex min-w-0 items-start gap-1">{value}<Copy value={bech32} label="Copy account id" /></span>
      {hex === 'line' && (
        <span className="flex min-w-0 items-start gap-1 text-[11px] text-faint">
          <span className="shrink-0">hex</span>
          <span className="min-w-0 break-all font-mono" data-testid={`${testId}-hex`}>{id}</span>
          <Copy value={id} label="Copy account id (hex)" />
        </span>
      )}
      {hex === 'details' && (
        <details className="text-[11px] text-faint">
          <summary className="cursor-pointer select-none hover:text-ink">hex</summary>
          <span className="flex min-w-0 items-start gap-1">
            <span className="min-w-0 break-all font-mono" data-testid={`${testId}-hex`}>{id}</span>
            <Copy value={id} label="Copy account id (hex)" />
          </span>
        </details>
      )}
    </span>
  );
}

/** An owner credential as its L1 address (enterprise for a payment key, reward for a stake key), with its key hash. */
export function OwnerAddress({ owner, networkId, testId = 'owner-address' }: {
  owner: { credentialKind: string | number; keyHash: string }; networkId: number; testId?: string;
}) {
  let view;
  try {
    view = ownerAddress(owner, networkId);
  } catch {
    return <span className="break-all font-mono text-[12px] text-muted">{String(owner.credentialKind)} {owner.keyHash}</span>;
  }
  return (
    <span className="flex min-w-0 flex-col gap-0.5">
      <span className="flex min-w-0 items-start gap-1">
        <span className="min-w-0 break-all font-mono text-[12px]" data-testid={testId} data-kind={view.kind}
          title={`key hash ${owner.keyHash}`}>{view.address}</span>
        <Copy value={view.address} label="Copy owner address" />
      </span>
      <span className="text-[11px] text-faint">{view.hint} Key hash{' '}
        <span className="break-all font-mono" data-testid={`${testId}-key-hash`}>{owner.keyHash}</span></span>
    </span>
  );
}

/** The label an owner row uses. */
export function ownerLabel(owner: { credentialKind: string | number }): string {
  return owner.credentialKind === 'STAKE_KEY' || owner.credentialKind === 1 ? 'Owner (stake key)' : 'Owner (payment key)';
}
