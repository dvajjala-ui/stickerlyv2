@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.stickerdock.ios

import app.stickerdock.core.Dhash
import app.stickerdock.core.StickerRules
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGInterpolationHigh
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage

/** Image work iOS can do natively: UIImage decodes WebP (static and the first frame of animations) on iOS 14+. */
internal object Images {
    /** WhatsApp tray icon: exactly 96 × 96 px PNG (scale 1), ≤ 50 KB, from the pack's cover sticker. */
    fun trayPng(sticker: ByteArray): ByteArray? = autoreleasepool {
        val image = UIImage.imageWithData(sticker.toNSData()) ?: return@autoreleasepool null
        val side = StickerRules.TRAY_PIXELS.toDouble()
        val (width, height) = image.size.useContents { width to height }
        if (width <= 0.0 || height <= 0.0) return@autoreleasepool null
        val scale = minOf(side / width, side / height)
        val w = width * scale
        val h = height * scale
        val format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        format.opaque = false
        val renderer = UIGraphicsImageRenderer(size = CGSizeMake(side, side), format = format)
        val png = renderer.PNGDataWithActions { _ -> image.drawInRect(CGRectMake((side - w) / 2, (side - h) / 2, w, h)) }
        png.toByteArray().takeIf { it.size <= StickerRules.TRAY_MAX_BYTES }
    }

    /**
     * 64-bit dHash of the first frame for near-duplicate detection (see [Dhash]): drawn into a 64 × 64
     * RGBA bitmap, composited onto white, then box-averaged to 9 × 8. Null when undecodable or flat.
     */
    fun dhash(bytes: ByteArray): Long? = autoreleasepool {
        val cgImage = UIImage.imageWithData(bytes.toNSData())?.CGImage ?: return@autoreleasepool null
        val side = 64
        val rgba = ByteArray(side * side * 4)
        val drawn = rgba.usePinned { pinned ->
            val space = CGColorSpaceCreateDeviceRGB()
            val context = CGBitmapContextCreate(pinned.addressOf(0), side.toULong(), side.toULong(), 8u, (side * 4).toULong(), space,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value)
            if (context != null) {
                CGContextSetInterpolationQuality(context, kCGInterpolationHigh)
                CGContextDrawImage(context, CGRectMake(0.0, 0.0, side.toDouble(), side.toDouble()), cgImage)
                CGContextRelease(context)
            }
            CGColorSpaceRelease(space)
            context != null
        }
        if (!drawn) return@autoreleasepool null
        val pixels = IntArray(Dhash.WIDTH * Dhash.HEIGHT)
        for (oy in 0 until Dhash.HEIGHT) for (ox in 0 until Dhash.WIDTH) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (y in oy * side / Dhash.HEIGHT until (oy + 1) * side / Dhash.HEIGHT) {
                for (x in ox * side / Dhash.WIDTH until (ox + 1) * side / Dhash.WIDTH) {
                    val i = (y * side + x) * 4
                    val white = 255 - (rgba[i + 3].toInt() and 0xff) // premultiplied colour over white
                    r += (rgba[i].toInt() and 0xff) + white
                    g += (rgba[i + 1].toInt() and 0xff) + white
                    b += (rgba[i + 2].toInt() and 0xff) + white
                    n++
                }
            }
            pixels[oy * Dhash.WIDTH + ox] = (0xff shl 24) or ((r / n).coerceAtMost(255) shl 16) or
                ((g / n).coerceAtMost(255) shl 8) or (b / n).coerceAtMost(255)
        }
        if (Dhash.informative(pixels)) Dhash.of(pixels) else null
    }
}
