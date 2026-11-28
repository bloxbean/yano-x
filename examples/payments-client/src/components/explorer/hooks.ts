/**
 * The explorer's reads: the tip and block summaries (polled, or refreshed by the SSE stream), finalized blocks
 * (immutable, cached for good), the live stream itself, and each message's outcome from its receipt or deposit
 * record. Everything is node data; outcomes are bound to the message in lib/explorer.ts.
 */
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import type { PaymentsApi, ReceiptView } from '../../lib/api';
import { MAX_BODY_BYTES, parseMessage, type Block, type ChainMessage } from '../../lib/chain';
import {
  contextOf, decodeMessage, depositOutcome, receiptOutcome, type DecodeContext, type DecodedTx, type Outcome
} from '../../lib/explorer';
import { usePayments } from '../../state';

export type StreamMode = 'connecting' | 'live' | 'polling';

/** The chain's tip; polled every 3 s, or every 15 s while the SSE stream pushes new blocks. */
export function useTip(mode: StreamMode = 'polling') {
  const { api } = usePayments();
  return useQuery({
    queryKey: ['x-tip', api?.chainId],
    queryFn: () => api!.tip(),
    enabled: !!api,
    refetchInterval: mode === 'live' ? 15_000 : 3_000,
  });
}

/** A page of block summaries: the newest page (from = null) is refreshed, an older one is final. */
export function useBlockPage(from: number | null, limit: number, mode: StreamMode = 'polling') {
  const { api } = usePayments();
  return useQuery({
    queryKey: from === null ? ['x-blocks-latest', api?.chainId, limit] : ['x-blocks', api?.chainId, from, limit],
    queryFn: () => api!.blocks(from, limit),
    enabled: !!api,
    refetchInterval: from === null ? (mode === 'live' ? 15_000 : 3_000) : false,
    staleTime: from === null ? 2_000 : Infinity,
  });
}

export function blockQuery(api: PaymentsApi, height: number) {
  return {
    queryKey: ['x-block', api.chainId, height],
    queryFn: () => api.block(height),
    // A finalized block never changes.
    staleTime: Infinity,
    retry: 2,
  } as const;
}

export function useBlock(height: number) {
  const { api } = usePayments();
  return useQuery({ ...blockQuery(api!, height), enabled: !!api, retry: (count: number, error: Error) =>
    !('status' in error && (error as { status: number }).status === 404) && count < 2 });
}

/** Decoded messages per block object: a finalized block is immutable, so it is decoded once per chain context. */
const decodedBlocks = new WeakMap<Block, { ctx: string; txs: DecodedTx[] }>();

export function decodeBlock(block: Block, ctx: DecodeContext): DecodedTx[] {
  const key = JSON.stringify(ctx);
  const cached = decodedBlocks.get(block);
  if (cached && cached.ctx === key) return cached.txs;
  const txs = block.messages.map((message) => decodeMessage(message, ctx));
  decodedBlocks.set(block, { ctx: key, txs });
  return txs;
}

/** The chain's topics, from payments/info. */
export function useDecodeContext(): DecodeContext {
  const { info } = usePayments();
  const topic = info?.intentTopic ?? '';
  const deposit = info?.depositObserverId;
  const payout = info?.payoutObserverId;
  return useMemo(() => contextOf({ intentTopic: topic, depositObserverId: deposit, payoutObserverId: payout }),
    [topic, deposit, payout]);
}

/** The finalized blocks at {@code heights}, with their messages decoded. */
export function useBlocks(heights: number[]) {
  const { api } = usePayments();
  const ctx = useDecodeContext();
  const results = useQueries({ queries: api ? heights.map((height) => blockQuery(api, height)) : [] });
  return {
    blocks: results.map((result) => result.data),
    decoded: results.map((result) => (result.data ? decodeBlock(result.data, ctx) : undefined)),
    loading: results.some((result) => result.isLoading),
    errors: results.filter((result) => result.isError).length,
  };
}

/**
 * The SSE stream of finalized messages from now on. Each event's message is kept (newest 300) and refreshes the tip
 * and the newest block page; when the stream cannot connect (no EventSource, an API key it cannot send, a proxy that
 * buffers) the views fall back to polling.
 */
