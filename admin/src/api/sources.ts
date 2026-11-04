// source_images (+ the private `sources` storage bucket).

import type { SupabaseClient } from '@supabase/supabase-js'
import { isStorageConflict, sourceObjectPath, type PreparedUpload } from '../lib/media'
import { clone, delay, demoError, demoUuid, type DemoDb } from './demo/db'
import {
  SOURCE_COLUMNS,
  SOURCE_LIST_COLUMNS,
  SOURCE_STATUSES,
  type Iso,
  type Page,
  type PageReq,
  type SourceFilter,
  type SourceImage,
  type SourceStatus,
  type SourceSummary,
  type TriState,
  type UploadOutcome,
  type Uuid,
} from './types'
import { ApiError, chunk, matchesTerms, searchTerms, unwrap, unwrapCount } from './util'

export type SourceAction = 'reject' | 'reanalyze'

export interface SourcesApi {
  list(filter: SourceFilter, page: PageReq): Promise<Page<SourceSummary>>
  get(id: Uuid): Promise<SourceImage | null>
  /** Signed (private bucket) URLs keyed by storage path. Cached until shortly before expiry. */
  signedUrls(paths: string[]): Promise<Record<string, string>>
  /** reject → status rejected; reanalyze → status screened with attempts reset. Never touches taken_down rows. */
  apply(ids: Uuid[], action: SourceAction): Promise<void>
  upload(file: PreparedUpload): Promise<UploadOutcome>
  sites(): Promise<string[]>
  countSince(column: 'collected_at' | 'analyzed_at', since: Iso): Promise<number>
  countByStatus(): Promise<Record<SourceStatus, number>>
}

const KNOWN_SITES = ['reddit', 'pinterest', 'imgflip', 'openverse', 'upload']
const SIGNED_TTL_S = 3600

function actionPatch(action: SourceAction): Partial<SourceImage> {
  return action === 'reject'
    ? { status: 'rejected', locked_until: null }
    : { status: 'screened', attempts: 0, last_error: null, locked_until: null }
}

function uploadRow(f: PreparedUpload, path: string): Record<string, unknown> {
  const row: Record<string, unknown> = {
    sha256: f.sha256,
    storage_path: path,
    mime: f.mime,
    bytes: f.bytes,
    width: f.width,
    height: f.height,
    site: 'upload',
    collector: 'admin',
    status: 'new',
  }
  if (f.frames !== null) row.frames = f.frames
  if (f.duration_ms !== null) row.duration_ms = f.duration_ms
  return row
}

// ------------------------------------------------------------------ Supabase

