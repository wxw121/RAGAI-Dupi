param(
    [switch]$PolicyOnly,
    [string]$PipAuditJson = "",
    [string]$TrivyJson = "",
    [string]$SbomJson = "",
    [long]$ImageSizeBytes = 0,
    [long]$ImageBudgetBytes = 3GB,
    [string]$Image = "dupi-rag-worker:v1.3",
    [string]$OutputPath = (Join-Path $PSScriptRoot "..\artifacts\v1.3-release-scan"),
    [string]$HighExceptionPath = ""
)

$ErrorActionPreference = "Stop"
$denylistPath = Join-Path $PSScriptRoot "..\deploy\license-denylist.txt"

function Read-Json([string]$Path) {
    if (-not $Path) { return $null }
    Get-Content -Raw -Encoding UTF8 $Path | ConvertFrom-Json
}

function Assert-Policy {
    $blocked = @()
    if ($PipAuditJson) {
        $audit = Read-Json $PipAuditJson
        $pythonFindings = @($audit | ForEach-Object { $_ } | Where-Object { @($_.vulns).Count -gt 0 })
        if ($pythonFindings.Count -gt 0) {
            $blocked += "Vulnerable Python dependencies: $($pythonFindings.name -join ', ')"
        }
    }
    if ($TrivyJson) {
        $trivy = Read-Json $TrivyJson
        $critical = @($trivy.Results | ForEach-Object { $_.Vulnerabilities } | Where-Object { $_.Severity -eq "CRITICAL" -and -not $_.FixedVersion })
        if ($critical.Count -gt 0) { $blocked += "Unfixed CRITICAL vulnerabilities: $($critical.VulnerabilityID -join ', ')" }
    }
    if ($SbomJson) {
        $sbom = Read-Json $SbomJson
        $denied = @(Get-Content -Encoding UTF8 $denylistPath | Where-Object { $_ -and -not $_.StartsWith('#') })
        $found = @($sbom.artifacts | ForEach-Object { $_.licenses } | ForEach-Object { $_.value } | Where-Object { $denied -contains $_ } | Sort-Object -Unique)
        if ($found.Count -gt 0) { $blocked += "Denied license: $($found -join ', ')" }
    }
    if ($ImageSizeBytes -gt $ImageBudgetBytes) { $blocked += "Worker image size $ImageSizeBytes exceeds budget $ImageBudgetBytes" }
    if ($blocked.Count -gt 0) { throw ($blocked -join "; ") }
}

try {
    if ($PolicyOnly) { Assert-Policy; Write-Host "Release scan policy passed"; exit 0 }
    foreach ($command in @("pip-audit", "syft", "trivy", "docker")) {
        if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Missing required scan tool: $command" }
    }
    New-Item -ItemType Directory -Force -Path $OutputPath | Out-Null
    $PipAuditJson = Join-Path $OutputPath "pip-audit.json"
    $SbomJson = Join-Path $OutputPath "worker-sbom.syft.json"
    $cycloneDx = Join-Path $OutputPath "worker-sbom.cdx.json"
    $TrivyJson = Join-Path $OutputPath "worker-trivy.json"
    $requirements = Join-Path $PSScriptRoot "..\services\worker\requirements.txt"
    & pip-audit -r $requirements -f json -o $PipAuditJson
    if ($LASTEXITCODE -notin @(0, 1)) { throw "pip-audit failed with exit code $LASTEXITCODE" }
    & docker build -t $Image (Join-Path $PSScriptRoot "..\services\worker")
    if ($LASTEXITCODE -ne 0) { throw "Worker image build failed" }
    & syft $Image -o "syft-json=$SbomJson" -o "cyclonedx-json=$cycloneDx"
    if ($LASTEXITCODE -ne 0) { throw "Syft scan failed" }
    & trivy image --format json --output $TrivyJson $Image
    if ($LASTEXITCODE -ne 0) { throw "Trivy scan failed" }
    $ImageSizeBytes = [long](& docker image inspect $Image --format "{{.Size}}")
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect Worker image size" }
    Assert-Policy
    $trivy = Read-Json $TrivyJson
    $high = @($trivy.Results | ForEach-Object { $_.Vulnerabilities } | Where-Object { $_.Severity -eq "HIGH" })
    if ($high.Count -gt 0 -and -not $HighExceptionPath) { throw "HIGH vulnerabilities require a dated exception file" }
    if ($HighExceptionPath -and -not (Test-Path $HighExceptionPath)) { throw "HIGH exception file does not exist" }
    @"
# V1.3 Release Scan

- Generated: $((Get-Date).ToUniversalTime().ToString("o"))
- Image: $Image
- Image size: $ImageSizeBytes bytes
- Image budget: $ImageBudgetBytes bytes
- Unfixed CRITICAL findings: 0
- HIGH findings: $($high.Count)
- License deny list: deploy/license-denylist.txt
- SBOM: worker-sbom.cdx.json
"@ | Set-Content -Encoding UTF8 (Join-Path $OutputPath "summary.md")
    Write-Host "Release scan passed: $OutputPath"
    exit 0
} catch {
    Write-Error $_
    exit 1
}
