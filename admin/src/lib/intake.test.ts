// Tests for backend/supabase/functions/intake/lib.ts (pure, runtime-agnostic), run under Node so
// the Edge Function's parsing rules are covered without Deno.
import { describe, expect, it } from 'vitest'
import {
  MAX_BASE64_CHARS,
  MAX_IMAGE_BYTES,
  BodyTooLargeError,
  decodeBase64,
  isStorageConflict,
  normalizeMime,
  parseIntakeBody,
  probeMedia,
  readTextCapped,
  sniffMedia,
  sourceObjectPath,
  sourceRow,
  timingSafeEqual,
  tokensMatch,
} from '../../../backend/supabase/functions/intake/lib.ts'

const b64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0))
const ascii = (s: string) => Array.from(s, (c) => c.charCodeAt(0))
const u32le = (n: number) => [n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff, (n >>> 24) & 0xff]

// Well-known minimal files.
const PNG_1x1 = b64('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==')
const GIF_1x1 = b64('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7')
const WEBP_LOSSLESS_1x1 = b64('UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==')

function jpeg(width: number, height: number): Uint8Array {
  // SOI, APP0 (JFIF, 16 bytes), SOF0 with the given size, EOI.
  return new Uint8Array([
    0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, ...ascii('JFIF'), 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
    0xff, 0xc0, 0x00, 0x11, 0x08, height >> 8, height & 0xff, width >> 8, width & 0xff, 0x03,
    0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01, 0xff, 0xd9,
  ])
}

function animatedWebp(width: number, height: number, frames: number): Uint8Array {
  const vp8x = [...ascii('VP8X'), ...u32le(10), 0x02, 0, 0, 0, ...u32le(width - 1).slice(0, 3), ...u32le(height - 1).slice(0, 3)]
  const anim = [...ascii('ANIM'), ...u32le(6), 0, 0, 0, 0, 0, 0]
  const frame = [...ascii('ANMF'), ...u32le(16), ...new Array(16).fill(0)]
  const body = [...ascii('WEBP'), ...vp8x, ...anim, ...Array.from({ length: frames }, () => frame).flat()]
  return new Uint8Array([...ascii('RIFF'), ...u32le(body.length), ...body])
}

function animatedGif(frames: number): Uint8Array {
  const header = [...ascii('GIF89a'), 2, 0, 3, 0, 0x80, 0, 0, 0, 0, 0, 255, 255, 255] // 2×3, 2-colour global table
  const gce = [0x21, 0xf9, 4, 0, 10, 0, 0, 0]
  const image = [0x2c, 0, 0, 0, 0, 2, 0, 3, 0, 0, 2, 2, 0x4c, 0x01, 0]
  return new Uint8Array([...header, ...Array.from({ length: frames }, () => [...gce, ...image]).flat(), 0x3b])
}

const ftyp = (major: string, compat: string[] = []) =>
  new Uint8Array([...[0, 0, 0, 16 + compat.length * 4], ...ascii('ftyp'), ...ascii(major), 0, 0, 2, 0, ...compat.flatMap(ascii)])
const WEBM = new Uint8Array([0x1a, 0x45, 0xdf, 0xa3, 0x9f, 0x42, 0x86, 0x81, 0x01, 0x42, 0x82, 0x84, ...ascii('webm'), 0x42, 0x87])
const MKV = new Uint8Array([0x1a, 0x45, 0xdf, 0xa3, 0x9f, 0x42, 0x82, 0x88, ...ascii('matroska')])

describe('sniffMedia (magic bytes)', () => {
  it('detects the six accepted types', () => {
    expect(sniffMedia(PNG_1x1)?.mime).toBe('image/png')
    expect(sniffMedia(jpeg(64, 32))?.mime).toBe('image/jpeg')
    expect(sniffMedia(GIF_1x1)?.mime).toBe('image/gif')
    expect(sniffMedia(WEBP_LOSSLESS_1x1)?.mime).toBe('image/webp')
    expect(sniffMedia(ftyp('isom', ['iso2', 'mp41']))?.mime).toBe('video/mp4')
    expect(sniffMedia(ftyp('XXXX', ['mp42']))?.mime).toBe('video/mp4')
    expect(sniffMedia(WEBM)).toEqual({ mime: 'video/webm', ext: 'webm', video: true })
    expect(sniffMedia(jpeg(1, 1))?.ext).toBe('jpg')
  })

  it('refuses look-alikes and other formats', () => {
    expect(sniffMedia(ftyp('avif', ['mif1', 'miaf']))).toBeNull()
    expect(sniffMedia(ftyp('heic'))).toBeNull()
    expect(sniffMedia(ftyp('qt  '))).toBeNull()
    expect(sniffMedia(ftyp('XXXX', ['mif1', 'isom']))).toBeNull()
    expect(sniffMedia(MKV)).toBeNull()
    expect(sniffMedia(new TextEncoder().encode('<svg xmlns="http://www.w3.org/2000/svg"/>'))).toBeNull()
    expect(sniffMedia(new TextEncoder().encode('%PDF-1.7'))).toBeNull()
    expect(sniffMedia(new Uint8Array([0x89, 0x50]))).toBeNull()
    expect(sniffMedia(new Uint8Array([]))).toBeNull()
  })

  it('normalises declared MIME aliases', () => {
    expect(normalizeMime('Image/JPG; charset=binary')).toBe('image/jpeg')
    expect(normalizeMime('image/x-png')).toBe('image/png')
  })
})

