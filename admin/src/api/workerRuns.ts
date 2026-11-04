// worker_runs (one row per worker stage run; read-only for admins).

import type { SupabaseClient } from '@supabase/supabase-js'
import { clone, delay, type DemoDb } from './demo/db'
import type { WorkerRun } from './types'
import { unwrap } from './util'

export interface WorkerRunsApi {
  recent(limit: number): Promise<WorkerRun[]>
}

export function supabaseWorkerRuns(sb: SupabaseClient): WorkerRunsApi {
  return {
    async recent(limit) {
      const res = await sb.from('worker_runs').select('*').order('started_at', { ascending: false }).limit(limit)
      return unwrap(res, 'Load worker runs') as WorkerRun[]
    },
  }
}

export function demoWorkerRuns(db: DemoDb): WorkerRunsApi {
  return {
    async recent(limit) {
      await delay(db)
      return [...db.workerRuns].sort((a, b) => b.started_at.localeCompare(a.started_at)).slice(0, limit).map(clone)
    },
  }
}