export function supabaseSources(sb: SupabaseClient): SourcesApi {
  const signedCache = new Map<string, { url: string; expires: number }>()

  return {
    async list(f, page) {
      let q = sb.from('source_images').select(SOURCE_LIST_COLUMNS.join(','), { count: 'exact' })
      if (f.status) q = q.eq('status', f.status)
      if (f.site) q = q.eq('site', f.site)
      if (f.kind) q = q.eq('kind', f.kind)
      if (f.category) q = q.eq('category', f.category)
      if (f.nsfw) q = f.nsfw === 'unknown' ? q.is('nsfw', null) : q.eq('nsfw', f.nsfw)
      if (f.minors && f.minors !== 'any') q = f.minors === 'unknown' ? q.is('minors', null) : q.eq('minors', f.minors === 'yes')
      if (f.hasText && f.hasText !== 'any') {
        q = f.hasText === 'unknown' ? q.is('has_text', null) : q.eq('has_text', f.hasText === 'yes')
      }
      if (f.scoreMin !== undefined) q = q.gte('sticker_score', f.scoreMin)
      if (f.scoreMax !== undefined) q = q.lte('sticker_score', f.scoreMax)
      for (const term of searchTerms(f.q)) q = q.ilike('search_text', `%${term}%`)
      if (f.sort === 'score') q = q.order('sticker_score', { ascending: false, nullsFirst: false })
      else if (f.sort === 'analyzed') q = q.order('analyzed_at', { ascending: false, nullsFirst: false })
      q = q.order('collected_at', { ascending: false }).range(page.offset, page.offset + page.limit - 1)
      const res = await q
      return { rows: unwrap(res, 'Load sources') as unknown as SourceSummary[], total: res.count ?? 0 }
    },

    async get(id) {
      const res = await sb.from('source_images').select(SOURCE_COLUMNS.join(',')).eq('id', id).maybeSingle()
      return unwrap(res, 'Load source') as unknown as SourceImage | null
    },

    async signedUrls(paths) {
      const now = Date.now()
      const out: Record<string, string> = {}
      const missing: string[] = []
      for (const p of new Set(paths)) {
        const hit = signedCache.get(p)
        if (hit && hit.expires - now > 120_000) out[p] = hit.url
        else missing.push(p)
      }
      for (const group of chunk(missing, 100)) {
        const { data, error } = await sb.storage.from('sources').createSignedUrls(group, SIGNED_TTL_S)
        if (error) throw new ApiError(`Sign source URLs: ${error.message}`)
        for (const d of data ?? []) {
          if (!d.path || !d.signedUrl) continue
          signedCache.set(d.path, { url: d.signedUrl, expires: now + SIGNED_TTL_S * 1000 })
          out[d.path] = d.signedUrl
        }
      }
      return out
    },

    async apply(ids, action) {
      if (!ids.length) return
      const res = await sb.from('source_images').update(actionPatch(action)).in('id', ids).neq('status', 'taken_down')
      unwrap(res, action === 'reject' ? 'Reject sources' : 'Re-analyze sources')
    },

    async upload(f) {
      const blocked = await sb.from('takedowns').select('id').eq('sha256', f.sha256).limit(1)
      if ((unwrap(blocked, 'Check takedowns') ?? []).length) return { status: 'blocked', reason: 'Taken down before — re-upload blocked' }
      const existing = await sb.from('source_images').select('id').eq('sha256', f.sha256).maybeSingle()
      const dupe = unwrap(existing, 'Check duplicates') as { id: string } | null
      if (dupe) return { status: 'duplicate', id: dupe.id }

      const path = sourceObjectPath(f.sha256, f.ext)
      const up = await sb.storage
        .from('sources')
        .upload(path, f.blob, { contentType: f.mime, upsert: false, cacheControl: '31536000' })
      if (up.error && !isStorageConflict(up.error)) throw new ApiError(`Upload ${f.filename}: ${up.error.message}`)

      const ins = await sb.from('source_images').insert(uploadRow(f, path)).select('id').single()
      if (ins.error?.code === '23505') {
        const again = await sb.from('source_images').select('id').eq('sha256', f.sha256).maybeSingle()
        const row = unwrap(again, 'Check duplicates') as { id: string } | null
        if (row) return { status: 'duplicate', id: row.id }
      }
      const created = unwrap(ins, `Save ${f.filename}`) as { id: string }
      return { status: 'created', id: created.id, path }
    },

    async sites() {
      const res = await sb.from('source_images').select('site').order('collected_at', { ascending: false }).limit(1000)
      const rows = unwrap(res, 'Load sites') as { site: string }[]
      return [...new Set([...KNOWN_SITES, ...rows.map((r) => r.site)])].sort()
    },

    async countSince(column, since) {
      const res = await sb.from('source_images').select('id', { count: 'exact', head: true }).gte(column, since)
      return unwrapCount(res, 'Count sources')
    },

    async countByStatus() {
      const counts = await Promise.all(
        SOURCE_STATUSES.map(async (s) => {
          const res = await sb.from('source_images').select('id', { count: 'exact', head: true }).eq('status', s)
          return [s, unwrapCount(res, 'Count sources')] as const
        }),
      )
      return Object.fromEntries(counts) as Record<SourceStatus, number>
    },
  }
}

// ------------------------------------------------------------------ demo

function triMatch(value: boolean | null, f: TriState | undefined): boolean {
  if (!f || f === 'any') return true
  if (f === 'unknown') return value === null
  return value === (f === 'yes')
}

export function sourceSearchText(s: SourceImage): string {
  return [s.description, s.tags.join(' '), s.mood.join(' '), s.category, s.kind, s.ocr_text, s.topic_hint, s.site, s.public_figure]
    .filter(Boolean)
    .join(' ')
}

function summarize(s: SourceImage): SourceSummary {
  const out: Record<string, unknown> = {}
  for (const c of SOURCE_LIST_COLUMNS) out[c] = s[c]
  return clone(out) as SourceSummary
}

