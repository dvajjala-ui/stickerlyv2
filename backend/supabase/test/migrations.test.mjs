// Applies every migration, in order, to a real Postgres (PGlite, in-process) set up like a fresh Supabase project
// (roles, default grants, auth/storage stand-ins), then checks what the phones' anonymous key can and cannot see.
// Run: cd backend/supabase/test && npm ci && npm test
import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { PGlite } from '@electric-sql/pglite'
import { pg_trgm } from '@electric-sql/pglite/contrib/pg_trgm'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'
import { vector } from '@electric-sql/pglite-pgvector'

const migrations = join(dirname(fileURLToPath(import.meta.url)), '..', 'migrations')

// What Supabase provides before our migrations run (simplified to what they rely on).
const SUPABASE = `
create role anon nologin;
create role authenticated nologin;
create role service_role nologin bypassrls;
grant usage on schema public to anon, authenticated, service_role;
alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public grant all on functions to anon, authenticated, service_role;
alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;
create schema auth;
create table auth.users (id uuid primary key, email text);
create function auth.uid() returns uuid language sql stable as
  $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
create function auth.role() returns text language sql stable as
  $$ select coalesce(nullif(current_setting('request.jwt.claim.role', true), ''), current_user::text) $$;
grant usage on schema auth to anon, authenticated, service_role;
grant execute on all functions in schema auth to anon, authenticated, service_role;
create schema storage;
create table storage.buckets (id text primary key, name text not null, public boolean default false,
  file_size_limit bigint, allowed_mime_types text[]);
create table storage.objects (id uuid primary key default gen_random_uuid(), bucket_id text references storage.buckets(id),
  name text, owner uuid);
alter table storage.objects enable row level security;
`

async function project() {
  const db = new PGlite({ extensions: { pg_trgm, pgcrypto, vector } })
  await db.exec(SUPABASE)
  const files = readdirSync(migrations).filter((f) => f.endsWith('.sql')).sort()
  assert.ok(files.length >= 3, 'expected the core, pack-version and quality-gate migrations')
  for (const file of files) {
    try {
      await db.exec(readFileSync(join(migrations, file), 'utf8'))
    } catch (error) {
      throw new Error(`${file}: ${error.message}`)
    }
  }
  return db
}

let n = 0
async function addSticker(db, status, extra = {}) {
  n += 1
  const row = {
    recipe: 'caption', kind: 'static', file_path: `test/${n}.webp`, sha256: n.toString(16).padStart(64, '0'),
    bytes: 1000, status, caption: `Chai pe charcha ${n}`, search_text: `chai pe charcha office ${n}`,
    published_at: status === 'published' ? new Date().toISOString() : null, ...extra,
  }
  const columns = Object.keys(row)
  const { rows } = await db.query(
    `insert into public.stickers (${columns.join(', ')}) values (${columns.map((_, i) => `$${i + 1}`).join(', ')}) returning id`,
    Object.values(row))
  return rows[0].id
}

async function asAnon(db, sql, params = []) {
  await db.exec('set role anon')
  try {
    return (await db.query(sql, params)).rows
  } finally {
    await db.exec('reset role')
  }
}

test('every migration applies in order to a fresh project', async () => {
  const db = await project()
  const { rows } = await db.query(`select count(*)::int as n from information_schema.tables where table_schema = 'public'`)
  assert.ok(rows[0].n >= 12, `public tables: ${rows[0].n}`)
  const buckets = await db.query('select id, public from storage.buckets order by id')
  assert.deepEqual(buckets.rows, [{ id: 'sources', public: false }, { id: 'stickers', public: true }])
  const gate = await db.query(`select value from public.settings where key = 'quality_gate'`)
  assert.equal(gate.rows[0].value.min_score, 60)
})

test('the anonymous key sees published stickers only (views, search, table)', async () => {
  const db = await project()
  const published = await addSticker(db, 'published')
  await addSticker(db, 'held', { quality_score: 41, quality_reasons: ['blurry'] })
  await addSticker(db, 'draft')
  await addSticker(db, 'published', { rating: 'adult' })
  const feed = await asAnon(db, `select id from public.discover_stickers where rating = 'general'`)
  assert.deepEqual(feed.map((r) => r.id), [published])
  const search = await asAnon(db, `select id from public.search_stickers('chai', false, 60)`)
  assert.deepEqual(search.map((r) => r.id), [published])
  const table = await asAnon(db, `select status from public.stickers`)
  assert.ok(table.every((r) => r.status === 'published'), JSON.stringify(table))
  assert.equal(table.length, 2, 'general + adult published rows; held and draft stay hidden')
})

