// Emoji parsing for sticker metadata. WhatsApp allows at most 3 emojis per sticker and its
// validator wants at least 1; the database enforces cardinality(emojis) <= 3.

export const MAX_EMOJIS = 3

const segmenter =
  typeof Intl !== 'undefined' && 'Segmenter' in Intl ? new Intl.Segmenter(undefined, { granularity: 'grapheme' }) : null

const PICTOGRAPHIC = /\p{Extended_Pictographic}/u
const FLAG = /^\p{Regional_Indicator}{2}$/u
const KEYCAP = /^[0-9#*]️?⃣$/u

export function graphemes(text: string): string[] {
  if (segmenter) return Array.from(segmenter.segment(text), (s) => s.segment)
  return Array.from(text)
}

export function isEmoji(grapheme: string): boolean {
  return PICTOGRAPHIC.test(grapheme) || FLAG.test(grapheme) || KEYCAP.test(grapheme)
}

/** Emoji graphemes found in free text, de-duplicated, in order. Non-emoji characters are ignored. */
export function extractEmojis(text: string): string[] {
  const out: string[] = []
  for (const g of graphemes(text)) {
    if (isEmoji(g) && !out.includes(g)) out.push(g)
  }
  return out
}

export interface EmojiCheck {
  ok: boolean
  error: string | null
}

export function checkEmojis(list: readonly string[]): EmojiCheck {
  if (list.length > MAX_EMOJIS) return { ok: false, error: `Max ${MAX_EMOJIS} emojis per sticker (WhatsApp limit)` }
  const bad = list.find((e) => graphemes(e).length !== 1 || !isEmoji(e))
  if (bad !== undefined) return { ok: false, error: `"${bad}" is not a single emoji` }
  if (new Set(list).size !== list.length) return { ok: false, error: 'Duplicate emoji' }
  return { ok: true, error: null }
}

/** Adds the emojis found in `text` to `list`, keeping at most MAX_EMOJIS. Reports what was dropped. */
export function addEmojis(list: readonly string[], text: string): { list: string[]; dropped: number; invalid: boolean } {
  const found = extractEmojis(text)
  const merged = [...list]
  let dropped = 0
  for (const e of found) {
    if (merged.includes(e)) continue
    if (merged.length >= MAX_EMOJIS) dropped++
    else merged.push(e)
  }
  const nonSpace = graphemes(text).filter((g) => g.trim() !== '')
  return { list: merged, dropped, invalid: nonSpace.length > found.length }
}
