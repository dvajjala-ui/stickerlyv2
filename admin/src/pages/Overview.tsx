import { useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import { SOURCE_STATUSES, type Json } from '../api/types'
import { Icon } from '../components/Icon'
import { ErrorNote, Pill, Spinner, StatusPill } from '../components/ui'
import { fmtDateTime, fmtDay, fmtDuration, istDate, istDayStart, timeAgo } from '../lib/format'
import { useAsync } from '../lib/useAsync'

function compact(v: Json | null): string {
  if (v === null || typeof v !== 'object') return v === null ? '' : String(v)
  if (Array.isArray(v)) return v.map((x) => compact(x)).join(', ')
  return Object.entries(v)
    .map(([k, x]) => (x !== null && typeof x === 'object' && !Array.isArray(x) ? `${k} (${compact(x)})` : `${k.replace(/_/g, ' ')} ${compact(x)}`))
    .join(' · ')
}

function Meter({ label, used, limit, hint }: { label: string; used: number; limit: number; hint?: string }) {
  const ratio = limit > 0 ? used / limit : used > 0 ? Infinity : 0
  const tone = ratio > 1 ? 'danger' : ratio >= 0.8 ? 'warn' : 'ok'
  return (
    <div className="meter">
      <div className="meter-head">
        <span className="meter-label">{label}</span>
        <span className="meter-value">
          <strong>{used}</strong>
          <span className="muted"> / {limit > 0 ? limit : 'off'}</span>
        </span>
      </div>
      <div className={`meter-track meter-${tone}`} role="meter" aria-valuenow={used} aria-valuemin={0} aria-valuemax={limit} aria-label={label}>
        <span style={{ width: `${Math.min(100, (Number.isFinite(ratio) ? ratio : 1) * 100)}%` }} />
      </div>
      {hint && <div className="meter-hint muted">{hint}</div>}
    </div>
  )
}

export function Overview() {
  const [now] = useState(() => new Date())
  const since = istDayStart(now).toISOString()
  const { data, error, loading, reload } = useAsync(async () => {
    const [newSources, analyzed, created, published, packsToday, packsLive, openReports, pipeline, usage, settings, runs] =
      await Promise.all([
        api.sources.countSince('collected_at', since),
        api.sources.countSince('analyzed_at', since),
        api.stickers.countSince('created_at', since),
        api.stickers.countSince('published_at', since),
        api.packs.countSince('published_at', since),
        api.packs.countLive(),
        api.reports.countOpen(),
        api.sources.countByStatus(),
        api.aiCalls.usageSince(since),
        api.settings.load(),
        api.workerRuns.recent(12),
      ])
    return { newSources, analyzed, created, published, packsToday, packsLive, openReports, pipeline, usage, settings, runs }
  }, [since])

  const tiles = data
    ? [
        { label: 'New sources', value: data.newSources, to: '/sources?status=new' },
        { label: 'Analyzed', value: data.analyzed, to: '/sources?status=analyzed&sort=analyzed' },
        { label: 'Stickers made', value: data.created, to: '/stickers?status=draft' },
        { label: 'Published', value: data.published, to: '/stickers?status=published&sort=published' },
        { label: 'Packs published', value: data.packsToday, sub: `${data.packsLive} live`, to: '/packs?status=published' },
        { label: 'Open reports', value: data.openReports, to: '/reports', alert: data.openReports > 0 },
      ]
    : []
  const budgets = data?.settings.budgets

  return (
    <div className="page">
      <div className="page-head">
        <h1>Today</h1>
        <span className="muted">{fmtDay(istDate(now))} · IST</span>
        <div className="spacer" />
        {loading && <Spinner />}
        <button className="btn btn-sm" onClick={reload}>
          <Icon name="refresh" size={14} /> Refresh
        </button>
      </div>
      {error && <ErrorNote error={error} onRetry={reload} />}

      <div className="stats">
        {tiles.map((t) => (
          <Link key={t.label} to={t.to} className={`stat ${t.alert ? 'stat-alert' : ''}`}>
            <span className="stat-value">{t.value}</span>
            <span className="stat-label">{t.label}</span>
            {t.sub && <span className="stat-sub">{t.sub}</span>}
          </Link>
        ))}
        {!data && !error && Array.from({ length: 6 }, (_, i) => <div key={i} className="stat stat-skeleton" />)}
      </div>

      {data && budgets && (
        <div className="grid-2">
          <section className="card">
            <h2>AI calls today vs budget</h2>
            <Meter label="Vision (analyze)" used={data.usage.vision} limit={budgets.vision_per_day} />
            <Meter label="Text (captions, judging, titles, trends)" used={data.usage.text} limit={budgets.text_per_day} />
            <Meter label="Image generation" used={data.usage.image} limit={budgets.image_gen_per_day} />
            <Meter label="New stickers" used={data.created} limit={budgets.max_new_stickers_per_day} />
            <p className="muted small">
              {data.usage.total} calls today, {data.usage.failed} failed. Budgets live in{' '}
              <Link to="/settings">Settings</Link>.
            </p>
          </section>
          <section className="card">
            <h2>Source pipeline</h2>
            <div className="pipeline">
              {SOURCE_STATUSES.map((s) => (
                <Link key={s} to={`/sources?status=${s}`} className="pipeline-step">
                  <span className="pipeline-count">{data.pipeline[s]}</span>
                  <StatusPill status={s} />
                </Link>
              ))}
            </div>
            <p className="muted small">new → screened → analyzing → analyzed → used. Failed rows stop after 5 attempts.</p>
          </section>
        </div>
      )}

      {data && (
        <section className="card">
          <h2>Last worker runs</h2>
          {data.runs.length === 0 ? (
            <p className="muted">No runs recorded yet.</p>
          ) : (
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Stage</th>
                    <th>Started</th>
                    <th>Took</th>
                    <th>Result</th>
                    <th>Summary</th>
                  </tr>
                </thead>
                <tbody>
                  {data.runs.map((r) => (
                    <tr key={r.id}>
                      <td>
                        <strong>{r.stage}</strong>
                      </td>
                      <td title={fmtDateTime(r.started_at)}>{timeAgo(r.started_at)}</td>
                      <td>{r.finished_at ? fmtDuration(new Date(r.finished_at).getTime() - new Date(r.started_at).getTime()) : '—'}</td>
                      <td>
                        {r.ok === null ? <Pill tone="warn">running</Pill> : r.ok ? <Pill tone="ok">ok</Pill> : <Pill tone="danger">failed</Pill>}
                      </td>
                      <td className="cell-summary">{compact(r.summary)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </div>
  )
}
