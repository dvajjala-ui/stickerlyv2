import { useState, type FormEvent, type ReactNode } from 'react'
import { api } from '../api'
import { Link } from 'react-router'
import { DEFAULT_SETTINGS, type Budgets, type CategoriesSetting, type Iso, type ProtectedPerson, type PublishPolicy, type QualityGate, type SettingKey, type SettingValues, type SettingsBundle } from '../api/types'
import { ChipsInput } from '../components/ChipsInput'
import { Icon } from '../components/Icon'
import { ErrorNote, Field, Segmented, Spinner, Toggle } from '../components/ui'
import { fmtDateTime } from '../lib/format'
import { judgeRange, qualityGateError } from '../lib/quality'
import { invalidateReference } from '../lib/reference'
import { useToast } from '../lib/toast'
import { toError, useAsync } from '../lib/useAsync'

function useSection<K extends SettingKey>(key: K, initial: SettingValues[K], updatedAt: Iso | undefined) {
  const toast = useToast()
  const [value, setValue] = useState<SettingValues[K]>(initial)
  const [saved, setSaved] = useState<SettingValues[K]>(initial)
  const [at, setAt] = useState(updatedAt)
  const [busy, setBusy] = useState(false)
  const dirty = JSON.stringify(value) !== JSON.stringify(saved)
  const save = async () => {
    setBusy(true)
    try {
      const when = await api.settings.save(key, value)
      setSaved(value)
      setAt(when)
      invalidateReference()
      toast('Saved. The worker picks this up on its next run')
    } catch (e) {
      toast(toError(e).message, 'error')
    } finally {
      setBusy(false)
    }
  }
  return { value, setValue, dirty, busy, save, at, reset: () => setValue(saved) }
}

function Card({ title, sub, at, dirty, busy, onSave, onReset, invalid, children }: {
  title: string
  sub: ReactNode
  at?: Iso
  dirty: boolean
  busy: boolean
  onSave: () => void
  onReset: () => void
  invalid?: string | null
  children: ReactNode
}) {
  return (
    <section className="card form">
      <div className="row-between">
        <h2>{title}</h2>
        <span className="muted small">{at ? `saved ${fmtDateTime(at)}` : 'default values'}</span>
      </div>
      <p className="muted small">{sub}</p>
      {children}
      {invalid && <div className="note note-danger">{invalid}</div>}
      <div className="row">
        <button className="btn btn-primary" disabled={!dirty || busy || !!invalid} onClick={onSave}>
          Save
        </button>
        {dirty && (
          <button className="btn btn-ghost" disabled={busy} onClick={onReset}>
            Undo changes
          </button>
        )}
      </div>
    </section>
  )
}

function PolicyCard({ s }: { s: SettingsBundle }) {
  const sec = useSection('publish_policy', s.publish_policy, s.updated_at.publish_policy)
  const v = sec.value
  const set = (patch: Partial<PublishPolicy>) => sec.setValue({ ...v, ...patch })
  return (
    <Card title="Publish policy" sub="How the worker publishes what it makes. Hard rules (no explicit content, no 18+ with children, protected people) always apply." at={sec.at} dirty={sec.dirty} busy={sec.busy} onSave={() => void sec.save()} onReset={sec.reset}>
      <Toggle checked={v.auto_publish} onChange={(auto_publish) => set({ auto_publish })} label="Auto-publish stickers that pass every check" />
      <Field label={`Minimum quality to auto-publish: ${v.min_quality}/10`}>
        <input type="range" min={0} max={10} step={1} value={v.min_quality} onChange={(e) => set({ min_quality: Number(e.target.value) })} />
      </Field>
      <Toggle checked={v.adult_lane} onChange={(adult_lane) => set({ adult_lane })} label="18+ lane on (adult humour, clothed art, age-gated in the app)" />
      <Field label="Celebrities (not on the protected list)" hint="“Review” holds anything with a recognised public figure for a human">
        <Segmented<'publish' | 'review'>
          label="Celebrities"
          value={v.celebrities}
          onChange={(celebrities) => set({ celebrities })}
          options={[
            { value: 'publish', label: 'Publish' },
            { value: 'review', label: 'Review first' },
          ]}
        />
      </Field>
      <Field label="Always review stickers from these sites">
        <ChipsInput value={v.require_review_sites} onChange={(require_review_sites) => set({ require_review_sites })} placeholder="pinterest, imgflip…" />
      </Field>
    </Card>
  )
}

