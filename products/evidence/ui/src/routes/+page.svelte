<script lang="ts">
  import { onMount } from 'svelte';
  import { DOCUMENT_REVIEW, actionCommitmentHex } from '$lib/adapters';
  import { YanoApi } from '$lib/api';
  import { toHex } from '$lib/cbor';
  import { DEFAULT_RUNTIME_CONFIG, loadRuntimeConfig } from '$lib/config';
  import {
    actorIds,
    awaitFinalized,
    buildExport,
    decisionStatement,
    documentReviewCommand,
    matchSigningKey,
    proposalIds,
    proposalStatement,
    readActor,
    readCommandResult,
    readDocumentHead,
    readOrganization,
    readPolicy,
    readProposal,
    readReceipt,
    readStats,
    releaseBodyHex,
    scanCommands,
    submitStatement,
    type ScanResult,
    type SigningIdentity,
    type StatementOutcome
  } from '$lib/desk';
  import { sha256Hex } from '$lib/hash';
  import { failureMessage, formatBytes, short, withLiveStatus } from '$lib/model';
  import { RESULT_EXPLANATIONS, clauseProgress, decisionObstacle, encodeSignedCommand, requireHex64, requireId, type ClauseProgress } from '$lib/roles';
  import { createSigner, parseSeed, webCryptoEd25519Available, type ActorSigner } from '$lib/signer';
  import ConnectionPanel from '$lib/components/ConnectionPanel.svelte';
  import CopyValue from '$lib/components/CopyValue.svelte';
  import type {
    ActiveConnection,
    ActorRecord,
    ActorStatement,
    ApprovalStats,
    DiscoveredChain,
    DocumentReviewCommand,
    DocumentReviewReceipt,
    NodeConfig,
    NodeStatus,
    OrganizationRecord,
    PolicyRecord,
    ProofBound,
    ProposalRecord,
    RuntimeConfig,
    ScannedCommand,
    TrailHead
  } from '$lib/types';

  type View = 'proposals' | 'propose' | 'directory' | 'key' | 'about';

  const MAX_DOCUMENT_BYTES = 256 * 1024 * 1024;
  const views: Array<{ id: View; code: string; label: string }> = [
    { id: 'proposals', code: 'PR', label: 'Proposals and decisions' },
    { id: 'propose', code: 'NW', label: 'Propose a release' },
    { id: 'directory', code: 'DR', label: 'Directory' },
    { id: 'key', code: 'KY', label: 'Actor key' },
    { id: 'about', code: '??', label: 'What this proves' }
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
  let activeView: View = 'proposals';

  // Actor key
  let seedText = '';
  let keyActorId = '';
  let keyBusy = false;
  let keyError = '';
  let signer: ActorSigner | null = null;
  let identity: SigningIdentity | null = null;
  let identityActor: ProofBound<ActorRecord> | null = null;

  // Proposals
  let scanBusy = false;
  let scanError = '';
  let scan: ScanResult | null = null;
  let lookupId = '';
  let selectedProposalId = '';
  let detailBusy = false;
  let detailError = '';
  let proposal: ProofBound<ProposalRecord> | null = null;
  let policy: ProofBound<PolicyRecord> | null = null;
  let receipt: ProofBound<DocumentReviewReceipt> | null = null;
  let documentHead: ProofBound<TrailHead> | null = null;
  let progress: ClauseProgress[] = [];
  let decideClause = '';
  let decideBusy = false;
  let decideError = '';
  let decideOutcome: StatementOutcome | null = null;
  let decidePreview: ActorStatement | null = null;
  let exportUrl = '';
  let exportName = '';

  // Propose and release
  let proposalIdInput = '';
  let entityInput = '';
  let referenceInput = '';
  let digestInput = '';
  let selectedFile: File | null = null;
  let digestBusy = false;
  let proposeBusy = false;
  let proposeError = '';
  let proposeStep: 'idle' | 'signing' | 'submitting' | 'finalizing' | 'done' = 'idle';
  let proposeOutcome: StatementOutcome | null = null;
  let proposePreview: ActorStatement | null = null;
  let proposedCommand: DocumentReviewCommand | null = null;
  const commandsInMemory = new Map<string, DocumentReviewCommand>();
  let releaseBusy = false;
  let releaseError = '';
  let releaseOutcome: { messageIdHex: string; height: number } | null = null;
  let releaseEntity = '';
  let releaseDigest = '';
  let releaseReference = '';

  // Directory
  let dirBusy = false;
  let dirError = '';
  let organizations: Array<ProofBound<OrganizationRecord>> = [];
  let actors: Array<ProofBound<ActorRecord>> = [];
  let policies: Array<ProofBound<PolicyRecord>> = [];
  let stats: ProofBound<ApprovalStats> | null = null;
  let dirLookupKind: 'actor' | 'organization' | 'policy' = 'actor';
  let dirLookupId = '';

  $: selectedChain = chains.find((chain) => chain.summary.chainId === selectedChainId) ?? null;
  $: adapter = selectedChain?.adapter ?? 'approvals-only';
  $: tipHeight = selectedChain?.summary.tipHeight ?? 0;
  $: hints = runtimeConfig.directoryHints?.[selectedChainId] ?? {};
  $: proposalList = summarize(scan?.commands ?? []);
  $: releasePolicyId = adapter === 'document-review' ? DOCUMENT_REVIEW.policyId : '';
  $: canDecide = !!identity && !!proposal && proposal.record.status === 'PENDING';
  $: obstacle = identity && proposal && policy && decideClause
    ? decisionObstacle(policy.record, proposal.record, identity.actor, decideClause, tipHeight) : null;
  $: signingUnavailable = !webCryptoEd25519Available();

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
      const candidate = new YanoApi(next);
      const [config, status, discovered] = await Promise.all([
        candidate.nodeConfig(), candidate.nodeStatus(), candidate.discoverChains()
      ]);
      if (runtimeConfig.expectedNetwork
          && config.network?.toLowerCase() !== runtimeConfig.expectedNetwork.toLowerCase()) {
        throw new Error(`This node reports ${config.network || 'an unknown network'}, but the UI expects ${runtimeConfig.expectedNetwork}`);
      }
      if (!discovered.length) {
        throw new Error('The node is reachable, but no app chain carries the domain-actors and role-approvals components');
      }
      connection = next;
      api = candidate;
      nodeConfig = config;
      nodeStatus = status;
      chains = discovered;
      selectedChainId = runtimeConfig.defaultChainId
        && discovered.some((chain) => chain.summary.chainId === runtimeConfig.defaultChainId)
        ? runtimeConfig.defaultChainId
        : discovered[0].summary.chainId;
      await selectChain(selectedChainId);
    } catch (cause) {
      connectionError = failureMessage(cause, 'Could not verify this Yano installation');
    } finally {
      connecting = false;
    }
  }

  function disconnectNode() {
    releaseSigner();
    connection = null;
    api = null;
    nodeConfig = null;
    nodeStatus = null;
    chains = [];
    selectedChainId = '';
    resetChainState();
  }

  async function selectChain(chainId: string) {
    selectedChainId = chainId;
    releaseSigner();
    resetChainState();
    await refreshChain();
    await rescan();
  }

  function resetChainState() {
    scan = null;
    scanError = '';
    selectedProposalId = '';
    proposal = null;
    policy = null;
    receipt = null;
    documentHead = null;
    progress = [];
    decideOutcome = null;
    decideError = '';
    proposeOutcome = null;
    proposeError = '';
    proposeStep = 'idle';
    proposedCommand = null;
    releaseOutcome = null;
    releaseError = '';
    organizations = [];
    actors = [];
    policies = [];
    stats = null;
    dirError = '';
    revokeExport();
  }

  async function refreshChain() {
    if (!api || !selectedChainId) return;
    try {
      const live = await api.chainStatus(selectedChainId);
      chains = chains.map((chain) => chain.summary.chainId === selectedChainId ? withLiveStatus(chain, live) : chain);
    } catch (cause) {
      scanError = failureMessage(cause, 'The chain status could not be refreshed');
    }
  }

  // ------------------------------------------------------------------ actor key

  async function loadKey() {
    if (!api || !selectedChainId) return;
    keyBusy = true;
    keyError = '';
    const seedInput = seedText;
    releaseSigner();
    try {
      const actorId = requireId(keyActorId.trim(), 'actor id');
      const bound = await readActor(api, selectedChainId, actorId);
      if (!bound) throw new Error(`No current actor record for ${actorId} on ${selectedChainId}`);
      const candidate = await createSigner(parseSeed(seedInput));
      const match = matchSigningKey(bound.record, candidate.publicKeyHex, tipHeight);
      if (!match) {
        candidate.release();
        throw new Error(`The seed's public key ${short(candidate.publicKeyHex, 20)} is not ${actorId}'s ACTIVE key`);
      }
      signer = candidate;
      identity = match;
      identityActor = bound;
    } catch (cause) {
      keyError = failureMessage(cause, 'The actor key could not be loaded');
    } finally {
      keyBusy = false;
    }
  }

  function releaseSigner() {
    signer?.release();
    signer = null;
    identity = null;
    identityActor = null;
    seedText = '';
  }

  async function chooseSeedFile(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    if (!file) return;
    if (file.size > 4096) {
      keyError = 'A seed file holds 32 raw bytes or 64 hex characters';
      return;
    }
    try {
      seedText = toHex(parseSeed(new Uint8Array(await file.arrayBuffer())));
      keyError = '';
    } catch (cause) {
      keyError = failureMessage(cause, 'The seed file could not be read');
    } finally {
      input.value = '';
    }
  }

  // ------------------------------------------------------------------ proposals

  function summarize(commands: ScannedCommand[]) {
    const byProposal = new Map<string, { proposalId: string; latest: ScannedCommand; count: number; released: boolean }>();
    for (const command of commands) {
      const entry = byProposal.get(command.proposalId);
      if (!entry) {
        byProposal.set(command.proposalId, { proposalId: command.proposalId, latest: command, count: 1, released: command.action === 'RELEASE' });
      } else {
        entry.count++;
        entry.released ||= command.action === 'RELEASE';
        if (command.height > entry.latest.height || (command.height === entry.latest.height && command.index > entry.latest.index)) {
          entry.latest = command;
        }
      }
    }
    return [...byProposal.values()].sort((a, b) => b.latest.height - a.latest.height);
  }

  async function rescan() {
    if (!api || !selectedChainId) return;
    scanBusy = true;
    scanError = '';
    try {
      await refreshChain();
      scan = await scanCommands(api, selectedChainId, tipHeight, adapter);
    } catch (cause) {
      scanError = failureMessage(cause, 'The recent blocks could not be scanned');
    } finally {
      scanBusy = false;
    }
  }

  async function openProposal(id: string) {
    if (!api || !selectedChainId) return;
    selectedProposalId = id;
    detailBusy = true;
    detailError = '';
    decideOutcome = null;
    decideError = '';
    decidePreview = null;
    releaseOutcome = null;
    releaseError = '';
    revokeExport();
    try {
      const proposalId = requireId(id.trim(), 'proposal id');
      const bound = await readProposal(api, selectedChainId, proposalId);
      if (!bound) throw new Error(`No proposal ${proposalId} on ${selectedChainId}`);
      proposal = bound;
      policy = await readPolicy(api, selectedChainId, bound.record.policyId, bound.record.policyRevision);
      progress = policy ? clauseProgress(policy.record, bound.record.decisions) : [];
      decideClause = policy?.record.clauses.find((clause) => identity?.actor.roles.includes(clause.role))?.clauseId
        ?? policy?.record.clauses[0]?.clauseId ?? '';
      if (adapter === 'document-review') {
        receipt = await readReceipt(api, selectedChainId, proposalId);
        documentHead = receipt ? await readDocumentHead(api, selectedChainId, receipt.record.documentEntityId) : null;
      } else {
        receipt = null;
        documentHead = null;
      }
      const remembered = commandsInMemory.get(proposalId);
      releaseEntity = remembered?.documentEntityId ?? '';
      releaseDigest = remembered?.documentHashHex ?? '';
      releaseReference = remembered?.documentRef ?? '';
    } catch (cause) {
      proposal = null;
      detailError = failureMessage(cause, 'The proposal could not be read');
    } finally {
      detailBusy = false;
    }
  }

  function previewDecision(action: 'APPROVE' | 'REJECT') {
    decideError = '';
    decidePreview = null;
    if (!identity || !proposal) return;
    try {
      decidePreview = decisionStatement(selectedChainId, identity, proposal.record, action, decideClause, tipHeight);
    } catch (cause) {
      decideError = failureMessage(cause, 'The statement could not be prepared');
    }
  }

  async function signAndSubmitDecision() {
    if (!api || !signer || !decidePreview) return;
    decideBusy = true;
    decideError = '';
    try {
      const signed = await signer.sign(decidePreview);
      const outcome = await submitStatement(api, selectedChainId, toHex(encodeSignedCommand(signed)));
      decidePreview = null;
      await openProposal(selectedProposalId);
      decideOutcome = outcome;
      await rescan();
    } catch (cause) {
      decideError = failureMessage(cause, 'The decision could not be submitted');
    } finally {
      decideBusy = false;
    }
  }

  async function exportBundle() {
    if (!api || !proposal) return;
    revokeExport();
    const bundle = buildExport({
      chainId: selectedChainId,
      adapter,
      proposal,
      policy,
      receipt,
      documentHead,
      stats: await readStats(api, selectedChainId),
      organizations,
      actors
    });
    exportName = `${proposal.record.proposalId}.evidence-desk.json`;
    exportUrl = URL.createObjectURL(new Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json' }));
  }

  function revokeExport() {
    if (exportUrl) URL.revokeObjectURL(exportUrl);
    exportUrl = '';
    exportName = '';
  }

  // ------------------------------------------------------------------ propose and release

  async function chooseDocument(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    selectedFile = null;
    if (!file) return;
    if (file.size > MAX_DOCUMENT_BYTES) {
      proposeError = 'Files above 256 MiB are hashed outside the browser; paste the digest instead';
      return;
    }
    digestBusy = true;
    try {
      digestInput = await sha256Hex(new Uint8Array(await file.arrayBuffer()));
      selectedFile = file;
      proposeError = '';
    } catch (cause) {
      proposeError = failureMessage(cause, 'The file could not be hashed');
    } finally {
      digestBusy = false;
    }
  }

  async function previewProposal() {
    if (!api || !identity) return;
    proposeError = '';
    proposePreview = null;
    proposedCommand = null;
    try {
      const releasePolicy = await readPolicy(api, selectedChainId, releasePolicyId);
      if (!releasePolicy) throw new Error(`The chain has no current ${releasePolicyId} policy`);
      const command = documentReviewCommand(requireId(proposalIdInput.trim(), 'proposal id'), releasePolicy.record,
        entityInput.trim(), requireHex64(digestInput, 'document digest'), referenceInput.trim());
      proposePreview = proposalStatement(selectedChainId, identity, releasePolicy.record, adapter,
        command.proposalId, actionCommitmentHex(command), tipHeight);
      proposedCommand = command;
    } catch (cause) {
      proposeError = failureMessage(cause, 'The proposal could not be prepared');
    }
  }

  async function signAndSubmitProposal() {
    if (!api || !signer || !proposePreview || !proposedCommand) return;
    proposeBusy = true;
    proposeError = '';
    try {
      proposeStep = 'signing';
      const signed = await signer.sign(proposePreview);
      proposeStep = 'submitting';
      const bodyHex = toHex(encodeSignedCommand(signed));
      proposeStep = 'finalizing';
      proposeOutcome = await submitStatement(api, selectedChainId, bodyHex);
      commandsInMemory.set(proposedCommand.proposalId, proposedCommand);
      proposeStep = 'done';
      await rescan();
    } catch (cause) {
      proposeError = failureMessage(cause, 'The proposal could not be submitted');
      proposeStep = 'idle';
    } finally {
      proposeBusy = false;
    }
  }

  async function submitRelease() {
    if (!api || !proposal || !policy) return;
    releaseBusy = true;
    releaseError = '';
    releaseOutcome = null;
    try {
      const command = documentReviewCommand(proposal.record.proposalId, policy.record,
        releaseEntity.trim(), requireHex64(releaseDigest, 'document digest'), releaseReference.trim());
      const bodyHex = releaseBodyHex(command, proposal.record);
      const submitted = await api.submitMessage(selectedChainId, DOCUMENT_REVIEW.topic, bodyHex);
      const finalized = await awaitFinalized(api, selectedChainId, submitted.messageId);
      commandsInMemory.set(command.proposalId, command);
      await openProposal(proposal.record.proposalId);
      releaseOutcome = { messageIdHex: submitted.messageId, height: finalized.height };
      await rescan();
    } catch (cause) {
      releaseError = failureMessage(cause, 'The release could not be submitted');
    } finally {
      releaseBusy = false;
    }
  }

  // ------------------------------------------------------------------ directory

  async function loadDirectory() {
    if (!api || !selectedChainId) return;
    dirBusy = true;
    dirError = '';
    try {
      const chainId = selectedChainId;
      const knownActors = [...new Set([...(hints.actors ?? []), ...actorIds(scan?.commands ?? [])])];
      const knownPolicies = [...new Set([...(hints.policies ?? []), ...(releasePolicyId ? [releasePolicyId] : [])])];
      const actorRecords = (await Promise.all(knownActors.map((id) => readActor(api!, chainId, id))))
        .filter((record): record is ProofBound<ActorRecord> => record !== null);
      const knownOrganizations = [...new Set([...(hints.organizations ?? []), ...actorRecords.map((actor) => actor.record.organizationId)])];
      organizations = (await Promise.all(knownOrganizations.map((id) => readOrganization(api!, chainId, id))))
        .filter((record): record is ProofBound<OrganizationRecord> => record !== null);
      actors = actorRecords;
      policies = (await Promise.all(knownPolicies.map((id) => readPolicy(api!, chainId, id))))
        .filter((record): record is ProofBound<PolicyRecord> => record !== null);
      stats = await readStats(api, chainId);
    } catch (cause) {
      dirError = failureMessage(cause, 'The directory could not be read');
    } finally {
      dirBusy = false;
    }
  }

  async function lookupDirectory() {
    if (!api || !selectedChainId) return;
    dirBusy = true;
    dirError = '';
    try {
      const id = requireId(dirLookupId.trim(), 'identifier');
      if (dirLookupKind === 'actor') {
        const record = await readActor(api, selectedChainId, id);
        if (!record) throw new Error(`No actor ${id}`);
        actors = [record, ...actors.filter((actor) => actor.record.actorId !== id)];
      } else if (dirLookupKind === 'organization') {
        const record = await readOrganization(api, selectedChainId, id);
        if (!record) throw new Error(`No organization ${id}`);
        organizations = [record, ...organizations.filter((entry) => entry.record.organizationId !== id)];
      } else {
        const record = await readPolicy(api, selectedChainId, id);
        if (!record) throw new Error(`No policy ${id}`);
        policies = [record, ...policies.filter((entry) => entry.record.policyId !== id)];
      }
    } catch (cause) {
      dirError = failureMessage(cause, 'The record could not be read');
    } finally {
      dirBusy = false;
    }
  }

  function proofPill(state: 'BOUND' | 'MISMATCH'): string {
    return state === 'BOUND' ? 'pill-ok' : 'pill-bad';
  }

  function statusPill(status: string): string {
    return status === 'APPROVED' ? 'pill-ok' : status === 'PENDING' ? 'pill-warn' : 'pill-bad';
  }

  function outcomeText(outcome: StatementOutcome | null): string {
    if (!outcome) return '';
    if (!outcome.result) return 'finalized, but the workflow recorded no result: malformed or not a role command';
    return `${outcome.result.record.resultCode}: ${RESULT_EXPLANATIONS[outcome.result.record.resultCode]}`;
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
        <div class="grid size-10 place-items-center rounded-xl border border-mint-400/30 bg-mint-400/10 font-mono text-xs font-bold text-mint-400">EV</div>
        <div><div class="text-sm font-semibold tracking-tight">Yano X <span class="text-mint-400">Evidence Desk</span></div><div class="text-[.67rem] uppercase tracking-[.13em] text-[#71887f]">Role-gated release, proof-bound</div></div>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <span class="pill pill-ok">{nodeConfig?.network || 'connected'}</span>
        <span class="pill {nodeStatus?.runtimeDegraded ? 'pill-warn' : 'pill-ok'}">{nodeStatus?.runtimeDegraded ? 'degraded' : 'node online'}</span>
        {#if identity}<span class="pill pill-ok">signing as {identity.actor.actorId}</span>{:else}<span class="pill pill-warn">no actor key</span>{/if}
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
      <nav class="mt-3 flex gap-1 overflow-auto lg:block" aria-label="Desk sections">
        {#each views as view}
          <button class="nav-item shrink-0 {activeView === view.id ? 'active' : ''}" type="button" onclick={() => { activeView = view.id; if (view.id === 'directory' && !organizations.length && !dirBusy) void loadDirectory(); }}>
            <span class="nav-icon">{view.code}</span><span>{view.label}</span>
          </button>
        {/each}
      </nav>
      <div class="mt-4 border-t border-white/[.07] px-2 pt-4">
        <label class="text-[.65rem] uppercase tracking-[.1em] text-[#60766f]" for="chain-picker">Role chain</label>
        <select id="chain-picker" class="field mt-2" value={selectedChainId} onchange={(event) => void selectChain(event.currentTarget.value)}>
          {#each chains as chain}<option value={chain.summary.chainId}>{chain.summary.chainId}</option>{/each}
        </select>
        <div class="mt-3 text-xs text-[#789087]">Adapter <span class="mono text-[#b7cbc4]">{adapter}</span></div>
        <div class="mt-1 text-xs text-[#789087]">Height {tipHeight.toLocaleString()}</div>
        <div class="mt-1 text-xs text-[#789087]">{selectedChain?.status.members ?? '—'} members, threshold {selectedChain?.status.threshold ?? '—'}</div>
        <button class="button-quiet mt-3 min-h-0 w-full py-2" type="button" disabled={scanBusy} onclick={rescan}>{scanBusy ? 'Scanning…' : 'Refresh'}</button>
      </div>
    </aside>

    <main class="min-w-0">
      {#if activeView === 'proposals'}
        <section class="glass p-5">
          <div class="eyebrow">Proposals</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Decisions on {selectedChainId}</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
            Proposals found in the last {scan ? (scan.toHeight - scan.fromHeight + 1).toLocaleString() : '—'} blocks
            {#if scan?.truncated}(scan truncated at the fetch bound){/if}. Records are read from the chain's
            authenticated state and bound to the root of the block they were committed in.
          </p>
          {#if scanError}<div class="notice notice-error mt-4">{scanError}</div>{/if}

          <form class="mt-5 flex flex-wrap items-end gap-3" onsubmit={(event) => { event.preventDefault(); void openProposal(lookupId); }}>
            <div class="min-w-64 flex-1">
              <label class="text-xs font-semibold text-[#a9beb7]" for="lookup-proposal">Open a proposal by id</label>
              <input id="lookup-proposal" class="field mono mt-2" bind:value={lookupId} placeholder="review-2026-0042" />
            </div>
            <button class="button-secondary" type="submit" disabled={detailBusy || !lookupId.trim()}>Open</button>
          </form>

          <div class="mt-5 grid gap-4 xl:grid-cols-[minmax(0,.9fr)_minmax(0,1.3fr)]">
            <div class="panel p-4">
              <div class="metric-label">Recent proposals</div>
              {#if !proposalList.length}
                <div class="empty-state mt-3">{scanBusy ? 'Scanning recent blocks…' : 'No role commands in the scanned window.'}</div>
              {:else}
                <div class="table-wrap mt-3">
                  <table class="data-table">
                    <thead><tr><th>Proposal</th><th>Last</th><th>Height</th></tr></thead>
                    <tbody>
                      {#each proposalList as entry}
                        <tr>
                          <td><button class="mono text-left text-mint-400 hover:underline" type="button" onclick={() => void openProposal(entry.proposalId)}>{entry.proposalId}</button></td>
                          <td class="text-[#a9beb7]">{entry.latest.action}{entry.latest.actorId ? ` by ${entry.latest.actorId}` : ''}{entry.released ? ' · released' : ''}</td>
                          <td class="text-[#a9beb7]">{entry.latest.height}</td>
                        </tr>
                      {/each}
                    </tbody>
                  </table>
                </div>
              {/if}
            </div>

            <div class="panel p-4">
              {#if detailBusy}
                <div class="empty-state">Reading the proposal and its proofs…</div>
              {:else if detailError}
                <div class="notice notice-error">{detailError}</div>
              {:else if !proposal}
                <div class="empty-state">Select a proposal to see its decisions, clause progress, and proofs.</div>
              {:else}
                <div class="flex flex-wrap items-center justify-between gap-2">
                  <div><div class="metric-label">Proposal</div><div class="mono mt-1 text-sm text-[#d8e9e3]">{proposal.record.proposalId}</div></div>
                  <span class="pill {statusPill(proposal.record.status)}">{proposal.record.status}</span>
                </div>
                <div class="mt-3 grid gap-3 text-xs sm:grid-cols-2">
                  <div><div class="metric-label">Proposer</div><div class="mt-1 text-[#b7cbc4]">{proposal.record.proposerActorId} ({proposal.record.proposerOrganizationId}, {proposal.record.proposerRole})</div></div>
                  <div><div class="metric-label">Policy</div><div class="mt-1 text-[#b7cbc4]">{proposal.record.policyId} r{proposal.record.policyRevision}</div></div>
                  <div><div class="metric-label">Deadline</div><div class="mt-1 text-[#b7cbc4]">height {proposal.record.deadlineHeight} (tip {tipHeight}){tipHeight > proposal.record.deadlineHeight ? ' · past' : ''}</div></div>
                  <div><div class="metric-label">Payload</div><div class="mt-1 text-[#b7cbc4]">{proposal.record.payloadDomain} · <CopyValue value={proposal.record.payloadHashHex} width={20} label="payload hash" /></div></div>
                  <div><div class="metric-label">Record proof</div><div class="mt-1"><span class="pill {proofPill(proposal.proof)}">{proposal.proof}</span> <span class="text-[#789087]">{proposal.detail}</span></div></div>
                  <div><div class="metric-label">Policy proof</div><div class="mt-1">{#if policy}<span class="pill {proofPill(policy.proof)}">{policy.proof}</span> <span class="text-[#789087]">{policy.detail}</span>{:else}<span class="pill pill-bad">policy revision missing</span>{/if}</div></div>
                </div>

                <div class="metric-label mt-5">Clauses</div>
                <div class="table-wrap mt-2">
                  <table class="data-table">
                    <thead><tr><th>Clause</th><th>Role</th><th>Needs</th><th>Has</th><th>State</th></tr></thead>
                    <tbody>
                      {#each progress as entry}
                        <tr>
                          <td class="mono">{entry.clause.clauseId}</td>
                          <td>{entry.clause.role}</td>
                          <td>{entry.clause.minimumCount} distinct {entry.clause.distinctBy === 'ORGANIZATION' ? 'organizations' : 'actors'}</td>
                          <td>{entry.distinctCount}</td>
                          <td><span class="pill {entry.satisfied ? 'pill-ok' : 'pill-warn'}">{entry.satisfied ? 'satisfied' : 'open'}</span></td>
                        </tr>
                      {/each}
                    </tbody>
                  </table>
                </div>

                <div class="metric-label mt-5">Accepted decisions</div>
                {#if !proposal.record.decisions.length}
                  <div class="empty-state mt-2">No accepted decision yet.</div>
                {:else}
                  <div class="table-wrap mt-2">
                    <table class="data-table">
                      <thead><tr><th>Decision</th><th>Actor</th><th>Organization</th><th>Clause</th><th>Key</th><th>Height</th></tr></thead>
                      <tbody>
                        {#each proposal.record.decisions as decision}
                          <tr><td>{decision.action}</td><td>{decision.actorId} r{decision.actorRevision}</td><td>{decision.organizationId}</td><td class="mono">{decision.clauseId}</td><td class="mono">{decision.keyId}</td><td>{decision.acceptedHeight}</td></tr>
                        {/each}
                      </tbody>
                    </table>
                  </div>
                {/if}

                {#if adapter === 'document-review'}
                  <div class="metric-label mt-5">Release</div>
                  {#if receipt}
                    <div class="mt-2 grid gap-3 text-xs sm:grid-cols-2">
                      <div><div class="metric-label">Consumption receipt</div><div class="mt-1"><span class="pill {proofPill(receipt.proof)}">{receipt.proof}</span> <span class="text-[#789087]">applied at height {receipt.record.appliedHeight}</span></div></div>
                      <div><div class="metric-label">Commitment matches proposal</div><div class="mt-1"><span class="pill {receipt.record.actionCommitmentHex === proposal.record.payloadHashHex ? 'pill-ok' : 'pill-bad'}">{receipt.record.actionCommitmentHex === proposal.record.payloadHashHex ? 'MATCH' : 'MISMATCH'}</span></div></div>
                      <div><div class="metric-label">Document</div><div class="mt-1 mono text-[#b7cbc4]">{receipt.record.documentEntityId}</div></div>
                      <div><div class="metric-label">Document head</div><div class="mt-1">{#if documentHead}<span class="pill {proofPill(documentHead.proof)}">{documentHead.proof}</span> <span class="text-[#789087]">revision {documentHead.record.count}, head {short(documentHead.record.headHashHex, 18)}</span>{:else}<span class="pill pill-bad">absent</span>{/if}</div></div>
                      <div class="sm:col-span-2"><div class="metric-label">Release message</div><div class="mt-1"><CopyValue value={receipt.record.messageIdHex} width={40} label="message id" /></div></div>
                    </div>
                  {:else if proposal.record.status === 'APPROVED'}
                    <form class="mt-2 grid gap-3 sm:grid-cols-2" onsubmit={(event) => { event.preventDefault(); void submitRelease(); }}>
                      <div><label class="text-xs font-semibold text-[#a9beb7]" for="release-entity">Document entity id</label><input id="release-entity" class="field mono mt-2" bind:value={releaseEntity} required /></div>
                      <div><label class="text-xs font-semibold text-[#a9beb7]" for="release-digest">Document SHA-256</label><input id="release-digest" class="field mono mt-2" bind:value={releaseDigest} required /></div>
                      <div class="sm:col-span-2"><label class="text-xs font-semibold text-[#a9beb7]" for="release-ref">Reference</label><input id="release-ref" class="field mt-2" bind:value={releaseReference} /></div>
                      <div class="sm:col-span-2 text-xs text-[#789087]">The release is submitted only when blake2b-256 of these inputs equals the proposal's payload hash. The inputs are pre-filled when this browser made the proposal.</div>
                      {#if releaseError}<div class="notice notice-error sm:col-span-2">{releaseError}</div>{/if}
                      <div class="sm:col-span-2"><button class="button-primary" type="submit" disabled={releaseBusy}>{releaseBusy ? 'Submitting and waiting for finality…' : 'Submit the release'}</button></div>
                    </form>
                  {:else}
                    <div class="empty-state mt-2">Release becomes available once the proposal is approved.</div>
                  {/if}
                  {#if releaseOutcome}<div class="notice mt-3">Release {short(releaseOutcome.messageIdHex, 24)} finalized at height {releaseOutcome.height}.</div>{/if}
                {/if}

                <div class="metric-label mt-5">Decide</div>
                {#if !identity}
                  <div class="empty-state mt-2">Load an actor key to approve or reject.</div>
                {:else if !canDecide}
                  <div class="empty-state mt-2">This proposal is {proposal.record.status.toLowerCase()}; no further decision is accepted.</div>
                {:else}
                  <div class="mt-2 flex flex-wrap items-end gap-3">
                    <div>
                      <label class="text-xs font-semibold text-[#a9beb7]" for="decide-clause">Clause</label>
                      <select id="decide-clause" class="field mt-2" bind:value={decideClause}>
                        {#each policy?.record.clauses ?? [] as clause}<option value={clause.clauseId}>{clause.clauseId} ({clause.role})</option>{/each}
                      </select>
                    </div>
                    <button class="button-secondary" type="button" disabled={decideBusy || !!obstacle} onclick={() => previewDecision('APPROVE')}>Prepare approval</button>
                    <button class="button-danger" type="button" disabled={decideBusy || !!obstacle || policy?.record.rejectionMode === 'DISABLED'} onclick={() => previewDecision('REJECT')}>Prepare rejection</button>
                  </div>
                  {#if obstacle}<div class="notice notice-warn mt-3">The chain would answer <span class="mono">{obstacle}</span>: {RESULT_EXPLANATIONS[obstacle]}</div>{/if}
                  {#if decidePreview}
                    <div class="panel mt-3 p-3 text-xs">
                      <div class="metric-label">Statement to sign as {decidePreview.actorId} with {decidePreview.keyId}</div>
                      <div class="mt-2 grid gap-1 sm:grid-cols-2 text-[#b7cbc4]">
                        <div>action <span class="mono">{decidePreview.action}</span></div><div>clause <span class="mono">{decidePreview.clauseId}</span></div>
                        <div>chain <span class="mono">{decidePreview.chainId}</span></div><div>proposal <span class="mono">{decidePreview.proposalId}</span></div>
                        <div>policy <span class="mono">{decidePreview.policyId} r{decidePreview.policyRevision}</span></div><div>deadline <span class="mono">{decidePreview.deadlineHeight}</span></div>
                        <div class="sm:col-span-2">payload <span class="mono">{decidePreview.payloadDomain} {short(decidePreview.payloadHashHex, 24)}</span></div>
                      </div>
                      <button class="button-primary mt-3" type="button" disabled={decideBusy} onclick={signAndSubmitDecision}>{decideBusy ? 'Signing, submitting, waiting for finality…' : 'Sign and submit'}</button>
                    </div>
                  {/if}
                  {#if decideError}<div class="notice notice-error mt-3">{decideError}</div>{/if}
                {/if}
                {#if decideOutcome}
                  <div class="notice mt-3 {decideOutcome.result?.record.resultCode === 'ACCEPTED' ? '' : 'notice-warn'}">
                    Message {short(decideOutcome.messageIdHex, 24)} finalized at height {decideOutcome.height}. Outcome {outcomeText(decideOutcome)}
                    {#if decideOutcome.result}<span class="pill {proofPill(decideOutcome.result.proof)} ml-2">result {decideOutcome.result.proof}</span>{/if}
                  </div>
                {/if}

                <div class="mt-5 flex flex-wrap items-center gap-3">
                  <button class="button-secondary" type="button" onclick={exportBundle}>Build export bundle</button>
                  {#if exportUrl}<a class="button-primary" href={exportUrl} download={exportName}>Download {exportName}</a>{/if}
                </div>
              {/if}
            </div>
          </div>
        </section>

      {:else if activeView === 'propose'}
        <section class="glass p-5">
          <div class="eyebrow">Propose</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Propose a release on {selectedChainId}</h1>
          {#if adapter !== 'document-review'}
            <div class="notice notice-warn mt-4">
              {adapter === 'role-evidence'
                ? 'This chain runs the role-evidence profile. Its release nests connector commands that the desk does not build yet; propose and release with demo.sh publish, then decide and inspect here.'
                : 'This chain carries the role components but no release adapter the desk knows. Proposals must be made with the CLI; decisions work here.'}
            </div>
          {:else if !identity}
            <div class="empty-state mt-4">Load an actor key that holds a proposer role first.</div>
          {:else}
            <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
              The proposal commits to the whole release: entity id, document digest, and reference are fixed now,
              hashed with blake2b-256 into the statement's payload hash, and re-derived at release time.
            </p>
            <form class="mt-5 grid gap-4 lg:grid-cols-2" onsubmit={(event) => { event.preventDefault(); void previewProposal(); }}>
              <div><label class="text-xs font-semibold text-[#a9beb7]" for="propose-id">Proposal id</label><input id="propose-id" class="field mono mt-2" bind:value={proposalIdInput} placeholder="review-2026-0042" required /></div>
              <div><label class="text-xs font-semibold text-[#a9beb7]" for="propose-entity">Document entity id</label><input id="propose-entity" class="field mono mt-2" bind:value={entityInput} placeholder="contract-2026-0042" required /></div>
              <div>
                <label class="text-xs font-semibold text-[#a9beb7]" for="propose-file">Document <span class="muted">hashed here, never uploaded</span></label>
                <input id="propose-file" class="field mt-2" type="file" onchange={chooseDocument} />
                {#if digestBusy}<div class="mt-2 text-xs text-[#789087]">Hashing…</div>{/if}
                {#if selectedFile}<div class="mt-2 text-xs text-[#b7cbc4]">{selectedFile.name} · {formatBytes(selectedFile.size)}</div>{/if}
              </div>
              <div><label class="text-xs font-semibold text-[#a9beb7]" for="propose-digest">Document SHA-256 <span class="muted">or paste</span></label><input id="propose-digest" class="field mono mt-2" bind:value={digestInput} placeholder="64 hex characters" required /></div>
              <div class="lg:col-span-2"><label class="text-xs font-semibold text-[#a9beb7]" for="propose-ref">Reference <span class="muted">bound on chain</span></label><input id="propose-ref" class="field mt-2" bind:value={referenceInput} placeholder="showcase://documents/contract-2026-0042" /></div>
              {#if proposeError}<div class="notice notice-error lg:col-span-2">{proposeError}</div>{/if}
              <div class="lg:col-span-2"><button class="button-secondary" type="submit" disabled={proposeBusy}>Prepare the statement</button></div>
            </form>
            {#if proposePreview && proposedCommand}
              <div class="panel mt-4 p-3 text-xs">
                <div class="metric-label">Statement to sign as {proposePreview.actorId} with {proposePreview.keyId}</div>
                <div class="mt-2 grid gap-1 sm:grid-cols-2 text-[#b7cbc4]">
                  <div>action <span class="mono">PROPOSE</span></div><div>proposal <span class="mono">{proposePreview.proposalId}</span></div>
                  <div>policy <span class="mono">{proposePreview.policyId} r{proposePreview.policyRevision}</span></div><div>deadline <span class="mono">{proposePreview.deadlineHeight}</span> (tip {tipHeight})</div>
                  <div class="sm:col-span-2">payload hash <span class="mono">{proposePreview.payloadHashHex}</span></div>
                  <div class="sm:col-span-2">= blake2b-256 of [{proposedCommand.documentEntityId}, {short(proposedCommand.documentHashHex, 20)}, "{proposedCommand.documentRef}"]</div>
                </div>
                <button class="button-primary mt-3" type="button" disabled={proposeBusy} onclick={signAndSubmitProposal}>{proposeBusy ? `${proposeStep}…` : 'Sign and submit'}</button>
              </div>
            {/if}
            {#if proposeOutcome}
              <div class="notice mt-4 {proposeOutcome.result?.record.resultCode === 'ACCEPTED' ? '' : 'notice-warn'}">
                Message {short(proposeOutcome.messageIdHex, 24)} finalized at height {proposeOutcome.height}. Outcome {outcomeText(proposeOutcome)}
                {#if proposeOutcome.result?.record.resultCode === 'ACCEPTED'}<button class="button-quiet ml-2 min-h-0 py-1" type="button" onclick={() => { activeView = 'proposals'; void openProposal(proposeOutcome!.result!.record.subjectId); }}>Open</button>{/if}
              </div>
            {/if}
          {/if}
        </section>

      {:else if activeView === 'directory'}
        <section class="glass p-5">
          <div class="eyebrow">Directory</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Organizations, actors, and policies</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
            Current records resolved through their current pointer and read at one committed height. The
            chain has no listing query, so identifiers come from the runtime configuration hints and from
            commands seen in the scanned blocks; look up any other id below.
          </p>
          <form class="mt-5 flex flex-wrap items-end gap-3" onsubmit={(event) => { event.preventDefault(); void lookupDirectory(); }}>
            <div><label class="text-xs font-semibold text-[#a9beb7]" for="dir-kind">Kind</label>
              <select id="dir-kind" class="field mt-2" bind:value={dirLookupKind}><option value="actor">Actor</option><option value="organization">Organization</option><option value="policy">Policy</option></select></div>
            <div class="min-w-56 flex-1"><label class="text-xs font-semibold text-[#a9beb7]" for="dir-id">Identifier</label><input id="dir-id" class="field mono mt-2" bind:value={dirLookupId} placeholder="auditor-a" /></div>
            <button class="button-secondary" type="submit" disabled={dirBusy || !dirLookupId.trim()}>Look up</button>
            <button class="button-quiet" type="button" disabled={dirBusy} onclick={loadDirectory}>{dirBusy ? 'Reading…' : 'Reload known records'}</button>
          </form>
          {#if dirError}<div class="notice notice-error mt-4">{dirError}</div>{/if}
          {#if stats}
            <div class="mt-5 grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
              {#each Object.entries(stats.record) as [name, value]}<div class="metric"><div class="metric-label">{name}</div><div class="metric-value">{value}</div></div>{/each}
            </div>
            <div class="mt-2 text-xs text-[#789087]"><span class="pill {proofPill(stats.proof)}">{stats.proof}</span> statistics {stats.detail}</div>
          {/if}
          <div class="metric-label mt-5">Actors</div>
          <div class="table-wrap mt-2">
            <table class="data-table">
              <thead><tr><th>Actor</th><th>Organization</th><th>Roles</th><th>Active key</th><th>Status</th><th>Proof</th></tr></thead>
              <tbody>
                {#each actors as bound}
                  <tr><td class="mono">{bound.record.actorId} r{bound.record.revision}</td><td>{bound.record.organizationId}</td><td>{bound.record.roles.join(', ')}</td>
                    <td class="mono">{bound.record.keys.filter((key) => key.status === 'ACTIVE').map((key) => `${key.keyId} ${short(key.publicKeyHex, 14)}`).join(', ')}</td>
                    <td>{bound.record.status}</td><td><span class="pill {proofPill(bound.proof)}">{bound.proof}</span></td></tr>
                {/each}
                {#if !actors.length}<tr><td colspan="6" class="text-[#789087]">{dirBusy ? 'Reading…' : 'No actor records loaded.'}</td></tr>{/if}
              </tbody>
            </table>
          </div>
          <div class="metric-label mt-5">Organizations</div>
          <div class="table-wrap mt-2">
            <table class="data-table">
              <thead><tr><th>Organization</th><th>Revision</th><th>Status</th><th>Proof</th></tr></thead>
              <tbody>
                {#each organizations as bound}<tr><td class="mono">{bound.record.organizationId}</td><td>{bound.record.revision}</td><td>{bound.record.status}</td><td><span class="pill {proofPill(bound.proof)}">{bound.proof}</span></td></tr>{/each}
                {#if !organizations.length}<tr><td colspan="4" class="text-[#789087]">No organization records loaded.</td></tr>{/if}
              </tbody>
            </table>
          </div>
          <div class="metric-label mt-5">Policies</div>
          <div class="table-wrap mt-2">
            <table class="data-table">
              <thead><tr><th>Policy</th><th>Proposers</th><th>Clauses</th><th>Rejection</th><th>Max lifetime</th><th>Proof</th></tr></thead>
              <tbody>
                {#each policies as bound}
                  <tr><td class="mono">{bound.record.policyId} r{bound.record.revision}</td><td>{bound.record.proposerRoles.join(', ')}</td>
                    <td>{bound.record.clauses.map((clause) => `${clause.clauseId}: ${clause.minimumCount} ${clause.role} distinct by ${clause.distinctBy.toLowerCase()}`).join('; ')}</td>
                    <td>{bound.record.rejectionMode}</td><td>{bound.record.maximumLifetimeBlocks} blocks</td><td><span class="pill {proofPill(bound.proof)}">{bound.proof}</span></td></tr>
                {/each}
                {#if !policies.length}<tr><td colspan="6" class="text-[#789087]">No policy records loaded.</td></tr>{/if}
              </tbody>
            </table>
          </div>
        </section>

      {:else if activeView === 'key'}
        <section class="glass p-5">
          <div class="eyebrow">Actor key</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">Sign as a registered actor</h1>
          <p class="mt-3 max-w-2xl text-sm leading-6 text-[#8ea8a0]">
            The seed is imported into this tab's WebCrypto as a non-extractable key and matched against the
            actor's ACTIVE key epoch on {selectedChainId}. It is never sent anywhere and is discarded when you
            change chain, change node, or release it here.
          </p>
          {#if signingUnavailable}
            <div class="notice notice-warn mt-4">This browser has no WebCrypto Ed25519. Sign with <span class="mono">yano appchain role sign</span> and submit the printed hex through the CLI instead.</div>
          {/if}
          {#if identity && identityActor}
            <div class="panel mt-5 p-4 text-xs">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <div><div class="metric-label">Signing as</div><div class="mono mt-1 text-sm text-[#d8e9e3]">{identity.actor.actorId} r{identity.actor.revision} · key {identity.keyId}</div></div>
                <span class="pill {proofPill(identityActor.proof)}">actor record {identityActor.proof}</span>
              </div>
              <div class="mt-3 grid gap-2 sm:grid-cols-2 text-[#b7cbc4]">
                <div>organization <span class="mono">{identity.actor.organizationId}</span></div>
                <div>roles <span class="mono">{identity.actor.roles.join(', ')}</span></div>
                <div class="sm:col-span-2">public key <CopyValue value={identity.publicKeyHex} width={40} label="public key" /></div>
              </div>
              <button class="button-danger mt-4" type="button" onclick={releaseSigner}>Release the key</button>
            </div>
          {:else}
            <form class="mt-5 grid gap-4 lg:grid-cols-2" onsubmit={(event) => { event.preventDefault(); void loadKey(); }}>
              <div>
                <label class="text-xs font-semibold text-[#a9beb7]" for="key-actor">Actor id</label>
                <input id="key-actor" class="field mono mt-2" bind:value={keyActorId} list="known-actors" placeholder="auditor-a" required />
                <datalist id="known-actors">{#each [...(hints.actors ?? []), ...actorIds(scan?.commands ?? [])] as id}<option value={id}></option>{/each}</datalist>
              </div>
              <div>
                <label class="text-xs font-semibold text-[#a9beb7]" for="key-seed">Ed25519 seed <span class="muted">64 hex characters</span></label>
                <input id="key-seed" class="field mono mt-2" type="password" autocomplete="off" bind:value={seedText} required />
                <label class="mt-2 block text-[.72rem] text-[#71887f]" for="key-file">or choose a seed file <input id="key-file" class="mt-1 text-xs" type="file" onchange={chooseSeedFile} /></label>
              </div>
              {#if keyError}<div class="notice notice-error lg:col-span-2">{keyError}</div>{/if}
              <div class="lg:col-span-2"><button class="button-primary" type="submit" disabled={keyBusy || signingUnavailable}>{keyBusy ? 'Matching the key…' : 'Load and match'}</button></div>
            </form>
          {/if}
        </section>

      {:else}
        <section class="glass p-5">
          <div class="eyebrow">Proof story</div>
          <h1 class="mb-0 mt-2 text-2xl font-semibold tracking-tight">What the desk proves, and what it does not</h1>
          <div class="mt-4 grid gap-4 text-sm leading-6 text-[#8ea8a0] lg:grid-cols-2">
            <div class="panel p-4"><div class="metric-label">Records</div><p class="mt-2">Every organization, actor, policy, proposal, result, receipt, and document head is read from the chain's authenticated state through the state proof endpoint, decoded as canonical CBOR, and shown with the height and root it was committed at. <span class="pill pill-ok">BOUND</span> means the proof names that key, height, and root and the finalized block at that height carries the same root.</p></div>
            <div class="panel p-4"><div class="metric-label">Decisions</div><p class="mt-2">A decision is an Ed25519 signature by the actor's registered key over a statement that binds chain, proposal, policy revision, payload domain and hash, deadline, actor revision, key id, and clause. The chain records the outcome of every finalized statement; the desk reads that record rather than assuming acceptance.</p></div>
            <div class="panel p-4"><div class="metric-label">Release</div><p class="mt-2">On the document-review chain the release command hashes to the proposal's payload hash; the workflow applies it once, appends to the document trail, and writes a consumption receipt. The desk shows receipt, head, and their proofs separately, never as one indicator.</p></div>
            <div class="panel p-4"><div class="metric-label">Not in the browser</div><p class="mt-2">The MPF proof path and threshold finality are verified by the JVM verifier on the export bundle, as with Attest certificates. Connector effects (S3, IPFS, Kafka) for the role-evidence profile are shown from committed terminal outcomes only; the derived business status stays with the evidence product's tooling.</p></div>
          </div>
        </section>
      {/if}
    </main>
  </div>
{/if}
