import { useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import type { PackRow, Report, ReportStatus, StickerRow } from '../api/types'
import { Icon } from '../components/Icon'
import { StickerImage } from '../components/Media'
import { Empty, ErrorNote, Pill, Segmented, Spinner, StatusPill } from '../components/ui'
import { ageHours, fmtDateTime, timeAgo } from '../lib/format'
import { announceReportsChanged } from '../lib/reference'
import { useToast } from '../lib/toast'
import { toError, useAsync } from '../lib/useAsync'

const REASON_LABEL: Record<Report['reason'], string> = {
  copyright: 'Copyright',
  person: 'My photo / a real person',
  hate: 'Hate or abuse',
  sexual: 'Sexual content',
  minor: 'Involves a child',
  other: 'Other',
}
const URGENT = new Set<Report['reason']>(['minor', 'sexual'])

interface Group {
  key: string
  type: Report['target_type']
  id: string
  reports: Report[]
  sticker?: StickerRow
  pack?: PackRow | null
}

function slaTone(hours: number): 'ok' | 'warn' | 'danger' {
  return hours >= 24 ? 'danger' : hours >= 6 ? 'warn' : 'ok'
}

async function loadQueue(status: ReportStatus): Promise<{ groups: Group[]; total: number }> {
  const page = await api.reports.list(status, { offset: 0, limit: 200 })
  const byTarget = new Map<string, Group>()
  for (const r of page.rows) {
    const key = `${r.target_type}:${r.target_id}`
    const g = byTarget.get(key) ?? { key, type: r.target_type, id: r.target_id, reports: [] }
    g.reports.push(r)
    byTarget.set(key, g)
  }
  const groups = [...byTarget.values()]
  const stickerIds = groups.filter((g) => g.type === 'sticker').map((g) => g.id)
  const [stickers, packs] = await Promise.all([
    api.stickers.byIds(stickerIds),
    Promise.all(groups.filter((g) => g.type === 'pack').map((g) => api.packs.get(g.id))),
  ])
  const packById = new Map(packs.filter((p): p is PackRow => !!p).map((p) => [p.id, p]))
  for (const g of groups) {
    if (g.type === 'sticker') g.sticker = stickers.find((s) => s.id === g.id)
    else g.pack = packById.get(g.id) ?? null
  }
  return { groups, total: page.total }
}

export function Reports() {
  const toast = useToast()
  const [status, setStatus] = useState<ReportStatus>('open')
  const [busyKey, setBusyKey] = useState<string | null>(null)
  const queue = useAsync(() => loadQueue(status), [status])

  const resolve = async (g: Group, action: 'takedown' | 'dismiss' | 'close') => {
    setBusyKey(g.key)
    try {
      if (action === 'takedown') {
        const first = g.reports[0]
        const reason = `report:${first.reason}${first.details ? ` — ${first.details.slice(0, 160)}` : ''}`
        await api.takedowns.takeDown(g.type, g.id, reason, first.id) // also records the fingerprint
        await api.reports.resolveTarget(g.id, 'actioned')
        toast(`Taken down · fingerprint recorded · ${g.reports.length} report${g.reports.length === 1 ? '' : 's'} closed`)
      } else if (action === 'close') {
        await api.reports.resolveTarget(g.id, 'actioned')
        toast('Reports closed')
      } else {
        await api.reports.dismiss(g.reports.map((r) => r.id))
        toast('Dismissed')
      }
      queue.setData((d) => ({
        groups: (d?.groups ?? []).filter((x) => x.key !== g.key),
        total: Math.max(0, (d?.total ?? 0) - g.reports.length),
      }))
      announceReportsChanged()
    } catch (e) {
      toast(toError(e).message, 'error')
    } finally {
      setBusyKey(null)
    }
  }

  const groups = queue.data?.groups ?? []

  return (
    <div className="page">
      <div className="page-head">
        <h1>Reports</h1>
        <span className="muted">{queue.data ? `${queue.data.total} ${status}` : ''}</span>
        <div className="spacer" />
        {queue.loading && <Spinner />}
        <button className="btn btn-sm" onClick={queue.reload}>
          <Icon name="refresh" size={14} /> Refresh
        </button>
      </div>
      <Segmented<ReportStatus>
        label="Report status"
        value={status}
        onChange={setStatus}
        options={[
          { value: 'open', label: 'Open' },
          { value: 'actioned', label: 'Taken down' },
          { value: 'dismissed', label: 'Dismissed' },
        ]}
      />
      {status === 'open' && (
        <p className="muted small">
          Oldest first. Indian IT Rules expect fast action on complaints, so a takedown is one click: it removes the item
          everywhere, records its fingerprint so it can't be collected again, and closes every report about it.
        </p>
      )}
      {queue.error && <ErrorNote error={queue.error} onRetry={queue.reload} />}
      {queue.data && groups.length === 0 && <Empty title={status === 'open' ? 'No open reports' : 'Nothing here'}>{status === 'open' ? 'All clear.' : ''}</Empty>}

      <div className="report-list">
        {groups.map((g) => {
          const oldest = g.reports.reduce((a, r) => (r.created_at < a ? r.created_at : a), g.reports[0].created_at)
          const hours = ageHours(oldest)
          const targetStatus = g.sticker?.status ?? g.pack?.status
          const alreadyDown = targetStatus === 'taken_down'
          const busy = busyKey === g.key
          const urgent = g.reports.some((r) => URGENT.has(r.reason))
          return (
            <article key={g.key} className={`report ${urgent && status === 'open' ? 'report-urgent' : ''}`}>
              <div className="report-target">
                {g.sticker ? (
                  <StickerImage src={api.stickers.publicUrl(g.sticker.file_path)} alt={g.sticker.caption ?? 'sticker'} size={96} />
                ) : g.pack?.cover ? (
                  <StickerImage src={api.stickers.publicUrl(g.pack.cover.file_path)} alt={g.pack.title} size={96} />
                ) : (
                  <div className="media-box report-missing">
                    <span className="media-missing">{g.type}</span>
                  </div>
                )}
              </div>
              <div className="report-body">
                <div className="report-title">
                  <Pill tone="ink">{g.type}</Pill>
                  {g.sticker && (
                    <Link to={`/stickers?status=${g.sticker.status}&id=${g.sticker.id}`}>
                      <strong>{g.sticker.caption || 'Sticker'}</strong>
                    </Link>
                  )}
                  {g.pack && (
                    <Link to={`/packs/${g.pack.id}`}>
                      <strong>{g.pack.title}</strong>
                    </Link>
                  )}
                  {!g.sticker && !g.pack && <span className="faint">target no longer exists</span>}
                  {targetStatus && <StatusPill status={targetStatus} />}
                  {g.sticker?.rating === 'adult' || g.pack?.rating === 'adult' ? <Pill tone="danger">18+</Pill> : null}
                  {status === 'open' && (
                    <Pill tone={slaTone(hours)} title={`Oldest report ${fmtDateTime(oldest)}`}>
                      waiting {timeAgo(oldest).replace(' ago', '')}
                    </Pill>
                  )}
                </div>
                <ul className="report-items">
                  {g.reports.map((r) => (
                    <li key={r.id}>
                      <Pill tone={URGENT.has(r.reason) ? 'danger' : 'warn'}>{REASON_LABEL[r.reason]}</Pill>
                      <span className="report-details">{r.details || <span className="faint">no details</span>}</span>
                      <span className="muted small" title={fmtDateTime(r.created_at)}>
                        {timeAgo(r.created_at)}
                        {r.resolved_at && <> · resolved {timeAgo(r.resolved_at)}</>}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
              {status === 'open' && (
                <div className="report-actions">
                  {alreadyDown ? (
                    <button className="btn" disabled={busy} onClick={() => void resolve(g, 'close')}>
                      Already down · close
                    </button>
                  ) : (
                    <button className="btn btn-danger" disabled={busy || (!g.sticker && !g.pack)} onClick={() => void resolve(g, 'takedown')}>
                      <Icon name="shield" size={15} /> Take down
                    </button>
                  )}
                  <button className="btn btn-ghost" disabled={busy} onClick={() => void resolve(g, 'dismiss')}>
                    Dismiss
                  </button>
                </div>
              )}
            </article>
          )
        })}
      </div>
    </div>
  )
}
