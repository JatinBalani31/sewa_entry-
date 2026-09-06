/**
 * Generates the app icons as real PNGs, with no image library.
 *
 * The mark is a navy rounded square holding a white tick — attendance marked.
 * Everything is rasterised from geometry, so the icons can be regenerated at
 * any size:
 *
 *   node scripts/generate-icons.mjs
 */
import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const OUT_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'public');

const NAVY = [31, 58, 95];
const WHITE = [255, 255, 255];

// --- Tiny PNG encoder ------------------------------------------------------

function crc32(buf) {
  let crc = ~0;
  for (const byte of buf) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (0xEDB88320 & -(crc & 1));
  }
  return ~crc >>> 0;
}

function chunk(type, data) {
  const typeAndData = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(typeAndData));
  return Buffer.concat([length, typeAndData, crc]);
}

/** `pixels` is RGBA, row-major, size*size*4 bytes. */
function encodePng(size, pixels) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0);
  header.writeUInt32BE(size, 4);
  header[8] = 8;  // bit depth
  header[9] = 6;  // colour type: RGBA
  // 10-12 stay zero: deflate, adaptive filtering, no interlace.

  // Each scanline is prefixed with its filter byte; filter 0 (none) is plenty
  // for flat artwork and keeps this encoder simple.
  const stride = size * 4;
  const raw = Buffer.alloc((stride + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (stride + 1)] = 0;
    pixels.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// --- Geometry --------------------------------------------------------------

/** Signed coverage of a rounded rectangle, sampled for antialiasing. */
function insideRoundedSquare(x, y, size, radius, inset) {
  const min = inset;
  const max = size - inset;
  const cx = Math.min(Math.max(x, min + radius), max - radius);
  const cy = Math.min(Math.max(y, min + radius), max - radius);
  if (x < min || x > max || y < min || y > max) return false;
  return Math.hypot(x - cx, y - cy) <= radius;
}

/**
 * Distance from a point to a line segment — the primitive the tick is built
 * from. Two thick round-capped strokes make a clean check at any size.
 */
function distanceToSegment(px, py, ax, ay, bx, by) {
  const dx = bx - ax;
  const dy = by - ay;
  const lengthSquared = dx * dx + dy * dy;
  const t = lengthSquared === 0
    ? 0
    : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lengthSquared));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

/** A tick: a short down-stroke into a long up-stroke. */
function insideTick(x, y, size) {
  const width = size * 0.085;
  const short = distanceToSegment(x, y, size * 0.28, size * 0.52, size * 0.44, size * 0.68);
  const long = distanceToSegment(x, y, size * 0.44, size * 0.68, size * 0.74, size * 0.33);
  return Math.min(short, long) <= width;
}

function renderIcon(size, { padded }) {
  const pixels = Buffer.alloc(size * size * 4);
  // A maskable icon must keep its content inside the safe zone, because the
  // launcher is free to crop the corners into a circle.
  const scale = padded ? 0.78 : 1;
  const offset = (size * (1 - scale)) / 2;

  const SAMPLES = 3; // 3x3 supersampling — enough to kill the jaggies
  const step = 1 / SAMPLES;

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let bg = 0;
      let fg = 0;

      for (let sy = 0; sy < SAMPLES; sy++) {
        for (let sx = 0; sx < SAMPLES; sx++) {
          const px = x + (sx + 0.5) * step;
          const py = y + (sy + 0.5) * step;

          if (insideRoundedSquare(px, py, size, size * 0.22, 0)) bg++;

          const lx = (px - offset) / scale;
          const ly = (py - offset) / scale;
          if (insideTick(lx, ly, size)) fg++;
        }
      }

      const total = SAMPLES * SAMPLES;
      const bgAlpha = bg / total;
      const fgAlpha = fg / total;

      const [r, g, b] = blend(NAVY, WHITE, fgAlpha);
      const index = (y * size + x) * 4;
      pixels[index] = r;
      pixels[index + 1] = g;
      pixels[index + 2] = b;
      pixels[index + 3] = Math.round(255 * bgAlpha);
    }
  }

  return encodePng(size, pixels);
}

function blend(from, to, t) {
  return from.map((channel, i) => Math.round(channel + (to[i] - channel) * t));
}

// --- Output ----------------------------------------------------------------

mkdirSync(OUT_DIR, { recursive: true });

const targets = [
  ['icon-192.png', 192, { padded: false }],
  ['icon-512.png', 512, { padded: false }],
  ['icon-maskable-512.png', 512, { padded: true }],
  ['apple-touch-icon.png', 180, { padded: false }],
  ['favicon-32.png', 32, { padded: false }],
];

for (const [name, size, options] of targets) {
  const png = renderIcon(size, options);
  writeFileSync(join(OUT_DIR, name), png);
  console.log(`  ${name.padEnd(24)} ${size}x${size}  ${(png.length / 1024).toFixed(1)} kB`);
}

console.log(`\nWrote ${targets.length} icons to web/public/`);
