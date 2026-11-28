import { FileSignature, ShieldAlert } from 'lucide-react';
import type { ReviewRequest } from '../flows';
import { Cancelled } from '../lib/errors';
import { usePayments } from '../state';
import { Button, Facts, Modal } from '../ui';

/**
 * The last step before any wallet prompt. It shows every value in full, as this client decoded or encoded it: the
 * amount in display and base units, the full AssetId, the recipient, the account, the chain, genesis and network of
 * the intent domain, and the expiry (or, for a deposit, the values decoded from the unsigned transaction). Nothing
 * here is shortened, since the base units and asset id are what the ledger executes.
 */
export function ReviewSheet({ request, onApprove, onCancel }: {
  request: ReviewRequest; onApprove: () => void; onCancel: () => void;
}) {
  const deposit = request.kind === 'deposit';
  return (
    <Modal data-testid="review" title={`Review: ${request.title}`} wide onClose={onCancel}
      icon={<FileSignature size={18} />}
      subtitle={deposit ? 'Decoded by this client from the unsigned transaction the node built.'
        : 'Encoded by this client and cross-checked with the node\'s encoder.'}
      footer={<>
        <Button variant="ghost" onClick={onCancel} data-testid="review-cancel">Cancel</Button>
        <Button onClick={onApprove} data-testid="review-sign">
          <FileSignature size={15} /> {deposit ? 'Sign transaction (signTx)' : 'Sign message (signData)'}
        </Button>
      </>}>
      <div className="space-y-4">
        <div className="flex gap-2 rounded-xl border border-amber/30 bg-amber/5 px-3 py-2 text-xs text-muted">
          <ShieldAlert size={15} className="mt-0.5 shrink-0 text-amber" />
          <span>
            {deposit
              ? <>Your wallet shows the transaction next. Check that its outputs and fee match these values.</>
              : <>Your wallet shows these bytes <b className="text-ink">as hex, not as a Cardano transaction</b>. Check
                the fields here before you approve.</>}
          </span>
        </div>
        <Facts rows={request.fields} data-testid="review-fields" />
        <details className="text-xs text-muted">
          <summary className="cursor-pointer select-none hover:text-ink">Exact bytes to sign
            ({request.bytesHex.length / 2} bytes)</summary>
          <code data-testid="review-bytes"
            className="mt-2 block max-h-40 overflow-y-auto break-all rounded-xl border border-line/70 bg-surface-2 p-3 font-mono text-[11px]">
            {request.bytesHex}
          </code>
        </details>
      </div>
    </Modal>
  );
}

/** The review a running flow is waiting on. */
export function PendingReview() {
  const { pendingReview } = usePayments();
  if (!pendingReview) return null;
  return <ReviewSheet key={pendingReview.request.bytesHex} request={pendingReview.request}
    onApprove={pendingReview.resolve} onCancel={() => pendingReview.reject(new Cancelled())} />;
}
