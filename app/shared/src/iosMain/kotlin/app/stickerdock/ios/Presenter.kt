@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package app.stickerdock.ios

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject

/** Presents system UI (document pickers, share sheet, alerts) above the Compose screen. Main thread only. */
internal class Presenter {
    private var root: WeakReference<UIViewController>? = null

    /** UIDocumentPickerViewController.delegate is weak, so the active delegate is kept here. */
    private var activeDelegate: PickerDelegate? = null

    fun attach(controller: UIViewController) {
        root = WeakReference(controller)
    }

    private fun top(): UIViewController? {
        var controller = root?.get() ?: return null
        while (true) {
            val next = controller.presentedViewController ?: break
            if (next.isBeingDismissed()) break
            controller = next
        }
        return controller
    }

    /** Files picker. [asCopy] gives the app private copies (iCloud files are downloaded first). */
    fun pickDocuments(types: List<UTType>, multiple: Boolean, asCopy: Boolean, onPicked: (List<NSURL>) -> Unit) {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = asCopy)
        picker.allowsMultipleSelection = multiple
        picker.shouldShowFileExtensions = true
        present(picker, onPicked)
    }

    /** "Save to Files" for a finished file; [onDone] gets true when the user saved it somewhere. */
    fun exportFile(url: NSURL, onDone: (Boolean) -> Unit) {
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
        present(picker) { onDone(it.isNotEmpty()) }
    }

    fun share(url: NSURL) {
        val presenter = top() ?: return
        val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
        sheet.popoverPresentationController?.sourceView = presenter.view // iPad / iPhone-app-on-iPad anchor
        presenter.presentViewController(sheet, animated = true, completion = null)
    }

    fun alert(title: String, message: String, action: String, onAction: () -> Unit) {
        val presenter = top() ?: return onAction()
        val alert = UIAlertController.alertControllerWithTitle(title, message, UIAlertControllerStyleAlert)
        alert.addAction(UIAlertAction.actionWithTitle("Cancel", UIAlertActionStyleCancel, null))
        alert.addAction(UIAlertAction.actionWithTitle(action, UIAlertActionStyleDefault) { _ -> onAction() })
        presenter.presentViewController(alert, animated = true, completion = null)
    }

    private fun present(picker: UIDocumentPickerViewController, onPicked: (List<NSURL>) -> Unit) {
        val presenter = top() ?: return onPicked(emptyList())
        val delegate = PickerDelegate { urls ->
            activeDelegate = null
            onPicked(urls)
        }
        activeDelegate = delegate
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }
}

internal class PickerDelegate(private val onPicked: (List<NSURL>) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    private var done = false

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        finish(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        finish(emptyList())
    }

    private fun finish(urls: List<NSURL>) {
        if (done) return
        done = true
        onPicked(urls)
    }
}
