<script lang="ts">
  import { onMount } from 'svelte';
  import { ExplorerApi } from '$lib/api';
  import { DEFAULT_RUNTIME_CONFIG, loadRuntimeConfig } from '$lib/config';
  import { availabilityLabel, failureMessage, fieldText, formatTime, levelLabel, short, stateCheckLabel } from '$lib/model';
  import { verifyRowBundleLocally, type BrowserVerification } from '$lib/verify';
  import ConnectionPanel from '$lib/components/ConnectionPanel.svelte';
  import CopyValue from '$lib/components/CopyValue.svelte';
  import type {
    ActiveConnection,
    BlockDetail,
    BlockPage,
    ChainView,
    MessageView,
    RowBundle,
    RuntimeConfig,
    SearchHit,
    StateBundle,
    SubjectSummary,
    SubjectView,
    TrailRevision
  } from '$lib/types';

  type View = 'chains' | 'blocks' | 'search' | 'subjects' | 'about';

  const views: Array<{ id: View; code: string; label: string }> = [
    { id: 'chains', code: 'CH', label: 'Chains' },
    { id: 'blocks', code: 'BL', label: 'Blocks and messages' },
    { id: 'search', code: 'SR', label: 'Search' },
    { id: 'subjects', code: 'SU', label: 'Subjects and trails' },
    { id: 'about', code: '??', label: 'What this proves' }
  ];
  const modules = ['', 'doc-trail', 'kv-registry', 'balances', 'approvals', 'authenticated-map'];

  let runtimeConfig: RuntimeConfig = DEFAULT_RUNTIME_CONFIG;
  let configReady = false;
  let connecting = false;
  let connectionError = '';
  let connection: ActiveConnection | null = null;
  let api: ExplorerApi | null = null;
  let chains: ChainView[] = [];
  let selectedChainId = '';
  let activeView: View = 'chains';

  // Blocks
  let page: BlockPage | null = null;
  let pageFrom = 0;
  let blocksBusy = false;
  let blocksError = '';
  let block: BlockDetail | null = null;

  // Message
  let message: MessageView | null = null;
  let messageError = '';
  let bundle: RowBundle | null = null;
  let browser: BrowserVerification | null = null;
  let verifyBusy = false;
  let exportUrl = '';
  let exportName = '';

  // Search
  let query = '';
  let hits: SearchHit[] = [];
  let searchBusy = false;
  let searchError = '';

  // Subjects
  let moduleFilter = '';
  let prefix = '';
  let subjects: SubjectSummary[] = [];
  let subjectsBusy = false;
  let subjectsError = '';
  let subject: SubjectView | null = null;
  let subjectBusy = false;
  let subjectError = '';
  let stateExportUrl = '';
  let stateExportName = '';

  $: selectedChain = chains.find((chain) => chain.chainId === selectedChainId) ?? null;
  $: revisions = (subject?.module === 'doc-trail'
    ? ((subject.derived.revisions as TrailRevision[] | undefined) ?? []) : []);

  onMount(() => {
    void loadRuntimeConfig().then((loaded) => {
      runtimeConfig = loaded;
      configReady = true;
    });
  });

  // ------------------------------------------------------------------ connection

  async function openConnection(next: ActiveConnection) {
    connecting = true;
    connectionError = '';
    try {
      const candidate = new ExplorerApi(next);
      const listed = await candidate.chains();
      if (!listed.length) throw new Error('The service is reachable but indexes no chain yet');
      connection = next;
      api = candidate;
      chains = listed;
      const preferred = listed.find((chain) => chain.chainId === runtimeConfig.defaultChainId)
        ?? listed.find((chain) => (chain.checkpointHeight ?? 0) > 0) ?? listed[0];
      selectChain(preferred.chainId);
    } catch (cause) {
      connectionError = failureMessage(cause, 'Could not read the explorer service');
    } finally {
      connecting = false;
    }
  }

  function disconnect() {
    connection = null;
    api = null;
    chains = [];
    selectedChainId = '';
    resetChainState();
  }

  function selectChain(chainId: string) {
    selectedChainId = chainId;
    resetChainState();
    void refreshChains();
    void loadBlocks(0);
  }

  function resetChainState() {
    page = null;
    block = null;
    message = null;
    messageError = '';
    bundle = null;
    browser = null;
    hits = [];
    subjects = [];
    subject = null;
    revokeExports();
  }

  async function refreshChains() {
    if (!api) return;
    try {
      chains = await api.chains();
    } catch (cause) {
      blocksError = failureMessage(cause, 'The chain list could not be refreshed');
    }
  }

  // ------------------------------------------------------------------ blocks

  async function loadBlocks(from: number) {
    if (!api || !selectedChainId) return;
    blocksBusy = true;
    blocksError = '';
    try {
      pageFrom = from;
      page = await api.blocks(selectedChainId, from, 20);
    } catch (cause) {
      blocksError = failureMessage(cause, 'The blocks could not be read');
    } finally {
      blocksBusy = false;
    }
  }

  function olderBlocks() {
    if (!page?.blocks.length) return;
    void loadBlocks(Math.max(1, page.blocks[0].height - 20));
  }

  function newerBlocks() {
    if (!page?.blocks.length) return;
    void loadBlocks(page.blocks[page.blocks.length - 1].height + 1);
  }

  async function openBlock(height: number) {
    if (!api || !selectedChainId) return;
    activeView = 'blocks';
    blocksError = '';
    message = null;
    try {
      block = await api.block(selectedChainId, height);
    } catch (cause) {
      blocksError = failureMessage(cause, 'The block could not be read');
    }
  }

  async function openMessage(messageId: string) {
    if (!api || !selectedChainId) return;
    activeView = 'blocks';
    messageError = '';
    bundle = null;
    browser = null;
    revokeExports();
    try {
      message = await api.message(selectedChainId, messageId);
      if (!block || block.height !== message.height) block = await api.block(selectedChainId, message.height);
    } catch (cause) {
      message = null;
      messageError = failureMessage(cause, 'The message could not be read');
    }
  }

  async function verifyRow() {
    if (!api || !selectedChainId || !message) return;
    verifyBusy = true;
    messageError = '';
    revokeExports();
    try {
      bundle = await api.rowProof(selectedChainId, message.messageId);
      browser = await verifyRowBundleLocally(bundle);
      const blob = new Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json' });
      exportUrl = URL.createObjectURL(blob);
      exportName = `${selectedChainId}-h${bundle.height}-i${bundle.index}.explorer-row-proof.json`;
    } catch (cause) {
      bundle = null;
      browser = null;
      messageError = failureMessage(cause, 'The row could not be proven');
    } finally {
      verifyBusy = false;
    }
  }

  function revokeExports() {
    if (exportUrl) URL.revokeObjectURL(exportUrl);
    if (stateExportUrl) URL.revokeObjectURL(stateExportUrl);
    exportUrl = '';
    exportName = '';
    stateExportUrl = '';
    stateExportName = '';
  }

  // ------------------------------------------------------------------ search

  async function runSearch() {
    if (!api || !selectedChainId) return;
    searchBusy = true;
    searchError = '';
    try {
      hits = await api.search(selectedChainId, query.trim());
      if (!hits.length) searchError = 'No block, message, or subject matches';
    } catch (cause) {
      hits = [];
      searchError = failureMessage(cause, 'The search failed');
    } finally {
      searchBusy = false;
    }
  }

  function openHit(hit: SearchHit) {
    if (hit.type === 'block') void openBlock(hit.height);
    else if (hit.type === 'message') void openMessage(hit.messageIdHex);
    else void openSubject(hit.module, hit.subject);
  }

  // ------------------------------------------------------------------ subjects

  async function listSubjects() {
    if (!api || !selectedChainId) return;
    subjectsBusy = true;
    subjectsError = '';
    try {
      subjects = await api.subjects(selectedChainId, moduleFilter, prefix.trim(), 50);
      if (!subjects.length) subjectsError = 'No subjects match; index a chain that runs a stock machine, or widen the filter';
    } catch (cause) {
      subjects = [];
      subjectsError = failureMessage(cause, 'The subjects could not be listed');
    } finally {
      subjectsBusy = false;
    }
  }

  async function openSubject(module: string, id: string) {
    if (!api || !selectedChainId) return;
    activeView = 'subjects';
    subjectBusy = true;
    subjectError = '';
    stateExportUrl = '';
    stateExportName = '';
    try {
      subject = await api.subject(selectedChainId, module, id, true);
    } catch (cause) {
      subject = null;
      subjectError = failureMessage(cause, 'The subject could not be read');
    } finally {
      subjectBusy = false;
    }
  }

  async function exportState() {
    if (!api || !selectedChainId || !subject) return;
    subjectBusy = true;
    subjectError = '';
    try {
      const state: StateBundle = await api.stateProof(selectedChainId, subject.module, subject.subject);
      const blob = new Blob([JSON.stringify(state, null, 2)], { type: 'application/json' });
      if (stateExportUrl) URL.revokeObjectURL(stateExportUrl);
      stateExportUrl = URL.createObjectURL(blob);
      stateExportName = `${selectedChainId}-${subject.module}-${subject.subject.replace(/[^A-Za-z0-9._-]+/g, '_')}-h${state.height}.explorer-state-proof.json`;
    } catch (cause) {
      subjectError = failureMessage(cause, 'The state proof could not be built');
    } finally {
      subjectBusy = false;
    }
  }

  function levelPill(level: string | undefined): string {
    const tone = levelLabel(level).tone;
    return tone === 'ok' ? 'pill-ok' : tone === 'warn' ? 'pill-warn' : 'pill';
  }

  function checkPill(state: string): string {
    return state === 'PASS' ? 'pill-ok' : state === 'FAIL' ? 'pill-bad' : 'pill';
  }

  function contentHref(sha: string | undefined): string {
    return api && selectedChainId && sha ? api.contentUrl(selectedChainId, sha) : '';
  }
