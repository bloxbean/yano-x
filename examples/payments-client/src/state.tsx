/**
 * The client's state: configuration and chain (read once), the connected wallet and account, the polled ledger views,
 * the activity this tab submitted, toasts and the review sheet. Reads go through TanStack Query. Status queries poll
 * until their state is terminal (a receipt, a deposit record, a paid claim) and keep polling in a background tab, so
 * a status never freezes half way.
 */
import { useQueries, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  DEPOSIT_DEADLINE_MS, forgetSession, loadSessions, ownsAccount, chooseAccount as pickAccount,
  type DepositSubmitted, type FlowContext, type ReviewRequest, type Submitted
} from './flows';
import { discoverChain, PaymentsApi, type AccountView, type Info, type ReceiptView } from './lib/api';
import { loadConfig, loadScript, type ClientConfig } from './lib/config';
import { factOf } from './lib/display';
import { describeCode, errorMessage } from './lib/errors';
import { credentialFromAddress, type Credential } from './lib/intents';
import type { Fact } from './lib/proof';
import { ed25519Supported } from './lib/session';
import { connectWallet, installedWallets, type ConnectedWallet, type WalletDescriptor } from './lib/wallet';

export interface IntentActivity extends Submitted {
  kind: 'intent';
  id: string;
  createdAtMs: number;
}

export interface DepositActivity extends DepositSubmitted {
  kind: 'deposit';
  id: string;
  createdAtMs: number;
}

export type Activity = IntentActivity | DepositActivity;

export interface Toast {
  id: number;
  tone: 'accent' | 'rose' | 'amber' | 'default';
  title: string;
  detail?: string;
}

interface PendingReview {
  request: ReviewRequest;
  resolve: () => void;
  reject: (error: Error) => void;
}

/** An intent that has no receipt this long after its expiry was refused at admission or never sequenced. */
export const EXPIRY_GRACE_MS = 30_000;

// ------------------------------------------------------------------ query options shared by views

export function receiptQuery(api: PaymentsApi, item: IntentActivity) {
  return {
    queryKey: ['receipt', api.chainId, item.intentId],
    queryFn: () => api.receipt(item.intentId),
    refetchInterval: (query: { state: { data?: ReceiptView } }) =>
      query.state.data?.found || Date.now() > item.notAfterMs + EXPIRY_GRACE_MS ? false : 2_000,
    refetchIntervalInBackground: true,
    retry: 2,
  } as const;
}

export interface DepositState {
  found: boolean;
  deposit?: { status: string; reason: string; creditedAmount: string };
}

export function depositQuery(api: PaymentsApi, item: DepositSubmitted) {
  return {
    queryKey: ['deposit', api.chainId, item.vaultId, item.txId, item.outputIndex],
    // Not committed yet, or the node is briefly unavailable: keep polling.
    queryFn: (): Promise<DepositState> => api.deposit(item.vaultId, item.txId, item.outputIndex)
      .catch(() => ({ found: false })),
    refetchInterval: (query: { state: { data?: DepositState } }) =>
      query.state.data?.found || Date.now() > item.submittedAtMs + DEPOSIT_DEADLINE_MS ? false : 3_000,
    refetchIntervalInBackground: true,
  } as const;
}

export type IntentState =
  | { state: 'pending' }
  | { state: 'expired' }
  | { state: 'accepted' | 'rejected'; reason: string; height: number; fact: Fact };

export function intentState(item: IntentActivity, receipt: ReceiptView | undefined, now: number): IntentState {
  if (receipt?.found && receipt.receipt) {
    return { state: receipt.receipt.outcome === 'ACCEPTED' ? 'accepted' : 'rejected', reason: receipt.receipt.reason,
      height: receipt.receipt.height, fact: factOf(receipt.proof, receipt.committed) };
  }
  return now > item.notAfterMs + EXPIRY_GRACE_MS ? { state: 'expired' } : { state: 'pending' };
}

