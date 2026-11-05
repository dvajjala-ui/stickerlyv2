// Realistic demo rows (Hinglish stickers, Indian sources) generated deterministically, with
// times relative to "now" so the Overview's "today" numbers are populated.
// Every image is a generated SVG; no third-party content ships with the panel.

import { DEMO_ADMIN_EMAIL } from '../auth'
import { addDays, istDate, istDayStart } from '../../lib/format'
import { sourceObjectPath } from '../../lib/media'
import type {
  Json,
  Nsfw,
  Pack,
  PackStatus,
  PackType,
  Rating,
  Recipe,
  Report,
  ReportReason,
  SourceImage,
  SourceStatus,
  Sticker,
  StickerKind,
  StickerStatus,
  Trend,
  WorkerRun,
} from '../types'
import { DEFAULT_SETTINGS } from '../types'
import { mulberry32, sourceArt, stickerArt, type ArtFlaw } from './art'
import { emptyDemoDb, touchPackCounts, type DemoDb } from './db'

export interface SeedOptions {
  latencyMs?: number
  now?: Date
  seed?: number
}

const HUE: Record<string, number> = {
  reactions: 48, funny: 28, hinglish: 80, movies: 0, old_movies: 18, songs: 300, memes: 200, dark: 260,
  relationship: 340, office: 210, festivals: 35, animals: 140, baby: 190, trending: 170, adult: 320,
}

const KIND_FOR_CATEGORY: Record<string, string> = {
  office: 'reaction', animals: 'animal', baby: 'baby', relationship: 'couple', old_movies: 'scene', movies: 'scene',
  festivals: 'other', memes: 'meme', hinglish: 'text_post', dark: 'meme', adult: 'meme', funny: 'reaction',
  reactions: 'reaction', songs: 'scene',
}

const SITES = ['reddit', 'pinterest', 'imgflip', 'openverse'] as const
const SIZES = [
  [1080, 1080], [720, 900], [800, 600], [1280, 720], [640, 640], [900, 1200],
] as const

/** `at` < 1 → that fraction of today (IST) has elapsed; otherwise hours ago. */
interface StickerSpec {
  caption: string
  emojis: string[]
  cat: string
  kind: StickerKind
  rating: Rating
  status: StickerStatus
  recipe: Recipe
  tags: string[]
  q: number
  kb: number
  scene: string
  at: number
  real?: boolean
  minors?: boolean
  figure?: string
  nsfw?: Nsfw
  note?: string
  /** Quality gate penalties (worker imaging/quality.py); score = 100 − their sum unless `score` says otherwise. */
  penalties?: Record<string, number>
  /** Final gate score when caps or the vision judge changed it. */
  score?: number
  /** Extra quality_signals (measurements, judge verdict). */
  signals?: { [key: string]: Json }
  /** Reasons the vision judge added (`judge:<issue>`). */
  judged?: string[]
  /** near_duplicate: caption of the sticker this one repeats. */
  dupOf?: string
  flaw?: ArtFlaw
}

/** Stickers older than this were made before the quality gate shipped, so they have no score. */
const GATE_SHIPPED_HOURS_AGO = 120
const GATE_MIN_SCORE = DEFAULT_SETTINGS.quality_gate.min_score

const S = 'static' as const
const A = 'animated' as const
const G = 'general' as const
const X = 'adult' as const

