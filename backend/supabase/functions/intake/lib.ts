/**
 * Pure helpers for the `intake` Edge Function.
 *
 * Runtime-agnostic on purpose: only Web-standard globals (crypto.subtle, TextEncoder/TextDecoder,
 * atob, ReadableStream) and no imports. The admin panel re-uses this file for its browser-side
 * upload checks (admin/src/lib/media.ts) and its vitest suite tests it under Node
 * (admin/src/lib/intake.test.ts), so the parsing rules are tested without Deno.
 */

export const MAX_IMAGE_BYTES = 8 * 1024 * 1024;
/** Hard cap for the JSON body: base64 of MAX_IMAGE_BYTES (~11.2 MB) plus headroom for metadata. */
export const MAX_BODY_BYTES = 12 * 1024 * 1024;
/** Upper bound on base64 characters (incl. a data: URL prefix and line wrapping) before decoding. */
export const MAX_BASE64_CHARS = Math.ceil(((Math.ceil(MAX_IMAGE_BYTES / 3) * 4) * 1.03)) + 256;

export const MEDIA_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'video/mp4', 'video/webm'] as const;
export type MediaMime = (typeof MEDIA_TYPES)[number];

export const MEDIA_EXT: Readonly<Record<MediaMime, string>> = {
  'image/png': 'png',
  'image/jpeg': 'jpg',
  'image/webp': 'webp',
  'image/gif': 'gif',
  'video/mp4': 'mp4',
  'video/webm': 'webm',
};

export interface SniffResult {
  mime: MediaMime;
  ext: string;
  video: boolean;
}

export interface MediaProbe {
  width: number | null;
  height: number | null;
  /** Frame count when it can be read cheaply from the header; null = unknown (videos). */
  frames: number | null;
  animated: boolean;
}

// ------------------------------------------------------------------ byte readers

