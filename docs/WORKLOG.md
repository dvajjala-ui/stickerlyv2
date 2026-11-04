# Work log (newest first)

Resume here after any interruption. Each entry: what changed, how it was verified, what is next.

## 2026-11-05 — session 1 (owner asleep; autonomous overnight run)

**Device (owner's Galaxy S24 Ultra)** — authorized over USB.
- SM-S928B, Android 16 (SDK 36), One UI 8.5 (`ro.build.version.oneui=80500`).
- Users: 0 owner · **95 `DUAL_APP` type `profile.CLONE` (parent 0)** · 150 Secure Folder (`profile.PRIVATE`).
- `com.whatsapp` 2.26.39.74 installed in user 0 **and** user 95. WhatsApp Business not installed.
- Default IME Samsung Keyboard (`com.samsung.android.honeyboard`). Sticker.ly installed in user 0.
- Changed for overnight testing: `stay_on_while_plugged_in` 0 → 2 (USB). **Restore:** `adb shell svc power stayon false`.
- Rule: never open WhatsApp chats or send anything; install/uninstall our builds and read state only.

**Toolchain installed** (D:\dev): Temurin JDK 21.0.13+9 (sha256 verified), Android cmdline-tools +
platform-tools (adb 1.0.41), Gradle 9.6.1 (sha256 verified). Gradle user home `D:\dev\gradle-home`.

**Decisions made by owner** — see AGENTS.md "Owner decisions".

**Next**
1. Android core build: shared library DB + WebP rules, provider, keyboard, folder import → install on S24.
2. Device checks: provider eligibility, `pm install-existing --user 95` feasibility, keyboard into
   our own test field (never into WhatsApp chats).
3. Backend schema + worker + admin; docs (PRODUCT, ARCHITECTURE, CONTENT_POLICY, STATUS, TEST_MATRIX).
