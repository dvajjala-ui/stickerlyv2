import { useCallback, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'

type ParamValue = string | number | boolean | null | undefined

/** Filter state kept in the URL (shareable, survives reloads). Changing a filter resets paging. */
export function useUrlState() {
  const [params, setParams] = useSearchParams()
  const get = useCallback((key: string): string | undefined => params.get(key) ?? undefined, [params])
  const num = useCallback(
    (key: string): number | undefined => {
      const v = params.get(key)
      if (v === null || v === '') return undefined
      const n = Number(v)
      return Number.isFinite(n) ? n : undefined
    },
    [params],
  )
  const set = useCallback(
    (patch: Record<string, ParamValue>) => {
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          for (const [k, v] of Object.entries(patch)) {
            if (v === null || v === undefined || v === '' || v === false) next.delete(k)
            else next.set(k, String(v))
          }
          const onlyUi = Object.keys(patch).every((k) => k === 'id' || k === 'offset')
          if (!onlyUi) next.delete('offset')
          return next
        },
        { replace: true },
      )
    },
    [setParams],
  )
  return { params, get, num, set }
}

/**
 * Multi-select with shift-click ranges over the currently visible ids. The selection is always
 * limited to visible rows, so bulk actions never touch rows from another page or filter.
 */
export function useSelection(visibleIds: string[]) {
  const [picked, setSelected] = useState<Set<string>>(new Set())
  const selected = new Set([...picked].filter((id) => visibleIds.includes(id)))
  const anchor = useRef<string | null>(null)
  const toggle = useCallback(
    (id: string, range = false) => {
      setSelected((prev) => {
        const next = new Set(prev)
        if (range && anchor.current && visibleIds.includes(anchor.current)) {
          const a = visibleIds.indexOf(anchor.current)
          const b = visibleIds.indexOf(id)
          for (const x of visibleIds.slice(Math.min(a, b), Math.max(a, b) + 1)) next.add(x)
        } else if (next.has(id)) next.delete(id)
        else next.add(id)
        return next
      })
      anchor.current = id
    },
    [visibleIds],
  )
  const clear = useCallback(() => setSelected(new Set()), [])
  const selectAll = useCallback(() => setSelected(new Set(visibleIds)), [visibleIds])
  return { selected, toggle, clear, selectAll, count: selected.size, ids: [...selected] }
}