function need(b: Uint8Array, o: number, n: number): void {
  if (o < 0 || o + n > b.length) throw new RangeError('truncated');
}
function u8(b: Uint8Array, o: number): number {
  need(b, o, 1);
  return b[o];
}
function u16be(b: Uint8Array, o: number): number {
  need(b, o, 2);
  return (b[o] << 8) | b[o + 1];
}
function u16le(b: Uint8Array, o: number): number {
  need(b, o, 2);
  return b[o] | (b[o + 1] << 8);
}
function u24le(b: Uint8Array, o: number): number {
  need(b, o, 3);
  return b[o] | (b[o + 1] << 8) | (b[o + 2] << 16);
}
function u32be(b: Uint8Array, o: number): number {
  need(b, o, 4);
  return ((b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]) >>> 0;
}
function u32le(b: Uint8Array, o: number): number {
  need(b, o, 4);
  return (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0;
}
/** ASCII text of b[o, o+n), or '' when out of range. */
function ascii(b: Uint8Array, o: number, n: number): string {
  if (o < 0 || o + n > b.length) return '';
  let s = '';
  for (let i = 0; i < n; i++) s += String.fromCharCode(b[o + i]);
  return s;
}
function dim(n: number): number | null {
  return Number.isInteger(n) && n > 0 && n <= 65535 ? n : null;
}

// ------------------------------------------------------------------ magic-byte sniffing

const MP4_BRANDS = new Set([
  'isom', 'iso2', 'iso3', 'iso4', 'iso5', 'iso6', 'iso7', 'iso8', 'iso9', 'mp41', 'mp42', 'mp71', 'avc1',
  'dash', 'M4V ', 'M4VH', 'M4VP', 'MSNV', 'NDAS', 'mmp4', 'f4v ', '3gp4', '3gp5', '3gp6', '3g2a',
]);
/** ISO-BMFF brands that are images or QuickTime, never accepted as video/mp4. */
const NOT_MP4_BRANDS = new Set([
  'avif', 'avis', 'heic', 'heix', 'heim', 'heis', 'hevc', 'hevx', 'mif1', 'msf1', 'miaf', 'jpeg', 'jxl ', 'qt  ', 'crx ',
]);

function result(mime: MediaMime): SniffResult {
  return { mime, ext: MEDIA_EXT[mime], video: mime.startsWith('video/') };
}

/** Detects png/jpeg/webp/gif/mp4/webm from the leading bytes. Anything else returns null. */
export function sniffMedia(b: Uint8Array): SniffResult | null {
  if (b.length >= 8 && b[0] === 0x89 && ascii(b, 1, 3) === 'PNG' && b[4] === 0x0d && b[5] === 0x0a && b[6] === 0x1a && b[7] === 0x0a) {
    return result('image/png');
  }
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return result('image/jpeg');
  const gif = ascii(b, 0, 6);
  if (gif === 'GIF87a' || gif === 'GIF89a') return result('image/gif');
  if (ascii(b, 0, 4) === 'RIFF' && ascii(b, 8, 4) === 'WEBP') return result('image/webp');
  if (b.length >= 4 && b[0] === 0x1a && b[1] === 0x45 && b[2] === 0xdf && b[3] === 0xa3) {
    // EBML header; DocType "webm" sits inside the first few dozen bytes. Plain Matroska is not accepted.
    return ascii(b, 0, Math.min(b.length, 64)).includes('webm') ? result('video/webm') : null;
  }
  if (ascii(b, 4, 4) === 'ftyp') {
    const major = ascii(b, 8, 4);
    if (NOT_MP4_BRANDS.has(major)) return null;
    if (MP4_BRANDS.has(major)) return result('video/mp4');
    let boxEnd = 0;
    try {
      boxEnd = Math.min(u32be(b, 0), b.length, 256);
    } catch {
      return null;
    }
    let sawMp4 = false;
    for (let o = 16; o + 4 <= boxEnd; o += 4) {
      const brand = ascii(b, o, 4);
      if (NOT_MP4_BRANDS.has(brand)) return null;
      if (MP4_BRANDS.has(brand)) sawMp4 = true;
    }
    return sawMp4 ? result('video/mp4') : null;
  }
  return null;
}

/** Normalises a declared MIME type (lower-case, no parameters, common aliases). */
export function normalizeMime(raw: string): string {
  const m = raw.split(';')[0].trim().toLowerCase();
  if (m === 'image/jpg' || m === 'image/pjpeg') return 'image/jpeg';
  if (m === 'image/x-png') return 'image/png';
  return m;
}

export function isMediaMime(m: string): m is MediaMime {
  return (MEDIA_TYPES as readonly string[]).includes(m);
}

// ------------------------------------------------------------------ dimension / frame probing

const UNKNOWN: MediaProbe = { width: null, height: null, frames: null, animated: false };

function probePng(b: Uint8Array): MediaProbe {
  const width = dim(u32be(b, 16));
  const height = dim(u32be(b, 20));
  let frames = 1;
  let off = 8;
  for (let i = 0; i < 256 && off + 8 <= b.length; i++) {
    const len = u32be(b, off);
    const type = ascii(b, off + 4, 4);
    if (type === 'acTL') {
      frames = Math.max(1, u32be(b, off + 8));
      break;
    }
    if (type === 'IDAT' || type === 'IEND') break;
    off += 12 + len;
  }
  return { width, height, frames, animated: frames > 1 };
}

function skipSubBlocks(b: Uint8Array, start: number): number {
  let off = start;
  while (off < b.length) {
    const size = b[off];
    off += 1;
    if (size === 0) return off;
    off += size;
  }
  return b.length;
}

function probeGif(b: Uint8Array): MediaProbe {
  const width = dim(u16le(b, 6));
  const height = dim(u16le(b, 8));
  const packed = u8(b, 10);
  let off = 13;
  if (packed & 0x80) off += 3 * (1 << ((packed & 7) + 1));
  let frames = 0;
  while (off < b.length) {
    const block = b[off];
    if (block === 0x3b) break; // trailer
    if (block === 0x21) {
      off = skipSubBlocks(b, off + 2); // introducer + label, then data sub-blocks
      continue;
    }
    if (block === 0x2c) {
      frames++;
      const p = u8(b, off + 9);
      off += 10;
      if (p & 0x80) off += 3 * (1 << ((p & 7) + 1));
      off = skipSubBlocks(b, off + 1); // LZW minimum code size, then image data
      continue;
    }
    break; // garbage or truncated
  }
  return { width, height, frames: Math.max(frames, 1), animated: frames > 1 };
}

function countRiffChunks(b: Uint8Array, fourcc: string): number {
  let off = 12;
  let n = 0;
  while (off + 8 <= b.length) {
    if (ascii(b, off, 4) === fourcc) n++;
    const size = u32le(b, off + 4);
    off += 8 + size + (size & 1);
  }
  return n;
}

function probeWebp(b: Uint8Array): MediaProbe {
  const chunk = ascii(b, 12, 4);
  if (chunk === 'VP8X') {
    const flags = u8(b, 20);
    const animated = (flags & 0x02) !== 0;
    return {
      width: dim(u24le(b, 24) + 1),
      height: dim(u24le(b, 27) + 1),
      frames: animated ? Math.max(1, countRiffChunks(b, 'ANMF')) : 1,
      animated,
    };
  }
  if (chunk === 'VP8 ') {
    if (u8(b, 23) !== 0x9d || u8(b, 24) !== 0x01 || u8(b, 25) !== 0x2a) return { ...UNKNOWN, frames: 1 };
    return { width: dim(u16le(b, 26) & 0x3fff), height: dim(u16le(b, 28) & 0x3fff), frames: 1, animated: false };
  }
  if (chunk === 'VP8L') {
    if (u8(b, 20) !== 0x2f) return { ...UNKNOWN, frames: 1 };
    const bits = u32le(b, 21);
    return { width: dim((bits & 0x3fff) + 1), height: dim(((bits >>> 14) & 0x3fff) + 1), frames: 1, animated: false };
  }
  return { ...UNKNOWN, frames: 1 };
}

const JPEG_SOF = new Set([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf]);

function probeJpeg(b: Uint8Array): MediaProbe {
  let off = 2;
  for (let guard = 0; off + 4 <= b.length && guard < 100_000; guard++) {
    if (b[off] !== 0xff) {
      off++;
      continue;
    }
    const marker = b[off + 1];
    if (marker === 0xff) {
      off++;
      continue;
    }
    if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      off += 2;
      continue;
    }
    if (marker === 0xd9 || marker === 0xda) break;
    if (JPEG_SOF.has(marker)) {
      return { width: dim(u16be(b, off + 7)), height: dim(u16be(b, off + 5)), frames: 1, animated: false };
    }
    off += 2 + u16be(b, off + 2);
  }
  return { ...UNKNOWN, frames: 1 };
}

