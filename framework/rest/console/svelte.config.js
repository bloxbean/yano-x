import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),
  kit: {
    csp: {
      mode: 'hash',
      directives: {
        'default-src': ['self'],
        'script-src': ['self'],
        'style-src': ['self'],
        // SvelteKit renders the app wrapper and its accessibility announcer with
        // bounded style attributes. Keep style elements external/hash-only while
        // allowing those framework-owned attributes to render correctly.
        'style-src-attr': ['unsafe-inline'],
        'connect-src': ['self', 'http:', 'https:'],
        'img-src': ['self', 'data:'],
        'object-src': ['none'],
        'base-uri': ['none'],
        'form-action': ['self']
      }
    },
    adapter: adapter({
      pages: '../build/generated/console/META-INF/resources/ui/app-chain',
      assets: '../build/generated/console/META-INF/resources/ui/app-chain',
      fallback: undefined,
      precompress: false,
      strict: true
    }),
    // Served by framework/rest next to Yano's console at /ui/, which it links back to.
    paths: { base: '/ui/app-chain' },
    prerender: {
      crawl: false,
      entries: ['*']
    }
  }
};

export default config;
