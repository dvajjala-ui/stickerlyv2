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

test('pack stickers never leak a different rating (W2 guard)', async () => {
  const db = await project()
  const { rows } = await db.query(
    `insert into public.packs (title, kind, status, rating) values ('Office Ke Dukh', 'static', 'published', 'general') returning id`)
  const pack = rows[0]
  const general = await addSticker(db, 'published')
  const adult = await addSticker(db, 'published', { rating: 'adult' })
  for (const [i, id] of [general, adult].entries()) {
    await db.query('insert into public.pack_items (pack_id, sticker_id, position) values ($1, $2, $3)', [pack.id, id, i])
  }
  const inPack = await asAnon(db, 'select id from public.discover_pack_stickers($1)', [pack.id])
  assert.deepEqual(inPack.map((r) => r.id), [general], 'adult sticker must not be served through a general pack')
})