/** Reads width/height (and frame count for GIF/APNG/WebP) from the header. Never throws. */
export function probeMedia(b: Uint8Array, mime: MediaMime): MediaProbe {
  try {
    switch (mime) {
      case 'image/png':
        return probePng(b);
      case 'image/gif':
        return probeGif(b);
      case 'image/webp':
        return probeWebp(b);
      case 'image/jpeg':
        return probeJpeg(b);
      default:
        return { width: null, height: null, frames: null, animated: true };
    }
  } catch {
    return { width: null, height: null, frames: mime.startsWith('video/') ? null : 1, animated: mime.startsWith('video/') };
  }
}

// ------------------------------------------------------------------ hashing / tokens

export function toHex(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += bytes[i].toString(16).padStart(2, '0');
  return s;
}

export async function sha256Bytes(bytes: Uint8Array): Promise<Uint8Array> {
  const digest = await crypto.subtle.digest('SHA-256', bytes as Uint8Array<ArrayBuffer>);
  return new Uint8Array(digest);
}

export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  return toHex(await sha256Bytes(bytes));
}

/** Constant-time comparison of two equal-length byte arrays. */
export function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

/**
 * Compares a presented token with the expected secret without leaking timing or length:
 * both sides are hashed to 32 bytes first, then compared in constant time.
 */
export async function tokensMatch(presented: string, expected: string): Promise<boolean> {
  if (!expected) return false;
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([sha256Bytes(enc.encode(presented)), sha256Bytes(enc.encode(expected))]);
  return timingSafeEqual(a, b);
}

// ------------------------------------------------------------------ base64 / body

const DATA_URL_PREFIX = /^data:[a-z0-9.+-]+\/[a-z0-9.+-]+(?:;[a-z0-9=._+-]+)*;base64,/i;

/** Decodes standard or URL-safe base64 (optionally a data: URL, optionally line-wrapped). Null if invalid. */
export function decodeBase64(input: string): Uint8Array | null {
  let s = input.trim();
  const prefix = DATA_URL_PREFIX.exec(s.slice(0, 256));
  if (prefix) s = s.slice(prefix[0].length);
  s = s.replace(/\s+/g, '').replace(/-/g, '+').replace(/_/g, '/');
  if (s.length % 4 === 1) return null;
  if (s.length % 4) s += '='.repeat(4 - (s.length % 4));
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(s)) return null;
  let bin: string;
  try {
    bin = atob(s);
  } catch {
    return null;
  }
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export class BodyTooLargeError extends Error {
  constructor() {
    super('body too large');
    this.name = 'BodyTooLargeError';
  }
}

/** Reads a request body as text, aborting as soon as it exceeds `max` bytes. */
export async function readTextCapped(body: ReadableStream<Uint8Array> | null, max: number): Promise<string> {
  if (!body) return '';
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > max) {
      await reader.cancel().catch(() => undefined);
      throw new BodyTooLargeError();
    }
    chunks.push(value);
  }
  const all = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) {
    all.set(c, off);
    off += c.byteLength;
  }
  return new TextDecoder().decode(all);
}

// ------------------------------------------------------------------ request validation

export interface IntakeInput {
  base64: string;
  declaredMime: string;
  site: string;
  sourceUrl: string | null;
  pageUrl: string | null;
  author: string | null;
  license: string | null;
  topic: string | null;
  collector: string;
}

export type Parsed<T> = { ok: true; value: T } | { ok: false; status: 400 | 413; error: string; detail: string; field?: string };

const SLUG = /^[a-z0-9][a-z0-9._-]{0,63}$/;

