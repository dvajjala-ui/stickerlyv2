import { Fragment, type ReactNode } from 'react'
import type { Json } from '../api/types'

/** Pretty-printed, syntax-coloured JSON (read-only). */
export function JsonView({ value }: { value: Json | null | undefined }) {
  if (value === null || value === undefined) return <div className="faint">— none —</div>
  return <pre className="json">{render(value, 0)}</pre>
}

function render(v: Json, depth: number): ReactNode {
  const pad = '  '.repeat(depth)
  if (v === null) return <span className="j-null">null</span>
  if (typeof v === 'string') return <span className="j-str">{JSON.stringify(v)}</span>
  if (typeof v === 'number') return <span className="j-num">{v}</span>
  if (typeof v === 'boolean') return <span className="j-bool">{String(v)}</span>
  if (Array.isArray(v)) {
    if (!v.length) return '[]'
    const simple = v.every((x) => x === null || typeof x !== 'object')
    if (simple && JSON.stringify(v).length < 72) {
      return (
        <>
          [
          {v.map((x, i) => (
            <Fragment key={i}>
              {i > 0 && ', '}
              {render(x, depth + 1)}
            </Fragment>
          ))}
          ]
        </>
      )
    }
    return (
      <>
        [{'\n'}
        {v.map((x, i) => (
          <Fragment key={i}>
            {pad}
            {'  '}
            {render(x, depth + 1)}
            {i < v.length - 1 ? ',' : ''}
            {'\n'}
          </Fragment>
        ))}
        {pad}]
      </>
    )
  }
  const entries = Object.entries(v)
  if (!entries.length) return '{}'
  return (
    <>
      {'{'}
      {'\n'}
      {entries.map(([k, x], i) => (
        <Fragment key={k}>
          {pad}
          {'  '}
          <span className="j-key">{JSON.stringify(k)}</span>: {render(x, depth + 1)}
          {i < entries.length - 1 ? ',' : ''}
          {'\n'}
        </Fragment>
      ))}
      {pad}
      {'}'}
    </>
  )
}
