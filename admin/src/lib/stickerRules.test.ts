import { describe, expect, it } from 'vitest'
import { WA_ANIMATED_MAX_BYTES, WA_STATIC_MAX_BYTES, errorsOf, protectedMatch, stickerIssues, waBadge, type StickerLike } from './stickerRules'

const base: StickerLike = { id: 'a', kind: 'static', rating: 'general', status: 'draft', bytes: 50_000, emojis: ['😂'] }
const flags = { nsfw: 'none' as const, minors: false, real_person: false, public_figure: null }
const codes = (s: StickerLike, names: string[] = []) => errorsOf(stickerIssues(s, { protectedNames: names })).map((i) => i.code)

describe('WhatsApp size badge', () => {
  it('uses 100 KB for static and 500 KB for animated (KB = 1024 bytes)', () => {
    expect(waBadge({ kind: 'static', bytes: WA_STATIC_MAX_BYTES }).ok).toBe(true)
    expect(waBadge({ kind: 'static', bytes: WA_STATIC_MAX_BYTES + 1 }).ok).toBe(false)
    expect(waBadge({ kind: 'animated', bytes: WA_ANIMATED_MAX_BYTES }).ok).toBe(true)
    expect(waBadge({ kind: 'animated', bytes: WA_ANIMATED_MAX_BYTES + 1 }).ok).toBe(false)
    expect(waBadge({ kind: 'static', bytes: 84 * 1024 }).label).toBe('84/100 KB')
  })
})

describe('sticker publish rules', () => {
  it('passes a normal sticker', () => {
    expect(codes(base)).toEqual([])
  })

  it('never publishes explicit sources', () => {
    expect(codes({ ...base, source: { ...flags, nsfw: 'explicit' } })).toContain('explicit')
  })

  it('never makes a child 18+', () => {
    expect(codes({ ...base, rating: 'adult', source: { ...flags, minors: true } })).toContain('minor_adult')
    expect(codes({ ...base, rating: 'general', source: { ...flags, minors: true } })).not.toContain('minor_adult')
  })

  it('blocks protected people by public_figure or by name in the caption/tags', () => {
    const names = ['Amitabh Bachchan', 'Anil Kapoor']
    expect(codes({ ...base, source: { ...flags, public_figure: 'anil kapoor' } }, names)).toContain('protected')
    expect(codes({ ...base, caption: 'Amitabh Bachchan style entry' }, names)).toContain('protected')
    expect(protectedMatch({ ...base, tags: ['kapoor'] }, names)).toBeNull()
  })

  it('needs 1–3 emojis', () => {
    expect(codes({ ...base, emojis: [] })).toContain('no_emoji')
    expect(codes({ ...base, emojis: ['😂', '🔥', '🙏', '💀'] })).toContain('emojis')
  })

  it('blocks stickers WhatsApp would reject for size, and taken-down stickers', () => {
    expect(codes({ ...base, bytes: 130 * 1024 })).toContain('size')
    expect(codes({ ...base, kind: 'animated', bytes: 130 * 1024 })).not.toContain('size')
    expect(codes({ ...base, status: 'taken_down' })).toContain('taken_down')
  })

  it('warns (not blocks) on real people in 18+ stickers', () => {
    const issues = stickerIssues({ ...base, rating: 'adult', source: { ...flags, real_person: true } }, { protectedNames: [] })
    expect(issues.map((i) => `${i.level}:${i.code}`)).toEqual(['warning:real_adult'])
  })
})