/** A 0–100 input (inside a Field label) that keeps an empty entry as NaN, so validation catches it. */
function ScoreInput({ value, onChange }: { value: number; onChange: (v: number) => void }) {
  return (
    <input
      className="input input-num"
      type="number"
      min={0}
      max={100}
      step={1}
      inputMode="numeric"
      value={Number.isFinite(value) ? value : ''}
      onChange={(e) => onChange(e.target.value.trim() === '' ? Number.NaN : Number(e.target.value))}
    />
  )
}

function QualityGateCard({ s }: { s: SettingsBundle }) {
  const sec = useSection('quality_gate', s.quality_gate, s.updated_at.quality_gate)
  const v = sec.value
  const set = (patch: Partial<QualityGate>) => sec.setValue({ ...v, ...patch })
  const range = judgeRange(v)
  return (
    <Card
      title="Quality gate"
      sub={
        <>
          The worker scores every sticker it makes from 0 to 100 (blur, tiny subject, caption contrast, near-duplicates, animation
          timing). One the publish policy would publish but that scores below the minimum is held for review in{' '}
          <Link to="/stickers?status=held">Stickers → Held</Link>.
        </>
      }
      at={sec.at}
      dirty={sec.dirty}
      busy={sec.busy}
      onSave={() => void sec.save()}
      onReset={sec.reset}
      invalid={qualityGateError(v)}
    >
      <Toggle checked={v.enabled} onChange={(enabled) => set({ enabled })} label="Hold stickers that score below the minimum" />
      {!v.enabled && <p className="muted small">Off: scores are still recorded, but nothing is held and the vision judge never runs.</p>}
      <Field label="Minimum score (0–100)" hint="Below this a sticker is held instead of published. Default 60; hard problems cap a score at 35.">
        <ScoreInput value={v.min_score} onChange={(min_score) => set({ min_score })} />
      </Field>
      <Toggle checked={v.vision_judge} onChange={(vision_judge) => set({ vision_judge })} label="Ask the vision judge about borderline stickers" />
      <p className={v.vision_judge ? 'note note-warn' : 'muted small'}>
        Spends the daily Groq vision budget: one image per judged sticker, shared with source analysis (
        {s.budgets.vision_per_day} images a day under Daily budgets). Keep it off on the free tier.
      </p>
      <Field
        label="Judge band (0–100)"
        hint={
          v.judge_band === 0
            ? 'Band 0: the judge never runs.'
            : range
              ? `With the judge on, it sees stickers scoring ${range[0]}–${range[1]} without a hard problem (a band of 100 covers all of them).`
              : 'Stickers scoring within this many points of the minimum, without a hard problem, go to the judge.'
        }
      >
        <ScoreInput value={v.judge_band} onChange={(judge_band) => set({ judge_band })} />
      </Field>
    </Card>
  )
}

function BudgetsCard({ s }: { s: SettingsBundle }) {
  const sec = useSection('budgets', s.budgets, s.updated_at.budgets)
  const v = sec.value
  const fields: { key: keyof Budgets; label: string; hint: string }[] = [
    { key: 'vision_per_day', label: 'Vision calls / day', hint: 'Image analysis (Groq vision)' },
    { key: 'text_per_day', label: 'Text calls / day', hint: 'Captions, judging, pack titles, trends' },
    { key: 'image_gen_per_day', label: 'Image generations / day', hint: '0 turns generation off' },
    { key: 'max_new_stickers_per_day', label: 'New stickers / day', hint: 'Hard cap on what the worker makes' },
  ]
  const invalid = fields.some((f) => !Number.isInteger(v[f.key]) || v[f.key] < 0) ? 'Budgets must be whole numbers, 0 or more' : null
  return (
    <Card title="Daily budgets" sub="Limits for the worker, reset at midnight IST. The Overview compares today's usage against these." at={sec.at} dirty={sec.dirty} busy={sec.busy} onSave={() => void sec.save()} onReset={sec.reset} invalid={invalid}>
      <div className="form-grid">
        {fields.map((f) => (
          <Field key={f.key} label={f.label} hint={f.hint}>
            <input className="input input-num" type="number" min={0} step={1} value={v[f.key]} onChange={(e) => sec.setValue({ ...v, [f.key]: e.target.value === '' ? 0 : Number(e.target.value) })} />
          </Field>
        ))}
      </div>
    </Card>
  )
}