const STICKERS: StickerSpec[] = [
  { caption: 'Kya scene hai?', emojis: ['🤔', '👀'], cat: 'hinglish', kind: S, rating: G, status: 'published', recipe: 'caption', tags: ['question', 'curious', 'kya scene'], q: 8, kb: 46, scene: 'Guy leaning in with a suspicious squint', at: 0.9 },
  { caption: 'Bas kar pagle, rulayega kya', emojis: ['😭', '🥹'], cat: 'funny', kind: S, rating: G, status: 'published', recipe: 'caption', tags: ['emotional', 'crying', 'dosti'], q: 9, kb: 62, scene: 'Two friends hugging dramatically at a farewell', at: 0.75, real: true },
  { caption: 'Monday ko dekh ke', emojis: ['😩'], cat: 'office', kind: S, rating: G, status: 'published', recipe: 'direct', tags: ['monday', 'office', 'tired'], q: 7, kb: 38, scene: 'Man staring blankly at a laptop on a Monday video call', at: 30, real: true },
  { caption: 'Meeting jo email ho sakti thi', emojis: ['📧', '🙄'], cat: 'office', kind: S, rating: G, status: 'published', recipe: 'caption', tags: ['meeting', 'email', 'office'], q: 8, kb: 51, scene: 'Woman rolling her eyes in a glass-walled meeting room', at: 52, real: true },
  { caption: 'Chai peelo, sab theek ho jayega', emojis: ['☕', '😌'], cat: 'reactions', kind: S, rating: G, status: 'published', recipe: 'cutout', tags: ['chai', 'calm', 'advice'], q: 9, kb: 44, scene: 'Kitten peeking out of a kulhad chai cup', at: 70 },
  { caption: 'Arre yaar!', emojis: ['🤦'], cat: 'reactions', kind: A, rating: G, status: 'published', recipe: 'motion', tags: ['facepalm', 'frustrated'], q: 8, kb: 312, scene: 'Uncle doing a slow facepalm at a wedding', at: 0.6, real: true },
  { caption: 'Aukaat mein reh', emojis: ['😤'], cat: 'dark', kind: S, rating: G, status: 'published', recipe: 'caption', tags: ['savage', 'attitude'], q: 7, kb: 58, scene: 'Cartoon cat with arms crossed and a glare', at: 40 },
  { caption: 'Sab moh maya hai', emojis: ['🧘', '✨'], cat: 'memes', kind: S, rating: G, status: 'published', recipe: 'direct', tags: ['philosophy', 'detached'], q: 8, kb: 41, scene: 'Cartoon sage meditating on a mountain top', at: 90 },
  { caption: 'Paisa hi paisa hoga', emojis: ['🤑', '💸'], cat: 'movies', kind: A, rating: G, status: 'published', recipe: 'motion', tags: ['money', 'dream'], q: 9, kb: 388, scene: 'Cartoon showering banknotes in slow motion', at: 26 },
  { caption: 'Kal se pakka', emojis: ['🫡'], cat: 'funny', kind: S, rating: G, status: 'published', recipe: 'caption', tags: ['procrastination', 'promise'], q: 8, kb: 35, scene: 'Student asleep on a pile of books', at: 120 },
  { caption: 'Bhai tu rehne de', emojis: ['✋', '😑'], cat: 'reactions', kind: S, rating: G, status: 'published', recipe: 'caption', tags: ['stop', 'dosti'], q: 7, kb: 49, scene: 'Man raising a palm to stop his friend mid-sentence', at: 0.5, real: true },
  { caption: 'Mummy ki chappal incoming', emojis: ['🩴', '🏃'], cat: 'funny', kind: A, rating: G, status: 'published', recipe: 'motion', tags: ['mummy', 'family', 'run'], q: 9, kb: 421, scene: 'Cartoon slipper flying across a living room', at: 33 },
  { caption: 'Happy Diwali', emojis: ['🪔', '🎆'], cat: 'festivals', kind: A, rating: G, status: 'draft', recipe: 'original', tags: ['diwali', 'festival', 'wishes'], q: 8, kb: 290, scene: '', at: 0.4 },
  { caption: 'Shubh Deepavali', emojis: ['🪔'], cat: 'festivals', kind: A, rating: G, status: 'draft', recipe: 'original', tags: ['diwali', 'festival'], q: 7, kb: 264, scene: '', at: 0.35 },
  { caption: 'Patakhe nahi, pyaar jalao', emojis: ['❤️', '🪔'], cat: 'festivals', kind: A, rating: G, status: 'draft', recipe: 'remake', tags: ['diwali', 'eco'], q: 6, kb: 241, scene: 'Rangoli and diyas on a doorstep', at: 0.3 },
  { caption: 'Billi mausi approves', emojis: ['🐱', '👍'], cat: 'animals', kind: S, rating: G, status: 'published', recipe: 'cutout', tags: ['cat', 'approve', 'cute'], q: 8, kb: 39, scene: 'Fluffy cat giving a slow approving blink', at: 60 },
  { caption: 'Doggo ka attitude', emojis: ['🐶', '😎'], cat: 'animals', kind: S, rating: G, status: 'draft', recipe: 'cutout', tags: ['dog', 'attitude'], q: 7, kb: 57, scene: 'Indie dog wearing sunglasses on a scooter', at: 0.2 },
  { caption: 'Baby ki smile', emojis: ['👶', '😊'], cat: 'baby', kind: S, rating: G, status: 'published', recipe: 'cutout', tags: ['baby', 'cute', 'smile'], q: 8, kb: 33, scene: 'Toddler grinning with two teeth', at: 80, minors: true, real: true },
  { caption: 'Tum toh dhokebaaz nikle', emojis: ['💔', '😒'], cat: 'relationship', kind: S, rating: G, status: 'published', recipe: 'caption', tags: ['betrayal', 'couple'], q: 8, kb: 54, scene: 'Couple on a park bench, one looking away', at: 100, real: true },
  { caption: 'Shaadi kab karoge?', emojis: ['💍', '😬'], cat: 'relationship', kind: S, rating: G, status: 'draft', recipe: 'caption', tags: ['shaadi', 'relatives', 'family'], q: 9, kb: 47, scene: 'Aunties whispering at a family function', at: 0.65, real: true },
  { caption: 'Rishta pakka', emojis: ['🤝', '💍'], cat: 'relationship', kind: A, rating: G, status: 'draft', recipe: 'motion', tags: ['rishta', 'shaadi'], q: 7, kb: 356, scene: 'Two fathers shaking hands with garlands on', at: 0.55, real: true },
  { caption: 'Picture abhi baaki hai mere dost', emojis: ['🎬'], cat: 'old_movies', kind: S, rating: G, status: 'published', recipe: 'direct', tags: ['dialogue', 'filmy'], q: 8, kb: 72, scene: 'Retro film still of a hero pointing at the camera', at: 140, real: true },
  { caption: 'Ye toh hona hi tha', emojis: ['🤷'], cat: 'memes', kind: S, rating: G, status: 'published', recipe: 'direct', tags: ['expected', 'meh'], q: 7, kb: 29, scene: 'Shrugging cartoon potato', at: 150 },
  { caption: 'Mood off hai', emojis: ['🌧️'], cat: 'reactions', kind: S, rating: G, status: 'draft', recipe: 'caption', tags: ['sad', 'mood'], q: 6, kb: 43, scene: 'Rainy window with a sad cat silhouette', at: 0.15 },
  { caption: 'Thoda adjust kar lo', emojis: ['🙏'], cat: 'hinglish', kind: S, rating: G, status: 'draft', recipe: 'caption', tags: ['adjust', 'request', 'local train'], q: 7, kb: 128, scene: 'Crowded local train compartment', at: 0.12, real: true },
  { caption: 'Light le yaar', emojis: [], cat: 'hinglish', kind: S, rating: G, status: 'draft', recipe: 'caption', tags: ['chill'], q: 6, kb: 52, scene: 'Man lounging in a hammock', at: 0.1 },
  { caption: 'Naach meri jaan', emojis: ['💃', '🕺'], cat: 'songs', kind: A, rating: G, status: 'published', recipe: 'motion', tags: ['dance', 'party', 'baraat'], q: 8, kb: 455, scene: 'Baraat dancers in a street procession', at: 45, real: true },
  { caption: 'Gaana bajao', emojis: ['🎶', '🔊'], cat: 'songs', kind: S, rating: G, status: 'draft', recipe: 'caption', tags: ['music', 'party'], q: 6, kb: 66, scene: 'Old cassette player with big speakers', at: 0.08 },
  { caption: 'Double meaning mat samjho', emojis: ['😏'], cat: 'adult', kind: S, rating: X, status: 'published', recipe: 'caption', tags: ['double meaning', 'spicy'], q: 8, kb: 48, scene: 'Cartoon man raising one eyebrow', at: 50, nsfw: 'suggestive' },
  { caption: 'Raat ko call mat karna', emojis: ['🌚', '📵'], cat: 'adult', kind: S, rating: X, status: 'published', recipe: 'caption', tags: ['late night', 'savage'], q: 7, kb: 55, scene: 'Phone face-down on a bedside table at night', at: 75, nsfw: 'suggestive' },
  { caption: 'Savage mode on', emojis: ['💀', '🔥'], cat: 'dark', kind: S, rating: X, status: 'published', recipe: 'caption', tags: ['savage', 'dark'], q: 8, kb: 61, scene: 'Cartoon skeleton in sunglasses on a throne', at: 85 },
  { caption: 'Ex ka message aaya', emojis: ['🙈', '📱'], cat: 'relationship', kind: S, rating: X, status: 'published', recipe: 'caption', tags: ['ex', 'spicy'], q: 8, kb: 58, scene: 'Monkey covering its eyes next to a buzzing phone', at: 95, nsfw: 'suggestive' },
  { caption: 'Thand rakh', emojis: ['🧊'], cat: 'adult', kind: S, rating: X, status: 'draft', recipe: 'caption', tags: ['chill', 'spicy'], q: 7, kb: 44, scene: 'Ice cube with a smug face', at: 0.45 },
  { caption: 'WFH dress code', emojis: ['👔', '🩳'], cat: 'office', kind: S, rating: G, status: 'published', recipe: 'remake', tags: ['wfh', 'office'], q: 8, kb: 52, scene: 'Man in a shirt, tie and shorts on a video call', at: 110, real: true },
  { caption: 'Salary aayi, salary gayi', emojis: ['💸', '😭'], cat: 'office', kind: A, rating: G, status: 'published', recipe: 'motion', tags: ['salary', 'month end'], q: 9, kb: 377, scene: 'Cartoon wallet with money flying away', at: 28 },
  { caption: 'HR ko mat batana', emojis: ['🤫'], cat: 'office', kind: S, rating: G, status: 'published', recipe: 'caption', tags: ['hr', 'secret'], q: 8, kb: 41, scene: 'Colleagues whispering by the coffee machine', at: 65, real: true },
  { caption: 'Weekend kab aayega', emojis: ['📅', '😮‍💨'], cat: 'office', kind: S, rating: G, status: 'published', recipe: 'caption', tags: ['weekend', 'office'], q: 7, kb: 37, scene: 'Desk calendar with Friday circled in red', at: 36 },
  { caption: 'Jhooth mat bolo', emojis: ['🤥'], cat: 'reactions', kind: S, rating: G, status: 'rejected', recipe: 'caption', tags: ['lie'], q: 4, kb: 45, scene: 'Blurry screenshot of a TV serial', at: 0.7, note: 'Low-quality crop', penalties: { blurry: 45, small_subject: 7.2 }, signals: { bbox: 0.2352, sharpness: 10.4, sharpness_rel: 0.0068 } },
  { caption: 'Blurry copy', emojis: ['😐'], cat: 'memes', kind: S, rating: G, status: 'rejected', recipe: 'direct', tags: ['lowres'], q: 2, kb: 22, scene: 'Heavily compressed reposted meme', at: 0.66, note: 'Too blurry', penalties: { blurry: 45 }, signals: { sharpness: 7.9, sharpness_rel: 0.0049 } },
  { caption: 'Bade log, badi baatein', emojis: ['🙄'], cat: 'memes', kind: S, rating: G, status: 'taken_down', recipe: 'direct', tags: ['comic'], q: 6, kb: 40, scene: 'Panel from a paid web comic', at: 200, note: 'report:copyright — creator request' },
  { caption: 'Hero entry', emojis: ['😎'], cat: 'movies', kind: S, rating: G, status: 'draft', recipe: 'direct', tags: ['filmy', 'entry'], q: 7, kb: 63, scene: 'Film still of a famous actor making a slow-motion entry', at: 0.25, real: true, figure: 'Anil Kapoor' },
  { caption: 'Chhota packet, bada dhamaka', emojis: ['💥'], cat: 'adult', kind: S, rating: X, status: 'draft', recipe: 'caption', tags: ['spicy'], q: 6, kb: 49, scene: 'Toddler flexing tiny arms', at: 0.22, minors: true, real: true },
  // Held by the quality gate: the policy would publish them, the gate scored them below 60.
  // Hard reasons (tiny_subject, caption_low_contrast, near_duplicate, caption_clipped) cap the score at 35.
  { caption: 'Kitna late hai yaar', emojis: ['⏰', '😤'], cat: 'reactions', kind: S, rating: G, status: 'held', recipe: 'caption', tags: ['late', 'waiting', 'friends'], q: 7, kb: 41, scene: 'Friend tapping a wristwatch outside a cafe', at: 0.58, real: true, penalties: { blurry: 45 }, signals: { sharpness: 8.7, sharpness_rel: 0.0061 }, flaw: 'blurry' },
  { caption: 'Ghar se kaam, ghar ka kaam', emojis: ['🏠', '💻'], cat: 'office', kind: S, rating: G, status: 'held', recipe: 'cutout', tags: ['wfh', 'chores', 'office'], q: 7, kb: 36, scene: 'Woman on a laptop call while stirring a pot', at: 0.48, real: true, penalties: { tiny_subject: 50 }, score: 35, signals: { visible: 0.0861, bbox: 0.1043, fill: 0.826, measured: 0.0312 }, flaw: 'tiny' },
  { caption: 'Sunday ko bhi meeting?', emojis: ['📅', '😩'], cat: 'office', kind: S, rating: G, status: 'held', recipe: 'caption', tags: ['meeting', 'weekend', 'office'], q: 8, kb: 47, scene: 'Man in pyjamas joining a video call', at: 0.42, real: true, penalties: { caption_low_contrast: 45 }, score: 35, signals: { caption_contrast: 1.84 }, flaw: 'low_contrast' },
  { caption: 'Chai break?', emojis: ['☕', '🙂'], cat: 'reactions', kind: S, rating: G, status: 'held', recipe: 'cutout', tags: ['chai', 'break'], q: 8, kb: 43, scene: 'Kitten peeking out of a clay cup, cropped tighter', at: 3, penalties: { near_duplicate: 50 }, score: 35, dupOf: 'Chai peelo, sab theek ho jayega' },
  { caption: 'Nachoge nahi?', emojis: ['💃', '🎉'], cat: 'songs', kind: A, rating: G, status: 'held', recipe: 'motion', tags: ['dance', 'party', 'sangeet'], q: 7, kb: 268, scene: 'Cousins dancing at a sangeet', at: 0.3, real: true, penalties: { animation_few_frames: 15, animation_choppy: 15, soft: 14.2 }, signals: { sharpness: 19.1, sharpness_rel: 0.0105, frames: 2, duration_ms: 800, median_frame_ms: 400 } },
  { caption: 'Bas 5 minute', emojis: ['⏳', '🙏'], cat: 'hinglish', kind: S, rating: G, status: 'held', recipe: 'caption', tags: ['waiting', 'excuse', '5 minute'], q: 7, kb: 39, scene: 'Man on a scooter waving from a traffic jam', at: 20, real: true, penalties: { small_subject: 14.4, soft: 9.5 }, score: 53, judged: ['pixelated'], signals: { bbox: 0.17, sharpness: 27.9, sharpness_rel: 0.0151, judge: { score: 3, issues: ['pixelated'] }, local_score: 76 } },
  // No emoji: the panel's publish rules block approving it, so a bulk approve reports it as skipped.
  { caption: 'Raat abhi baaki hai', emojis: [], cat: 'adult', kind: S, rating: X, status: 'held', recipe: 'caption', tags: ['late night', 'spicy'], q: 7, kb: 45, scene: 'Cartoon owl winking under a full moon', at: 0.12, nsfw: 'suggestive', penalties: { caption_clipped: 45 }, score: 35, signals: { caption_box: [-14, 396, 526, 480] } },
]

