// @ts-check
import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';

export default defineConfig({
  site: 'https://norrklang.app',
  trailingSlash: 'never',
  integrations: [sitemap()],
  build: {
    // Pairs with the Worker's `html_handling: drop-trailing-slash`: /privacy is
    // served from privacy/index.html, and canonical/og:url never see ".html".
    format: 'directory',
    // Inlining would force `style-src 'unsafe-inline'` in public/_headers.
    inlineStylesheets: 'never',
  },
  vite: {
    build: {
      // Same reason for scripts: Astro inlines small ones, which the CSP's
      // `script-src 'self'` would block. Always emit them as files.
      assetsInlineLimit: 0,
    },
  },
});