</script>

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
        <div class="grid size-10 place-items-center rounded-xl border border-mint-400/30 bg-mint-400/10 font-mono text-xs font-bold text-mint-400">EX</div>
        <div><div class="text-sm font-semibold tracking-tight">Yano X <span class="text-mint-400">Verifiable Explorer</span></div><div class="text-[.67rem] uppercase tracking-[.1em] text-[#60766f]">a derived index that proves its rows</div></div>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <span class="pill pill-ok">{chains.length} chain(s)</span>
        {#if selectedChain}
          <span class="pill {selectedChain.lagBlocks === 0 ? 'pill-ok' : 'pill-warn'}">{selectedChain.lagBlocks === 0 ? 'caught up' : selectedChain.lagBlocks && selectedChain.lagBlocks > 0 ? `${selectedChain.lagBlocks} behind` : 'node unreachable'}</span>
        {/if}
        <button class="button-quiet min-h-0 py-2" type="button" onclick={disconnect}>Change service</button>
      </div>
    </div>
  </header>

  <div class="shell grid gap-5 py-5 lg:grid-cols-[240px_minmax(0,1fr)]">
    <aside class="glass h-fit p-3 lg:sticky lg:top-5">
      <div class="border-b border-white/[.07] px-2 pb-4 pt-2">
        <div class="eyebrow">Explorer service</div>
        <div class="mt-2 truncate text-xs text-[#b7cbc4]" title={connection.serviceUrl}>{connection.serviceUrl}</div>
      </div>
      <nav class="mt-3 flex gap-1 overflow-auto lg:block" aria-label="Console sections">
        {#each views as view}
          <button class="nav-item shrink-0 {activeView === view.id ? 'active' : ''}" type="button" onclick={() => { activeView = view.id; if (view.id === 'subjects' && !subjects.length) void listSubjects(); }}>
            <span class="nav-icon">{view.code}</span><span>{view.label}</span>
          </button>
        {/each}
      </nav>
      <div class="mt-4 border-t border-white/[.07] px-2 pt-4">
        <label class="text-[.65rem] uppercase tracking-[.1em] text-[#60766f]" for="chain-picker">Chain</label>
        <select id="chain-picker" class="field mt-2" value={selectedChainId} onchange={(event) => selectChain(event.currentTarget.value)}>
          {#each chains as chain}<option value={chain.chainId}>{chain.chainId} ({chain.checkpointHeight ?? 0})</option>{/each}
        </select>
        {#if selectedChain}
          <div class="mt-3 space-y-1 text-[.7rem] text-[#8ea8a0]">
            <div>application <span class="mono text-[#b7cbc4]">{selectedChain.applicationId}</span></div>
            <div>checkpoint <span class="mono text-[#b7cbc4]">{selectedChain.checkpointHeight}</span> of tip <span class="mono text-[#b7cbc4]">{selectedChain.tipHeight ?? '?'}</span></div>
            {#each Object.entries(selectedChain.levels ?? {}) as [level, count]}
              <div><span class="pill {levelPill(level)}">{levelLabel(level).text}</span> <span class="mono">{count}</span></div>
            {/each}
          </div>
        {/if}
      </div>
    </aside>

    <main class="min-w-0 space-y-5">
      {#if activeView === 'chains'}
        <section class="glass p-5">
          <div class="eyebrow">Indexed chains</div>
          <h2 class="mt-2 text-lg font-semibold tracking-tight">Identity, checkpoint, and verification level per chain</h2>
          <div class="table-wrap mt-4">
            <table class="data-table">
              <thead><tr><th>Chain</th><th>Application</th><th>Checkpoint</th><th>Tip</th><th>Levels</th><th>Topics</th></tr></thead>
              <tbody>
                {#each chains as chain}
                  <tr>
                    <td><button class="button-quiet min-h-0 px-0 py-0" type="button" onclick={() => { selectChain(chain.chainId); activeView = 'blocks'; }}>{chain.chainId}</button></td>
                    <td class="mono">{chain.applicationId ?? ''}</td>
                    <td class="mono">{chain.checkpointHeight ?? 0}</td>
                    <td class="mono">{chain.tipHeight ?? '?'}</td>
                    <td>{#each Object.entries(chain.levels ?? {}) as [level, count]}<span class="pill {levelPill(level)} mr-1">{levelLabel(level).text} {count}</span>{/each}</td>
                    <td class="text-[.72rem] text-[#8ea8a0]">{Object.keys(chain.topics ?? {}).join(', ')}{chain.diagnostic ? ` · ${chain.diagnostic}` : ''}</td>
                  </tr>
                {/each}
              </tbody>
            </table>
          </div>
          {#if selectedChain}
            <div class="mt-4 grid gap-3 sm:grid-cols-2">
              <div class="panel p-4"><div class="eyebrow">State genesis</div><div class="mt-2"><CopyValue value={selectedChain.stateGenesisId ?? ''} label="state genesis id" /></div></div>
              <div class="panel p-4"><div class="eyebrow">Index identity digest</div><div class="mt-2"><CopyValue value={selectedChain.identityDigest ?? ''} label="identity digest" /></div></div>
            </div>
          {/if}
        </section>
      {:else if activeView === 'blocks'}
        <section class="glass p-5">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <div><div class="eyebrow">Timeline</div><h2 class="mt-2 text-lg font-semibold tracking-tight">{selectedChainId}</h2></div>
            <div class="flex gap-2">
              <button class="button-secondary" type="button" disabled={blocksBusy || !page?.blocks.length || page.blocks[0].height <= 1} onclick={olderBlocks}>Older</button>
              <button class="button-secondary" type="button" disabled={blocksBusy} onclick={() => loadBlocks(0)}>Newest</button>
              <button class="button-secondary" type="button" disabled={blocksBusy || !page?.blocks.length || page.blocks[page.blocks.length - 1].height >= (page?.checkpointHeight ?? 0)} onclick={newerBlocks}>Newer</button>
            </div>
          </div>
          {#if blocksError}<div class="notice notice-error mt-4">{blocksError}</div>{/if}
          <div class="table-wrap mt-4">
            <table class="data-table">
              <thead><tr><th>Height</th><th>Time</th><th>Messages</th><th>Level</th><th>Block hash</th><th>Record</th></tr></thead>
              <tbody>
                {#each page?.blocks ?? [] as row}
                  <tr>
                    <td><button class="button-quiet min-h-0 px-0 py-0 mono" type="button" onclick={() => openBlock(row.height)}>{row.height}</button></td>
                    <td class="text-[.72rem]">{formatTime(row.timestamp)}</td>
                    <td class="mono">{row.messageCount}</td>
                    <td><span class="pill {levelPill(row.level)}">{levelLabel(row.level).text}</span></td>
                    <td class="mono text-[.7rem]">{short(row.blockHash, 22)}</td>
                    <td class="text-[.72rem]">{row.blockRecordCaptured ? 'captured' : row.provable ? 'header only' : 'unprovable'}</td>
                  </tr>
                {/each}
              </tbody>
            </table>
          </div>
          {#if !page?.blocks.length && !blocksBusy}<div class="empty-state mt-4">No blocks are indexed for this chain yet.</div>{/if}
        </section>

        {#if block}
          <section class="glass p-5">
            <div class="flex flex-wrap items-center justify-between gap-3">
              <div><div class="eyebrow">Block {block.height}</div><h2 class="mt-2 text-lg font-semibold tracking-tight">{block.messageCount} message(s), {block.certSignatures} certificate signature(s)</h2></div>
              <span class="pill {levelPill(block.level)}" title={levelLabel(block.level).detail}>{levelLabel(block.level).text}</span>
            </div>
            <div class="mt-4 grid gap-3 sm:grid-cols-2">
              <div class="panel p-4"><div class="eyebrow">Block hash</div><div class="mt-2"><CopyValue value={block.blockHash} label="block hash" /></div></div>
              <div class="panel p-4"><div class="eyebrow">State root</div><div class="mt-2"><CopyValue value={block.stateRoot} label="state root" /></div></div>
              <div class="panel p-4"><div class="eyebrow">Messages root</div><div class="mt-2"><CopyValue value={block.messagesRoot} label="messages root" /></div></div>
              <div class="panel p-4"><div class="eyebrow">Declared members</div><div class="mt-2 text-sm">{block.memberKeysHex?.length ?? 0} member(s), threshold {block.threshold ?? '?'}{block.anchor ? ', anchored' : ''}</div></div>
            </div>
            <div class="table-wrap mt-4">
              <table class="data-table">
                <thead><tr><th>#</th><th>Topic</th><th>Sender</th><th>Rows</th><th>Message id</th></tr></thead>
                <tbody>
                  {#each block.messages as entry}
                    <tr>
                      <td class="mono">{entry.index}</td>
                      <td class="mono text-[.72rem]">{entry.topic}</td>
                      <td class="mono text-[.7rem]">{short(entry.sender, 18)}</td>
                      <td class="text-[.72rem]">{entry.rows.map((row) => `${row.module} ${row.op} ${row.subject}`).join('; ') || (entry.state === 'TOMBSTONE' ? 'tombstone' : 'generic')}</td>
                      <td><button class="button-quiet min-h-0 px-0 py-0 mono text-[.7rem]" type="button" onclick={() => openMessage(entry.messageId)}>{short(entry.messageId, 22)}</button></td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </div>
          </section>
        {/if}

        {#if messageError}<div class="notice notice-error">{messageError}</div>{/if}
        {#if message}
          <section class="glass p-5">
            <div class="flex flex-wrap items-center justify-between gap-3">
              <div><div class="eyebrow">Message at height {message.height}, index {message.index}</div><h2 class="mt-2 text-lg font-semibold tracking-tight mono">{short(message.messageId, 34)}</h2></div>
              <div class="flex flex-wrap gap-2">
                <span class="pill {levelPill(message.level)}">{levelLabel(message.level).text}</span>
                <button class="button-primary" type="button" disabled={verifyBusy || message.provable === false} onclick={verifyRow}>{verifyBusy ? 'Building the proof…' : 'Verify this row'}</button>
                {#if exportUrl}<a class="button-secondary" href={exportUrl} download={exportName}>Download bundle</a>{/if}
              </div>
            </div>
            <div class="mt-4 grid gap-3 sm:grid-cols-2">
              <div class="panel p-4"><div class="eyebrow">Topic</div><div class="mt-2 mono text-sm">{message.topic}</div></div>
              <div class="panel p-4"><div class="eyebrow">Sender</div><div class="mt-2"><CopyValue value={message.sender} label="sender" /></div></div>
              <div class="panel p-4"><div class="eyebrow">Message id</div><div class="mt-2"><CopyValue value={message.messageId} label="message id" /></div></div>
              <div class="panel p-4"><div class="eyebrow">Body</div><div class="mt-2 mono break-all text-[.7rem] text-[#b7cbc4]">{message.state === 'TOMBSTONE' ? 'retention tombstone: body not retained' : short(message.bodyHex, 120)}</div></div>
            </div>
            {#if message.rows.length}
              <div class="table-wrap mt-4">
                <table class="data-table">
                  <thead><tr><th>Module</th><th>Op</th><th>Subject</th><th>Fields</th></tr></thead>
                  <tbody>
                    {#each message.rows as row}
                      <tr>
                        <td class="mono">{row.module}</td>
                        <td class="mono">{row.op}</td>
                        <td><button class="button-quiet min-h-0 px-0 py-0" type="button" onclick={() => openSubject(row.module, row.subject)}>{short(row.subject, 40)}</button></td>
                        <td class="text-[.72rem] text-[#8ea8a0]">{Object.entries(row.fields).map(([key, value]) => `${key}=${short(fieldText(value), 40)}`).join(' · ')}</td>
                      </tr>
                    {/each}
                  </tbody>
                </table>
              </div>
            {/if}
            {#if browser && bundle}
              <div class="mt-5 border-t border-white/[.07] pt-4">
                <div class="flex flex-wrap items-center justify-between gap-2">
                  <div class="eyebrow">Browser checks</div>
                  <span class="pill {browser.consistent ? 'pill-ok' : 'pill-bad'}">{browser.consistent ? 'consistent in this browser' : 'inconsistent'}</span>
                </div>
                <ul class="mt-3 space-y-2">
                  {#each browser.checks as check}
                    <li class="flex flex-wrap items-start gap-2 text-sm"><span class="pill {checkPill(check.state)}">{check.state}</span><span class="font-medium">{check.label}</span><span class="text-[#8ea8a0]">{check.detail}</span></li>
                  {/each}
                </ul>
                <p class="mt-3 text-[.72rem] text-[#71887f]">Finality under pinned members and the anchor are checked by <span class="mono">yano-explorer verify --bundle {exportName}</span> (exit 5 with <span class="mono">--members</span>, 6 with the bundle's declared members, 0 with an anchor datum).</p>
              </div>
            {/if}
          </section>
        {/if}
      {:else if activeView === 'search'}
        <section class="glass p-5">
          <div class="eyebrow">Search</div>
          <h2 class="mt-2 text-lg font-semibold tracking-tight">A message id, a height, a topic, a sender prefix, or a subject prefix</h2>
          <form class="mt-4 flex flex-wrap gap-2" onsubmit={(event) => { event.preventDefault(); void runSearch(); }}>
            <input class="field flex-1" bind:value={query} placeholder="case-1, 12, doc-trail.command.v1, 8a88e3dd…" maxlength="256" />
            <button class="button-primary" type="submit" disabled={searchBusy || !query.trim()}>{searchBusy ? 'Searching…' : 'Search'}</button>
          </form>
          {#if searchError}<div class="notice notice-warn mt-4">{searchError}</div>{/if}
          {#if hits.length}
            <div class="table-wrap mt-4">
              <table class="data-table">
                <thead><tr><th>Type</th><th>Where</th><th>Detail</th></tr></thead>
                <tbody>
                  {#each hits as hit}
                    <tr>
                      <td><span class="pill">{hit.type}</span></td>
                      <td><button class="button-quiet min-h-0 px-0 py-0 mono text-[.72rem]" type="button" onclick={() => openHit(hit)}>{hit.type === 'subject' ? `${hit.module} ${short(hit.subject, 36)}` : hit.type === 'message' ? short(hit.messageIdHex, 24) : `height ${hit.height}`}</button></td>
                      <td class="text-[.72rem] text-[#8ea8a0]">{hit.detail}</td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </div>
          {/if}
        </section>
      {:else if activeView === 'subjects'}
        <section class="glass p-5">
          <div class="eyebrow">Subjects</div>
          <h2 class="mt-2 text-lg font-semibold tracking-tight">Entities, keys, accounts, items, and map entries the modules decoded</h2>
          <form class="mt-4 flex flex-wrap gap-2" onsubmit={(event) => { event.preventDefault(); void listSubjects(); }}>
            <select class="field w-48" bind:value={moduleFilter}>{#each modules as candidate}<option value={candidate}>{candidate || 'every module'}</option>{/each}</select>
            <input class="field flex-1" bind:value={prefix} placeholder="subject prefix" maxlength="256" />
            <button class="button-primary" type="submit" disabled={subjectsBusy}>{subjectsBusy ? 'Listing…' : 'List'}</button>
          </form>
          {#if subjectsError}<div class="notice notice-warn mt-4">{subjectsError}</div>{/if}
          {#if subjects.length}
            <div class="table-wrap mt-4">
              <table class="data-table">
                <thead><tr><th>Module</th><th>Subject</th><th>Rows</th><th>Heights</th></tr></thead>
                <tbody>
                  {#each subjects as summary}
                    <tr>
                      <td class="mono">{summary.module}</td>
                      <td><button class="button-quiet min-h-0 px-0 py-0" type="button" onclick={() => openSubject(summary.module, summary.subject)}>{short(summary.subject, 48)}</button></td>
                      <td class="mono">{summary.rowCount}</td>
                      <td class="mono text-[.72rem]">{summary.firstHeight}–{summary.lastHeight}</td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </div>
          {/if}
        </section>

        {#if subjectError}<div class="notice notice-error">{subjectError}</div>{/if}
        {#if subject}
          <section class="glass p-5">
            <div class="flex flex-wrap items-center justify-between gap-3">
              <div><div class="eyebrow">{subject.module} {subject.kind}</div><h2 class="mt-2 text-lg font-semibold tracking-tight mono">{short(subject.subject, 48)}</h2></div>
              <div class="flex flex-wrap gap-2">
                <span class="pill {stateCheckLabel(subject.stateCheck.status).tone === 'ok' ? 'pill-ok' : stateCheckLabel(subject.stateCheck.status).tone === 'warn' ? 'pill-bad' : 'pill'}" title={stateCheckLabel(subject.stateCheck.status).detail}>{stateCheckLabel(subject.stateCheck.status).text}</span>
                <button class="button-secondary" type="button" disabled={subjectBusy} onclick={exportState}>{subjectBusy ? 'Working…' : 'Build state proof'}</button>
                {#if stateExportUrl}<a class="button-secondary" href={stateExportUrl} download={stateExportName}>Download state bundle</a>{/if}
              </div>
            </div>
            <div class="mt-4 grid gap-3 sm:grid-cols-2">
              <div class="panel p-4"><div class="eyebrow">Derived from finalized commands</div><div class="mt-2 space-y-1 text-[.75rem] text-[#b7cbc4]">{#each Object.entries(subject.derived).filter(([key]) => key !== 'revisions') as [key, value]}<div><span class="text-[#71887f]">{key}</span> <span class="mono break-all">{short(fieldText(value), 64)}</span></div>{/each}</div></div>
              <div class="panel p-4"><div class="eyebrow">Authenticated state at height {fieldText(subject.stateCheck.height)}</div><div class="mt-2 space-y-1 text-[.75rem] text-[#b7cbc4]">{#if subject.stateCheck.fact}{#each Object.entries(subject.stateCheck.fact as Record<string, unknown>) as [key, value]}<div><span class="text-[#71887f]">{key}</span> <span class="mono break-all">{short(fieldText(value), 64)}</span></div>{/each}{:else}<div>{fieldText(subject.stateCheck.reason ?? subject.stateCheck.presence ?? 'not read')}</div>{/if}</div></div>
            </div>
            {#if revisions.length}
              <div class="table-wrap mt-4">
                <table class="data-table">
                  <thead><tr><th>Rev</th><th>Entry hash</th><th>Reference</th><th>Author</th><th>Height</th><th>Availability</th></tr></thead>
                  <tbody>
                    {#each revisions as revision}
                      <tr>
                        <td class="mono">{revision.revision}</td>
                        <td class="mono text-[.7rem]">{short(revision.entryHashHex, 22)}</td>
                        <td class="text-[.72rem] break-all">{revision.reference || '—'}</td>
                        <td class="mono text-[.7rem]">{short(revision.authorHex, 16)}</td>
                        <td><button class="button-quiet min-h-0 px-0 py-0 mono" type="button" onclick={() => openMessage(revision.messageId)}>{revision.height}#{revision.index}</button></td>
                        <td><span class="pill {availabilityLabel(revision.availability).tone === 'ok' ? 'pill-ok' : 'pill'}">{availabilityLabel(revision.availability).text}</span>{#if revision.contentSha256}<a class="ml-2 text-[.72rem] text-mint-400" href={contentHref(revision.contentSha256)} target="_blank" rel="noreferrer">body</a>{/if}</td>
                      </tr>
                    {/each}
                  </tbody>
                </table>
              </div>
            {:else}
              <div class="table-wrap mt-4">
                <table class="data-table">
                  <thead><tr><th>Height</th><th>Op</th><th>Fields</th><th>Level</th></tr></thead>
                  <tbody>
                    {#each subject.rows as row}
                      <tr>
                        <td><button class="button-quiet min-h-0 px-0 py-0 mono" type="button" onclick={() => openMessage(row.messageId)}>{row.height}#{row.index}</button></td>
                        <td class="mono">{row.op}</td>
                        <td class="text-[.72rem] text-[#8ea8a0]">{Object.entries(row.fields).map(([key, value]) => `${key}=${short(fieldText(value), 40)}`).join(' · ')}</td>
                        <td><span class="pill {levelPill(row.level)}">{levelLabel(row.level).text}</span></td>
                      </tr>
                    {/each}
                  </tbody>
                </table>
              </div>
            {/if}
          </section>
        {/if}
      {:else}
        <section class="glass p-5 space-y-4 text-sm leading-6 text-[#b7cbc4]">
          <div class="eyebrow">What this proves</div>
          <p>The explorer service indexes a node's finalized blocks over its public REST API. For every block with a message it fetches the node's evidence bundle, verifies the canonical block, its message ids, and its finality certificate under the bundle's declared members (or caller-pinned members when the service runs with <span class="mono">--members</span>), captures the authenticated block record proof, and only then writes the rows. A block's level says what was established; rows inherit it.</p>
          <p>Rows are finalized commands. A command that the state machine rejected still finalized, so each subject view also reads the authenticated value with a proof at the tip and reports whether the derived view matches it.</p>
          <p><strong class="text-white">Verify this row</strong> asks the service for the row's bundle: the certified block, the compact inclusion path, the block record proof, and the message copy. This browser recomputes the envelope copy, the message id, the sender signature where WebCrypto allows, the inclusion path, and the block record. Finality under pinned members and the anchor are verified by the CLI:</p>
          <pre class="panel overflow-auto p-4 text-[.72rem]">yano-explorer verify --bundle &lt;file&gt; [--members members.json | --anchor-datum-hex &lt;hex&gt;]</pre>
          <p>Exit codes follow ADR-047: 0 with an independent anchor, 5 with caller-pinned members, 6 with the bundle's own members, 4 when anything fails.</p>
          <p>The index is never an authority. Nothing in consensus, proof verification, or accounting reads it, and it can be rebuilt from any node with <span class="mono">yano-explorer rebuild</span>.</p>
        </section>
      {/if}
    </main>
  </div>
{/if}
