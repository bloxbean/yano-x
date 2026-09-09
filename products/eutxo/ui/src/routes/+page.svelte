<script lang="ts">
  import { onMount } from 'svelte';
  import { ApiError, YanoApi } from '$lib/api';
  import { DEFAULT_RUNTIME_CONFIG, loadRuntimeConfig } from '$lib/config';
  import {
    adaToLovelace,
    canonicalIdentifier,
    canonicalOutpoint,
    formatLovelace,
    indexStatusLabel,
    short,
    totalLovelace,
    validateDeposit,
    withLiveStatus
  } from '$lib/model';
  import { connectWallet, installedWallets, type Cip30WalletApi, type WalletDescriptor } from '$lib/wallet';
  import ConnectionPanel from '$lib/components/ConnectionPanel.svelte';
  import CopyValue from '$lib/components/CopyValue.svelte';
  import type {
    ActiveConnection,
    AnchorCommitment,
    DepositBuildResponse,
    DiscoveredChain,
    EutxoDeposit,
    EutxoIndexEnvelope,
    EutxoIndexedAccount,
    EutxoIndexStatus,
    EutxoTransactionSummary,
    EutxoValidityBatch,
    EutxoWithdrawal,
    L1Detail,
    L2BuildResponse,
    NodeConfig,
    NodeStatus,
    OperationReceipt,
    RuntimeConfig
  } from '$lib/types';

  type View = 'overview' | 'wallet' | 'activity' | 'accounts' | 'lifecycle' | 'proofs';
  type PendingAction =
    | { kind: 'deposit'; build: DepositBuildResponse; amount: number }
    | { kind: 'transfer' | 'withdrawal'; build: L2BuildResponse; amount: number };

  const AUTO_REFRESH_MS = 5_000;

  const views: Array<{ id: View; code: string; label: string }> = [
    { id: 'overview', code: 'OV', label: 'Overview' },
    { id: 'wallet', code: 'TX', label: 'Wallet & transact' },
    { id: 'activity', code: 'AC', label: 'L2 activity' },
    { id: 'accounts', code: 'UT', label: 'Accounts & UTxOs' },
    { id: 'lifecycle', code: 'BR', label: 'Bridge lifecycle' },
    { id: 'proofs', code: 'PF', label: 'Proof & settlement' }
  ];

  let runtimeConfig: RuntimeConfig = DEFAULT_RUNTIME_CONFIG;
  let configReady = false;
  let connecting = false;
  let connectionError = '';
  let connection: ActiveConnection | null = null;
  let api: YanoApi | null = null;
  let nodeConfig: NodeConfig | null = null;
  let nodeStatus: NodeStatus | null = null;
  let chains: DiscoveredChain[] = [];
  let selectedChainId = '';
  let activeView: View = 'overview';
  let loading = false;
  let pageError = '';

  let indexEnvelope: EutxoIndexEnvelope<EutxoIndexStatus> | null = null;
  let transactions: EutxoTransactionSummary[] = [];
  let deposits: EutxoDeposit[] = [];
  let withdrawals: EutxoWithdrawal[] = [];
  let validityBatches: EutxoValidityBatch[] = [];
  let anchor: AnchorCommitment | null = null;
  let selectedTransaction: EutxoTransactionSummary | null = null;
  let selectedProof: unknown = null;
  let proofError = '';

  let accountInput = '';
  let account: EutxoIndexedAccount | null = null;
  let accountBusy = false;
  let accountError = '';
  let lineageInput = '';
  let lineage: unknown = null;

  let wallets: WalletDescriptor[] = [];
  let walletDescriptor: WalletDescriptor | null = null;
  let walletApi: Cip30WalletApi | null = null;
  let walletAddress = '';
  let walletError = '';
  let walletBusy = false;
  let depositAmount = '5';
  let transferAmount = '1';
  let transferTo = '';
  let withdrawalAmount = '1';
  let withdrawalPayout = '';
  let pendingAction: PendingAction | null = null;
  let operationMessage = '';
  let receipts: OperationReceipt[] = [];

  let l1Lookup = '';
  let l1Detail: L1Detail | null = null;

  $: selectedChain = chains.find((chain) => chain.summary.chainId === selectedChainId) ?? null;
  $: bridge = selectedChain?.bridge ?? null;

  onMount(() => {
    void loadRuntimeConfig().then((loaded) => {
      runtimeConfig = loaded;
      configReady = true;
    });
    wallets = installedWallets();
    const walletScan = window.setInterval(() => (wallets = installedWallets()), 2000);
    const liveRefresh = window.setInterval(() => {
      if (document.visibilityState === 'visible' && connection && !loading) {
        void refreshProductData();
      }
    }, AUTO_REFRESH_MS);
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible' && connection && !loading) {
        void refreshProductData();
      }
    };
    document.addEventListener('visibilitychange', refreshWhenVisible);
    return () => {
      window.clearInterval(walletScan);
      window.clearInterval(liveRefresh);
      document.removeEventListener('visibilitychange', refreshWhenVisible);
    };
  });

  async function openConnection(next: ActiveConnection) {
    connecting = true;
    connectionError = '';
    try {
      const candidate = new YanoApi(next);
      const [config, status, discovered] = await Promise.all([
        candidate.nodeConfig(), candidate.nodeStatus(), candidate.discoverChains()
      ]);
      if (runtimeConfig.expectedNetwork
          && config.network?.toLowerCase() !== runtimeConfig.expectedNetwork.toLowerCase()) {
        throw new Error(
          `This node reports ${config.network || 'an unknown network'}, but the UI expects ${runtimeConfig.expectedNetwork}`
        );
      }
      if (!discovered.length) {
        throw new Error('The node is reachable, but it hosts no compatible EUTxO app chain');
      }
      connection = next;
      api = candidate;
      nodeConfig = config;
      nodeStatus = status;
      chains = discovered;
      selectedChainId = chooseInitialChain(discovered);
      await refreshProductData();
    } catch (cause) {
      connectionError = failureMessage(cause, 'Could not verify this Yano installation');
    } finally {
      connecting = false;
    }
  }

  function chooseInitialChain(discovered: DiscoveredChain[]): string {
    if (runtimeConfig.defaultChainId
        && discovered.some((chain) => chain.summary.chainId === runtimeConfig.defaultChainId)) {
      return runtimeConfig.defaultChainId;
    }
    return discovered.find((chain) => chain.bridgeState === 'available')?.summary.chainId
      ?? discovered[0].summary.chainId;
  }

  function disconnectNode() {
    connection = null;
    api = null;
    nodeConfig = null;
    nodeStatus = null;
    chains = [];
    selectedChainId = '';
    walletApi = null;
    walletDescriptor = null;
    walletAddress = '';
    clearProductData();
  }

  async function changeChain(value: string) {
    selectedChainId = value;
    clearProductData();
    await refreshProductData();
  }

  function clearProductData() {
    indexEnvelope = null;
    transactions = [];
    deposits = [];
    withdrawals = [];
    validityBatches = [];
    anchor = null;
    selectedTransaction = null;
    selectedProof = null;
    account = null;
    lineage = null;
  }

  async function refreshProductData() {
    if (!api || !selectedChainId) return;
    loading = true;
    pageError = '';
    const current = selectedChainId;
    const settled = await Promise.allSettled([
      api.nodeStatus(),
      api.chainStatus(current),
      api.indexStatus(current),
      api.indexedTransactions(current, 30),
      api.deposits(current, 30),
      api.withdrawals(current, 30),
      api.validityBatches(current, 30),
      api.anchorCommitment(current)
    ]);
    if (current !== selectedChainId) return;
    if (settled[0].status === 'fulfilled') nodeStatus = settled[0].value;
    if (settled[1].status === 'fulfilled') {
      const liveStatus = settled[1].value;
      chains = chains.map((chain) => chain.summary.chainId === current
        ? withLiveStatus(chain, liveStatus)
        : chain);
    }
    if (settled[2].status === 'fulfilled') {
      assertIndexEnvelope(settled[2].value);
      indexEnvelope = settled[2].value;
    }
    if (settled[3].status === 'fulfilled') {
      assertIndexEnvelope(settled[3].value);
      transactions = settled[3].value.data.items;
    } else {
      try {
        const committed = await api.committedTransactions(current, 30);
        if (committed.chainId !== current || committed.stateMachineId !== 'eutxo-ledger') {
          throw new Error('Committed EUTxO response identity does not match the selected chain');
        }
        transactions = committed.data;
      } catch (cause) {
        pageError = failureMessage(cause, 'Transaction history is unavailable');
      }
    }
    if (settled[4].status === 'fulfilled') deposits = settled[4].value.data.items;
    if (settled[5].status === 'fulfilled') withdrawals = settled[5].value.data.items;
    if (settled[6].status === 'fulfilled') validityBatches = settled[6].value.data.items;
    if (settled[7].status === 'fulfilled') anchor = settled[7].value;
    loading = false;
  }

  function assertIndexEnvelope(value: EutxoIndexEnvelope<unknown>) {
    if (value.apiVersion !== 'eutxo-index/v1'
        || value.chainId !== selectedChainId
        || value.stateMachineId !== 'eutxo-ledger'
        || value.projection?.kind !== 'DERIVED') {
      throw new Error('EUTxO index identity does not match the selected chain');
    }
  }

  async function attachWallet(descriptor: WalletDescriptor) {
    walletBusy = true;
    walletError = '';
    try {
      const handle = await connectWallet(descriptor, nodeConfig?.network || runtimeConfig.expectedNetwork);
      walletApi = handle;
      walletDescriptor = descriptor;
      walletAddress = await handle.getChangeAddress();
      accountInput = walletAddress;
    } catch (cause) {
      walletError = failureMessage(cause, 'Wallet connection failed');
    } finally {
      walletBusy = false;
    }
  }

  function detachWallet() {
    walletApi = null;
    walletDescriptor = null;
    walletAddress = '';
    pendingAction = null;
    operationMessage = '';
  }

  async function reviewDeposit() {
    if (!api || !bridge || !walletApi || !walletAddress) return;
    operationMessage = '';
    const amount = adaToLovelace(depositAmount);
    const problem = validateDeposit(amount, bridge);
    if (problem || amount === null) {
      operationMessage = problem || 'Enter a valid deposit amount';
      return;
    }
    await runWalletStep(async () => {
      const build = await api!.buildDeposit(
        selectedChainId, walletAddress, walletAddress, amount
      );
      pendingAction = { kind: 'deposit', build, amount };
    }, 'Could not build the unsigned deposit');
  }

  async function reviewL2(kind: 'transfer' | 'withdrawal') {
    if (!api || !walletApi || !walletAddress) return;
    operationMessage = '';
    const amount = adaToLovelace(kind === 'transfer' ? transferAmount : withdrawalAmount);
    if (amount === null || amount < 1) {
      operationMessage = 'Enter an ADA amount such as 1 or 1.5';
      return;
    }
    if (kind === 'transfer' && !transferTo.trim()) {
      operationMessage = 'Enter the destination L2 address';
      return;
    }
    await runWalletStep(async () => {
      const build = kind === 'transfer'
        ? await api!.buildTransfer(selectedChainId, walletAddress, transferTo.trim(), amount)
        : await api!.buildWithdrawal(
            selectedChainId,
            walletAddress,
            withdrawalPayout.trim() || walletAddress,
            amount
          );
      pendingAction = { kind, build, amount };
    }, `Could not build the ${kind === 'transfer' ? 'L2 transfer' : 'withdrawal claim'}`);
  }

  async function approvePending() {
    if (!api || !walletApi || !pendingAction) return;
    const action = pendingAction;
    await runWalletStep(async () => {
      operationMessage = 'Confirm the transaction in your wallet…';
      const witness = await walletApi!.signTx(action.build.unsignedTxCborHex, true);
      const assembled = await api!.assembleDeposit(
        selectedChainId, action.build.unsignedTxCborHex, witness
      );
      if (action.kind === 'deposit') {
        const submitted = await api!.submitL1Transaction(assembled.signedTxCborHex);
        receipts = [{
          kind: 'deposit',
          transactionId: submitted || assembled.transactionId,
          l2OwnerAddress: action.build.l2OwnerAddress,
          submittedAt: Date.now()
        }, ...receipts];
        operationMessage = `Deposit submitted to L1. It will be mirrored after ${bridge?.stabilityDepth ?? 0} stable blocks.`;
      } else {
        const submitted = await api!.submitL2Transaction(
          selectedChainId, action.build.submitTopic, assembled.signedTxCborHex
        );
        receipts = [{
          kind: action.kind === 'transfer' ? 'transfer' : 'withdrawal',
          transactionId: action.build.transactionId,
          messageId: submitted.messageId,
          submittedAt: Date.now()
        }, ...receipts];
        operationMessage = action.kind === 'transfer'
          ? 'L2 transfer submitted. It will appear after the app chain finalizes it.'
          : 'Withdrawal claim submitted. The settlement operator will pay it on L1.';
      }
      pendingAction = null;
      window.setTimeout(() => void refreshProductData(), 3500);
    }, `The ${action.kind} operation failed`);
  }

  async function runWalletStep(action: () => Promise<void>, fallback: string) {
    if (walletBusy) return;
    walletBusy = true;
    try {
      await action();
    } catch (cause) {
      operationMessage = failureMessage(cause, fallback);
    } finally {
      walletBusy = false;
    }
  }

  async function lookupAccount() {
    if (!api || !accountInput.trim()) return;
    accountBusy = true;
    accountError = '';
    try {
      const result = await api.indexedAccount(selectedChainId, accountInput.trim());
      assertIndexEnvelope(result);
      account = result.data;
    } catch (cause) {
      accountError = failureMessage(cause, 'Account lookup failed');
      account = null;
    } finally {
      accountBusy = false;
    }
  }

  async function lookupLineage() {
    if (!api) return;
    pageError = '';
    try {
      const outpoint = canonicalOutpoint(lineageInput);
      const result = await api.lineage(
        selectedChainId, outpoint.transactionId, outpoint.outputIndex, 3
      );
      assertIndexEnvelope(result);
      lineage = result.data;
    } catch (cause) {
      pageError = failureMessage(cause, 'Lineage lookup failed');
    }
  }

  async function openTransaction(transaction: EutxoTransactionSummary) {
    selectedTransaction = transaction;
    selectedProof = null;
    proofError = '';
    activeView = 'activity';
  }

  async function loadProof() {
    if (!api || !selectedTransaction) return;
    proofError = '';
    try {
      selectedProof = await api.proofPackage(selectedChainId, selectedTransaction.messageId);
    } catch (cause) {
      proofError = failureMessage(cause, 'Proof package is unavailable');
    }
  }

  async function lookupL1(transactionId = l1Lookup) {
    if (!api) return;
    try {
      const id = canonicalIdentifier(transactionId);
      l1Detail = { id, state: 'loading' };
      const [transaction, utxos] = await Promise.all([
        api.l1Transaction(id), api.l1TransactionUtxos(id)
      ]);
      if ((transaction.hash && transaction.hash !== id) || (utxos.hash && utxos.hash !== id)) {
        throw new Error('L1 response identity does not match the requested transaction');
      }
      l1Detail = { id, state: 'ready', transaction, utxos };
    } catch (cause) {
      l1Detail = {
        id: transactionId,
        state: cause instanceof ApiError && cause.status === 404 ? 'not-found' : 'failed',
        message: failureMessage(cause, 'L1 transaction lookup failed')
      };
    }
  }

  function failureMessage(cause: unknown, fallback: string): string {
    if (cause instanceof ApiError) {
      const suffix = cause.status ? ` (HTTP ${cause.status}${cause.code ? `, ${cause.code}` : ''})` : '';
      return `${cause.message}${suffix}`;
    }
    return cause instanceof Error ? cause.message : fallback;
  }

  function formattedTime(value: number): string {
    return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(value);
  }
