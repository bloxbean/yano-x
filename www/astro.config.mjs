// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import { unified } from '@astrojs/markdown-remark';
import llmsIntegration from './scripts/llms-integration.mjs';
import remarkMermaid from './scripts/remark-mermaid.mjs';

export default defineConfig({
  site: 'https://yanox.dev',
  markdown: {
    processor: unified({ remarkPlugins: [remarkMermaid] }),
  },
  integrations: [
    starlight({
      title: 'Yano X',
      description:
        'Build an application-specific replicated ledger on Cardano. Yano X is the ' +
        'JVM extension ecosystem for Yano app chains.',
      logo: {
        src: './public/logo.svg',
        alt: 'Yano X',
        replacesTitle: false,
      },
      favicon: '/favicon.svg',
      social: [
        { icon: 'github', label: 'GitHub', href: 'https://github.com/bloxbean/yano-x' },
      ],
      editLink: {
        baseUrl: 'https://github.com/bloxbean/yano-x/edit/main/www/',
      },
      components: {
        Head: './src/components/overrides/Head.astro',
      },
      customCss: ['./src/styles/starlight.css'],
      lastUpdated: true,
      sidebar: [
        {
          label: 'Start here',
          items: [
            { label: 'What is an app chain?', slug: 'start-here/what-is-an-app-chain' },
            { label: 'Why Yano X', slug: 'start-here/why-yano-x' },
            { label: 'Release downloads', slug: 'start-here/release-downloads' },
            { label: 'Local showcase', slug: 'start-here/quickstart' },
            { label: 'Choose a deployment path', slug: 'deployment' },
            { label: 'Build from source', slug: 'start-here/build-from-source' },
          ],
        },
        {
          label: 'Deployment',
          autogenerate: { directory: 'deployment' },
        },
        {
          label: 'Concepts',
          items: [
            { label: 'Architecture', slug: 'concepts/architecture' },
            { label: 'Consensus and finality', slug: 'concepts/consensus-and-finality' },
            { label: 'State and proofs', slug: 'concepts/state-and-proofs' },
            { label: 'Effects', slug: 'concepts/effects' },
            { label: 'Cardano anchoring', slug: 'concepts/anchoring' },
            { label: 'Determinism rules', slug: 'concepts/determinism-rules' },
          ],
        },
        {
          label: 'Recipes',
          items: [
            { label: 'Recipe catalog', slug: 'recipes' },
            { label: 'Choosing a recipe', slug: 'recipes/choosing-a-recipe' },
          ],
        },
        {
          label: 'Plugin framework',
          items: [
            { label: 'The extension ladder', slug: 'plugins' },
            { label: 'Scaffold, sign, install', slug: 'plugins/scaffold-sign-install' },
            { label: 'SPI and manifest', slug: 'plugins/spi-and-manifest' },
            { label: 'Consensus rules', slug: 'plugins/consensus-rules' },
            { label: 'Testing and deployment', slug: 'plugins/testing-and-deployment' },
          ],
        },
        {
          label: 'Tutorials',
          autogenerate: { directory: 'tutorials' },
        },
        {
          label: 'State machines',
          collapsed: true,
          autogenerate: { directory: 'state-machines' },
        },
        {
          label: 'Products',
          collapsed: true,
          items: [
            { label: 'Overview', slug: 'products' },
            { label: 'Evidence', slug: 'products/evidence' },
            { label: 'Cardano History', slug: 'products/cardano-history' },
            { label: 'Attest', slug: 'products/attest' },
            { label: 'Evidence Desk', slug: 'products/evidence-desk' },
            { label: 'Trust Registry', slug: 'products/trust-registry' },
            { label: 'Verifiable Explorer', slug: 'products/explorer' },
            { label: 'DPP Starter', slug: 'products/dpp-starter' },
            { label: 'Attestation Feed', slug: 'products/attestation-feed' },
            {
              label: 'eUTxO and ZK',
              slug: 'products/eutxo-and-zk',
              badge: { text: 'Experimental', variant: 'caution' },
            },
          ],
        },
        {
          label: 'AI agents',
          items: [
            { label: 'Using Yano X with AI', slug: 'ai' },
            { label: 'AI Starter Pack', slug: 'ai/starter-pack' },
          ],
        },
        {
          label: 'Reference',
          collapsed: true,
          items: [
            { label: 'CLI', slug: 'reference/cli' },
            { label: 'REST API', slug: 'reference/rest-api' },
            { label: 'Capability catalog', slug: 'reference/capabilities' },
            { label: 'Configuration', slug: 'reference/configuration' },
            { label: 'Modules and artifacts', slug: 'reference/modules' },
            { label: 'Reference shelf', slug: 'reference/shelf' },
          ],
        },
        {
          label: 'Contributing',
          items: [{ label: 'Developing Yano X', slug: 'contributing' }],
        },
      ],
    }),
    llmsIntegration(),
  ],
});
