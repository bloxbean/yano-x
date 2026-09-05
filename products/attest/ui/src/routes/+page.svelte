<script lang="ts">
  import { onMount } from 'svelte';
  import { YanoApi } from '$lib/api';
  import { toHex } from '$lib/cbor';
  import { assembleCertificate, confirmWithNode, parseCertificate, verifyLocally, type NodeConfirmation } from '$lib/certificate';
  import { DEFAULT_RUNTIME_CONFIG, loadRuntimeConfig } from '$lib/config';
  import {
    DOC_TRAIL_TOPIC,
    decodeTrailHead,
    derivedEntityId,
    encodeAppendCommand,
    entityKeyHex,
    extractBlockStateRootHex,
    extractEnvelope,
    validateEntityId
  } from '$lib/envelope';
  import { sha256Hex } from '$lib/hash';
  import { failureMessage, formatBytes, formatTime, short, withLiveStatus } from '$lib/model';
  import ConnectionPanel from '$lib/components/ConnectionPanel.svelte';
  import CopyValue from '$lib/components/CopyValue.svelte';
  import type {
    ActiveConnection,
    AttestCertificate,
    DiscoveredChain,
    FinalizedMessage,
    LocalVerification,
    NodeConfig,
    NodeStatus,
    RuntimeConfig
  } from '$lib/types';

  type View = 'attest' | 'verify' | 'trail' | 'about';
  type AttestStep = 'idle' | 'submitting' | 'finalizing' | 'collecting' | 'done';

  const FINALITY_TIMEOUT_MS = 90_000;
  const FINALITY_POLL_MS = 1_000;
  const MAX_DOCUMENT_BYTES = 256 * 1024 * 1024;

  const views: Array<{ id: View; code: string; label: string }> = [
    { id: 'attest', code: 'AT', label: 'Attest a document' },
    { id: 'verify', code: 'VF', label: 'Verify a certificate' },
    { id: 'trail', code: 'TR', label: 'Look up a trail' },
    { id: 'about', code: '??', label: 'What this proves' }
  ];

  let runtimeConfig: RuntimeConfig = DEFAULT_RUNTIME_CONFIG;
  let configReady = false;
  let connecting = false;
  let connectionError = '';
  let connection: ActiveConnection | null = null;
  let offline = false;
  let api: YanoApi | null = null;
  let nodeConfig: NodeConfig | null = null;
  let nodeStatus: NodeStatus | null = null;
  let chains: DiscoveredChain[] = [];
  let selectedChainId = '';
  let activeView: View = 'attest';

  // Attest view
  let selectedFile: File | null = null;
  let documentBytes: Uint8Array | null = null;
  let digestHex = '';
  let digestBusy = false;
  let entityInput = '';
  let referenceInput = '';
  let labelInput = '';
  let attestBusy = false;
  let attestStep: AttestStep = 'idle';
  let attestError = '';
  let attestMessageId = '';
  let issued: AttestCertificate | null = null;
  let issuedJson = '';
  let issuedUrl = '';

  // Verify view
  let verifyCertificate: AttestCertificate | null = null;
  let verifyCertificateName = '';
  let verifyDocumentName = '';
  let verifyDocumentBytes: Uint8Array | null = null;
  let verifyBusy = false;
  let verifyError = '';
  let verification: LocalVerification | null = null;
  let nodeConfirmation: NodeConfirmation | null = null;

  // Trail view
  let trailEntity = '';
  let trailBusy = false;
  let trailError = '';
  let trail: { present: boolean; revision: number; headDigestHex: string; committedHeight: number; stateRoot: string } | null = null;

  $: selectedChain = chains.find((chain) => chain.summary.chainId === selectedChainId) ?? null;

  onMount(() => {
    void loadRuntimeConfig().then((loaded) => {
      runtimeConfig = loaded;
      configReady = true;
    });
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
        throw new Error('The node is reachable, but it hosts no app chain running the doc-trail state machine');
      }
      connection = next;
      offline = false;
      api = candidate;
      nodeConfig = config;
      nodeStatus = status;
      chains = discovered;
      selectedChainId = runtimeConfig.defaultChainId
        && discovered.some((chain) => chain.summary.chainId === runtimeConfig.defaultChainId)
        ? runtimeConfig.defaultChainId
        : discovered[0].summary.chainId;
    } catch (cause) {
      connectionError = failureMessage(cause, 'Could not verify this Yano installation');
    } finally {
      connecting = false;
    }
  }

  function verifyOffline() {
    offline = true;
    activeView = 'verify';
  }

  function disconnectNode() {
    connection = null;
    offline = false;
    api = null;
    nodeConfig = null;
    nodeStatus = null;
    chains = [];
    selectedChainId = '';
    resetAttest();
    trail = null;
  }

  async function refreshChain() {
    if (!api || !selectedChainId) return;
    try {
      const live = await api.chainStatus(selectedChainId);
      chains = chains.map((chain) => chain.summary.chainId === selectedChainId ? withLiveStatus(chain, live) : chain);
    } catch (cause) {
      attestError = failureMessage(cause, 'The chain status could not be refreshed');
    }
  }

  // ------------------------------------------------------------------ attest

  async function chooseDocument(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    resetAttest();
    if (!file) return;
    if (file.size > MAX_DOCUMENT_BYTES) {
      attestError = 'Files above 256 MiB are hashed with the CLI instead';
      return;
    }
    digestBusy = true;
    try {
      const bytes = new Uint8Array(await file.arrayBuffer());
      digestHex = await sha256Hex(bytes);
      documentBytes = bytes;
      selectedFile = file;
    } catch (cause) {
      attestError = failureMessage(cause, 'The file could not be hashed');
    } finally {
      digestBusy = false;
    }
  }

  function resetAttest() {
    selectedFile = null;
    documentBytes = null;
    digestHex = '';
    attestStep = 'idle';
    attestError = '';
    attestMessageId = '';
    issued = null;
    issuedJson = '';
    if (issuedUrl) URL.revokeObjectURL(issuedUrl);
    issuedUrl = '';
  }

  async function recordDocument() {
    if (!api || !selectedChainId || !selectedFile || !digestHex) return;
    const chainId = selectedChainId;
    attestBusy = true;
    attestError = '';
    try {
      const entityId = entityInput.trim() || derivedEntityId(digestHex);
      validateEntityId(entityId);
      const command = encodeAppendCommand(entityId, digestHex, referenceInput.trim());
      attestStep = 'submitting';
      const submitted = await api.submitMessage(chainId, DOC_TRAIL_TOPIC, toHex(command));
      attestMessageId = submitted.messageId;
      attestStep = 'finalizing';
      const finalized = await awaitFinalized(chainId, submitted.messageId);
      attestStep = 'collecting';
      const [proof, evidence, status] = await Promise.all([
        api.messageProof(chainId, submitted.messageId),
        api.evidence(chainId, submitted.messageId),
        api.chainStatus(chainId)
      ]);
      if (evidence.chainId !== chainId || evidence.messageId !== submitted.messageId
          || proof.blockHeight !== finalized.height || proof.messageIndex !== finalized.index) {
        throw new Error('The node documents disagree about the message position');
      }
      const envelope = extractEnvelope(evidence.blocksCbor[0], finalized.index);
      if (envelope.messageIdHex !== submitted.messageId) {
        throw new Error('The evidence block envelope does not carry the submitted message');
      }
      const stateProof = await api.stateProof(chainId, entityKeyHex(entityId), finalized.height);
      const trailHead = stateProof?.presence === 'PRESENT' && stateProof.valueHex
        ? decodeTrailHead(stateProof.valueHex) : null;
      let anchorMode: string | null = null;
      let lastRoot: string | null = null;
      if (evidence.anchor) {
        const commitment = await api.anchorCommitment(chainId);
        anchorMode = commitment?.mode ?? status.anchor?.mode ?? 'unknown';
        lastRoot = extractBlockStateRootHex(evidence.blocksCbor[evidence.blocksCbor.length - 1]);
      }
      issued = assembleCertificate({
        chainId,
        applicationId: status.capabilityManifest?.applicationId ?? null,
        finalized,
        envelope,
        messageProof: proof,
        evidence,
        stateProof: trailHead ? stateProof : null,
        trailHead,
        anchorMode,
        lastBlockStateRootHex: lastRoot,
        metadata: {
          fileName: selectedFile.name,
          sizeBytes: selectedFile.size,
          mediaType: selectedFile.type || undefined,
          label: labelInput.trim() || undefined
        }
      });
      issuedJson = JSON.stringify(issued, null, 2);
      if (issuedUrl) URL.revokeObjectURL(issuedUrl);
      issuedUrl = URL.createObjectURL(new Blob([issuedJson], { type: 'application/json' }));
      attestStep = 'done';
      await refreshChain();
    } catch (cause) {
      attestError = failureMessage(cause, 'The document could not be attested');
      if (attestStep !== 'done') attestStep = 'idle';
    } finally {
      attestBusy = false;
    }
  }

  async function awaitFinalized(chainId: string, messageId: string): Promise<FinalizedMessage> {
    const deadline = Date.now() + FINALITY_TIMEOUT_MS;
    while (Date.now() < deadline) {
      const finalized = await api!.finalizedMessage(chainId, messageId);
      if (finalized && finalized.height > 0) return finalized;
      await new Promise((resolve) => setTimeout(resolve, FINALITY_POLL_MS));
    }
    throw new Error(`Message ${messageId} was not finalized within ${FINALITY_TIMEOUT_MS / 1000} seconds`);
  }

  function verifyIssued() {
    if (!issued) return;
    verifyCertificate = issued;
    verifyCertificateName = `${selectedFile?.name ?? 'document'}.attest.json`;
    verifyDocumentBytes = documentBytes;
    verifyDocumentName = selectedFile?.name ?? '';
    verification = null;
    nodeConfirmation = null;
    activeView = 'verify';
    void runVerification();
  }

  // ------------------------------------------------------------------ verify

  async function chooseCertificate(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    verification = null;
    nodeConfirmation = null;
    verifyError = '';
    verifyCertificate = null;
    verifyCertificateName = '';
    if (!file) return;
    try {
      verifyCertificate = parseCertificate(await file.text());
      verifyCertificateName = file.name;
    } catch (cause) {
      verifyError = failureMessage(cause, 'The certificate could not be read');
    }
  }

  async function chooseVerifyDocument(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    verification = null;
    verifyDocumentBytes = null;
    verifyDocumentName = '';
    if (!file) return;
    if (file.size > MAX_DOCUMENT_BYTES) {
      verifyError = 'Files above 256 MiB are verified with the CLI instead';
      return;
    }
    verifyDocumentBytes = new Uint8Array(await file.arrayBuffer());
    verifyDocumentName = file.name;
  }

  async function runVerification() {
    if (!verifyCertificate) return;
    verifyBusy = true;
    verifyError = '';
    try {
      verification = await verifyLocally(verifyCertificate, verifyDocumentBytes);
      nodeConfirmation = api && selectedChainId === verifyCertificate.chainId
        ? await confirmWithNode(api, verifyCertificate)
        : null;
    } catch (cause) {
      verifyError = failureMessage(cause, 'The certificate could not be verified');
    } finally {
      verifyBusy = false;
    }
  }

  // ------------------------------------------------------------------ trail

  async function lookupTrail() {
    if (!api || !selectedChainId) return;
    trailBusy = true;
    trailError = '';
    trail = null;
    try {
      const entityId = trailEntity.trim();
      validateEntityId(entityId);
      const proof = await api.stateProof(selectedChainId, entityKeyHex(entityId));
      if (!proof) {
        throw new Error('The node retains no state proof for this entity');
      }
      const head = proof.presence === 'PRESENT' && proof.valueHex ? decodeTrailHead(proof.valueHex) : null;
      trail = {
        present: head !== null,
        revision: head?.count ?? 0,
        headDigestHex: head?.headHashHex ?? '',
        committedHeight: proof.committedHeight,
        stateRoot: proof.stateRoot
      };
    } catch (cause) {
      trailError = failureMessage(cause, 'The trail could not be read');
    } finally {
      trailBusy = false;
    }
  }

  function pillClass(state: string): string {
    return state === 'PASS' ? 'pill-ok' : state === 'FAIL' ? 'pill-bad' : 'pill-warn';
  }

  function stepLabel(step: AttestStep): string {
    switch (step) {
      case 'submitting': return 'Submitting the doc-trail append…';
      case 'finalizing': return 'Waiting for threshold finality…';
      case 'collecting': return 'Collecting proof, evidence, and trail head…';
      case 'done': return 'Certificate ready';
      default: return '';
    }
  }
