# Real browser E2E gate for dupi-RAG Web.
# Requires a running local app and a real admin login.
param(
    [string]$BaseUrl = ""
)

$ErrorActionPreference = "Stop"

if (-not $BaseUrl) {
    if ($env:E2E_BASE_URL) {
        $BaseUrl = $env:E2E_BASE_URL
    } else {
        $BaseUrl = "http://localhost:8080"
    }
}

if (-not $env:E2E_ADMIN_USERNAME -or -not $env:E2E_ADMIN_PASSWORD) {
    Write-Host "Missing E2E_ADMIN_USERNAME or E2E_ADMIN_PASSWORD. Set both env vars to run the real browser gate." -ForegroundColor Red
    exit 2
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$webRoot = Join-Path $repoRoot "services\web"

Push-Location $webRoot
try {
    $env:E2E_BASE_URL = $BaseUrl
    Write-Host "Running browser E2E gate against $BaseUrl" -ForegroundColor Cyan
    npm run test:e2e -- --config=playwright.config.ts
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
