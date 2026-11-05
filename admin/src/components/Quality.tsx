import type { ReactNode } from 'react'
import type { StickerRow } from '../api/types'
import { describeReason, duplicateOf, gateTone, judgeSummary, parseGateNote, reasonLabels, type GateTone } from '../lib/quality'
import { JsonView } from './JsonView'
import { Kv, Pill } from './ui'

type Scored = Pick<StickerRow, 'quality_score' | 'quality_reasons'>

const FLAG_CLASS: Record<GateTone, string> = { ok: 'flag-ok', warn: 'flag-warn', danger: 'flag-danger', neutral: '' }

function scoreTitle(s: Scored, minScore: number): string {
  const labels = reasonLabels(s.quality_reasons)
  return `Quality gate score ${s.quality_score}/100 (minimum ${minScore})${labels.length ? `: ${labels.join(', ')}` : ''}`
}

/** "gate 55/100" pill for the drawer. */
export function GateScorePill({ s, minScore }: { s: Scored; minScore: number }) {
  if (s.quality_score === null) return null
  return (
    <Pill tone={gateTone(s.quality_score, minScore, s.quality_reasons)} title={scoreTitle(s, minScore)}>
      gate {s.quality_score}/100
    </Pill>
  )
}

/** Score chip for a tile's flag row. */
export function GateScoreFlag({ s, minScore }: { s: Scored; minScore: number }) {
  if (s.quality_score === null) return null
  return (
    <span className={FLAG_CLASS[gateTone(s.quality_score, minScore, s.quality_reasons)]} title={scoreTitle(s, minScore)}>
      gate {s.quality_score}
    </span>
  )
}

/** One line of short reason labels for a tile (full list in the tooltip). */
export function GateReasonsLine({ reasons }: { reasons: string[] }) {
  if (!reasons.length) return null
  const labels = reasonLabels(reasons)
  return (
    <div className="tile-reasons" title={labels.join('\n')}>
      {labels.join(' · ')}
    </div>
  )
}

/** Why a held sticker is held, from its score, reasons and the worker's review note. */
export function HeldNote({ s, minScore }: { s: Pick<StickerRow, 'quality_score' | 'quality_reasons' | 'review_note'>; minScore: number }) {
  const note = parseGateNote(s.review_note)
  const score = s.quality_score ?? note?.score ?? null
  const min = note?.min ?? minScore
  const labels = reasonLabels(s.quality_reasons.length ? s.quality_reasons : (note?.reasons ?? []))
  return (
    <div className="note note-warn">
      <span>
        Held by the quality gate{score !== null && <>: scored {score}, below the minimum of {min}</>}
        {labels.length > 0 && <> ({labels.join(', ')})</>}. Approving publishes it; the next compose run packs it.
        {note && note.min !== minScore && <> The minimum is now {minScore}.</>}
      </span>
    </div>
  )
}

/** Drawer section: score, readable reasons, judge verdict, duplicate link and the raw signals (collapsed). */
export function QualitySection({ s, minScore, onOpenSticker }: { s: StickerRow; minScore: number; onOpenSticker: (id: string) => void }) {
  if (s.quality_score === null && !s.quality_reasons.length && s.quality_signals === null) {
    return <p className="muted small">Not scored: made before the quality gate, or not made by the worker.</p>
  }
  const reasons = s.quality_reasons.map(describeReason)
  const judge = judgeSummary(s.quality_signals)
  const dup = duplicateOf(s.quality_signals)
  const rows: [ReactNode, ReactNode][] = [
    [
      'Score',
      s.quality_score === null ? null : (
        <span key="score" className="row">
          <GateScorePill s={s} minScore={minScore} />
          <span className="muted small">minimum now {minScore}</span>
        </span>
      ),
    ],
    [
      'Reasons',
      reasons.length ? (
        <ul key="reasons" className="reason-list">
          {reasons.map((r) => (
            <li key={r.code}>
              <Pill tone={r.hard ? 'danger' : 'warn'}>{r.label}</Pill>
              <span className="muted small">
                {r.hint}
                {r.hard && ' · hard problem, the score is capped'}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        'none'
      ),
    ],
  ]
  if (judge) {
    rows.push([
      'Vision judge',
      judge.state === 'skipped'
        ? `not asked: ${judge.why}`
        : [
            `${judge.score}/10`,
            judge.issues.length ? judge.issues.map((i) => describeReason(`judge:${i}`).label.replace(/^Judge: /, '')).join(', ') : null,
            judge.localScore !== null ? `blended 50/50 with the local score ${judge.localScore}` : null,
          ]
            .filter(Boolean)
            .join(' · '),
    ])
  }
  if (dup) {
    rows.push([
      'Near-duplicate of',
      <button key="dup" type="button" className="btn-link" onClick={() => onOpenSticker(dup.id)}>
        open that sticker{dup.distance !== null && ` (${dup.distance} bit${dup.distance === 1 ? '' : 's'} apart)`}
      </button>,
    ])
  }
  return (
    <>
      <Kv rows={rows} />
      <details className="json-block">
        <summary>quality_signals (raw JSON)</summary>
        <JsonView value={s.quality_signals} />
      </details>
    </>
  )
}
