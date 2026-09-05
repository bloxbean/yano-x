<script lang="ts">
  import { onMount } from 'svelte';
  import { GatewayApi, PortalApi } from '$lib/api';
  import { CONNECTION_DEFAULTS, DEFAULT_RUNTIME_CONFIG, loadRuntimeConfig, normalizeServiceUrl } from '$lib/config';
  import { sha256Hex } from '$lib/hash';
  import { failureMessage, flagText, formatBytes, formatEpochSeconds, pillFor, short } from '$lib/model';
  import {
    KNOWN_EVENT_TYPES,
    STATUS_NAMES,
    checkBundle,
    checkDisclosure,
    digitalLinkPath,
    parseDisclosure,
    parseProductInput,
    type BundleCheck,
    type DisclosureOutcome
  } from '$lib/passport';
  import CopyValue from '$lib/components/CopyValue.svelte';
  import type {
    CertificationRequestDocument,
    GatewayActor,
    PassportDocument,
    PassportView,
    ReceiptDocument,
    RuntimeConfig,
    TimelineEntry
  } from '$lib/types';

  type View = 'passport' | 'operator' | 'about';
  type OperatorForm = 'register' | 'version' | 'status' | 'claim' | 'event' | 'certify';

  const views: Array<{ id: View; code: string; label: string }> = [
    { id: 'passport', code: 'PP', label: 'Passport' },
    { id: 'operator', code: 'OP', label: 'Operator' },
    { id: 'about', code: '??', label: 'What this proves' }
  ];
  const forms: Array<{ id: OperatorForm; label: string }> = [
    { id: 'register', label: 'Register product' },
    { id: 'version', label: 'Publish version' },
    { id: 'status', label: 'Status and revocation' },
    { id: 'claim', label: 'Attach claim' },
    { id: 'event', label: 'Append event' },
    { id: 'certify', label: 'Certification round' }
  ];

  let runtimeConfig: RuntimeConfig = DEFAULT_RUNTIME_CONFIG;
  let configReady = false;
  let activeView: View = 'passport';

  // Passport
  let portalUrl = CONNECTION_DEFAULTS.serviceUrl;
  let productInput = '';
  let passportHeight = '';
  let passportBusy = false;
  let passportError = '';
  let portal: PortalApi | null = null;
  let view: PassportView | null = null;
  let bundle: PassportDocument | null = null;
  let check: BundleCheck | null = null;
  let exportUrl = '';
  let exportName = '';
  let disclosureText = '';
  let disclosureResult: { outcome: DisclosureOutcome; message: string } | null = null;
  let disclosureError = '';

  // Operator
  let gatewayUrl = CONNECTION_DEFAULTS.gatewayUrl;
  let gatewayToken = '';
  let gateway: GatewayApi | null = null;
  let gatewayChainId = '';
  let actors: GatewayActor[] = [];
  let actorId = '';
  let gatewayError = '';
  let gatewayBusy = false;
  let activeForm: OperatorForm = 'register';
  let result: ReceiptDocument | Record<string, unknown> | null = null;
  let resultError = '';
  let formBusy = false;

  let productId = 'gtin:09506000134352';
  let manufacturerOrg = '';
  let passportProfile = 'battery-passport-demo-v1';
  let registerStatus = 'DRAFT';
  let versionNumber = '1';
  let versionFile: File | null = null;
  let versionSha = '';
  let versionMediaType = 'application/json';
  let versionReference = '';
  let statusValue = 'ACTIVE';
  let successorId = '';
  let claimType = 'recycled-content';
  let claimId = 'rc-1';
  let claimText = '';
  let claimCommitted = false;
  let claimOrg = '';
  let claimValidFrom = '';
  let claimValidUntil = '';
  let eventType = 'MANUFACTURED';
  let eventOrg = '';
  let eventObservedAt = String(Math.floor(Date.now() / 1000));
  let eventLocation = '';
  let eventNote = '';
  let certificateId = 'cert-eco-1';
  let certificateType = 'EU-Ecodesign';
  let certificateOrg = '';
  let certificateEvidenceSha = '';
  let certificateRevoke = false;
  let requestText = '';
  let requestUrl = '';

  $: selectedActor = actors.find((actor) => actor.actorId === actorId) ?? null;
  $: disclosurePresent = !!bundle && bundle.answers.some((answer) => answer.collection === 'claims'
    && !!answer.entry && answer.entry.valueHex.length > 0);
  $: digitalLink = view && portal ? (digitalLinkPath(view.productId) ? `${portal.baseUrl}${digitalLinkPath(view.productId)}` : '') : '';
  $: statusClass = view ? pillFor(view.status) : 'pill';
  $: parsedRequest = parseRequest(requestText);

  onMount(() => {
    void loadRuntimeConfig().then((loaded) => {
      runtimeConfig = loaded;
      if (loaded.serviceUrl) portalUrl = loaded.serviceUrl;
      if (loaded.gatewayUrl) gatewayUrl = loaded.gatewayUrl;
      configReady = true;
      const hash = globalThis.location?.hash ?? '';
      const link = hash.match(/^#\/01\/[0-9]{8,14}(?:\/21\/[A-Za-z0-9._-]{1,20})?$/);
      const named = hash.match(/^#\/passports\/([A-Za-z0-9._:~-]{1,64})$/);
      if (link) {
        productInput = hash.slice(1);
        void openPassport();
      } else if (named) {
        productInput = named[1];
        void openPassport();
      }
    });
  });

  // ------------------------------------------------------------------ passport

  async function openPassport() {
    passportBusy = true;
    passportError = '';
    disclosureResult = null;
    disclosureError = '';
    releaseExport();
    try {
      const api = new PortalApi(normalizeServiceUrl(portalUrl, 'portal'));
      const id = parseProductInput(productInput);
      const height = passportHeight.trim() ? Number(passportHeight) : undefined;
      if (height !== undefined && (!Number.isInteger(height) || height < 1)) throw new Error('Height must be a positive integer');
      const [fetchedView, fetchedBundle] = await Promise.all([api.passport(id, height), api.proof(id, height)]);
      if (fetchedBundle.productId !== id || fetchedView.productId !== id) {
        throw new Error('The portal answered for another product');
      }
      portal = api;
      view = fetchedView;
      bundle = fetchedBundle;
      check = checkBundle(fetchedBundle);
      const json = JSON.stringify(fetchedBundle, null, 2);
      exportUrl = URL.createObjectURL(new Blob([json], { type: 'application/json' }));
      exportName = `passport-${id.replace(/[^A-Za-z0-9._-]/g, '_')}-h${fetchedBundle.height}.json`;
      const path = digitalLinkPath(id);
      if (globalThis.history) globalThis.history.replaceState(null, '', path ? `#${path}` : `#/passports/${id}`);
    } catch (cause) {
      view = null;
      bundle = null;
      check = null;
      passportError = failureMessage(cause, 'The passport could not be read');
    } finally {
      passportBusy = false;
    }
  }

  function releaseExport() {
    if (exportUrl) URL.revokeObjectURL(exportUrl);
    exportUrl = '';
    exportName = '';
  }

  async function runDisclosure() {
    disclosureError = '';
    disclosureResult = null;
    if (!bundle) return;
    try {
      disclosureResult = await checkDisclosure(bundle, parseDisclosure(disclosureText));
    } catch (cause) {
      disclosureError = failureMessage(cause, 'The disclosure could not be checked');
    }
  }

  function bindingFor(collection: string, key: string): { bound: boolean; belongs: boolean; notes: string[] } | null {
    return check?.answers.find((answer) => answer.collection === collection && answer.key === key) ?? null;
  }

  function versionKey(version: unknown): string {
    return `${view?.productId}/${version}`;
  }

  function claimKey(claim: Record<string, unknown>): string {
    return `${view?.productId}/${claim.claimType}/${claim.claimId}`;
  }

  function eventKey(event: Record<string, unknown>): string {
    return `${view?.productId}/${event.eventId}`;
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

  function flagsOf(node: Record<string, unknown>): string[] {
    return Array.isArray(node.flags) ? (node.flags as string[]) : [];
  }

  // ------------------------------------------------------------------ operator

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

  async function pickVersionFile(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    versionFile = input.files?.[0] ?? null;
    versionSha = '';
    if (versionFile) {
      versionSha = await sha256Hex(new Uint8Array(await versionFile.arrayBuffer()));
      if (versionFile.type) versionMediaType = versionFile.type;
    }
  }

  async function pickEvidenceFile(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    certificateEvidenceSha = file ? await sha256Hex(new Uint8Array(await file.arrayBuffer())) : '';
  }

  function bytesToBase64(bytes: Uint8Array): string {
    let binary = '';
    for (let index = 0; index < bytes.length; index += 0x8000) {
      binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000));
    }
    return btoa(binary);
  }

  function optionalHeight(value: string): number {
    const trimmed = value.trim();
    if (!trimmed) return 0;
    const parsed = Number(trimmed);
    if (!Number.isInteger(parsed) || parsed < 0) throw new Error('Heights are non-negative integers');
    return parsed;
  }

  function organization(field: string): string {
    return field.trim() || selectedActor?.organizationId || '';
  }

  function register() {
    return submit(() => gateway!.write('/operator/products', {
      actorId, productId: parseProductInput(productId), manufacturerOrganizationId: organization(manufacturerOrg),
      passportProfileId: passportProfile.trim(), status: registerStatus
    }));
  }

  function publishVersion() {
    return submit(async () => {
      if (!versionFile) throw new Error('Choose the passport document');
      const bytes = new Uint8Array(await versionFile.arrayBuffer());
      return gateway!.write('/operator/versions', {
        actorId, productId: parseProductInput(productId), version: Number(versionNumber),
        mediaType: versionMediaType.trim() || 'application/octet-stream', reference: versionReference.trim(),
        documentBase64: bytesToBase64(bytes)
      });
    });
  }

  function setStatus() {
    return submit(() => gateway!.write('/operator/status', {
      actorId, productId: parseProductInput(productId), status: statusValue, successorProductId: successorId.trim()
    }));
  }

  function revokeProduct() {
    return submit(() => gateway!.write('/operator/revoke', { actorId, productId: parseProductInput(productId) }));
  }

  function attachClaim() {
    return submit(() => gateway!.write('/operator/claims', {
      actorId, productId: parseProductInput(productId), claimType: claimType.trim(), claimId: claimId.trim(),
      text: claimText, committed: claimCommitted, issuerOrganizationId: organization(claimOrg),
      validFromHeight: optionalHeight(claimValidFrom), validUntilHeight: optionalHeight(claimValidUntil)
    }));
  }

  function appendEvent() {
    return submit(() => gateway!.write('/operator/events', {
      actorId, productId: parseProductInput(productId), eventType: eventType.trim(),
      actorOrganizationId: organization(eventOrg), observedAt: Number(eventObservedAt) || 0,
      location: eventLocation.trim(), note: eventNote.trim()
    }));
  }

  function propose() {
    return submit(async () => {
      const body: Record<string, unknown> = certificateRevoke
        ? { actorId, certificateId: certificateId.trim(), revoke: true }
        : {
          actorId, productId: parseProductInput(productId), certificateId: certificateId.trim(),
          certificateType: certificateType.trim(), issuerOrganizationId: organization(certificateOrg),
          evidenceSha256: certificateEvidenceSha
        };
      const proposed = await gateway!.propose(body);
      requestText = JSON.stringify(proposed.request, null, 2);
      refreshRequestUrl();
      return proposed as unknown as Record<string, unknown>;
    });
  }

  function decide(approve: boolean) {
    return submit(async () => {
      if (!parsedRequest) throw new Error('Paste a dpp-certification-request-v1 document');
      return (await gateway!.decide(approve, actorId, parsedRequest)) as unknown as Record<string, unknown>;
    });
  }

  function apply() {
    return submit(async () => {
      if (!parsedRequest) throw new Error('Paste a dpp-certification-request-v1 document');
      return gateway!.apply(parsedRequest);
    });
  }

  function parseRequest(text: string): CertificationRequestDocument | null {
    try {
      const value = JSON.parse(text) as CertificationRequestDocument;
      return value && value.type === 'dpp-certification-request-v1' && value.schemaVersion === 1 ? value : null;
    } catch {
      return null;
    }
  }

  function refreshRequestUrl() {
    if (requestUrl) URL.revokeObjectURL(requestUrl);
    requestUrl = requestText ? URL.createObjectURL(new Blob([requestText], { type: 'application/json' })) : '';
  }

  function disclosureOf(receipt: unknown): string {
    const node = receipt as { disclosure?: unknown };
    return node?.disclosure ? JSON.stringify(node.disclosure, null, 2) : '';
  }

  function resultRows(receipt: unknown): Array<{ collection: string; key: string; revision: number; status: string }> {
    const node = receipt as { results?: Array<{ collection: string; key: string; revision: number; status: string }> };
    return Array.isArray(node?.results) ? node.results : [];
  }

  function resultText(receipt: unknown): string {
    return JSON.stringify(receipt, null, 2);
  }
</script>

{#if !configReady}
  <div class="grid min-h-screen place-items-center"><span class="pill">Loading interface</span></div>
{:else}
  <header class="border-b border-white/[.07] bg-[#050b0a]/70 backdrop-blur-xl">
    <div class="shell flex min-h-[4.7rem] flex-wrap items-center justify-between gap-4 py-3">
      <div class="flex items-center gap-3">
        <div class="grid size-10 place-items-center rounded-xl border border-mint-400/30 bg-mint-400/10 font-mono text-xs font-bold text-mint-400">DP</div>
        <div><div class="text-sm font-semibold tracking-tight">Yano X <span class="text-mint-400">DPP Starter</span></div><div class="text-[.67rem] uppercase tracking-[.1em] text-[#60766f]">product passports with proofs, a prototype</div></div>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <span class="pill pill-warn">prototype, not the DPP product</span>
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
        <p class="mt-2 leading-5">A configuration-only starter on the governed authenticated map (ADR-051). It shows infrastructure, not DPP rules, and claims no conformance.</p>
      </div>
    </aside>

    <main class="min-w-0">
      {#if activeView === 'passport'}
        <section class="glass p-5">
          <div class="eyebrow">Passport</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Open a product passport</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
            Paste a GS1 Digital Link from a label, its <span class="mono">/01/…</span> path, or a product id. The
            portal answers every record with a state proof at one height; the browser checks that all of them
            name one chain, genesis, height, root, and block. No key or secret is needed here.
          </p>
          <form class="mt-5 grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)_110px_auto]" onsubmit={(event) => { event.preventDefault(); void openPassport(); }}>
            <div>
              <label class="text-xs font-semibold text-[#a9beb7]" for="portal-url">Portal URL</label>
              <input id="portal-url" class="field mono mt-2" bind:value={portalUrl} disabled={!runtimeConfig.allowServiceOverride && !!runtimeConfig.serviceUrl} />
            </div>
            <div>
              <label class="text-xs font-semibold text-[#a9beb7]" for="product-input">Product id or Digital Link</label>
              <input id="product-input" class="field mono mt-2" bind:value={productInput} placeholder="gtin:09506000134352 or https://…/01/09506000134352" />
            </div>
            <div>
              <label class="text-xs font-semibold text-[#a9beb7]" for="passport-height">Height</label>
              <input id="passport-height" class="field mono mt-2" bind:value={passportHeight} placeholder="tip" />
            </div>
            <div class="flex items-end"><button class="button-primary" type="submit" disabled={passportBusy || !productInput.trim()}>{passportBusy ? 'Opening…' : 'Open passport'}</button></div>
          </form>
          {#if passportError}<div class="notice notice-error mt-4">{passportError}</div>{/if}

          {#if view && bundle && check}
            <div class="panel mt-5 p-4">
              <div class="flex flex-wrap items-center gap-3">
                <span class={statusClass}>{view.status}</span>
                <span class="pill {check.binding === 'BOUND' ? 'pill-ok' : 'pill-bad'}">proofs {check.binding}</span>
                <span class="pill">{bundle.answers.length} records at height {bundle.height}</span>
                <span class="pill">{check.certSignatures} certificate signature(s)</span>
                {#each view.flags as flag}<span class="pill pill-warn" title={flagText(flag)}>{flag}</span>{/each}
              </div>
              <div class="mt-3 grid gap-1 text-xs text-[#b7cbc4] sm:grid-cols-2">
                <div>product <CopyValue value={view.productId} width={40} /></div>
                <div>chain <span class="mono">{view.chainId}</span></div>
                <div>state root <CopyValue value={bundle.stateRoot} width={30} /></div>
                <div>block <CopyValue value={bundle.blockHash} width={30} /></div>
                {#if view.product.manufacturerOrganizationId}
                  <div>manufacturer <span class="mono">{view.product.manufacturerOrganizationId}</span>, profile <span class="mono">{view.product.passportProfileId}</span></div>
                  <div>current version <span class="mono">{view.product.currentVersion}</span>{#if view.product.successorProductId}, successor <span class="mono">{view.product.successorProductId}</span>{/if}</div>
                {/if}
                <div class="sm:col-span-2">{provenanceText(view.product.provenance)}</div>
                {#if digitalLink}<div class="sm:col-span-2">Digital Link <CopyValue value={digitalLink} width={60} /></div>{/if}
              </div>
              {#each check.notes as note}<div class="notice notice-warn mt-3 text-xs">{note}</div>{/each}
              <div class="mt-4 flex flex-wrap gap-2">
                {#if exportUrl}<a class="button-secondary" href={exportUrl} download={exportName}>Download passport bundle</a>{/if}
                <span class="self-center text-xs text-[#789087]">Verify offline: <span class="mono">yano-dpp verify --passport {exportName || 'passport.json'} --members members.json</span></span>
              </div>
            </div>

            <div class="mt-5 grid gap-4">
              <div class="panel p-4">
                <div class="metric-label">Versions</div>
                {#if view.versions.length === 0}<div class="empty-state mt-3">No version record.</div>{/if}
                {#each view.versions as version}
                  {@const binding = bindingFor('product-versions', versionKey(version.version))}
                  <div class="mt-3 border-t border-white/[.06] pt-3 text-xs text-[#b7cbc4]">
                    <div class="flex flex-wrap items-center gap-2">
                      <span class="font-semibold text-white">v{version.version}</span>
                      {#if version.current}<span class="pill pill-ok">current</span>{/if}
                      <span class={pillFor(String(version.presence))}>{version.presence}</span>
                      {#if version.availability}<span class={pillFor(String(version.availability))}>{version.availability}</span>{/if}
                      {#if binding}<span class="pill {binding.bound && binding.belongs ? 'pill-ok' : 'pill-bad'}">proof {binding.bound && binding.belongs ? 'BOUND' : 'MISMATCH'}</span>{/if}
                      {#each flagsOf(version) as flag}<span class="pill pill-warn" title={flagText(flag)}>{flag}</span>{/each}
                    </div>
                    {#if version.documentSha256}
                      <div class="mt-2">document SHA-256 <CopyValue value={String(version.documentSha256)} width={36} />, {version.mediaType}, {formatBytes(Number(version.byteLength))}</div>
                      <div class="mt-1">reference <span class="mono break-all">{version.reference || '—'}</span>
                        {#if portal && version.availability === 'CONTENT_VERIFIED'} · <a class="text-mint-400 underline" href={portal.documentUrl(String(version.documentSha256))} target="_blank" rel="noreferrer">open served document</a>{/if}</div>
                    {/if}
                    <div class="mt-1">revision {version.revision}, created {version.createdHeight}, last mutation {version.lastMutationHeight}. {provenanceText(version.provenance)}</div>
                  </div>
                {/each}
              </div>

              <div class="panel p-4">
                <div class="metric-label">Claims</div>
                {#if view.claims.length === 0}<div class="empty-state mt-3">No claim.</div>{/if}
                {#each view.claims as claim}
                  {@const binding = bindingFor('claims', claimKey(claim))}
                  <div class="mt-3 border-t border-white/[.06] pt-3 text-xs text-[#b7cbc4]">
                    <div class="flex flex-wrap items-center gap-2">
                      <span class="font-semibold text-white">{claim.claimType}/{claim.claimId}</span>
                      <span class={pillFor(String(claim.presence))}>{claim.presence}</span>
                      {#if claim.visibility}<span class="pill">{claim.visibility}</span>{/if}
                      {#if claim.validity}<span class={pillFor(String(claim.validity))}>{claim.validity}</span>{/if}
                      {#if binding}<span class="pill {binding.bound && binding.belongs ? 'pill-ok' : 'pill-bad'}">proof {binding.bound && binding.belongs ? 'BOUND' : 'MISMATCH'}</span>{/if}
                      {#each flagsOf(claim) as flag}<span class="pill pill-warn" title={flagText(flag)}>{flag}</span>{/each}
                    </div>
                    {#if claim.text !== undefined}<div class="mt-2 text-sm text-white">“{claim.text}”</div>{/if}
                    {#if claim.commitment}<div class="mt-2">commitment <CopyValue value={String(claim.commitment)} width={40} /> (value withheld; check a disclosure below)</div>{/if}
                    <div class="mt-1">issuer <span class="mono">{claim.issuerOrganizationId ?? '—'}</span>, valid heights <span class="mono">{claim.validFromHeight ?? 0} – {claim.validUntilHeight || '∞'}</span></div>
                    <div class="mt-1">{provenanceText(claim.provenance)}</div>
                  </div>
                {/each}
                {#if disclosurePresent}
                  <div class="mt-4 border-t border-white/[.06] pt-4">
                    <label class="text-xs font-semibold text-[#a9beb7]" for="disclosure-text">Check a disclosure (dpp-disclosure-v1 from the issuer)</label>
                    <textarea id="disclosure-text" class="field mono mt-2 min-h-24" bind:value={disclosureText} placeholder={'{"schemaVersion":1,"type":"dpp-disclosure-v1", …}'}></textarea>
                    <div class="mt-2 flex flex-wrap items-center gap-3">
                      <button class="button-secondary" type="button" disabled={!disclosureText.trim()} onclick={() => void runDisclosure()}>Check disclosure</button>
                      {#if disclosureResult}<span class={pillFor(disclosureResult.outcome)}>{disclosureResult.outcome}</span><span class="text-xs text-[#b7cbc4]">{disclosureResult.message}</span>{/if}
                    </div>
                    {#if disclosureError}<div class="notice notice-error mt-3 text-xs">{disclosureError}</div>{/if}
                  </div>
                {/if}
              </div>

              <div class="panel p-4">
                <div class="metric-label">Lifecycle events (ledger order)</div>
                {#if view.events.length === 0}<div class="empty-state mt-3">No event.</div>{/if}
                {#each view.events as event}
                  {@const binding = bindingFor('events', eventKey(event))}
                  <div class="mt-3 border-t border-white/[.06] pt-3 text-xs text-[#b7cbc4]">
                    <div class="flex flex-wrap items-center gap-2">
                      <span class="font-semibold text-white">{event.eventType ?? '?'}</span>
                      <span class="pill">height {event.lastMutationHeight}</span>
                      <span class={pillFor(String(event.presence))}>{event.presence}</span>
                      {#if binding}<span class="pill {binding.bound && binding.belongs ? 'pill-ok' : 'pill-bad'}">proof {binding.bound && binding.belongs ? 'BOUND' : 'MISMATCH'}</span>{/if}
                      {#each flagsOf(event) as flag}<span class="pill pill-warn" title={flagText(flag)}>{flag}</span>{/each}
                    </div>
                    <div class="mt-2">by <span class="mono">{event.actorOrganizationId ?? '—'}</span>, observed {formatEpochSeconds(Number(event.observedAt))} (signed business time), {event.location || 'no location'}{#if event.note}, “{event.note}”{/if}</div>
                    <div class="mt-1">{provenanceText(event.provenance)}</div>
                  </div>
                {/each}
              </div>

              <div class="panel p-4">
                <div class="metric-label">Certificates</div>
                {#if view.certificates.length === 0}<div class="empty-state mt-3">No certificate.</div>{/if}
                {#each view.certificates as certificate}
                  {@const binding = bindingFor('certificates', String(certificate.certificateId))}
                  <div class="mt-3 border-t border-white/[.06] pt-3 text-xs text-[#b7cbc4]">
                    <div class="flex flex-wrap items-center gap-2">
                      <span class="font-semibold text-white">{certificate.certificateId}</span>
                      <span class={pillFor(String(certificate.presence))}>{certificate.presence}</span>
                      {#if certificate.validity}<span class={pillFor(String(certificate.validity))}>{certificate.validity}</span>{/if}
                      <span class="pill {certificate.approvalConsumption ? 'pill-ok' : 'pill-warn'}">approval consumption {certificate.approvalConsumption ? 'proven' : 'absent'}</span>
                      {#if binding}<span class="pill {binding.bound && binding.belongs ? 'pill-ok' : 'pill-bad'}">proof {binding.bound && binding.belongs ? 'BOUND' : 'MISMATCH'}</span>{/if}
                      {#each flagsOf(certificate) as flag}<span class="pill pill-warn" title={flagText(flag)}>{flag}</span>{/each}
                    </div>
                    {#if certificate.certificateType}<div class="mt-2">{certificate.certificateType} by <span class="mono">{certificate.issuerOrganizationId}</span>, valid heights <span class="mono">{certificate.validFromHeight} – {certificate.validUntilHeight || '∞'}</span>, evidence <CopyValue value={String(certificate.evidenceSha256)} width={30} /></div>{/if}
                    <div class="mt-1">{provenanceText(certificate.provenance)}</div>
                  </div>
                {/each}
              </div>

              <div class="panel p-4">
                <div class="metric-label">Proof rows</div>
                <div class="table-wrap mt-3">
                  <table class="data-table">
                    <thead><tr><th>Record</th><th>Presence</th><th>Provenance</th><th>Facts</th><th>Binding</th></tr></thead>
                    <tbody>
                      {#each check.answers as answer}
                        <tr>
                          <td class="mono">{answer.label}</td>
                          <td><span class={pillFor(answer.presence)}>{answer.presence}</span></td>
                          <td class="mono">{answer.provenance}</td>
                          <td>{answer.facts}</td>
                          <td><span class="pill {answer.bound && answer.belongs ? 'pill-ok' : 'pill-bad'}">{answer.bound && answer.belongs ? 'BOUND' : 'MISMATCH'}</span>{#each answer.notes as note}<div class="mt-1 text-[.68rem] text-[#f8c97d]">{note}</div>{/each}</td>
                        </tr>
                      {/each}
                    </tbody>
                  </table>
                </div>
                <div class="mt-3 text-xs text-[#789087]">Timeline: {(view.timeline as TimelineEntry[]).length} applied mutation(s) in ledger order. MPF paths and the finality certificate are verified by <span class="mono">yano-dpp verify</span> on the export.</div>
              </div>
            </div>
          {/if}
        </section>

      {:else if activeView === 'operator'}
        <section class="glass p-5">
          <div class="eyebrow">Operator</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Write through the operator gateway</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
            The gateway runs on the operator's machine with the actor seeds; this console sends it what to
            sign and shows the receipt. The token is the only secret it holds, in memory.
          </p>
          {#if !gateway}
            <form class="mt-5 grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto]" onsubmit={(event) => { event.preventDefault(); void connectGateway(); }}>
              <div><label class="text-xs font-semibold text-[#a9beb7]" for="gateway-url">Gateway URL</label><input id="gateway-url" class="field mono mt-2" bind:value={gatewayUrl} /></div>
              <div><label class="text-xs font-semibold text-[#a9beb7]" for="gateway-token">Gateway token</label><input id="gateway-token" class="field mono mt-2" type="password" autocomplete="off" bind:value={gatewayToken} placeholder="printed by yano-dpp gateway" /></div>
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
                <div class="sm:col-span-2"><label class="text-xs font-semibold text-[#a9beb7]" for="op-product">Product id</label><input id="op-product" class="field mono mt-2" bind:value={productId} /></div>
                {#if activeForm === 'register'}
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-manufacturer">Manufacturer organization</label><input id="op-manufacturer" class="field mono mt-2" bind:value={manufacturerOrg} placeholder={selectedActor?.organizationId ?? ''} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-profile">Passport profile</label><input id="op-profile" class="field mono mt-2" bind:value={passportProfile} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-register-status">Initial status</label><select id="op-register-status" class="field mt-2" bind:value={registerStatus}>{#each STATUS_NAMES as name}<option value={name}>{name}</option>{/each}</select></div>
                  <div class="flex items-end"><button class="button-primary" type="button" disabled={formBusy} onclick={() => void register()}>Register (manufacturer)</button></div>
                {:else if activeForm === 'version'}
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-version">Version</label><input id="op-version" class="field mono mt-2" bind:value={versionNumber} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-document">Passport document</label><input id="op-document" class="field mt-2" type="file" onchange={(event) => void pickVersionFile(event)} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-media">Media type</label><input id="op-media" class="field mono mt-2" bind:value={versionMediaType} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-reference">Reference</label><input id="op-reference" class="field mono mt-2" bind:value={versionReference} placeholder="https://…/passport.json" /></div>
                  {#if versionSha}<div class="text-xs text-[#b7cbc4] sm:col-span-2">document SHA-256 <CopyValue value={versionSha} width={48} /> ({formatBytes(versionFile?.size ?? 0)}); this digest is committed on chain</div>{/if}
                  <div class="flex items-end"><button class="button-primary" type="button" disabled={formBusy || !versionFile} onclick={() => void publishVersion()}>Publish version (manufacturer)</button></div>
                {:else if activeForm === 'status'}
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-status">Status</label><select id="op-status" class="field mt-2" bind:value={statusValue}>{#each STATUS_NAMES.slice(1) as name}<option value={name}>{name}</option>{/each}</select></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-successor">Successor product (for REPLACED)</label><input id="op-successor" class="field mono mt-2" bind:value={successorId} /></div>
                  <div class="flex flex-wrap items-end gap-2 sm:col-span-2">
                    <button class="button-primary" type="button" disabled={formBusy} onclick={() => void setStatus()}>Set status</button>
                    <button class="button-danger" type="button" disabled={formBusy} onclick={() => void revokeProduct()}>Revoke passport (tombstone)</button>
                  </div>
                {:else if activeForm === 'claim'}
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-claim-type">Claim type</label><input id="op-claim-type" class="field mono mt-2" bind:value={claimType} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-claim-id">Claim id</label><input id="op-claim-id" class="field mono mt-2" bind:value={claimId} /></div>
                  <div class="sm:col-span-2"><label class="text-xs font-semibold text-[#a9beb7]" for="op-claim-text">Claim text</label><input id="op-claim-text" class="field mt-2" bind:value={claimText} placeholder="42 %" /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-claim-org">Issuer organization</label><input id="op-claim-org" class="field mono mt-2" bind:value={claimOrg} placeholder={selectedActor?.organizationId ?? ''} /></div>
                  <div class="flex items-end gap-2"><input id="op-claim-committed" type="checkbox" bind:checked={claimCommitted} /><label class="text-xs text-[#a9beb7]" for="op-claim-committed">Committed (only a salted commitment goes on chain; the disclosure is returned once)</label></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-claim-from">Valid from height</label><input id="op-claim-from" class="field mono mt-2" bind:value={claimValidFrom} placeholder="0" /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-claim-until">Valid until height</label><input id="op-claim-until" class="field mono mt-2" bind:value={claimValidUntil} placeholder="0 = open" /></div>
                  <div class="flex items-end"><button class="button-primary" type="button" disabled={formBusy || !claimText.trim()} onclick={() => void attachClaim()}>Attach claim (claim issuer)</button></div>
                {:else if activeForm === 'event'}
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-event-type">Event type</label><input id="op-event-type" class="field mono mt-2" bind:value={eventType} list="event-types" /><datalist id="event-types">{#each KNOWN_EVENT_TYPES as type}<option value={type}></option>{/each}</datalist></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-event-org">Actor organization</label><input id="op-event-org" class="field mono mt-2" bind:value={eventOrg} placeholder={selectedActor?.organizationId ?? ''} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-event-at">Observed at (epoch seconds)</label><input id="op-event-at" class="field mono mt-2" bind:value={eventObservedAt} /></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-event-location">Location</label><input id="op-event-location" class="field mt-2" bind:value={eventLocation} /></div>
                  <div class="sm:col-span-2"><label class="text-xs font-semibold text-[#a9beb7]" for="op-event-note">Note</label><input id="op-event-note" class="field mt-2" bind:value={eventNote} /></div>
                  <div class="flex items-end"><button class="button-primary" type="button" disabled={formBusy} onclick={() => void appendEvent()}>Append event (operator)</button></div>
                {:else}
                  <div class="flex items-center gap-2 sm:col-span-2"><input id="op-cert-revoke" type="checkbox" bind:checked={certificateRevoke} /><label class="text-xs text-[#a9beb7]" for="op-cert-revoke">Propose a revocation instead of a new certificate</label></div>
                  <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-cert-id">Certificate id</label><input id="op-cert-id" class="field mono mt-2" bind:value={certificateId} /></div>
                  {#if !certificateRevoke}
                    <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-cert-type">Certificate type</label><input id="op-cert-type" class="field mono mt-2" bind:value={certificateType} /></div>
                    <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-cert-org">Issuing organization</label><input id="op-cert-org" class="field mono mt-2" bind:value={certificateOrg} placeholder={selectedActor?.organizationId ?? ''} /></div>
                    <div><label class="text-xs font-semibold text-[#a9beb7]" for="op-cert-evidence">Evidence document (hashed in the browser)</label><input id="op-cert-evidence" class="field mt-2" type="file" onchange={(event) => void pickEvidenceFile(event)} /></div>
                    {#if certificateEvidenceSha}<div class="text-xs text-[#b7cbc4] sm:col-span-2">evidence SHA-256 <CopyValue value={certificateEvidenceSha} width={48} /></div>{/if}
                  {/if}
                  <div class="flex items-end"><button class="button-primary" type="button" disabled={formBusy || (!certificateRevoke && !certificateEvidenceSha)} onclick={() => void propose()}>1. Propose (certifier)</button></div>
                  <div class="sm:col-span-2"><label class="text-xs font-semibold text-[#a9beb7]" for="op-request">Certification request (travels between certifier, auditors, and whoever applies)</label><textarea id="op-request" class="field mono mt-2 min-h-28" bind:value={requestText} oninput={refreshRequestUrl}></textarea></div>
                  <div class="flex flex-wrap items-end gap-2 sm:col-span-2">
                    <button class="button-secondary" type="button" disabled={formBusy || !parsedRequest} onclick={() => void decide(true)}>2. Approve (auditor)</button>
                    <button class="button-danger" type="button" disabled={formBusy || !parsedRequest} onclick={() => void decide(false)}>Reject (auditor)</button>
                    <button class="button-primary" type="button" disabled={formBusy || !parsedRequest} onclick={() => void apply()}>3. Apply</button>
                    {#if requestUrl}<a class="button-quiet" href={requestUrl} download={`certification-request-${parsedRequest?.proposalId ?? 'draft'}.json`}>Download request</a>{/if}
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
                  {#if 'decision' in result}<span class="pill pill-ok">{result.decision}</span>{/if}
                  {#if 'messageId' in result}<span class="text-xs text-[#b7cbc4]">message <CopyValue value={String(result.messageId)} width={24} /></span>{/if}
                </div>
                {#each resultRows(result) as row}
                  <div class="mt-2 text-xs text-[#b7cbc4]"><span class="mono">{row.collection}/{row.key}</span> revision {row.revision} {row.status}</div>
                {/each}
                {#if 'documentSha256' in result}<div class="mt-2 text-xs text-[#b7cbc4]">document stored as <CopyValue value={String(result.documentSha256)} width={48} /></div>{/if}
                {#if disclosureOf(result)}
                  <div class="notice notice-warn mt-3 text-xs">Disclosure returned once; keep it with the issuer and hand it to verifiers out of band.</div>
                  <pre class="mono mt-2 max-h-64 overflow-auto text-[.68rem] text-[#b7cbc4]">{disclosureOf(result)}</pre>
                {/if}
                <details class="mt-3 text-xs text-[#789087]"><summary>Raw response</summary><pre class="mono mt-2 max-h-72 overflow-auto text-[.68rem]">{resultText(result)}</pre></details>
              </div>
            {/if}
          {/if}
        </section>

      {:else}
        <section class="glass p-5">
          <div class="eyebrow">Proof story</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">What a passport proves</h1>
          <div class="mt-4 grid gap-3 text-sm leading-6 text-[#b7cbc4] lg:grid-cols-2">
            <div class="panel p-4"><div class="metric-label">Records</div><div class="mt-2">Each record is a state proof at one height: ACTIVE with this revision and value, REVOKED as a tombstone, or ABSENT by exclusion. The passport is a snapshot: every record names the same chain, genesis, height, root, and certified block.</div></div>
            <div class="panel p-4"><div class="metric-label">Who wrote it</div><div class="mt-2">DIRECT_ROLE rows bind the receipt, the actor's one-use authorization, and the actor, organization, and policy records. A certificate is applied under the approval-gated collection with its proposal's one-use consumption proven; the approvals themselves are Evidence Desk's to show.</div></div>
            <div class="panel p-4"><div class="metric-label">Flags</div><div class="mt-2">REWRITTEN, DANGLING, and FOREIGN_WRITER are what a configuration-only profile cannot prevent but the ledger exposes: any manufacturer can rewrite any product. The full DPP provider (ADR-026) enforces the rules on chain.</div></div>
            <div class="panel p-4"><div class="metric-label">Trust</div><div class="mt-2">The browser checks binding only. <span class="mono">yano-dpp verify</span> with a members file or an anchor datum reaches CALLER_PINNED_ROOT or INDEPENDENTLY_VERIFIED_L1_ANCHOR. Nothing here says a physical event occurred, a measurement is true, a document is still available, or any DPP standard is met.</div></div>
          </div>
        </section>
      {/if}
    </main>
  </div>
{/if}
