import SwiftUI
import Shared

@main
struct StickerDockApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                // .wastickers / .zip opened with StickerDock (Files, Mail, a WhatsApp chat's share sheet).
                .onOpenURL { url in
                    _ = MainViewControllerKt.openDocument(url: url)
                }
        }
    }
}