describe('probeMedia', () => {
  it('reads dimensions from PNG, GIF, WebP (VP8L) and JPEG headers', () => {
    expect(probeMedia(PNG_1x1, 'image/png')).toEqual({ width: 1, height: 1, frames: 1, animated: false })
    expect(probeMedia(GIF_1x1, 'image/gif')).toEqual({ width: 1, height: 1, frames: 1, animated: false })
    expect(probeMedia(WEBP_LOSSLESS_1x1, 'image/webp')).toEqual({ width: 1, height: 1, frames: 1, animated: false })
    expect(probeMedia(jpeg(640, 480), 'image/jpeg')).toMatchObject({ width: 640, height: 480 })
  })

  it('counts frames of animated WebP and GIF', () => {
    expect(probeMedia(animatedWebp(512, 512, 7), 'image/webp')).toEqual({ width: 512, height: 512, frames: 7, animated: true })
    expect(probeMedia(animatedGif(4), 'image/gif')).toEqual({ width: 2, height: 3, frames: 4, animated: true })
  })

  it('never throws on truncated input and leaves videos to the worker', () => {
    expect(probeMedia(PNG_1x1.slice(0, 12), 'image/png').width).toBeNull()
    expect(probeMedia(jpeg(10, 10).slice(0, 8), 'image/jpeg').width).toBeNull()
    expect(probeMedia(WEBM, 'video/webm')).toEqual({ width: null, height: null, frames: null, animated: true })
  })
})

describe('decodeBase64', () => {
  it('decodes standard, URL-safe, unpadded, wrapped and data: URL input', () => {
    expect(decodeBase64('aGVsbG8=')).toEqual(new TextEncoder().encode('hello'))
    expect(decodeBase64('aGVsbG8')).toEqual(new TextEncoder().encode('hello'))
    expect(decodeBase64('aGVs\nbG8=')).toEqual(new TextEncoder().encode('hello'))
    expect(decodeBase64('data:image/png;base64,iVBORw0KGgo=')?.slice(0, 4)).toEqual(new Uint8Array([0x89, 0x50, 0x4e, 0x47]))
    expect(decodeBase64('-_8=')).toEqual(new Uint8Array([0xfb, 0xff]))
  })

  it('returns null for invalid input', () => {
    expect(decodeBase64('not base64!')).toBeNull()
    expect(decodeBase64('a')).toBeNull()
    expect(decodeBase64('ab=c')).toBeNull()
  })
})

describe('token comparison', () => {
  it('compares byte arrays in constant time', () => {
    expect(timingSafeEqual(new Uint8Array([1, 2, 3]), new Uint8Array([1, 2, 3]))).toBe(true)
    expect(timingSafeEqual(new Uint8Array([1, 2, 3]), new Uint8Array([1, 2, 4]))).toBe(false)
    expect(timingSafeEqual(new Uint8Array([1, 2]), new Uint8Array([1, 2, 3]))).toBe(false)
  })

  it('matches only the exact secret, and never an empty one', async () => {
    const secret = 'f3c1d2e4a5b6978812345678abcdef00'
    expect(await tokensMatch(secret, secret)).toBe(true)
    expect(await tokensMatch(`${secret} `, secret)).toBe(false)
    expect(await tokensMatch(secret.slice(0, -1), secret)).toBe(false)
    expect(await tokensMatch('', '')).toBe(false)
  })
})

