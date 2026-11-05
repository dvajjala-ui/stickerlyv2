import { beforeEach, describe, expect, it } from 'vitest'
import { istDate, istDayStart } from '../lib/format'
import { checkEmojis } from '../lib/emoji'
import type { PreparedUpload } from '../lib/media'
import { duplicateOf, parseGateNote } from '../lib/quality'
import { sha256Hex } from '../lib/sha256'
import { DEMO_ADMIN_EMAIL } from './auth'
import { createDemoApi, type Api } from './compose'
import type { DemoDb } from './demo/db'
import { seedDemoDb } from './demo/fixtures'
import { mergeSettings } from './settings'

const NOW = new Date('2026-11-05T09:30:00Z') // 15:00 IST
const PAGE = { offset: 0, limit: 500 }
let db: DemoDb
let api: Api

beforeEach(() => {
  db = seedDemoDb({ now: NOW })
  api = createDemoApi(db)
})

async function upload(text: string): Promise<PreparedUpload> {
  const bytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, ...new TextEncoder().encode(text)])
  return {
    blob: new Blob([bytes], { type: 'image/png' }),
    filename: `${text}.png`,
    sha256: await sha256Hex(bytes),
    mime: 'image/png',
    ext: 'png',
    bytes: bytes.length,
    width: 10,
    height: 10,
    frames: 1,
    duration_ms: null,
  }
}

describe('demo fixtures', () => {
  it('respect the schema checks', () => {
    for (const s of db.sources) {
      expect(s.sha256).toMatch(/^[0-9a-f]{64}$/)
      expect(s.storage_path).toMatch(/^\d{4}\/\d{2}\/[0-9a-f]{64}\.(png|jpg|webp|gif|mp4|webm)$/)
      expect(s.sticker_score === null || (s.sticker_score >= 0 && s.sticker_score <= 10)).toBe(true)
    }
    for (const s of db.stickers) {
      expect(s.emojis.length).toBeLessThanOrEqual(3)
      expect(checkEmojis(s.emojis).ok).toBe(true)
      expect(s.bytes).toBeLessThanOrEqual(500_000)
      expect(db.objects.has(`stickers/${s.file_path}`)).toBe(true)
    }
    expect(new Set(db.stickers.map((s) => s.sha256)).size).toBe(db.stickers.length)
  })

  it('keep pack counts equal to their published stickers (like the trigger)', () => {
    for (const p of db.packs) {
      const ids = db.packItems.filter((i) => i.pack_id === p.id).map((i) => i.sticker_id)
      expect(p.sticker_count).toBe(db.stickers.filter((s) => ids.includes(s.id) && s.status === 'published').length)
    }
  })

  it('put activity in "today" (IST) for the overview', async () => {
    const since = istDayStart(NOW).toISOString()
    expect(await api.sources.countSince('collected_at', since)).toBeGreaterThan(5)
    expect(await api.stickers.countSince('created_at', since)).toBeGreaterThan(5)
    expect(await api.aiCalls.usageSince(since)).toEqual({ vision: 37, text: 212, image: 0, failed: 6, total: 249 })
    expect((await api.trends.list(istDate(NOW))).length).toBe(6)
  })
})

describe('sources (demo)', () => {
  it('filters like the Supabase query', async () => {
    const all = await api.sources.list({}, PAGE)
    expect(all.total).toBe(db.sources.length)
    const fresh = await api.sources.list({ status: 'new' }, PAGE)
    expect(fresh.rows.every((r) => r.status === 'new')).toBe(true)
    expect((await api.sources.list({ nsfw: 'explicit' }, PAGE)).total).toBe(1)
    expect((await api.sources.list({ minors: 'yes' }, PAGE)).rows.every((r) => r.minors === true)).toBe(true)
    expect((await api.sources.list({ minors: 'unknown' }, PAGE)).rows.every((r) => r.minors === null)).toBe(true)
    const chai = await api.sources.list({ q: 'kulhad CHAI' }, PAGE)
    expect(chai.total).toBe(1)
    const scored = await api.sources.list({ scoreMin: 8, sort: 'score' }, PAGE)
    expect(scored.rows.every((r) => (r.sticker_score ?? 0) >= 8)).toBe(true)
    expect(scored.rows[0].sticker_score).toBe(9)
  })

  it('paginates with a stable total', async () => {
    const p1 = await api.sources.list({}, { offset: 0, limit: 10 })
    const p2 = await api.sources.list({}, { offset: 10, limit: 10 })
    expect(p1.rows).toHaveLength(10)
    expect(p1.total).toBe(p2.total)
    expect(p1.rows.map((r) => r.id)).not.toContain(p2.rows[0].id)
  })

  it('dedupes uploads by sha256 and blocks taken-down fingerprints', async () => {
    const f = await upload('new-image')
    const first = await api.sources.upload(f)
    expect(first.status).toBe('created')
    const row = db.sources.find((s) => s.sha256 === f.sha256)!
    expect(row).toMatchObject({ site: 'upload', collector: 'admin', status: 'new' })
    expect(row.storage_path).toMatch(new RegExp(`^\\d{4}/\\d{2}/${f.sha256}\\.png$`))
    expect(await api.sources.upload(f)).toEqual({ status: 'duplicate', id: row.id })

    const blocked = await upload('blocked-image')
    db.takedowns.push({ id: 't', sha256: blocked.sha256, phash: null, source_url: null, reason: 'test', report_id: null, created_at: NOW.toISOString() })
    expect((await api.sources.upload(blocked)).status).toBe('blocked')
  })

  it('reject / re-analyze never touch taken-down rows', async () => {
    const failed = db.sources.find((s) => s.status === 'failed')!
    const down = db.sources.find((s) => s.status === 'taken_down')!
    await api.sources.apply([failed.id, down.id], 'reanalyze')
    expect(failed).toMatchObject({ status: 'screened', attempts: 0, last_error: null })
    expect(down.status).toBe('taken_down')
    await api.sources.apply([failed.id], 'reject')
    expect(failed.status).toBe('rejected')
  })
})

