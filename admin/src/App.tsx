import { useCallback, useEffect, useRef, useState } from 'react'
import { Route, Routes } from 'react-router'
import { api } from './api'
import type { SessionUser } from './api/types'
import { Brand, Layout } from './components/Layout'
import { Empty, Spinner } from './components/ui'
import { toError } from './lib/useAsync'
import { NotAuthorised } from './pages/NotAuthorised'
import { Overview } from './pages/Overview'
import { PackEditor } from './pages/PackEditor'
import { Packs } from './pages/Packs'
import { Reports } from './pages/Reports'
import { Settings } from './pages/Settings'
import { SignIn } from './pages/SignIn'
import { Sources } from './pages/Sources'
import { Stickers } from './pages/Stickers'
import { Trends } from './pages/Trends'

type Gate =
  | { state: 'loading' }
  | { state: 'signed-out' }
  | { state: 'checking'; user: SessionUser }
  | { state: 'not-admin'; user: SessionUser; error?: string }
  | { state: 'admin'; user: SessionUser }

export default function App() {
  const [gate, setGate] = useState<Gate>({ state: 'loading' })
  const lastUserId = useRef<string | null | undefined>(undefined)

  const check = useCallback(async (user: SessionUser | null, force = false) => {
    const id = user?.id ?? null
    if (!force && lastUserId.current === id) return // token refreshes re-announce the same user
    lastUserId.current = id
    if (!user) {
      setGate({ state: 'signed-out' })
      return
    }
    setGate({ state: 'checking', user })
    try {
      const ok = await api.auth.isAdmin()
      if (lastUserId.current === id) setGate(ok ? { state: 'admin', user } : { state: 'not-admin', user })
    } catch (e) {
      if (lastUserId.current === id) setGate({ state: 'not-admin', user, error: toError(e).message })
    }
  }, [])

  useEffect(() => {
    let alive = true
    api.auth.current().then(
      (u) => alive && void check(u),
      () => alive && setGate({ state: 'signed-out' }),
    )
    const off = api.auth.onChange((u) => void check(u))
    return () => {
      alive = false
      off()
    }
  }, [check])

  const renderApp = (user: SessionUser) => (
    <Routes>
      <Route element={<Layout user={user} />}>
        <Route index element={<Overview />} />
        <Route path="sources" element={<Sources />} />
        <Route path="stickers" element={<Stickers />} />
        <Route path="packs" element={<Packs />} />
        <Route path="packs/:id" element={<PackEditor />} />
        <Route path="reports" element={<Reports />} />
        <Route path="trends" element={<Trends />} />
        <Route path="settings" element={<Settings />} />
        <Route path="*" element={<Empty title="Page not found">Use the menu to pick a section.</Empty>} />
      </Route>
    </Routes>
  )

  // DEMO: open admin — skip sign-in when VITE_OPEN_ADMIN=true. The panel then runs on the PUBLIC (publishable) key;
  // the matching backend grants anon access to the content tables (backend/supabase/demo/open_admin.sql). The SECRET
  // key is never shipped to the browser. Leave this flag OFF (the default) for any real deployment.
  if (import.meta.env.VITE_OPEN_ADMIN === 'true') return renderApp({ id: 'open', email: 'open admin (demo)' })

  if (gate.state === 'loading' || gate.state === 'checking') {
    return (
      <div className="splash">
        <Brand />
        <Spinner label={gate.state === 'checking' ? 'Checking access…' : undefined} />
      </div>
    )
  }
  if (gate.state === 'signed-out') return <SignIn />
  if (gate.state === 'not-admin') return <NotAuthorised user={gate.user} error={gate.error} onRetry={() => void check(gate.user, true)} />

  return renderApp(gate.user)
  )
}
