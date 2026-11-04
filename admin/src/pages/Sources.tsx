import { useCallback, useMemo, useState, type DragEvent, type ReactNode } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import { NSFW_LEVELS, SOURCE_KINDS, SOURCE_STATUSES, type Nsfw, type SourceFilter, type SourceSort, type SourceStatus, type SourceSummary, type TriState } from '../api/types'
import { Drawer } from '../components/Drawer'
import { Icon } from '../components/Icon'
import { JsonView } from '../components/JsonView'
import { SourceMedia, StickerImage } from '../components/Media'
import { SearchBox } from '../components/SearchBox'
import { Empty, ErrorNote, Kv, Pager, Pill, Spinner, StatusPill } from '../components/ui'
import { fmtBytes, fmtDateTime, fmtDuration, safeHref, timeAgo } from '../lib/format'
import { prepareUpload } from '../lib/media'
import { useSelection, useUrlState } from '../lib/params'
import { categoryOptions, useReference } from '../lib/reference'
import { useToast } from '../lib/toast'
import { toError, useAsync } from '../lib/useAsync'

const PAGE = 48
const TRI: { value: TriState; label: string }[] = [
  { value: 'any', label: 'any' },
  { value: 'yes', label: 'yes' },
  { value: 'no', label: 'no' },
  { value: 'unknown', label: 'unknown' },
]

function Select({ label, value, options, onChange }: { label: string; value: string; options: (string | { value: string; label: string })[]; onChange: (v: string) => void }) {
  return (
    <label className="filter">
      <span>{label}</span>
      <select className="select" value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">any</option>
        {options.map((o) => {
          const opt = typeof o === 'string' ? { value: o, label: o.replace(/_/g, ' ') } : o
          return (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          )
        })}
      </select>
    </label>
  )
}

// ------------------------------------------------------------------ uploads

interface UploadItem {
  key: number
  name: string
  state: 'queued' | 'reading' | 'uploading' | 'created' | 'duplicate' | 'blocked' | 'error'
  detail?: string
  id?: string
}

let uploadSeq = 0

function useUploader(onFinished: () => void) {
  const [items, setItems] = useState<UploadItem[]>([])
  const update = (key: number, patch: Partial<UploadItem>) => setItems((all) => all.map((it) => (it.key === key ? { ...it, ...patch } : it)))
  const run = useCallback(
    async (files: File[]) => {
      if (!files.length) return
      const keys = files.map(() => ++uploadSeq)
      const batch = files.map((f, i) => ({ key: keys[i], name: f.name, state: 'queued' as const }))
      setItems((all) => [...batch, ...all].slice(0, 200))
      for (const [i, file] of files.entries()) {
        const key = keys[i]
        update(key, { state: 'reading' })
        try {
          const prepared = await prepareUpload(file)
          update(key, { state: 'uploading', detail: `${prepared.mime} · ${fmtBytes(prepared.bytes)}` })
          const out = await api.sources.upload(prepared)
          if (out.status === 'created') update(key, { state: 'created', id: out.id, detail: 'queued as new' })
          else if (out.status === 'duplicate') update(key, { state: 'duplicate', id: out.id, detail: 'already in sources' })
          else update(key, { state: 'blocked', detail: out.reason })
        } catch (e) {
          update(key, { state: 'error', detail: toError(e).message })
        }
      }
      onFinished()
    },
    [onFinished],
  )
  return { items, run, clear: () => setItems([]) }
}

const UPLOAD_TONE: Record<UploadItem['state'], 'neutral' | 'ok' | 'warn' | 'danger' | 'info'> = {
  queued: 'neutral', reading: 'info', uploading: 'info', created: 'ok', duplicate: 'warn', blocked: 'danger', error: 'danger',
}

