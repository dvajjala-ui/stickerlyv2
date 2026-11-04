# `intake` Edge Function

`POST /functions/v1/intake` lets the owner's image collector (HandNavigate) and worker adapters drop one
collected image into the pipeline. The function:

1. checks the `x-intake-token` header against the `INTAKE_TOKEN` secret (constant-time, both sides hashed first);
2. validates the JSON body and decodes the base64 image (max **8 MiB** decoded, 12 MiB request body);
3. detects the real type from **magic bytes** (PNG, JPEG, WebP, GIF, MP4, WebM; anything else is 415), so the
   stored type never depends on what the collector claimed;
4. hashes it (SHA-256) and refuses it with **409** if the hash or the `source_url` is in `public.takedowns`;
5. returns the existing row with `duplicate: true` if the hash is already in `public.source_images`;
6. uploads the original to the private `sources` bucket at `<yyyy>/<mm>/<sha256>.<ext>` (UTC);
7. inserts a `source_images` row (`status = 'new'`, `collector`, `site`, provenance, width/height, frame count).

`storage_path` holds the path **inside** the bucket (e.g. `2026/11/<sha256>.webp`), the same convention the admin
panel's uploads use.

## Request

```http
POST https://<project-ref>.supabase.co/functions/v1/intake
content-type: application/json
x-intake-token: <INTAKE_TOKEN>

{
  "image_base64": "<base64, optionally a data: URL>",
  "mime": "image/webp",
  "site": "reddit",
  "source_url": "https://i.redd.it/abc123.webp",
  "page_url": "https://www.reddit.com/r/IndianDankMemes/comments/abc123/",
  "author": "u/someone",
  "license": "unknown",
  "topic": "monday mood",
  "collector": "handnavigate"
}
```

| field | required | rules |
| --- | --- | --- |
| `image_base64` | yes | standard or URL-safe base64, a `data:` URL prefix and line breaks are fine |
| `mime` | yes | what the collector believes; the magic bytes decide (see `mime_corrected`) |
| `site` | yes | lower-cased; letters, digits, `.`, `_`, `-`; max 64 (`reddit`, `pinterest`, `imgflip`, …) |
| `source_url`, `page_url` | no | `http(s)` URLs, max 2048 characters |
| `author`, `license`, `topic` | no | free text, trimmed, cut to 300 characters (`topic` is stored as `topic_hint`) |
| `collector` | no | same charset as `site`; defaults to `unknown` |

## Responses

| status | body | meaning |
| --- | --- | --- |
| 201 | `{"id", "duplicate": false, "mime", "mime_corrected"?}` | stored and queued (`status = new`) |
| 200 | `{"id", "duplicate": true}` | already collected (same SHA-256); nothing changed |
| 400 | `{"error": "bad_request" \| "bad_json" \| "bad_base64", "detail", "field"}` | fix the request |
| 401 | `{"error": "unauthorized"}` | missing or wrong `x-intake-token` |
| 405 | `{"error": "method_not_allowed"}` | only `POST` (and CORS `OPTIONS`) |
| 409 | `{"error": "taken_down"}` | hash or source URL was taken down: never collect it again |
| 413 | `{"error": "too_large"}` | decoded image over 8 MiB, or body over 12 MiB |
| 415 | `{"error": "unsupported_media"}` | not PNG/JPEG/WebP/GIF/MP4/WebM by magic bytes |
| 500 | `{"error": "server_error"}` | logged server-side (`supabase functions logs intake`), no internals in the reply |
| 503 | `{"error": "not_configured"}` | `INTAKE_TOKEN` is missing or shorter than 16 characters |

Collectors should treat 200 and 409 as "done, don't retry", retry 500/503 with backoff, and log 4xx.

## Setup and deploy (owner approval needed; not done by the agents)

```bash
cd backend
# One long random secret, shared with the collectors only. Never commit it.
supabase secrets set INTAKE_TOKEN="$(openssl rand -hex 32)" --project-ref <project-ref>
# verify_jwt = false comes from ../../config.toml ([functions.intake]); the flag below says the same.
supabase functions deploy intake --no-verify-jwt --project-ref <project-ref>
```

`SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are injected by the platform. The service-role key never leaves
the function. Dependencies are pinned in `deno.json` (`@supabase/supabase-js` 2.118.0).

## Examples

Bash (Git Bash / Linux / macOS). The body goes through stdin, so large images don't hit argument limits:

```bash
FILE=meme.webp
{ printf '{"mime":"image/webp","site":"reddit","source_url":"https://i.redd.it/abc123.webp","collector":"handnavigate","image_base64":"'
  base64 -w0 "$FILE"            # macOS: base64 -i "$FILE"
  printf '"}'
} | curl -sS -X POST "https://<project-ref>.supabase.co/functions/v1/intake" \
      -H "content-type: application/json" \
      -H "x-intake-token: $INTAKE_TOKEN" \
      --data-binary @-
# → {"id":"…","duplicate":false,"mime":"image/webp"}
```

PowerShell:

```powershell
$body = @{
  image_base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\memes\meme.webp"))
  mime = "image/webp"; site = "reddit"; source_url = "https://i.redd.it/abc123.webp"; collector = "handnavigate"
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "https://<project-ref>.supabase.co/functions/v1/intake" `
  -ContentType "application/json" -Headers @{ "x-intake-token" = $env:INTAKE_TOKEN } -Body $body
```

## Tests

`lib.ts` is pure TypeScript (no Deno APIs), so the validation, base64, magic-byte, probing, token and path
rules run under Node in the admin's vitest suite: `cd admin && npm test` (`src/lib/intake.test.ts`).
The admin panel imports the same file for its browser-side upload checks, so both paths share one implementation.
`deno check --no-lock index.ts` type-checks the function itself.
