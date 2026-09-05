<script lang="ts">
  import { short } from '../model';

  export let value = '';
  export let label = 'value';
  export let width = 30;
  export let full = false;

  let copied = false;

  async function copy() {
    if (!value) return;
    await navigator.clipboard.writeText(value);
    copied = true;
    setTimeout(() => (copied = false), 1200);
  }
</script>

<span class="inline-flex max-w-full items-center gap-1.5" title={value}>
  <span class="mono break-all">{full ? value : short(value, width)}</span>
  {#if value}
    <button class="copy-button shrink-0" type="button" aria-label={`Copy ${label}`} onclick={copy}>
      {copied ? '✓' : '⧉'}
    </button>
  {/if}
</span>
