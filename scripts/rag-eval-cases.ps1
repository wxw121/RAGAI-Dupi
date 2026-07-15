param(
    [string]$ManifestPath = (Join-Path $PSScriptRoot "..\benchmarks\v1.3\retrieval-cases.json"),
    [string]$CorpusPath = (Join-Path $PSScriptRoot "..\benchmarks\v1.3\corpus"),
    [switch]$ValidateOnly,
    [switch]$SkipCorpusSync,
    [string]$KbId = "",
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ApiKey = ""
)

$ErrorActionPreference = "Stop"

function Test-Manifest([object[]]$Cases) {
    $required = @("zh", "en", "exact", "semantic", "no_answer", "conflict")
    foreach ($case in $Cases) {
        if (-not $case.caseKey -or -not $case.query) { throw "Each case requires caseKey and query" }
        if ($case.category -eq "no_answer" -and ([int]$case.minHits -ne 0 -or $case.expectedFileName -or @($case.mustContainAny).Count -ne 0)) {
            throw "no_answer cases require minHits=0 and no positive evidence assertion"
        }
        if ($case.category -eq "conflict" -and (-not $case.expectedFileName -or @($case.mustContainAny).Count -eq 0)) {
            throw "conflict cases require expectedFileName and disambiguating tokens"
        }
    }
    if ($Cases.Count -lt 30) { throw "RAG benchmark requires at least 30 cases" }
    $keys = @($Cases | ForEach-Object { $_.caseKey })
    if (@($keys | Sort-Object -Unique).Count -ne $keys.Count) { throw "caseKey values must be unique" }
    foreach ($category in $required) {
        if (@($Cases | Where-Object { $_.category -eq $category }).Count -eq 0) {
            throw "Missing required category: $category"
        }
    }
}

try {
    $parsed = Get-Content -Raw -Encoding UTF8 $ManifestPath | ConvertFrom-Json
    $cases = @($parsed | ForEach-Object { $_ })
    Test-Manifest $cases
    if ($ValidateOnly) { Write-Host "Validated $($cases.Count) RAG evaluation cases"; return }
    if (-not $KbId) { throw "KbId is required unless ValidateOnly is set" }
    $headers = @{}
    if ($ApiKey) { $headers["X-Dupi-API-Key"] = $ApiKey }
    $base = "$BaseUrl/api/v1/knowledge-bases/$KbId/rag-eval/cases"
    if (-not $SkipCorpusSync) {
        $documentsUrl = "$BaseUrl/api/v1/knowledge-bases/$KbId/documents"
        $documents = @(Invoke-RestMethod -Method Get -Uri $documentsUrl -Headers $headers)
        foreach ($file in Get-ChildItem -File $CorpusPath) {
            if ($documents.fileName -contains $file.Name) { continue }
            $curlArgs = @("-sS", "-X", "POST", $documentsUrl, "-F", "file=@$($file.FullName)")
            if ($ApiKey) { $curlArgs += @("-H", "X-Dupi-API-Key: $ApiKey") }
            & curl.exe @curlArgs | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Failed to upload corpus file: $($file.Name)" }
        }
        $deadline = (Get-Date).AddMinutes(5)
        do {
            $documents = @(Invoke-RestMethod -Method Get -Uri $documentsUrl -Headers $headers)
            $corpusNames = @(Get-ChildItem -File $CorpusPath | ForEach-Object { $_.Name })
            $pending = @($documents | Where-Object { $corpusNames -contains $_.fileName -and $_.status -in @("PENDING", "PROCESSING") })
            $failed = @($documents | Where-Object { $corpusNames -contains $_.fileName -and $_.status -eq "FAILED" })
            if ($failed.Count -gt 0) { throw "Corpus ingestion failed: $($failed.fileName -join ', ')" }
            if ($pending.Count -gt 0) { Start-Sleep -Seconds 2 }
        } while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline)
        if ($pending.Count -gt 0) { throw "Timed out waiting for corpus ingestion" }
    }
    $existing = @(Invoke-RestMethod -Method Get -Uri $base -Headers $headers)
    foreach ($case in $cases) {
        $body = [ordered]@{
            caseKey = $case.caseKey; query = $case.query; minHits = $case.minHits; topK = $case.topK
            expectedFileName = $case.expectedFileName; mustContainAny = @($case.mustContainAny)
        } | ConvertTo-Json -Depth 5
        $current = $existing | Where-Object { $_.caseKey -eq $case.caseKey } | Select-Object -First 1
        if ($current) {
            Invoke-RestMethod -Method Patch -Uri "$base/$($current.id)" -Headers $headers -ContentType "application/json" -Body $body | Out-Null
        } else {
            Invoke-RestMethod -Method Post -Uri $base -Headers $headers -ContentType "application/json" -Body $body | Out-Null
        }
    }
    Write-Host "Reconciled $($cases.Count) RAG evaluation cases"
    return
} catch {
    Write-Error $_
    exit 1
}
