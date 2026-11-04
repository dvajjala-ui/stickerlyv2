// Row types mirroring backend/supabase/migrations/20261105000001_core.sql.
// `embedding` and `search_text` columns are never selected by the admin.

export type Uuid = string
export type Iso = string
export type Json = string | number | boolean | null | Json[] | { [key: string]: Json }

export interface PageReq {
  offset: number
  limit: number
}
export interface Page<T> {
  rows: T[]
  total: number
}

/** Filter for nullable booleans: any value, true, false or null. */
export type TriState = 'any' | 'yes' | 'no' | 'unknown'

// ------------------------------------------------------------------ sources

export const SOURCE_STATUSES = ['new', 'screened', 'analyzing', 'analyzed', 'used', 'rejected', 'failed', 'taken_down'] as const
export type SourceStatus = (typeof SOURCE_STATUSES)[number]
export const SOURCE_KINDS = ['meme', 'reaction', 'scene', 'cartoon', 'text_post', 'animal', 'baby', 'couple', 'other'] as const
export const NSFW_LEVELS = ['none', 'suggestive', 'explicit'] as const
export type Nsfw = (typeof NSFW_LEVELS)[number]

export interface SourceImage {
  id: Uuid
  sha256: string
  phash: string | null
  storage_path: string
  mime: string
  width: number | null
  height: number | null
  bytes: number
  frames: number
  duration_ms: number
  site: string
  source_url: string | null
  page_url: string | null
  author: string | null
  license: string | null
  collector: string
  topic_hint: string | null
  collected_at: Iso
  status: SourceStatus
  attempts: number
  locked_until: Iso | null
  last_error: string | null
  prescreen: Json | null
  prescreen_score: number | null
  ocr_text: string | null
  analysis: Json | null
  kind: string | null
  has_text: boolean | null
  language: string | null
  category: string | null
  mood: string[]
  tags: string[]
  emojis: string[]
  description: string | null
  nsfw: Nsfw | null
  minors: boolean | null
  real_person: boolean | null
  public_figure: string | null
  sticker_score: number | null
  analyzed_at: Iso | null
  created_at: Iso
  updated_at: Iso
}

export const SOURCE_COLUMNS = [
  'id', 'sha256', 'phash', 'storage_path', 'mime', 'width', 'height', 'bytes', 'frames', 'duration_ms', 'site',
  'source_url', 'page_url', 'author', 'license', 'collector', 'topic_hint', 'collected_at', 'status', 'attempts',
  'locked_until', 'last_error', 'prescreen', 'prescreen_score', 'ocr_text', 'analysis', 'kind', 'has_text', 'language',
  'category', 'mood', 'tags', 'emojis', 'description', 'nsfw', 'minors', 'real_person', 'public_figure',
  'sticker_score', 'analyzed_at', 'created_at', 'updated_at',
] as const satisfies readonly (keyof SourceImage)[]

/** Columns for grid views (no JSON blobs). */
export const SOURCE_LIST_COLUMNS = [
  'id', 'sha256', 'storage_path', 'mime', 'width', 'height', 'bytes', 'frames', 'site', 'source_url', 'author',
  'license', 'collector', 'topic_hint', 'collected_at', 'status', 'kind', 'has_text', 'category', 'tags', 'nsfw',
  'minors', 'real_person', 'public_figure', 'sticker_score', 'prescreen_score', 'description', 'analyzed_at',
  'last_error',
] as const satisfies readonly (keyof SourceImage)[]
export type SourceSummary = Pick<SourceImage, (typeof SOURCE_LIST_COLUMNS)[number]>

export type SourceSort = 'collected' | 'score' | 'analyzed'
export interface SourceFilter {
  status?: SourceStatus
  site?: string
  kind?: string
  category?: string
  nsfw?: Nsfw | 'unknown'
  minors?: TriState
  hasText?: TriState
  scoreMin?: number
  scoreMax?: number
  q?: string
  sort?: SourceSort
}

export type UploadOutcome =
  | { status: 'created'; id: Uuid; path: string }
  | { status: 'duplicate'; id: Uuid }
  | { status: 'blocked'; reason: string }

// ------------------------------------------------------------------ stickers

export const STICKER_STATUSES = ['draft', 'published', 'rejected', 'taken_down'] as const
export type StickerStatus = (typeof STICKER_STATUSES)[number]
export type StickerKind = 'static' | 'animated'
export type Rating = 'general' | 'adult'
export const RECIPES = ['direct', 'caption', 'cutout', 'motion', 'remake', 'original', 'upload'] as const
export type Recipe = (typeof RECIPES)[number]

export interface Credit {
  site?: string | null
  url?: string | null
  author?: string | null
  license?: string | null
}

export interface Sticker {
  id: Uuid
  source_id: Uuid | null
  recipe: Recipe
  kind: StickerKind
  rating: Rating
  category: string
  caption: string | null
  language: string | null
  tags: string[]
  emojis: string[]
  description: string | null
  file_path: string
  sha256: string
  phash: string | null
  bytes: number
  ai_generated: boolean
  credit: Credit | null
  quality: number | null
  trend_id: Uuid | null
  status: StickerStatus
  review_note: string | null
  published_at: Iso | null
  created_at: Iso
  updated_at: Iso
}

export const STICKER_COLUMNS = [
  'id', 'source_id', 'recipe', 'kind', 'rating', 'category', 'caption', 'language', 'tags', 'emojis', 'description',
  'file_path', 'sha256', 'phash', 'bytes', 'ai_generated', 'credit', 'quality', 'trend_id', 'status', 'review_note',
  'published_at', 'created_at', 'updated_at',
] as const satisfies readonly (keyof Sticker)[]

