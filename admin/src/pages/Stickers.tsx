import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import { REPORT_REASONS, type Rating, type StickerFilter, type StickerKind, type StickerPatch, type StickerRow, type StickerSort, type StickerStatus } from '../api/types'
import { EmojiInput, ChipsInput } from '../components/ChipsInput'
import { Drawer } from '../components/Drawer'
import { Icon } from '../components/Icon'
import { StickerImage } from '../components/Media'
import { SearchBox } from '../components/SearchBox'
import { Empty, ErrorNote, Field, IssueList, Kv, Pager, Pill, Segmented, Spinner, StatusPill, Toggle } from '../components/ui'
import { checkEmojis } from '../lib/emoji'
import { fmtBytes, fmtDateTime, safeHref, timeAgo } from '../lib/format'
import { useSelection, useUrlState } from '../lib/params'
import { categoryOptions, useReference } from '../lib/reference'
import { errorsOf, stickerIssues, waBadge, type StickerLike } from '../lib/stickerRules'
import { useToast } from '../lib/toast'
import { toError, useAsync } from '../lib/useAsync'

const PAGE = 60
const TABS: { value: StickerStatus | 'all'; label: string }[] = [
  { value: 'draft', label: 'Drafts' },
  { value: 'published', label: 'Published' },
  { value: 'rejected', label: 'Rejected' },
  { value: 'taken_down', label: 'Taken down' },
  { value: 'all', label: 'All' },
]

export function WaBadge({ s }: { s: Pick<StickerRow, 'kind' | 'bytes'> }) {
  const b = waBadge(s)
  return (
    <span className={`wa ${b.ok ? 'wa-ok' : 'wa-bad'}`} title={b.detail}>
      WA {b.ok ? '✓' : '✕'} {b.label}
    </span>
  )
}

export function StickerBadges({ s }: { s: Pick<StickerRow, 'kind' | 'rating' | 'status' | 'ai_generated'> }) {
  return (
    <>
      <StatusPill status={s.status} />
      {s.kind === 'animated' && <Pill tone="info">animated</Pill>}
      {s.rating === 'adult' && <Pill tone="danger">18+</Pill>}
    </>
  )
}

function reasonsText(s: StickerLike, protectedNames: string[]): string[] {
  return errorsOf(stickerIssues(s, { protectedNames })).map((i) => i.message)
}

// ------------------------------------------------------------------ editor drawer