/** quality_signals as the worker writes them: base measurements, the spec's overrides, then penalties. */
function gateSignals(i: number, spec: StickerSpec, penalties: Record<string, number>): { [key: string]: Json } {
  const visible = 0.55 + (i % 9) * 0.03
  const bbox = Math.min(0.97, visible + 0.09)
  const sharpness = 180 + ((i * 53) % 900)
  const detail = 40 + (i % 7) * 1.5
  const out: { [key: string]: Json } = {
    visible: Math.round(visible * 1e4) / 1e4,
    bbox: Math.round(bbox * 1e4) / 1e4,
    fill: Math.round((visible / bbox) * 1e3) / 1e3,
    measured: Math.round((visible - 0.2) * 1e4) / 1e4,
    sharpness,
    sharpness_rel: Math.round((sharpness / detail ** 2) * 1e4) / 1e4,
    detail,
    flat: false,
  }
  if (spec.recipe !== 'direct') Object.assign(out, { caption_box: [40, 400, 472, 488], caption_contrast: 8 + (i % 5) * 0.7 })
  if (spec.kind === 'animated') Object.assign(out, { frames: 12, duration_ms: 1300, median_frame_ms: 100 })
  Object.assign(out, spec.signals ?? {})
  if (spec.signals?.sharpness !== undefined && spec.signals.sharpness_rel !== undefined) {
    out.detail = Math.round(Math.sqrt(Number(spec.signals.sharpness) / Number(spec.signals.sharpness_rel)) * 10) / 10
  }
  const order = Object.keys(penalties).sort((a, b) => penalties[b] - penalties[a])
  out.penalties = Object.fromEntries(order.map((r) => [r, penalties[r]]))
  return out
}