function CategoriesCard({ s }: { s: SettingsBundle }) {
  const sec = useSection('categories', s.categories, s.updated_at.categories)
  const [custom, setCustom] = useState('')
  const enabled = sec.value.enabled
  const known = [...new Set([...DEFAULT_SETTINGS.categories.enabled, ...s.categories.enabled, ...enabled])]
  const setEnabled = (list: string[]) => sec.setValue({ enabled: list } satisfies CategoriesSetting)
  const toggle = (c: string, on: boolean) => setEnabled(on ? known.filter((k) => k === c || enabled.includes(k)) : enabled.filter((x) => x !== c))
  const addCustom = (e: FormEvent) => {
    e.preventDefault()
    const c = custom.trim().toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '')
    if (c && !enabled.includes(c)) setEnabled([...enabled, c])
    setCustom('')
  }
  return (
    <Card title="Categories" sub="What the worker may make and the app may show. Disabling one hides it from new stickers; existing stickers keep theirs." at={sec.at} dirty={sec.dirty} busy={sec.busy} onSave={() => void sec.save()} onReset={sec.reset} invalid={enabled.length === 0 ? 'Enable at least one category' : null}>
      <div className="check-grid">
        {known.map((c) => (
          <label key={c} className={`check ${enabled.includes(c) ? 'is-on' : ''}`}>
            <input type="checkbox" checked={enabled.includes(c)} onChange={(e) => toggle(c, e.target.checked)} />
            {c.replace(/_/g, ' ')}
            {c === 'adult' && <span className="faint small"> (18+)</span>}
          </label>
        ))}
      </div>
      <form className="row" onSubmit={addCustom}>
        <input className="input" value={custom} onChange={(e) => setCustom(e.target.value)} placeholder="New category, e.g. cricket" aria-label="New category" />
        <button className="btn btn-sm" disabled={!custom.trim()}>
          <Icon name="plus" size={14} /> Add
        </button>
      </form>
    </Card>
  )
}

function ProtectedCard({ initial }: { initial: ProtectedPerson[] }) {
  const toast = useToast()
  const [people, setPeople] = useState(initial)
  const [name, setName] = useState('')
  const [note, setNote] = useState('')
  const [confirming, setConfirming] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const add = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      const p = await api.protectedPeople.add(name, note || null)
      setPeople((all) => [...all, p].sort((a, b) => a.name.localeCompare(b.name)))
      setName('')
      setNote('')
      invalidateReference()
      toast(`${p.name} is now protected`)
    } catch (err) {
      toast(toError(err).message, 'error')
    } finally {
      setBusy(false)
    }
  }
  const remove = async (n: string) => {
    setBusy(true)
    try {
      await api.protectedPeople.remove(n)
      setPeople((all) => all.filter((p) => p.name !== n))
      setConfirming(null)
      invalidateReference()
      toast(`${n} removed from the protected list`)
    } catch (err) {
      toast(toError(err).message, 'error')
    } finally {
      setBusy(false)
    }
  }
  return (
    <section className="card form">
      <h2>Protected people</h2>
      <p className="muted small">
        People with standing Indian court orders against unauthorised use of their persona (memes, GIFs, AI). Stickers that
        depict or name them can't be published from this panel, and the worker skips them.
      </p>
      <ul className="people">
        {people.map((p) => (
          <li key={p.name}>
            <span>
              <strong>{p.name}</strong>
              {p.note && <span className="muted small"> · {p.note}</span>}
            </span>
            {confirming === p.name ? (
              <span className="row">
                <button className="btn btn-sm btn-danger" disabled={busy} onClick={() => void remove(p.name)}>
                  Remove
                </button>
                <button className="btn btn-sm btn-ghost" onClick={() => setConfirming(null)}>
                  Keep
                </button>
              </span>
            ) : (
              <button className="btn-icon" onClick={() => setConfirming(p.name)} aria-label={`Remove ${p.name}`}>
                <Icon name="trash" size={16} />
              </button>
            )}
          </li>
        ))}
      </ul>
      <form className="form-row" onSubmit={add}>
        <Field label="Name">
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} required placeholder="Full name as in the order" />
        </Field>
        <Field label="Note">
          <input className="input" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Court, year, scope" />
        </Field>
        <button className="btn" disabled={busy || !name.trim()}>
          <Icon name="plus" size={14} /> Protect
        </button>
      </form>
    </section>
  )
}

export function Settings() {
  const loaded = useAsync(() => Promise.all([api.settings.load(), api.protectedPeople.list()]), [])
  return (
    <div className="page">
      <div className="page-head">
        <h1>Settings</h1>
        <div className="spacer" />
        {loaded.loading && <Spinner />}
      </div>
      {loaded.error && <ErrorNote error={loaded.error} onRetry={loaded.reload} />}
      {loaded.data && (
        <div className="settings-grid">
          <PolicyCard s={loaded.data[0]} />
          <QualityGateCard s={loaded.data[0]} />
          <BudgetsCard s={loaded.data[0]} />
          <CategoriesCard s={loaded.data[0]} />
          <ProtectedCard initial={loaded.data[1]} />
        </div>
      )}
    </div>
  )
}