function bad(detail: string, field?: string): Parsed<never> {
  return { ok: false, status: 400, error: 'bad_request', detail, field };
}

/** undefined/null/'' → null; string → trimmed and cut to max; anything else → false (invalid). */
function optText(v: unknown, max: number): string | null | false {
  if (v === undefined || v === null) return null;
  if (typeof v !== 'string') return false;
  const t = v.trim();
  return t ? t.slice(0, max) : null;
}

function optUrl(v: unknown): string | null | false {
  const t = optText(v, 4096);
  if (t === null || t === false) return t;
  if (t.length > 2048) return false;
  try {
    const u = new URL(t);
    return u.protocol === 'http:' || u.protocol === 'https:' ? t : false;
  } catch {
    return false;
  }
}

/** Validates the collector's JSON body. Image bytes are checked later (decode + magic bytes). */
export function parseIntakeBody(body: unknown): Parsed<IntakeInput> {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return bad('body must be a JSON object');
  const o = body as Record<string, unknown>;

  const image = o.image_base64;
  if (typeof image !== 'string' || image.trim() === '') return bad('image_base64 is required', 'image_base64');
  if (image.length > MAX_BASE64_CHARS) {
    return { ok: false, status: 413, error: 'too_large', detail: `image must be at most ${MAX_IMAGE_BYTES} bytes`, field: 'image_base64' };
  }

  if (typeof o.mime !== 'string' || !o.mime.trim()) return bad('mime is required', 'mime');

  const site = typeof o.site === 'string' ? o.site.trim().toLowerCase() : '';
  if (!SLUG.test(site)) return bad('site is required: lower-case letters, digits, ".", "_" or "-" (max 64)', 'site');

  const sourceUrl = optUrl(o.source_url);
  if (sourceUrl === false) return bad('source_url must be an http(s) URL of at most 2048 characters', 'source_url');
  const pageUrl = optUrl(o.page_url);
  if (pageUrl === false) return bad('page_url must be an http(s) URL of at most 2048 characters', 'page_url');

  const author = optText(o.author, 300);
  if (author === false) return bad('author must be a string', 'author');
  const license = optText(o.license, 300);
  if (license === false) return bad('license must be a string', 'license');
  const topic = optText(o.topic, 300);
  if (topic === false) return bad('topic must be a string', 'topic');

  const collectorRaw = optText(o.collector, 64);
  if (collectorRaw === false) return bad('collector must be a string', 'collector');
  const collector = (collectorRaw ?? 'unknown').toLowerCase();
  if (!SLUG.test(collector)) return bad('collector: lower-case letters, digits, ".", "_" or "-" (max 64)', 'collector');

  return {
    ok: true,
    value: { base64: image, declaredMime: normalizeMime(o.mime), site, sourceUrl, pageUrl, author, license, topic, collector },
  };
}

// ------------------------------------------------------------------ storage / rows

/** Object path inside the private "sources" bucket: <yyyy>/<mm>/<sha256>.<ext> (UTC). */
export function sourceObjectPath(sha256: string, ext: string, at: Date = new Date()): string {
  if (!/^[0-9a-f]{64}$/.test(sha256)) throw new Error('sha256 must be 64 lower-case hex characters');
  if (!/^[a-z0-9]{2,5}$/.test(ext)) throw new Error('bad extension');
  const mm = String(at.getUTCMonth() + 1).padStart(2, '0');
  return `${at.getUTCFullYear()}/${mm}/${sha256}.${ext}`;
}

/** True when a storage upload failed only because the (content-addressed) object already exists. */
export function isStorageConflict(err: unknown): boolean {
  if (!err || typeof err !== 'object') return false;
  const e = err as { statusCode?: unknown; status?: unknown; message?: unknown; error?: unknown };
  return (
    String(e.statusCode) === '409' ||
    e.status === 409 ||
    /already exists/i.test(String(e.message ?? '')) ||
    /^duplicate$/i.test(String(e.error ?? ''))
  );
}

/** The `source_images` insert for an accepted intake request. */
export function sourceRow(args: {
  sha256: string;
  path: string;
  mime: MediaMime;
  bytes: number;
  probe: MediaProbe;
  input: IntakeInput;
}): Record<string, string | number | null> {
  const { sha256, path, mime, bytes, probe, input } = args;
  const row: Record<string, string | number | null> = {
    sha256,
    storage_path: path,
    mime,
    bytes,
    width: probe.width,
    height: probe.height,
    site: input.site,
    source_url: input.sourceUrl,
    page_url: input.pageUrl,
    author: input.author,
    license: input.license,
    collector: input.collector,
    topic_hint: input.topic,
    status: 'new',
  };
  if (probe.frames !== null) row.frames = probe.frames;
  return row;
}
