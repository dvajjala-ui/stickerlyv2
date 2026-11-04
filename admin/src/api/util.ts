// Shared helpers for the API implementations.

export class ApiError extends Error {
  code: string | null
  constructor(message: string, code: string | null = null) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

interface PgErrorLike {
  message: string
  code?: string | null
  details?: string | null
  hint?: string | null
}

/** Human-readable message for a PostgREST / Postgres error. */
export function friendlyMessage(err: PgErrorLike, what: string): string {
  switch (err.code) {
    case '23505':
      return `${what}: already exists (${err.details ?? err.message})`
    case '23514':
      return `${what}: a database rule rejected it (${err.message})`
    case '23503':
      return `${what}: a linked row is missing (${err.message})`
    case '42501':
      return `${what}: not allowed. Is this account listed in public.admins?`
    case 'PGRST116':
      return `${what}: not found`
    default:
      if (/failed to fetch|networkerror|load failed/i.test(err.message)) return `${what}: network error, check your connection`
      return `${what}: ${err.message}`
  }
}

export function fail(err: PgErrorLike, what: string): never {
  throw new ApiError(friendlyMessage(err, what), err.code ?? null)
}

/** Unwraps a supabase-js result, throwing a friendly ApiError on failure. */
export function unwrap<T>(res: { data: T | null; error: PgErrorLike | null }, what: string): T {
  if (res.error) fail(res.error, what)
  return res.data as T
}

export function unwrapCount(res: { count: number | null; error: PgErrorLike | null }, what: string): number {
  if (res.error) fail(res.error, what)
  return res.count ?? 0
}

/**
 * Splits a search box into ILIKE terms (ANDed). Strips LIKE wildcards and PostgREST's `*`
 * alias so user input can never widen the pattern; caps the number and length of terms.
 */
export function searchTerms(q: string | undefined): string[] {
  if (!q) return []
  return q
    .replace(/[%_*\\]/g, ' ')
    .split(/\s+/)
    .map((t) => t.trim().slice(0, 40))
    .filter(Boolean)
    .slice(0, 6)
}

/** Case-insensitive "every term is contained" match, the demo twin of chained ILIKEs. */
export function matchesTerms(haystack: string, terms: string[]): boolean {
  const h = haystack.toLowerCase()
  return terms.every((t) => h.includes(t.toLowerCase()))
}

export function chunk<T>(items: readonly T[], size: number): T[][] {
  const out: T[][] = []
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size))
  return out
}

export function slugify(text: string): string {
  return text
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48)
}

export function randomSuffix(len = 4): string {
  const alphabet = 'abcdefghijkmnpqrstuvwxyz23456789'
  const bytes = crypto.getRandomValues(new Uint8Array(len))
  return Array.from(bytes, (b) => alphabet[b % alphabet.length]).join('')
}

export function nowIso(): string {
  return new Date().toISOString()
}
