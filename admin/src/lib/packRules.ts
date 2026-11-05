// Pack composition rules: 3–30 stickers, one kind (static XOR animated, a WhatsApp rule), one
// rating (general XOR adult, our rule), and for publishing every sticker must itself be publishable.

import type { Rating, StickerKind } from '../api/types'
import { stickerIssues, type Issue, type StickerLike } from './stickerRules'

export const PACK_MIN = 3
export const PACK_MAX = 30

export interface PackDraft {
  title: string
  kind: StickerKind
  rating: Rating
  cover_sticker_id: string | null
  stickers: StickerLike[]
}

const s = (n: number, word = 'sticker') => `${n} ${word}${n === 1 ? '' : 's'}`

export function checkPack(p: PackDraft, opts: { forPublish: boolean; protectedNames?: readonly string[] }): Issue[] {
  const out: Issue[] = []
  const error = (code: string, message: string, stickerIds?: string[]) => out.push({ level: 'error', code, message, stickerIds })
  const warning = (code: string, message: string, stickerIds?: string[]) => out.push({ level: 'warning', code, message, stickerIds })
  const n = p.stickers.length
  const ids = p.stickers.map((x) => x.id)

  const title = p.title.trim()
  if (!title) error('title', 'Give the pack a title')
  else if (title.length > 128) error('title', 'Title is longer than 128 characters')

  if (n > PACK_MAX) error('too_many', `${s(n)}: a WhatsApp pack holds at most ${PACK_MAX}`)
  if (n < PACK_MIN) {
    const msg = `${s(n)}: a pack needs at least ${PACK_MIN} to publish`
    if (opts.forPublish) error('too_few', msg)
    else warning('too_few', msg)
  }

  const dupes = ids.filter((id, i) => ids.indexOf(id) !== i)
  if (dupes.length) error('duplicate', 'The same sticker appears twice', dupes)

  const statics = p.stickers.filter((x) => x.kind === 'static')
  const animated = p.stickers.filter((x) => x.kind === 'animated')
  if (statics.length && animated.length) {
    const odd = p.kind === 'static' ? animated : statics
    error(
      'mixed_kind',
      `Mixes ${statics.length} static and ${animated.length} animated stickers. WhatsApp needs one kind per pack`,
      odd.map((x) => x.id),
    )
  } else if (n && (statics.length ? 'static' : 'animated') !== p.kind) {
    error('kind_mismatch', `This is a ${p.kind} pack but its stickers are ${statics.length ? 'static' : 'animated'}`, ids)
  }

  const adult = p.stickers.filter((x) => x.rating === 'adult')
  const general = p.stickers.filter((x) => x.rating === 'general')
  if (adult.length && general.length) {
    const odd = p.rating === 'adult' ? general : adult
    error('mixed_rating', `Mixes ${adult.length} 18+ and ${general.length} general stickers. Keep 18+ in its own pack`, odd.map((x) => x.id))
  } else if (n && (adult.length ? 'adult' : 'general') !== p.rating) {
    error('rating_mismatch', `This is ${p.rating === 'adult' ? 'an 18+' : 'a general'} pack but its stickers are ${adult.length ? '18+' : 'general'}`, ids)
  }

  const dead = p.stickers.filter((x) => x.status === 'rejected' || x.status === 'taken_down')
  if (dead.length) error('dead', `${s(dead.length)} rejected or taken down. Remove them`, dead.map((x) => x.id))

  const drafts = p.stickers.filter((x) => x.status === 'draft')
  if (drafts.length) {
    const msg = `${s(drafts.length)} still draft. Phones only show published stickers`
    if (opts.forPublish) error('drafts', msg, drafts.map((x) => x.id))
    else warning('drafts', msg, drafts.map((x) => x.id))
  }

  // The worker never packs held stickers; one can only get here through old data or a manual edit.
  const held = p.stickers.filter((x) => x.status === 'held')
  if (held.length) {
    const msg = `${s(held.length)} held by the quality gate. Approve or remove ${held.length === 1 ? 'it' : 'them'} (Stickers → Held)`
    if (opts.forPublish) error('held', msg, held.map((x) => x.id))
    else warning('held', msg, held.map((x) => x.id))
  }

  // Per-sticker blockers (except taken_down, reported above), grouped by rule.
  const groups = new Map<string, { message: string; ids: string[] }>()
  for (const st of p.stickers) {
    for (const issue of stickerIssues(st, { protectedNames: opts.protectedNames ?? [] })) {
      if (issue.level !== 'error' || issue.code === 'taken_down') continue
      const g = groups.get(issue.code) ?? { message: issue.message, ids: [] }
      g.ids.push(st.id)
      groups.set(issue.code, g)
    }
  }
  for (const [code, g] of groups) {
    const msg = g.ids.length === 1 ? `1 sticker: ${g.message}` : `${g.ids.length} stickers: ${g.message}`
    if (opts.forPublish) error(`sticker_${code}`, msg, g.ids)
    else warning(`sticker_${code}`, msg, g.ids)
  }

  if (n && !p.cover_sticker_id) warning('no_cover', 'No cover chosen: the first sticker will be used')
  else if (p.cover_sticker_id && n && !ids.includes(p.cover_sticker_id)) warning('cover_gone', 'The cover sticker is no longer in this pack')

  return out
}

export function hasErrors(issues: readonly Issue[]): boolean {
  return issues.some((i) => i.level === 'error')
}