/** Gate columns for one fixture: older stickers predate the gate; most others are clean (100). */
function gateColumns(i: number, spec: StickerSpec): Pick<Sticker, 'quality_score' | 'quality_reasons' | 'quality_signals'> | null {
  if (spec.at >= GATE_SHIPPED_HOURS_AGO) return null
  const penalties: Record<string, number> = { ...spec.penalties }
  if (!spec.penalties && i % 5 === 2) penalties.soft = 4 + (i % 9) // a few mildly soft ones for variety
  if (!spec.penalties && i % 7 === 3) penalties.small_subject = 3 + (i % 6)
  const local = Math.max(0, Math.min(100, Math.round(100 - Object.values(penalties).reduce((a, b) => a + b, 0))))
  const reasons = Object.keys(penalties).sort((a, b) => penalties[b] - penalties[a])
  return {
    quality_score: spec.score ?? local,
    quality_reasons: [...reasons, ...(spec.judged ?? []).map((issue) => `judge:${issue}`)],
    quality_signals: gateSignals(i, spec, penalties),
  }
}

interface ExtraSource {
  desc: string
  site: string
  status: SourceStatus
  at: number
  emoji: string
  cat?: string
  kind?: string
  score?: number
  tags?: string[]
  emojis?: string[]
  topic?: string
  license?: string
  nsfw?: Nsfw
  real?: boolean
  video?: boolean
  error?: string
  text?: string
}

const EXTRA_SOURCES: ExtraSource[] = [
  { desc: 'Uncle dancing at a sangeet', site: 'reddit', status: 'new', at: 0.96, emoji: '🕺', video: true, topic: 'wedding season' },
  { desc: 'Cat asleep on a laptop keyboard', site: 'pinterest', status: 'new', at: 0.94, emoji: '😺' },
  { desc: 'Two-panel template with Hindi text', site: 'imgflip', status: 'new', at: 0.92, emoji: '🙅', text: 'Padhai / Reels' },
  { desc: 'Cricket commentary meme', site: 'reddit', status: 'new', at: 0.9, emoji: '🏏', topic: 'India vs Australia T20' },
  { desc: 'Street food vendor flipping a dosa', site: 'openverse', status: 'new', at: 0.88, emoji: '🥞', license: 'CC BY-SA 4.0' },
  { desc: 'Owner upload: rickshaw selfie', site: 'upload', status: 'new', at: 0.86, emoji: '🛺' },
  { desc: 'Auto-rickshaw driver giving a thumbs up', site: 'reddit', status: 'screened', at: 0.82, emoji: '👍' },
  { desc: 'Grandma squinting at a smartphone', site: 'pinterest', status: 'screened', at: 0.8, emoji: '👵' },
  { desc: 'Gym bro flexing in a mirror', site: 'imgflip', status: 'screened', at: 0.78, emoji: '💪', text: 'Leg day? Kal' },
  { desc: 'Traffic jam in Bengaluru at dusk', site: 'reddit', status: 'analyzing', at: 0.76, emoji: '🚗' },
  { desc: 'Puppy wrapped in a blanket', site: 'pinterest', status: 'analyzed', at: 0.72, emoji: '🐶', cat: 'animals', kind: 'animal', score: 9, tags: ['puppy', 'cozy', 'winter'], emojis: ['🐶', '🥺'] },
  { desc: 'Cricket fan crying in the stands', site: 'reddit', status: 'analyzed', at: 0.7, emoji: '😭', cat: 'trending', kind: 'reaction', score: 8, tags: ['cricket', 'fan', 'heartbreak'], emojis: ['😭', '🏏'], real: true, topic: 'India vs Australia T20' },
  { desc: 'Diyas floating on a river at dusk', site: 'openverse', status: 'analyzed', at: 0.68, emoji: '🪔', cat: 'festivals', kind: 'scene', score: 7, tags: ['diya', 'river', 'diwali'], emojis: ['🪔'], license: 'CC BY 2.0' },
  { desc: 'Mirror selfie with an awkward caption', site: 'imgflip', status: 'analyzed', at: 0.62, emoji: '🤳', cat: 'memes', kind: 'text_post', score: 5, tags: ['selfie', 'awkward'], emojis: ['🤳'], text: 'Outfit check' },
  { desc: 'Panorama of a hill station', site: 'reddit', status: 'failed', at: 30, emoji: '🏔️', error: 'vision: groq 429 rate limited (5 attempts)' },
  { desc: 'Blurry crowd photo', site: 'pinterest', status: 'rejected', at: 40, emoji: '👥', score: 2, cat: 'memes', kind: 'other', tags: ['blurry'] },
  { desc: 'Image flagged sexually explicit by the vision model', site: 'reddit', status: 'rejected', at: 0.5, emoji: '🚫', score: 0, nsfw: 'explicit', cat: 'adult', kind: 'other', tags: ['explicit'] },
]

