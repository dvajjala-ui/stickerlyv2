import { useState, type FormEvent } from 'react'
import { api } from '../api'
import type { Trend } from '../api/types'
import { ChipsInput } from '../components/ChipsInput'
import { Icon } from '../components/Icon'
import { Empty, ErrorNote, Field, Pill, Spinner, Toggle } from '../components/ui'
import { addDays, fmtDay, istDate, safeHref } from '../lib/format'
import { categoryOptions, useReference } from '../lib/reference'
import { useToast } from '../lib/toast'
import { toError, useAsync } from '../lib/useAsync'

const SOURCE_LABEL: Record<string, string> = { owner: 'owner', google_trends_in: 'Google Trends IN', pib: 'PIB' }

function AddTopic({ day, onAdded }: { day: string; onAdded: (t: Trend) => void }) {
  const toast = useToast()
  const ref = useReference()
  const [title, setTitle] = useState('')
  const [keywords, setKeywords] = useState<string[]>([])
  const [category, setCategory] = useState('')
  const [summary, setSummary] = useState('')
  const [score, setScore] = useState(100)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!title.trim()) return
    setBusy(true)
    try {
      const t = await api.trends.addOwnerTopic({ day, title, keywords, category: category || null, summary: summary || null, score })
      onAdded(t)
      setTitle('')
      setKeywords([])
      setSummary('')
      toast('Topic added for today')
    } catch (err) {
      toast(toError(err).message, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="card form" onSubmit={submit}>
      <h2>Add an owner topic</h2>
      <p className="muted small">The worker makes stickers for active topics; owner topics are saved with source “owner”.</p>
      <Field label="Topic">
        <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Diwali bonus kab aayega?" required maxLength={120} />
      </Field>
      <Field label="Keywords" hint="Enter or comma after each">
        <ChipsInput value={keywords} onChange={setKeywords} placeholder="diwali, bonus, office" />
      </Field>
      <div className="form-row">
        <Field label="Category">
          <select className="select" value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">—</option>
            {categoryOptions(ref.categories).map((c) => (
              <option key={c} value={c}>
                {c.replace(/_/g, ' ')}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Score" hint="Higher = picked sooner">
          <input className="input input-num" type="number" min={0} max={1000} value={score} onChange={(e) => setScore(Number(e.target.value) || 0)} />
        </Field>
      </div>
      <Field label="Notes for the worker">
        <input className="input" value={summary} onChange={(e) => setSummary(e.target.value)} placeholder="Angle, jokes to avoid, …" />
      </Field>
      <button className="btn btn-volt" disabled={busy || !title.trim()}>
        <Icon name="plus" size={15} /> Add topic
      </button>
    </form>
  )
}

export function Trends() {
  const toast = useToast()
  const today = istDate()
  const [day, setDay] = useState(today)
  const list = useAsync(() => api.trends.list(day), [day])
  const rows = list.data ?? []

  const toggle = async (t: Trend, active: boolean) => {
    list.setData((all) => (all ?? []).map((x) => (x.id === t.id ? { ...x, active } : x)))
    try {
      await api.trends.setActive(t.id, active)
    } catch (e) {
      toast(toError(e).message, 'error')
      list.reload()
    }
  }

  return (
    <div className="page">
      <div className="page-head">
        <h1>Trends</h1>
        <div className="daynav">
          <button className="btn-icon" onClick={() => setDay(addDays(day, -1))} aria-label="Previous day">
            <Icon name="left" />
          </button>
          <span className="daynav-label">{day === today ? 'Today' : fmtDay(day)}</span>
          <button className="btn-icon" disabled={day >= today} onClick={() => setDay(addDays(day, 1))} aria-label="Next day">
            <Icon name="right" />
          </button>
        </div>
        <div className="spacer" />
        {list.loading && <Spinner />}
      </div>

      <div className="grid-side">
        <section className="card">
          <h2>
            {day === today ? "Today's topics" : fmtDay(day)} <span className="muted">{rows.length}</span>
          </h2>
          {list.error && <ErrorNote error={list.error} onRetry={list.reload} />}
          {list.data && rows.length === 0 && <Empty title="No topics for this day" />}
          <ul className="trend-list">
            {rows.map((t) => (
              <li key={t.id} className={`trend ${t.active ? '' : 'is-off'}`}>
                <div className="trend-main">
                  <div className="trend-title">
                    <strong>{t.title}</strong>
                    <Pill tone={t.source === 'owner' ? 'volt' : 'neutral'}>{SOURCE_LABEL[t.source] ?? t.source}</Pill>
                    {t.category && <Pill>{t.category.replace(/_/g, ' ')}</Pill>}
                    <span className="muted small">score {t.score}</span>
                  </div>
                  {t.summary && <div className="muted small">{t.summary}</div>}
                  {t.keywords.length > 0 && (
                    <div className="chips">
                      {t.keywords.map((k) => (
                        <span key={k} className="chip chip-static">
                          {k}
                        </span>
                      ))}
                    </div>
                  )}
                  {t.source_urls.map((u) => {
                    const href = safeHref(u)
                    return href ? (
                      <a key={u} href={href} target="_blank" rel="noopener noreferrer" className="small link-break">
                        {u}
                      </a>
                    ) : null
                  })}
                </div>
                <Toggle checked={t.active} onChange={(v) => void toggle(t, v)} label={t.active ? 'active' : 'off'} />
              </li>
            ))}
          </ul>
        </section>
        {day === today ? (
          <AddTopic day={today} onAdded={(t) => list.setData((all) => [t, ...(all ?? [])])} />
        ) : (
          <div className="note">Owner topics are added for today. Go to today to add one.</div>
        )}
      </div>
    </div>
  )
}
