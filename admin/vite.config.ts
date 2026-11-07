import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// The admin re-uses the intake function's pure media helpers (magic bytes, probing, paths),
// so the dev server must be allowed to serve that one folder outside the project root.
const INTAKE_LIB_DIR = '../backend/supabase/functions/intake'

// Served from '/' on Vercel (root project) and from a sub-path on GitHub Pages
// (e.g. /stickerlyv2/admin/). VITE_BASE sets it at build time; import.meta.env.BASE_URL
// (used by the router basename and the auth redirect) follows from it.
const BASE = process.env.VITE_BASE || '/'

export default defineConfig({
  base: BASE,
  plugins: [react()],
  server: {
    port: 5173,
    fs: { allow: ['.', INTAKE_LIB_DIR] },
  },
  build: {
    target: 'es2022',
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
