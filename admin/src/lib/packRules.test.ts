import { describe, expect, it } from 'vitest'
import { PACK_MAX, checkPack, hasErrors, type PackDraft } from './packRules'
import type { StickerLike } from './stickerRules'

let n = 0
function st(over: Partial<StickerLike> = {}): StickerLike {
  n += 1
  return { id: `s${n}`, kind: 'static', rating: 'general', status: 'published', bytes: 40_000, emojis: ['😂'], ...over }
}
function pack(stickers: StickerLike[], over: Partial<PackDraft> = {}): PackDraft {
  return { title: 'Office Pain', kind: 'static', rating: 'general', cover_sticker_id: stickers[0]?.id ?? null, stickers, ...over }
}
const codes = (p: PackDraft, forPublish = true) => checkPack(p, { forPublish }).map((i) => `${i.level}:${i.code}`)

describe('checkPack', () => {
  it('passes a clean 3-sticker static/general pack', () => {
    const issues = checkPack(pack([st(), st(), st()]), { forPublish: true })
    expect(issues).toEqual([])
    expect(hasErrors(issues)).toBe(false)
  })

  it('needs at least 3 stickers to publish, but lets drafts be saved smaller', () => {
    expect(codes(pack([st(), st()]))).toContain('error:too_few')
    expect(codes(pack([st(), st()]), false)).toContain('warning:too_few')
  })

  it(`caps packs at ${PACK_MAX} stickers even for drafts`, () => {
    const many = Array.from({ length: PACK_MAX + 1 }, () => st())
    expect(codes(pack(many), false)).toContain('error:too_many')
    expect(codes(pack(many.slice(0, PACK_MAX)))).not.toContain('error:too_many')
  })

  it('rejects static/animated mixing and flags the odd ones out', () => {
    const odd = st({ kind: 'animated', bytes: 300_000 })
    const issues = checkPack(pack([st(), st(), odd]), { forPublish: false })
    const mixed = issues.find((i) => i.code === 'mixed_kind')
    expect(mixed?.level).toBe('error')
    expect(mixed?.stickerIds).toEqual([odd.id])
  })

  it('rejects a pack whose kind disagrees with all of its stickers', () => {
    const anim = () => st({ kind: 'animated', bytes: 300_000 })
    expect(codes(pack([anim(), anim(), anim()]))).toContain('error:kind_mismatch')
    expect(codes(pack([anim(), anim(), anim()], { kind: 'animated' }))).toEqual([])
  })

  it('rejects adult/general mixing and rating mismatches', () => {
    expect(codes(pack([st(), st(), st({ rating: 'adult' })]))).toContain('error:mixed_rating')
    const adult = () => st({ rating: 'adult' })
    expect(codes(pack([adult(), adult(), adult()]))).toContain('error:rating_mismatch')
    expect(codes(pack([adult(), adult(), adult()], { rating: 'adult' }))).toEqual([])
  })

  it('blocks publishing while stickers are drafts; only warns when saving', () => {
    const p = pack([st(), st(), st({ status: 'draft' })])
    expect(codes(p)).toContain('error:drafts')
    expect(codes(p, false)).toContain('warning:drafts')
  })

  it('always errors on rejected or taken-down stickers', () => {
    expect(codes(pack([st(), st(), st({ status: 'taken_down' })]), false)).toContain('error:dead')
  })

  it('surfaces per-sticker WhatsApp rules (emoji count, size) grouped by rule', () => {
    const issues = checkPack(pack([st({ emojis: [] }), st({ emojis: [] }), st({ bytes: 150_000 })]), { forPublish: true })
    const emoji = issues.find((i) => i.code === 'sticker_no_emoji')
    expect(emoji?.message).toMatch(/^2 stickers:/)
    expect(emoji?.stickerIds).toHaveLength(2)
    expect(issues.find((i) => i.code === 'sticker_size')?.level).toBe('error')
  })

  it('blocks protected people when publishing', () => {
    const p = pack([st(), st(), st({ source: { nsfw: 'none', minors: false, real_person: true, public_figure: 'Anil Kapoor' } })])
    expect(checkPack(p, { forPublish: true, protectedNames: ['Anil Kapoor'] }).map((i) => i.code)).toContain('sticker_protected')
  })

  it('requires a title and warns about the cover', () => {
    expect(codes(pack([st(), st(), st()], { title: '  ' }))).toContain('error:title')
    expect(codes(pack([st(), st(), st()], { cover_sticker_id: null }))).toContain('warning:no_cover')
    expect(codes(pack([st(), st(), st()], { cover_sticker_id: 'gone' }))).toContain('warning:cover_gone')
  })

  it('rejects duplicate stickers', () => {
    const a = st()
    expect(codes(pack([a, a, st()]), false)).toContain('error:duplicate')
  })
})
