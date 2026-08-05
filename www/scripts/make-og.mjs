/*
 * Regenerates public/og.png (the 1200x630 social card) from the site's mark
 * and palette. Run `node scripts/make-og.mjs` after tagline or colour changes;
 * the output is committed — CI does not run this.
 */
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import sharp from 'sharp';
// Node 22.18+ strips the types natively; the tagline has one source of truth.
import { TAGLINE } from '../src/config.ts';

const BG = '#0B0D10';
const ACCENT = '#4FD8C4';
const ACCENT_BRIGHT = '#6FE6D6';
const TEXT = '#E8EDF2';
const MUTED = '#97A3B1';

// The mark's 108-unit grid, scaled and translated into the card.
const mark = (x, y, scale) => `
  <g transform="translate(${x} ${y}) scale(${scale})" stroke-width="6" stroke-linecap="round" fill="none">
    <path d="M36 46 L36 62" stroke="${ACCENT}"/>
    <path d="M45 38 L45 70" stroke="${ACCENT}"/>
    <path d="M54 30 L54 78" stroke="${ACCENT_BRIGHT}"/>
    <path d="M63 38 L63 70" stroke="${ACCENT}"/>
    <path d="M72 46 L72 62" stroke="${ACCENT}"/>
  </g>`;

const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="630" viewBox="0 0 1200 630">
  <defs>
    <radialGradient id="glow" cx="50%" cy="0%" r="75%">
      <stop offset="0%" stop-color="${ACCENT}" stop-opacity="0.18"/>
      <stop offset="100%" stop-color="${ACCENT}" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="1200" height="630" fill="${BG}"/>
  <rect width="1200" height="630" fill="url(#glow)"/>
  ${mark(56, 60, 1.75)}
  <text x="96" y="330" fill="${TEXT}" font-family="Helvetica, Arial, sans-serif" font-size="86" font-weight="600" letter-spacing="-2">Norrklang</text>
  <text x="96" y="410" fill="${ACCENT_BRIGHT}" font-family="Helvetica, Arial, sans-serif" font-size="40" font-weight="500">${TAGLINE}</text>
  <text x="96" y="500" fill="${MUTED}" font-family="Helvetica, Arial, sans-serif" font-size="28">Android Automotive OS &#183; no analytics, no third parties</text>
  <rect x="96" y="545" width="120" height="4" rx="2" fill="${ACCENT}"/>
</svg>`;

const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'public', 'og.png');
const png = await sharp(Buffer.from(svg)).png({ compressionLevel: 9 }).toBuffer();
writeFileSync(out, png);
console.log(`wrote ${out} (${(png.length / 1024).toFixed(1)} kB)`);
