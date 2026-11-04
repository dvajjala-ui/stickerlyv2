import { useEffect, useState, type FormEvent } from 'react'
import { api } from '../api'
import { Brand, DemoBanner } from '../components/Layout'
import { toError } from '../lib/useAsync'

const LAST_EMAIL = 'stickerdock-admin-last-email'
const RESEND_SECONDS = 60

function remembered(): string {
  try {
    return localStorage.getItem(LAST_EMAIL) ?? ''
  } catch {
    return ''
  }
}

/** An expired or reused email link comes back as #error_description=… (or ?error_description=…). */
function linkError(): string | null {
  try {
    const msg =
      new URLSearchParams(window.location.hash.replace(/^#/, '')).get('error_description') ??
      new URLSearchParams(window.location.search).get('error_description')
    return msg ? `That sign-in link didn't work (${msg}). Request a new one below.` : null
  } catch {
    return null
  }
}

export function SignIn() {
  const [email, setEmail] = useState(remembered)
  const [sent, setSent] = useState(false)
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(linkError)
  const [cooldown, setCooldown] = useState(0)

  useEffect(() => {
    if (cooldown <= 0) return
    const t = window.setTimeout(() => setCooldown((c) => c - 1), 1000)
    return () => window.clearTimeout(t)
  }, [cooldown])

  const send = async (e?: FormEvent) => {
    e?.preventDefault()
    setError(null)
    const value = email.trim()
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) {
      setError('Enter a valid email address')
      return
    }
    setBusy(true)
    try {
      await api.auth.sendLink(value)
      try {
        localStorage.setItem(LAST_EMAIL, value)
      } catch {
        /* not essential */
      }
      setSent(true)
      setCooldown(RESEND_SECONDS)
    } catch (err) {
      setError(toError(err).message)
    } finally {
      setBusy(false)
    }
  }

  const verify = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await api.auth.verifyCode(email, code) // on success the auth listener swaps this screen out
    } catch (err) {
      setError(toError(err).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-screen">
      <DemoBanner />
      <div className="auth-card">
        <Brand />
        <h1>Sign in</h1>
        <p className="muted">
          No password. We email you a one-time link; open it on this device and it stays signed in here until you sign out.
        </p>
        {api.auth.demoHint && <div className="note note-volt">{api.auth.demoHint}</div>}

        {!sent ? (
          <form onSubmit={send} className="stack">
            <label className="field">
              <span className="field-label">Email</span>
              <input
                className="input"
                type="email"
                autoComplete="email"
                inputMode="email"
                autoFocus
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="you@example.com"
              />
            </label>
            <button className="btn btn-primary btn-block" disabled={busy}>
              {busy ? 'Sending…' : 'Email me a sign-in link'}
            </button>
          </form>
        ) : (
          <div className="stack">
            <div className="note note-ok">
              Link sent to <strong>{email}</strong>. Open it on this device to finish signing in.
            </div>
            <form onSubmit={verify} className="stack">
              <label className="field">
                <span className="field-label">Or enter the 6-digit code from the email</span>
                <input
                  className="input input-code"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  pattern="\d{6}"
                  maxLength={6}
                  value={code}
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                  placeholder="123456"
                />
              </label>
              <button className="btn btn-block" disabled={busy || code.length !== 6}>
                Verify code
              </button>
            </form>
            <div className="row-between">
              <button
                className="btn-link"
                onClick={() => {
                  setSent(false)
                  setCode('')
                }}
              >
                Use another email
              </button>
              <button className="btn-link" disabled={cooldown > 0 || busy} onClick={() => void send()}>
                {cooldown > 0 ? `Resend in ${cooldown}s` : 'Resend link'}
              </button>
            </div>
          </div>
        )}
        {error && (
          <div className="note note-danger" role="alert">
            {error}
          </div>
        )}
      </div>
    </div>
  )
}
