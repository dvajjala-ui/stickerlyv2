# Deduplication — the dedupe tool's gaps and a better design

## Problem (owner)
"The dedupe tool lacks many functionalities." On the live batch the owner also saw near-identical stickers and two
near-twin packs.

## How it works today
- **Shared hash:** `core/Dhash.kt` (app) and `imaging/metrics.py` (worker) compute a perceptual hash (dHash/pHash);
  near-duplicate if Hamming distance ≤ a small threshold (`NEAR_DUPLICATE = 5`).
- **Worker prescreen** rejects a new source that pHash-matches an existing one; the **quality gate** skips a render that
  matches an existing sticker — **but excludes the same `source_id`** (worker review **P9**), so re-processing one
  source publishes multiple caption variants of the same picture.
- **App "Find duplicates"** (`DuplicatesScreen`): hashes the library, groups near-dups, lets the user merge (keeper
  takes the copies' pack places/cover/favourite/words), with undo. But it is **O(n²)** and **rescans the whole library
  after every single merge** (app review A34), and the near-dup check runs **before** the exact-SHA check and **ignores
  vault/rating** (A5) — so a normal import matching a vault sticker is dropped, and the same photo with a different
  caption can be rejected.
- Synthetic-animated twins (see `animated-stickers.md`) create intentional near-dups the dedupe then trips over.

## What research says
- `imagehash` (Python) is the standard: aHash/pHash/dHash/wHash, compared by Hamming distance; a 2025 guide suggests
  thresholds ~5–10. It does **not** cluster — you add clustering yourself.
- `imgdupes` — ready-made CLI to find/delete near-dups by perceptual hash (good reference for batch UX).
- `imgdd` — Rust+Python, multiple hashes (GPL-3.0 — license-check before use in MIT code).
- **PDQ** (Meta's `pdqhash`, via `phim`) — robust 256-bit perceptual hash designed for near-dup/abuse matching at scale;
  stronger than dHash for adversarial/recompressed images.
- **Clustering pattern** (none of the libs do it for you): compute hashes → index neighbours (or pairwise under a
  threshold) → **union-find** to form groups. This is the missing piece for "show me all the dupes grouped".

## Gaps (what "lacks functionality" means concretely)
1. Dedupe only acts **within one library**; no cross-pack or whole-catalog view.
2. No **grouped clusters** UI — merges are pairwise, slow (full rescan each merge).
3. Near-dup runs before SHA and **ignores vault/rating** → wrong drops (A5).
4. Worker lets the **same source** publish multiple variants (P9).
5. No **"keep the best"** heuristic (sharpest/biggest/most-complete) — the keeper is just the older one.
6. No dedupe across **static vs its synthetic-animated twin**.

## Resolution path (easiest first)
1. **Fix correctness first (from the review):** SHA before near-dup; make near-dup respect vault + rating (A5); stop the
   worker re-publishing variants of the same `source_id` (P9); stop auto-twinning statics (animated-stickers.md #2).
2. **Cluster, don't pair:** compute hashes once, union-find into groups under the threshold, show the user **groups**
   ("12 look the same — keep 1") with a one-tap "keep best, remove rest". Recompute only the touched group, not the
   whole library (fixes A34 O(n²)).
3. **Keep-best heuristic:** within a cluster prefer highest resolution, sharpest (`metrics.sharpness`), largest subject,
   fewest artifacts; the chosen one inherits pack places/cover/favourite/words (already how merge works).
4. **Stronger hash where it matters:** keep dHash for the fast first pass; add **PDQ** for the cross-catalog dedupe
   (recompressed memes), which is where dHash is weakest.
5. **Catalog-level dedupe in the worker/admin:** before publishing, cluster new renders against the published set and the
   same batch, so Discover never shows two of the same meme (admin "near-duplicates" review already exists — wire it to
   clusters).
6. **Expose it simply** (ties to UX plan): the user never sees "hash" or "distance" — just "These look the same" with a
   picture and "Keep this one".

## Open questions
- Threshold tuning per content type (memes recompress a lot → looser; original art → tighter).
- Do we auto-remove catalog dups or hold for admin? (Auto for exact/very-near; hold for borderline.)

## Sources
- imagehash + thresholds: https://pypi.org/project/phim/ · https://ssojet.com/hashing/phash-in-python
- imgdupes (batch CLI): https://awesome.ecosyste.ms/projects/github.com%2Fknjcode%2Fimgdupes
- imgdd (Rust/Python, GPL): https://lib.rs/crates/imgdd
- PDQ via phim/pdqhash: https://pypi.org/project/phim/
