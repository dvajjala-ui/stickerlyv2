#!/usr/bin/env bash
# Waits for an authorized Android device, then records read-only facts relevant to
# multi-WhatsApp delivery (profiles, WhatsApp installs, keyboards). Never changes settings.
ADB="${ADB:-/d/dev/android-sdk/platform-tools/adb.exe}"
OUT="${1:-device-facts.txt}"
deadline=$(( $(date +%s) + ${WAIT_SECONDS:-21600} ))
while :; do
  state=$("$ADB" get-state 2>/dev/null | tr -d '\r')
  [ "$state" = "device" ] && break
  [ "$(date +%s)" -ge "$deadline" ] && { echo "timeout waiting for authorized device" > "$OUT"; exit 3; }
  sleep 20
done
{
  echo "# collected $(date -u +%FT%TZ)"
  for p in ro.product.manufacturer ro.product.model ro.build.version.release ro.build.version.sdk ro.build.version.oneui ro.build.display.id; do
    echo "$p=$("$ADB" shell getprop "$p" | tr -d '\r')"
  done
  echo "## users"; "$ADB" shell pm list users | tr -d '\r'
  for u in $("$ADB" shell pm list users | tr -d '\r' | sed -n 's/.*UserInfo{\([0-9]*\):.*/\1/p'); do
    echo "## packages user $u"; "$ADB" shell pm list packages --user "$u" 2>/dev/null | tr -d '\r' | grep -i -E 'whatsapp|telegram|stickerdock|inputmethod|keyboard|honeyboard' || true
  done
  for pkg in com.whatsapp com.whatsapp.w4b; do
    echo "## $pkg"; "$ADB" shell dumpsys package "$pkg" 2>/dev/null | tr -d '\r' | grep -E 'versionName|versionCode|User [0-9]+:.*installed' | head -12
  done
  echo "## input methods (enabled)"; "$ADB" shell ime list -s 2>/dev/null | tr -d '\r'
  echo "default_input_method=$("$ADB" shell settings get secure default_input_method | tr -d '\r')"
  echo "## whatsapp sticker whitelist providers"; "$ADB" shell dumpsys package providers 2>/dev/null | tr -d '\r' | grep -i -E 'sticker_whitelist_check|sticker' | head -10
} > "$OUT" 2>&1
echo "facts written to $OUT"
