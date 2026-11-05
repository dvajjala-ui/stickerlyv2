import { type ReactNode } from 'react'
import type { Issue } from '../lib/stickerRules'
import { Icon } from './Icon'

export type Tone = 'neutral' | 'ok' | 'warn' | 'danger' | 'info' | 'volt' | 'ink'

export function Pill({ tone = 'neutral', children, title }: { tone?: Tone; children: ReactNode; title?: string }) {
  return (
    <span className={`pill pill-${tone}`} title={title}>
      {children}
    </span>
  )
}

const STATUS_TONE: Record<string, Tone> = {
  new: 'info',
  screened: 'info',
  analyzing: 'warn',
  analyzed: 'volt',
  used: 'ok',
  rejected: 'neutral',
  failed: 'danger',
  taken_down: 'danger',
  draft: 'warn',
  held: 'warn',
  published: 'ok',
  archived: 'neutral',
  open: 'danger',
  actioned: 'ok',
  dismissed: 'neutral',
}

export function StatusPill({ status }: { status: string }) {
  return <Pill tone={STATUS_TONE[status] ?? 'neutral'}>{status.replace('_', ' ')}</Pill>
}

export function Spinner({ label }: { label?: string }) {
  return (
    <span className="spinner-wrap" role="status">
      <span className="spinner" aria-hidden="true" />
      {label && <span className="muted">{label}</span>}
    </span>
  )
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty-title">{title}</div>
      {children && <div className="muted">{children}</div>}
    </div>
  )
}

export function ErrorNote({ error, onRetry }: { error: Error | string; onRetry?: () => void }) {
  return (
    <div className="note note-danger" role="alert">
      <Icon name="alert" size={16} />
      <span>{typeof error === 'string' ? error : error.message}</span>
      {onRetry && (
        <button className="btn btn-sm" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  )
}

export function Field({ label, hint, error, children }: { label: string; hint?: ReactNode; error?: string | null; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {error ? <span className="field-error">{error}</span> : hint ? <span className="field-hint">{hint}</span> : null}
    </label>
  )
}

export function Toggle({
  checked,
  onChange,
  label,
  disabled,
}: {
  checked: boolean
  onChange: (v: boolean) => void
  label: ReactNode
  disabled?: boolean
}) {
  return (
    <label className={`toggle ${disabled ? 'is-disabled' : ''}`}>
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span className="toggle-track" aria-hidden="true">
        <span className="toggle-knob" />
      </span>
      <span className="toggle-label">{label}</span>
    </label>
  )
}

export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
}: {
  value: T
  options: readonly { value: T; label: ReactNode }[]
  onChange: (v: T) => void
  label: string
}) {
  return (
    <div className="segmented" role="radiogroup" aria-label={label}>
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={o.value === value}
          className={o.value === value ? 'is-on' : ''}
          onClick={() => onChange(o.value)}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Pager({ offset, limit, total, onPage }: { offset: number; limit: number; total: number; onPage: (offset: number) => void }) {
  if (total <= limit && offset === 0) return null
  const end = Math.min(offset + limit, total)
  return (
    <div className="pager">
      <span className="muted">
        {total ? offset + 1 : 0}–{end} of {total}
      </span>
      <button className="btn btn-sm" disabled={offset === 0} onClick={() => onPage(Math.max(0, offset - limit))}>
        <Icon name="left" size={14} /> Prev
      </button>
      <button className="btn btn-sm" disabled={end >= total} onClick={() => onPage(offset + limit)}>
        Next <Icon name="right" size={14} />
      </button>
    </div>
  )
}

export function IssueList({ issues, empty }: { issues: Issue[]; empty?: ReactNode }) {
  if (!issues.length) return empty ? <div className="note note-ok">{empty}</div> : null
  return (
    <ul className="issues">
      {issues.map((i, k) => (
        <li key={`${i.code}-${k}`} className={`issue issue-${i.level}`}>
          <Icon name={i.level === 'error' ? 'x' : 'alert'} size={14} />
          <span>{i.message}</span>
        </li>
      ))}
    </ul>
  )
}

export function Kv({ rows }: { rows: [ReactNode, ReactNode][] }) {
  return (
    <dl className="kv">
      {rows.map(([k, v], i) => (
        <div key={i} className="kv-row">
          <dt>{k}</dt>
          <dd>{v ?? <span className="faint">—</span>}</dd>
        </div>
      ))}
    </dl>
  )
}
