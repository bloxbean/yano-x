<script lang="ts">
  import { onMount } from 'svelte';
  import { GatewayApi, PortalApi } from '$lib/api';
  import { CONNECTION_DEFAULTS, DEFAULT_RUNTIME_CONFIG, loadRuntimeConfig, normalizeServiceUrl } from '$lib/config';
  import { decimal, dispositionText, failureMessage, flagText, formatEpochSeconds, pillFor, scaled, short } from '$lib/model';
  import { checkBundle, datumOf, parseFeedInput, parseRoundInput, type BundleCheck } from '$lib/round';
  import CopyValue from '$lib/components/CopyValue.svelte';
  import type {
    FeedSummary, GatewayActor, ReceiptDocument, RoundDocument, RoundRequestDocument, RoundViewDocument, RuntimeConfig
  } from '$lib/types';

  type View = 'round' | 'source' | 'operator' | 'about';
  type OperatorForm = 'feed' | 'close';

  const views: Array<{ id: View; code: string; label: string }> = [
    { id: 'round', code: 'RD', label: 'Round' },
    { id: 'source', code: 'SR', label: 'Source' },
    { id: 'operator', code: 'OP', label: 'Operator' },
    { id: 'about', code: '??', label: 'What this proves' }
  ];
  const forms: Array<{ id: OperatorForm; label: string }> = [
    { id: 'feed', label: 'Define feed' },
    { id: 'close', label: 'Close round' }
  ];

  let runtimeConfig: RuntimeConfig = DEFAULT_RUNTIME_CONFIG;
  let configReady = false;
  let activeView: View = 'round';

  // Round
  let portalUrl = CONNECTION_DEFAULTS.serviceUrl;
  let feedInput = 'coldstore-7';
  let roundInput = 'latest';
  let roundHeight = '';
  let roundBusy = false;
  let roundError = '';
  let portal: PortalApi | null = null;
  let summary: FeedSummary | null = null;
  let view: RoundViewDocument | null = null;
  let bundle: RoundDocument | null = null;
  let check: BundleCheck | null = null;
  let datum: { hex: string; sha256: string; binds: boolean } | null = null;
  let exportUrl = '';
  let exportName = '';

  // Gateway (shared by the Source and Operator views)
  let gatewayUrl = CONNECTION_DEFAULTS.gatewayUrl;
  let gatewayToken = '';
  let gateway: GatewayApi | null = null;
  let gatewayChainId = '';
  let actors: GatewayActor[] = [];
  let actorId = '';
  let gatewayError = '';
  let gatewayBusy = false;
  let result: ReceiptDocument | Record<string, unknown> | null = null;
  let resultError = '';
  let formBusy = false;

  // Source
  let observeFeed = 'coldstore-7';
  let observeScale = '2';
  let observeDecimal = '-18.25';
  let observeAt = String(Math.floor(Date.now() / 1000));
  let observeRound = '';
  let observeNote = '';

  // Operator
  let activeForm: OperatorForm = 'close';
  let feedId = 'coldstore-7';
  let feedDescription = 'Cold store 7 air temperature';
  let feedUnit = 'degC';
  let feedScale = '2';
  let feedEpochStart = String(Math.floor(Date.now() / 1000 / 60) * 60);
  let feedRoundSeconds = '60';
  let feedSources = 'source-alpha, source-beta, source-gamma';
  let feedMinimumSources = '2';
  let feedDeviationPpm = '20000';
  let feedDeviationAbsolute = '50';
  let feedMinimum = '-40.00';
  let feedMaximum = '10.00';
  let feedStatus = 'ACTIVE';
  let feedUpdate = false;
  let closeFeed = 'coldstore-7';
  let closeRound = '';
  let closeHeight = '';
  let requestText = '';
  let requestUrl = '';
  let dispositions: Record<string, string> | null = null;

  $: selectedActor = actors.find((actor) => actor.actorId === actorId) ?? null;
  $: sourceActors = actors.filter((actor) => actor.roles?.includes('source'));
  $: statusClass = check ? pillFor(check.status) : 'pill';
  $: parsedRequest = parseRequest(requestText);
  $: recordAgreement = check?.agrees === null || check?.agrees === undefined ? '' : check.agrees ? 'AGREES' : 'DISAGREES';

  onMount(() => {
    void loadRuntimeConfig().then((loaded) => {
      runtimeConfig = loaded;
      if (loaded.serviceUrl) portalUrl = loaded.serviceUrl;
      if (loaded.gatewayUrl) gatewayUrl = loaded.gatewayUrl;
      configReady = true;
      const hash = globalThis.location?.hash ?? '';
      const named = hash.match(/^#\/feeds\/([a-z0-9][a-z0-9._-]{0,31})(?:\/rounds\/([0-9]{1,19}|latest))?$/);
      if (named) {
        feedInput = named[1];
        roundInput = named[2] ?? 'latest';
        void openRound();
      }
    });
  });

  // ------------------------------------------------------------------ round

  async function openRound() {
    roundBusy = true;
    roundError = '';
    datum = null;
    releaseExport();
    try {
      const api = new PortalApi(normalizeServiceUrl(portalUrl, 'portal'));
      const id = parseFeedInput(feedInput);
      const round = parseRoundInput(roundInput);
      const height = roundHeight.trim() ? Number(roundHeight) : undefined;
      if (height !== undefined && (!Number.isInteger(height) || height < 1)) throw new Error('Height must be a positive integer');
      const [fetchedSummary, fetchedView, fetchedBundle] = await Promise.all([
        api.feed(id), api.round(id, round, height), api.proof(id, round, height)
      ]);
      if (fetchedBundle.feedId !== id || fetchedView.feedId !== id || fetchedBundle.round !== fetchedView.round) {
        throw new Error('The portal answered for another feed or round');
      }
      portal = api;
      summary = fetchedSummary;
      view = fetchedView;
      bundle = fetchedBundle;
      check = checkBundle(fetchedBundle);
      roundInput = String(fetchedBundle.round);
      if (check.feed && check.result && check.record && check.record.status === 1 && check.agrees && check.sameHeight) {
        const computed = await datumOf(fetchedBundle, check.feed, check.result);
        datum = { ...computed, binds: computed.sha256 === check.record.datumSha256 };
      }
      const json = JSON.stringify(fetchedBundle, null, 2);
      exportUrl = URL.createObjectURL(new Blob([json], { type: 'application/json' }));
      exportName = `round-${id}-${fetchedBundle.round}-h${fetchedBundle.observationHeight}.json`;
      if (globalThis.history) globalThis.history.replaceState(null, '', `#/feeds/${id}/rounds/${fetchedBundle.round}`);
    } catch (cause) {
      summary = null;
      view = null;
      bundle = null;
      check = null;
      roundError = failureMessage(cause, 'The round could not be read');
    } finally {
      roundBusy = false;
    }
  }

  function releaseExport() {
    if (exportUrl) URL.revokeObjectURL(exportUrl);
    exportUrl = '';
    exportName = '';
  }

  function bindingFor(label: string): { bound: boolean; notes: string[] } | null {
    return check?.answers.find((answer) => answer.label === label) ?? null;
  }

  function sourceLabel(source: Record<string, unknown>): string {
    return `observations/${view?.feedId}/${view?.round}/${source.sourceId}`;
  }

  function provenanceText(node: unknown): string {
    const p = (node ?? {}) as Record<string, unknown>;
    switch (p.kind) {
      case 'GENESIS': return 'Seeded at genesis.';
      case 'RECEIPT': return `Applied by message ${short(String(p.messageId ?? ''), 18)} at height ${p.appliedHeight} through the approval route.`;
      case 'DIRECT_ROLE': return `Written by ${p.actorId} (${p.organizationId}, role ${p.role}, key ${p.keyId}) under policy ${p.policyId} revision ${p.policyRevision}, applied at height ${p.appliedHeight}.`;
      default: return 'No entry was ever written under this key.';
    }
  }

  function decimalOf(value: unknown, scale: number): string {
    try {
      return decimal(BigInt(String(value)), scale);
    } catch {
      return String(value);
    }
  }

  function disposition(source: Record<string, unknown>): string {
    const own = check?.result?.sources.find((entry) => entry.sourceId === source.sourceId);
    return own ? own.disposition : String(source.disposition ?? 'UNKNOWN');
  }

  // ------------------------------------------------------------------ gateway

  async function connectGateway() {
    gatewayBusy = true;
    gatewayError = '';
    try {
      const api = new GatewayApi(normalizeServiceUrl(gatewayUrl, 'gateway'), gatewayToken.trim());
      const listed = await api.actors();
      gateway = api;
      gatewayChainId = listed.chainId;
      actors = listed.actors;
      actorId = actors[0]?.actorId ?? '';
      gatewayToken = '';
    } catch (cause) {
      gateway = null;
      gatewayError = failureMessage(cause, 'The gateway could not be reached');
    } finally {
      gatewayBusy = false;
    }
  }

  function disconnectGateway() {
    gateway = null;
    actors = [];
    actorId = '';
    result = null;
    resultError = '';
  }

  async function submit(action: () => Promise<ReceiptDocument | Record<string, unknown>>) {
    if (!gateway) return;
    formBusy = true;
    resultError = '';
    result = null;
    try {
      result = await action();
    } catch (cause) {
      resultError = failureMessage(cause, 'The gateway refused the request');
    } finally {
      formBusy = false;
    }
  }

  function integerText(value: string, name: string): string {
    const trimmed = value.trim();
    if (!/^-?[0-9]{1,19}$/.test(trimmed)) throw new Error(`${name} must be an integer`);
    return trimmed;
  }

  function observe() {
    return submit(() => {
      const scale = Number(observeScale);
      if (!Number.isInteger(scale) || scale < 0 || scale > 18) throw new Error('The scale is 0-18');
      const body: Record<string, unknown> = {
        actorId, feedId: parseFeedInput(observeFeed), value: scaled(observeDecimal, scale).toString(),
        observedAt: integerText(observeAt, 'Observed at'), note: observeNote.trim()
      };
      if (observeRound.trim()) body.round = integerText(observeRound, 'Round');
      return gateway!.observe(body);
    });
  }

  function defineFeed() {
    return submit(() => {
      const scale = Number(feedScale);
      if (!Number.isInteger(scale) || scale < 0 || scale > 18) throw new Error('The scale is 0-18');
      const sources = feedSources.split(',').map((source) => source.trim()).filter(Boolean);
      return gateway!.defineFeed({
        actorId, feedId: parseFeedInput(feedId), update: feedUpdate,
        feed: {
          description: feedDescription.trim(), unit: feedUnit.trim(), scale,
          epochStart: integerText(feedEpochStart, 'Epoch start'), roundSeconds: integerText(feedRoundSeconds, 'Round seconds'),
          sources, minimumSources: integerText(feedMinimumSources, 'Quorum'),
          maximumDeviationPpm: integerText(feedDeviationPpm, 'Deviation ppm'),
          maximumDeviationAbsolute: integerText(feedDeviationAbsolute, 'Absolute deviation'),
          minimumValue: scaled(feedMinimum, scale).toString(), maximumValue: scaled(feedMaximum, scale).toString(),
          status: feedStatus
        }
      });
    });
  }

  function propose() {
    return submit(async () => {
      const body: Record<string, unknown> = { actorId, feedId: parseFeedInput(closeFeed), round: integerText(closeRound, 'Round') };
      if (closeHeight.trim()) body.height = integerText(closeHeight, 'Height');
      const proposed = await gateway!.propose(body);
      requestText = JSON.stringify(proposed.request, null, 2);
      dispositions = proposed.dispositions ?? null;
      refreshRequestUrl();
      return proposed as unknown as Record<string, unknown>;
    });
  }

  function decide(approve: boolean) {
    return submit(async () => {
      if (!parsedRequest) throw new Error('Paste a feed-round-request-v1 document');
      return (await gateway!.decide(approve, actorId, parsedRequest)) as unknown as Record<string, unknown>;
    });
  }

  function apply() {
    return submit(async () => {
      if (!parsedRequest) throw new Error('Paste a feed-round-request-v1 document');
      return gateway!.apply(parsedRequest);
    });
  }

  function parseRequest(text: string): RoundRequestDocument | null {
    try {
      const value = JSON.parse(text) as RoundRequestDocument;
      return value && value.type === 'feed-round-request-v1' && value.schemaVersion === 1 ? value : null;
    } catch {
      return null;
    }
  }

  function refreshRequestUrl() {
    if (requestUrl) URL.revokeObjectURL(requestUrl);
    requestUrl = requestText ? URL.createObjectURL(new Blob([requestText], { type: 'application/json' })) : '';
  }

  function resultRows(receipt: unknown): Array<{ collection: string; key: string; revision: number; status: string }> {
    const node = receipt as { results?: Array<{ collection: string; key: string; revision: number; status: string }> };
    return Array.isArray(node?.results) ? node.results : [];
  }

  function requestRecord(request: RoundRequestDocument | null): Record<string, unknown> | null {
    return request?.record && typeof request.record === 'object' ? request.record : null;
  }
</script>

{#if !configReady}
  <div class="grid min-h-screen place-items-center"><span class="pill">Loading interface</span></div>
{:else}
  <header class="border-b border-white/[.07] bg-[#050b0a]/70 backdrop-blur-xl">
    <div class="shell flex min-h-[4.7rem] flex-wrap items-center justify-between gap-4 py-3">
      <div class="flex items-center gap-3">
        <div class="grid size-10 place-items-center rounded-xl border border-mint-400/30 bg-mint-400/10 font-mono text-xs font-bold text-mint-400">AF</div>
        <div><div class="text-sm font-semibold tracking-tight">Yano X <span class="text-mint-400">Attestation Feed</span></div><div class="text-[.67rem] uppercase tracking-[.1em] text-[#60766f]">consortium observations with recomputable rounds, experimental</div></div>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <span class="pill pill-warn">experimental starter, not an oracle</span>
        {#if portal}<span class="pill pill-ok">portal {short(portal.baseUrl, 28)}</span>{/if}
        {#if gateway}<span class="pill pill-ok">gateway {gatewayChainId}</span>{/if}
      </div>
    </div>
  </header>

  <div class="shell grid gap-5 py-5 lg:grid-cols-[240px_minmax(0,1fr)]">
    <aside class="glass h-fit p-3 lg:sticky lg:top-5">
      <nav class="flex gap-1 overflow-auto lg:block" aria-label="Console sections">
        {#each views as item}
          <button class="nav-item shrink-0 {activeView === item.id ? 'active' : ''}" type="button" onclick={() => { activeView = item.id; }}>
            <span class="nav-icon">{item.code}</span><span>{item.label}</span>
          </button>
        {/each}
      </nav>
      <div class="mt-4 border-t border-white/[.07] px-2 pt-4 text-xs text-[#789087]">
        <div class="eyebrow">Posture</div>
        <p class="mt-2 leading-5">A configuration-only observation ledger on the governed authenticated map (ADR-052). Aggregates are recomputed by every verifier; nothing is published to Cardano.</p>
      </div>
    </aside>

    <main class="min-w-0">
      {#if activeView === 'round'}
        <section class="glass p-5">
          <div class="eyebrow">Round</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Open a feed round</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
            The portal answers the feed policy and every configured source's observation with a state proof at
            the closing height, and the consortium's record at its apply height. The browser checks that the
            proofs name one chain and genesis, recomputes the round with the same integer rules, and compares
            it with the record. No key or secret is needed here.
          </p>
          <form class="mt-5 grid gap-3 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_120px_110px_auto]" onsubmit={(event) => { event.preventDefault(); void openRound(); }}>
            <div>
              <label class="text-xs font-semibold text-[#a9beb7]" for="portal-url">Portal URL</label>
              <input id="portal-url" class="field mono mt-2" bind:value={portalUrl} disabled={!runtimeConfig.allowServiceOverride && !!runtimeConfig.serviceUrl} />
            </div>
            <div>
              <label class="text-xs font-semibold text-[#a9beb7]" for="feed-input">Feed id</label>
              <input id="feed-input" class="field mono mt-2" bind:value={feedInput} placeholder="coldstore-7" />
            </div>
            <div>
              <label class="text-xs font-semibold text-[#a9beb7]" for="round-input">Round</label>
              <input id="round-input" class="field mono mt-2" bind:value={roundInput} placeholder="latest" />
            </div>
            <div>
              <label class="text-xs font-semibold text-[#a9beb7]" for="round-height">Height</label>
              <input id="round-height" class="field mono mt-2" bind:value={roundHeight} placeholder="record's" />
            </div>
            <div class="flex items-end"><button class="button-primary" type="submit" disabled={roundBusy || !feedInput.trim()}>{roundBusy ? 'Opening…' : 'Open round'}</button></div>
          </form>
          {#if roundError}<div class="notice notice-error mt-4">{roundError}</div>{/if}

          {#if view && bundle && check}
            <div class="panel mt-5 p-4">
              <div class="flex flex-wrap items-center gap-3">
                <span class={statusClass}>{check.status}</span>
                <span class="pill {check.binding === 'BOUND' ? 'pill-ok' : 'pill-bad'}">proofs {check.binding}</span>
                {#if recordAgreement}<span class={pillFor(recordAgreement)}>record {recordAgreement}</span>{/if}
                <span class="pill">{bundle.answers.length} answers, observations at height {bundle.observationHeight}, record at {bundle.recordHeight}</span>
                <span class="pill">{check.certSignatures} certificate signature(s)</span>
                {#each check.flags as flag}<span class="pill pill-warn" title={flagText(flag)}>{flag}</span>{/each}
              </div>
              <div class="mt-3 grid gap-1 text-xs text-[#b7cbc4] sm:grid-cols-2">
                <div>feed <CopyValue value={view.feedId} width={32} /> round <span class="mono">{view.round}</span></div>
                <div>chain <span class="mono">{view.chainId}</span></div>
                <div>state root at the observation height <CopyValue value={bundle.stateRoot} width={30} /></div>
                {#if check.feed}<div>window <span class="mono">{formatEpochSeconds(check.feed.epochStart + BigInt(view.round) * check.feed.roundSeconds)}</span> to <span class="mono">{formatEpochSeconds(check.feed.epochStart + (BigInt(view.round) + 1n) * check.feed.roundSeconds - 1n)}</span></div>{/if}
                {#if summary?.currentRoundByPortalClock !== undefined}<div class="sm:col-span-2">calendar round by the portal's clock: <span class="mono">{summary.currentRoundByPortalClock}</span>; rounds with records: <span class="mono">{summary.roundsWithRecords.join(', ') || 'none'}</span></div>{/if}
              </div>
              {#each check.notes as note}<div class="notice notice-warn mt-3 text-xs">{note}</div>{/each}
              <div class="mt-4 flex flex-wrap gap-2">
                {#if exportUrl}<a class="button-secondary" href={exportUrl} download={exportName}>Download round bundle</a>{/if}
                <span class="self-center text-xs text-[#789087]">Verify offline: <span class="mono">yano-feed verify --bundle {exportName || 'round.json'} --members members.json</span></span>
              </div>
            </div>

            <div class="mt-5 grid gap-4">
              <div class="panel p-4">
                <div class="metric-label">Feed policy at height {bundle.observationHeight}</div>
                {#if check.feed}
                  {@const binding = bindingFor(`feeds/${view.feedId}`)}
                  <div class="mt-3 flex flex-wrap items-center gap-2 text-xs text-[#b7cbc4]">
                    <span class="font-semibold text-white">{check.feed.description || view.feedId}</span>
                    <span class={pillFor(check.feed.status === 0 ? 'ACTIVE' : 'PAUSED')}>{check.feed.status === 0 ? 'ACTIVE' : 'PAUSED'}</span>
                    {#if binding}<span class="pill {binding.bound ? 'pill-ok' : 'pill-bad'}">proof {binding.bound ? 'BOUND' : 'MISMATCH'}</span>{/if}
                  </div>
                  <div class="mt-2 text-xs text-[#b7cbc4]">unit <span class="mono">{check.feed.unit}</span> at scale {check.feed.scale}; rounds of {check.feed.roundSeconds} s from epoch {check.feed.epochStart}; quorum {check.feed.minimumSources} of {check.feed.sources.length}; outliers beyond {check.feed.maximumDeviationPpm} ppm or {check.feed.maximumDeviationAbsolute} absolute; values within [{decimal(check.feed.minimumValue, check.feed.scale)}, {decimal(check.feed.maximumValue, check.feed.scale)}]</div>
                  <div class="mt-1 text-xs text-[#b7cbc4]">{provenanceText(view.feed.provenance)}</div>
                {:else}
                  <div class="empty-state mt-3">The feed is not active at this height.</div>
                {/if}
              </div>

              <div class="panel p-4">
                <div class="metric-label">Sources at height {bundle.observationHeight}</div>
                <div class="table-wrap mt-3">
                  <table class="data-table">
                    <thead><tr><th>Source</th><th>Value</th><th>Observed at (signed)</th><th>Disposition</th><th>Revision</th><th>Writer</th><th>Proof</th></tr></thead>
                    <tbody>
                      {#each view.sources as source}
                        {@const binding = bindingFor(sourceLabel(source))}
                        {@const state = disposition(source)}
                        <tr>
                          <td class="mono">{source.sourceId}</td>
                          <td class="mono">{source.value !== undefined ? decimalOf(source.value, check.feed?.scale ?? 0) : '—'}</td>
                          <td>{source.observedAt !== undefined ? formatEpochSeconds(Number(source.observedAt)) : '—'}</td>
                          <td><span class={pillFor(state)} title={dispositionText(state)}>{state}</span></td>
                          <td>{source.revision ?? '—'}</td>
                          <td class="mono">{(source.provenance as Record<string, unknown>)?.actorId ?? '—'}</td>
                          <td>{#if binding}<span class="pill {binding.bound ? 'pill-ok' : 'pill-bad'}">{binding.bound ? 'BOUND' : 'MISMATCH'}</span>{/if}</td>
                        </tr>
                      {/each}
                    </tbody>
                  </table>
                </div>
              </div>

              <div class="grid gap-4 lg:grid-cols-2">
                <div class="panel p-4">
                  <div class="metric-label">Recomputed in this browser</div>
                  {#if check.result && check.feed}
                    <div class="mt-3 flex flex-wrap items-center gap-2">
                      <span class={pillFor(check.result.statusName)}>{check.result.statusName}</span>
                      {#if check.result.statusName === 'CLOSED'}<span class="text-2xl font-semibold text-white">{decimal(check.result.aggregate, check.result.scale)} <span class="text-sm text-[#8ea8a0]">{check.feed.unit}</span></span>{/if}
                    </div>
                    <div class="mt-2 text-xs text-[#b7cbc4]">lower median of {check.result.acceptedSources.join(', ') || 'no source'} ({check.result.candidateCount} candidate(s) of {check.feed.sources.length}); rule <span class="mono">feed-aggregation-v1</span></div>
                  {:else}
                    <div class="empty-state mt-3">The round could not be recomputed from this bundle.</div>
                  {/if}
                </div>
                <div class="panel p-4">
                  <div class="metric-label">Recorded by the consortium</div>
                  {#if check.record}
                    {@const binding = bindingFor(`rounds/${view.feedId}/${view.round}`)}
                    <div class="mt-3 flex flex-wrap items-center gap-2">
                      <span class={pillFor(check.status)}>{check.status}</span>
                      {#if check.record.status === 1}<span class="text-2xl font-semibold text-white">{decimal(check.record.aggregate, check.record.scale)} <span class="text-sm text-[#8ea8a0]">{check.feed?.unit ?? ''}</span></span>{/if}
                      {#if recordAgreement}<span class={pillFor(recordAgreement)}>{recordAgreement}</span>{/if}
                      <span class="pill {view.record.approvalConsumption ? 'pill-ok' : 'pill-warn'}">approval consumption {view.record.approvalConsumption ? 'proven' : 'absent'}</span>
                      {#if binding}<span class="pill {binding.bound ? 'pill-ok' : 'pill-bad'}">proof {binding.bound ? 'BOUND' : 'MISMATCH'}</span>{/if}
                    </div>
                    <div class="mt-2 text-xs text-[#b7cbc4]">closed at height {check.record.closedAtHeight}{#if !check.sameHeight} (this bundle recomputes at {bundle.observationHeight}; compare, do not verify){/if}; accepted {check.record.acceptedSources.join(', ') || 'none'}; policy <CopyValue value={check.record.policySha256} width={22} /></div>
                    <div class="mt-1 text-xs text-[#b7cbc4]">{provenanceText(view.record.provenance)}</div>
                    {#if datum}
                      <div class="mt-2 text-xs text-[#b7cbc4]">candidate datum <CopyValue value={datum.sha256} width={22} /> <span class={pillFor(datum.binds ? 'BINDS' : 'MISMATCH')}>{datum.binds ? 'BINDS the record' : 'does not bind the record'}</span> (feed-datum-candidate-v1, for the deferred Cardano executor; nothing is published)</div>
                    {/if}
                  {:else}
                    <div class="empty-state mt-3">{check.status === 'OPEN' ? `No record at height ${bundle.recordHeight}: the round is open (the recomputation is a preview).` : 'The record is ' + check.status + '.'}</div>
                  {/if}
                </div>
              </div>

              <div class="panel p-4">
                <div class="metric-label">Proof rows</div>
                <div class="table-wrap mt-3">
                  <table class="data-table">
                    <thead><tr><th>Record</th><th>Presence</th><th>Height</th><th>Provenance</th><th>Facts</th><th>Binding</th></tr></thead>
                    <tbody>
                      {#each check.answers as answer}
                        <tr>
                          <td class="mono">{answer.label}</td>
                          <td><span class={pillFor(answer.presence)}>{answer.presence}</span></td>
                          <td>{answer.height}</td>
                          <td class="mono">{answer.provenance}</td>
                          <td>{answer.facts}</td>
                          <td><span class="pill {answer.bound ? 'pill-ok' : 'pill-bad'}">{answer.bound ? 'BOUND' : 'MISMATCH'}</span>{#each answer.notes as note}<div class="mt-1 text-[.68rem] text-[#f8c97d]">{note}</div>{/each}</td>
                        </tr>
                      {/each}
                    </tbody>
                  </table>
                </div>
                <div class="mt-3 text-xs text-[#789087]">MPF paths and the finality certificate are verified by <span class="mono">yano-feed verify</span> on the export.</div>
              </div>
            </div>
          {/if}
        </section>

      {:else if activeView === 'source'}
        <section class="glass p-5">
          <div class="eyebrow">Source</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Submit an observation</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
            A source signs its reading with its own actor key through the gateway that holds it. The value is
            an exact decimal at the feed's scale; the signed time picks the round on the feed's calendar.
          </p>
          {#if !gateway}
            <form class="mt-5 grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto]" onsubmit={(event) => { event.preventDefault(); void connectGateway(); }}>
              <div><label class="text-xs font-semibold text-[#a9beb7]" for="gateway-url">Gateway URL</label><input id="gateway-url" class="field mono mt-2" bind:value={gatewayUrl} /></div>
              <div><label class="text-xs font-semibold text-[#a9beb7]" for="gateway-token">Gateway token</label><input id="gateway-token" class="field mono mt-2" type="password" autocomplete="off" bind:value={gatewayToken} placeholder="printed by yano-feed gateway" /></div>
              <div class="flex items-end"><button class="button-primary" type="submit" disabled={gatewayBusy || !gatewayToken.trim()}>{gatewayBusy ? 'Connecting…' : 'Connect'}</button></div>
            </form>
            {#if gatewayError}<div class="notice notice-error mt-4">{gatewayError}</div>{/if}
          {:else}
            <div class="mt-5 flex flex-wrap items-center gap-3">
              <label class="text-xs font-semibold text-[#a9beb7]" for="source-picker">Sign as</label>
              <select id="source-picker" class="field w-auto" bind:value={actorId}>
                {#each (sourceActors.length ? sourceActors : actors) as actor}<option value={actor.actorId}>{actor.actorId}{actor.organizationId ? ` (${actor.organizationId}${actor.roles ? ': ' + actor.roles.join(', ') : ''})` : ''}</option>{/each}
              </select>
              <button class="button-quiet min-h-0 py-2" type="button" onclick={disconnectGateway}>Disconnect</button>
            </div>
            <div class="panel mt-4 p-4">
              <div class="grid gap-3 sm:grid-cols-2">
                <div><label class="text-xs font-semibold text-[#a9beb7]" for="ob-feed">Feed id</label><input id="ob-feed" class="field mono mt-2" bind:value={observeFeed} /></div>
                <div><label class="text-xs font-semibold text-[#a9beb7]" for="ob-scale">Feed scale (decimal places)</label><input id="ob-scale" class="field mono mt-2" bind:value={observeScale} /></div>
                <div><label class="text-xs font-semibold text-[#a9beb7]" for="ob-value">Reading</label><input id="ob-value" class="field mono mt-2" bind:value={observeDecimal} placeholder="-18.25" /></div>
                <div><label class="text-xs font-semibold text-[#a9beb7]" for="ob-at">Observed at (epoch seconds, signed)</label><input id="ob-at" class="field mono mt-2" bind:value={observeAt} /></div>
                <div><label class="text-xs font-semibold text-[#a9beb7]" for="ob-round">Round (blank: from the time)</label><input id="ob-round" class="field mono mt-2" bind:value={observeRound} /></div>
                <div><label class="text-xs font-semibold text-[#a9beb7]" for="ob-note">Note</label><input id="ob-note" class="field mt-2" bind:value={observeNote} /></div>
                <div class="flex items-end"><button class="button-primary" type="button" disabled={formBusy || !observeDecimal.trim()} onclick={() => void observe()}>Submit observation (source)</button></div>
              </div>
            </div>
            {#if resultError}<div class="notice notice-error mt-4">{resultError}</div>{/if}
            {#if result}
              <div class="panel mt-4 p-4">
                <div class="flex flex-wrap items-center gap-2">
                  <div class="metric-label">Result</div>
                  {#if 'status' in result}<span class={pillFor(String(result.status))}>{result.status}</span>{/if}
                  {#if 'height' in result}<span class="pill">height {result.height}</span>{/if}
                  {#if 'round' in result}<span class="pill">round {result.round}</span>{/if}
                  {#if 'errorName' in result && result.errorName !== 'NONE'}<span class="pill pill-bad">{result.errorName}</span>{/if}
                  {#if 'messageId' in result}<span class="text-xs text-[#b7cbc4]">message <CopyValue value={String(result.messageId)} width={24} /></span>{/if}
                </div>
                {#each resultRows(result) as row}
                  <div class="mt-2 text-xs text-[#b7cbc4]"><span class="mono">{row.collection}/{row.key}</span> revision {row.revision} {row.status}</div>
                {/each}
              </div>
            {/if}
          {/if}
        </section>

      {:else if activeView === 'operator'}
        <section class="glass p-5">
          <div class="eyebrow">Operator</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Define feeds and close rounds</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
            A feed administrator defines the policy; a feed operator proposes a round record computed at one
            height; two publishers from distinct organizations approve after recomputing it themselves (the
            gateway refuses an approval whose numbers it cannot reproduce); anyone applies. The token is the
            only secret this console holds, in memory.
          </p>
          {#if !gateway}
            <form class="mt-5 grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto]" onsubmit={(event) => { event.preventDefault(); void connectGateway(); }}>
              <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-gateway-url">Gateway URL</label><input id="op-gateway-url" class="field mono mt-2" bind:value={gatewayUrl} /></div>
              <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-gateway-token">Gateway token</label><input id="op-gateway-token" class="field mono mt-2" type="password" autocomplete="off" bind:value={gatewayToken} placeholder="printed by yano-feed gateway" /></div>
              <div class="flex items-end"><button class="button-primary" type="submit" disabled={gatewayBusy || !gatewayToken.trim()}>{gatewayBusy ? 'Connecting…' : 'Connect'}</button></div>
            </form>
            {#if gatewayError}<div class="notice notice-error mt-4">{gatewayError}</div>{/if}
          {:else}
            <div class="mt-5 flex flex-wrap items-center gap-3">
              <label class="text-xs font-semibold text-[#a9beb7]" for="actor-picker">Sign as</label>
              <select id="actor-picker" class="field w-auto" bind:value={actorId}>
                {#each actors as actor}<option value={actor.actorId}>{actor.actorId}{actor.organizationId ? ` (${actor.organizationId}${actor.roles ? ': ' + actor.roles.join(', ') : ''})` : ''}</option>{/each}
              </select>
              <button class="button-quiet min-h-0 py-2" type="button" onclick={disconnectGateway}>Disconnect</button>
            </div>
            <div class="mt-4 flex flex-wrap gap-1">
              {#each forms as form}
                <button class="nav-item {activeForm === form.id ? 'active' : ''}" type="button" onclick={() => { activeForm = form.id; result = null; resultError = ''; }}>{form.label}</button>
              {/each}
            </div>

            <div class="panel mt-4 p-4">
              <div class="grid gap-3 sm:grid-cols-2">
                {#if activeForm === 'feed'}
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="fd-id">Feed id</label><input id="fd-id" class="field mono mt-2" bind:value={feedId} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="fd-description">Description</label><input id="fd-description" class="field mt-2" bind:value={feedDescription} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="fd-unit">Unit</label><input id="fd-unit" class="field mono mt-2" bind:value={feedUnit} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="fd-scale">Scale (decimal places)</label><input id="fd-scale" class="field mono mt-2" bind:value={feedScale} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="fd-epoch">Epoch start (epoch seconds)</label><input id="fd-epoch" class="field mono mt-2" bind:value={feedEpochStart} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="fd-seconds">Round length (seconds)</label><input id="fd-seconds" class="field mono mt-2" bind:value={feedRoundSeconds} /></div>
                  <div class="sm:col-span-2"><label class="text-xs font-semibold text-[#a9beb7]" for="fd-sources">Source actors (comma separated)</label><input id="fd-sources" class="field mono mt-2" bind:value={feedSources} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="fd-quorum">Quorum (minimum sources)</label><input id="fd-quorum" class="field mono mt-2" bind:value={feedMinimumSources} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="fd-status">Status</label><select id="fd-status" class="field mt-2" bind:value={feedStatus}><option value="ACTIVE">ACTIVE</option><option value="PAUSED">PAUSED</option></select></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="fd-ppm">Outlier tolerance (ppm of the median)</label><input id="fd-ppm" class="field mono mt-2" bind:value={feedDeviationPpm} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="fd-abs">Outlier tolerance (absolute, at scale)</label><input id="fd-abs" class="field mono mt-2" bind:value={feedDeviationAbsolute} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="fd-min">Minimum value</label><input id="fd-min" class="field mono mt-2" bind:value={feedMinimum} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="fd-max">Maximum value</label><input id="fd-max" class="field mono mt-2" bind:value={feedMaximum} /></div>
                  <div class="flex items-center gap-2"><input id="fd-update" type="checkbox" bind:checked={feedUpdate} /><label class="text-xs text-[#a9beb7]" for="fd-update">Update an existing feed (compare-and-set)</label></div>
                  <div class="flex items-end"><button class="button-primary" type="button" disabled={formBusy} onclick={() => void defineFeed()}>{feedUpdate ? 'Update feed' : 'Create feed'} (feed admin)</button></div>
                {:else}
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="cl-feed">Feed id</label><input id="cl-feed" class="field mono mt-2" bind:value={closeFeed} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="cl-round">Round</label><input id="cl-round" class="field mono mt-2" bind:value={closeRound} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="cl-height">Closing height (blank: the tip)</label><input id="cl-height" class="field mono mt-2" bind:value={closeHeight} /></div>
                  <div class="flex items-end"><button class="button-primary" type="button" disabled={formBusy || !closeRound.trim()} onclick={() => void propose()}>1. Propose (feed operator)</button></div>
                  {#if dispositions}<div class="text-xs text-[#b7cbc4] sm:col-span-2">dispositions at the closing height: {#each Object.entries(dispositions) as [source, state]}<span class="mono">{source}</span> <span class={pillFor(state)} title={dispositionText(state)}>{state}</span> {/each}</div>{/if}
                  <div class="sm:col-span-2"><label class="text-xs font-semibold text-[#a9beb7]" for="op-request">Round request (travels between the operator, the publishers, and whoever applies)</label><textarea id="op-request" class="field mono mt-2 min-h-28" bind:value={requestText} oninput={refreshRequestUrl}></textarea></div>
                  {#if requestRecord(parsedRequest)}
                    {@const record = requestRecord(parsedRequest)}
                    <div class="text-xs text-[#b7cbc4] sm:col-span-2">proposes <span class={pillFor(String(record?.status))}>{record?.status}</span> {record?.decimal} from {(record?.acceptedSources as string[] | undefined)?.join(', ') || 'no source'} at height {record?.closedAtHeight}; a publisher's approval recomputes this first</div>
                  {/if}
                  <div class="flex flex-wrap items-end gap-2 sm:col-span-2">
                    <button class="button-secondary" type="button" disabled={formBusy || !parsedRequest} onclick={() => void decide(true)}>2. Approve (publisher, recomputes first)</button>
                    <button class="button-danger" type="button" disabled={formBusy || !parsedRequest} onclick={() => void decide(false)}>Reject (publisher)</button>
                    <button class="button-primary" type="button" disabled={formBusy || !parsedRequest} onclick={() => void apply()}>3. Apply</button>
                    {#if requestUrl}<a class="button-quiet" href={requestUrl} download={`round-request-${parsedRequest?.proposalId ?? 'draft'}.json`}>Download request</a>{/if}
                  </div>
                {/if}
              </div>
            </div>

            {#if resultError}<div class="notice notice-error mt-4">{resultError}</div>{/if}
            {#if result}
              <div class="panel mt-4 p-4">
                <div class="flex flex-wrap items-center gap-2">
                  <div class="metric-label">Result</div>
                  {#if 'status' in result}<span class={pillFor(String(result.status))}>{result.status}</span>{/if}
                  {#if 'height' in result}<span class="pill">height {result.height}</span>{/if}
                  {#if 'errorName' in result && result.errorName !== 'NONE'}<span class="pill pill-bad">{result.errorName}</span>{/if}
                  {#if 'decision' in result}<span class={pillFor(String(result.decision))}>{result.decision}</span>{/if}
                  {#if 'proposalId' in result}<span class="pill">proposal {result.proposalId}</span>{/if}
                  {#if 'messageId' in result}<span class="text-xs text-[#b7cbc4]">message <CopyValue value={String(result.messageId)} width={24} /></span>{/if}
                </div>
                {#each resultRows(result) as row}
                  <div class="mt-2 text-xs text-[#b7cbc4]"><span class="mono">{row.collection}/{row.key}</span> revision {row.revision} {row.status}</div>
                {/each}
                <details class="mt-3 text-xs text-[#789087]"><summary>Raw response</summary><pre class="mono mt-2 max-h-72 overflow-auto text-[.68rem]">{JSON.stringify(result, null, 2)}</pre></details>
              </div>
            {/if}
          {/if}
        </section>

      {:else}
        <section class="glass p-5">
          <div class="eyebrow">Proof story</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">What a round proves</h1>
          <div class="mt-4 grid gap-3 text-sm leading-6 text-[#b7cbc4] lg:grid-cols-2">
            <div class="panel p-4"><div class="metric-label">Observations</div><div class="mt-2">Each configured source's observation is a state proof at the closing height: ACTIVE with this value, writer, and revision, or ABSENT by exclusion. The feed policy is proven at the same height under the same root, so the set a round aggregates over is exactly known.</div></div>
            <div class="panel p-4"><div class="metric-label">The aggregate</div><div class="mt-2">Nothing on chain computes it. The browser and <span class="mono">yano-feed verify</span> recompute the lower median with the feed's outlier and quorum rules from the proven observations (feed-aggregation-v1) and compare it with the consortium's record. A record that disagrees is a flagged failure, never hidden.</div></div>
            <div class="panel p-4"><div class="metric-label">The record</div><div class="mt-2">A feed operator proposes, two publishers from distinct organizations approve after recomputing, and the map applies the record once with its proposal's one-use consumption proven. The record binds the policy it was computed under and the hash of a candidate datum for the deferred Cardano executor.</div></div>
            <div class="panel p-4"><div class="metric-label">Limits</div><div class="mt-2">Dispositions such as FOREIGN_WRITER, EQUIVOCATED, WRONG_ROUND, and OUT_OF_RANGE are what a configuration-only profile cannot prevent but the ledger exposes. Nothing here says a reading is true, that sources are independent, that the closing height was fair, or that anything reached Cardano. This is not the oracle pipeline of ADR app-layer/012.</div></div>
          </div>
        </section>
      {/if}
    </main>
  </div>
{/if}
