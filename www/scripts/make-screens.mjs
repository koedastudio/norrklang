/*
 * Regenerates src/assets/screen-*.webp from the portrait Play screenshots in
 * ../assets/screenshots. Run `node scripts/make-screens.mjs` after the Play
 * set changes; the output is committed — CI does not run this.
 */
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import sharp from 'sharp';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const src = join(root, '..', 'assets', 'screenshots');
const out = join(root, 'src', 'assets');

const screens = {
  'portrait-01-home.png': 'screen-home.webp',
  'portrait-02-now-playing.png': 'screen-player.webp',
  'portrait-04-library.png': 'screen-library.webp',
};

for (const [from, to] of Object.entries(screens)) {
  const info = await sharp(join(src, from)).webp({ quality: 82 }).toFile(join(out, to));
  console.log(`wrote ${to} (${(info.size / 1024).toFixed(1)} kB, ${info.width}x${info.height})`);
}