describe('stickers (demo)', () => {
  it('publishing sets published_at once and recounts packs', async () => {
    const daily = db.packs.find((p) => p.pack_type === 'daily')!
    const before = daily.sticker_count
    const draft = db.stickers.find((s) => s.caption === 'Thoda adjust kar lo')!
    expect(draft.published_at).toBeNull()
    await api.stickers.setStatus([draft.id], 'published')
    expect(draft.status).toBe('published')
    expect(draft.published_at).not.toBeNull()
    expect(daily.sticker_count).toBe(before + 1)
  })

  it('bulk status changes skip taken-down stickers', async () => {
    const down = db.stickers.find((s) => s.status === 'taken_down')!
    await api.stickers.setStatus([down.id], 'published')
    expect(down.status).toBe('taken_down')
  })

  it('enforces the 3-emoji check constraint and filters by search', async () => {
    const s = db.stickers[0]
    await expect(api.stickers.update(s.id, { emojis: ['😂', '🔥', '🙏', '💀'] })).rejects.toMatchObject({ code: '23514' })
    const updated = await api.stickers.update(s.id, { caption: 'Naya caption', emojis: ['😂'] })
    expect(updated).toMatchObject({ caption: 'Naya caption', emojis: ['😂'] })
    const found = await api.stickers.list({ q: 'naya' }, PAGE)
    expect(found.rows.map((r) => r.id)).toEqual([s.id])
    expect(found.rows[0].source?.site).toBeDefined()
  })

  it('lists by status, kind and rating', async () => {
    const adult = await api.stickers.list({ rating: 'adult', status: 'published' }, PAGE)
    expect(adult.total).toBeGreaterThan(0)
    expect(adult.rows.every((r) => r.rating === 'adult' && r.status === 'published')).toBe(true)
    const animated = await api.stickers.list({ kind: 'animated' }, PAGE)
    expect(animated.rows.every((r) => r.kind === 'animated')).toBe(true)
  })
})

