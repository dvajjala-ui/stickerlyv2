import { useEffect, useState } from 'react'
import { Link, NavLink, Outlet } from 'react-router'
import { api } from '../api'
import type { SessionUser } from '../api/types'
import { REPORTS_CHANGED } from '../lib/reference'
import { useToast } from '../lib/toast'
import { Icon, type IconName } from './Icon'

const NAV: { to: string; label: string; icon: IconName }[] = [
  { to: '/', label: 'Overview', icon: 'overview' },
  { to: '/sources', label: 'Sources', icon: 'sources' },
  { to: '/stickers', label: 'Stickers', icon: 'stickers' },
  { to: '/packs', label: 'Packs', icon: 'packs' },
  { to: '/reports', label: 'Reports', icon: 'reports' },
  { to: '/trends', label: 'Trends', icon: 'trends' },
  { to: '/settings', label: 'Settings', icon: 'settings' },
]

function useOpenReports(): number {
  const [n, setN] = useState(0)
  useEffect(() => {
    let alive = true
    const load = () =>
      api.reports.countOpen().then(
        (c) => alive && setN(c),
        () => undefined,
      )
    load()
    const timer = window.setInterval(load, 60_000)
    window.addEventListener(REPORTS_CHANGED, load)
    return () => {
      alive = false
      window.clearInterval(timer)
      window.removeEventListener(REPORTS_CHANGED, load)
    }
  }, [])
  return n
}

export function Brand({ small }: { small?: boolean }) {
  return (
    <span className="brand">
      <span className="brand-mark" aria-hidden="true">
        SD
      </span>
      {!small && (
        <span className="brand-text">
          StickerDock <span className="brand-sub">admin</span>
        </span>
      )}
    </span>
  )
}

export function DemoBanner() {
  if (api.mode !== 'demo') return null
  return (
    <div className="demo-banner" role="note">
      <strong>Demo data</strong> — no backend connected; edits live in memory and reset on reload.
      <span className="demo-hint"> Set VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY to go live.</span>
    </div>
  )
}

export function Layout({ user }: { user: SessionUser }) {
  const openReports = useOpenReports()
  const toast = useToast()
  const signOut = () => api.auth.signOut().catch((e: unknown) => toast(e instanceof Error ? e.message : String(e), 'error'))
  return (
    <div className="app">
      <div className="shell">
        <aside className="side">
          <Link to="/" className="side-brand" aria-label="Overview">
            <Brand />
          </Link>
          <nav className="nav" aria-label="Main">
            {NAV.map((item) => (
              <NavLink key={item.to} to={item.to} end={item.to === '/'} className={({ isActive }) => (isActive ? 'is-active' : '')}>
                <Icon name={item.icon} />
                <span className="nav-label">{item.label}</span>
                {item.to === '/reports' && openReports > 0 && (
                  <span className="nav-count" aria-label={`${openReports} open`}>
                    {openReports}
                  </span>
                )}
              </NavLink>
            ))}
          </nav>
          <div className="side-foot">
            <span className="side-user" title={user.email ?? user.id}>
              {user.email ?? user.id}
            </span>
            <button className="btn-icon btn-icon-dark" onClick={signOut} title="Sign out of this device" aria-label="Sign out">
              <Icon name="logout" />
            </button>
          </div>
        </aside>
        <main className="main" id="main">
          <DemoBanner />
          <Outlet />
        </main>
      </div>
    </div>
  )
}
