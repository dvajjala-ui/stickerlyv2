import { useEffect, useRef, type ReactNode } from 'react'
import { Icon } from './Icon'

/** Right-hand detail panel (full screen on phones). Esc or the backdrop closes it. */
export function Drawer({
  title,
  onClose,
  children,
  footer,
  wide,
}: {
  title: ReactNode
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  wide?: boolean
}) {
  const panel = useRef<HTMLDivElement>(null)
  const close = useRef(onClose)
  useEffect(() => {
    close.current = onClose
  })

  // Mount-only: focus the panel, listen for Esc, restore focus on close.
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null
    panel.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close.current()
    }
    window.addEventListener('keydown', onKey)
    document.body.classList.add('has-drawer')
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.classList.remove('has-drawer')
      prev?.focus?.()
    }
  }, [])

  return (
    <div className="drawer-root">
      <div className="drawer-backdrop" onClick={() => close.current()} />
      <div className={`drawer ${wide ? 'drawer-wide' : ''}`} role="dialog" aria-modal="true" tabIndex={-1} ref={panel}>
        <header className="drawer-head">
          <div className="drawer-title">{title}</div>
          <button className="btn-icon" onClick={() => close.current()} aria-label="Close">
            <Icon name="x" />
          </button>
        </header>
        <div className="drawer-body">{children}</div>
        {footer && <footer className="drawer-foot">{footer}</footer>}
      </div>
    </div>
  )
}