describe('held stickers (demo)', () => {
  const held = () => db.stickers.filter((s) => s.status === 'held')

  it('fixtures look like the worker wrote them', () => {
    expect(held().length).toBeGreaterThanOrEqual(5)
    for (const s of db.stickers) {
      expect(s.quality_score === null || (Number.isInteger(s.quality_score) && s.quality_score >= 0 && s.quality_score <= 100)).toBe(true)
      expect(s.render_phash === null || /^[0-9a-f]{16}$/.test(s.render_phash)).toBe(true)
    }
    for (const s of held()) {
      expect(s.quality_score).toBeLessThan(db.settings.quality_gate.value.min_score)
      expect(s.quality_reasons.length).toBeGreaterThan(0)
      expect(s.published_at).toBeNull()
      expect(parseGateNote(s.review_note)).toEqual({ score: s.quality_score, min: 60, reasons: s.quality_reasons })
      expect(db.packItems.some((i) => i.sticker_id === s.id)).toBe(false) // never packed
    }
    const reasons = new Set(held().flatMap((s) => s.quality_reasons))
    for (const r of ['blurry', 'tiny_subject', 'caption_low_contrast', 'near_duplicate', 'judge:pixelated']) expect(reasons).toContain(r)
    const dup = held().find((s) => s.quality_reasons.includes('near_duplicate'))!
    expect(db.stickers.some((s) => s.id === duplicateOf(dup.quality_signals)?.id && s.status === 'published')).toBe(true)
  })

  it('lists the held queue newest first, or by gate score lowest first', async () => {
    const newest = await api.stickers.list({ status: 'held', sort: 'created' }, PAGE)
    expect(newest.total).toBe(held().length)
    const times = newest.rows.map((r) => r.created_at)
    expect(times).toEqual([...times].sort().reverse())
    const scores = (await api.stickers.list({ status: 'held', sort: 'score' }, PAGE)).rows.map((r) => r.quality_score!)
    expect(scores).toEqual([...scores].sort((a, b) => a - b))
  })

  it('approve publishes (setting published_at once) and reject rejects, only for rows still held', async () => {
    const [a, b, c] = held()
    const published = db.stickers.find((s) => s.status === 'published')!
    const publishedAt = published.published_at

    const out = await api.stickers.reviewHeld([a.id, b.id, published.id, 'missing-id'], 'approve')
    expect(out).toEqual({ done: [a.id, b.id], notHeld: [published.id, 'missing-id'], failed: [] })
    expect(a).toMatchObject({ status: 'published' })
    expect(a.published_at).not.toBeNull()
    expect(published.published_at).toBe(publishedAt)

    expect(await api.stickers.reviewHeld([c.id, a.id], 'reject')).toEqual({ done: [c.id], notHeld: [a.id], failed: [] })
    expect(c).toMatchObject({ status: 'rejected', published_at: null })
    expect(a.status).toBe('published') // an approved sticker is not rejected by a stale selection
  })

  it('never touches a taken-down sticker', async () => {
    const s = held()[0]
    await api.takedowns.takeDown('sticker', s.id, 'test')
    expect(await api.stickers.reviewHeld([s.id], 'approve')).toMatchObject({ done: [], notHeld: [s.id] })
    expect(s.status).toBe('taken_down')
  })

  it('round-trips the quality_gate setting and fills it from defaults', async () => {
    const s = await api.settings.load()
    expect(s.quality_gate).toEqual({ enabled: true, min_score: 60, vision_judge: false, judge_band: 20 })
    await api.settings.save('quality_gate', { ...s.quality_gate, min_score: 45, vision_judge: true })
    expect((await api.settings.load()).quality_gate).toMatchObject({ min_score: 45, vision_judge: true, judge_band: 20 })
    const merged = mergeSettings([{ key: 'quality_gate', value: { enabled: false }, updated_at: NOW.toISOString() }])
    expect(merged.quality_gate).toEqual({ enabled: false, min_score: 60, vision_judge: false, judge_band: 20 })
    expect(merged.updated_at.quality_gate).toBe(NOW.toISOString())
  })
})

describe('packs (demo)', () => {
  it('setItems writes only changes and bumps the version per changed row', async () => {
    const pack = db.packs.find((p) => p.title === 'Office Pain')!
    const items = await api.packs.items(pack.id)
    const ids = items.map((i) => i.sticker_id)
    const v0 = pack.version
    await api.packs.setItems(pack.id, ids) // no-op
    expect(pack.version).toBe(v0)
    await api.packs.setItems(pack.id, [ids[1], ids[0], ...ids.slice(2, -1)]) // swap two, drop the last
    expect(pack.version).toBe(v0 + 3)
    expect((await api.packs.items(pack.id)).map((i) => i.sticker_id)).toEqual([ids[1], ids[0], ...ids.slice(2, -1)])
    expect(pack.sticker_count).toBe(ids.length - 1)
  })

  it('refuses duplicates and more than 30 stickers', async () => {
    const pack = db.packs[0]
    const id = db.stickers[0].id
    await expect(api.packs.setItems(pack.id, [id, id])).rejects.toThrow(/once/)
    await expect(api.packs.setItems(pack.id, Array.from({ length: 31 }, (_, i) => `x${i}`))).rejects.toThrow(/30/)
  })

  it('creates, edits (version bump), publishes and enforces the series/volume key', async () => {
    const created = await api.packs.create({
      title: 'Test Pack', description: null, kind: 'static', rating: 'general', category: 'office', pack_type: 'series',
      series: 'office-pain', volume: 2, cover_sticker_id: null,
    })
    expect(created).toMatchObject({ status: 'draft', version: 1, sticker_count: 0 })
    expect(created.slug).toMatch(/^test-pack-[a-z0-9]{4}$/)
    const edited = await api.packs.update(created.id, { title: 'Test Pack 2' })
    expect(edited.version).toBe(2)
    await expect(api.packs.update(created.id, { volume: 1 })).rejects.toMatchObject({ code: '23505' })
    const published = await api.packs.setStatus(created.id, 'published')
    expect(published.status).toBe('published')
    expect(published.published_at).not.toBeNull()
    expect((await api.packs.list({ status: 'published', q: 'test pack' }, PAGE)).total).toBe(1)
  })

  it('a taken-down pack stays down', async () => {
    const pack = db.packs[0]
    await api.takedowns.takeDown('pack', pack.id, 'test')
    await expect(api.packs.setStatus(pack.id, 'published')).rejects.toThrow(/taken down/)
  })
})

