// Reference data most pages need (enabled categories, protected people, the quality gate's
// minimum), loaded once and refreshed when Settings saves.

import { api } from '../api'
import { DEFAULT_SETTINGS } from '../api/types'
import { effectiveQualityGate } from './quality'
import { useAsync } from './useAsync'

export interface Reference {
  categories: string[]
  protectedNames: string[]
  /** settings.quality_gate.min_score as the worker applies it. */
  minScore: number
}

let cache: Promise<Reference> | null = null

export function loadReference(): Promise<Reference> {
  if (!cache) {
    cache = Promise.all([api.settings.load(), api.protectedPeople.list()]).then(([s, people]) => ({
      categories: s.categories.enabled,
      protectedNames: people.map((p) => p.name),
      minScore: effectiveQualityGate(s.quality_gate).min_score,
    }))
    cache.catch(() => {
      cache = null
    })
  }
  return cache
}

export function invalidateReference(): void {
  cache = null
}

export const FALLBACK_REFERENCE: Reference = {
  categories: DEFAULT_SETTINGS.categories.enabled,
  protectedNames: [],
  minScore: DEFAULT_SETTINGS.quality_gate.min_score,
}

export function useReference(): Reference & { ready: boolean } {
  const { data } = useAsync(loadReference, [])
  return { ...(data ?? FALLBACK_REFERENCE), ready: !!data }
}

/** Category options: enabled ones plus any extra value already in use. */
export function categoryOptions(enabled: string[], ...current: (string | null | undefined)[]): string[] {
  return [...new Set([...enabled, ...current.filter((c): c is string => !!c)])]
}

// Cross-page signal: something changed the open-report count (nav badge listens).
export const REPORTS_CHANGED = 'stickerdock:reports-changed'
export function announceReportsChanged(): void {
  window.dispatchEvent(new Event(REPORTS_CHANGED))
}
