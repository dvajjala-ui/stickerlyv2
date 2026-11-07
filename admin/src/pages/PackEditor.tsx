import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api } from '../api'
import { PACK_TYPES, type PackInput, type PackRow, type PackType, type Rating, type StickerKind, type StickerRow } from '../api/types'
import { Icon } from '../components/Icon'
import { StickerImage } from '../components/Media'
import { SearchBox } from '../components/SearchBox'
import { Empty, ErrorNote, Field, IssueList, Pill, Segmented, Spinner, StatusPill, Toggle } from '../components/ui'
import { fmtDateTime } from '../lib/format'
import { PACK_MAX, PACK_MIN, checkPack, hasErrors } from '../lib/packRules'
import { categoryOptions, useReference } from '../lib/reference'
import { errorsOf, stickerIssues } from '../lib/stickerRules'
import { useToast } from '../lib/toast'
import { toError, useAsync } from '../lib/useAsync'
import { StickerBadges, WaBadge } from './Stickers'

const BLANK: PackInput = {
  title: '',
  description: null,
  kind: 'static',
  rating: 'general',
  category: null,
  pack_type: 'manual',
  series: null,
  volume: 1,
  cover_sticker_id: null,
}

function inputOf(p: PackRow): PackInput {
  return {
    title: p.title,
    description: p.description,
    kind: p.kind,
    rating: p.rating,
    category: p.category,
    pack_type: p.pack_type,
    series: p.series,
    volume: p.volume,
    cover_sticker_id: p.cover_sticker_id,
  }
}

export function PackEditor() {
  const { id = 'new' } = useParams()
  const navigate = useNavigate()
  const [round, setRound] = useState(0)
  const loaded = useAsync(async () => {
    if (id === 'new') return { pack: null, items: [] as StickerRow[] }
    const [pack, items] = await Promise.all([api.packs.get(id), api.packs.items(id)])
    return { pack, items: items.map((i) => i.sticker) }
  }, [id, round])

  if (loaded.error) return <ErrorNote error={loaded.error} onRetry={loaded.reload} />
  if (!loaded.data || loaded.loading) return <Spinner label="Loading pack…" />
  if (id !== 'new' && !loaded.data.pack) return <Empty title="Pack not found"><Link to="/packs">Back to packs</Link></Empty>
  return (
    <PackForm
      key={`${id}:${round}`}
      pack={loaded.data.pack}
      initialItems={loaded.data.items}
      onSaved={(savedId) => {
        if (savedId !== id) navigate(`/packs/${savedId}`, { replace: true })
        else setRound((r) => r + 1)
      }}
    />
  )
}

