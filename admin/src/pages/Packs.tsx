import { Link } from 'react-router'
import { api } from '../api'
import { PACK_STATUSES, PACK_TYPES, type PackFilter, type PackStatus, type PackType } from '../api/types'
import { Icon } from '../components/Icon'
import { StickerImage } from '../components/Media'
import { SearchBox } from '../components/SearchBox'
import { Empty, ErrorNote, Pager, Pill, Spinner, StatusPill } from '../components/ui'
import { timeAgo } from '../lib/format'
import { useUrlState } from '../lib/params'
import { useAsync } from '../lib/useAsync'

const PAGE = 50

export function Packs() {
  const { get, num, set } = useUrlState()
  const filter: PackFilter = {
    pack_type: get('type') as PackType | undefined,
    status: get('status') as PackStatus | undefined,
    q: get('q'),
  }
  const offset = num('offset') ?? 0
  const list = useAsync(() => api.packs.list(filter, { offset, limit: PAGE }), [filter.pack_type, filter.status, filter.q, offset])
  const rows = list.data?.rows ?? []

  return (
    <div className="page">
      <div className="page-head">
        <h1>Packs</h1>
        <span className="muted">{list.data ? `${list.data.total} packs` : ''}</span>
        <div className="spacer" />
        {list.loading && <Spinner />}
        <Link to="/packs/new" className="btn btn-volt">
          <Icon name="plus" size={15} /> New pack
        </Link>
      </div>

      <div className="toolbar">
        <SearchBox value={filter.q ?? ''} onSearch={(q) => set({ q })} placeholder="Search pack titles…" />
        <label className="filter">
          <span>type</span>
          <select className="select" value={filter.pack_type ?? ''} onChange={(e) => set({ type: e.target.value })}>
            <option value="">any</option>
            {PACK_TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </label>
        <label className="filter">
          <span>status</span>
          <select className="select" value={filter.status ?? ''} onChange={(e) => set({ status: e.target.value })}>
            <option value="">any</option>
            {PACK_STATUSES.map((s) => (
              <option key={s} value={s}>
                {s.replace('_', ' ')}
              </option>
            ))}
          </select>
        </label>
      </div>

      {list.error && <ErrorNote error={list.error} onRetry={list.reload} />}
      {list.data && rows.length === 0 && <Empty title="No packs match">Create one with “New pack”.</Empty>}

      <div className="pack-list">
        {rows.map((p) => (
          <Link key={p.id} to={`/packs/${p.id}`} className="pack-row">
            <span className="pack-cover">{p.cover ? <StickerImage src={api.stickers.publicUrl(p.cover.file_path)} alt="" /> : <span className="media-missing">—</span>}</span>
            <span className="pack-title">
              <strong>{p.title}</strong>
              <span className="muted small">
                {p.series ? `${p.series} · vol ${p.volume}` : p.category ?? ''}
              </span>
            </span>
            <span className="pack-tags">
              <Pill>{p.pack_type}</Pill>
              <Pill tone={p.kind === 'animated' ? 'info' : 'neutral'}>{p.kind}</Pill>
              {p.rating === 'adult' && <Pill tone="danger">18+</Pill>}
            </span>
            <span className="pack-count" title="Published stickers in the pack: what phones see">
              <strong>{p.sticker_count}</strong> <span className="muted small">live</span>
            </span>
            <span className="pack-version muted small">v{p.version}</span>
            <span className="pack-status">
              <StatusPill status={p.status} />
            </span>
            <span className="pack-updated muted small">{timeAgo(p.updated_at)}</span>
          </Link>
        ))}
      </div>
      {list.data && <Pager offset={offset} limit={PAGE} total={list.data.total} onPage={(o) => set({ offset: o || null })} />}
    </div>
  )
}