function StickerDrawer({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const toast = useToast()
  const ref = useReference()
  const [draft, setDraft] = useState<StickerPatch | null>(null)
  const [busy, setBusy] = useState(false)
  const [reason, setReason] = useState<string>('copyright')
  const { data, error, loading, reload } = useAsync(async () => {
    const [s, packs] = await Promise.all([api.stickers.get(id), api.packs.forSticker(id)])
    setDraft(null)
    return s ? { s, packs } : null
  }, [id])

  const s = data?.s
  const form = s ? { caption: s.caption, tags: s.tags, emojis: s.emojis, category: s.category, rating: s.rating, ai_generated: s.ai_generated, review_note: s.review_note, ...draft } : null
  const dirty = !!draft && Object.keys(draft).length > 0
  const edit = (patch: StickerPatch) => setDraft((d) => ({ ...d, ...patch }))
  const merged: StickerLike | null = s && form ? { ...s, ...form, emojis: form.emojis ?? s.emojis, rating: form.rating ?? s.rating } : null
  const issues = merged ? stickerIssues(merged, { protectedNames: ref.protectedNames }) : []
  const emojiCheck = checkEmojis(form?.emojis ?? [])

  const run = async (what: string, fn: () => Promise<unknown>) => {
    setBusy(true)
    try {
      await fn()
      toast(what)
      reload()
      onChanged()
    } catch (e) {
      toast(toError(e).message, 'error')
    } finally {
      setBusy(false)
    }
  }
  const save = () => run('Sticker saved', () => api.stickers.update(id, draft ?? {}))
  const publish = () =>
    run('Published', async () => {
      if (dirty) await api.stickers.update(id, draft ?? {})
      await api.stickers.setStatus([id], 'published')
    })

  const credit = s?.credit
  const creditHref = safeHref(credit?.url ?? s?.source?.page_url ?? s?.source?.source_url)
  const ratingChangeInPacks = !!s && !!draft?.rating && draft.rating !== s.rating && (data?.packs.length ?? 0) > 0

  return (
    <Drawer
      title={s ? s.caption || 'Sticker' : 'Sticker'}
      onClose={onClose}
      footer={
        s &&
        s.status !== 'taken_down' && (
          <>
            <button className="btn btn-primary" disabled={!dirty || busy || !emojiCheck.ok} onClick={() => void save()}>
              Save
            </button>
            {s.status !== 'published' && (
              <button className="btn btn-volt" disabled={busy || errorsOf(issues).length > 0} onClick={() => void publish()} title={errorsOf(issues)[0]?.message}>
                Publish
              </button>
            )}
            {s.status === 'published' && (
              <button className="btn" disabled={busy} onClick={() => void run('Moved back to draft', () => api.stickers.setStatus([id], 'draft'))}>
                Unpublish
              </button>
            )}
            {s.status !== 'rejected' && (
              <button className="btn btn-danger-outline" disabled={busy} onClick={() => void run('Rejected', () => api.stickers.setStatus([id], 'rejected'))}>
                Reject
              </button>
            )}
            {s.status === 'rejected' && (
              <button className="btn" disabled={busy} onClick={() => void run('Moved back to draft', () => api.stickers.setStatus([id], 'draft'))}>
                Back to draft
              </button>
            )}
          </>
        )
      }
    >
      {loading && !data && <Spinner label="Loading…" />}
      {error && <ErrorNote error={error} onRetry={reload} />}
      {data === null && <Empty title="Sticker not found" />}
      {s && form && (
        <div className="detail">
          <div className="sticker-hero">
            <StickerImage src={api.stickers.publicUrl(s.file_path)} alt={s.caption ?? 'sticker'} size={220} />
            <div className="stack-sm">
              <div className="pill-row">
                <StickerBadges s={s} />
                <WaBadge s={s} />
              </div>
              <div className="pill-row">
                {s.ai_generated ? <Pill tone="volt">AI-generated</Pill> : <Pill>as found</Pill>}
                <Pill>{s.recipe}</Pill>
                {s.quality !== null && <Pill tone="ink">quality {s.quality}/10</Pill>}
              </div>
              <div className="muted small">
                {fmtBytes(s.bytes)} · made {timeAgo(s.created_at)}
                {s.published_at && <> · published {timeAgo(s.published_at)}</>}
              </div>
              {data.packs.length > 0 && (
                <div className="small">
                  In packs:{' '}
                  {data.packs.map((p, i) => (
                    <span key={p.id}>
                      {i > 0 && ', '}
                      <Link to={`/packs/${p.id}`}>{p.title}</Link>
                    </span>
                  ))}
                </div>
              )}
            </div>
          </div>

          <IssueList issues={issues} empty={s.status === 'published' ? 'Live on Discover' : 'Ready to publish'} />
          {s.status === 'taken_down' && <div className="note note-danger">Taken down{s.review_note ? `: ${s.review_note}` : ''}. Editing is disabled.</div>}

          <fieldset className="form" disabled={s.status === 'taken_down' || busy}>
            <Field label="Caption" hint="Shown under the sticker and used for search">
              <input className="input" value={form.caption ?? ''} maxLength={140} onChange={(e) => edit({ caption: e.target.value || null })} />
            </Field>
            <Field label="Emojis (1–3)" error={emojiCheck.ok ? null : emojiCheck.error} hint="WhatsApp uses these to suggest the sticker">
              <EmojiInput value={form.emojis ?? []} onChange={(emojis) => edit({ emojis })} />
            </Field>
            <Field label="Tags">
              <ChipsInput value={form.tags ?? []} onChange={(tags) => edit({ tags })} placeholder="office, monday, chai…" />
            </Field>
            <div className="form-row">
              <Field label="Category">
                <select className="select" value={form.category ?? ''} onChange={(e) => edit({ category: e.target.value })}>
                  {categoryOptions(ref.categories, s.category, form.category).map((c) => (
                    <option key={c} value={c}>
                      {c.replace(/_/g, ' ')}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Rating">
                <Segmented<Rating>
                  label="Rating"
                  value={form.rating ?? 'general'}
                  options={[
                    { value: 'general', label: 'General' },
                    { value: 'adult', label: '18+' },
                  ]}
                  onChange={(rating) => edit({ rating })}
                />
              </Field>
            </div>
            {ratingChangeInPacks && <div className="note note-warn">This sticker is in {data.packs.length} pack(s); changing its rating will break their 18+/general rule until it is moved.</div>}
            <Toggle checked={form.ai_generated ?? true} onChange={(ai_generated) => edit({ ai_generated })} label="Label as AI-generated (IT Rules: synthetic content must be labelled)" />
            <Field label="Review note">
              <input className="input" value={form.review_note ?? ''} onChange={(e) => edit({ review_note: e.target.value || null })} placeholder="Why it was rejected, edited…" />
            </Field>
          </fieldset>

          <h3>Credit</h3>
          <Kv
            rows={[
              ['Site', credit?.site ?? s.source?.site ?? null],
              ['Link', creditHref ? <a key="link" href={creditHref} target="_blank" rel="noopener noreferrer" className="link-break">{creditHref}</a> : null],
              ['Author', credit?.author ?? s.source?.author ?? null],
              ['License', credit?.license ?? s.source?.license ?? null],
              ['Source', s.source_id ? <Link key="src" to={`/sources?id=${s.source_id}`}>open source image</Link> : 'original (no source)'],
            ]}
          />

          {s.status !== 'taken_down' && (
            <>
              <h3>Take down</h3>
              <div className="row">
                <select className="select" value={reason} onChange={(e) => setReason(e.target.value)} aria-label="Takedown reason">
                  {REPORT_REASONS.map((r) => (
                    <option key={r} value={r}>
                      {r}
                    </option>
                  ))}
                </select>
                <button className="btn btn-danger" disabled={busy} onClick={() => void run('Taken down · fingerprint recorded', () => api.takedowns.takeDown('sticker', id, `admin:${reason}`))}>
                  <Icon name="shield" size={15} /> Take down
                </button>
              </div>
              <p className="muted small">Removes it from Discover and every pack, and blocks the image from being collected again.</p>
            </>
          )}

          <h3>File</h3>
          <Kv
            rows={[
              ['Path', <code key="path" className="hash">stickers/{s.file_path}</code>],
              ['SHA-256', <code key="sha" className="hash">{s.sha256}</code>],
              ['Created', fmtDateTime(s.created_at)],
              ['Published', fmtDateTime(s.published_at)],
            ]}
          />
        </div>
      )}
    </Drawer>
  )
}

// ------------------------------------------------------------------ page

export function Stickers() {
  const { params, get, num, set } = useUrlState()
  const toast = useToast()
  const ref = useReference()
  const [busy, setBusy] = useState(false)
  const [takedownReason, setTakedownReason] = useState<string>('')
  const [skipped, setSkipped] = useState<{ caption: string; reasons: string[] }[]>([])

  const tab = (get('status') as StickerStatus | 'all' | undefined) ?? 'draft'
  const filter: StickerFilter = useMemo(
    () => ({
      status: tab === 'all' ? undefined : tab,
      category: get('cat'),
      rating: get('rating') as Rating | undefined,
      kind: get('kind') as StickerKind | undefined,
      q: get('q'),
      sort: (get('sort') as StickerSort | undefined) ?? (tab === 'published' ? 'published' : 'created'),
    }),
    [get, tab],
  )
  const offset = num('offset') ?? 0
  const openId = get('id')
  const list = useAsync(() => api.stickers.list(filter, { offset, limit: PAGE }), [JSON.stringify(filter), offset])
  const rows = useMemo(() => list.data?.rows ?? [], [list.data])
  const ids = useMemo(() => rows.map((r) => r.id), [rows])
  const sel = useSelection(ids)
  const selectedRows = rows.filter((r) => sel.selected.has(r.id))

  const finish = (msg: string) => {
    toast(msg)
    sel.clear()
    list.reload()
  }
  const bulk = async (fn: () => Promise<void>) => {
    setBusy(true)
    try {
      await fn()
    } catch (e) {
      toast(toError(e).message, 'error')
    } finally {
      setBusy(false)
    }
  }
  const publishSelected = () =>
    bulk(async () => {
      const ok: string[] = []
      const bad: { caption: string; reasons: string[] }[] = []
      for (const r of selectedRows) {
        const reasons = reasonsText(r, ref.protectedNames)
        if (reasons.length) bad.push({ caption: r.caption ?? r.id.slice(0, 8), reasons })
        else ok.push(r.id)
      }
      if (ok.length) await api.stickers.setStatus(ok, 'published')
      setSkipped(bad)
      finish(`Published ${ok.length}${bad.length ? ` · skipped ${bad.length} (see list)` : ''}`)
    })
  const setSelected = (status: 'rejected' | 'draft') =>
    bulk(async () => {
      await api.stickers.setStatus(sel.ids, status)
      finish(`${sel.count} moved to ${status}`)
    })
  const takeDownSelected = () =>
    bulk(async () => {
      for (const r of selectedRows) await api.takedowns.takeDown('sticker', r.id, `admin:${takedownReason}`)
      setTakedownReason('')
      finish(`${selectedRows.length} taken down · fingerprints recorded`)
    })

  const activeFilters = ['cat', 'rating', 'kind', 'q'].filter((k) => params.get(k))

  return (
    <div className="page">
      <div className="page-head">
        <h1>Stickers</h1>
        <span className="muted">{list.data ? `${list.data.total} shown` : ''}</span>
        <div className="spacer" />
        {list.loading && <Spinner />}
      </div>

      <div className="tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.value} role="tab" aria-selected={tab === t.value} className={tab === t.value ? 'is-on' : ''} onClick={() => set({ status: t.value === 'draft' ? null : t.value, sort: null })}>
            {t.label}
          </button>
        ))}
      </div>

      <div className="toolbar">
        <SearchBox value={filter.q ?? ''} onSearch={(q) => set({ q })} placeholder="Search captions, tags, emojis…" />
        <label className="filter">
          <span>category</span>
          <select className="select" value={filter.category ?? ''} onChange={(e) => set({ cat: e.target.value })}>
            <option value="">any</option>
            {categoryOptions(ref.categories, filter.category).map((c) => (
              <option key={c} value={c}>
                {c.replace(/_/g, ' ')}
              </option>
            ))}
          </select>
        </label>
        <label className="filter">
          <span>rating</span>
          <select className="select" value={filter.rating ?? ''} onChange={(e) => set({ rating: e.target.value })}>
            <option value="">any</option>
            <option value="general">general</option>
            <option value="adult">18+</option>
          </select>
        </label>
        <label className="filter">
          <span>kind</span>
          <select className="select" value={filter.kind ?? ''} onChange={(e) => set({ kind: e.target.value })}>
            <option value="">any</option>
            <option value="static">static</option>
            <option value="animated">animated</option>
          </select>
        </label>
        <label className="filter">
          <span>sort</span>
          <select className="select" value={filter.sort} onChange={(e) => set({ sort: e.target.value })}>
            <option value="created">newest</option>
            <option value="published">recently published</option>
            <option value="quality">quality</option>
            <option value="size">file size</option>
          </select>
        </label>
        {activeFilters.length > 0 && (
          <button className="btn btn-sm btn-ghost" onClick={() => set(Object.fromEntries(activeFilters.map((k) => [k, null])))}>
            Clear
          </button>
        )}
      </div>

      {skipped.length > 0 && (
        <div className="note note-warn note-block">
          <div className="row-between">
            <strong>Not published ({skipped.length})</strong>
            <button className="btn-link" onClick={() => setSkipped([])}>
              dismiss
            </button>
          </div>
          <ul>
            {skipped.map((s, i) => (
              <li key={i}>
                <strong>{s.caption}</strong>: {s.reasons.join('; ')}
              </li>
            ))}
          </ul>
        </div>
      )}
      {list.error && <ErrorNote error={list.error} onRetry={list.reload} />}
      {list.data && rows.length === 0 && <Empty title="No stickers here">{tab === 'draft' ? 'The review queue is empty.' : 'Try another tab or filter.'}</Empty>}

      <div className="tiles tiles-stickers">
        {rows.map((r) => {
          const selected = sel.selected.has(r.id)
          const blocked = r.status === 'draft' && reasonsText(r, ref.protectedNames).length > 0
          return (
            <article key={r.id} className={`tile ${selected ? 'is-selected' : ''}`}>
              <div className="tile-media">
                <StickerImage src={api.stickers.publicUrl(r.file_path)} alt={r.caption ?? 'sticker'} />
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
                <StickerBadges s={r} />
              </div>
              <div className="tile-meta">
                <button type="button" className="tile-title tile-open" title={r.caption ?? ''} onClick={() => set({ id: r.id })}>
                  {r.caption || <span className="faint">no caption</span>}
                </button>
                <div className="tile-sub">
                  <span className="tile-emojis">{r.emojis.join(' ') || <span className="flag-danger">no emoji</span>}</span>
                  <span>{r.category.replace(/_/g, ' ')}</span>
                </div>
                <div className="tile-flags">
                  <WaBadge s={r} />
                  {r.ai_generated && <span title="AI-generated label">AI</span>}
                  {blocked && (
                    <span className="flag-danger" title={reasonsText(r, ref.protectedNames).join('\n')}>
                      blocked
                    </span>
                  )}
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
          <button className="btn btn-sm btn-volt" disabled={busy} onClick={() => void publishSelected()}>
            Publish
          </button>
          <button className="btn btn-sm" disabled={busy} onClick={() => void setSelected('draft')}>
            To draft
          </button>
          <button className="btn btn-sm btn-danger-outline" disabled={busy} onClick={() => void setSelected('rejected')}>
            Reject
          </button>
          <select className="select select-sm" value={takedownReason} onChange={(e) => setTakedownReason(e.target.value)} aria-label="Takedown reason">
            <option value="">Take down…</option>
            {REPORT_REASONS.map((r) => (
              <option key={r} value={r}>
                reason: {r}
              </option>
            ))}
          </select>
          {takedownReason && (
            <button className="btn btn-sm btn-danger" disabled={busy} onClick={() => void takeDownSelected()}>
              Take down {sel.count}
            </button>
          )}
          <button className="btn-icon" onClick={sel.clear} aria-label="Clear selection">
            <Icon name="x" />
          </button>
        </div>
      )}

      {openId && <StickerDrawer id={openId} onClose={() => set({ id: null })} onChanged={list.reload} />}
    </div>
  )
}
