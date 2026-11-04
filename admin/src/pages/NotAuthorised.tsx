import { api } from '../api'
import type { SessionUser } from '../api/types'
import { Brand, DemoBanner } from '../components/Layout'

function sqlLiteral(s: string): string {
  return `'${s.replace(/'/g, "''")}'`
}

export function NotAuthorised({ user, onRetry, error }: { user: SessionUser; onRetry: () => void; error?: string }) {
  const sql = `insert into public.admins (user_id, email)\nvalues (${sqlLiteral(user.id)}, ${sqlLiteral(user.email ?? '')});`
  return (
    <div className="auth-screen">
      <DemoBanner />
      <div className="auth-card">
        <Brand />
        {error ? (
          <>
            <h1>Couldn't check access</h1>
            <div className="note note-danger">{error}</div>
          </>
        ) : (
          <>
            <h1>Not authorised</h1>
            <p className="muted">
              You're signed in as <strong>{user.email ?? user.id}</strong>, but this account isn't an admin.
            </p>
            <p className="muted">
              The project owner can grant access in the Supabase SQL editor (that editor runs with full rights; the panel
              itself can never add admins):
            </p>
            <pre className="code">{sql}</pre>
            <button className="btn btn-sm" onClick={() => void navigator.clipboard?.writeText(sql)}>
              Copy SQL
            </button>
          </>
        )}
        <div className="row-between">
          <button className="btn" onClick={onRetry}>
            Check again
          </button>
          <button className="btn btn-ghost" onClick={() => void api.auth.signOut()}>
            Sign out
          </button>
        </div>
      </div>
    </div>
  )
}
