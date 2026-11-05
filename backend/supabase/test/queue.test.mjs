// Queue calls HandNavigate's cloud bridge sends to the intake function (Node 24 imports the TypeScript directly).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parseQueueAction } from '../functions/intake/lib.ts'

const WORKER = '3f1c2b9e-1d2a-4b8c-9e7f-0a1b2c3d4e5f'
const JOB = '7a6b5c4d-3e2f-4a1b-8c9d-0e1f2a3b4c5d'

test('image uploads are not queue calls', () => {
  assert.equal(parseQueueAction({ image_base64: 'AAAA', mime: 'image/png' }), null)
  assert.equal(parseQueueAction(null), null)
  assert.equal(parseQueueAction([1, 2]), null)
})

test('claim takes source ids in either shape and drops bad ones', () => {
  const r = parseQueueAction({ action: 'claim', worker_id: WORKER.toUpperCase(),
    supported_sources: [{ id: 'pinterest', name: 'Pinterest' }, 'reddit', { id: 'Bad Id!' }, 42, null] })
  assert.deepEqual(r, { ok: true, value: { action: 'claim', worker: WORKER, sources: ['pinterest', 'reddit'] } })
  assert.equal(parseQueueAction({ action: 'claim', worker_id: 'not-a-uuid' }).ok, false)
  assert.equal(parseQueueAction({ action: 'enqueue_daily', worker_id: WORKER }).field, 'action')
})

test('report is validated, clamped and kept small', () => {
  const r = parseQueueAction({ action: 'report', worker_id: WORKER, id: JOB, report: {
    status: 'PARTIAL', saved: 12, delivered: -3, error: '  pinterest asked to log in  ',
    source_status: { pinterest: 'AUTH_REQUIRED' }, events: Array.from({ length: 40 }, (_, i) => ({ i })) } })
  assert.equal(r.ok, true)
  assert.equal(r.value.saved, 12)
  assert.equal(r.value.delivered, 0)
  assert.equal(r.value.error, 'pinterest asked to log in')
  assert.equal(r.value.events.length, 20)
  assert.deepEqual(r.value.sourceStatus, { pinterest: 'AUTH_REQUIRED' })
  const huge = parseQueueAction({ action: 'report', worker_id: WORKER, id: JOB,
    report: { status: 'RUNNING', source_status: { blob: 'x'.repeat(30_000) } } })
  assert.deepEqual(huge.value.sourceStatus, { truncated: true })
  assert.equal(parseQueueAction({ action: 'report', worker_id: WORKER, id: JOB, report: { status: 'QUEUED' } }).field, 'report.status')
  assert.equal(parseQueueAction({ action: 'report', worker_id: WORKER, id: 'x', report: { status: 'RUNNING' } }).field, 'id')
  assert.equal(parseQueueAction({ action: 'report', worker_id: WORKER, id: JOB }).field, 'report')
})
