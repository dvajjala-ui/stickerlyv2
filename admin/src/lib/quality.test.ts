import { describe, expect, it } from 'vitest'
import { STICKER_SELECT } from '../api/shared'
import { DEFAULT_SETTINGS, HELD_DECISION_STATUS, STICKER_COLUMNS, STICKER_STATUSES, type QualityGate } from '../api/types'
import {
  describeReason,
  duplicateOf,
  effectiveQualityGate,
  gateTone,
  heldReviewReport,
  judgeRange,
  judgeSummary,
  parseGateNote,
  qualityGateError,
  reasonLabels,
} from './quality'

describe('held status constants', () => {
  it('match the quality gate migration', () => {
    // stickers_status_check in 20261105000003_quality_gate.sql
    expect([...STICKER_STATUSES].sort()).toEqual(['draft', 'held', 'published', 'rejected', 'taken_down'])
    expect(HELD_DECISION_STATUS).toEqual({ approve: 'published', reject: 'rejected' })
  })

  it('select the four quality columns everywhere stickers are loaded', () => {
    for (const col of ['quality_score', 'quality_reasons', 'quality_signals', 'render_phash'] as const) {
      expect(STICKER_COLUMNS).toContain(col)
      expect(STICKER_SELECT.split(',')).toContain(col)
    }
  })
})

describe('quality reason labels', () => {
  // Every reason imaging/quality.py and pipeline/gate.py can write, with the worker's HARD set.
  const WORKER_REASONS = [
    'blank', 'mostly_transparent', 'tiny_subject', 'small_subject', 'blurry', 'soft', 'caption_clipped',
    'caption_low_contrast', 'near_duplicate', 'animation_too_short', 'animation_few_frames', 'animation_choppy',
    'quality_check_failed',
  ]
  const HARD = ['blank', 'mostly_transparent', 'tiny_subject', 'near_duplicate', 'caption_clipped', 'caption_low_contrast', 'animation_too_short']

  it('gives every worker reason a short label and a hint', () => {
    for (const code of WORKER_REASONS) {
      const r = describeReason(code)
      expect(r.label).not.toContain('_')
      expect(r.label.length).toBeLessThanOrEqual(22)
      expect(r.hint.length).toBeGreaterThan(10)
    }
    expect(reasonLabels(['blurry', 'tiny_subject', 'caption_low_contrast', 'near_duplicate'])).toEqual([
      'Blurry', 'Tiny subject', 'Caption hard to read', 'Near-duplicate',
    ])
  })

  it('marks hard (score-capping) reasons like the worker', () => {
    for (const code of HARD) expect(describeReason(code).hard).toBe(true)
    for (const code of ['small_subject', 'blurry', 'soft', 'animation_few_frames', 'animation_choppy']) expect(describeReason(code).hard).toBe(false)
  })

  it('labels vision judge issues and keeps unknown codes readable', () => {
    expect(describeReason('judge:pixelated')).toMatchObject({ label: 'Judge: pixelated', judge: true, hard: false })
    expect(describeReason('judge:text_covers_subject').label).toBe('Judge: text covers the subject')
    expect(describeReason('judge:new_issue').label).toBe('Judge: new issue')
    expect(describeReason('fancy_new_check')).toMatchObject({ label: 'Fancy new check', hard: false, judge: false })
  })

  it('tones a score against the current minimum', () => {
    expect(gateTone(null, 60)).toBe('neutral')
    expect(gateTone(60, 60)).toBe('ok')
    expect(gateTone(55, 60, ['blurry'])).toBe('warn')
    expect(gateTone(35, 60, ['tiny_subject'])).toBe('danger')
    expect(gateTone(55, 50, ['blurry'])).toBe('ok') // held under a stricter minimum
  })
})

describe('worker notes and signals', () => {
  it('parses the hold note, also after other review notes', () => {
    expect(parseGateNote('quality_gate:55<60:blurry')).toEqual({ score: 55, min: 60, reasons: ['blurry'] })
    expect(parseGateNote('celebrity_review,quality_gate:53<60:small_subject+soft+judge:pixelated')).toEqual({
      score: 53, min: 60, reasons: ['small_subject', 'soft', 'judge:pixelated'],
    })
    expect(parseGateNote('quality_gate:0<60:quality_check_failed')?.reasons).toEqual(['quality_check_failed'])
    expect(parseGateNote('Too blurry')).toBeNull()
    expect(parseGateNote(null)).toBeNull()
  })

  it('reads the judge verdict and the duplicate match from quality_signals', () => {
    expect(judgeSummary({ judge: { score: 3, issues: ['pixelated'] }, local_score: 76 })).toEqual({
      state: 'scored', score: 3, issues: ['pixelated'], localScore: 76,
    })
    expect(judgeSummary({ judge: { skipped: 'vision_budget_exhausted' } })).toEqual({ state: 'skipped', why: "today's vision budget was spent" })
    expect(judgeSummary({ sharpness: 8.7 })).toBeNull()
    expect(judgeSummary(null)).toBeNull()
    expect(duplicateOf({ duplicate_of: 'abc', duplicate_distance: 4 })).toEqual({ id: 'abc', distance: 4 })
    expect(duplicateOf({ duplicate_of: '' })).toBeNull()
    expect(duplicateOf(['not', 'an', 'object'])).toBeNull()
  })
})

