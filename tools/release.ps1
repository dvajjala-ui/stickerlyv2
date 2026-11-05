<#
.SYNOPSIS
  Builds a signed StickerDock release APK for sideloading (no Play Store needed).

.DESCRIPTION
  Signing material lives OUTSIDE the repo in %USERPROFILE%\.stickerdock\:
    release.jks          the signing key (created on first run)
    release.properties   its passwords (created on first run)
    discover.properties  optional: url=https://<project>.supabase.co and anon_key=<public anon key>
  Back that folder up. Android only installs updates signed with the same key, so losing it means
  uninstalling (and restoring from a StickerDock backup) before the next build can be installed.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\release.ps1 -Install
#>
param(
    [switch]$Install,             # adb install -r the APK on the connected phone
    [string]$DiscoverUrl = "",    # overrides discover.properties
    [string]$DiscoverAnonKey = ""
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'dev-env.ps1')

$home_ = Join-Path $env:USERPROFILE '.stickerdock'
$jks = Join-Path $home_ 'release.jks'
$props = Join-Path $home_ 'release.properties'
New-Item -ItemType Directory -Force $home_ | Out-Null

function Read-Props($path) {
    $map = @{}
    if (Test-Path $path) {
        Get-Content $path | Where-Object { $_ -match '^\s*([^#=]+?)\s*=\s*(.*)$' } | ForEach-Object { $map[$Matches[1]] = $Matches[2].Trim() }
    }
    return $map
}

if (-not (Test-Path $jks)) {
    Write-Host "No signing key yet — creating $jks"
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $password = [Convert]::ToBase64String($bytes) -replace '[+/=]', 'x'
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $jks -storetype PKCS12 -alias stickerdock `
        -keyalg RSA -keysize 3072 -validity 10000 -storepass $password -keypass $password `
        -dname "CN=StickerDock, O=StickerDock owner" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
    @("# StickerDock release signing — keep private, back up together with release.jks",
      "store_password=$password", "key_alias=stickerdock", "key_password=$password") | Set-Content -Encoding ascii $props
    Write-Host "Created. BACK UP $home_ (both files) somewhere safe." -ForegroundColor Yellow
}
$signing = Read-Props $props
if (-not $signing['store_password']) { throw "$props is missing store_password" }

$discover = Read-Props (Join-Path $home_ 'discover.properties')
if (-not $DiscoverUrl) { $DiscoverUrl = $discover['url'] }
if (-not $DiscoverAnonKey) { $DiscoverAnonKey = $discover['anon_key'] }
if (-not $DiscoverUrl) { Write-Host "No Discover project configured — the Discover tab will show its offline state." -ForegroundColor Yellow }

Push-Location $repo
try { $versionCode = [int](git rev-list --count HEAD) } finally { Pop-Location }

$env:ANDROID_KEYSTORE_FILE = $jks
$env:ANDROID_KEYSTORE_PASSWORD = $signing['store_password']
$env:ANDROID_KEY_ALIAS = $signing['key_alias']
$env:ANDROID_KEY_PASSWORD = $signing['key_password']
Push-Location (Join-Path $repo 'app')
try {
    & .\gradlew.bat :androidApp:assembleRelease "-PversionCode=$versionCode" "-PdiscoverUrl=$DiscoverUrl" "-PdiscoverAnonKey=$DiscoverAnonKey" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally {
    Pop-Location
    Remove-Item Env:ANDROID_KEYSTORE_PASSWORD, Env:ANDROID_KEY_PASSWORD -ErrorAction SilentlyContinue
}

$apk = Join-Path $repo 'app\androidApp\build\outputs\apk\release\androidApp-release.apk'
if (-not (Test-Path $apk)) { throw "Signed APK not found at $apk" }
$dist = Join-Path $repo 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
$out = Join-Path $dist "StickerDock-$versionCode.apk"
Copy-Item $apk $out -Force

$apksigner = Get-ChildItem "$env:ANDROID_HOME\build-tools\*\apksigner.bat" | Sort-Object FullName | Select-Object -Last 1
if ($apksigner) { & $apksigner.FullName verify --print-certs $out | Select-String 'SHA-256' }
Write-Host "APK: $out  (sha256 $((Get-FileHash $out -Algorithm SHA256).Hash.ToLower()))"

if ($Install) {
    & "$env:ANDROID_HOME\platform-tools\adb.exe" install -r $out
    if ($LASTEXITCODE -ne 0) { throw "adb install failed (a build signed with a different key must be uninstalled first)" }
}
