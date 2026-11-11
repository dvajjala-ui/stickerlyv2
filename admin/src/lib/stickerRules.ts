// What may be published. Errors block publishing; warnings ask a human to look.
// Hard rules (docs/AGENTS.md): never explicit material, a child is never 18+, protected people
// (court orders) are never published. WhatsApp: 1–3 emojis, static < 100 KB, animated < 500 KB.

import type { Rating, SourceFlags, StickerKind, StickerStatus } from '../api/types'
import { checkEmojis } from './emoji'

/** WhatsApp sample validator limits (KB = 1024 bytes). The DB also caps stickers.bytes at 500,000. */
export const WA_STATIC_MAX_BYTES = 100 * 1024
export const WA_ANIMATED_MAX_BYTES = 500 * 1024

export interface StickerLike {
  id: string
  kind: StickerKind
  rating: Rating
  status: StickerStatus
  bytes: number
  emojis: string[]
  caption?: string | null
  description?: string | null
  tags?: string[]
  source?: Pick<SourceFlags, 'nsfw' | 'minors' | 'real_person' | 'public_figure'> | null
}

export interface Issue {
  level: 'error' | 'warning'
  code: string
  message: string
  stickerIds?: string[]
}

export function waSizeLimit(kind: StickerKind): number {
  return kind === 'animated' ? WA_ANIMATED_MAX_BYTES : WA_STATIC_MAX_BYTES
}

export interface WaBadge {
  ok: boolean
  label: string
  detail: string
}

export function waBadge(s: Pick<StickerLike, 'kind' | 'bytes'>): WaBadge {
  const limitKb = waSizeLimit(s.kind) / 1024
  const kb = Math.ceil(s.bytes / 1024)
  const ok = s.bytes <= waSizeLimit(s.kind)
  return {
    ok,
    label: `${kb}/${limitKb} KB`,
    detail: ok
      ? `Fits WhatsApp's ${limitKb} KB limit for ${s.kind} stickers`
      : `${kb} KB is over WhatsApp's ${limitKb} KB limit for ${s.kind} stickers`,
  }
}

function norm(text: string): string {
  return text.toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/\s+/g, ' ').trim()
}

/** Name of a protected person this sticker depicts or names, if any. */
export function protectedMatch(s: StickerLike, names: readonly string[]): string | null {
  const figure = s.source?.public_figure ? norm(s.source.public_figure) : ''
  const text = norm([s.caption, s.description, ...(s.tags ?? [])].filter(Boolean).join(' '))
  for (const name of names) {
    const n = norm(name)
    if (!n) continue
    if (figure && figure.includes(n)) return name
    if (text.includes(n)) return name
  }
  return null
}

export function stickerIssues(s: StickerLike, ctx: { protectedNames: readonly string[] }): Issue[] {
  const out: Issue[] = []
  const error = (code: string, message: string) => out.push({ level: 'error', code, message, stickerIds: [s.id] })
  const warning = (code: string, message: string) => out.push({ level: 'warning', code, message, stickerIds: [s.id] })

  if (s.status === 'taken_down') error('taken_down', 'Taken down; its fingerprint stays blocked')
  if (s.source?.nsfw === 'explicit') error('explicit', 'Source is flagged sexually explicit; never publishable')
  if (s.rating === 'adult' && s.source?.minors === true) error('minor_adult', 'Shows a child; can never be 18+')
  const who = protectedMatch(s, ctx.protectedNames)
  if (who) error('protected', `Depicts or names ${who}, a protected person (court order)`)
  const emoji = checkEmojis(s.emojis)
  if (!emoji.ok) error('emojis', emoji.error ?? 'Invalid emojis')
  else if (s.emojis.length === 0) error('no_emoji', 'Needs 1–3 emojis (WhatsApp requires at least one)')
  const wa = waBadge(s)
  if (!wa.ok) error('size', wa.detail)

  if (s.source?.minors === true && s.rating === 'general') warning('child', 'Shows a child: check the caption is wholesome')
  if (s.source?.real_person === true && s.rating === 'adult') warning('real_adult', 'Real person in an 18+ sticker: it must not sexualise them')
  if (s.source?.nsfw === 'suggestive' && s.rating === 'general') warning('suggestive', 'Source looks suggestive: consider the 18+ rating')
  return out
}

export function errorsOf(issues: readonly Issue[]): Issue[] {
  return issues.filter((i) => i.level === 'error')
}

/** W2: a rating change must not leave the sticker inside a pack with a different rating
 * (adult stickers in general packs leak into the default feed; packs never mix ratings). */
export function ratingBreaksPacks(newRating: Rating, packs: readonly { rating: Rating }[]): boolean {
  return packs.some((p) => p.rating !== newRating)
}
