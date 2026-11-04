import { useState, type KeyboardEvent } from 'react'
import { addEmojis, MAX_EMOJIS } from '../lib/emoji'
import { Icon } from './Icon'

/** Free-text chips (tags, keywords, site names). Enter or comma adds; Backspace on empty removes. */
export function ChipsInput({
  value,
  onChange,
  placeholder,
  lowercase = true,
  max = 30,
  id,
}: {
  value: string[]
  onChange: (v: string[]) => void
  placeholder?: string
  lowercase?: boolean
  max?: number
  id?: string
}) {
  const [text, setText] = useState('')
  const commit = (raw: string) => {
    const parts = raw
      .split(',')
      .map((p) => (lowercase ? p.trim().toLowerCase() : p.trim()))
      .filter(Boolean)
    if (!parts.length) return
    onChange([...new Set([...value, ...parts])].slice(0, max))
    setText('')
  }
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      commit(text)
    } else if (e.key === 'Backspace' && !text && value.length) {
      onChange(value.slice(0, -1))
    }
  }
  return (
    <div className="chips-input">
      {value.map((v) => (
        <span key={v} className="chip">
          {v}
          <button type="button" aria-label={`Remove ${v}`} onClick={() => onChange(value.filter((x) => x !== v))}>
            <Icon name="x" size={11} />
          </button>
        </span>
      ))}
      <input
        id={id}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={onKey}
        onBlur={() => commit(text)}
        placeholder={value.length ? '' : placeholder}
      />
    </div>
  )
}

const QUICK = ['😂', '🤣', '😭', '😍', '🥹', '😎', '🤔', '🙄', '😤', '😡', '🙏', '👍', '🔥', '💀', '🤦', '🫡', '😏', '❤️', '💔', '🎉']

/** 0–3 emojis, as WhatsApp allows. Typing, pasting or the quick-pick row all respect the limit. */
export function EmojiInput({ value, onChange }: { value: string[]; onChange: (v: string[]) => void }) {
  const [text, setText] = useState('')
  const [hint, setHint] = useState<string | null>(null)
  const add = (raw: string) => {
    if (!raw.trim()) return
    const r = addEmojis(value, raw)
    onChange(r.list)
    setText('')
    setHint(r.dropped ? `Only ${MAX_EMOJIS} emojis fit (WhatsApp limit)` : r.invalid ? 'Only emojis are kept' : null)
  }
  return (
    <div className="emoji-input">
      <div className="chips-input">
        {value.map((e) => (
          <span key={e} className="chip chip-emoji">
            {e}
            <button type="button" aria-label={`Remove ${e}`} onClick={() => onChange(value.filter((x) => x !== e))}>
              <Icon name="x" size={11} />
            </button>
          </span>
        ))}
        {value.length < MAX_EMOJIS && (
          <input
            value={text}
            onChange={(e) => {
              const v = e.target.value
              // Commit as soon as a whole emoji is typed or pasted.
              if (/\p{Extended_Pictographic}|\p{Regional_Indicator}/u.test(v)) add(v)
              else setText(v)
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                add(text)
              } else if (e.key === 'Backspace' && !text && value.length) onChange(value.slice(0, -1))
            }}
            placeholder={value.length ? '' : 'Type or paste emojis'}
            aria-label="Add emoji"
          />
        )}
        <span className="chips-count">
          {value.length}/{MAX_EMOJIS}
        </span>
      </div>
      <div className="emoji-quick">
        {QUICK.map((e) => (
          <button
            key={e}
            type="button"
            disabled={value.includes(e) || value.length >= MAX_EMOJIS}
            onClick={() => add(e)}
            aria-label={`Add ${e}`}
          >
            {e}
          </button>
        ))}
      </div>
      {hint && <span className="field-hint">{hint}</span>}
    </div>
  )
}
