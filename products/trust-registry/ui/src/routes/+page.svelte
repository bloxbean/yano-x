<script lang="ts">
  import { onMount } from 'svelte';
  import { exportAnswer, readAnswer } from '$lib/answer';
  import { YanoApi, fetchStatusListDocument } from '$lib/api';
  import { DEFAULT_RUNTIME_CONFIG, loadRuntimeConfig, normalizeServiceUrl } from '$lib/config';
  import { sha256Hex } from '$lib/hash';
  import { failureMessage, short } from '$lib/model';
  import { applicationKey, bitAt, decodeEncodedList, evaluateTrqp, setCount, type TrqpAnswer } from '$lib/registry';
  import ConnectionPanel from '$lib/components/ConnectionPanel.svelte';
  import CopyValue from '$lib/components/CopyValue.svelte';
  import type {
    ActiveConnection,
    Collection,
    DiscoveredChain,
    NodeConfig,
    NodeStatus,
    RegistryAnswer,
    RuntimeConfig
  } from '$lib/types';

  type View = 'lookup' | 'lists' | 'trqp' | 'about';
  type LookupKind = 'subject' | 'status' | 'list' | 'issuer' | 'schema';

  const views: Array<{ id: View; code: string; label: string }> = [
    { id: 'lookup', code: 'LK', label: 'Look up an entry' },
    { id: 'lists', code: 'SL', label: 'Status lists' },
    { id: 'trqp', code: 'TQ', label: 'Authorization (TRQP)' },
    { id: 'about', code: '??', label: 'What this proves' }
  ];
  const kinds: Array<{ id: LookupKind; label: string; collection: Collection }> = [
    { id: 'subject', label: 'Subject', collection: 'subjects' },
    { id: 'status', label: 'Status index', collection: 'status' },
    { id: 'list', label: 'Status list entry', collection: 'status-lists' },
    { id: 'issuer', label: 'Issuer', collection: 'issuers' },
    { id: 'schema', label: 'Schema', collection: 'schemas' }
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
  let activeView: View = 'lookup';

  // Lookup
  let lookupKind: LookupKind = 'status';
  let lookupId = '';
  let lookupIndex = '';
  let lookupHeight = '';
  let lookupBusy = false;
  let lookupError = '';
  let answer: RegistryAnswer | null = null;
  let exportUrl = '';
  let exportName = '';

  // Status lists
  let listId = '';
  let serviceUrl = '';
  let listHeight = '';
  let listBusy = false;
  let listError = '';
  let listEntry: RegistryAnswer | null = null;
  let served: { purpose: string; bitLength: number; setCount: number; sha256Hex: string; replayedHeight: number; publishedHeight: number; raw: Uint8Array } | null = null;
  let bitIndex = '';

  // TRQP
  let trqpEntity = '';
  let trqpAuthorization = 'issue:credential';
  let trqpFramework = '';
  let trqpHeight = '';
  let trqpBusy = false;
  let trqpError = '';
  let trqpAnswer: RegistryAnswer | null = null;
  let trqpResult: TrqpAnswer | null = null;

  $: selectedChain = chains.find((chain) => chain.summary.chainId === selectedChainId) ?? null;
  $: tipHeight = selectedChain?.summary.tipHeight ?? 0;
  $: listMatches = served && listEntry?.decoded?.kind === 'status-list'
    ? served.sha256Hex === listEntry.decoded.value.listSha256Hex : null;
  $: bitLookup = served && bitIndex.trim() !== '' && Number.isInteger(Number(bitIndex)) && Number(bitIndex) >= 0
    && Number(bitIndex) < served.bitLength ? bitAt(served.raw, Number(bitIndex)) : null;

  onMount(() => {
    void loadRuntimeConfig().then((loaded) => {
      runtimeConfig = loaded;
      serviceUrl = loaded.serviceUrl;
      configReady = true;
    });
  });

  // ------------------------------------------------------------------ connection

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
        throw new Error(`This node reports ${config.network || 'an unknown network'}, but the UI expects ${runtimeConfig.expectedNetwork}`);
      }
      if (!discovered.length) {
        throw new Error('The node is reachable, but no app chain runs the authenticated-map state machine');
      }
      connection = next;
      api = candidate;
      nodeConfig = config;
      nodeStatus = status;
      chains = discovered;
      const preferred = discovered.find((chain) => chain.summary.chainId === runtimeConfig.defaultChainId)
        ?? discovered.find((chain) => chain.registry) ?? discovered[0];
      selectChain(preferred.summary.chainId);
    } catch (cause) {
      connectionError = failureMessage(cause, 'Could not verify this Yano installation');
    } finally {
      connecting = false;
    }
  }

  function disconnectNode() {
    connection = null;
    api = null;
    nodeConfig = null;
    nodeStatus = null;
    chains = [];
    selectedChainId = '';
    resetChainState();
  }

  function selectChain(chainId: string) {
    selectedChainId = chainId;
    resetChainState();
    void refreshChain();
  }

  function resetChainState() {
    answer = null;
    lookupError = '';
    listEntry = null;
    served = null;
    listError = '';
    trqpAnswer = null;
    trqpResult = null;
    trqpError = '';
    revokeExport();
  }

  async function refreshChain() {
    if (!api || !selectedChainId) return;
    try {
      const live = await api.chainStatus(selectedChainId);
      chains = chains.map((chain) => chain.summary.chainId === selectedChainId
        ? { ...chain, status: { ...chain.status, ...live }, summary: { ...chain.summary, tipHeight: live.tipHeight ?? chain.summary.tipHeight, stateRoot: live.stateRoot ?? chain.summary.stateRoot } }
        : chain);
    } catch (cause) {
      lookupError = failureMessage(cause, 'The chain status could not be refreshed');
    }
  }

  // ------------------------------------------------------------------ lookup

  function parseHeight(value: string): number | undefined {
    const trimmed = value.trim();
    if (!trimmed) return undefined;
    const height = Number(trimmed);
    if (!Number.isInteger(height) || height < 1) throw new Error('The height must be a positive integer');
    return height;
  }

  async function lookup() {
    if (!api || !selectedChain) return;
    lookupBusy = true;
    lookupError = '';
    revokeExport();
    try {
      await refreshChain();
      const kind = kinds.find((candidate) => candidate.id === lookupKind)!;
      const index = lookupKind === 'status' ? Number(lookupIndex.trim()) : undefined;
      const key = applicationKey(kind.collection, lookupId.trim(), index);
      answer = await readAnswer(api, selectedChain, kind.collection, key, parseHeight(lookupHeight));
    } catch (cause) {
      answer = null;
      lookupError = failureMessage(cause, 'The entry could not be read');
    } finally {
      lookupBusy = false;
    }
  }

  function buildExport() {
    if (!answer) return;
    revokeExport();
    const blob = new Blob([exportAnswer(answer)], { type: 'application/json' });
    exportUrl = URL.createObjectURL(blob);
    exportName = `${answer.collection}-${(answer.keyText ?? answer.keyHex).replace(/[^A-Za-z0-9._-]+/g, '_')}-h${answer.height}.trust-registry-answer.json`;
  }

  function revokeExport() {
    if (exportUrl) URL.revokeObjectURL(exportUrl);
    exportUrl = '';
    exportName = '';
  }

  // ------------------------------------------------------------------ status lists

  async function checkList() {
    if (!api || !selectedChain) return;
    listBusy = true;
    listError = '';
    listEntry = null;
    served = null;
    try {
      await refreshChain();
      const height = parseHeight(listHeight);
      const key = applicationKey('status-lists', listId.trim());
      listEntry = await readAnswer(api, selectedChain, 'status-lists', key, height);
      if (serviceUrl.trim()) {
        const base = normalizeServiceUrl(serviceUrl);
        const document = await fetchStatusListDocument(base, listId.trim(), height);
        const subject = (document.credentialSubject ?? {}) as Record<string, unknown>;
        const yano = (document['x-yano'] ?? {}) as Record<string, unknown>;
        const bitLength = Number(yano.bitLength);
        const encoded = String(subject.encodedList ?? '');
        const raw = await decodeEncodedList(encoded, bitLength);
        served = {
          purpose: String(subject.statusPurpose ?? ''),
          bitLength,
          setCount: setCount(raw),
          sha256Hex: await sha256Hex(raw),
          replayedHeight: Number(yano.replayedHeight ?? 0),
          publishedHeight: Number(yano.publishedHeight ?? 0),
          raw
        };
      }
    } catch (cause) {
      listError = failureMessage(cause, 'The status list could not be checked');
    } finally {
      listBusy = false;
    }
  }

  // ------------------------------------------------------------------ TRQP

  async function askTrqp() {
    if (!api || !selectedChain) return;
    trqpBusy = true;
    trqpError = '';
    trqpAnswer = null;
    trqpResult = null;
    try {
      await refreshChain();
      const key = applicationKey('issuers', trqpEntity.trim());
      const read = await readAnswer(api, selectedChain, 'issuers', key, parseHeight(trqpHeight));
      trqpAnswer = read;
      const issuer = read.decoded?.kind === 'issuer' ? read.decoded.value : null;
      trqpResult = evaluateTrqp(read.presence, issuer, trqpFramework.trim(), trqpAuthorization.trim(), read.height);
    } catch (cause) {
      trqpError = failureMessage(cause, 'The authorization question could not be answered');
    } finally {
      trqpBusy = false;
    }
  }

  function presencePill(presence: string): string {
    return presence === 'ACTIVE' ? 'pill-ok' : presence === 'REVOKED' ? 'pill-bad' : 'pill-warn';
  }

  function bindingPill(binding: string): string {
    return binding === 'BOUND' ? 'pill-ok' : 'pill-bad';
  }

  function provenanceText(read: RegistryAnswer): string {
    const p = read.provenance;
    switch (p.kind) {
      case 'NONE': return 'No entry was ever written under this key.';
      case 'GENESIS': return 'Seeded at genesis; no receipt exists.';
      case 'RECEIPT': return `Applied by message ${short(p.messageIdHex, 20)} at height ${p.appliedHeight} through the approval route.`;
      case 'DIRECT_ROLE': return `Written by ${p.actorId} (${p.organizationId}, role ${p.role}, key ${p.keyId}) under policy ${p.policyId} revision ${p.policyRevision}, applied at height ${p.appliedHeight}.`;
    }
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
        <div class="grid size-10 place-items-center rounded-xl border border-mint-400/30 bg-mint-400/10 font-mono text-xs font-bold text-mint-400">TR</div>
        <div><div class="text-sm font-semibold tracking-tight">Yano X <span class="text-mint-400">Trust Registry</span></div><div class="text-[.67rem] uppercase tracking-[.1em] text-[#60766f]">status and trust answers with proofs</div></div>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <span class="pill pill-ok">{nodeConfig?.network || 'connected'}</span>
        <span class="pill {nodeStatus?.runtimeDegraded ? 'pill-warn' : 'pill-ok'}">{nodeStatus?.runtimeDegraded ? 'degraded' : 'node online'}</span>
        <span class="pill {selectedChain?.registry ? 'pill-ok' : 'pill-warn'}">{selectedChain?.registry ? 'registry profile' : 'profile unverified'}</span>
        <button class="button-quiet min-h-0 py-2" type="button" onclick={disconnectNode}>Change node</button>
      </div>
    </div>
  </header>

  <div class="shell grid gap-5 py-5 lg:grid-cols-[240px_minmax(0,1fr)]">
    <aside class="glass h-fit p-3 lg:sticky lg:top-5">
      <div class="border-b border-white/[.07] px-2 pb-4 pt-2">
        <div class="eyebrow">Connected node</div>
        <div class="mt-2 truncate text-xs text-[#b7cbc4]" title={connection.nodeUrl}>{connection.nodeUrl}</div>
        <div class="mt-1 font-mono text-[.65rem] text-[#60766f]">Yano {nodeConfig?.version || 'unknown'}</div>
      </div>
      <nav class="mt-3 flex gap-1 overflow-auto lg:block" aria-label="Console sections">
        {#each views as view}
          <button class="nav-item shrink-0 {activeView === view.id ? 'active' : ''}" type="button" onclick={() => { activeView = view.id; }}>
            <span class="nav-icon">{view.code}</span><span>{view.label}</span>
          </button>
        {/each}
      </nav>
      <div class="mt-4 border-t border-white/[.07] px-2 pt-4">
        <label class="text-[.65rem] uppercase tracking-[.1em] text-[#60766f]" for="chain-picker">Registry chain</label>
        <select id="chain-picker" class="field mt-2" value={selectedChainId} onchange={(event) => selectChain(event.currentTarget.value)}>
          {#each chains as chain}<option value={chain.summary.chainId}>{chain.summary.chainId}{chain.registry ? '' : ' (not a registry)'}</option>{/each}
        </select>
        <div class="mt-3 text-xs text-[#789087]">Height {tipHeight.toLocaleString()}</div>
        <div class="mt-1 text-xs text-[#789087]">{selectedChain?.status.members ?? '—'} members, threshold {selectedChain?.status.threshold ?? '—'}</div>
        <div class="mt-1 truncate text-xs text-[#789087]" title={selectedChain?.identity.genesisId}>genesis {short(selectedChain?.identity.genesisId, 18)}</div>
        {#if selectedChain && selectedChain.collections === null}
          <div class="notice notice-warn mt-3 text-[.7rem]">No block yet: the map genesis cannot be queried until the first write.</div>
        {/if}
        <button class="button-quiet mt-3 min-h-0 w-full py-2" type="button" onclick={() => void refreshChain()}>Refresh</button>
      </div>
    </aside>

    <main class="min-w-0">
      {#if activeView === 'lookup'}
        <section class="glass p-5">
          <div class="eyebrow">Look up</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Entries on {selectedChainId}</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
            An answer is the entry's state proof at one height, bound to the certified block at that height. For
            governed writes the receipt and the actor's one-use authorization are read with their proofs too.
          </p>
          <form class="mt-5 grid gap-3 lg:grid-cols-[180px_minmax(0,1fr)_120px_120px_auto]" onsubmit={(event) => { event.preventDefault(); void lookup(); }}>
            <div>
              <label class="text-xs font-semibold text-[#a9beb7]" for="lookup-kind">Kind</label>
              <select id="lookup-kind" class="field mt-2" bind:value={lookupKind}>
                {#each kinds as kind}<option value={kind.id}>{kind.label}</option>{/each}
              </select>
            </div>
            <div>
              <label class="text-xs font-semibold text-[#a9beb7]" for="lookup-id">{lookupKind === 'status' || lookupKind === 'list' ? 'List id' : 'Identifier'}</label>
              <input id="lookup-id" class="field mono mt-2" bind:value={lookupId} placeholder={lookupKind === 'subject' ? 'did:example:subject-1' : lookupKind === 'issuer' ? 'issuer-a' : 'list-1'} />
            </div>
            <div>
              <label class="text-xs font-semibold text-[#a9beb7]" for="lookup-index">Index</label>
              <input id="lookup-index" class="field mono mt-2" bind:value={lookupIndex} disabled={lookupKind !== 'status'} placeholder="5" />
            </div>
            <div>
              <label class="text-xs font-semibold text-[#a9beb7]" for="lookup-height">As of height</label>
              <input id="lookup-height" class="field mono mt-2" bind:value={lookupHeight} placeholder="tip" />
            </div>
            <div class="flex items-end"><button class="button-primary" type="submit" disabled={lookupBusy || !lookupId.trim()}>{lookupBusy ? 'Reading proofs…' : 'Read'}</button></div>
          </form>
          {#if lookupError}<div class="notice notice-error mt-4">{lookupError}</div>{/if}

          {#if answer}
            <div class="panel mt-5 p-4">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <div><div class="metric-label">{answer.collection}</div><div class="mono mt-1 text-sm text-[#d8e9e3]">{answer.keyText ?? answer.keyHex}</div></div>
                <div class="flex gap-2">
                  <span class="pill {presencePill(answer.presence)}">{answer.presence}</span>
                  <span class="pill {bindingPill(answer.binding)}">proofs {answer.binding}</span>
                </div>
              </div>
              <div class="mt-3 grid gap-3 text-xs sm:grid-cols-2">
                <div><div class="metric-label">Height</div><div class="mt-1 text-[#b7cbc4]">{answer.height} (tip {tipHeight})</div></div>
                <div><div class="metric-label">Certificate</div><div class="mt-1 text-[#b7cbc4]">{answer.certSignatures} signature(s) on block <CopyValue value={answer.blockHashHex} width={22} /></div></div>
                <div><div class="metric-label">State root</div><div class="mt-1"><CopyValue value={answer.stateRootHex} width={30} /></div></div>
                <div><div class="metric-label">Genesis</div><div class="mt-1"><CopyValue value={answer.genesisIdHex} width={30} /></div></div>
                {#if answer.entry}
                  <div><div class="metric-label">Revision</div><div class="mt-1 text-[#b7cbc4]">{answer.entry.revision} (created {answer.entry.createdHeight}, last mutation {answer.entry.lastMutationHeight})</div></div>
                  <div><div class="metric-label">Logical value hash</div><div class="mt-1"><CopyValue value={answer.entry.logicalValueHashHex} width={30} /></div></div>
                {/if}
              </div>
              {#if answer.decoded}
                <div class="metric-label mt-5">Value</div>
                <div class="mt-2 grid gap-1 text-xs text-[#b7cbc4] sm:grid-cols-2">
                  {#if answer.decoded.kind === 'status'}
                    <div>bit <span class="mono">{answer.decoded.value.bit}</span> ({answer.decoded.value.bit === 1 ? 'set' : 'clear'})</div>
                    <div>reason code <span class="mono">{answer.decoded.value.reasonCode}</span></div>
                  {:else if answer.decoded.kind === 'subject'}
                    <div>controller <span class="mono">{answer.decoded.value.controllerOrganizationId}</span></div>
                    <div>kind <span class="mono">{answer.decoded.value.kind}</span></div>
                    <div class="sm:col-span-2">metadata hash <CopyValue value={answer.decoded.value.metadataHashHex} width={40} /></div>
                  {:else if answer.decoded.kind === 'status-list'}
                    <div>purpose <span class="mono">{answer.decoded.value.purpose}</span></div>
                    <div>bits <span class="mono">{answer.decoded.value.bitLength}</span></div>
                    <div>published at height <span class="mono">{answer.decoded.value.publishedHeight}</span></div>
                    <div class="sm:col-span-2">list SHA-256 <CopyValue value={answer.decoded.value.listSha256Hex} width={40} /></div>
                  {:else if answer.decoded.kind === 'issuer'}
                    <div>framework <span class="mono">{answer.decoded.value.framework}</span></div>
                    <div>valid heights <span class="mono">{answer.decoded.value.validFromHeight} – {answer.decoded.value.validUntilHeight || '∞'}</span></div>
                    <div class="sm:col-span-2">authorizations <span class="mono">{answer.decoded.value.authorizations.join(', ') || '(none)'}</span></div>
                  {:else}
                    <div class="sm:col-span-2">opaque value <CopyValue value={answer.decoded.valueHex} width={48} /></div>
                  {/if}
                </div>
              {/if}
              <div class="metric-label mt-5">Provenance</div>
              <div class="mt-2 text-xs text-[#b7cbc4]"><span class="pill mr-2">{answer.provenance.kind}</span>{provenanceText(answer)}</div>
              <div class="metric-label mt-5">Facts</div>
              <div class="table-wrap mt-2">
                <table class="data-table">
                  <thead><tr><th>Fact</th><th>Presence</th><th>Key</th><th>Value</th></tr></thead>
                  <tbody>
                    {#each answer.facts as fact}
                      <tr><td class="mono">{fact.name}</td><td>{fact.proof.presence}</td><td><CopyValue value={fact.keyHex} width={26} /></td><td>{#if fact.valueHex}<CopyValue value={fact.valueHex} width={26} />{:else}—{/if}</td></tr>
                    {/each}
                  </tbody>
                </table>
              </div>
              {#each answer.notes as note}<div class="notice notice-warn mt-3">{note}</div>{/each}
              <div class="mt-5 flex flex-wrap items-center gap-3">
                <button class="button-secondary" type="button" onclick={buildExport}>Build answer export</button>
                {#if exportUrl}<a class="button-primary" href={exportUrl} download={exportName}>Download {exportName}</a>{/if}
                <span class="text-xs text-[#789087]">Verify offline: <span class="mono">yano-trust verify --answer &lt;file&gt; --members members.json</span></span>
              </div>
            </div>
          {/if}
        </section>

      {:else if activeView === 'lists'}
        <section class="glass p-5">
          <div class="eyebrow">Status lists</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Check a served list against {selectedChainId}</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
            The chain holds the SHA-256 of each published list's raw bitstring. The console fetches the Bitstring
            Status List a <span class="mono">yano-trust serve</span> instance serves, decompresses it here, hashes it,
            and compares it with the chain's proof-bound entry.
          </p>
          <form class="mt-5 grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)_120px_auto]" onsubmit={(event) => { event.preventDefault(); void checkList(); }}>
            <div><label class="text-xs font-semibold text-[#a9beb7]" for="list-id">List id</label><input id="list-id" class="field mono mt-2" bind:value={listId} placeholder="list-1" /></div>
            <div><label class="text-xs font-semibold text-[#a9beb7]" for="service-url">Service URL <span class="muted">optional</span></label><input id="service-url" class="field mono mt-2" bind:value={serviceUrl} placeholder="http://127.0.0.1:8480" /></div>
            <div><label class="text-xs font-semibold text-[#a9beb7]" for="list-height">As of height</label><input id="list-height" class="field mono mt-2" bind:value={listHeight} placeholder="tip" /></div>
            <div class="flex items-end"><button class="button-primary" type="submit" disabled={listBusy || !listId.trim()}>{listBusy ? 'Checking…' : 'Check'}</button></div>
          </form>
          {#if listError}<div class="notice notice-error mt-4">{listError}</div>{/if}
          {#if listEntry}
            <div class="mt-5 grid gap-4 xl:grid-cols-2">
              <div class="panel p-4">
                <div class="flex items-center justify-between"><div class="metric-label">Chain entry</div><div class="flex gap-2"><span class="pill {presencePill(listEntry.presence)}">{listEntry.presence}</span><span class="pill {bindingPill(listEntry.binding)}">proofs {listEntry.binding}</span></div></div>
                {#if listEntry.decoded?.kind === 'status-list'}
                  <div class="mt-3 grid gap-1 text-xs text-[#b7cbc4]">
                    <div>purpose <span class="mono">{listEntry.decoded.value.purpose}</span>, {listEntry.decoded.value.bitLength} bits</div>
                    <div>published at height <span class="mono">{listEntry.decoded.value.publishedHeight}</span>, answered at height {listEntry.height}</div>
                    <div>chain hash <CopyValue value={listEntry.decoded.value.listSha256Hex} width={40} /></div>
                    <div>{provenanceText(listEntry)}</div>
                  </div>
                {:else}
                  <div class="empty-state mt-3">The list is not published at this height.</div>
                {/if}
              </div>
              <div class="panel p-4">
                <div class="flex items-center justify-between"><div class="metric-label">Served list</div>{#if served}<span class="pill {listMatches ? 'pill-ok' : 'pill-bad'}">{listMatches ? 'matches chain' : 'differs from chain'}</span>{/if}</div>
                {#if served}
                  <div class="mt-3 grid gap-1 text-xs text-[#b7cbc4]">
                    <div>purpose <span class="mono">{served.purpose}</span>, {served.bitLength} bits, {served.setCount} set</div>
                    <div>replayed to height <span class="mono">{served.replayedHeight}</span>, published at <span class="mono">{served.publishedHeight}</span></div>
                    <div>bitstring SHA-256 <CopyValue value={served.sha256Hex} width={40} /></div>
                    <div class="mt-2 flex items-end gap-3">
                      <div><label class="text-xs font-semibold text-[#a9beb7]" for="bit-index">Index</label><input id="bit-index" class="field mono mt-2 w-32" bind:value={bitIndex} placeholder="5" /></div>
                      {#if bitLookup !== null}<span class="pill {bitLookup ? 'pill-bad' : 'pill-ok'}">bit {bitIndex} is {bitLookup ? 'set' : 'clear'}</span>{/if}
                    </div>
                  </div>
                {:else}
                  <div class="empty-state mt-3">Enter the service URL to fetch and hash the served list.</div>
                {/if}
              </div>
            </div>
          {/if}
        </section>

      {:else if activeView === 'trqp'}
        <section class="glass p-5">
          <div class="eyebrow">Authorization</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Is this entity authorized on {selectedChainId}?</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
            A TRQP-shaped question answered from the chain's <span class="mono">issuers</span> entry: framework,
            granted authorizations, and validity heights, with the entry's proof.
          </p>
          <form class="mt-5 grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_minmax(0,1fr)_110px_auto]" onsubmit={(event) => { event.preventDefault(); void askTrqp(); }}>
            <div><label class="text-xs font-semibold text-[#a9beb7]" for="trqp-entity">Entity id</label><input id="trqp-entity" class="field mono mt-2" bind:value={trqpEntity} placeholder="issuer-a" /></div>
            <div><label class="text-xs font-semibold text-[#a9beb7]" for="trqp-auth">Authorization</label><input id="trqp-auth" class="field mono mt-2" bind:value={trqpAuthorization} /></div>
            <div><label class="text-xs font-semibold text-[#a9beb7]" for="trqp-framework">Framework</label><input id="trqp-framework" class="field mono mt-2" bind:value={trqpFramework} placeholder="yano-demo-framework-v1" /></div>
            <div><label class="text-xs font-semibold text-[#a9beb7]" for="trqp-height">Height</label><input id="trqp-height" class="field mono mt-2" bind:value={trqpHeight} placeholder="tip" /></div>
            <div class="flex items-end"><button class="button-primary" type="submit" disabled={trqpBusy || !trqpEntity.trim() || !trqpFramework.trim()}>{trqpBusy ? 'Asking…' : 'Ask'}</button></div>
          </form>
          {#if trqpError}<div class="notice notice-error mt-4">{trqpError}</div>{/if}
          {#if trqpAnswer && trqpResult}
            <div class="panel mt-5 p-4">
              <div class="flex flex-wrap items-center gap-3">
                <span class="pill {trqpResult.authorized ? 'pill-ok' : 'pill-bad'}">{trqpResult.authorized ? 'AUTHORIZED' : 'NOT AUTHORIZED'}</span>
                <span class="pill {bindingPill(trqpAnswer.binding)}">proofs {trqpAnswer.binding}</span>
                <span class="text-xs text-[#b7cbc4]">{trqpResult.reason} (height {trqpAnswer.height})</span>
              </div>
              {#if trqpAnswer.decoded?.kind === 'issuer'}
                <div class="mt-3 grid gap-1 text-xs text-[#b7cbc4] sm:grid-cols-2">
                  <div>framework <span class="mono">{trqpAnswer.decoded.value.framework}</span></div>
                  <div>valid heights <span class="mono">{trqpAnswer.decoded.value.validFromHeight} – {trqpAnswer.decoded.value.validUntilHeight || '∞'}</span></div>
                  <div class="sm:col-span-2">authorizations <span class="mono">{trqpAnswer.decoded.value.authorizations.join(', ') || '(none)'}</span></div>
                </div>
              {/if}
              <div class="mt-3 text-xs text-[#b7cbc4]"><span class="pill mr-2">{trqpAnswer.provenance.kind}</span>{provenanceText(trqpAnswer)}</div>
            </div>
          {/if}
        </section>

      {:else}
        <section class="glass p-5">
          <div class="eyebrow">Proof story</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">What an answer proves</h1>
          <div class="mt-4 grid gap-3 text-sm leading-6 text-[#b7cbc4] lg:grid-cols-2">
            <div class="panel p-4"><div class="metric-label">Presence</div><div class="mt-2">ACTIVE: the entry exists with this revision and value. REVOKED: a tombstone retains the last value hash; nothing is restored. ABSENT: an exclusion proof, the key has no entry at this height.</div></div>
            <div class="panel p-4"><div class="metric-label">Proofs BOUND</div><div class="mt-2">Every fact is a state proof at the answer height whose key, root, genesis, and certified block agree with the finalized block the node serves. The MPF paths and the finality certificate are verified by <span class="mono">yano-trust verify</span> on the export.</div></div>
            <div class="panel p-4"><div class="metric-label">Provenance</div><div class="mt-2">DIRECT_ROLE: the receipt, the one-use consumption, and the actor, organization, and policy records name who wrote the entry and under which policy revision. RECEIPT: bound to the applied receipt only. GENESIS: seeded before any block.</div></div>
            <div class="panel p-4"><div class="metric-label">Trust</div><div class="mt-2">The console shows internal consistency. Pin members with a members file or an anchor datum in the CLI to reach CALLER_PINNED_ROOT or INDEPENDENTLY_VERIFIED_L1_ANCHOR. The registry never asserts that a credential's claims are true.</div></div>
          </div>
        </section>
      {/if}
    </main>
  </div>
{/if}