</script>

<svelte:head>
  <title>Yano X · EUTxO</title>
  <meta name="description" content="A portable product interface for compatible Yano EUTxO app chains" />
</svelte:head>

{#if !configReady}
  <div class="grid min-h-screen place-items-center"><span class="pill">Loading interface</span></div>
{:else if !connection}
  <main class="shell">
    <ConnectionPanel config={runtimeConfig} busy={connecting} error={connectionError} onconnect={openConnection} />
  </main>
{:else}
  <header class="border-b border-white/[.07] bg-[#050b0a]/70 backdrop-blur-xl">
    <div class="shell flex min-h-[4.7rem] flex-wrap items-center justify-between gap-4 py-3">
      <div class="flex items-center gap-3">
        <div class="grid size-10 place-items-center rounded-xl border border-mint-400/30 bg-mint-400/10 font-mono text-xs font-bold text-mint-400">UX</div>
        <div><div class="text-sm font-semibold tracking-tight">Yano X <span class="text-mint-400">EUTxO</span></div><div class="text-[.67rem] uppercase tracking-[.13em] text-[#71887f]">Portable product interface</div></div>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <span class="pill pill-ok">{nodeConfig?.network || 'connected'}</span>
        <span class="pill {nodeStatus?.runtimeDegraded ? 'pill-warn' : 'pill-ok'}">{nodeStatus?.runtimeDegraded ? 'degraded' : 'node online'}</span>
        <button class="button-quiet min-h-0 py-2" type="button" onclick={disconnectNode}>Change node</button>
      </div>
    </div>
  </header>

  <div class="shell grid gap-5 py-5 lg:grid-cols-[220px_minmax(0,1fr)]">
    <aside class="glass h-fit p-3 lg:sticky lg:top-5">
      <div class="border-b border-white/[.07] px-2 pb-4 pt-2">
        <div class="eyebrow">Connected node</div>
        <div class="mt-2 truncate text-xs text-[#b7cbc4]" title={connection.nodeUrl}>{connection.nodeUrl}</div>
        <div class="mt-1 font-mono text-[.65rem] text-[#60766f]">Yano {nodeConfig?.version || 'unknown'}</div>
      </div>
      <nav class="mt-3 flex gap-1 overflow-auto lg:block" aria-label="EUTxO sections">
        {#each views as view}
          <button class="nav-item shrink-0 {activeView === view.id ? 'active' : ''}" type="button" onclick={() => (activeView = view.id)}>
            <span class="nav-icon">{view.code}</span><span>{view.label}</span>
          </button>
        {/each}
      </nav>
      <div class="mt-4 border-t border-white/[.07] px-2 pt-4">
        <div class="text-[.65rem] uppercase tracking-[.1em] text-[#60766f]">Wallet</div>
        <div class="mt-2 text-xs {walletApi ? 'text-mint-400' : 'text-[#789087]'}">{walletDescriptor?.name || 'Not connected'}</div>
      </div>
    </aside>

    <main class="min-w-0">
      <section class="glass p-5">
        <div class="flex flex-wrap items-start justify-between gap-4">
          <div>
            <div class="eyebrow">Active app chain</div>
            <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">{selectedChainId}</h1>
            <div class="mt-2 flex flex-wrap gap-2">
              <span class="pill {selectedChain?.status.running ? 'pill-ok' : 'pill-warn'}">{selectedChain?.status.running ? 'running' : 'not running'}</span>
              <span class="pill {bridge ? 'pill-ok' : 'pill-warn'}">bridge {selectedChain?.bridgeState}</span>
              {#if indexEnvelope}<span class="pill pill-ok">index {indexStatusLabel(indexEnvelope.projection.status)}</span>{/if}
            </div>
          </div>
          <div class="flex items-center gap-2">
            <label class="sr-only" for="chain-picker">EUTxO chain</label>
            <select id="chain-picker" class="field min-w-52" value={selectedChainId} onchange={(event) => void changeChain(event.currentTarget.value)}>
              {#each chains as chain}<option value={chain.summary.chainId}>{chain.summary.chainId}</option>{/each}
            </select>
            <span class="pill">auto 5s</span>
            <button class="button-secondary" type="button" disabled={loading} onclick={() => void refreshProductData()}>{loading ? 'Refreshing…' : 'Refresh'}</button>
          </div>
        </div>
        <div class="mt-5 grid gap-4 border-t border-white/[.07] pt-5 sm:grid-cols-2 xl:grid-cols-4">
          <div class="metric"><div class="metric-label">App height</div><div class="metric-value">{selectedChain?.summary.tipHeight?.toLocaleString() || '0'}</div></div>
          <div class="metric"><div class="metric-label">State root</div><div class="metric-value text-xs">{short(selectedChain?.summary.stateRoot || '', 25)}</div></div>
          <div class="metric"><div class="metric-label">Index lag</div><div class="metric-value">{indexEnvelope ? `${indexEnvelope.projection.lagBlocks} blocks` : 'Unavailable'}</div></div>
          <div class="metric"><div class="metric-label">Latest anchor</div><div class="metric-value">{anchor ? `#${anchor.anchoredHeight.toLocaleString()}` : 'Pending'}</div></div>
        </div>
      </section>

      {#if pageError}<div class="notice notice-error mt-4">{pageError}</div>{/if}

      {#if activeView === 'overview'}
        <div class="mt-4 grid gap-4 xl:grid-cols-[1.15fr_.85fr]">
          <section class="glass p-5">
            <div class="eyebrow">Live system</div><h2 class="mb-0 mt-2 text-lg font-semibold">Chain health</h2>
            <div class="mt-5 grid gap-3 sm:grid-cols-2">
              <div class="panel p-4"><div class="metric-label">Consensus members</div><div class="mt-2 text-2xl font-semibold">{selectedChain?.status.members ?? '—'}</div><div class="mt-1 text-xs text-[#71887f]">Threshold {selectedChain?.status.threshold ?? '—'}</div></div>
              <div class="panel p-4"><div class="metric-label">State machine</div><div class="mt-2 font-mono text-sm text-mint-400">eutxo-ledger</div><div class="mt-1 text-xs text-[#71887f]">Discovered from live capabilities</div></div>
              <div class="panel p-4"><div class="metric-label">Indexed height</div><div class="mt-2 text-2xl font-semibold">{indexEnvelope?.projection.indexedHeight?.toLocaleString() ?? '—'}</div><div class="mt-1 text-xs text-[#71887f]">Finalized {indexEnvelope?.projection.finalizedHeight?.toLocaleString() ?? '—'}</div></div>
              <div class="panel p-4"><div class="metric-label">Submissions</div><div class="mt-2 text-2xl font-semibold">{selectedChain?.status.submissionsPaused ? 'Paused' : 'Open'}</div><div class="mt-1 text-xs text-[#71887f]">{selectedChain?.status.stalled ? 'Chain reports stalled' : 'No stall reported'}</div></div>
            </div>
          </section>
          <section class="glass p-5">
            <div class="eyebrow">Bridge contract</div><h2 class="mb-0 mt-2 text-lg font-semibold">Cardano ↔ EUTxO</h2>
            {#if bridge}
              <div class="mt-5 space-y-4 text-xs">
                <div><div class="metric-label">Vault address</div><div class="mt-2"><CopyValue value={bridge.vaultAddress} width={38} label="vault address" /></div></div>
                <div><div class="metric-label">Withdrawal address</div><div class="mt-2"><CopyValue value={bridge.withdrawalAddress} width={38} label="withdrawal address" /></div></div>
                <div class="grid grid-cols-2 gap-4"><div><div class="metric-label">Deposit cap</div><div class="mt-2">{formatLovelace(bridge.maxDepositLovelace)}</div></div><div><div class="metric-label">Stability</div><div class="mt-2">{bridge.stabilityDepth} blocks</div></div></div>
                {#if bridge.withdrawalsPaused}<div class="notice notice-warn">Withdrawals are currently paused by this bridge.</div>{/if}
              </div>
            {:else}
              <div class="notice notice-warn mt-5">This EUTxO chain does not expose the Cardano bridge routes. Explorer and proof features remain available.</div>
            {/if}
          </section>
        </div>
        <section class="glass mt-4 p-5">
          <div class="flex items-center justify-between"><div><div class="eyebrow">Recent finality</div><h2 class="mb-0 mt-2 text-lg font-semibold">Latest L2 transactions</h2></div><button class="button-quiet" type="button" onclick={() => (activeView = 'activity')}>View activity</button></div>
          <div class="table-wrap mt-4"><table class="data-table"><thead><tr><th>Transaction</th><th>Status</th><th>Height</th><th>Value out</th></tr></thead><tbody>{#each transactions.slice(0, 6) as transaction}<tr><td><button class="mono text-mint-400" type="button" onclick={() => void openTransaction(transaction)}>{short(transaction.transactionId || transaction.messageId, 28)}</button></td><td><span class="pill {transaction.status === 'ACCEPTED' ? 'pill-ok' : 'pill-bad'}">{transaction.status}</span></td><td>#{transaction.appHeight}</td><td>{totalLovelace(transaction.outputs)}</td></tr>{:else}<tr><td colspan="4" class="muted text-center">No finalized transactions in this coverage window.</td></tr>{/each}</tbody></table></div>
        </section>
      {:else if activeView === 'wallet'}
        <div class="mt-4 grid gap-4 xl:grid-cols-[.8fr_1.2fr]">
          <section class="glass p-5">
            <div class="eyebrow">CIP-30</div><h2 class="mb-0 mt-2 text-lg font-semibold">Wallet connection</h2>
            {#if walletApi && walletDescriptor}
              <div class="panel mt-5 p-4"><div class="flex items-center gap-3">{#if walletDescriptor.icon}<img class="size-9 rounded-lg" src={walletDescriptor.icon} alt="" />{/if}<div><div class="font-semibold">{walletDescriptor.name}</div><div class="text-xs text-mint-400">Connected to {nodeConfig?.network || 'node network'}</div></div></div><div class="mt-4 text-[.7rem] text-[#71887f]">Change / L2 owner address</div><div class="mt-2 text-xs"><CopyValue value={walletAddress} width={36} label="wallet address" /></div></div>
              <button class="button-quiet mt-4 w-full" type="button" onclick={detachWallet}>Disconnect wallet</button>
            {:else}
              <p class="mt-4 text-sm leading-6 text-[#8ea8a0]">Choose an installed Cardano wallet. The UI checks its network before it enables signing.</p>
              <div class="mt-4 grid gap-2">{#each wallets as wallet}<button class="button-secondary justify-start" type="button" disabled={walletBusy} onclick={() => void attachWallet(wallet)}>{#if wallet.icon}<img class="size-6 rounded" src={wallet.icon} alt="" />{/if}<span>Connect {wallet.name}</span></button>{:else}<div class="notice notice-warn">No CIP-30 wallet is currently injected into this page. Install or unlock a compatible wallet, then rescan.</div>{/each}<button class="button-quiet" type="button" onclick={() => (wallets = installedWallets())}>Rescan wallets</button></div>
            {/if}
            {#if walletError}<div class="notice notice-error mt-4">{walletError}</div>{/if}
          </section>
          <section class="glass p-5">
            <div class="eyebrow">Move value</div><h2 class="mb-0 mt-2 text-lg font-semibold">Deposit, transfer, or withdraw</h2>
            {#if !bridge}<div class="notice notice-warn mt-5">Bridge operations are unavailable for this chain.</div>{:else if !walletApi}<div class="notice mt-5">Connect a wallet to build transactions. No seed phrase or signing key is sent to the node.</div>{:else}
              <div class="mt-5 grid gap-4 lg:grid-cols-3">
                <div class="panel p-4"><div class="eyebrow">L1 → L2</div><h3 class="mb-0 mt-2 text-base">Deposit</h3><label class="mt-4 block text-xs text-[#8ea8a0]" for="deposit-amount">Amount in ADA</label><input id="deposit-amount" class="field mt-2" bind:value={depositAmount} inputmode="decimal" /><button class="button-primary mt-3 w-full" type="button" disabled={walletBusy} onclick={() => void reviewDeposit()}>Review deposit</button></div>
                <div class="panel p-4"><div class="eyebrow">L2 → L2</div><h3 class="mb-0 mt-2 text-base">Transfer</h3><label class="mt-4 block text-xs text-[#8ea8a0]" for="transfer-address">Destination</label><input id="transfer-address" class="field mono mt-2" bind:value={transferTo} placeholder="addr_test…" /><label class="mt-3 block text-xs text-[#8ea8a0]" for="transfer-amount">Amount in ADA</label><input id="transfer-amount" class="field mt-2" bind:value={transferAmount} inputmode="decimal" /><button class="button-primary mt-3 w-full" type="button" disabled={walletBusy} onclick={() => void reviewL2('transfer')}>Review transfer</button></div>
                <div class="panel p-4"><div class="eyebrow">L2 → L1</div><h3 class="mb-0 mt-2 text-base">Withdraw</h3><label class="mt-4 block text-xs text-[#8ea8a0]" for="withdrawal-amount">Amount in ADA</label><input id="withdrawal-amount" class="field mt-2" bind:value={withdrawalAmount} inputmode="decimal" /><label class="mt-3 block text-xs text-[#8ea8a0]" for="payout-address">Payout address</label><input id="payout-address" class="field mono mt-2" bind:value={withdrawalPayout} placeholder="Defaults to wallet" /><p class="mb-0 mt-3 text-xs leading-5 text-[#71887f]">The operator settles the finalized claim on L1.</p><button class="button-danger mt-3 w-full" type="button" disabled={walletBusy || bridge.withdrawalsPaused} onclick={() => void reviewL2('withdrawal')}>Review withdrawal</button></div>
              </div>
            {/if}
            {#if operationMessage}<div class="notice {operationMessage.includes('failed') || operationMessage.includes('Could not') ? 'notice-error' : ''} mt-4">{operationMessage}</div>{/if}
            {#if pendingAction}
              <div class="mt-5 rounded-xl border border-mint-400/25 bg-mint-400/[.05] p-5">
                <div class="eyebrow">Review before signing</div><h3 class="mb-0 mt-2 text-lg capitalize">{pendingAction.kind}</h3>
                <div class="mt-4 grid gap-4 text-xs sm:grid-cols-2"><div><div class="metric-label">Network</div><div class="mt-2">{nodeConfig?.network || 'unknown'}</div></div><div><div class="metric-label">Amount</div><div class="mt-2">{formatLovelace(pendingAction.amount)}</div></div><div><div class="metric-label">From</div><div class="mt-2"><CopyValue value={pendingAction.kind === 'deposit' ? pendingAction.build.depositorAddress : pendingAction.build.fromAddress || walletAddress} width={35} label="source address" /></div></div><div><div class="metric-label">Destination</div><div class="mt-2"><CopyValue value={pendingAction.kind === 'deposit' ? pendingAction.build.vaultAddress : pendingAction.kind === 'withdrawal' ? pendingAction.build.payoutAddress || '' : pendingAction.build.toAddress || ''} width={35} label="destination address" /></div></div>{#if pendingAction.kind === 'deposit'}<div><div class="metric-label">L1 fee</div><div class="mt-2">{formatLovelace(pendingAction.build.fee)}</div></div><div><div class="metric-label">Valid until slot</div><div class="mt-2">{pendingAction.build.ttlSlot.toLocaleString()}</div></div>{/if}</div>
                <div class="mt-5 flex gap-2"><button class="button-primary" type="button" disabled={walletBusy} onclick={() => void approvePending()}>{walletBusy ? 'Waiting…' : 'Approve in wallet'}</button><button class="button-quiet" type="button" disabled={walletBusy} onclick={() => (pendingAction = null)}>Cancel</button></div>
              </div>
            {/if}
            {#if receipts.length}<div class="mt-5"><div class="metric-label">This browser session</div>{#each receipts as receipt}<div class="panel mt-2 flex flex-wrap items-center justify-between gap-3 p-3 text-xs"><div><span class="pill pill-ok">{receipt.kind}</span><span class="ml-3 text-[#71887f]">{formattedTime(receipt.submittedAt)}</span></div><CopyValue value={receipt.transactionId} width={30} label="transaction ID" /></div>{/each}</div>{/if}
          </section>
        </div>
      {:else if activeView === 'activity'}
        <section class="glass mt-4 overflow-hidden">
          <div class="p-5"><div class="eyebrow">Finalized ledger</div><h2 class="mb-0 mt-2 text-lg font-semibold">L2 transactions</h2><p class="mb-0 mt-2 text-xs text-[#71887f]">Derived index results are root-bound and identity-checked. The UI falls back to committed ledger summaries if the index is unavailable.</p></div>
          <div class="table-wrap"><table class="data-table"><thead><tr><th>Transaction</th><th>Message</th><th>Height</th><th>Status</th><th>Inputs / outputs</th><th>Output value</th></tr></thead><tbody>{#each transactions as transaction}<tr><td><button class="mono text-mint-400" type="button" onclick={() => (selectedTransaction = transaction)}>{short(transaction.transactionId || 'rejected', 28)}</button></td><td><CopyValue value={transaction.messageId} width={24} label="message ID" /></td><td>#{transaction.appHeight}:{transaction.ordinal}</td><td><span class="pill {transaction.status === 'ACCEPTED' ? 'pill-ok' : 'pill-bad'}">{transaction.status}</span></td><td>{transaction.inputs.length} / {transaction.outputs.length}</td><td>{totalLovelace(transaction.outputs)}</td></tr>{:else}<tr><td colspan="6" class="muted text-center">No transactions in this coverage window.</td></tr>{/each}</tbody></table></div>
        </section>
        {#if selectedTransaction}<section class="glass mt-4 p-5"><div class="flex flex-wrap items-start justify-between gap-3"><div><div class="eyebrow">Transaction detail</div><h2 class="mb-0 mt-2 text-lg font-semibold">Height {selectedTransaction.appHeight}:{selectedTransaction.ordinal}</h2></div><span class="pill {selectedTransaction.status === 'ACCEPTED' ? 'pill-ok' : 'pill-bad'}">{selectedTransaction.status}</span></div><div class="mt-5 grid gap-5 lg:grid-cols-2"><div class="space-y-4 text-xs"><div><div class="metric-label">Transaction ID</div><div class="mt-2"><CopyValue value={selectedTransaction.transactionId} width={52} label="transaction ID" /></div></div><div><div class="metric-label">App message ID</div><div class="mt-2"><CopyValue value={selectedTransaction.messageId} width={52} label="message ID" /></div></div><div><div class="metric-label">Authorization</div><div class="mt-2">{selectedTransaction.authorizationProfile || 'unknown'}</div></div><button class="button-secondary" type="button" onclick={() => void loadProof()}>Load proof package</button></div><div class="grid gap-3 sm:grid-cols-2"><div><div class="metric-label">Inputs</div>{#each selectedTransaction.inputs as entry}<div class="panel mt-2 p-3 text-xs"><CopyValue value={entry.outpoint} width={28} label="input outpoint" /><div class="mt-2">{formatLovelace(entry.lovelace)}</div></div>{:else}<p class="muted text-xs">None</p>{/each}</div><div><div class="metric-label">Outputs</div>{#each selectedTransaction.outputs as entry}<div class="panel mt-2 p-3 text-xs"><CopyValue value={entry.outpoint} width={28} label="output outpoint" /><div class="mt-2">{formatLovelace(entry.lovelace)}</div></div>{:else}<p class="muted text-xs">None</p>{/each}</div></div></div>{#if proofError}<div class="notice notice-error mt-4">{proofError}</div>{/if}{#if selectedProof}<details class="panel mt-4 p-4"><summary class="cursor-pointer text-sm font-semibold">Message proof package JSON</summary><pre class="mt-3 max-h-96 overflow-auto whitespace-pre-wrap break-all text-[.68rem] text-[#8ea8a0]">{JSON.stringify(selectedProof, null, 2)}</pre></details>{/if}</section>{/if}
      {:else if activeView === 'accounts'}
        <section class="glass mt-4 p-5"><div class="eyebrow">Address state</div><h2 class="mb-0 mt-2 text-lg font-semibold">Account & UTxO lookup</h2><form class="mt-5 flex gap-2" onsubmit={(event) => { event.preventDefault(); void lookupAccount(); }}><input class="field mono min-w-0 flex-1" bind:value={accountInput} placeholder="Cardano address or CIP-30 address hex" aria-label="EUTxO account address" /><button class="button-primary" type="submit" disabled={accountBusy}>{accountBusy ? 'Opening…' : 'Open account'}</button></form>{#if accountError}<div class="notice notice-error mt-4">{accountError}</div>{/if}{#if account}<div class="mt-5 grid gap-4 sm:grid-cols-3"><div class="panel p-4"><div class="metric-label">Balance</div><div class="mt-2 text-xl text-mint-400">{formatLovelace(account.lovelace)}</div></div><div class="panel p-4"><div class="metric-label">Current UTxOs</div><div class="mt-2 text-2xl">{account.utxos.length}</div></div><div class="panel p-4"><div class="metric-label">Indexed activity</div><div class="mt-2 text-2xl">{account.activityTransactionIds.length}</div></div></div><div class="mt-5 grid gap-4 lg:grid-cols-2"><div><div class="metric-label">Unspent outputs</div>{#each account.utxos as entry}<div class="panel mt-2 p-3 text-xs"><CopyValue value={entry.outpoint} width={38} label="UTxO outpoint" /><div class="mt-2 text-mint-400">{formatLovelace(entry.lovelace)}</div><div class="mt-2 text-[#71887f]"><CopyValue value={entry.address} width={36} label="owner address" /></div></div>{:else}<p class="muted text-sm">No current UTxOs.</p>{/each}</div><div><div class="metric-label">Activity transactions</div>{#each account.activityTransactionIds as id}<div class="panel mt-2 p-3 text-xs"><CopyValue value={id} width={42} label="activity transaction ID" /></div>{:else}<p class="muted text-sm">No activity in indexed history.</p>{/each}</div></div>{/if}</section>
        <section class="glass mt-4 p-5"><div class="eyebrow">Provenance graph</div><h2 class="mb-0 mt-2 text-lg font-semibold">Outpoint lineage</h2><form class="mt-5 flex gap-2" onsubmit={(event) => { event.preventDefault(); void lookupLineage(); }}><input class="field mono min-w-0 flex-1" bind:value={lineageInput} placeholder="transaction-id#output-index" aria-label="EUTxO outpoint" /><button class="button-secondary" type="submit">Trace lineage</button></form>{#if lineage}<pre class="panel mt-4 max-h-96 overflow-auto whitespace-pre-wrap break-all p-4 text-[.7rem] text-[#8ea8a0]">{JSON.stringify(lineage, null, 2)}</pre>{/if}</section>
      {:else if activeView === 'lifecycle'}
        <div class="mt-4 grid gap-4 xl:grid-cols-2"><section class="glass p-5"><div class="eyebrow">L1 to L2</div><h2 class="mb-0 mt-2 text-lg font-semibold">Stable deposits</h2>{#each deposits as deposit}<div class="panel mt-3 p-4 text-xs"><div class="flex items-center justify-between"><span class="pill pill-ok">credited</span><span class="muted">height #{deposit.creditedHeight}</span></div><div class="mt-4 metric-label">Accepted outpoint</div><div class="mt-2"><CopyValue value={deposit.acceptedOutpoint} width={43} label="accepted outpoint" /></div><div class="mt-3 metric-label">L2 owner</div><div class="mt-2"><CopyValue value={deposit.l2Address} width={43} label="L2 owner" /></div><button class="button-quiet mt-3" type="button" onclick={() => void lookupL1(deposit.acceptedOutpoint.split('#')[0])}>Inspect L1 source</button></div>{:else}<p class="muted mt-5 text-sm">No stable deposits in indexed history.</p>{/each}</section><section class="glass p-5"><div class="eyebrow">L2 to L1</div><h2 class="mb-0 mt-2 text-lg font-semibold">Withdrawal claims</h2>{#each withdrawals as withdrawal}<div class="panel mt-3 p-4 text-xs"><div class="flex items-center justify-between"><span class="pill {withdrawal.status === 'CONFIRMED' ? 'pill-ok' : 'pill-warn'}">{withdrawal.status}</span><span>{formatLovelace(withdrawal.lovelace)}</span></div><div class="mt-4 metric-label">Claim ID</div><div class="mt-2"><CopyValue value={withdrawal.claimId} width={43} label="claim ID" /></div><div class="mt-3 metric-label">Destination</div><div class="mt-2"><CopyValue value={withdrawal.destinationAddress} width={43} label="withdrawal destination" /></div>{#if withdrawal.settlementTransactionId}<button class="button-quiet mt-3" type="button" onclick={() => void lookupL1(withdrawal.settlementTransactionId)}>Inspect settlement on L1</button>{/if}</div>{:else}<p class="muted mt-5 text-sm">No withdrawal claims in indexed history.</p>{/each}</section></div>
        <section class="glass mt-4 p-5"><div class="eyebrow">Cardano source</div><h2 class="mb-0 mt-2 text-lg font-semibold">L1 transaction inspector</h2><form class="mt-5 flex gap-2" onsubmit={(event) => { event.preventDefault(); void lookupL1(); }}><input class="field mono min-w-0 flex-1" bind:value={l1Lookup} placeholder="64-character transaction ID" aria-label="L1 transaction ID" /><button class="button-secondary" type="submit">Inspect</button></form>{#if l1Detail}<div class="panel mt-4 p-4 text-xs">{#if l1Detail.state === 'loading'}Loading L1 transaction…{:else if l1Detail.state === 'ready'}<div class="grid gap-4 sm:grid-cols-3"><div><div class="metric-label">Transaction</div><div class="mt-2"><CopyValue value={l1Detail.id} width={35} label="L1 transaction" /></div></div><div><div class="metric-label">Slot</div><div class="mt-2">{l1Detail.transaction?.slot?.toLocaleString() ?? '—'}</div></div><div><div class="metric-label">Fee</div><div class="mt-2">{l1Detail.transaction?.fees ? formatLovelace(l1Detail.transaction.fees) : '—'}</div></div></div><div class="mt-4 text-[#71887f]">{l1Detail.utxos?.inputs?.length ?? 0} inputs · {l1Detail.utxos?.outputs?.length ?? 0} outputs</div>{:else}<div class="notice notice-error">{l1Detail.message}</div>{/if}</div>{/if}</section>
      {:else if activeView === 'proofs'}
        <div class="mt-4 grid gap-4 xl:grid-cols-[.8fr_1.2fr]"><section class="glass p-5"><div class="eyebrow">L1 anchor</div><h2 class="mb-0 mt-2 text-lg font-semibold">Confirmed commitment</h2>{#if anchor}<div class="mt-5 space-y-4 text-xs"><div><div class="metric-label">Anchored height</div><div class="mt-2 text-2xl">#{anchor.anchoredHeight.toLocaleString()}</div></div><div><div class="metric-label">State root</div><div class="mt-2"><CopyValue value={anchor.stateRoot} width={48} label="anchored state root" /></div></div><div><div class="metric-label">Cardano transaction</div><div class="mt-2"><CopyValue value={anchor.transactionHash} width={48} label="anchor transaction" /></div></div><div class="notice notice-warn">{anchor.trustWarning || 'Verify the Cardano transaction independently before treating this root as trusted.'}</div></div>{:else}<div class="notice mt-5">No L1-confirmed anchor is currently available from this node.</div>{/if}</section><section class="glass p-5"><div class="eyebrow">Validity pipeline</div><h2 class="mb-0 mt-2 text-lg font-semibold">Proof batches & settlement</h2>{#each validityBatches as batch}<div class="panel mt-3 p-4 text-xs"><div class="flex flex-wrap items-center justify-between gap-2"><span class="pill {batch.proofStatus === 'VERIFIED' ? 'pill-ok' : 'pill-warn'}">{batch.proofStatus || 'proof'}</span><span class="pill {batch.settlementStatus === 'CONFIRMED' ? 'pill-ok' : 'pill-warn'}">{batch.settlementStatus || 'settlement'}</span></div><div class="mt-4 metric-label">Batch</div><div class="mt-2"><CopyValue value={batch.batchId} width={44} label="validity batch" /></div><div class="mt-3 grid grid-cols-2 gap-4"><div><div class="metric-label">Transactions</div><div class="mt-2">{batch.transactionIds.length}</div></div><div><div class="metric-label">Proof system</div><div class="mt-2">{batch.proofSystem || '—'}</div></div></div></div>{:else}<div class="notice mt-5">No validity batches are available. The chain may not enable the validity/index capability yet.</div>{/each}</section></div>
      {/if}
    </main>
  </div>
{/if}