function UploadPanel({ items, onFiles, onClear, onOpen }: { items: UploadItem[]; onFiles: (f: File[]) => void; onClear: () => void; onOpen: (id: string) => void }) {
  const [over, setOver] = useState(false)
  return (
    <section className="card upload">
      <label
        className={`dropzone ${over ? 'is-over' : ''}`}
        onDragOver={(e) => {
          e.preventDefault()
          setOver(true)
        }}
        onDragLeave={() => setOver(false)}
        onDrop={(e) => {
          e.preventDefault()
          e.stopPropagation()
          setOver(false)
          onFiles([...e.dataTransfer.files])
        }}
      >
        <Icon name="upload" size={22} />
        <span>
          <strong>Drop images or clips here</strong> or click to choose. PNG, JPEG, WebP, GIF, MP4, WebM · max 10 MB each.
        </span>
        <span className="muted small">
          Hashed (SHA-256) in your browser first: duplicates and taken-down images are refused before anything uploads.
        </span>
        <input
          type="file"
          multiple
          accept="image/png,image/jpeg,image/webp,image/gif,video/mp4,video/webm"
          onChange={(e) => {
            onFiles([...(e.target.files ?? [])])
            e.target.value = ''
          }}
        />
      </label>
      {items.length > 0 && (
        <>
          <ul className="upload-list">
            {items.map((it) => (
              <li key={it.key}>
                <span className="upload-name" title={it.name}>
                  {it.name}
                </span>
                <Pill tone={UPLOAD_TONE[it.state]}>{it.state}</Pill>
                <span className="muted small">{it.detail}</span>
                {it.id && (
                  <button className="btn-link" onClick={() => onOpen(it.id!)}>
                    open
                  </button>
                )}
              </li>
            ))}
          </ul>
          <button className="btn btn-sm btn-ghost" onClick={onClear}>
            Clear list
          </button>
        </>
      )}
    </section>
  )
}

// ------------------------------------------------------------------ detail drawer

function SourceDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const { data, error, loading, reload } = useAsync(async () => {
    const src = await api.sources.get(id)
    if (!src) return null
    const [urls, stickers] = await Promise.all([api.sources.signedUrls([src.storage_path]), api.stickers.bySource(id)])
    return { src, url: urls[src.storage_path], stickers }
  }, [id])

  const act = async (action: 'reject' | 'reanalyze') => {
    setBusy(true)
    try {
      await api.sources.apply([id], action)
      toast(action === 'reject' ? 'Source rejected' : 'Queued for re-analysis (status screened)')
      reload()
      onChanged()
    } catch (e) {
      toast(toError(e).message, 'error')
    } finally {
      setBusy(false)
    }
  }

  const s = data?.src
  const link = (url: string | null) => {
    const href = safeHref(url)
    return href ? (
      <a href={href} target="_blank" rel="noopener noreferrer" className="link-break">
        {url} <Icon name="external" size={12} />
      </a>
    ) : (
      url
    )
  }
  const flag = (v: boolean | null, yes: ReactNode, tone: 'danger' | 'warn' | 'info' = 'warn') =>
    v === null ? <Pill>unknown</Pill> : v ? <Pill tone={tone}>{yes}</Pill> : <Pill>no</Pill>

  return (
    <Drawer
      title={s ? <>Source · {s.site}</> : 'Source'}
      onClose={onClose}
      wide
      footer={
        s && (
          <>
            <button className="btn" disabled={busy || s.status === 'taken_down' || s.status === 'new'} onClick={() => void act('reanalyze')} title="Sets status back to screened so the worker analyzes it again">
              <Icon name="refresh" size={15} /> Re-analyze
            </button>
            <button className="btn btn-danger-outline" disabled={busy || s.status === 'rejected' || s.status === 'taken_down'} onClick={() => void act('reject')}>
              Reject
            </button>
            {data?.url && (
              <a className="btn btn-ghost" href={data.url} target="_blank" rel="noopener noreferrer">
                Original <Icon name="external" size={13} />
              </a>
            )}
          </>
        )
      }
    >
      {loading && !data && <Spinner label="Loading…" />}
      {error && <ErrorNote error={error} onRetry={reload} />}
      {data === null && <Empty title="Source not found" />}
      {s && (
        <div className="detail">
          <div className="detail-media">
            <SourceMedia src={data?.url} mime={s.mime} nsfw={s.nsfw} alt={s.description ?? 'source image'} controls />
          </div>
          <div className="pill-row">
            <StatusPill status={s.status} />
            {s.sticker_score !== null && <Pill tone="ink">score {s.sticker_score}/10</Pill>}
            {s.kind && <Pill>{s.kind}</Pill>}
            {s.category && <Pill>{s.category}</Pill>}
            {s.nsfw && s.nsfw !== 'none' && <Pill tone={s.nsfw === 'explicit' ? 'danger' : 'warn'}>{s.nsfw}</Pill>}
            {s.minors && <Pill tone="danger">minor</Pill>}
            {s.public_figure && <Pill tone="warn">{s.public_figure}</Pill>}
          </div>
          {s.description && <p className="lead">{s.description}</p>}
          {s.last_error && <div className="note note-danger">Last error: {s.last_error}</div>}

          <h3>Provenance</h3>
          <Kv
            rows={[
              ['Site', s.site],
              ['Source URL', link(s.source_url)],
              ['Page URL', link(s.page_url)],
              ['Author', s.author],
              ['License', s.license ? <>{s.license} <span className="faint">(as reported, unverified)</span></> : null],
              ['Collector', s.collector],
              ['Topic hint', s.topic_hint],
              ['Collected', fmtDateTime(s.collected_at)],
            ]}
          />

          <h3>AI analysis</h3>
          <Kv
            rows={[
              ['Kind / category', [s.kind, s.category].filter(Boolean).join(' · ') || null],
              ['Tags', s.tags.length ? s.tags.join(', ') : null],
              ['Mood', s.mood.length ? s.mood.join(', ') : null],
              ['Emojis', s.emojis.length ? s.emojis.join(' ') : null],
              ['Has text', flag(s.has_text, 'yes', 'info')],
              ['Text (OCR)', s.ocr_text],
              ['Language', s.language],
              ['NSFW', s.nsfw],
              ['Minors', flag(s.minors, 'yes', 'danger')],
              ['Real person', flag(s.real_person, 'yes')],
              ['Public figure', s.public_figure],
              ['Analyzed', s.analyzed_at ? fmtDateTime(s.analyzed_at) : null],
            ]}
          />
          <details className="json-block" open={!!s.analysis}>
            <summary>analysis (raw JSON)</summary>
            <JsonView value={s.analysis} />
          </details>
          <details className="json-block">
            <summary>prescreen (score {s.prescreen_score ?? '—'})</summary>
            <JsonView value={s.prescreen} />
          </details>

          <h3>Stickers made from it</h3>
          {data.stickers.length === 0 ? (
            <p className="muted small">None yet.</p>
          ) : (
            <div className="mini-grid">
              {data.stickers.map((st) => (
                <Link key={st.id} to={`/stickers?status=${st.status}&id=${st.id}`} className="mini" title={st.caption ?? ''}>
                  <StickerImage src={api.stickers.publicUrl(st.file_path)} alt={st.caption ?? 'sticker'} />
                  <StatusPill status={st.status} />
                </Link>
              ))}
            </div>
          )}

          <h3>File</h3>
          <Kv
            rows={[
              ['Type', s.mime],
              ['Size', `${fmtBytes(s.bytes)}${s.width ? ` · ${s.width}×${s.height}` : ''}`],
              ['Frames / duration', `${s.frames} · ${fmtDuration(s.duration_ms)}`],
              ['SHA-256', <code key="sha" className="hash">{s.sha256}</code>],
              ['pHash', s.phash ? <code key="phash" className="hash">{s.phash}</code> : null],
              ['Storage', <code key="path" className="hash">sources/{s.storage_path}</code>],
              ['Attempts', `${s.attempts}${s.locked_until ? ` · locked until ${fmtDateTime(s.locked_until)}` : ''}`],
            ]}
          />
        </div>
      )}
    </Drawer>
  )
}

// ------------------------------------------------------------------ page