// ------------------------------------------------------------------ context

interface PaymentsValue {
  config: ClientConfig | undefined;
  api: PaymentsApi | undefined;
  info: Info | undefined;
  bootError: string | null;
  wallets: WalletDescriptor[];
  rescanWallets: () => void;
  walletSdkError: string | null;
  wallet: ConnectedWallet | null;
  credential: Credential | null;
  account: Credential | null;
  connect: (descriptor: WalletDescriptor, useStakeKey: boolean) => Promise<void>;
  disconnect: () => void;
  chooseAccount: (text: string) => Promise<void>;
  sessionKeysSupported: boolean | undefined;
  activity: Activity[];
  track: (submitted: Submitted | DepositSubmitted) => void;
  toasts: Toast[];
  toast: (toast: Omit<Toast, 'id'>) => void;
  dismissToast: (id: number) => void;
  pendingReview: PendingReview | null;
  /** A flow context for the connected wallet; throws when none is connected. */
  flow: () => FlowContext;
  /** A payment request link this page was opened with, until a pay sheet takes it. */
  payLink: string | null;
  takePayLink: () => string | null;
  now: number;
}

const PaymentsContext = createContext<PaymentsValue | null>(null);

export function usePayments(): PaymentsValue {
  const value = useContext(PaymentsContext);
  if (!value) throw new Error('PaymentsProvider missing');
  return value;
}

function accountKey(api: PaymentsApi | undefined, account: Credential | null) {
  return ['account', api?.chainId, account?.accountId] as const;
}

/** The account view, polled while the dashboard shows it. */
export function useAccountView() {
  const { api, account } = usePayments();
  return useQuery({
    queryKey: accountKey(api, account),
    queryFn: () => api!.account(account!.accountId),
    enabled: !!api && !!account,
    refetchInterval: 4_000,
    refetchIntervalInBackground: true,
  });
}

/** Committed grants and this browser's session keys (stale keys pruned on every read). */
export function useSessions(enabled: boolean) {
  const { api, info, account } = usePayments();
  return useQuery({
    queryKey: ['sessions', api?.chainId, account?.accountId],
    queryFn: () => loadSessions(api!, info!, account!.accountId),
    enabled: enabled && !!api && !!info && !!account,
    refetchInterval: 5_000,
    refetchIntervalInBackground: true,
  });
}

const TERMINAL_CLAIM = new Set(['PAID', 'PAID_MISMATCH']);

/** The account's withdrawals: polled quickly while any claim is not yet paid. */
export function useWithdrawals(enabled: boolean) {
  const { api, account } = usePayments();
  return useQuery({
    queryKey: ['withdrawals', api?.chainId, account?.accountId],
    queryFn: () => api!.withdrawals(account!.accountId),
    enabled: enabled && !!api && !!account,
    refetchInterval: (query) => query.state.data?.withdrawals.every((claim) => TERMINAL_CLAIM.has(claim.lifecycle))
      ? 15_000 : 3_000,
    refetchIntervalInBackground: true,
  });
}

function invalidateAccount(queries: QueryClient): void {
  for (const key of ['account', 'sessions', 'withdrawals', 'info']) void queries.invalidateQueries({ queryKey: [key] });
}

