// The worker's quality gate as the panel shows it: readable reasons, the hold note, the raw
// signals, the settings card's rules, and the summary of a bulk review of held stickers.
// Reasons and thresholds follow backend/worker/README.md (stage 3) and imaging/quality.py.

import {
  DEFAULT_SETTINGS,
  type HeldDecision,
  type HeldReviewOutcome,
  type Json,
  type QualityGate,
  type Uuid,
} from '../api/types'

export const QUALITY_SCORE_MAX = 100

export interface ReasonInfo {
  code: string
  label: string
  hint: string
  /** Caps the score at 35 (5 for blank), so no vision judge can lift it over the minimum. */
  hard: boolean
  /** Added by the vision judge (`judge:<issue>`). */
  judge: boolean
}

const REASONS: Record<string, Omit<ReasonInfo, 'code' | 'judge'>> = {
  blank: { label: 'Blank', hint: 'Nothing visible, or an empty flat card', hard: true },
  mostly_transparent: { label: 'Mostly transparent', hint: 'Under 8% of the canvas is visible', hard: true },
  tiny_subject: { label: 'Tiny subject', hint: 'The subject covers under 12% of the canvas', hard: true },
  small_subject: { label: 'Small subject', hint: 'The subject covers 12–30% of the canvas', hard: false },
  blurry: { label: 'Blurry', hint: 'Almost no sharp detail inside the subject', hard: false },
  soft: { label: 'Slightly soft', hint: 'Less sharp detail inside the subject than a crisp sticker', hard: false },
  caption_clipped: { label: 'Caption cut off', hint: 'The caption runs past the edge of the canvas', hard: true },
  caption_low_contrast: {
    label: 'Caption hard to read',
    hint: "Caption contrast is under 3:1 on WhatsApp's light or dark chat background",
    hard: true,
  },
  near_duplicate: { label: 'Near-duplicate', hint: 'Looks like a published or draft sticker from another source', hard: true },
  animation_too_short: { label: 'Animation too short', hint: 'The whole loop lasts under 300 ms, so it flickers', hard: true },
  animation_few_frames: { label: 'Too few frames', hint: 'The animation has 2 frames or fewer', hard: false },
  animation_choppy: { label: 'Choppy animation', hint: 'Middle frames last over 250 ms (under 4 fps)', hard: false },
  // Not in the worker's HARD set, but its cap is 0, which is just as final.
  quality_check_failed: { label: 'Check failed', hint: 'The worker could not decode or score the file', hard: true },
  low_score: { label: 'Low score', hint: 'Scored below the minimum without a specific reason', hard: false },
}

/** Issues the vision judge may report (worker ai/schemas.py QUALITY_ISSUES, plus its low-score fallback). */
const JUDGE_ISSUES: Record<string, string> = {
  blurry: 'blurry',
  pixelated: 'pixelated',
  subject_cut_off: 'subject cut off',
  subject_too_small: 'subject too small',
  unreadable_text: 'unreadable text',
  text_cut_off: 'text cut off',
  text_covers_subject: 'text covers the subject',
  compression_artifacts: 'compression artefacts',
  watermark: 'watermark',
  mostly_empty: 'mostly empty',
  other: 'other problem',
  low_score: 'low score',
}

function humanize(code: string): string {
  const words = code.replace(/[_-]+/g, ' ').trim()
  return words ? words[0].toUpperCase() + words.slice(1) : code
}

/** Short human label and explanation for one `quality_reasons` entry (unknown codes still read well). */
export function describeReason(code: string): ReasonInfo {
  if (code.startsWith('judge:')) {
    const issue = code.slice('judge:'.length)
    const text = JUDGE_ISSUES[issue] ?? humanize(issue).toLowerCase()
    return { code, label: `Judge: ${text}`, hint: `The vision judge (Groq) reported: ${text}`, hard: false, judge: true }
  }
  const known = REASONS[code]
  if (known) return { code, ...known, judge: false }
  return { code, label: humanize(code), hint: `Worker reason “${code}”`, hard: false, judge: false }
}

export function reasonLabels(codes: readonly string[]): string[] {
  return codes.map((c) => describeReason(c).label)
}

export type GateTone = 'ok' | 'warn' | 'danger' | 'neutral'

/** ok at/above the minimum; below it danger when a hard reason caps the score, else warn. */
export function gateTone(score: number | null, minScore: number, reasons: readonly string[] = []): GateTone {
  if (score === null) return 'neutral'
  if (score >= minScore) return 'ok'
  return reasons.some((r) => describeReason(r).hard) ? 'danger' : 'warn'
}

export interface GateNote {
  score: number
  /** The minimum when the worker held it (settings may have changed since). */
  min: number
  reasons: string[]
}

/** Reads the worker's hold note, e.g. `quality_gate:55<60:blurry+soft` (possibly after other comma-separated notes). */
export function parseGateNote(note: string | null | undefined): GateNote | null {
  const m = note?.match(/(?:^|,)\s*quality_gate:(\d{1,3})<(\d{1,3}):([^,]*)/)
  if (!m) return null
  return { score: Number(m[1]), min: Number(m[2]), reasons: m[3].split('+').filter(Boolean) }
}

function asObject(v: Json | null | undefined): { [key: string]: Json } | null {
  return v !== null && typeof v === 'object' && !Array.isArray(v) ? v : null
}

const JUDGE_SKIPS: Record<string, string> = {
  vision_budget_exhausted: "today's vision budget was spent",
  no_ai_provider: 'no AI provider was configured',
  ai_unavailable: 'the AI provider was unavailable',
  judge_error: 'the judge returned an error',
}

