import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// The admin re-uses the intake function's pure media helpers (magic bytes, probing, paths),
// so the dev server must be allowed to serve that one folder outside the project root.
const INTAKE_LIB_DIR = '../backend/supabase/functions/intake'

export default defineConfig({
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