test('held is a valid status and the gate columns are checked', async () => {
  const db = await project()
  await addSticker(db, 'held', { quality_score: 55, quality_reasons: ['caption_low_contrast'], render_phash: '0123456789abcdef' })
  await assert.rejects(addSticker(db, 'bogus'), /stickers_status_check/)
  await assert.rejects(addSticker(db, 'held', { quality_score: 101 }), /check/)
  await assert.rejects(addSticker(db, 'held', { render_phash: 'not-a-hash' }), /check/)
})

test('pack counts and versions only count published stickers', async () => {
  const db = await project()
  const { rows } = await db.query(`insert into public.packs (title, kind, status) values ('Office Ke Dukh', 'static', 'published') returning id, version`)
  const pack = rows[0]
  const items = [await addSticker(db, 'published'), await addSticker(db, 'held'), await addSticker(db, 'published')]
  for (const [i, id] of items.entries()) {
    await db.query('insert into public.pack_items (pack_id, sticker_id, position) values ($1, $2, $3)', [pack.id, id, i])
  }
  const after = await db.query('select sticker_count, version from public.packs where id = $1', [pack.id])
  assert.equal(after.rows[0].sticker_count, 2)
  assert.ok(after.rows[0].version > pack.version)
  // Fewer than 3 published stickers: phones don't see the pack yet.
  const visible = await asAnon(db, 'select id from public.discover_packs')
  assert.deepEqual(visible, [])
  const inPack = await asAnon(db, 'select id from public.discover_pack_stickers($1)', [pack.id])
  assert.equal(inPack.length, 2)
})

test('collectors claim queued jobs one at a time and report progress', async () => {
  const db = await project()
  const queue = async (topics, sources, limit) => (await db.query(
    'insert into public.collection_jobs (topics, sources, image_limit) values ($1, $2, $3) returning id', [topics, sources, limit])).rows[0].id
  const cats = await queue(['funny cat reaction'], ['pinterest', 'reddit'], 25)
  const office = await queue(['office meme'], ['flickr'], 25)
  const me = '11111111-1111-4111-8111-111111111111'
  const other = '22222222-2222-4222-8222-222222222222'
  const claim = async (worker, sources) => (await db.query('select * from public.claim_collection_job($1, $2)', [worker, sources])).rows

  const first = await claim(me, ['pinterest'])
  assert.equal(first.length, 1)
  assert.equal(first[0].id, cats, 'oldest job whose sources the collector supports')
  assert.equal(first[0].status, 'RUNNING')
  assert.deepEqual(await claim(me, ['pinterest']), [], 'a running job is not handed out twice')

  const progress = await db.query(`select * from public.report_collection_job($1, $2, 'VERIFIED_STORAGE', 25, 25)`, [me, cats])
  assert.equal(progress.rows[0].saved, 25)
  assert.equal(progress.rows[0].status, 'VERIFIED_STORAGE')
  const stranger = await db.query(`select * from public.report_collection_job($1, $2, 'FAILED', 0, 0)`, [other, cats])
  assert.equal(stranger.rows.length, 0, 'only the collector holding the job may report on it')
  await assert.rejects(db.query(`select * from public.report_collection_job($1, $2, 'QUEUED', 0, 0)`, [me, cats]), /bad collection status/)

  // A collector that went quiet for 15 minutes loses its job to the next one.
  assert.equal((await claim(other, ['flickr']))[0].id, office)
  await db.query(`update public.collection_jobs set reported_at = now() - interval '20 minutes' where id = $1`, [office])
  const taken = await claim(me, ['flickr'])
  assert.equal(taken[0].id, office)
  assert.equal(taken[0].worker_id, me)
  assert.equal((await db.query(`select * from public.report_collection_job($1, $2, 'RUNNING', 3, 3)`, [other, office])).rows.length, 0)

  // Cancelled jobs are skipped; the phones' key can't touch the queue.
  const cancelled = await queue(['skip me'], ['reddit'], 5)
  await db.query('update public.collection_jobs set cancel_requested = true where id = $1', [cancelled])
  assert.deepEqual(await claim(me, ['reddit']), [])
  await assert.rejects(asAnon(db, 'select * from public.claim_collection_job($1, $2)', [me, ['reddit']]), /permission denied/)
  assert.deepEqual(await asAnon(db, 'select id from public.collection_jobs'), [])
  const settings = await db.query(`select value from public.settings where key = 'collect'`)
  assert.equal(settings.rows[0].value.enabled, false)
})
