param(
    [Parameter(Mandatory = $true)][string]$KbId,
    [Parameter(Mandatory = $true)][string]$HybridProfileId,
    [Parameter(Mandatory = $true)][string]$RerankProfileId,
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ApiKey = "",
    [string]$OutputPath = "",
    [string]$ManifestPath = (Join-Path $PSScriptRoot "..\benchmarks\v1.3\retrieval-cases.json"),
    [int]$WarmIterations = 3
)

$ErrorActionPreference = "Stop"
$headers = @{}
if ($ApiKey) { $headers["X-Dupi-API-Key"] = $ApiKey }
if (-not $OutputPath) {
    $OutputPath = Join-Path $PSScriptRoot "..\artifacts\rag-retrieval-benchmark-$KbId.json"
}

& (Join-Path $PSScriptRoot "rag-eval-cases.ps1") -ManifestPath $ManifestPath -KbId $KbId -BaseUrl $BaseUrl -ApiKey $ApiKey
if ($LASTEXITCODE -ne 0) { throw "Failed to reconcile the benchmark case manifest" }
$parsedManifest = Get-Content -Raw -Encoding UTF8 $ManifestPath | ConvertFrom-Json
$manifest = @($parsedManifest | ForEach-Object { $_ })
$apiCases = @(Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/knowledge-bases/$KbId/rag-eval/cases" -Headers $headers)
if ($apiCases.Count -lt 30) { throw "Benchmark requires at least 30 enabled API cases" }
$categoryCoverage = [ordered]@{}
foreach ($category in @("zh", "en", "exact", "semantic", "no_answer", "conflict")) {
    $categoryCoverage[$category] = @($manifest | Where-Object { $_.category -eq $category }).Count
}

function Invoke-Run([string]$label, [hashtable]$body) {
    $started = Get-Date
    $run = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/knowledge-bases/$KbId/rag-eval/runs" `
        -Headers $headers -ContentType "application/json" -Body ($body | ConvertTo-Json -Depth 10)
    [ordered]@{
        label = $label; runId = $run.id; gateStatus = $run.gateStatus
        profileSnapshot = $run.profileSnapshot; metrics = $run.metrics
        durationMs = [math]::Round(((Get-Date) - $started).TotalMilliseconds)
        cases = @($run.results | ForEach-Object {
            [ordered]@{
                caseKey = $_.caseKey; passed = $_.passed; hitCount = $_.hitCount; latencyMs = $_.latencyMs
                retrievalMode = $_.retrievalMode; fallbackReason = $_.fallbackReason; matchedRank = $_.matchedRank; vectorRank = $_.vectorRank
                sparseRank = $_.sparseRank; fusionRank = $_.fusionRank; rerankRank = $_.rerankRank
            }
        })
    }
}

$coldResults = @(
    Invoke-Run "VECTOR" @{ retrievalMode = "VECTOR"; useRerank = $false }
    Invoke-Run "HYBRID" @{ retrievalMode = "HYBRID"; useRerank = $false; profileId = $HybridProfileId }
    Invoke-Run "HYBRID+RERANK" @{ retrievalMode = "HYBRID"; useRerank = $true; profileId = $RerankProfileId }
)
$warmRuns = @()
for ($iteration = 1; $iteration -le $WarmIterations; $iteration++) {
    $warmRuns += ,@(
        Invoke-Run "VECTOR" @{ retrievalMode = "VECTOR"; useRerank = $false }
        Invoke-Run "HYBRID" @{ retrievalMode = "HYBRID"; useRerank = $false; profileId = $HybridProfileId }
        Invoke-Run "HYBRID+RERANK" @{ retrievalMode = "HYBRID"; useRerank = $true; profileId = $RerankProfileId }
    )
}
$results = if ($warmRuns.Count -gt 0) { $warmRuns[-1] } else { $coldResults }
$expectedModes = @("vector", "hybrid", "hybrid_rerank")
for ($index = 0; $index -lt $results.Count; $index++) {
    $actualMode = [string]$results[$index].profileSnapshot.retrievalMode
    if (-not $actualMode -and $results[$index].cases.Count -gt 0) { $actualMode = [string]$results[$index].cases[0].retrievalMode }
    if ($actualMode -ne $expectedModes[$index]) {
        throw "$($results[$index].label) executed as '$actualMode', expected '$($expectedModes[$index])'"
    }
}
if ($results[2].profileSnapshot.rerankEnabled -ne $true) {
    throw "HYBRID+RERANK requires a rerank-enabled Profile"
}
$vectorRanks = @{}
foreach ($case in $results[0].cases) { $vectorRanks[$case.caseKey] = $case.matchedRank }
foreach ($result in $results) {
    if ($result.gateStatus -eq "BLOCKED") { throw "$($result.label) was blocked by the quality gate" }
    if (@($result.cases | Where-Object { $_.fallbackReason }).Count -gt 0) {
        throw "$($result.label) used retrieval fallback"
    }
    foreach ($case in $result.cases) {
        $baselineRank = $vectorRanks[$case.caseKey]
        $case["rankDeltaVsVector"] = $(
            if ($null -ne $baselineRank -and $null -ne $case.matchedRank) { [int]$case.matchedRank - [int]$baselineRank } else { $null }
        )
    }
}
if (@($results[2].cases | Where-Object { $null -eq $_.rerankRank }).Count -gt 0) {
    throw "HYBRID+RERANK did not produce rerank rank evidence for every case"
}
$artifact = [ordered]@{
    kbId = $KbId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    caseCount = $apiCases.Count
    categoryCoverage = $categoryCoverage
    coldResults = $coldResults
    warmIterations = $WarmIterations
    warmRuns = $warmRuns
    results = $results
}
$directory = Split-Path -Parent $OutputPath
if ($directory) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
$artifact | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -Path $OutputPath
Write-Host "Benchmark written to $OutputPath"
