// Media helpers shared with the intake Edge Function (one implementation, tested once).
export {
  MEDIA_EXT,
  MEDIA_TYPES,
  isMediaMime,
  isStorageConflict,
  normalizeMime,
  probeMedia,
  sniffMedia,
  sourceObjectPath,
} from '../../../backend/supabase/functions/intake/lib.ts'
export type { MediaMime, MediaProbe, SniffResult } from '../../../backend/supabase/functions/intake/lib.ts'

import { probeMedia, sniffMedia } from '../../../backend/supabase/functions/intake/lib.ts'
import type { MediaMime } from '../../../backend/supabase/functions/intake/lib.ts'
import { sha256Hex } from './sha256'

/** The private `sources` bucket's file_size_limit (10 MiB). */
export const MAX_ADMIN_UPLOAD_BYTES = 10 * 1024 * 1024

export interface PreparedUpload {
  blob: Blob
  filename: string
  sha256: string
  mime: MediaMime
  ext: string
  bytes: number
  width: number | null
  height: number | null
  frames: number | null
  duration_ms: number | null
}

export class UploadRejected extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'UploadRejected'
  }
}

/** Reads a dropped file, checks its real type by magic bytes, hashes it and reads its dimensions. */
export async function prepareUpload(file: File): Promise<PreparedUpload> {
  if (file.size === 0) throw new UploadRejected('Empty file')
  if (file.size > MAX_ADMIN_UPLOAD_BYTES) throw new UploadRejected('Larger than 10 MB (bucket limit)')
  const bytes = new Uint8Array(await file.arrayBuffer())
  const sniff = sniffMedia(bytes)
  if (!sniff) throw new UploadRejected('Not a PNG, JPEG, WebP, GIF, MP4 or WebM file')
  const probe = probeMedia(bytes, sniff.mime)
  let { width, height } = probe
  let duration: number | null = null
  if (sniff.video) {
    const meta = await videoMeta(file).catch(() => null)
    if (meta) ({ width, height, duration_ms: duration } = meta)
  }
  return {
    blob: new Blob([bytes], { type: sniff.mime }),
    filename: file.name,
    sha256: await sha256Hex(bytes),
    mime: sniff.mime,
    ext: sniff.ext,
    bytes: bytes.length,
    width,
    height,
    frames: probe.frames,
    duration_ms: duration,
  }
}

function videoMeta(file: Blob): Promise<{ width: number | null; height: number | null; duration_ms: number | null }> {
  return new Promise((resolve, reject) => {
    if (typeof document === 'undefined') return reject(new Error('no DOM'))
    const url = URL.createObjectURL(file)
    const v = document.createElement('video')
    const done = () => URL.revokeObjectURL(url)
    const timer = setTimeout(() => {
      done()
      reject(new Error('timeout'))
    }, 8000)
    v.preload = 'metadata'
    v.muted = true
    v.onloadedmetadata = () => {
      clearTimeout(timer)
      done()
      resolve({
        width: v.videoWidth || null,
        height: v.videoHeight || null,
        duration_ms: Number.isFinite(v.duration) ? Math.round(v.duration * 1000) : null,
      })
    }
    v.onerror = () => {
      clearTimeout(timer)
      done()
      reject(new Error('unreadable video'))
    }
    v.src = url
  })
}