describe('take_down RPC twin', () => {
  it('takes a sticker down everywhere, records fingerprints and actions the report', async () => {
    const report = db.reports.find((r) => r.status === 'open' && r.target_type === 'sticker' && r.reason === 'copyright')!
    const sticker = db.stickers.find((s) => s.id === report.target_id)!
    const source = db.sources.find((s) => s.id === sticker.source_id)!
    const packs = db.packs.filter((p) => db.packItems.some((i) => i.pack_id === p.id && i.sticker_id === sticker.id))
    const versions = packs.map((p) => p.version)
    const fingerprints = db.takedowns.length

    await api.takedowns.takeDown('sticker', sticker.id, `report:${report.reason}`, report.id)

    expect(sticker).toMatchObject({ status: 'taken_down', review_note: 'report:copyright' })
    expect(source.status).toBe('taken_down')
    expect(db.takedowns.length).toBe(fingerprints + 2)
    expect(db.takedowns.some((t) => t.sha256 === sticker.sha256)).toBe(true)
    expect(db.takedowns.some((t) => t.source_url === source.source_url)).toBe(true)
    expect(report).toMatchObject({ status: 'actioned' })
    packs.forEach((p, i) => expect(p.version).toBeGreaterThan(versions[i]))
    // the same image can no longer be uploaded
    const reupload = await api.sources.upload({ ...(await upload('x')), sha256: source.sha256 })
    expect(reupload.status).toBe('blocked')
  })
})

describe('reports (demo)', () => {
  it('lists open reports oldest first and resolves all reports about a target', async () => {
    const open = await api.reports.list('open', PAGE)
    const times = open.rows.map((r) => r.created_at)
    expect(times).toEqual([...times].sort())
    const target = open.rows.find((r, _, all) => all.filter((x) => x.target_id === r.target_id).length > 1)!
    await api.reports.resolveTarget(target.target_id, 'dismissed')
    expect(db.reports.filter((r) => r.target_id === target.target_id).every((r) => r.status === 'dismissed')).toBe(true)
    expect(await api.reports.countOpen()).toBe(open.total - 2)
  })
})

describe('trends, settings, auth (demo)', () => {
  it('adds owner topics once per day', async () => {
    const day = istDate(NOW)
    const t = await api.trends.addOwnerTopic({ day, title: 'Chai pe charcha', summary: '', keywords: ['Chai', 'chai', ' '], category: 'hinglish', score: 100 })
    expect(t).toMatchObject({ source: 'owner', active: true, keywords: ['chai'], summary: null })
    await expect(api.trends.addOwnerTopic({ day, title: 'Chai pe charcha', summary: null, keywords: [], category: null, score: 1 })).rejects.toMatchObject({ code: '23505' })
    await api.trends.setActive(t.id, false)
    expect((await api.trends.list(day)).find((x) => x.id === t.id)?.active).toBe(false)
  })

  it('round-trips settings and fills missing fields from defaults', async () => {
    const s = await api.settings.load()
    await api.settings.save('budgets', { ...s.budgets, vision_per_day: 99 })
    expect((await api.settings.load()).budgets.vision_per_day).toBe(99)
    const merged = mergeSettings([{ key: 'publish_policy', value: { auto_publish: false }, updated_at: NOW.toISOString() }])
    expect(merged.publish_policy).toMatchObject({ auto_publish: false, min_quality: 6, celebrities: 'publish' })
    expect(merged.budgets.text_per_day).toBe(400)
  })

  it('gates the panel on public.admins', async () => {
    expect((await api.auth.current())?.email).toBe(DEMO_ADMIN_EMAIL)
    expect(await api.auth.isAdmin()).toBe(true)
    await api.auth.sendLink('someone@else.in')
    expect(await api.auth.isAdmin()).toBe(false)
    await api.auth.signOut()
    expect(await api.auth.current()).toBeNull()
  })

  it('protected people can be added once and removed', async () => {
    await api.protectedPeople.add('Test Person', 'test order')
    await expect(api.protectedPeople.add('Test Person', null)).rejects.toMatchObject({ code: '23505' })
    await api.protectedPeople.remove('Test Person')
    expect((await api.protectedPeople.list()).some((p) => p.name === 'Test Person')).toBe(false)
  })
})