function hex(rand: () => number, n: number): string {
  let s = ''
  for (let i = 0; i < n; i++) s += Math.floor(rand() * 16).toString(16)
  return s
}

function uuid(rand: () => number): string {
  const h = hex(rand, 32)
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-4${h.slice(13, 16)}-${'89ab'[Math.floor(rand() * 4)]}${h.slice(17, 20)}-${h.slice(20, 32)}`
}

export function seedDemoDb(opts: SeedOptions = {}): DemoDb {
  const now = opts.now ?? new Date()
  const rand = mulberry32(opts.seed ?? 20261105)
  const db = emptyDemoDb(opts.latencyMs ?? 0)
  const dayStart = istDayStart(now).getTime()
  const elapsed = Math.max(60_000, now.getTime() - dayStart)
  const when = (at: number) => new Date(at < 1 ? dayStart + elapsed * at : now.getTime() - at * 3_600_000)
  const iso = (d: Date) => d.toISOString()
  const plus = (d: Date, minutes: number) => new Date(d.getTime() + minutes * 60_000)
  const pick = <T,>(xs: readonly T[]) => xs[Math.floor(rand() * xs.length)]

  db.admins = [{ user_id: '5f0c6a1e-2d1b-4c55-9a51-0d6f2a7f9e01', email: DEMO_ADMIN_EMAIL }]
  db.settings = {
    publish_policy: { value: structuredClone(DEFAULT_SETTINGS.publish_policy), updated_at: iso(when(72)) },
    budgets: { value: structuredClone(DEFAULT_SETTINGS.budgets), updated_at: iso(when(72)) },
    categories: { value: structuredClone(DEFAULT_SETTINGS.categories), updated_at: iso(when(72)) },
    quality_gate: { value: structuredClone(DEFAULT_SETTINGS.quality_gate), updated_at: iso(when(30)) },
  }
  db.protectedPeople = [
    ['Amitabh Bachchan', 'Delhi HC 2022 order'], ['Anil Kapoor', 'Delhi HC 2023 order (incl. GIFs, jhakaas)'],
    ['Aishwarya Rai Bachchan', 'Delhi HC 2025 order'], ['Abhishek Bachchan', 'Delhi HC 2025 order'],
    ['Karan Johar', 'Delhi HC 2025 order'], ['Hrithik Roshan', 'Delhi HC 2025 order'],
    ['Jackie Shroff', 'Delhi HC 2024 order (memes partly allowed)'], ['Arijit Singh', 'Bombay HC 2024 order'],
  ].map(([name, note]) => ({ name, note, created_at: iso(when(96)) }))

  // ---------------------------------------------------------------- sources
  const makeSource = (o: {
    desc: string
    site: string
    status: SourceStatus
    collected: Date
    emoji: string
    hue: number
    cat: string | null
    kind: string | null
    score: number | null
    tags: string[]
    emojis: string[]
    text: string | null
    topic?: string | null
    license?: string | null
    nsfw?: Nsfw | null
    minors?: boolean | null
    real?: boolean | null
    figure?: string | null
    video?: boolean
    error?: string | null
  }): SourceImage => {
    const sha = hex(rand, 64)
    const [w, h] = o.video ? [720, 1280] : pick(SIZES)
    const mime = o.video ? 'video/mp4' : o.site === 'imgflip' ? 'image/jpeg' : pick(['image/jpeg', 'image/webp', 'image/png'])
    const ext = mime === 'video/mp4' ? 'mp4' : mime === 'image/jpeg' ? 'jpg' : mime === 'image/webp' ? 'webp' : 'png'
    const path = sourceObjectPath(sha, ext, o.collected)
    const ref = hex(rand, 7)
    const analyzed = ['analyzed', 'used', 'rejected', 'taken_down'].includes(o.status) && o.score !== null
    const screened = analyzed || o.status === 'screened' || o.status === 'analyzing'
    const author =
      o.site === 'reddit' ? pick(['u/chai_sutta_42', 'u/desi_memer', 'u/bombay_boi', 'u/kolkata_rasgulla'])
      : o.site === 'pinterest' ? pick(['Desi Memes Hub', 'Bollywood Daily', 'Cute Pets India'])
      : o.site === 'imgflip' ? pick(['imgflip user rk_07', 'imgflip user memewala'])
      : o.site === 'openverse' ? pick(['Ravi K (Flickr)', 'Meera S (Wikimedia)'])
      : null
    const urls: Record<string, [string, string]> = {
      reddit: [`https://i.redd.it/demo${ref}.jpg`, `https://www.reddit.com/r/IndianDankMemes/comments/demo${ref}/`],
      pinterest: [`https://i.pinimg.com/736x/demo/${ref}.jpg`, `https://in.pinterest.com/pin/demo${ref}/`],
      imgflip: [`https://i.imgflip.com/demo${ref}.jpg`, `https://imgflip.com/i/demo${ref}`],
      openverse: [`https://live.staticflickr.com/demo/${ref}.jpg`, `https://openverse.org/image/demo-${ref}`],
    }
    const [sourceUrl, pageUrl] = urls[o.site] ?? [null, null]
    const phash = screened ? hex(rand, 16) : null
    const prescreenScore = screened ? Math.round((0.35 + rand() * 0.6) * 100) / 100 : null
    const safety = { nsfw: o.nsfw ?? 'none', minors: o.minors ?? false, real_person: o.real ?? false, public_figure: o.figure ?? null }
    const analysis: Json = analyzed
      ? {
          model: 'groq/vision (demo)',
          kind: o.kind,
          category: o.cat,
          description: o.desc,
          has_text: !!o.text,
          ocr_text: o.text,
          language: o.text ? 'hinglish' : null,
          mood: o.tags.slice(0, 2),
          tags: o.tags,
          emojis: o.emojis,
          safety,
          sticker_score: o.score,
          suggested_recipes: o.text ? ['direct', 'cutout'] : ['caption', 'cutout'],
          caption_ideas: o.text ? [] : ['Kya baat hai', 'Bas yahi baaki tha'],
        }
      : null
    const collectedIso = iso(o.collected)
    const src: SourceImage = {
      id: uuid(rand),
      sha256: sha,
      phash,
      storage_path: path,
      mime,
      width: w,
      height: h,
      bytes: Math.round(60_000 + rand() * 2_400_000),
      frames: 1,
      duration_ms: o.video ? 6400 : 0,
      site: o.site,
      source_url: o.site === 'upload' ? null : sourceUrl,
      page_url: o.site === 'upload' ? null : pageUrl,
      author: o.site === 'upload' ? null : author,
      license: o.license ?? (o.site === 'upload' ? 'owner' : 'unknown'),
      collector: o.site === 'upload' ? 'admin' : pick(['handnavigate', 'handnavigate', 'worker-reddit']),
      topic_hint: o.topic ?? null,
      collected_at: collectedIso,
      status: o.status,
      attempts: o.status === 'failed' ? 5 : analyzed ? 1 : 0,
      locked_until: o.status === 'analyzing' ? iso(plus(now, 10)) : null,
      last_error: o.error ?? null,
      prescreen: screened ? { blur: Math.round(rand() * 30) / 100, faces: o.real ? 1 : 0, text_area: o.text ? 0.24 : 0.02, phash, score: prescreenScore } : null,
      prescreen_score: prescreenScore,
      ocr_text: analyzed ? o.text : null,
      analysis,
      kind: analyzed ? o.kind : null,
      has_text: analyzed ? !!o.text : null,
      language: analyzed && o.text ? 'hinglish' : null,
      category: analyzed ? o.cat : null,
      mood: analyzed ? o.tags.slice(0, 2) : [],
      tags: analyzed ? o.tags : [],
      emojis: analyzed ? o.emojis : [],
      description: analyzed ? o.desc : null,
      nsfw: analyzed ? safety.nsfw : null,
      minors: analyzed ? safety.minors : null,
      real_person: analyzed ? safety.real_person : null,
      public_figure: analyzed ? safety.public_figure : null,
      sticker_score: analyzed ? o.score : null,
      analyzed_at: analyzed ? iso(plus(o.collected, 14)) : null,
      created_at: collectedIso,
      updated_at: iso(plus(o.collected, 20)),
    }
    db.objects.set(
      `sources/${path}`,
      sourceArt({ seed: Math.floor(rand() * 1e9), width: Math.round(w / 2), height: Math.round(h / 2), hue: o.hue, emoji: o.emoji, top: o.text, video: o.video }),
    )
    db.sources.push(src)
    return src
  }

  // ---------------------------------------------------------------- stickers (+ their sources)
  const stickers: Sticker[] = STICKERS.map((spec, i) => {
    const created = when(spec.at)
    const hue = HUE[spec.cat] ?? 90
    const emoji = spec.emojis[0] ?? '✨'
    let source: SourceImage | null = null
    if (spec.recipe !== 'original') {
      source = makeSource({
        desc: spec.scene,
        site: SITES[i % SITES.length],
        status: spec.status === 'taken_down' ? 'taken_down' : 'used',
        collected: plus(created, -(spec.at < 1 ? 25 : 180)),
        emoji,
        hue,
        cat: spec.cat,
        kind: spec.minors ? 'baby' : (KIND_FOR_CATEGORY[spec.cat] ?? 'other'),
        score: spec.q,
        tags: spec.tags,
        emojis: spec.emojis,
        text: spec.recipe === 'direct' ? spec.caption : null,
        nsfw: spec.nsfw ?? 'none',
        minors: spec.minors ?? false,
        real: spec.real ?? false,
        figure: spec.figure ?? null,
      })
    }
    const sha = hex(rand, 64)
    const filePath = sourceObjectPath(sha, 'webp', created)
    db.objects.set(`stickers/${filePath}`, stickerArt({ seed: 1000 + i, caption: spec.caption, emoji, hue, animated: spec.kind === 'animated', flaw: spec.flaw }))
    const wasPublished = spec.status === 'published' || spec.status === 'taken_down'
    const publishedAt = wasPublished ? iso(new Date(Math.min(plus(created, 40).getTime(), now.getTime()))) : null
    const gate = gateColumns(i, spec)
    // The worker's review_note for a hold, e.g. quality_gate:55<60:blurry
    const heldNote = gate && spec.status === 'held' ? `quality_gate:${gate.quality_score}<${GATE_MIN_SCORE}:${gate.quality_reasons.join('+')}` : null
    return {
      id: uuid(rand),
      source_id: source?.id ?? null,
      recipe: spec.recipe,
      kind: spec.kind,
      rating: spec.rating,
      category: spec.cat,
      caption: spec.caption,
      language: 'hinglish',
      tags: spec.tags,
      emojis: spec.emojis,
      description: spec.scene || `Original ${spec.cat} sticker`,
      file_path: filePath,
      sha256: sha,
      phash: hex(rand, 16),
      bytes: Math.round(spec.kb * 1024 * (0.96 + rand() * 0.03)),
      ai_generated: spec.recipe !== 'direct',
      credit: source ? { site: source.site, url: source.page_url, author: source.author, license: source.license } : null,
      quality: spec.q,
      trend_id: null,
      status: spec.status,
      review_note: spec.note ?? heldNote,
      published_at: publishedAt,
      created_at: iso(created),
      updated_at: iso(plus(created, 41)),
      quality_score: gate?.quality_score ?? null,
      quality_reasons: gate?.quality_reasons ?? [],
      quality_signals: gate?.quality_signals ?? null,
      render_phash: gate ? hex(rand, 16) : null,
    }
  })
  // near_duplicate: point at the sticker it repeats, with a render pHash 1 bit away from it.
  STICKERS.forEach((spec, i) => {
    if (!spec.dupOf) return
    const original = stickers.find((s) => s.caption === spec.dupOf)
    const copy = stickers[i]
    if (!original?.render_phash || !copy.quality_signals || typeof copy.quality_signals !== 'object' || Array.isArray(copy.quality_signals)) {
      throw new Error(`fixture: near-duplicate of "${spec.dupOf}" needs a scored original`)
    }
    const last = (parseInt(original.render_phash.slice(-1), 16) ^ 1).toString(16)
    copy.render_phash = original.render_phash.slice(0, -1) + last
    Object.assign(copy.quality_signals, { duplicate_of: original.id, duplicate_distance: 1 })
  })
  db.stickers = stickers

  for (const e of EXTRA_SOURCES) {
    makeSource({
      desc: e.desc,
      site: e.site,
      status: e.status,
      collected: when(e.at),
      emoji: e.emoji,
      hue: HUE[e.cat ?? 'memes'] ?? 90,
      cat: e.cat ?? null,
      kind: e.kind ?? null,
      score: e.score ?? null,
      tags: e.tags ?? [],
      emojis: e.emojis ?? [],
      text: e.text ?? null,
      topic: e.topic,
      license: e.license,
      nsfw: e.nsfw,
      real: e.real,
      video: e.video,
      error: e.error,
    })
  }

  // ---------------------------------------------------------------- packs
  const byCaption = (c: string) => {
    const s = stickers.find((x) => x.caption === c)
    if (!s) throw new Error(`fixture: no sticker "${c}"`)
    return s
  }
  const packSpecs: {
    title: string
    type: PackType
    kind: StickerKind
    rating: Rating
    status: PackStatus
    series?: string
    category: string
    desc: string
    items: string[]
    at: number
    version: number
  }[] = [
    { title: 'Office Pain', type: 'series', series: 'office-pain', kind: S, rating: G, status: 'published', category: 'office', desc: 'Meetings, Mondays and HR — corporate life in one pack.', items: ['Meeting jo email ho sakti thi', 'Monday ko dekh ke', 'WFH dress code', 'HR ko mat batana', 'Weekend kab aayega', 'Kal se pakka'], at: 50, version: 9 },
    { title: 'Chai & Chill', type: 'situation', kind: S, rating: G, status: 'published', category: 'reactions', desc: 'Calm replies for chaotic chats.', items: ['Chai peelo, sab theek ho jayega', 'Sab moh maya hai', 'Ye toh hona hi tha', 'Billi mausi approves', 'Baby ki smile'], at: 58, version: 6 },
    { title: 'Diwali Dhamaka 2026', type: 'trend', kind: A, rating: G, status: 'draft', category: 'festivals', desc: 'Animated wishes for the festive week.', items: ['Happy Diwali', 'Shubh Deepavali', 'Patakhe nahi, pyaar jalao'], at: 0.4, version: 3 },
    { title: `Daily Dose · ${new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', timeZone: 'Asia/Kolkata' }).format(now)}`, type: 'daily', kind: S, rating: G, status: 'draft', category: 'hinglish', desc: "Today's freshest reactions.", items: ['Kya scene hai?', 'Bas kar pagle, rulayega kya', 'Bhai tu rehne de', 'Thoda adjust kar lo', 'Light le yaar'], at: 0.85, version: 5 },
    { title: 'Savage After Dark', type: 'series', series: 'savage-after-dark', kind: S, rating: X, status: 'published', category: 'adult', desc: '18+ humour. Double meaning, zero nudity.', items: ['Savage mode on', 'Double meaning mat samjho', 'Raat ko call mat karna', 'Ex ka message aaya'], at: 74, version: 4 },
    { title: 'Filmy Moments', type: 'manual', kind: S, rating: G, status: 'draft', category: 'movies', desc: 'Dialogues for every situation.', items: ['Picture abhi baaki hai mere dost', 'Paisa hi paisa hoga', 'Hero entry'], at: 0.3, version: 3 },
    { title: 'Month End Mood', type: 'situation', kind: A, rating: G, status: 'published', category: 'office', desc: 'When the salary leaves before you do.', items: ['Salary aayi, salary gayi', 'Arre yaar!', 'Mummy ki chappal incoming', 'Naach meri jaan'], at: 27, version: 7 },
    { title: 'Monday Blues (2025)', type: 'manual', kind: S, rating: G, status: 'archived', category: 'office', desc: 'Retired pack kept for history.', items: ['Monday ko dekh ke', 'Aukaat mein reh', 'Tum toh dhokebaaz nikle'], at: 400, version: 12 },
  ]
  for (const p of packSpecs) {
    const created = when(p.at)
    const items = p.items.map(byCaption)
    const pack: Pack = {
      id: uuid(rand),
      slug: `${p.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')}-${hex(rand, 4)}`,
      title: p.title,
      description: p.desc,
      kind: p.kind,
      rating: p.rating,
      category: p.category,
      pack_type: p.type,
      series: p.series ?? null,
      volume: 1,
      cover_sticker_id: items[0].id,
      version: p.version,
      sticker_count: 0,
      status: p.status,
      published_at: p.status === 'published' || p.status === 'archived' ? iso(plus(created, 30)) : null,
      created_at: iso(created),
      updated_at: iso(plus(created, p.at < 1 ? 5 : 90)),
    }
    db.packs.push(pack)
    items.forEach((s, position) => db.packItems.push({ pack_id: pack.id, sticker_id: s.id, position, added_at: iso(created) }))
    touchPackCounts(db, pack.id, 0)
  }

  // ---------------------------------------------------------------- reports & takedowns
  const report = (target: { type: 'sticker' | 'pack'; id: string }, reason: ReportReason, details: string, hoursAgo: number, status: Report['status'] = 'open', resolvedHoursAgo?: number): Report => ({
    id: uuid(rand),
    target_type: target.type,
    target_id: target.id,
    reason,
    details,
    status,
    created_at: iso(new Date(now.getTime() - hoursAgo * 3_600_000)),
    resolved_at: resolvedHoursAgo === undefined ? null : iso(new Date(now.getTime() - resolvedHoursAgo * 3_600_000)),
  })
  const st = (caption: string) => ({ type: 'sticker' as const, id: byCaption(caption).id })
  const savage = db.packs.find((p) => p.title === 'Savage After Dark')!
  db.reports = [
    report(st('Picture abhi baaki hai mere dost'), 'copyright', 'Dialogue frame from our film. Please remove it.', 0.6),
    report(st('Tum toh dhokebaaz nikle'), 'person', 'This is me and my wife, posted without permission', 3.2),
    report(st('Tum toh dhokebaaz nikle'), 'person', "Please remove, it's my friend's photo", 0.8),
    report({ type: 'pack', id: savage.id }, 'sexual', 'Too vulgar for a public app', 9),
    report(st('Aukaat mein reh'), 'hate', 'Insulting caption', 27),
    report(st('Baby ki smile'), 'minor', 'Child photo, the parents may not have agreed', 5),
    report(st('Bade log, badi baatein'), 'copyright', 'Creator says this panel is from a paid comic', 210, 'actioned', 205),
    report(st('Kal se pakka'), 'other', 'not funny', 100, 'dismissed', 99),
  ]
  const downed = byCaption('Bade log, badi baatein')
  const downedSource = db.sources.find((s) => s.id === downed.source_id)!
  const actioned = db.reports.find((r) => r.status === 'actioned')!
  db.takedowns = [
    { id: uuid(rand), sha256: downed.sha256, phash: downed.phash, source_url: null, reason: downed.review_note ?? 'copyright', report_id: actioned.id, created_at: actioned.resolved_at! },
    { id: uuid(rand), sha256: downedSource.sha256, phash: null, source_url: downedSource.source_url, reason: downed.review_note ?? 'copyright', report_id: actioned.id, created_at: actioned.resolved_at! },
  ]

  // ---------------------------------------------------------------- trends
  const today = istDate(now)
  const yesterday = addDays(today, -1)
  const trend = (day: string, title: string, source: string, category: string, keywords: string[], score: number, summary: string, active = true): Trend => ({
    id: uuid(rand),
    day,
    title,
    summary,
    keywords,
    category,
    source,
    source_urls: source === 'google_trends_in' ? ['https://trends.google.com/trending?geo=IN'] : source === 'pib' ? ['https://pib.gov.in/'] : [],
    score,
    active,
    created_at: iso(day === today ? when(0.05) : when(30)),
  })
  db.trends = [
    trend(today, 'Diwali bonus kab aayega?', 'owner', 'office', ['diwali', 'bonus', 'salary'], 100, 'Office humour about late Diwali bonuses'),
    trend(today, 'India vs Australia T20', 'google_trends_in', 'trending', ['cricket', 't20', 'india'], 92, 'Evening match; last-over drama memes'),
    trend(today, 'Delhi AQI crosses 400', 'google_trends_in', 'trending', ['aqi', 'delhi', 'smog'], 75, 'Pollution jokes, masks, "breathe responsibly"'),
    trend(today, 'Bigg Boss weekend ka vaar', 'google_trends_in', 'trending', ['bigg boss', 'tv'], 66, 'Reality-show reactions'),
    trend(today, 'Chhath Puja', 'pib', 'festivals', ['chhath', 'festival'], 58, 'Festival greetings'),
    trend(today, 'Bank holidays in November', 'pib', 'trending', ['holiday', 'bank'], 21, 'Low sticker potential', false),
    trend(yesterday, 'Wedding season traffic', 'owner', 'relationship', ['shaadi', 'traffic', 'baraat'], 100, 'Baraat blocking the road again'),
    trend(yesterday, 'Monsoon finally over', 'google_trends_in', 'trending', ['weather'], 40, 'Weather memes'),
  ]

  // ---------------------------------------------------------------- AI usage & worker runs
  let callId = 1
  const calls = (purpose: string, n: number, failed: number, today = true) => {
    for (let k = 0; k < n; k++) {
      db.aiCalls.push({ id: callId++, at: iso(today ? when(0.02 + rand() * 0.96) : when(26 + rand() * 20)), purpose, ok: k >= failed })
    }
  }
  calls('analyze', 37, 2)
  calls('caption', 120, 1)
  calls('judge', 60, 0)
  calls('pack_title', 18, 0)
  calls('trend', 14, 3)
  calls('analyze', 58, 1, false)
  calls('caption', 300, 4, false)

  const run = (stage: string, minutesAgo: number, durS: number | null, ok: boolean | null, summary: Json, id: number): WorkerRun => {
    const started = new Date(now.getTime() - minutesAgo * 60_000)
    return { id, stage, started_at: iso(started), finished_at: durS === null ? null : iso(new Date(started.getTime() + durS * 1000)), ok, summary }
  }
  db.workerRuns = [
    run('analyze', 4, null, null, { claimed: 12 }, 210),
    run('collect', 12, 48, true, { adapter: 'reddit', fetched: 120, new: 34, duplicates: 86 }, 209),
    run('prescreen', 22, 31, true, { screened: 34, passed: 21, rejected: 13 }, 208),
    run('analyze', 45, 130, false, { analyzed: 7, error: 'groq 429: rate limited', retry_in_s: 60 }, 207),
    run('make', 70, 184, true, { stickers: 12, recipes: { caption: 7, cutout: 3, motion: 2 } }, 206),
    run('judge', 80, 55, true, { judged: 12, kept: 9, rejected: 3 }, 205),
    run('pack', 90, 12, true, { packs_updated: 2, daily: 'Daily Dose' }, 204),
    run('publish', 95, 4, true, { published: 7, held_for_review: 2 }, 203),
    run('trends', 360, 20, true, { trends: 5, sources: ['google_trends_in', 'pib'] }, 202),
    run('collect', 420, 65, true, { adapter: 'pinterest', fetched: 80, new: 11, duplicates: 69 }, 201),
  ]

  return db
}