export function useLiveStream(enabled = true) {
  const { api } = usePayments();
  const queries = useQueryClient();
  const [mode, setMode] = useState<StreamMode>('connecting');
  const [messages, setMessages] = useState<ChainMessage[]>([]);
  useEffect(() => {
    if (!api || !enabled) return;
    if (typeof EventSource === 'undefined') {
      setMode('polling');
      return;
    }
    setMode('connecting');
    const source = new EventSource(api.streamUrl());
    let failures = 0;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const refresh = () => {
      if (timer) return;
      timer = setTimeout(() => {
        timer = null;
        void queries.invalidateQueries({ queryKey: ['x-tip', api.chainId] });
        void queries.invalidateQueries({ queryKey: ['x-blocks-latest', api.chainId] });
      }, 250);
    };
    source.onopen = () => {
      failures = 0;
      setMode('live');
    };
    source.onerror = () => {
      failures += 1;
      if (source.readyState === EventSource.CLOSED || failures >= 3) {
        source.close();
        setMode('polling');
      } else {
        setMode('connecting');
      }
    };
    source.addEventListener('app-message', (event) => {
      const data = (event as MessageEvent).data;
      if (typeof data !== 'string' || data.length > 2 * MAX_BODY_BYTES + 8_192) return;
      let raw: unknown;
      try {
        raw = JSON.parse(data);
      } catch {
        return;
      }
      if (typeof raw !== 'object' || raw === null || (raw as { chainId?: unknown }).chainId !== api.chainId) return;
      const message = parseMessage(raw);
      if (message.malformed?.includes('position')) return;
      setMode('live');
      setMessages((list) => [message, ...list.filter((entry) => entry.height !== message.height
        || entry.index !== message.index)].slice(0, 300));
      refresh();
    });
    return () => {
      source.close();
      if (timer) clearTimeout(timer);
    };
  }, [api, enabled, queries]);
  return { mode, messages };
}

/** Decodes messages the stream pushed, with the chain's topics. */
export function useDecoded(messages: ChainMessage[]): DecodedTx[] {
  const ctx = useDecodeContext();
  return useMemo(() => messages.map((message) => decodeMessage(message, ctx)), [messages, ctx]);
}

const TERMINAL = new Set(['accepted', 'rejected', 'credited', 'uncredited', 'none', 'inconsistent']);

/**
 * The outcome of a decoded message: an intent's receipt or a deposit's record, polled while the node's view is
 * behind the message's block. Undefined while loading, and for messages that have no ledger record to check.
 */
export function useOutcome(tx: DecodedTx): { outcome?: Outcome; error?: boolean; raw?: unknown } {
  const { api } = usePayments();
  const intent = !!api && !!tx.intentId;
  const deposit = !!api && tx.kind === 'deposit' && !!tx.depositId && !!tx.vaultId && !!tx.l1TxId
    && tx.outputIndex !== undefined;
  const refetch = (outcome: Outcome | undefined) => (outcome && TERMINAL.has(outcome.state) ? false : 2_000);
  const receipt = useQuery<ReceiptView | null>({
    queryKey: ['receipt', api?.chainId, tx.intentId],
    queryFn: () => api!.receipt(tx.intentId!),
    enabled: intent,
    refetchInterval: (query) => intent && query.state.data ? refetch(receiptOutcome(tx.intentId!, tx.height,
      query.state.data)) : false,
  });
  const record = useQuery({
    queryKey: ['x-deposit', api?.chainId, tx.vaultId, tx.l1TxId, tx.outputIndex],
    queryFn: () => api!.deposit(tx.vaultId!, tx.l1TxId!, tx.outputIndex!),
    enabled: deposit,
    refetchInterval: (query) => deposit && query.state.data ? refetch(depositOutcome(tx.depositId!, tx.height,
      query.state.data)) : false,
  });
  if (intent) {
    return { outcome: receipt.data ? receiptOutcome(tx.intentId!, tx.height, receipt.data) : undefined,
      error: receipt.isError, raw: receipt.data };
  }
  if (deposit) {
    return { outcome: record.data ? depositOutcome(tx.depositId!, tx.height, record.data) : undefined,
      error: record.isError, raw: record.data };
  }
  return {};
}
