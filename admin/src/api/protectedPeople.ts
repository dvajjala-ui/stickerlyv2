// protected_people (standing Indian court orders protecting a person's persona).

import type { SupabaseClient } from '@supabase/supabase-js'
import { clone, delay, demoError, type DemoDb } from './demo/db'
import type { ProtectedPerson } from './types'
import { unwrap } from './util'

export interface ProtectedPeopleApi {
  list(): Promise<ProtectedPerson[]>
  add(name: string, note: string | null): Promise<ProtectedPerson>
  remove(name: string): Promise<void>
}

export function supabaseProtectedPeople(sb: SupabaseClient): ProtectedPeopleApi {
  return {
    async list() {
      return unwrap(await sb.from('protected_people').select('*').order('name'), 'Load protected people') as ProtectedPerson[]
    },
    async add(name, note) {
      const res = await sb
        .from('protected_people')
        .insert({ name: name.trim(), note: note?.trim() || null })
        .select('*')
        .single()
      return unwrap(res, 'Add protected person') as ProtectedPerson
    },
    async remove(name) {
      unwrap(await sb.from('protected_people').delete().eq('name', name), 'Remove protected person')
    },
  }
}

export function demoProtectedPeople(db: DemoDb): ProtectedPeopleApi {
  return {
    async list() {
      await delay(db)
      return [...db.protectedPeople].sort((a, b) => a.name.localeCompare(b.name)).map(clone)
    },
    async add(name, note) {
      await delay(db)
      const n = name.trim()
      if (!n) throw demoError('Add protected person: name is required', '23502')
      if (db.protectedPeople.some((p) => p.name === n)) throw demoError(`Add protected person: already exists (${n})`, '23505')
      const row = { name: n, note: note?.trim() || null, created_at: new Date().toISOString() }
      db.protectedPeople.push(row)
      return clone(row)
    },
    async remove(name) {
      await delay(db)
      db.protectedPeople = db.protectedPeople.filter((p) => p.name !== name)
    },
  }
}
