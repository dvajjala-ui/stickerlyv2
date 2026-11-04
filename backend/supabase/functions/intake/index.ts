// Supabase Edge Function: POST /functions/v1/intake
//
// Receives one collected image from a collector (HandNavigate or a worker adapter), stores the
// original in the private `sources` bucket and queues a `source_images` row (status `new`).
// Auth is a shared secret in the `x-intake-token` header (not a Supabase JWT), so this function
// is deployed with verify_jwt = false (see ../../config.toml and ./README.md).
//
// Env: INTAKE_TOKEN (set with `supabase secrets set`), SUPABASE_URL and
// SUPABASE_SERVICE_ROLE_KEY (provided by the platform). Never log the token or the key.

import { createClient, type SupabaseClient } from '@supabase/supabase-js';
import {
  BodyTooLargeError,
  MAX_BODY_BYTES,
  MAX_IMAGE_BYTES,
  decodeBase64,
  isMediaMime,
  isStorageConflict,
  parseIntakeBody,
  probeMedia,
  readTextCapped,
  sha256Hex,
  sniffMedia,
  sourceObjectPath,
  sourceRow,
  tokensMatch,
} from './lib.ts';

const CORS: Record<string, string> = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': 'content-type, x-intake-token',
  'access-control-allow-methods': 'POST, OPTIONS',
  'access-control-max-age': '86400',
};

function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', ...CORS },
  });
}

let client: SupabaseClient | null = null;
function db(): SupabaseClient {
  if (!client) {
    const url = Deno.env.get('SUPABASE_URL');
    const key = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
    if (!url || !key) throw new Error('SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY missing');
    client = createClient(url, key, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
    });
  }
  return client;
}

async function isTakenDown(sb: SupabaseClient, sha256: string, sourceUrl: string | null): Promise<boolean> {
  const bySha = sb.from('takedowns').select('id').eq('sha256', sha256).limit(1);
  const byUrl = sourceUrl ? sb.from('takedowns').select('id').eq('source_url', sourceUrl).limit(1) : null;
  const [a, b] = await Promise.all([bySha, byUrl]);
  if (a.error) throw new Error(`takedown lookup: ${a.error.message}`);
  if (b?.error) throw new Error(`takedown lookup: ${b.error.message}`);
  return (a.data?.length ?? 0) > 0 || (b?.data?.length ?? 0) > 0;
}

async function existingId(sb: SupabaseClient, sha256: string): Promise<string | null> {
  const { data, error } = await sb.from('source_images').select('id').eq('sha256', sha256).maybeSingle();
  if (error) throw new Error(`duplicate lookup: ${error.message}`);
  return (data as { id: string } | null)?.id ?? null;
}

async function handle(req: Request): Promise<Response> {
  if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
  if (req.method !== 'POST') return json(405, { error: 'method_not_allowed' });

  // Fail closed: refuse to run without a reasonably long secret.
  const expected = Deno.env.get('INTAKE_TOKEN') ?? '';
  if (expected.length < 16) return json(503, { error: 'not_configured' });
  if (!(await tokensMatch(req.headers.get('x-intake-token') ?? '', expected))) {
    return json(401, { error: 'unauthorized' });
  }

  const declaredLength = Number(req.headers.get('content-length') ?? '0');
  if (declaredLength > MAX_BODY_BYTES) return json(413, { error: 'too_large', detail: `max ${MAX_IMAGE_BYTES} bytes` });

  let body: unknown;
  try {
    body = JSON.parse(await readTextCapped(req.body, MAX_BODY_BYTES));
  } catch (e) {
    if (e instanceof BodyTooLargeError) return json(413, { error: 'too_large', detail: `max ${MAX_IMAGE_BYTES} bytes` });
    return json(400, { error: 'bad_json' });
  }

  const parsed = parseIntakeBody(body);
  if (!parsed.ok) return json(parsed.status, { error: parsed.error, detail: parsed.detail, field: parsed.field });
  const input = parsed.value;

  const bytes = decodeBase64(input.base64);
  if (!bytes || bytes.length === 0) return json(400, { error: 'bad_base64' });
  if (bytes.length > MAX_IMAGE_BYTES) return json(413, { error: 'too_large', detail: `max ${MAX_IMAGE_BYTES} bytes` });

  const media = sniffMedia(bytes);
  if (!media) return json(415, { error: 'unsupported_media', detail: 'png, jpeg, webp, gif, mp4 or webm only' });
  // Magic bytes win over the declared type; tell the collector when they disagreed.
  const mimeCorrected = !isMediaMime(input.declaredMime) || input.declaredMime !== media.mime;

  const sha256 = await sha256Hex(bytes);
  const sb = db();

  if (await isTakenDown(sb, sha256, input.sourceUrl)) return json(409, { error: 'taken_down' });

  const dupe = await existingId(sb, sha256);
  if (dupe) return json(200, { id: dupe, duplicate: true });

  const path = sourceObjectPath(sha256, media.ext);
  const upload = await sb.storage.from('sources').upload(path, bytes, {
    contentType: media.mime,
    upsert: false,
    cacheControl: '31536000',
  });
  // Paths are content-addressed, so an existing object already holds these exact bytes.
  const uploadedNow = !upload.error;
  if (upload.error && !isStorageConflict(upload.error)) throw new Error(`storage upload: ${upload.error.message}`);

  const row = sourceRow({ sha256, path, mime: media.mime, bytes: bytes.length, probe: probeMedia(bytes, media.mime), input });
  const inserted = await sb.from('source_images').insert(row).select('id').single();
  if (inserted.error) {
    if (inserted.error.code === '23505') {
      const raced = await existingId(sb, sha256); // a parallel request stored the same image
      if (raced) return json(200, { id: raced, duplicate: true });
    }
    if (uploadedNow) await sb.storage.from('sources').remove([path]).catch(() => undefined);
    throw new Error(`insert source_images: ${inserted.error.message}`);
  }

  const id = (inserted.data as { id: string }).id;
  return json(201, { id, duplicate: false, mime: media.mime, ...(mimeCorrected ? { mime_corrected: true } : {}) });
}

Deno.serve(async (req: Request) => {
  try {
    return await handle(req);
  } catch (e) {
    console.error('intake failed:', e instanceof Error ? e.message : String(e));
    return json(500, { error: 'server_error' });
  }
});
