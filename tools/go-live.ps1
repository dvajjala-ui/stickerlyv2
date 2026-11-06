<#
.SYNOPSIS
  Takes StickerDock v2 live on the Supabase project in .env (shared with HandNavigate), step by step.

.DESCRIPTION
  1. Checks .env has what each step needs (prints key names only, never values).
  2. Applies backend/supabase/migrations (each file once; python -m stickerworker migrate).
  3. Runs the AI worker once: library (HandNavigate's images) -> prescreen -> analyze -> create -> compose.
  4. Builds the signed release APK with Discover pointing at the project (tools/release.ps1).
  Each step can be skipped. Re-running is safe: migrations and imports are idempotent.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\go-live.ps1
  powershell -ExecutionPolicy Bypass -File tools\go-live.ps1 -SkipApp -WorkerLimit 20
#>
param(
    [switch]$SkipMigrations,
    [switch]$SkipWorker,
    [switch]$SkipApp,
    [switch]$Install,          # pass through to release.ps1: adb install the APK
    [int]$WorkerLimit = 0      # max sources per queue stage for this first run (0 = worker defaults)
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $repo '.env'
if (-not (Test-Path $envFile)) { throw "Create $envFile from .env.example first." }

$values = @{}
Get-Content $envFile | Where-Object { $_ -match '^\s*([A-Z0-9_]+)\s*=\s*(.*)$' } | ForEach-Object {
    $value = ($Matches[2] -split '\s+#', 2)[0].Trim().Trim('"').Trim("'")
    if ($value) { $values[$Matches[1]] = $value }
}
function Need([string[]]$keys, [string]$step) {
    $missing = $keys | Where-Object { -not $values.ContainsKey($_) }
    if ($missing) { throw "$step needs $($missing -join ', ') in .env" }
}
"Values set in .env: " + (($values.Keys | Sort-Object) -join ', ')

$py = Join-Path $repo 'backend\worker\.venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw "Worker environment missing: create backend\worker\.venv and pip install -e "".[dev,db]""" }
Push-Location (Join-Path $repo 'backend\worker')
try {
    if (-not $SkipMigrations) {
        Need @('SUPABASE_DB_HOST', 'SUPABASE_DB_USER', 'SUPABASE_DB_PASSWORD') 'Migrations'
        "== Migrations"
        & $py -m stickerworker migrate
        if ($LASTEXITCODE -ne 0) { throw "migrate failed" }
    }
    if (-not $SkipWorker) {
        Need @('SUPABASE_URL', 'SUPABASE_SECRET_KEY') 'The worker'
        if (-not $values.ContainsKey('GROQ_API_KEYS')) { "No GROQ_API_KEYS: images are queued and pre-screened, but analyze/create wait for keys." }
        "== Worker (one full run)"
        $workerArgs = @('-m', 'stickerworker', 'run', '--stage', 'all')
        if ($WorkerLimit -gt 0) { $workerArgs += @('--limit', "$WorkerLimit") }
        & $py @workerArgs
        if ($LASTEXITCODE -eq 1) { throw "a worker stage crashed (see the output above)" }
    }
} finally { Pop-Location }

if (-not $SkipApp) {
    Need @('SUPABASE_URL', 'SUPABASE_PUBLISHABLE_KEY') 'The app build'
    "== Release APK (Discover = $($values['SUPABASE_URL']))"
    $release = @{ }
    if ($Install) { $release['Install'] = $true }
    & (Join-Path $PSScriptRoot 'release.ps1') @release
}

""
"Next, so it keeps running without this PC: GitHub -> stickerlyv2 -> Settings -> Secrets and variables -> Actions:"
"  secrets SUPABASE_URL, SUPABASE_SECRET_KEY, GROQ_API_KEYS; variable WORKER_SCHEDULE = on"
