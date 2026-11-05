// Request shapes of the held-sticker review and the score sort, through the real supabase-js client.
// `fetch` is replaced by an in-memory fake: no network, no keys.
import { createClient } from '@supabase/supabase-js'
import { describe, expect, it } from 'vitest'
import { HELD_REVIEW_BATCH, supabaseStickers } from './stickers'

interface Call {
  method: string
  url: URL
  headers: Headers
  body: unknown
}

const ids = (n: number, from = 0) =>
  Array.from({ length: n }, (_, i) => `00000000-0000-4000-8000-${String(from + i).padStart(12, '0')}`)
const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } })
/** The ids of a PATCH's `id=in.(…)` filter. */
const inIds = (c: Call) => (c.url.searchParams.get('id') ?? '').replace(/^in\.\(|\)$/g, '').split(',')

function fakeSupabase(respond: (call: Call) => Response) {
  const calls: Call[] = []
  const fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const href = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const call: Call = {
      method: init?.method ?? 'GET',
      url: new URL(href),
      headers: new Headers(init?.headers),
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
    }
    calls.push(call)
    return respond(call)
  }
  const sb = createClient('https://demo-project.supabase.co', 'test-publishable-key', {
    accessToken: async () => 'test-access-token', // no auth client, no storage
    global: { fetch },
  })
  return { api: supabaseStickers(sb), calls }
}

/** PostgREST twin: every id in the filter is still held unless listed in `notHeld`. */
const heldTable = (notHeld: string[] = []) => (c: Call) => json(200, inIds(c).filter((id) => !notHeld.includes(id)).map((id) => ({ id })))

describe('reviewHeld request shape (Supabase)', () => {
  it('approves with one guarded PATCH: status published, only rows still held, ids returned', async () => {
    const { api, calls } = fakeSupabase(heldTable())
    const picked = ids(3)
    const out = await api.reviewHeld(picked, 'approve')

    expect(calls).toHaveLength(1)
    const [c] = calls
    expect(c.method).toBe('PATCH')
    expect(c.url.pathname).toBe('/rest/v1/stickers')
    expect(c.url.searchParams.get('id')).toBe(`in.(${picked.join(',')})`)
    expect(c.url.searchParams.get('status')).toBe('eq.held')
    expect(c.url.searchParams.get('select')).toBe('id')
    expect(c.headers.get('prefer')).toContain('return=representation')
    // published_at is left to the stickers_search trigger, exactly like the existing Publish action
    expect(c.body).toEqual({ status: 'published' })
    expect(out).toEqual({ done: picked, notHeld: [], failed: [] })
  })

  it('rejects with the same guard and status rejected', async () => {
    const { api, calls } = fakeSupabase(heldTable())
    await api.reviewHeld(ids(2), 'reject')
    expect(calls[0].body).toEqual({ status: 'rejected' })
    expect(calls[0].url.searchParams.get('status')).toBe('eq.held')
  })

  it(`batches ${HELD_REVIEW_BATCH} ids per request, dedupes, and reports rows that were no longer held`, async () => {
    const picked = ids(HELD_REVIEW_BATCH * 2 + 20)
    const gone = [picked[3], picked[HELD_REVIEW_BATCH + 7]]
    const { api, calls } = fakeSupabase(heldTable(gone))
    const out = await api.reviewHeld([...picked, picked[0]], 'approve')

    expect(calls.map((c) => inIds(c).length)).toEqual([HELD_REVIEW_BATCH, HELD_REVIEW_BATCH, 20])
    expect(calls.flatMap(inIds)).toEqual(picked)
    expect(out.notHeld).toEqual(gone)
    expect(out.done).toEqual(picked.filter((id) => !gone.includes(id)))
    expect(out.failed).toEqual([])
  })

  it('reports a failed batch with its ids and a readable message, and still sends the other batches', async () => {
    const picked = ids(HELD_REVIEW_BATCH + 5)
    let n = 0
    const { api, calls } = fakeSupabase((c) =>
      n++ === 0 ? json(403, { code: '42501', message: 'permission denied for table stickers', details: null, hint: null }) : heldTable()(c),
    )
    const out = await api.reviewHeld(picked, 'approve')

    expect(calls).toHaveLength(2)
    expect(out.failed).toHaveLength(1)
    expect(out.failed[0].ids).toEqual(picked.slice(0, HELD_REVIEW_BATCH))
    expect(out.failed[0].message).toMatch(/^Approve held stickers: not allowed/)
    expect(out.done).toEqual(picked.slice(HELD_REVIEW_BATCH))
    expect(out.notHeld).toEqual([])
  })

  it('turns a network failure into a failed batch instead of throwing', async () => {
    const { api } = fakeSupabase(() => {
      throw new TypeError('Failed to fetch')
    })
    const out = await api.reviewHeld(ids(2), 'reject')
    expect(out.done).toEqual([])
    expect(out.failed).toEqual([{ ids: ids(2), message: expect.stringMatching(/^Reject held stickers: network error/) }])
  })

  it('sends nothing for an empty selection', async () => {
    const { api, calls } = fakeSupabase(heldTable())
    expect(await api.reviewHeld([], 'approve')).toEqual({ done: [], notHeld: [], failed: [] })
    expect(calls).toHaveLength(0)
  })
})

describe('held list request shape (Supabase)', () => {
  it('filters on held, selects the quality columns and sorts by gate score lowest first', async () => {
    const { api, calls } = fakeSupabase(() => json(200, [], { 'content-range': '*/0' }))
    await api.list({ status: 'held', sort: 'score' }, { offset: 0, limit: 60 })

    const [c] = calls
    expect(c.method).toBe('GET')
    expect(c.url.searchParams.get('status')).toBe('eq.held')
    expect(c.url.searchParams.get('order')).toBe('quality_score.asc.nullslast,created_at.desc')
    const select = c.url.searchParams.get('select') ?? ''
    for (const col of ['quality_score', 'quality_reasons', 'quality_signals', 'render_phash']) expect(select.split(',')).toContain(col)
  })

  it('keeps newest first as the default order of the held queue', async () => {
    const { api, calls } = fakeSupabase(() => json(200, [], { 'content-range': '*/0' }))
    await api.list({ status: 'held', sort: 'created' }, { offset: 0, limit: 60 })
    expect(calls[0].url.searchParams.get('order')).toBe('created_at.desc')
  })
})
