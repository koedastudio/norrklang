/*
 * Regenerates public/og.png (the 1200x630 social card) from the site's mark
 * and palette, and public/apple-touch-icon.png (180px, square — iOS applies
 * its own corner mask) from ../assets/icon.svg. Run `node scripts/make-og.mjs`
 * after tagline, mark or colour changes; the output is committed — CI does
 * not run this.
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

// The mark's 1024-unit grid (same geometry as src/components/Mark.astro),
// scaled and translated into the card.
const mark = (x, y, size) => `
  <g transform="translate(${x} ${y}) scale(${size / 1024})">
    <rect width="1024" height="1024" rx="230" fill="#C6F0EA"/>
    <path d="M0 512H1024V794A230 230 0 0 1 794 1024H230A230 230 0 0 1 0 794Z" fill="#0DA1A6"/>
    <path d="M512 102L922 512H102Z" fill="#1FB8B0"/>
    <g fill="#38D7DD">
      <path d="M102 512H172V582Z"/>
      <path d="M227 512H297V707L227 637Z"/>
      <path d="M352 512H422V832L352 762Z"/>
      <path d="M477 512H547V887L512 922L477 887Z"/>
      <path d="M602 512H672V762L602 832Z"/>
      <path d="M727 512H797V637L727 707Z"/>
      <path d="M852 512H922L852 582Z"/>
    </g>
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
  ${mark(96, 90, 140)}
  <text x="96" y="330" fill="${TEXT}" font-family="Helvetica, Arial, sans-serif" font-size="86" font-weight="600" letter-spacing="-2">Norrklang</text>
  <text x="96" y="410" fill="${ACCENT_BRIGHT}" font-family="Helvetica, Arial, sans-serif" font-size="40" font-weight="500">${TAGLINE}</text>
  <text x="96" y="500" fill="${MUTED}" font-family="Helvetica, Arial, sans-serif" font-size="28">Navidrome &#183; Plex &#183; Jellyfin &#183; Android Automotive OS &#183; no analytics</text>
  <rect x="96" y="545" width="120" height="4" rx="2" fill="${ACCENT}"/>
</svg>`;

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const out = join(root, 'public', 'og.png');
const png = await sharp(Buffer.from(svg)).png({ compressionLevel: 9 }).toBuffer();
writeFileSync(out, png);
console.log(`wrote ${out} (${(png.length / 1024).toFixed(1)} kB)`);

const touch = join(root, 'public', 'apple-touch-icon.png');
const touchPng = await sharp(join(root, '..', 'assets', 'icon.svg'))
  .resize(180, 180)
  .png({ compressionLevel: 9 })
  .toBuffer();
writeFileSync(touch, touchPng);
console.log(`wrote ${touch} (${(touchPng.length / 1024).toFixed(1)} kB)`);
