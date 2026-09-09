<script lang="ts">
  import { SERVICE_DEFAULT, connectionFromEndpoint, createConnection } from '../config';
  import type { ActiveConnection, RuntimeConfig } from '../types';

  export let config: RuntimeConfig;
  export let busy = false;
  export let error = '';
  export let onconnect: (connection: ActiveConnection) => void;

  let serviceUrl = config.serviceUrl || SERVICE_DEFAULT;
  let localError = '';

  function connectManual() {
    localError = '';
    try {
      onconnect(createConnection(serviceUrl));
    } catch (cause) {
      localError = cause instanceof Error ? cause.message : 'The service URL is invalid';
    }
  }
</script>

<section class="mx-auto grid min-h-[76vh] max-w-6xl items-center gap-8 py-10 lg:grid-cols-[1.05fr_.95fr]">
  <div class="px-2 lg:px-8">
    <div class="eyebrow">Yano X product interface</div>
    <h1 class="mt-5 max-w-3xl text-4xl font-semibold leading-[1.06] tracking-[-.045em] text-white sm:text-6xl">
      Browse the chain,<br /><span class="text-mint-400">prove every row.</span>
    </h1>
    <p class="mt-6 max-w-xl text-base leading-7 text-[#8ea8a0]">
      The Verifiable Explorer reads a derived index that a yano-explorer service builds from a
      node's certified blocks: timelines, decoded commands, entity trails, and search. Every row
      keeps its evidence, and any row can be verified in this browser and in the CLI. The index
      adds convenience, never trust.
    </p>
    <div class="mt-8 grid max-w-xl grid-cols-3 gap-3">
      <div class="panel p-4"><div class="eyebrow">01</div><div class="mt-2 text-sm">Open a service</div></div>
      <div class="panel p-4"><div class="eyebrow">02</div><div class="mt-2 text-sm">Browse and search</div></div>
      <div class="panel p-4"><div class="eyebrow">03</div><div class="mt-2 text-sm">Verify a row</div></div>
    </div>
  </div>

  <div class="glass p-6 sm:p-8">
    <div class="eyebrow">Connection</div>
    <h2 class="mt-2 text-xl font-semibold tracking-tight">Open a yano-explorer service</h2>
    <div class="mt-3"><span class="pill pill-warn">not connected</span></div>

    {#if config.endpoints.length}
      <div class="mt-6">
        <div class="text-xs font-semibold text-[#a9beb7]">Configured services</div>
        <div class="mt-2 grid gap-2">
          {#each config.endpoints as endpoint}
            <button
              class="button-secondary justify-between"
              type="button"
              disabled={busy}
              onclick={() => onconnect(connectionFromEndpoint(endpoint))}
            >
              <span>{endpoint.label || endpoint.id}</span>
              <span class="mono text-[.68rem] opacity-70">{endpoint.serviceUrl}</span>
            </button>
          {/each}
        </div>
      </div>
    {/if}

    {#if config.allowServiceOverride}
      <form class="mt-6 space-y-4" onsubmit={(event) => { event.preventDefault(); connectManual(); }}>
        <div>
          <label class="text-xs font-semibold text-[#a9beb7]" for="service-url">Service URL</label>
          <input
            id="service-url"
            class="field mt-2"
            type="url"
            autocomplete="url"
            bind:value={serviceUrl}
            placeholder="https://explorer.example.com"
            required
          />
          <p class="mb-0 mt-2 text-[.72rem] text-[#71887f]">The base URL of a running <span class="mono">yano-explorer serve</span>. Remote services must use HTTPS.</p>
        </div>
        {#if localError || error}<div class="notice notice-error">{localError || error}</div>{/if}
        <button class="button-primary w-full" type="submit" disabled={busy}>
          {busy ? 'Reading the index…' : 'Connect and list chains'}
        </button>
      </form>
    {:else if error}
      <div class="notice notice-error mt-5">{error}</div>
    {/if}

    <div class="mt-6 border-t border-white/[.07] pt-4 text-[.72rem] leading-5 text-[#71887f]">
      The service is read-only and holds the node API key on its side; this console sends no
      credentials and stores nothing.
    </div>
  </div>
</section>