describe('parseIntakeBody', () => {
  const ok = { image_base64: 'aGVsbG8=', mime: 'image/jpg', site: 'Reddit', source_url: 'https://i.redd.it/x.jpg' }

  it('accepts a minimal body and normalises fields', () => {
    const r = parseIntakeBody({ ...ok, author: '  u/someone ', collector: 'HandNavigate', topic: '' })
    expect(r.ok).toBe(true)
    if (!r.ok) return
    expect(r.value).toMatchObject({
      declaredMime: 'image/jpeg',
      site: 'reddit',
      sourceUrl: 'https://i.redd.it/x.jpg',
      pageUrl: null,
      author: 'u/someone',
      topic: null,
      collector: 'handnavigate',
    })
    expect(parseIntakeBody({ image_base64: 'aGVsbG8=', mime: 'image/png', site: 'x' }).ok && true).toBe(true)
  })

  it('rejects missing or malformed fields with the field name', () => {
    const field = (body: unknown) => {
      const r = parseIntakeBody(body)
      return r.ok ? 'ok' : r.field ?? r.error
    }
    expect(field(null)).toBe('bad_request')
    expect(field([])).toBe('bad_request')
    expect(field({ ...ok, image_base64: '' })).toBe('image_base64')
    expect(field({ ...ok, mime: 3 })).toBe('mime')
    expect(field({ ...ok, site: '' })).toBe('site')
    expect(field({ ...ok, site: 'has space' })).toBe('site')
    expect(field({ ...ok, source_url: 'javascript:alert(1)' })).toBe('source_url')
    expect(field({ ...ok, page_url: 'ftp://x' })).toBe('page_url')
    expect(field({ ...ok, author: 42 })).toBe('author')
    expect(field({ ...ok, collector: 'bad collector!' })).toBe('collector')
  })

  it('answers 413 for oversized images before decoding', () => {
    const r = parseIntakeBody({ ...ok, image_base64: 'A'.repeat(MAX_BASE64_CHARS + 1) })
    expect(r.ok ? 0 : r.status).toBe(413)
    expect(MAX_BASE64_CHARS).toBeGreaterThan((MAX_IMAGE_BYTES / 3) * 4)
  })
})

describe('storage helpers', () => {
  const sha = 'a'.repeat(64)

  it('builds <yyyy>/<mm>/<sha256>.<ext> paths in UTC', () => {
    expect(sourceObjectPath(sha, 'webp', new Date('2026-11-05T20:00:00Z'))).toBe(`2026/11/${sha}.webp`)
    expect(sourceObjectPath(sha, 'jpg', new Date('2026-12-31T23:30:00-02:00'))).toBe(`2027/01/${sha}.jpg`)
    expect(() => sourceObjectPath('xyz', 'png')).toThrow()
    expect(() => sourceObjectPath(sha, '../x')).toThrow()
  })

  it('recognises "object already exists" storage errors only', () => {
    expect(isStorageConflict({ statusCode: '409', message: 'Duplicate' })).toBe(true)
    expect(isStorageConflict({ message: 'The resource already exists' })).toBe(true)
    expect(isStorageConflict({ statusCode: '403', message: 'new row violates row-level security policy' })).toBe(false)
    expect(isStorageConflict(null)).toBe(false)
  })

  it('maps an accepted request to a source_images row', () => {
    const parsed = parseIntakeBody({ image_base64: 'aGVsbG8=', mime: 'image/webp', site: 'pinterest', topic: 'chai' })
    if (!parsed.ok) throw new Error('fixture invalid')
    const row = sourceRow({
      sha256: sha,
      path: `2026/11/${sha}.webp`,
      mime: 'image/webp',
      bytes: 1234,
      probe: { width: 512, height: 512, frames: 7, animated: true },
      input: parsed.value,
    })
    expect(row).toMatchObject({ site: 'pinterest', topic_hint: 'chai', collector: 'unknown', status: 'new', frames: 7, width: 512 })
    expect(sourceRow({ sha256: sha, path: 'p', mime: 'video/mp4', bytes: 1, probe: { width: null, height: null, frames: null, animated: true }, input: parsed.value })).not.toHaveProperty('frames')
  })
})

describe('readTextCapped', () => {
  const stream = (chunks: string[]) =>
    new ReadableStream<Uint8Array>({
      start(c) {
        for (const ch of chunks) c.enqueue(new TextEncoder().encode(ch))
        c.close()
      },
    })

  it('reads small bodies and aborts big ones', async () => {
    expect(await readTextCapped(stream(['{"a":', '1}']), 100)).toBe('{"a":1}')
    await expect(readTextCapped(stream(['x'.repeat(60), 'y'.repeat(60)]), 100)).rejects.toBeInstanceOf(BodyTooLargeError)
    expect(await readTextCapped(null, 10)).toBe('')
  })
})
