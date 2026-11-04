// Auth: one-time email link (magic link) or 6-digit email code; session persists per device.
// Admin = authenticated user listed in public.admins (checked with the is_admin() SQL function).

import type { SupabaseClient, User } from '@supabase/supabase-js'
import { delay, type DemoDb } from './demo/db'
import type { SessionUser } from './types'
import { ApiError } from './util'

export interface AuthApi {
  current(): Promise<SessionUser | null>
  /** Fires on sign-in/out (and token refresh, with the same user). Returns an unsubscribe function. */
  onChange(cb: (user: SessionUser | null) => void): () => void
  sendLink(email: string): Promise<void>
  verifyCode(email: string, code: string): Promise<void>
  isAdmin(): Promise<boolean>
  /** Signs out this device only; other devices stay signed in. */
  signOut(): Promise<void>
  /** Text for the sign-in page in demo mode; null when live. */
  demoHint: string | null
}

function redirectUrl(): string {
  return `${window.location.origin}${import.meta.env.BASE_URL ?? '/'}`
}

function authMessage(err: { message: string; status?: number; code?: string }): string {
  if (err.status === 429 || /only request this after|rate limit/i.test(err.message)) {
    return 'Too many emails requested. Wait a minute, then try again (or use the code from the last email).'
  }
  if (/signups not allowed|signup is disabled/i.test(err.message)) {
    return 'This email has no account and sign-ups are disabled for this project.'
  }
  if (/expired|invalid/i.test(err.message)) return 'That link or code is invalid or has expired. Request a new one.'
  return err.message
}

function toSessionUser(u: User | null | undefined): SessionUser | null {
  return u ? { id: u.id, email: u.email ?? null } : null
}

export function supabaseAuth(sb: SupabaseClient): AuthApi {
  return {
    async current() {
      const { data, error } = await sb.auth.getSession()
      if (error) throw new ApiError(authMessage(error))
      return toSessionUser(data.session?.user)
    },
    onChange(cb) {
      // supabase-js runs this callback inside its auth lock; dispatch afterwards so listeners can
      // safely call other Supabase methods (the admin check does).
      const { data } = sb.auth.onAuthStateChange((_event, session) => {
        const user = toSessionUser(session?.user)
        setTimeout(() => cb(user), 0)
      })
      return () => data.subscription.unsubscribe()
    },
    async sendLink(email) {
      const { error } = await sb.auth.signInWithOtp({
        email: email.trim(),
        options: { emailRedirectTo: redirectUrl(), shouldCreateUser: true },
      })
      if (error) throw new ApiError(authMessage(error))
    },
    async verifyCode(email, code) {
      const { error } = await sb.auth.verifyOtp({ email: email.trim(), token: code.trim(), type: 'email' })
      if (error) throw new ApiError(authMessage(error))
    },
    async isAdmin() {
      const { data, error } = await sb.rpc('is_admin')
      if (error) throw new ApiError(`Admin check failed: ${error.message}`)
      return data === true
    },
    async signOut() {
      const { error } = await sb.auth.signOut({ scope: 'local' })
      if (error) throw new ApiError(authMessage(error))
    },
    demoHint: null,
  }
}

// ------------------------------------------------------------------ demo

export const DEMO_ADMIN_EMAIL = 'owner@stickerdock.demo'
const STORAGE_KEY = 'stickerdock-demo-auth'
const SIGNED_OUT = '-'

function browserStorage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null
  } catch {
    return null // blocked storage (private mode, sandboxed iframe)
  }
}

export function demoAuth(db: DemoDb): AuthApi {
  const listeners = new Set<(u: SessionUser | null) => void>()
  let memory: string | null = null // used when there is no browser storage (tests)

  const read = (): string | null => {
    let v = memory
    try {
      v = browserStorage()?.getItem(STORAGE_KEY) ?? memory
    } catch {
      /* use memory */
    }
    if (v === null) return DEMO_ADMIN_EMAIL // first visit: signed in as the demo owner
    return v === SIGNED_OUT ? null : v
  }
  const write = (email: string | null) => {
    memory = email ?? SIGNED_OUT
    try {
      browserStorage()?.setItem(STORAGE_KEY, memory)
    } catch {
      /* memory only */
    }
    const user = email ? userFor(email) : null
    for (const l of listeners) l(user)
  }
  const userFor = (email: string): SessionUser => ({
    id: db.admins.find((a) => a.email === email)?.user_id ?? '00000000-0000-4000-8000-000000000001',
    email,
  })

  return {
    async current() {
      const email = read()
      return email ? userFor(email) : null
    },
    onChange(cb) {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
    async sendLink(email) {
      await delay(db)
      write(email.trim().toLowerCase())
    },
    async verifyCode(email, code) {
      await delay(db)
      if (!/^\d{6}$/.test(code.trim())) throw new ApiError('Enter the 6-digit code from the email')
      write(email.trim().toLowerCase())
    },
    async isAdmin() {
      const email = read()
      return !!email && db.admins.some((a) => a.email === email)
    },
    async signOut() {
      write(null)
    },
    demoHint: `Demo mode: no email is sent. ${DEMO_ADMIN_EMAIL} signs in as an admin; any other address shows the "not authorised" screen.`,
  }
}
