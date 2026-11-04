import { useEffect, useState } from 'react'
import { Icon } from './Icon'

/** Search input that reports its value after a short pause (or on Enter). */
export function SearchBox({
  value,
  onSearch,
  placeholder = 'Search',
  delay = 350,
}: {
  value: string
  onSearch: (q: string) => void
  placeholder?: string
  delay?: number
}) {
  const [text, setText] = useState(value)
  const [lastValue, setLastValue] = useState(value)
  if (value !== lastValue) {
    // The URL changed underneath us (back button, "clear filters"): adopt it.
    setLastValue(value)
    setText(value)
  }
  useEffect(() => {
    if (text === value) return
    const t = window.setTimeout(() => onSearch(text.trim()), delay)
    return () => window.clearTimeout(t)
  }, [text, value, delay, onSearch])
  return (
    <div className="search">
      <Icon name="search" size={16} />
      <input
        type="search"
        value={text}
        placeholder={placeholder}
        aria-label={placeholder}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') onSearch(text.trim())
        }}
      />
    </div>
  )
}
