// Generated SVG artwork for demo mode, so no real images ship with the panel.

export function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function svgUrl(svg: string): string {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg.replace(/\s*\n\s*/g, ' '))}`
}

function wrap(text: string, max: number, maxLines = 3): string[] {
  const lines: string[] = []
  let line = ''
  for (const w of text.split(/\s+/).filter(Boolean)) {
    const next = line ? `${line} ${w}` : w
    if (next.length > max && line) {
      lines.push(line)
      line = w
    } else line = next
  }
  if (line) lines.push(line)
  return lines.slice(0, maxLines)
}

/** Closed, smooth random blob (Catmull-Rom through jittered polar points). */
function blobPath(seed: number, cx: number, cy: number, r: number, points = 9, wobble = 0.12): string {
  const rand = mulberry32(seed)
  const pts = Array.from({ length: points }, (_, i) => {
    const a = (i / points) * Math.PI * 2
    const rr = r * (1 - wobble + rand() * wobble * 2)
    return [cx + Math.cos(a) * rr, cy + Math.sin(a) * rr]
  })
  const f = (n: number) => n.toFixed(1)
  let d = `M${f(pts[0][0])},${f(pts[0][1])}`
  for (let i = 0; i < points; i++) {
    const p0 = pts[(i - 1 + points) % points]
    const p1 = pts[i]
    const p2 = pts[(i + 1) % points]
    const p3 = pts[(i + 2) % points]
    d += ` C${f(p1[0] + (p2[0] - p0[0]) / 6)},${f(p1[1] + (p2[1] - p0[1]) / 6)} ${f(p2[0] - (p3[0] - p1[0]) / 6)},${f(
      p2[1] - (p3[1] - p1[1]) / 6,
    )} ${f(p2[0])},${f(p2[1])}`
  }
  return `${d}Z`
}

const EMOJI_FONT = "'Segoe UI Emoji','Apple Color Emoji','Noto Color Emoji',sans-serif"
const CAPTION_FONT = "'Arial Black','Segoe UI Black',Impact,system-ui,sans-serif"

/** Visible defects for held demo stickers, so the Held tab shows what each quality reason looks like. */
export type ArtFlaw = 'blurry' | 'tiny' | 'low_contrast'

/** 512×512 die-cut sticker with an emoji "character" and a Hinglish caption. */
export function stickerArt(o: { seed: number; caption: string; emoji: string; hue: number; animated: boolean; flaw?: ArtFlaw }): string {
  const lines = wrap(o.caption, 15)
  const fs = lines.length > 2 ? 40 : lines.length === 2 ? 46 : 54
  const shape = blobPath(o.seed, 256, 226, 176)
  const firstY = 418 - (lines.length - 1) * fs * 0.52
  const tspans = lines.map((l, i) => `<tspan x="256" y="${(firstY + i * fs * 1.04).toFixed(0)}">${esc(l)}</tspan>`).join('')
  const filter = o.flaw === 'blurry' ? '<filter id="f"><feGaussianBlur stdDeviation="7"/></filter>' : ''
  const body = o.flaw === 'blurry' ? ' filter="url(#f)"' : o.flaw === 'tiny' ? ' transform="translate(196 196) scale(0.235)"' : ''
  const [textFill, textStroke] = o.flaw === 'low_contrast' ? ['#cfd3c8', '#e9ebe4'] : ['#12130F', '#fff']
  const wiggle = o.animated
    ? '<animateTransform attributeName="transform" type="rotate" values="-6 256 256;6 256 256;-6 256 256" dur="1.3s" repeatCount="indefinite"/>'
    : ''
  const bob = o.animated
    ? '<animateTransform attributeName="transform" type="translate" values="0 0;0 -18;0 0" dur="0.65s" repeatCount="indefinite"/>'
    : ''
  return svgUrl(`<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
    <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="hsl(${o.hue} 82% 66%)"/><stop offset="1" stop-color="hsl(${(o.hue + 38) % 360} 86% 54%)"/>
    </linearGradient>${filter}</defs>
    <g${body}><g>${wiggle}
      <path d="${shape}" fill="#fff" stroke="#fff" stroke-width="30" stroke-linejoin="round"/>
      <path d="${shape}" fill="url(#g)"/>
      <g>${bob}<text x="256" y="212" font-size="168" text-anchor="middle" dominant-baseline="central" font-family="${EMOJI_FONT}">${esc(o.emoji)}</text></g>
      <text font-size="${fs}" font-weight="900" text-anchor="middle" fill="${textFill}" stroke="${textStroke}" stroke-width="11"
        stroke-linejoin="round" paint-order="stroke" font-family="${CAPTION_FONT}">${tspans}</text>
    </g></g></svg>`)
}

/** A "collected photo / meme" stand-in: gradient scene, subject emoji, optional top/bottom meme text. */
export function sourceArt(o: {
  seed: number
  width: number
  height: number
  hue: number
  emoji: string
  top?: string | null
  bottom?: string | null
  video?: boolean
}): string {
  const rand = mulberry32(o.seed)
  const { width: w, height: h } = o
  const blobs = Array.from({ length: 5 }, (_, i) => {
    const r = (0.12 + rand() * 0.22) * Math.min(w, h)
    return `<circle cx="${(rand() * w).toFixed(0)}" cy="${(rand() * h).toFixed(0)}" r="${r.toFixed(0)}" fill="hsl(${(o.hue + i * 23) % 360} 70% ${
      55 + rand() * 25
    }%)" opacity="${(0.25 + rand() * 0.35).toFixed(2)}"/>`
  }).join('')
  const meme = (text: string | null | undefined, y: number) => {
    if (!text) return ''
    const lines = wrap(text.toUpperCase(), Math.max(12, Math.round(w / 34)), 2)
    const fs = Math.round(Math.min(w, h) / 11)
    return `<text font-size="${fs}" font-weight="900" text-anchor="middle" fill="#fff" stroke="#000" stroke-width="${Math.round(
      fs / 7,
    )}" paint-order="stroke" font-family="Impact,'Arial Black',sans-serif">${lines
      .map((l, i) => `<tspan x="${w / 2}" y="${(y + i * fs * 1.05).toFixed(0)}">${esc(l)}</tspan>`)
      .join('')}</text>`
  }
  const play = o.video
    ? `<circle cx="${w / 2}" cy="${h / 2}" r="${Math.min(w, h) / 9}" fill="#000" opacity=".55"/><path d="M${w / 2 - 14},${
        h / 2 - 22
      } l40,22 -40,22z" fill="#fff"/>`
    : ''
  const fs = Math.round(Math.min(w, h) * 0.42)
  return svgUrl(`<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
    <defs><linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="hsl(${o.hue} 45% 78%)"/><stop offset="1" stop-color="hsl(${(o.hue + 30) % 360} 40% 42%)"/>
    </linearGradient></defs>
    <rect width="${w}" height="${h}" fill="url(#bg)"/>${blobs}
    <text x="${w / 2}" y="${h * 0.54}" font-size="${fs}" text-anchor="middle" dominant-baseline="central" font-family="${EMOJI_FONT}">${esc(o.emoji)}</text>
    ${meme(o.top, h * 0.14)}${meme(o.bottom, h * 0.86)}${play}</svg>`)
}
