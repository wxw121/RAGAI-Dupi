param(
    [Parameter(Mandatory = $true)][string]$KbId,
    [Parameter(Mandatory = $true)][string]$HybridProfileId,
    [Parameter(Mandatory = $true)][string]$RerankProfileId,
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ApiKey = "",
    [string]$OutputPath = "",
    [string]$ManifestPath = (Join-Path $PSScriptRoot "..\benchmarks\v1.6b\retrieval-cases.json"),
    [string]$CorpusPath = (Join-Path $PSScriptRoot "..\benchmarks\v1.6b\corpus"),
    [int]$WarmIterations = 3,
    [switch]$SkipCaseReconcile,
    [string]$ExperimentLabel = "",
    [int]$TopKOverride = 0
)

$ErrorActionPreference = "Stop"
$headers = @{}
if ($ApiKey) { $headers["X-Dupi-API-Key"] = $ApiKey }
if (-not $OutputPath) {
    $OutputPath = Join-Path $PSScriptRoot "..\artifacts\rag-retrieval-benchmark-$KbId.json"
}

if (-not $SkipCaseReconcile) {
    & (Join-Path $PSScriptRoot "rag-eval-cases.ps1") -ManifestPath $ManifestPath -CorpusPath $CorpusPath -KbId $KbId -BaseUrl $BaseUrl -ApiKey $ApiKey
    if ($LASTEXITCODE -ne 0) { throw "Failed to reconcile the benchmark case manifest" }
}
$parsedManifest = Get-Content -Raw -Encoding UTF8 $ManifestPath | ConvertFrom-Json
$manifest = @($parsedManifest | ForEach-Object { $_ })
$apiCases = @(Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/v1/knowledge-bases/$KbId/rag-eval/cases" -Headers $headers)
if ($apiCases.Count -ne 100) { throw "V1.6b benchmark requires exactly 100 enabled API cases" }
$categoryCoverage = [ordered]@{}
foreach ($category in @("REAL_QUERY", "HARD_NEGATIVE", "MULTI_DOCUMENT", "AMBIGUOUS")) {
    $categoryCoverage[$category] = @($manifest | Where-Object { $_.category -eq $category }).Count
}

function Invoke-Run([string]$label, [hashtable]$body) {
    $started = Get-Date
    $runBody = [ordered]@{}
    foreach ($key in $body.Keys) { $runBody[$key] = $body[$key] }
    $normalizedExperimentLabel = "$ExperimentLabel".Trim()
    if ($normalizedExperimentLabel) { $runBody["experimentLabel"] = $normalizedExperimentLabel }
    if ($TopKOverride -lt 0 -or $TopKOverride -gt 50) { throw "TopKOverride must be 0 or between 1 and 50" }
    if ($TopKOverride -gt 0) { $runBody["topKOverride"] = $TopKOverride }
    $run = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/knowledge-bases/$KbId/rag-eval/runs" `
        -Headers $headers -ContentType "application/json" -Body ($runBody | ConvertTo-Json -Depth 10)
    [ordered]@{
        label = $label; runId = $run.id; gateStatus = $run.gateStatus
        experimentLabel = $run.profileSnapshot.experimentLabel
        topKOverride = $run.profileSnapshot.topKOverride
        releaseGate = $run.metrics.releaseGate
        profileSnapshot = $run.profileSnapshot; metrics = $run.metrics
        durationMs = [math]::Round(((Get-Date) - $started).TotalMilliseconds)
        cases = @($run.results | ForEach-Object {
            $expectedSources = @($_.expectedFileName) + @($_.expectedFileNames)
            $expectedSources = @($expectedSources | Where-Object { $_ -and "$($_)".Trim() } | ForEach-Object { "$($_)".Trim() } | Sort-Object -Unique)
            [ordered]@{
                caseKey = $_.caseKey; category = $_.category; passed = $_.passed; hitCount = $_.hitCount; latencyMs = $_.latencyMs
                failureCategories = @($_.failureCategories); expectedFileName = $_.expectedFileName; expectedFileNames = @($_.expectedFileNames)
                expectedSources = $expectedSources; matchedFileNames = @($_.matchedFileNames)
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
if (@($results[2].cases | Where-Object { $_.hitCount -gt 0 -and $null -eq $_.rerankRank }).Count -gt 0) {
    throw "HYBRID+RERANK did not produce rerank rank evidence for every case with hits"
}
$artifact = [ordered]@{
    kbId = $KbId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    experiment = [ordered]@{
        experimentLabel = $(if ("$ExperimentLabel".Trim()) { "$ExperimentLabel".Trim() } else { $null })
        topKOverride = $(if ($TopKOverride -gt 0) { $TopKOverride } else { $null })
    }
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
