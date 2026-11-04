import { useState } from 'react'
import type { Nsfw } from '../api/types'
import { Icon } from './Icon'

/** Sticker art on a transparency checkerboard. */
export function StickerImage({ src, alt, size }: { src: string; alt: string; size?: number }) {
  const [broken, setBroken] = useState(false)
  return (
    <div className="checker media-box" style={size ? { width: size, height: size } : undefined}>
      {src && !broken ? (
        <img src={src} alt={alt} loading="lazy" decoding="async" onError={() => setBroken(true)} />
      ) : (
        <span className="media-missing">no image</span>
      )}
    </div>
  )
}

/**
 * A collected source (image or short video) from a signed URL. Explicit material is hidden
 * behind a click so it never shows up on screen by accident.
 */
export function SourceMedia({
  src,
  mime,
  nsfw,
  alt,
  controls,
}: {
  src: string | undefined
  mime: string
  nsfw: Nsfw | null
  alt: string
  controls?: boolean
}) {
  const [revealed, setRevealed] = useState(false)
  const hidden = nsfw === 'explicit' && !revealed
  // Demo posters for videos are SVG data URLs; only real video files go in a <video>.
  const isVideo = mime.startsWith('video/') && !!src && !src.startsWith('data:image/')
  return (
    <div className={`media-box source-media ${hidden ? 'is-hidden' : ''}`}>
      {!src ? (
        <span className="media-missing">loading…</span>
      ) : isVideo ? (
        <video src={src} muted loop playsInline controls={controls} preload="metadata" autoPlay={!!controls} />
      ) : (
        <img src={src} alt={alt} loading="lazy" decoding="async" />
      )}
      {mime.startsWith('video/') && !controls && (
        <span className="media-tag">
          <Icon name="play" size={11} /> {mime.split('/')[1]}
        </span>
      )}
      {hidden && (
        <button
          className="media-veil"
          onClick={(e) => {
            e.stopPropagation()
            setRevealed(true)
          }}
        >
          Explicit — click to reveal
        </button>
      )}
    </div>
  )
}
