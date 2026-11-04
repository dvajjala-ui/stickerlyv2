// The app-wide Api instance. initApi() runs once before the first render; demo fixtures and the
// Supabase client are loaded lazily so each mode only ships what it uses.

import { config } from '../config'
import type { Api } from './compose'

export type { Api } from './compose'

export let api: Api

export async function initApi(): Promise<Api> {
  if (config.demo) {
    const [{ seedDemoDb }, { createDemoApi }] = await Promise.all([import('./demo/fixtures'), import('./compose')])
    api = createDemoApi(seedDemoDb({ latencyMs: 140 }))
  } else {
    const [{ createClient }, { createSupabaseApi }] = await Promise.all([import('@supabase/supabase-js'), import('./compose')])
    const sb = createClient(config.supabaseUrl, config.supabaseKey, {
      auth: {
        persistSession: true, // "sign in once per device"
        autoRefreshToken: true,
        detectSessionInUrl: true,
        // Implicit flow: the emailed link works even when opened in a different browser than
        // the one that requested it (PKCE would need the original tab's code verifier).
        flowType: 'implicit',
        storageKey: 'stickerdock-admin-auth',
      },
    })
    api = createSupabaseApi(sb)
  }
  return api
}
