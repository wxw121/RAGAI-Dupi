$ErrorActionPreference = "Stop"

$gateScript = Join-Path $PSScriptRoot "rag-quality-gate.ps1"
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("dupi-rag-gate-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tempRoot | Out-Null

function Assert-GateExitCode {
    param(
        [string]$Status,
        [bool]$BlockWhenUnbaselined,
        [int]$ExpectedExitCode
    )

    $runPath = Join-Path $tempRoot "run.json"
    $policyPath = Join-Path $tempRoot "policy.json"
    @{ gateStatus = $Status } | ConvertTo-Json -Compress | Set-Content -Encoding utf8 $runPath
    @{ blockWhenUnbaselined = $BlockWhenUnbaselined } | ConvertTo-Json -Compress | Set-Content -Encoding utf8 $policyPath

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & powershell -NoProfile -ExecutionPolicy Bypass -File $gateScript `
        -RunResponsePath $runPath -PolicyResponsePath $policyPath 2>$null | Out-Null
    $actualExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($actualExitCode -ne $ExpectedExitCode) {
        throw "Expected $Status to exit $ExpectedExitCode, got $actualExitCode"
    }
}

try {
    Assert-GateExitCode "PASS" $false 0
    Assert-GateExitCode "WARN" $false 0
    Assert-GateExitCode "BLOCKED" $false 1
    Assert-GateExitCode "UNBASELINED" $false 0
    Assert-GateExitCode "UNBASELINED" $true 1
    Assert-GateExitCode "UNKNOWN" $false 2

    $runPath = Join-Path $tempRoot "run.json"
    $policyPath = Join-Path $tempRoot "policy.json"
    Set-Content -Encoding utf8 $runPath "{"
    Set-Content -Encoding utf8 $policyPath '{"blockWhenUnbaselined":false}'
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & powershell -NoProfile -ExecutionPolicy Bypass -File $gateScript `
        -RunResponsePath $runPath -PolicyResponsePath $policyPath 2>$null | Out-Null
    $malformedExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($malformedExitCode -ne 2) {
        throw "Expected malformed JSON to exit 2, got $malformedExitCode"
    }
    Write-Output "RAG quality gate exit-code tests passed."
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force
}
