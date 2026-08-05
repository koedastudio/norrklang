# norrklang.app

The Norrklang website: a landing page and the privacy policy. Astro, static
output, zero client-side JavaScript. `www/` is self-contained, like
`android/` — the repo root has no `package.json`.

## Develop

Requires Node 22.18+ (`scripts/make-og.mjs` imports `src/config.ts` and needs
native type stripping).

```bash
npm install
npm run dev       # http://localhost:4321, hot reload
npm run build     # → dist/
npm run preview   # serves dist/ through the real Workers runtime (wrangler dev)
npm run check     # astro check — also run in CI
```

Use `preview`, not `dev`, to verify anything edge-dependent: `public/_headers`,
the 404 page, trailing-slash handling.

**Privacy policy**: `src/pages/privacy.md` is canonical. Its URL
(`https://norrklang.app/privacy`) is declared in the Google Play listing and
linked from the app's Settings (`PRIVACY_POLICY_URL` in
`android/core-ui/.../SettingsRows.kt`). Play requires it to stay reachable —
never duplicate or move it.

**Social card**: `public/og.png` is generated — after changing the tagline or
brand colours, run `node scripts/make-og.mjs` and commit the result.

## Deploy

**Cloudflare Workers Builds** deploys on every push to `main` touching `www/`
— nothing to run, no API token to manage. GitHub Actions
(`.github/workflows/www.yml`) only runs a build check so a broken site fails
the PR; it holds no secrets. Manual deploy (rarely needed): `npm run deploy`
after `npx wrangler login`.

One-time setup:

1. **Import the repo** — dash.cloudflare.com → Workers & Pages → Create →
   Import a repository → pick the repo. Worker name `norrklang-www` (must
   match `wrangler.jsonc`), root directory `www`, build command
   `npm run build`, deploy command `npx wrangler deploy`, build watch path
   `www/*` (stops Android commits from rebuilding the site).
2. **Attach the domain** — the Worker's Settings → Domains & Routes → Add
   custom domain → `norrklang.app`. Cloudflare creates the DNS record and
   certificate; don't add a CNAME by hand. (`.app` is HSTS-preloaded — HTTPS
   only, covered by Universal SSL once this step is done.)
3. **`www.` redirect** — Rules → Redirect Rules → `www.norrklang.app/*` →
   301 to `https://norrklang.app/$1`.

## Notes

- **CSP is `default-src 'none'`** (`public/_headers`) — possible only because
  the site ships zero JavaScript and `inlineStylesheets: 'never'` keeps styles
  external. Adding a script means loosening the CSP deliberately, not deleting it.
- **`html_handling: "drop-trailing-slash"`** in `wrangler.jsonc` matches
  Astro's `trailingSlash: 'never'`; the default would 307 every canonical URL.
- **The `overrides` in `package.json`** work around transitive pins from
  miniflare (via wrangler): `sharp` is pinned to a version with no Node 24
  prebuilt binary (only `scripts/make-og.mjs` uses it, never the site), and
  `undici` to a version `npm audit` flags. Both overrides move to versions the
  dependents already accept; drop them once wrangler catches up.
