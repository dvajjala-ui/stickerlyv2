import { describe, expect, it } from 'vitest'
import { MAX_EMOJIS, addEmojis, checkEmojis, extractEmojis, graphemes, isEmoji } from './emoji'

describe('emoji parsing', () => {
  it('keeps multi-code-point emojis as single graphemes', () => {
    expect(graphemes('👨‍👩‍👧')).toHaveLength(1)
    expect(graphemes('👍🏽')).toHaveLength(1)
    expect(graphemes('🇮🇳')).toHaveLength(1)
    expect(graphemes('😮‍💨')).toHaveLength(1)
  })

  it('extracts emojis from mixed text, de-duplicated and in order', () => {
    expect(extractEmojis('😂 lol 👍🏽, 🇮🇳 ❤️ 1️⃣ 😂')).toEqual(['😂', '👍🏽', '🇮🇳', '❤️', '1️⃣'])
    expect(extractEmojis('no emoji here 123')).toEqual([])
  })

  it('classifies graphemes', () => {
    for (const e of ['🪔', '☕', '🫡', '🇮🇳', '#️⃣', '👨‍👩‍👧']) expect(isEmoji(e)).toBe(true)
    for (const t of ['a', '1', '#', ' ', 'क']) expect(isEmoji(t)).toBe(false)
  })
})

describe(`emoji limit (max ${MAX_EMOJIS})`, () => {
  it('accepts 0–3 distinct emojis', () => {
    expect(checkEmojis([]).ok).toBe(true)
    expect(checkEmojis(['😂', '🔥', '🙏']).ok).toBe(true)
  })

  it('rejects a 4th emoji, duplicates and non-emojis', () => {
    expect(checkEmojis(['😂', '🔥', '🙏', '💀'])).toEqual({ ok: false, error: 'Max 3 emojis per sticker (WhatsApp limit)' })
    expect(checkEmojis(['😂', '😂']).ok).toBe(false)
    expect(checkEmojis(['a']).ok).toBe(false)
    expect(checkEmojis(['😂🔥']).ok).toBe(false) // two emojis in one slot
  })

  it('addEmojis never exceeds the limit and reports what it dropped', () => {
    expect(addEmojis(['😂'], '🔥🙏💀🤌')).toEqual({ list: ['😂', '🔥', '🙏'], dropped: 2, invalid: false })
    expect(addEmojis(['😂'], '😂')).toEqual({ list: ['😂'], dropped: 0, invalid: false })
    expect(addEmojis([], 'hi 🔥').invalid).toBe(true)
  })
})