export function PaymentsProvider({ children }: { children: ReactNode }) {
  const queries = useQueryClient();
  const config = useQuery({ queryKey: ['config'], queryFn: () => loadConfig(window.location), staleTime: Infinity });
  const chain = useQuery({
    queryKey: ['chain', config.data?.nodeUrl, config.data?.chainId],
    queryFn: async () => {
      const cfg = config.data!;
      const chainId = cfg.chainId || await discoverChain(cfg.nodeUrl, cfg.apiKey);
      return new PaymentsApi(cfg.nodeUrl, chainId, cfg.apiKey);
    },
    enabled: !!config.data,
    staleTime: Infinity,
    retry: 1,
  });
  const api = chain.data;
  const infoQuery = useQuery({
    queryKey: ['info', api?.chainId],
    queryFn: () => api!.info(),
    enabled: !!api,
    refetchInterval: 5_000,
    retry: 1,
  });
  const info = infoQuery.data;
  const bootError = chain.error || (!info && infoQuery.error)
    ? `Cannot reach an asset-payments chain at ${config.data?.nodeUrl}: ${errorMessage(chain.error ?? infoQuery.error)}`
    : null;

  // Wallets: extensions inject asynchronously after page load, and the DevKit SDK announces itself.
  const [wallets, setWallets] = useState<WalletDescriptor[]>([]);
  const rescanWallets = useCallback(() => setWallets(installedWallets()), []);
  const [walletSdkError, setWalletSdkError] = useState<string | null>(null);
  useEffect(() => {
    if (!config.data) return;
    window.addEventListener('yacidevkit:ready', rescanWallets);
    if (config.data.devkitWalletSdkUrl) {
      loadScript(config.data.devkitWalletSdkUrl).then(rescanWallets, (error) => setWalletSdkError(errorMessage(error)));
    }
    rescanWallets();
    const timers = [300, 1_000, 2_500].map((delay) => setTimeout(rescanWallets, delay));
    return () => {
      window.removeEventListener('yacidevkit:ready', rescanWallets);
      timers.forEach(clearTimeout);
    };
  }, [config.data, rescanWallets]);

  const ed25519 = useQuery({ queryKey: ['ed25519'], queryFn: ed25519Supported, staleTime: Infinity });

  const [wallet, setWallet] = useState<ConnectedWallet | null>(null);
  const [credential, setCredential] = useState<Credential | null>(null);
  const [account, setAccount] = useState<Credential | null>(null);
  const [activity, setActivity] = useState<Activity[]>([]);

  const disconnect = useCallback(() => {
    setWallet(null);
    setCredential(null);
    setAccount(null);
    setActivity([]);
  }, []);

  const connect = useCallback(async (descriptor: WalletDescriptor, useStakeKey: boolean) => {
    if (!info) throw new Error('the chain is not loaded yet');
    disconnect();
    const connected = await connectWallet(descriptor, info.intentDomain.addressNetworkId, useStakeKey);
    const own = credentialFromAddress(connected.signingAddress);
    setWallet(connected);
    setCredential(own);
    setAccount(own);
  }, [info, disconnect]);

  const chooseAccount = useCallback(async (text: string) => {
    if (!api || !credential || !info) throw new Error('connect a wallet first');
    setAccount(await pickAccount(api, credential, text, info.intentDomain.addressNetworkId));
  }, [api, credential, info]);

  // Toasts
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextToast = useRef(1);
  const dismissToast = useCallback((id: number) => setToasts((list) => list.filter((t) => t.id !== id)), []);
  const toast = useCallback((entry: Omit<Toast, 'id'>) => {
    const id = nextToast.current++;
    setToasts((list) => [...list.slice(-3), { ...entry, id }]);
    setTimeout(() => dismissToast(id), entry.tone === 'rose' ? 9_000 : 6_000);
  }, [dismissToast]);

  const track = useCallback((submitted: Submitted | DepositSubmitted) => {
    const item: Activity = 'intentId' in submitted
      ? { ...submitted, kind: 'intent', id: submitted.intentId, createdAtMs: Date.now() }
      : { ...submitted, kind: 'deposit', id: `${submitted.txId}#${submitted.outputIndex}`, createdAtMs: Date.now() };
    setActivity((list) => [item, ...list.filter((entry) => entry.id !== item.id)].slice(0, 50));
  }, []);

  // Follow every submitted intent and deposit to its terminal state, wherever the user is in the UI.
  const intents = activity.filter((item): item is IntentActivity => item.kind === 'intent');
  const deposits = activity.filter((item): item is DepositActivity => item.kind === 'deposit');
  const receipts = useQueries({ queries: api ? intents.map((item) => receiptQuery(api, item)) : [] });
  const depositRecords = useQueries({ queries: api ? deposits.map((item) => depositQuery(api, item)) : [] });
  const handled = useRef(new Set<string>());
  useEffect(() => {
    intents.forEach((item, i) => {
      const receipt = receipts[i]?.data;
      if (!receipt?.found || !receipt.receipt || handled.current.has(item.id)) return;
      handled.current.add(item.id);
      const accepted = receipt.receipt.outcome === 'ACCEPTED';
      if (item.action === 'REVOKE_SESSION' && accepted && item.grantId && api && account) {
        void forgetSession(api.chainId, account.accountId, item.grantId).then(() => invalidateAccount(queries));
      }
      toast(accepted
        ? { tone: 'accent', title: `${item.title}: accepted`, detail: `Final at height ${receipt.receipt.height}.` }
        : { tone: 'rose', title: `${item.title}: rejected (${receipt.receipt.reason})`,
          detail: describeCode(receipt.receipt.reason) });
      invalidateAccount(queries);
    });
    deposits.forEach((item, i) => {
      const record = depositRecords[i]?.data;
      if (!record?.found || !record.deposit || handled.current.has(item.id)) return;
      handled.current.add(item.id);
      toast(record.deposit.status === 'CREDITED'
        ? { tone: 'accent', title: `Deposit credited: ${item.label}` }
        : { tone: 'amber', title: `Deposit ${record.deposit.status.toLowerCase()} (${record.deposit.reason})`,
          detail: describeCode(record.deposit.reason) });
      invalidateAccount(queries);
    });
  });

  // The review sheet: a flow awaits the user's decision.
  const [pendingReview, setPendingReview] = useState<PendingReview | null>(null);
  const review = useCallback((request: ReviewRequest) => new Promise<void>((resolve, reject) => {
    setPendingReview({
      request,
      resolve: () => { setPendingReview(null); resolve(); },
      reject: (error) => { setPendingReview(null); reject(error); },
    });
  }), []);

  const flow = useCallback((): FlowContext => {
    if (!config.data || !api || !info || !wallet || !credential || !account) throw new Error('connect a wallet first');
    const view = queries.getQueryData<AccountView>(accountKey(api, account));
    if (!ownsAccount(view, credential)) throw new Error('this wallet does not own the account');
    return { config: config.data, api, info, wallet, credential, account, view, review };
  }, [config.data, api, info, wallet, credential, account, queries, review]);

  // A payment request link: #/pay?... when the page opens, or later.
  const [payLink, setPayLink] = useState<string | null>(() =>
    window.location.hash.startsWith('#/pay?') ? window.location.href : null);
  useEffect(() => {
    const onHash = () => { if (window.location.hash.startsWith('#/pay?')) setPayLink(window.location.href); };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);
  const takePayLink = useCallback(() => {
    const link = payLink;
    setPayLink(null);
    if (link) history.replaceState(null, '', window.location.pathname + window.location.search);
    return link;
  }, [payLink]);

  // A clock for expiry countdowns and expired intents.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 5_000);
    return () => clearInterval(timer);
  }, []);

  const value = useMemo<PaymentsValue>(() => ({
    config: config.data, api, info, bootError, wallets, rescanWallets, walletSdkError, wallet, credential, account,
    connect, disconnect, chooseAccount, sessionKeysSupported: ed25519.data, activity, track, toasts, toast,
    dismissToast, pendingReview, flow, payLink, takePayLink, now,
  }), [config.data, api, info, bootError, wallets, rescanWallets, walletSdkError, wallet, credential, account, connect,
    disconnect, chooseAccount, ed25519.data, activity, track, toasts, toast, dismissToast, pendingReview, flow, payLink,
    takePayLink, now]);

  return <PaymentsContext.Provider value={value}>{children}</PaymentsContext.Provider>;
}
