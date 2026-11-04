import { useCallback, useEffect, useState, type DependencyList } from 'react'

export interface AsyncState<T> {
  data: T | undefined
  error: Error | null
  loading: boolean
  reload: () => void
  setData: (update: T | ((prev: T | undefined) => T)) => void
}

export function toError(e: unknown): Error {
  return e instanceof Error ? e : new Error(String(e))
}

/**
 * Runs `fn` when `deps` change (or reload() is called) and ignores stale results.
 * Keeps the previous data while reloading so lists don't flash empty.
 */
export function useAsync<T>(fn: () => Promise<T>, deps: DependencyList): AsyncState<T> {
  const [data, setDataState] = useState<T | undefined>(undefined)
  const [error, setError] = useState<Error | null>(null)
  const [loading, setLoading] = useState(true)
  const [tick, setTick] = useState(0)

  useEffect(() => {
    let alive = true
    setLoading(true)
    setError(null)
    fn().then(
      (value) => {
        if (!alive) return
        setDataState(value)
        setLoading(false)
      },
      (e: unknown) => {
        if (!alive) return
        setError(toError(e))
        setLoading(false)
      },
    )
    return () => {
      alive = false
    }
    // `fn` is intentionally not a dependency: callers list what it depends on in `deps`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])

  const reload = useCallback(() => setTick((t) => t + 1), [])
  const setData = useCallback((update: T | ((prev: T | undefined) => T)) => {
    setDataState((prev) => (typeof update === 'function' ? (update as (p: T | undefined) => T)(prev) : update))
  }, [])
  return { data, error, loading, reload, setData }
}