describe('quality_gate settings', () => {
  const ok: QualityGate = DEFAULT_SETTINGS.quality_gate

  it('accepts the migration seed and whole scores from 0 to 100', () => {
    expect(ok).toEqual({ enabled: true, min_score: 60, vision_judge: false, judge_band: 20 })
    expect(qualityGateError(ok)).toBeNull()
    expect(qualityGateError({ ...ok, min_score: 0, judge_band: 100 })).toBeNull()
    expect(qualityGateError({ ...ok, min_score: 100, judge_band: 0 })).toBeNull()
  })

  it('rejects out-of-range, fractional, empty and non-numeric values', () => {
    for (const min_score of [-1, 101, 60.5, Number.NaN]) expect(qualityGateError({ ...ok, min_score })).toMatch(/Minimum score/)
    for (const judge_band of [-5, 120, 2.5, Number.NaN]) expect(qualityGateError({ ...ok, judge_band })).toMatch(/Judge band/)
    expect(qualityGateError({ ...ok, min_score: '60' as unknown as number })).toMatch(/Minimum score/)
    expect(qualityGateError({ ...ok, vision_judge: 'yes' as unknown as boolean })).toMatch(/on or off/)
  })

  it('reads a stored row the way the worker does (defaults for junk, clamped numbers)', () => {
    expect(effectiveQualityGate(undefined)).toEqual(ok)
    expect(effectiveQualityGate({ min_score: 150, judge_band: -3, enabled: 'no', vision_judge: true })).toEqual({
      enabled: true, min_score: 100, vision_judge: true, judge_band: 0,
    })
    expect(effectiveQualityGate({ min_score: '70', judge_band: 12.9 })).toMatchObject({ min_score: 60, judge_band: 12 })
  })

  it('shows which scores the vision judge sees', () => {
    expect(judgeRange({ min_score: 60, judge_band: 20 })).toEqual([40, 79])
    expect(judgeRange({ min_score: 60, judge_band: 100 })).toEqual([0, 100])
    expect(judgeRange({ min_score: 10, judge_band: 20 })).toEqual([0, 29])
    expect(judgeRange({ min_score: 60, judge_band: 0 })).toBeNull()
    expect(judgeRange({ min_score: Number.NaN, judge_band: 20 })).toBeNull()
  })
})

describe('held review report', () => {
  const rows = [
    { id: 'a1', caption: 'Kitna late hai yaar' },
    { id: 'b2', caption: 'Raat abhi baaki hai' },
    { id: 'c3', caption: null },
    { id: 'd4', caption: 'Chai break?' },
    { id: 'e5', caption: 'Bas 5 minute' },
  ]

  it('is a plain success when every sticker changed', () => {
    const r = heldReviewReport('approve', rows, [], { done: ['a1', 'b2'], notHeld: [], failed: [] })
    expect(r).toEqual({ message: 'Approved 2 · live on Discover; the next compose run packs them', tone: 'ok', notice: null })
    expect(heldReviewReport('reject', rows, [], { done: ['a1'], notHeld: [], failed: [] }).message).toBe('Rejected 1')
  })

  it('lists every sticker that did not change, with why', () => {
    const r = heldReviewReport(
      'approve',
      rows,
      [{ id: 'b2', reasons: ['Needs 1–3 emojis (WhatsApp requires at least one)'] }],
      { done: ['a1'], notHeld: ['c3'], failed: [{ ids: ['d4', 'e5'], message: 'Approve held stickers: network error, check your connection' }] },
    )
    expect(r.tone).toBe('error') // a request failed
    expect(r.message).toBe('Approved 1 of 5 · 4 not approved (see list)')
    expect(r.notice?.title).toBe('Not approved (4 of 5)')
    expect(r.notice?.items).toEqual([
      { caption: 'Raat abhi baaki hai', reasons: ['Needs 1–3 emojis (WhatsApp requires at least one)'] },
      { caption: 'c3', reasons: ['No longer held (changed elsewhere); left as it is'] },
      { caption: 'Chai break?', reasons: ['Not saved: Approve held stickers: network error, check your connection'] },
      { caption: 'Bas 5 minute', reasons: ['Not saved: Approve held stickers: network error, check your connection'] },
    ])
  })

  it('stays ok when the only misses were changed elsewhere, and is an error when nothing changed', () => {
    const some = heldReviewReport('reject', rows, [], { done: ['a1'], notHeld: ['b2'], failed: [] })
    expect(some).toMatchObject({ tone: 'ok', message: 'Rejected 1 of 2 · 1 not rejected (see list)' })
    const none = heldReviewReport('reject', rows, [], { done: [], notHeld: ['a1', 'b2'], failed: [] })
    expect(none).toMatchObject({ tone: 'error', message: 'Nothing rejected · 2 not rejected (see list)' })
  })
})