function PackForm({ pack, initialItems, onSaved }: { pack: PackRow | null; initialItems: StickerRow[]; onSaved: (id: string) => void }) {
  const toast = useToast()
  const ref = useReference()
  const original = useMemo(() => (pack ? inputOf(pack) : BLANK), [pack])
  const [meta, setMeta] = useState<PackInput>(original)
  const [items, setItems] = useState<StickerRow[]>(initialItems)
  const [busy, setBusy] = useState(false)
  const [dragFrom, setDragFrom] = useState<number | null>(null)
  const [showPublishCheck, setShowPublishCheck] = useState(false)

  const isNew = !pack
  const ids = items.map((s) => s.id)
  const metaDirty = JSON.stringify(meta) !== JSON.stringify(original)
  const itemsDirty = ids.join(',') !== initialItems.map((s) => s.id).join(',')
  const dirty = isNew || metaDirty || itemsDirty
  const readOnly = pack?.status === 'taken_down'

  useEffect(() => {
    if (!dirty || isNew) return
    const warn = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty, isNew])

  const draftShape = { title: meta.title, kind: meta.kind, rating: meta.rating, cover_sticker_id: meta.cover_sticker_id, stickers: items }
  // Saving a published pack must enforce the publish rules (>=3 stickers, no oversize/protected/held), or a save
  // that drops it below 3 would keep it `published` here while Discover hides it (sticker_count >= 3) — it vanishes.
  const saveIssues = checkPack(draftShape, { forPublish: pack?.status === 'published', protectedNames: ref.protectedNames })
  const publishIssues = checkPack(draftShape, { forPublish: true, protectedNames: ref.protectedNames })
  const shownIssues = showPublishCheck ? publishIssues : saveIssues
  const flagged = new Set(shownIssues.filter((i) => i.level === 'error').flatMap((i) => i.stickerIds ?? []))
  const drafts = items.filter((s) => s.status === 'draft')
  const publishableDrafts = drafts.filter((s) => errorsOf(stickerIssues(s, { protectedNames: ref.protectedNames })).length === 0)

  const edit = (patch: Partial<PackInput>) => setMeta((m) => ({ ...m, ...patch }))
  const move = (from: number, to: number) => {
    if (to < 0 || to >= items.length || from === to) return
    setItems((list) => {
      const next = [...list]
      const [it] = next.splice(from, 1)
      next.splice(to, 0, it)
      return next
    })
  }
  const remove = (sid: string) => setItems((list) => list.filter((s) => s.id !== sid))
  const add = (s: StickerRow) => {
    if (items.length >= PACK_MAX) return toast(`A pack holds at most ${PACK_MAX} stickers`, 'error')
    if (ids.includes(s.id)) return
    setItems((list) => [...list, s])
  }

  const withBusy = async (fn: () => Promise<void>) => {
    setBusy(true)
    try {
      await fn()
    } catch (e) {
      toast(toError(e).message, 'error')
    } finally {
      setBusy(false)
    }
  }

  /** Saves metadata + order; returns the pack id. Composition errors block saving. */
  const persist = async (): Promise<string> => {
    if (hasErrors(saveIssues)) throw new Error('Fix the errors listed above before saving')
    const cover = meta.cover_sticker_id && ids.includes(meta.cover_sticker_id) ? meta.cover_sticker_id : (ids[0] ?? null)
    const input: PackInput = {
      ...meta,
      title: meta.title.trim(),
      description: meta.description?.trim() || null,
      series: meta.series?.trim() || null,
      cover_sticker_id: cover,
    }
    if (!pack) {
      const created = await api.packs.create(input)
      if (ids.length) await api.packs.setItems(created.id, ids)
      return created.id
    }
    if (metaDirty || cover !== original.cover_sticker_id) await api.packs.update(pack.id, input)
    if (itemsDirty) await api.packs.setItems(pack.id, ids)
    return pack.id
  }

  const save = () =>
    withBusy(async () => {
      const pid = await persist()
      toast(isNew ? 'Pack created' : 'Pack saved · version bumped')
      onSaved(pid)
    })

  const publish = () =>
    withBusy(async () => {
      setShowPublishCheck(true)
      if (hasErrors(publishIssues)) throw new Error('Not published: fix the errors listed above')
      const pid = dirty ? await persist() : pack!.id
      await api.packs.setStatus(pid, 'published')
      toast('Pack published')
      onSaved(pid)
    })

  const setStatus = (status: 'draft' | 'archived', msg: string) =>
    withBusy(async () => {
      if (!pack) return
      await api.packs.setStatus(pack.id, status)
      toast(msg)
      onSaved(pack.id)
    })

  const publishDrafts = () =>
    withBusy(async () => {
      const toPublish = publishableDrafts.map((s) => s.id)
      await api.stickers.setStatus(toPublish, 'published')
      setItems((list) => list.map((s) => (toPublish.includes(s.id) ? { ...s, status: 'published' } : s)))
      toast(`Published ${toPublish.length} sticker${toPublish.length === 1 ? '' : 's'}`)
    })

  return (
    <div className="page">
      <div className="page-head">
        <Link to="/packs" className="btn-icon" aria-label="Back to packs">
          <Icon name="left" />
        </Link>
        <h1>{isNew ? 'New pack' : meta.title || 'Untitled pack'}</h1>
        {pack && <StatusPill status={pack.status} />}
        {pack && (
          <span className="muted small" title="Phones re-sync a pack when its version changes">
            v{pack.version} · {pack.sticker_count} live
          </span>
        )}
        <div className="spacer" />
        {busy && <Spinner />}
        {dirty && !isNew && <Pill tone="warn">unsaved</Pill>}
      </div>

      {readOnly && <div className="note note-danger">This pack was taken down. It can't be edited or republished.</div>}

      <div className="editor">
        <section className="card editor-meta">
          <fieldset className="form" disabled={readOnly || busy}>
            <Field label="Title" error={meta.title.length > 128 ? 'Max 128 characters' : null}>
              <input className="input" value={meta.title} maxLength={140} onChange={(e) => edit({ title: e.target.value })} placeholder="Office Pain" autoFocus={isNew} />
            </Field>
            <Field label="Description">
              <textarea className="input" rows={2} value={meta.description ?? ''} onChange={(e) => edit({ description: e.target.value })} />
            </Field>
            <div className="form-row">
              <Field label="Type">
                <select className="select" value={meta.pack_type} onChange={(e) => edit({ pack_type: e.target.value as PackType })}>
                  {PACK_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {t}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Category">
                <select className="select" value={meta.category ?? ''} onChange={(e) => edit({ category: e.target.value || null })}>
                  <option value="">—</option>
                  {categoryOptions(ref.categories, meta.category).map((c) => (
                    <option key={c} value={c}>
                      {c.replace(/_/g, ' ')}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
            <div className="form-row">
              <Field label="Series key" hint="Growing series, e.g. office-pain">
                <input className="input" value={meta.series ?? ''} onChange={(e) => edit({ series: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '-') || null })} />
              </Field>
              <Field label="Volume">
                <input className="input input-num" type="number" min={1} value={meta.volume} onChange={(e) => edit({ volume: Math.max(1, Number(e.target.value) || 1) })} />
              </Field>
            </div>
            <div className="form-row">
              <Field label="Kind" hint="WhatsApp packs are all static or all animated">
                <Segmented<StickerKind> label="Kind" value={meta.kind} options={[{ value: 'static', label: 'Static' }, { value: 'animated', label: 'Animated' }]} onChange={(kind) => edit({ kind })} />
              </Field>
              <Field label="Rating">
                <Segmented<Rating> label="Rating" value={meta.rating} options={[{ value: 'general', label: 'General' }, { value: 'adult', label: '18+' }]} onChange={(rating) => edit({ rating })} />
              </Field>
            </div>
          </fieldset>

          <div className="editor-check">
            <div className="row-between">
              <strong>{showPublishCheck ? 'Publish check' : 'Checks'}</strong>
              <Toggle checked={showPublishCheck} onChange={setShowPublishCheck} label="as if publishing" />
            </div>
            <IssueList issues={shownIssues} empty={showPublishCheck ? 'Ready to publish' : 'No problems'} />
            {drafts.length > 0 && (
              <button className="btn btn-sm" disabled={busy || readOnly || publishableDrafts.length === 0} onClick={() => void publishDrafts()}>
                Publish {publishableDrafts.length} of {drafts.length} draft sticker{drafts.length === 1 ? '' : 's'}
              </button>
            )}
          </div>

          {!readOnly && (
            <div className="editor-actions">
              <button className="btn btn-primary" disabled={busy || !dirty || !ref.ready || hasErrors(saveIssues)} onClick={() => void save()}>
                {isNew ? 'Create pack' : 'Save'}
              </button>
              {pack?.status !== 'published' && pack?.status !== 'archived' && (
                <button className="btn btn-volt" disabled={busy || !ref.ready} onClick={() => void publish()}>
                  Publish
                </button>
              )}
              {pack?.status === 'published' && (
                <button className="btn" disabled={busy || dirty} onClick={() => void setStatus('draft', 'Unpublished')}>
                  Unpublish
                </button>
              )}
              {pack && pack.status !== 'archived' && (
                <button className="btn btn-ghost" disabled={busy || dirty} onClick={() => void setStatus('archived', 'Archived')}>
                  Archive
                </button>
              )}
              {pack?.status === 'archived' && (
                <button className="btn" disabled={busy || dirty} onClick={() => void setStatus('draft', 'Restored to draft')}>
                  Restore to draft
                </button>
              )}
            </div>
          )}
          {!readOnly && !ref.ready && (
            <p className="muted small">Loading publish rules (protected names, settings)…</p>
          )}
          {!readOnly && dirty && pack && (
            <p className="muted small">Save or discard your changes to unpublish, archive or restore this pack.</p>
          )}
          {pack && (
            <p className="muted small">
              Created {fmtDateTime(pack.created_at)} · updated {fmtDateTime(pack.updated_at)}
              {pack.published_at && <> · first published {fmtDateTime(pack.published_at)}</>}
            </p>
          )}
        </section>

        <section className="card editor-items">
          <div className="row-between">
            <h2>
              Stickers <span className="muted">{items.length}/{PACK_MAX}</span>
            </h2>
            <span className="muted small">
              {PACK_MIN}–{PACK_MAX}. Drag to reorder · ★ = cover (tray icon)
            </span>
          </div>
          {items.length === 0 && <Empty title="No stickers yet">Add some from the search below.</Empty>}
          <ol className="order">
            {items.map((s, i) => {
              const isCover = (meta.cover_sticker_id ?? ids[0]) === s.id
              return (
                <li
                  key={s.id}
                  className={`order-item ${flagged.has(s.id) ? 'is-flagged' : ''} ${dragFrom === i ? 'is-dragging' : ''}`}
                  draggable={!readOnly}
                  onDragStart={(e) => {
                    setDragFrom(i)
                    e.dataTransfer.effectAllowed = 'move'
                    e.dataTransfer.setData('text/plain', String(i))
                  }}
                  onDragOver={(e) => {
                    e.preventDefault()
                    e.dataTransfer.dropEffect = 'move'
                  }}
                  onDrop={(e) => {
                    e.preventDefault()
                    if (dragFrom !== null) move(dragFrom, i)
                    setDragFrom(null)
                  }}
                  onDragEnd={() => setDragFrom(null)}
                  title={flagged.has(s.id) ? shownIssues.filter((x) => x.stickerIds?.includes(s.id)).map((x) => x.message).join('\n') : undefined}
                >
                  <span className="order-pos">{i + 1}</span>
                  <span className="order-grip" aria-hidden="true">
                    <Icon name="grip" size={14} />
                  </span>
                  <StickerImage src={api.stickers.publicUrl(s.file_path)} alt={s.caption ?? 'sticker'} size={64} />
                  <span className="order-text">
                    <span className="order-caption">{s.caption || <span className="faint">no caption</span>}</span>
                    <span className="pill-row">
                      <StickerBadges s={s} />
                      <WaBadge s={s} />
                    </span>
                  </span>
                  {!readOnly && (
                    <span className="order-actions">
                      <button className={`btn-icon ${isCover ? 'is-on' : ''}`} onClick={() => edit({ cover_sticker_id: s.id })} aria-label="Use as cover" title="Use as cover">
                        <Icon name="star" size={16} />
                      </button>
                      <button className="btn-icon" disabled={i === 0} onClick={() => move(i, i - 1)} aria-label="Move up">
                        <Icon name="up" size={16} />
                      </button>
                      <button className="btn-icon" disabled={i === items.length - 1} onClick={() => move(i, i + 1)} aria-label="Move down">
                        <Icon name="down" size={16} />
                      </button>
                      <button className="btn-icon" onClick={() => remove(s.id)} aria-label="Remove from pack">
                        <Icon name="x" size={16} />
                      </button>
                    </span>
                  )}
                </li>
              )
            })}
          </ol>

          {!readOnly && <AddStickers kind={meta.kind} rating={meta.rating} exclude={ids} onAdd={add} full={items.length >= PACK_MAX} />}
        </section>
      </div>
    </div>
  )
}

function AddStickers({ kind, rating, exclude, onAdd, full }: { kind: StickerKind; rating: Rating; exclude: string[]; onAdd: (s: StickerRow) => void; full: boolean }) {
  const [q, setQ] = useState('')
  const [withDrafts, setWithDrafts] = useState(false)
  const results = useAsync(
    () =>
      api.stickers.list(
        { q, kind, rating, status: withDrafts ? undefined : 'published', excludeIds: exclude, sort: 'quality' },
        { offset: 0, limit: 24 },
      ),
    [q, kind, rating, withDrafts, exclude.join(',')],
  )
  const rows = (results.data?.rows ?? []).filter((s) => s.status === 'published' || s.status === 'draft')
  return (
    <div className="add-stickers">
      <div className="row-between">
        <h3>Add stickers</h3>
        <span className="muted small">
          Showing {kind} · {rating === 'adult' ? '18+' : 'general'} only, to match this pack
        </span>
      </div>
      <div className="toolbar">
        <SearchBox value={q} onSearch={setQ} placeholder="Search captions, tags…" />
        <Toggle checked={withDrafts} onChange={setWithDrafts} label="include drafts" />
      </div>
      {results.error && <ErrorNote error={results.error} onRetry={results.reload} />}
      {results.data && rows.length === 0 && <p className="muted small">Nothing to add for this search.</p>}
      <div className="pick-grid">
        {rows.map((s) => (
          <button key={s.id} className="pick" disabled={full} onClick={() => onAdd(s)} title={`Add “${s.caption ?? ''}”`}>
            <StickerImage src={api.stickers.publicUrl(s.file_path)} alt={s.caption ?? 'sticker'} />
            <span className="pick-caption">{s.caption}</span>
            {s.status === 'draft' && <Pill tone="warn">draft</Pill>}
            <span className="pick-plus" aria-hidden="true">
              <Icon name="plus" size={14} />
            </span>
          </button>
        ))}
      </div>
    </div>
  )
}
