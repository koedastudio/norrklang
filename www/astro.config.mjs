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
});
