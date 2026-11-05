package app.stickerdock.ios.whatsapp

import app.stickerdock.ios.toNSData
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.dateByAddingTimeInterval
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardOptionExpirationDate
import platform.UIKit.UIPasteboardOptionLocalOnly

/**
 * Hands one pack to WhatsApp the way the official iOS sample does (Interoperability.swift). WhatsApp and
 * WhatsApp Business register the same `whatsapp://` scheme; iOS decides which app opens. Main thread only.
 */
internal object WhatsAppSender {
    /** Needs `whatsapp` in LSApplicationQueriesSchemes (Info.plist). */
    fun installed(): Boolean {
        val url = NSURL.URLWithString(WhatsAppPayload.PROBE_URL) ?: return false
        return UIApplication.sharedApplication.canOpenURL(url)
    }

    fun send(json: ByteArray, onOpened: (Boolean) -> Unit) {
        val item = mapOf<Any?, Any?>(WhatsAppPayload.PASTEBOARD_TYPE to json.toNSData())
        val options = mapOf<Any?, Any?>(
            UIPasteboardOptionLocalOnly to NSNumber(bool = true),
            UIPasteboardOptionExpirationDate to NSDate().dateByAddingTimeInterval(WhatsAppPayload.PASTEBOARD_SECONDS),
        )
        UIPasteboard.generalPasteboard.setItems(listOf(item), options)
        val url = NSURL.URLWithString(WhatsAppPayload.SEND_URL) ?: return onOpened(false)
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any?>()) { opened -> onOpened(opened) }
    }
}