</script>

{#if !configReady}
  <div class="grid min-h-screen place-items-center"><span class="pill">Loading interface</span></div>
{:else if !connection && !offline}
  <main class="shell">
    <ConnectionPanel config={runtimeConfig} busy={connecting} error={connectionError} onconnect={openConnection} onverifyoffline={verifyOffline} />
  </main>
{:else}
  <header class="border-b border-white/[.07] bg-[#050b0a]/70 backdrop-blur-xl">
    <div class="shell flex min-h-[4.7rem] flex-wrap items-center justify-between gap-4 py-3">
      <div class="flex items-center gap-3">
        <div class="grid size-10 place-items-center rounded-xl border border-mint-400/30 bg-mint-400/10 font-mono text-xs font-bold text-mint-400">AT</div>
        <div><div class="text-sm font-semibold tracking-tight">Yano X <span class="text-mint-400">Attest</span></div><div class="text-[.67rem] uppercase tracking-[.13em] text-[#71887f]">Document attestation and certificates</div></div>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        {#if connection}
          <span class="pill pill-ok">{nodeConfig?.network || 'connected'}</span>
          <span class="pill {nodeStatus?.runtimeDegraded ? 'pill-warn' : 'pill-ok'}">{nodeStatus?.runtimeDegraded ? 'degraded' : 'node online'}</span>
          <button class="button-quiet min-h-0 py-2" type="button" onclick={disconnectNode}>Change node</button>
        {:else}
          <span class="pill pill-warn">offline verification</span>
          <button class="button-quiet min-h-0 py-2" type="button" onclick={disconnectNode}>Connect a node</button>
        {/if}
      </div>
    </div>
  </header>

  <div class="shell grid gap-5 py-5 lg:grid-cols-[230px_minmax(0,1fr)]">
    <aside class="glass h-fit p-3 lg:sticky lg:top-5">
      <div class="border-b border-white/[.07] px-2 pb-4 pt-2">
        <div class="eyebrow">Connected node</div>
        {#if connection}
          <div class="mt-2 truncate text-xs text-[#b7cbc4]" title={connection.nodeUrl}>{connection.nodeUrl}</div>
          <div class="mt-1 font-mono text-[.65rem] text-[#60766f]">Yano {nodeConfig?.version || 'unknown'}</div>
        {:else}
          <div class="mt-2 text-xs text-[#789087]">None. Local checks only.</div>
        {/if}
      </div>
      <nav class="mt-3 flex gap-1 overflow-auto lg:block" aria-label="Attest sections">
        {#each views as view}
          <button class="nav-item shrink-0 {activeView === view.id ? 'active' : ''}" type="button" disabled={!connection && (view.id === 'attest' || view.id === 'trail')} onclick={() => (activeView = view.id)}>
            <span class="nav-icon">{view.code}</span><span>{view.label}</span>
          </button>
        {/each}
      </nav>
      {#if connection}
        <div class="mt-4 border-t border-white/[.07] px-2 pt-4">
          <label class="text-[.65rem] uppercase tracking-[.1em] text-[#60766f]" for="chain-picker">Doc-trail chain</label>
          <select id="chain-picker" class="field mt-2" value={selectedChainId} onchange={(event) => { selectedChainId = event.currentTarget.value; resetAttest(); trail = null; }}>
            {#each chains as chain}<option value={chain.summary.chainId}>{chain.summary.chainId}</option>{/each}
          </select>
          <div class="mt-3 text-xs text-[#789087]">Height {selectedChain?.summary.tipHeight?.toLocaleString() ?? '—'}</div>
          <div class="mt-1 text-xs text-[#789087]">{selectedChain?.status.members ?? '—'} members, threshold {selectedChain?.status.threshold ?? '—'}</div>
          <div class="mt-1 text-xs text-[#789087]">Anchoring {selectedChain?.status.anchor ? (selectedChain.status.anchor.mode ?? 'configured') : 'not configured'}</div>
        </div>
      {/if}
    </aside>

    <main class="min-w-0">
      {#if activeView === 'attest'}
        <section class="glass p-5">
          <div class="eyebrow">Attest</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Record a document digest on {selectedChainId}</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
            The file is hashed here with SHA-256. Only the digest, the series id, and the reference are
            sent to the node as a canonical doc-trail append. Once the chain finalizes it, the UI collects
            the message proof, the evidence bundle, and the trail head into a certificate you can download.
          </p>

          <div class="mt-5 grid gap-4 lg:grid-cols-[1fr_1fr]">
            <div class="panel p-4">
              <label class="text-xs font-semibold text-[#a9beb7]" for="attest-file">Document</label>
              <input id="attest-file" class="field mt-2" type="file" disabled={attestBusy} onchange={chooseDocument} />
              {#if digestBusy}<div class="mt-3 text-xs text-[#789087]">Hashing…</div>{/if}
              {#if selectedFile && digestHex}
                <div class="mt-3 text-xs text-[#b7cbc4]">{selectedFile.name} · {formatBytes(selectedFile.size)}{selectedFile.type ? ` · ${selectedFile.type}` : ''}</div>
                <div class="mt-2"><div class="metric-label">SHA-256</div><div class="mt-1"><CopyValue value={digestHex} width={40} label="digest" /></div></div>
              {/if}
            </div>
            <div class="panel space-y-3 p-4">
              <div>
                <label class="text-xs font-semibold text-[#a9beb7]" for="attest-entity">Series id <span class="muted">optional</span></label>
                <input id="attest-entity" class="field mono mt-2" bind:value={entityInput} disabled={attestBusy} placeholder={digestHex ? derivedEntityId(digestHex) : 'sha256:<digest> when left empty'} />
                <p class="mb-0 mt-1 text-[.72rem] text-[#71887f]">Reuse a series id to chain revisions of the same document.</p>
              </div>
              <div>
                <label class="text-xs font-semibold text-[#a9beb7]" for="attest-reference">Reference <span class="muted">optional, bound on chain</span></label>
                <input id="attest-reference" class="field mt-2" bind:value={referenceInput} disabled={attestBusy} placeholder="invoice-2026-09, ticket URL, author…" maxlength="1024" />
              </div>
              <div>
                <label class="text-xs font-semibold text-[#a9beb7]" for="attest-label">Label <span class="muted">optional, certificate only</span></label>
                <input id="attest-label" class="field mt-2" bind:value={labelInput} disabled={attestBusy} placeholder="Q3 report, signed contract…" maxlength="256" />
              </div>
            </div>
          </div>

          {#if attestError}<div class="notice notice-error mt-4">{attestError}</div>{/if}

          <div class="mt-5 flex flex-wrap items-center gap-3">
            <button class="button-primary" type="button" disabled={attestBusy || !digestHex || attestStep === 'done'} onclick={() => void recordDocument()}>
              {attestBusy ? stepLabel(attestStep) : 'Record on chain'}
            </button>
            {#if attestStep !== 'idle'}<span class="pill {attestStep === 'done' ? 'pill-ok' : 'pill-warn'}">{stepLabel(attestStep)}</span>{/if}
            {#if attestMessageId}<span class="mono text-[.7rem] text-[#71887f]">message {short(attestMessageId, 22)}</span>{/if}
          </div>
        </section>

        {#if issued}
          <section class="glass mt-4 p-5">
            <div class="flex flex-wrap items-start justify-between gap-4">
              <div>
                <div class="eyebrow">Certificate</div>
                <h2 class="mb-0 mt-2 text-lg font-semibold">{issued.status === 'ANCHORED' ? 'Anchored' : 'Finalized'} on {issued.chainId}</h2>
              </div>
              <div class="flex flex-wrap gap-2">
                <a class="button-primary" href={issuedUrl} download={`${selectedFile?.name ?? 'document'}.attest.json`}>Download certificate</a>
                <button class="button-secondary" type="button" onclick={verifyIssued}>Verify it now</button>
              </div>
            </div>
            <div class="mt-5 grid gap-4 border-t border-white/[.07] pt-5 sm:grid-cols-2 xl:grid-cols-4">
              <div class="metric"><div class="metric-label">Block height</div><div class="metric-value">{issued.message.height.toLocaleString()}</div></div>
              <div class="metric"><div class="metric-label">Series revision</div><div class="metric-value">{issued.trailHead ? issued.trailHead.revision : 'not proven'}</div></div>
              <div class="metric"><div class="metric-label">Recorded by member</div><div class="metric-value text-xs">{short(issued.message.senderHex, 25)}</div></div>
              <div class="metric"><div class="metric-label">Anchor</div><div class="metric-value text-xs">{issued.anchorReference ? `#${issued.anchorReference.anchoredHeight} ${issued.anchorReference.mode}` : 'Pending or not configured'}</div></div>
            </div>
            <div class="mt-4 grid gap-3 text-xs sm:grid-cols-2">
              <div><div class="metric-label">Entity</div><div class="mt-1"><CopyValue value={issued.subject.entityId} width={40} label="entity id" /></div></div>
              <div><div class="metric-label">Message id</div><div class="mt-1"><CopyValue value={issued.message.messageIdHex} width={40} label="message id" /></div></div>
              {#if issued.trailHead}<div><div class="metric-label">Trail head</div><div class="mt-1"><CopyValue value={issued.trailHead.headDigestHex} width={40} label="trail head" /></div></div>{/if}
              <div><div class="metric-label">Issued</div><div class="mt-1 text-[#b7cbc4]">{formatTime(issued.issuedAt)}</div></div>
            </div>
            <div class="notice notice-warn mt-4">
              The browser assembled this certificate from node documents and checked the message id, the
              signed envelope, and the inclusion path. Run <span class="mono">yano-attest verify --members keys.json</span>
              to verify the threshold signatures independently.
            </div>
          </section>
        {/if}
      {:else if activeView === 'verify'}
        <section class="glass p-5">
          <div class="eyebrow">Verify</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Check a certificate</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
            Local checks run entirely in this tab: the file digest, the signed envelope copied from the
            evidence block, the recomputed message id, the sender signature, the doc-trail command, and the
            inclusion path. When a node is connected, the block and anchor it reports are compared too.
            Threshold finality and Cardano anchor verification stay with the CLI.
          </p>
          <div class="mt-5 grid gap-4 lg:grid-cols-2">
            <div class="panel p-4">
              <label class="text-xs font-semibold text-[#a9beb7]" for="verify-certificate">Certificate (.attest.json)</label>
              <input id="verify-certificate" class="field mt-2" type="file" accept="application/json,.json" onchange={(event) => void chooseCertificate(event)} />
              {#if verifyCertificate}
                <div class="mt-3 text-xs text-[#b7cbc4]">{verifyCertificateName}</div>
                <div class="mt-2 text-xs text-[#789087]">{verifyCertificate.subject.fileName ?? 'unnamed document'} · {verifyCertificate.subject.entityId} · {verifyCertificate.status} on {verifyCertificate.chainId}</div>
              {/if}
            </div>
            <div class="panel p-4">
              <label class="text-xs font-semibold text-[#a9beb7]" for="verify-document">Original document <span class="muted">optional</span></label>
              <input id="verify-document" class="field mt-2" type="file" onchange={(event) => void chooseVerifyDocument(event)} />
              {#if verifyDocumentName}<div class="mt-3 text-xs text-[#b7cbc4]">{verifyDocumentName}</div>{/if}
            </div>
          </div>
          {#if verifyError}<div class="notice notice-error mt-4">{verifyError}</div>{/if}
          <div class="mt-5">
            <button class="button-primary" type="button" disabled={!verifyCertificate || verifyBusy} onclick={() => void runVerification()}>{verifyBusy ? 'Verifying…' : 'Run checks'}</button>
          </div>
        </section>

        {#if verification && verifyCertificate}
          <section class="glass mt-4 p-5">
            <div class="flex flex-wrap items-center justify-between gap-3">
              <div>
                <div class="eyebrow">Result</div>
                <h2 class="mb-0 mt-2 text-lg font-semibold">{verification.consistent ? 'Locally consistent' : 'Invalid'}</h2>
              </div>
              <span class="pill {verification.consistent ? 'pill-ok' : 'pill-bad'}">{verification.consistent ? 'INTERNAL_CONSISTENCY_ONLY' : 'INVALID'}</span>
            </div>
            <div class="table-wrap mt-4">
              <table class="data-table">
                <thead><tr><th>Check</th><th>State</th><th>Detail</th></tr></thead>
                <tbody>
                  {#each verification.checks as item}
                    <tr><td class="font-semibold text-[#d8e9e3]">{item.label}</td><td><span class="pill {pillClass(item.state)}">{item.state}</span></td><td class="text-[#a9beb7]">{item.detail}</td></tr>
                  {/each}
                  {#if nodeConfirmation}
                    <tr><td class="font-semibold text-[#d8e9e3]">{nodeConfirmation.block.label}</td><td><span class="pill {pillClass(nodeConfirmation.block.state)}">{nodeConfirmation.block.state}</span></td><td class="text-[#a9beb7]">{nodeConfirmation.block.detail}</td></tr>
                    <tr><td class="font-semibold text-[#d8e9e3]">{nodeConfirmation.anchor.label}</td><td><span class="pill {pillClass(nodeConfirmation.anchor.state)}">{nodeConfirmation.anchor.state}</span></td><td class="text-[#a9beb7]">{nodeConfirmation.anchor.detail}</td></tr>
                  {/if}
                </tbody>
              </table>
            </div>
            <div class="mt-4 grid gap-3 text-xs sm:grid-cols-2">
              <div><div class="metric-label">Attested digest</div><div class="mt-1"><CopyValue value={verifyCertificate.subject.entryHashHex} width={40} label="digest" /></div></div>
              <div><div class="metric-label">Recorded by member</div><div class="mt-1"><CopyValue value={verifyCertificate.message.senderHex} width={40} label="sender" /></div></div>
              <div><div class="metric-label">Message</div><div class="mt-1 text-[#b7cbc4]">{short(verifyCertificate.message.messageIdHex, 30)} at height {verifyCertificate.message.height}</div></div>
              <div><div class="metric-label">Issued</div><div class="mt-1 text-[#b7cbc4]">{formatTime(verifyCertificate.issuedAt)} by {verifyCertificate.generator}</div></div>
            </div>
            <div class="notice notice-warn mt-4">
              Independent finality and anchor verification: <span class="mono">yano-attest verify --certificate {verifyCertificateName || 'certificate.json'} --members keys.json</span>
              {#if verifyCertificate.anchorReference} or <span class="mono">--anchor-datum-hex</span> with the state-thread datum from Cardano.{/if}
            </div>
          </section>
        {/if}
      {:else if activeView === 'trail'}
        <section class="glass p-5">
          <div class="eyebrow">Trail</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Look up a series on {selectedChainId}</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
            Every attestation with the same series id extends one hash chain. The node proves the current
            revision count and head digest against its finalized state root.
          </p>
          <form class="mt-5 flex flex-wrap items-end gap-3" onsubmit={(event) => { event.preventDefault(); void lookupTrail(); }}>
            <div class="min-w-64 flex-1">
              <label class="text-xs font-semibold text-[#a9beb7]" for="trail-entity">Series id</label>
              <input id="trail-entity" class="field mono mt-2" bind:value={trailEntity} placeholder="sha256:… or your own id" required />
            </div>
            <button class="button-primary" type="submit" disabled={trailBusy}>{trailBusy ? 'Reading…' : 'Look up'}</button>
          </form>
          {#if trailError}<div class="notice notice-error mt-4">{trailError}</div>{/if}
          {#if trail}
            <div class="mt-5 grid gap-4 border-t border-white/[.07] pt-5 sm:grid-cols-2 xl:grid-cols-4">
              <div class="metric"><div class="metric-label">Present</div><div class="metric-value">{trail.present ? 'yes' : 'no entries'}</div></div>
              <div class="metric"><div class="metric-label">Revisions</div><div class="metric-value">{trail.revision}</div></div>
              <div class="metric"><div class="metric-label">Proven at height</div><div class="metric-value">{trail.committedHeight.toLocaleString()}</div></div>
              <div class="metric"><div class="metric-label">State root</div><div class="metric-value text-xs">{short(trail.stateRoot, 25)}</div></div>
            </div>
            {#if trail.present}
              <div class="mt-4 text-xs"><div class="metric-label">Head digest</div><div class="mt-1"><CopyValue value={trail.headDigestHex} width={48} label="head digest" /></div></div>
            {/if}
          {/if}
        </section>
      {:else}
        <section class="glass p-5">
          <div class="eyebrow">About</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">What an attest certificate proves</h1>
          <div class="mt-5 grid gap-4 lg:grid-cols-2">
            <div class="panel p-4">
              <div class="metric-label">It proves</div>
              <ul class="mt-3 space-y-2 text-sm leading-6 text-[#b7cbc4]">
                <li>A doc-trail append carrying this exact digest, series id, and reference was signed by a chain member.</li>
                <li>That message sits at a fixed position in a block whose messages root the proof recomputes.</li>
                <li>The block was finalized by the chain's threshold of member signatures (CLI check).</li>
                <li>When anchored, a Cardano transaction commits to the block hash and state root (CLI check with the datum).</li>
                <li>The series had the stated revision count and head digest at that block (trail head).</li>
              </ul>
            </div>
            <div class="panel p-4">
              <div class="metric-label">It does not prove</div>
              <ul class="mt-3 space-y-2 text-sm leading-6 text-[#b7cbc4]">
                <li>Who the person behind the file was. The signer is the ingress member's key; put authorship in the reference.</li>
                <li>Anything about the file's content beyond its SHA-256 digest.</li>
                <li>A calendar time. Ordering comes from block height and, when anchored, the Cardano slot.</li>
              </ul>
            </div>
          </div>
          <div class="panel mt-4 p-4 text-sm leading-6 text-[#b7cbc4]">
            <div class="metric-label">Trust levels</div>
            <p class="mt-2">INTERNAL_CONSISTENCY_ONLY: the certificate agrees with itself (this UI). CALLER_PINNED_ROOT: the CLI verified finality against member keys you obtained independently. INDEPENDENTLY_VERIFIED_L1_ANCHOR: the CLI matched the segment to a state-thread datum read from Cardano.</p>
            <p class="mt-2 mono text-xs text-[#8ea8a0]">yano-attest verify --certificate file.attest.json --file original.pdf --members keys.json</p>
          </div>
        </section>
      {/if}
    </main>
  </div>
{/if}