/** Safety/provenance flags of a sticker's source, embedded with each sticker. */
export type SourceFlags = Pick<
  SourceImage,
  'id' | 'site' | 'source_url' | 'page_url' | 'author' | 'license' | 'nsfw' | 'minors' | 'real_person' | 'public_figure'
>
export const SOURCE_FLAG_COLUMNS = [
  'id', 'site', 'source_url', 'page_url', 'author', 'license', 'nsfw', 'minors', 'real_person', 'public_figure',
] as const satisfies readonly (keyof SourceFlags)[]

export interface StickerRow extends Sticker {
  source: SourceFlags | null
}

export type StickerSort = 'created' | 'published' | 'quality' | 'size'
export interface StickerFilter {
  status?: StickerStatus
  category?: string
  rating?: Rating
  kind?: StickerKind
  q?: string
  excludeIds?: Uuid[]
  sort?: StickerSort
}

export type StickerPatch = Partial<
  Pick<Sticker, 'caption' | 'tags' | 'emojis' | 'category' | 'rating' | 'ai_generated' | 'review_note' | 'description'>
>

// ------------------------------------------------------------------ packs

export const PACK_TYPES = ['daily', 'series', 'situation', 'trend', 'manual'] as const
export type PackType = (typeof PACK_TYPES)[number]
export const PACK_STATUSES = ['draft', 'published', 'archived', 'taken_down'] as const
export type PackStatus = (typeof PACK_STATUSES)[number]

export interface Pack {
  id: Uuid
  slug: string | null
  title: string
  description: string | null
  kind: StickerKind
  rating: Rating
  category: string | null
  pack_type: PackType
  series: string | null
  volume: number
  cover_sticker_id: Uuid | null
  version: number
  sticker_count: number
  status: PackStatus
  published_at: Iso | null
  created_at: Iso
  updated_at: Iso
}

export interface PackRow extends Pack {
  cover: Pick<Sticker, 'id' | 'file_path' | 'kind'> | null
}

export interface PackItemRow {
  sticker_id: Uuid
  position: number
  added_at: Iso
  sticker: StickerRow
}

export interface PackFilter {
  pack_type?: PackType
  status?: PackStatus
  q?: string
}

export type PackInput = Pick<
  Pack,
  'title' | 'description' | 'kind' | 'rating' | 'category' | 'pack_type' | 'series' | 'volume' | 'cover_sticker_id'
>

export type PackRef = Pick<Pack, 'id' | 'title' | 'kind' | 'rating' | 'status'>

// ------------------------------------------------------------------ reports & takedowns

export const REPORT_REASONS = ['copyright', 'person', 'hate', 'sexual', 'minor', 'other'] as const
export type ReportReason = (typeof REPORT_REASONS)[number]
export type ReportStatus = 'open' | 'actioned' | 'dismissed'
export type TargetType = 'sticker' | 'pack'

export interface Report {
  id: Uuid
  target_type: TargetType
  target_id: Uuid
  reason: ReportReason
  details: string | null
  status: ReportStatus
  created_at: Iso
  resolved_at: Iso | null
}

export interface Takedown {
  id: Uuid
  sha256: string | null
  phash: string | null
  source_url: string | null
  reason: string
  report_id: Uuid | null
  created_at: Iso
}

// ------------------------------------------------------------------ trends

export interface Trend {
  id: Uuid
  day: string
  title: string
  summary: string | null
  keywords: string[]
  category: string | null
  source: string
  source_urls: string[]
  score: number
  active: boolean
  created_at: Iso
}

export interface TrendInput {
  day: string
  title: string
  summary: string | null
  keywords: string[]
  category: string | null
  score: number
}

// ------------------------------------------------------------------ settings

export interface PublishPolicy {
  auto_publish: boolean
  min_quality: number
  adult_lane: boolean
  celebrities: 'publish' | 'review'
  require_review_sites: string[]
}

export interface Budgets {
  vision_per_day: number
  text_per_day: number
  image_gen_per_day: number
  max_new_stickers_per_day: number
}

export interface CategoriesSetting {
  enabled: string[]
}

export interface SettingValues {
  publish_policy: PublishPolicy
  budgets: Budgets
  categories: CategoriesSetting
}
export type SettingKey = keyof SettingValues

export interface SettingsBundle extends SettingValues {
  updated_at: Partial<Record<SettingKey, Iso>>
}

/** Seed values from the migration; used when a row is missing. */
export const DEFAULT_SETTINGS: SettingValues = {
  publish_policy: { auto_publish: true, min_quality: 6, adult_lane: true, celebrities: 'publish', require_review_sites: [] },
  budgets: { vision_per_day: 60, text_per_day: 400, image_gen_per_day: 0, max_new_stickers_per_day: 120 },
  categories: {
    enabled: [
      'reactions', 'funny', 'hinglish', 'movies', 'old_movies', 'songs', 'memes', 'dark', 'relationship', 'office',
      'festivals', 'animals', 'baby', 'trending', 'adult',
    ],
  },
}

export interface ProtectedPerson {
  name: string
  note: string | null
  created_at: Iso
}

// ------------------------------------------------------------------ AI usage & worker runs

/** ai_calls.purpose values counted against each daily budget. Everything else is "text". */
export const VISION_PURPOSES = ['analyze'] as const
export const IMAGE_PURPOSES = ['image'] as const

export interface AiUsage {
  vision: number
  text: number
  image: number
  failed: number
  total: number
}

export interface WorkerRun {
  id: number
  started_at: Iso
  finished_at: Iso | null
  stage: string
  ok: boolean | null
  summary: Json | null
}

// ------------------------------------------------------------------ auth

export interface SessionUser {
  id: Uuid
  email: string | null
}
