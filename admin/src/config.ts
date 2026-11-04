// Runtime configuration. Missing or placeholder env values switch the panel to demo mode.

const url = (import.meta.env.VITE_SUPABASE_URL ?? '').trim()
const key = (import.meta.env.VITE_SUPABASE_ANON_KEY ?? '').trim()
const forcedDemo = /^(1|true|yes)$/i.test((import.meta.env.VITE_DEMO ?? '').trim())

function looksConfigured(value: string): boolean {
  return value.length > 0 && !value.includes('<') && !/your[-_]/i.test(value)
}

export const config = {
  supabaseUrl: url,
  supabaseKey: key,
  demo: forcedDemo || !(looksConfigured(url) && looksConfigured(key)),
} as const