export function Sources() {
  const { params, get, num, set } = useUrlState()
  const toast = useToast()
  const ref = useReference()
  const [showUpload, setShowUpload] = useState(false)
  const [busy, setBusy] = useState(false)

  const filter: SourceFilter = useMemo(
    () => ({
      status: get('status') as SourceStatus | undefined,
      site: get('site'),
      kind: get('kind'),
      category: get('cat'),
      nsfw: get('nsfw') as Nsfw | 'unknown' | undefined,
      minors: get('minors') as TriState | undefined,
      hasText: get('text') as TriState | undefined,
      scoreMin: num('smin'),
      scoreMax: num('smax'),
      q: get('q'),
      sort: (get('sort') as SourceSort | undefined) ?? 'collected',
    }),
    [get, num],
  )
  const offset = num('offset') ?? 0
  const openId = get('id')
  const filterKey = JSON.stringify(filter)

  const list = useAsync(() => api.sources.list(filter, { offset, limit: PAGE }), [filterKey, offset])
  const sites = useAsync(() => api.sources.sites(), [])
  const rows: SourceSummary[] = useMemo(() => list.data?.rows ?? [], [list.data])
  const paths = rows.map((r) => r.storage_path).join('|')
  const urls = useAsync<Record<string, string>>(
    () => (rows.length ? api.sources.signedUrls(rows.map((r) => r.storage_path)) : Promise.resolve({})),
    [paths],
  )
  const ids = useMemo(() => rows.map((r) => r.id), [rows])
  const sel = useSelection(ids)

  const reloadList = list.reload
  const uploader = useUploader(useCallback(() => reloadList(), [reloadList]))
  const onPageDrop = (e: DragEvent) => {
    if (!e.dataTransfer.files.length) return
    e.preventDefault()
    setShowUpload(true)
    void uploader.run([...e.dataTransfer.files])
  }

  const bulk = async (action: 'reject' | 'reanalyze') => {
    setBusy(true)
    try {
      await api.sources.apply(sel.ids, action)
      toast(`${sel.count} source${sel.count === 1 ? '' : 's'} ${action === 'reject' ? 'rejected' : 'queued for re-analysis'}`)
      sel.clear()
      list.reload()
    } catch (e) {
      toast(toError(e).message, 'error')
    } finally {
      setBusy(false)
    }
  }

  const activeFilters = ['status', 'site', 'kind', 'cat', 'nsfw', 'minors', 'text', 'smin', 'smax', 'q'].filter((k) => params.get(k))

  return (
    <div className="page" onDragOver={(e) => e.dataTransfer.types.includes('Files') && e.preventDefault()} onDrop={onPageDrop}>
      <div className="page-head">
        <h1>Sources</h1>
        <span className="muted">{list.data ? `${list.data.total} images` : ''}</span>
        <div className="spacer" />
        {list.loading && <Spinner />}
        <button className={`btn ${showUpload ? 'btn-ink' : 'btn-volt'}`} onClick={() => setShowUpload((v) => !v)}>
          <Icon name="upload" size={15} /> Upload
        </button>
      </div>

      {showUpload && <UploadPanel items={uploader.items} onFiles={(f) => void uploader.run(f)} onClear={uploader.clear} onOpen={(id) => set({ id })} />}

      <div className="toolbar">
        <SearchBox value={filter.q ?? ''} onSearch={(q) => set({ q })} placeholder="Search description, tags, text, topic…" />
        <label className="filter">
          <span>sort</span>
          <select className="select" value={filter.sort} onChange={(e) => set({ sort: e.target.value === 'collected' ? null : e.target.value })}>
            <option value="collected">newest</option>
            <option value="score">sticker score</option>
            <option value="analyzed">recently analyzed</option>
          </select>
        </label>
      </div>
      <details className="filters" open={activeFilters.length > 0 || undefined}>
        <summary>
          <Icon name="filter" size={14} /> Filters{activeFilters.length ? ` (${activeFilters.length})` : ''}
        </summary>
        <div className="filters-grid">
          <Select label="status" value={filter.status ?? ''} options={[...SOURCE_STATUSES]} onChange={(v) => set({ status: v })} />
          <Select label="site" value={filter.site ?? ''} options={sites.data ?? []} onChange={(v) => set({ site: v })} />
          <Select label="kind" value={filter.kind ?? ''} options={[...SOURCE_KINDS]} onChange={(v) => set({ kind: v })} />
          <Select label="category" value={filter.category ?? ''} options={categoryOptions(ref.categories, filter.category)} onChange={(v) => set({ cat: v })} />
          <Select label="nsfw" value={filter.nsfw ?? ''} options={[...NSFW_LEVELS, 'unknown']} onChange={(v) => set({ nsfw: v })} />
          <Select label="minors" value={filter.minors ?? ''} options={TRI.slice(1)} onChange={(v) => set({ minors: v })} />
          <Select label="has text" value={filter.hasText ?? ''} options={TRI.slice(1)} onChange={(v) => set({ text: v })} />
          <label className="filter">
            <span>score</span>
            <span className="range">
              <input className="input input-num" type="number" min={0} max={10} placeholder="0" value={filter.scoreMin ?? ''} onChange={(e) => set({ smin: e.target.value })} aria-label="Minimum score" />
              –
              <input className="input input-num" type="number" min={0} max={10} placeholder="10" value={filter.scoreMax ?? ''} onChange={(e) => set({ smax: e.target.value })} aria-label="Maximum score" />
            </span>
          </label>
          {activeFilters.length > 0 && (
            <button className="btn btn-sm btn-ghost" onClick={() => set(Object.fromEntries(activeFilters.map((k) => [k, null])))}>
              Clear filters
            </button>
          )}
        </div>
      </details>

      {list.error && <ErrorNote error={list.error} onRetry={list.reload} />}
      {list.data && rows.length === 0 && <Empty title="No sources match">Try fewer filters, or upload images.</Empty>}

      <div className="tiles">
        {rows.map((r) => {
          const selected = sel.selected.has(r.id)
          return (
            <article key={r.id} className={`tile ${selected ? 'is-selected' : ''}`}>
              <div className="tile-media">
                <SourceMedia src={urls.data?.[r.storage_path]} mime={r.mime} nsfw={r.nsfw} alt={r.description ?? r.site} />
              </div>
              <button
                className="tile-check"
                aria-pressed={selected}
                aria-label={selected ? 'Deselect' : 'Select'}
                onClick={(e) => {
                  e.stopPropagation()
                  sel.toggle(r.id, e.shiftKey)
                }}
              >
                <Icon name="check" size={14} />
              </button>
              <div className="tile-badges">
                <StatusPill status={r.status} />
                {r.sticker_score !== null && <Pill tone="ink">{r.sticker_score}</Pill>}
              </div>
              <div className="tile-meta">
                <button type="button" className="tile-title tile-open" title={r.description ?? ''} onClick={() => set({ id: r.id })}>
                  {r.description ?? <span className="faint">not analyzed yet</span>}
                </button>
                <div className="tile-sub">
                  {r.site} · {timeAgo(r.collected_at)}
                </div>
                <div className="tile-flags">
                  {r.kind && <span>{r.kind}</span>}
                  {r.has_text && <span title="has text">T</span>}
                  {r.nsfw && r.nsfw !== 'none' && <span className={r.nsfw === 'explicit' ? 'flag-danger' : 'flag-warn'}>{r.nsfw}</span>}
                  {r.minors && <span className="flag-danger">minor</span>}
                  {r.public_figure && <span className="flag-warn">{r.public_figure}</span>}
                </div>
              </div>
            </article>
          )
        })}
      </div>

      {list.data && <Pager offset={offset} limit={PAGE} total={list.data.total} onPage={(o) => set({ offset: o || null })} />}

      {sel.count > 0 && (
        <div className="bulkbar" role="region" aria-label="Selection actions">
          <span>
            <strong>{sel.count}</strong> selected
          </span>
          <button className="btn btn-sm" onClick={sel.selectAll}>
            All on page
          </button>
          <div className="spacer" />
          <button className="btn btn-sm" disabled={busy} onClick={() => void bulk('reanalyze')}>
            Re-analyze
          </button>
          <button className="btn btn-sm btn-danger-outline" disabled={busy} onClick={() => void bulk('reject')}>
            Reject
          </button>
          <button className="btn-icon" onClick={sel.clear} aria-label="Clear selection">
            <Icon name="x" />
          </button>
        </div>
      )}

      {openId && <SourceDrawer id={openId} onClose={() => set({ id: null })} onChanged={list.reload} />}
    </div>
  )
}
