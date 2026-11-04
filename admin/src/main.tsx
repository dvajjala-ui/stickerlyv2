import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { initApi } from './api'
import App from './App'
import { ToastProvider } from './components/ToastProvider'
import './styles.css'

const root = createRoot(document.getElementById('root')!)

initApi().then(
  () =>
    root.render(
      <StrictMode>
        <BrowserRouter>
          <ToastProvider>
            <App />
          </ToastProvider>
        </BrowserRouter>
      </StrictMode>,
    ),
  (e: unknown) => {
    root.render(
      <div className="splash">
        <p>StickerDock admin failed to start: {e instanceof Error ? e.message : String(e)}</p>
      </div>,
    )
  },
)
