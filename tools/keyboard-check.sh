#!/usr/bin/env bash
# K1: StickerDock keyboard → our own debug test field (never a messaging app).
# Leaves our keyboard enabled but restores the owner's default keyboard at the end.
export MSYS_NO_PATHCONV=1
ADB="${ADB:-/d/dev/android-sdk/platform-tools/adb.exe}"
PKG="${PKG:-app.stickerdock.dev}"
IME="$PKG/app.stickerdock.android.keyboard.StickerKeyboardService"
OUT="${1:-.}"
DEFAULT=$("$ADB" shell settings get secure default_input_method | tr -d '\r')
echo "default keyboard: $DEFAULT"
"$ADB" shell ime enable "$IME"
"$ADB" shell ime set "$IME"
"$ADB" logcat -c
"$ADB" shell am start -n "$PKG/app.stickerdock.android.keyboard.KeyboardTestActivity" >/dev/null
sleep 4
"$ADB" exec-out screencap -p > "$OUT/kb_open.png"
# Tap the first sticker cell of the keyboard grid (keyboard occupies the bottom ~310dp).
SIZE=$("$ADB" shell wm size | tr -d '\r' | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/')
W=${SIZE% *}; H=${SIZE#* }
"$ADB" shell input tap $((W / 8)) $((H - H * 22 / 100))
sleep 3
"$ADB" exec-out screencap -p > "$OUT/kb_after.png"
"$ADB" logcat -d -s StickerDock:I | grep -E "\[keyboard|keyboard-test" | cut -c1-260
"$ADB" shell ime set "$DEFAULT"
echo "restored keyboard: $("$ADB" shell settings get secure default_input_method | tr -d '\r')"
