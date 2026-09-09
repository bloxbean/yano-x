<script lang="ts">
  import { CONNECTION_DEFAULTS, connectionFromEndpoint, createConnection } from '../config';
  import type { ActiveConnection, RuntimeConfig } from '../types';

  export let config: RuntimeConfig;
  export let busy = false;
  export let error = '';
  export let onconnect: (connection: ActiveConnection) => void;
  export let onverifyoffline: () => void;

  let nodeUrl = CONNECTION_DEFAULTS.nodeUrl;
  let apiPrefix = CONNECTION_DEFAULTS.apiPrefix;
  let apiKey = CONNECTION_DEFAULTS.apiKey;
  let localError = '';

  function connectManual() {
    localError = '';
    try {
      onconnect(createConnection(nodeUrl, apiPrefix, apiKey));
    } catch (cause) {
      localError = cause instanceof Error ? cause.message : 'The node connection is invalid';
    }
  }
</script>

<section class="mx-auto grid min-h-[76vh] max-w-6xl items-center gap-8 py-10 lg:grid-cols-[1.05fr_.95fr]">
  <div class="px-2 lg:px-8">
    <div class="eyebrow">Yano X product interface</div>
    <h1 class="mt-5 max-w-3xl text-4xl font-semibold leading-[1.06] tracking-[-.045em] text-white sm:text-6xl">
      Prove a document existed,<br /><span class="text-mint-400">without trusting the server.</span>
    </h1>
    <p class="mt-6 max-w-xl text-base leading-7 text-[#8ea8a0]">
      Attest hashes a file in your browser, records the digest on a threshold-finalized Yano app
      chain, and hands you a portable certificate. Anyone can verify it offline against the chain's
      member keys or its Cardano anchor. The file itself never leaves this tab.
    </p>
    <div class="mt-8 grid max-w-xl grid-cols-3 gap-3">
      <div class="panel p-4"><div class="eyebrow">01</div><div class="mt-2 text-sm">Connect node</div></div>
      <div class="panel p-4"><div class="eyebrow">02</div><div class="mt-2 text-sm">Attest a file</div></div>
      <div class="panel p-4"><div class="eyebrow">03</div><div class="mt-2 text-sm">Share the certificate</div></div>
    </div>
    <div class="mt-6">
      <button class="button-quiet" type="button" onclick={onverifyoffline}>Verify a certificate without connecting</button>
    </div>
  </div>

  <div class="glass p-5 sm:p-7">
    <div class="flex items-start justify-between gap-4">
      <div>
        <div class="eyebrow">Connection</div>
        <h2 class="mb-0 mt-2 text-xl font-semibold">Open a Yano installation</h2>
      </div>
      <span class="pill">Not connected</span>
    </div>

    {#if config.endpoints.length}
      <div class="mt-6">
        <div class="text-xs font-semibold text-[#a9beb7]">Configured nodes</div>
        <div class="mt-2 grid gap-2">
          {#each config.endpoints as endpoint}
            <button
              class="button-secondary justify-between"
              type="button"
              disabled={busy}
              onclick={() => onconnect(connectionFromEndpoint(endpoint))}
            >
              <span>{endpoint.label || endpoint.id}</span>
              <span class="mono text-[.68rem] opacity-70">{endpoint.nodeUrl}</span>
            </button>
          {/each}
        </div>
      </div>
    {/if}

    {#if config.allowEndpointOverride}
      <form class="mt-6 space-y-4" onsubmit={(event) => { event.preventDefault(); connectManual(); }}>
        <div>
          <label class="text-xs font-semibold text-[#a9beb7]" for="node-url">Yano node URL</label>
          <input
            id="node-url"
            class="field mt-2"
            type="url"
            autocomplete="url"
            bind:value={nodeUrl}
            placeholder="https://node.example.com"
            required
          />
          <p class="mb-0 mt-2 text-[.72rem] text-[#71887f]">Enter the origin only. Remote nodes must use HTTPS and allow this UI origin through CORS.</p>
        </div>
        <div class="grid gap-4 sm:grid-cols-2">
          <div>
            <label class="text-xs font-semibold text-[#a9beb7]" for="api-prefix">API prefix</label>
            <input id="api-prefix" class="field mono mt-2" bind:value={apiPrefix} />
          </div>
          <div>
            <label class="text-xs font-semibold text-[#a9beb7]" for="api-key">API key <span class="muted">optional</span></label>
            <input id="api-key" class="field mt-2" type="password" autocomplete="off" bind:value={apiKey} placeholder="Kept in memory" />
          </div>
        </div>
        {#if localError || error}<div class="notice notice-error">{localError || error}</div>{/if}
        <button class="button-primary w-full" type="submit" disabled={busy}>
          {busy ? 'Verifying node and discovering chains…' : 'Connect and discover'}
        </button>
      </form>
    {:else if error}
      <div class="notice notice-error mt-5">{error}</div>
    {/if}

    <div class="mt-6 border-t border-white/[.07] pt-4 text-[.72rem] leading-5 text-[#71887f]">
      The UI reads the live node identity and lists only chains that run the doc-trail state
      machine. It never uploads your documents; only their SHA-256 digest is recorded. An API key,
      when supplied, stays in this browser tab.
    </div>
  </div>
</section>
