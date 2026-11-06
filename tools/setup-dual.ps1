# One-time Dual Messenger / clone-profile setup for StickerDock (run on a PC with the phone on USB debugging).
#   powershell -ExecutionPolicy Bypass -File tools\setup-dual.ps1 [-Package app.stickerdock] [-Apk path\to.apk]
# Installs the StickerDock companion into every clone/dual profile and grants the cross-user permission so it can
# serve that profile's WhatsApp from your main library. Safe to re-run.
param(
    [string]$Package = 'app.stickerdock',
    [string]$Apk = '',
    [string]$Adb = 'D:\dev\android-sdk\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Adb)) { $Adb = 'adb' }
$state = (& $Adb get-state 2>$null)
if ($state -ne 'device') { throw 'No authorized phone found. Enable USB debugging and accept the prompt on the phone.' }
if ($Apk) { & $Adb install -r $Apk | Out-Host }
$users = & $Adb shell pm list users
$clones = $users | Select-String -Pattern 'UserInfo\{(\d+):([^:]*):' -AllMatches | ForEach-Object {
    $_.Matches | Where-Object { $_.Groups[1].Value -ne '0' -and $_.Groups[2].Value -match 'DUAL|Clone|clone|Parallel|Twin' } |
        ForEach-Object { $_.Groups[1].Value }
}
if (-not $clones) { Write-Host 'No Dual Messenger / clone profile found on this phone.'; exit 0 }
& $Adb shell pm grant $Package android.permission.INTERACT_ACROSS_USERS
foreach ($u in $clones) {
    Write-Host "Clone profile user $u"
    & $Adb shell pm install-existing --user $u $Package
    & $Adb shell pm grant --user $u $Package android.permission.INTERACT_ACROSS_USERS
}
Write-Host 'Done. Open StickerDock -> WhatsApp tab: "WhatsApp - Dual Messenger" should show Ready.'
