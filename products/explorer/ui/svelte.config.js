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
        'style-src-attr': ['unsafe-inline'],
        'connect-src': ['self', 'http:', 'https:'],
        'img-src': ['self', 'data:'],
        'font-src': ['self'],
        'object-src': ['none'],
        'base-uri': ['none'],
        'form-action': ['self'],
        'frame-ancestors': ['none']
      }
    },
    adapter: adapter({
      pages: 'build/site',
      assets: 'build/site',
      fallback: undefined,
      precompress: true,
      strict: true
    }),
    paths: { relative: true },
    prerender: {
      crawl: true,
      entries: ['*']
    }
  }
};

export default config;