export type JudgeSummary =
  | { state: 'scored'; score: number; issues: string[]; localScore: number | null }
  | { state: 'skipped'; why: string }

/** The vision judge's verdict from quality_signals.judge, if it was asked. */
export function judgeSummary(signals: Json | null | undefined): JudgeSummary | null {
  const s = asObject(signals)
  const judge = asObject(s?.judge)
  if (!s || !judge) return null
  if (typeof judge.skipped === 'string') return { state: 'skipped', why: JUDGE_SKIPS[judge.skipped] ?? judge.skipped.replace(/_/g, ' ') }
  if (typeof judge.score !== 'number') return null
  const issues = Array.isArray(judge.issues) ? judge.issues.filter((i): i is string => typeof i === 'string') : []
  return { state: 'scored', score: judge.score, issues, localScore: typeof s.local_score === 'number' ? s.local_score : null }
}

/** The sticker a near_duplicate verdict points at (quality_signals.duplicate_of / duplicate_distance). */
export function duplicateOf(signals: Json | null | undefined): { id: Uuid; distance: number | null } | null {
  const s = asObject(signals)
  if (!s || typeof s.duplicate_of !== 'string' || !s.duplicate_of) return null
  return { id: s.duplicate_of, distance: typeof s.duplicate_distance === 'number' ? s.duplicate_distance : null }
}

// ------------------------------------------------------------------ settings.quality_gate

function isScore(v: unknown): v is number {
  return typeof v === 'number' && Number.isInteger(v) && v >= 0 && v <= QUALITY_SCORE_MAX
}

/** Why the card can't save, or null. Mirrors the ranges the worker accepts (settings.py). */
export function qualityGateError(v: QualityGate): string | null {
  if (typeof v.enabled !== 'boolean' || typeof v.vision_judge !== 'boolean') return 'Switches must be on or off'
  if (!isScore(v.min_score)) return 'Minimum score must be a whole number from 0 to 100'
  if (!isScore(v.judge_band)) return 'Judge band must be a whole number from 0 to 100'
  return null
}

/**
 * What the worker actually applies for a stored row: like settings.py, invalid values fall back to
 * the defaults and numbers are clamped to 0–100 (and truncated).
 */
export function effectiveQualityGate(raw: Partial<Record<keyof QualityGate, unknown>> | null | undefined): QualityGate {
  const d = DEFAULT_SETTINGS.quality_gate
  const bool = (v: unknown, dflt: boolean) => (typeof v === 'boolean' ? v : dflt)
  const int = (v: unknown, dflt: number) =>
    typeof v === 'number' && Number.isFinite(v) ? Math.max(0, Math.min(QUALITY_SCORE_MAX, Math.trunc(v))) : dflt
  return {
    enabled: bool(raw?.enabled, d.enabled),
    min_score: int(raw?.min_score, d.min_score),
    vision_judge: bool(raw?.vision_judge, d.vision_judge),
    judge_band: int(raw?.judge_band, d.judge_band),
  }
}

/** Scores the vision judge is asked about: [min − band, min + band), as whole scores; null when empty (band 0). */
export function judgeRange(v: Pick<QualityGate, 'min_score' | 'judge_band'>): [number, number] | null {
  if (!isScore(v.min_score) || !isScore(v.judge_band) || v.judge_band === 0) return null
  return [Math.max(0, v.min_score - v.judge_band), Math.min(QUALITY_SCORE_MAX, v.min_score + v.judge_band - 1)]
}

// ------------------------------------------------------------------ bulk review of held stickers

export interface ReviewNotice {
  title: string
  items: { caption: string; reasons: string[] }[]
}

export interface ReviewReport {
  message: string
  tone: 'ok' | 'error'
  /** Every selected sticker that did not change, with why; null when all of them did. */
  notice: ReviewNotice | null
}

/**
 * Summary of approving/rejecting held stickers: `blocked` were never sent (an approval would break a
 * publish rule), the outcome says what happened to the rest.
 */
export function heldReviewReport(
  decision: HeldDecision,
  rows: readonly { id: Uuid; caption: string | null }[],
  blocked: readonly { id: Uuid; reasons: string[] }[],
  outcome: HeldReviewOutcome,
): ReviewReport {
  const caption = (id: Uuid) => rows.find((r) => r.id === id)?.caption || id.slice(0, 8)
  const items = [
    ...blocked.map((b) => ({ caption: caption(b.id), reasons: b.reasons })),
    ...outcome.notHeld.map((id) => ({ caption: caption(id), reasons: ['No longer held (changed elsewhere); left as it is'] })),
    ...outcome.failed.flatMap((f) => f.ids.map((id) => ({ caption: caption(id), reasons: [`Not saved: ${f.message}`] }))),
  ]
  const verb = decision === 'approve' ? 'Approved' : 'Rejected'
  const done = outcome.done.length
  const total = done + items.length
  const notice = items.length ? { title: `Not ${verb.toLowerCase()} (${items.length} of ${total})`, items } : null
  if (!items.length) {
    const next = decision === 'approve' ? ' · live on Discover; the next compose run packs them' : ''
    return { message: `${verb} ${done}${next}`, tone: 'ok', notice }
  }
  const failed = outcome.failed.length > 0
  const message = done
    ? `${verb} ${done} of ${total} · ${items.length} not ${verb.toLowerCase()} (see list)`
    : `Nothing ${verb.toLowerCase()} · ${items.length} not ${verb.toLowerCase()} (see list)`
  return { message, tone: failed || !done ? 'error' : 'ok', notice }
}