export function demoSources(db: DemoDb): SourcesApi {
  return {
    async list(f, page) {
      await delay(db)
      const terms = searchTerms(f.q)
      let rows = db.sources.filter(
        (s) =>
          (!f.status || s.status === f.status) &&
          (!f.site || s.site === f.site) &&
          (!f.kind || s.kind === f.kind) &&
          (!f.category || s.category === f.category) &&
          (!f.nsfw || (f.nsfw === 'unknown' ? s.nsfw === null : s.nsfw === f.nsfw)) &&
          triMatch(s.minors, f.minors) &&
          triMatch(s.has_text, f.hasText) &&
          (f.scoreMin === undefined || (s.sticker_score !== null && s.sticker_score >= f.scoreMin)) &&
          (f.scoreMax === undefined || (s.sticker_score !== null && s.sticker_score <= f.scoreMax)) &&
          (!terms.length || matchesTerms(sourceSearchText(s), terms)),
      )
      const key = f.sort === 'score' ? 'sticker_score' : f.sort === 'analyzed' ? 'analyzed_at' : 'collected_at'
      rows = [...rows].sort((a, b) => {
        const av = a[key] ?? null
        const bv = b[key] ?? null
        if (av === bv) return b.collected_at.localeCompare(a.collected_at)
        if (av === null) return 1
        if (bv === null) return -1
        return av < bv ? 1 : -1
      })
      return { rows: rows.slice(page.offset, page.offset + page.limit).map(summarize), total: rows.length }
    },

    async get(id) {
      await delay(db)
      const row = db.sources.find((s) => s.id === id)
      return row ? clone(row) : null
    },

    async signedUrls(paths) {
      const out: Record<string, string> = {}
      for (const p of paths) {
        const url = db.objects.get(`sources/${p}`)
        if (url) out[p] = url
      }
      return out
    },

    async apply(ids, action) {
      await delay(db)
      const patch = actionPatch(action)
      const at = new Date().toISOString()
      for (const s of db.sources) {
        if (ids.includes(s.id) && s.status !== 'taken_down') Object.assign(s, patch, { updated_at: at })
      }
    },

    async upload(f) {
      await delay(db)
      if (db.takedowns.some((t) => t.sha256 === f.sha256)) return { status: 'blocked', reason: 'Taken down before — re-upload blocked' }
      const dupe = db.sources.find((s) => s.sha256 === f.sha256)
      if (dupe) return { status: 'duplicate', id: dupe.id }
      const path = sourceObjectPath(f.sha256, f.ext)
      if (db.sources.some((s) => s.storage_path === path)) throw demoError('Save: storage_path already exists', '23505')
      const url = typeof URL.createObjectURL === 'function' ? URL.createObjectURL(f.blob) : `blob:demo/${f.sha256}`
      db.objects.set(`sources/${path}`, url)
      const at = new Date().toISOString()
      const row: SourceImage = {
        id: demoUuid(),
        sha256: f.sha256,
        phash: null,
        storage_path: path,
        mime: f.mime,
        width: f.width,
        height: f.height,
        bytes: f.bytes,
        frames: f.frames ?? 1,
        duration_ms: f.duration_ms ?? 0,
        site: 'upload',
        source_url: null,
        page_url: null,
        author: null,
        license: null,
        collector: 'admin',
        topic_hint: null,
        collected_at: at,
        status: 'new',
        attempts: 0,
        locked_until: null,
        last_error: null,
        prescreen: null,
        prescreen_score: null,
        ocr_text: null,
        analysis: null,
        kind: null,
        has_text: null,
        language: null,
        category: null,
        mood: [],
        tags: [],
        emojis: [],
        description: null,
        nsfw: null,
        minors: null,
        real_person: null,
        public_figure: null,
        sticker_score: null,
        analyzed_at: null,
        created_at: at,
        updated_at: at,
      }
      db.sources.unshift(row)
      return { status: 'created', id: row.id, path }
    },

    async sites() {
      return [...new Set([...KNOWN_SITES, ...db.sources.map((s) => s.site)])].sort()
    },

    async countSince(column, since) {
      await delay(db)
      return db.sources.filter((s) => (s[column] ?? '') >= since).length
    },

    async countByStatus() {
      const out = Object.fromEntries(SOURCE_STATUSES.map((s) => [s, 0])) as Record<SourceStatus, number>
      for (const s of db.sources) out[s.status]++
      return out
    },
  }
}
