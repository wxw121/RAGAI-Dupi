param(
    [string]$ManifestPath = (Join-Path $PSScriptRoot "..\benchmarks\v1.6b\retrieval-cases.json"),
    [string]$CorpusPath = (Join-Path $PSScriptRoot "..\benchmarks\v1.6b\corpus"),
    [switch]$ValidateOnly,
    [switch]$SkipCorpusSync,
    [string]$KbId = "",
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ApiKey = ""
)

$ErrorActionPreference = "Stop"

function Test-Manifest([object[]]$Cases, [string]$ExpectedCorpusPath) {
    $requiredCounts = [ordered]@{
        REAL_QUERY = 40
        HARD_NEGATIVE = 20
        MULTI_DOCUMENT = 20
        AMBIGUOUS = 20
    }
    foreach ($case in $Cases) {
        if (-not $case.caseKey -or -not $case.query -or -not $case.category) {
            throw "Each case requires caseKey, category, and query"
        }
        $expectedFiles = @($case.expectedFileName) + @($case.expectedFileNames | ForEach-Object { $_ })
        $expectedFiles = @($expectedFiles | Where-Object { $_ -and "$($_)".Trim() } | ForEach-Object { "$($_)".Trim() } | Sort-Object -Unique)
        $tokens = @($case.mustContainAny | Where-Object { $_ -and "$($_)".Trim() })
        switch ($case.category) {
            "REAL_QUERY" {
                if (([int]$case.minHits -lt 1) -or ($expectedFiles.Count -eq 0 -and $tokens.Count -eq 0)) {
                    throw "REAL_QUERY cases require positive retrieval assertions"
                }
            }
            "HARD_NEGATIVE" {
                if ([int]$case.minHits -ne 0 -or $expectedFiles.Count -ne 0 -or $tokens.Count -ne 0) {
                    throw "HARD_NEGATIVE cases require minHits=0 and no positive evidence assertion"
                }
            }
            "MULTI_DOCUMENT" {
                if ([int]$case.minHits -lt 2 -or $expectedFiles.Count -lt 2) {
                    throw "MULTI_DOCUMENT cases require minHits>=2 and at least two expected files"
                }
            }
            "AMBIGUOUS" {
                if ($expectedFiles.Count -eq 0 -or $tokens.Count -eq 0) {
                    throw "AMBIGUOUS cases require an expected source and disambiguating tokens"
                }
            }
            default { throw "Unsupported category: $($case.category)" }
        }
        if ($ExpectedCorpusPath) {
            foreach ($fileName in $expectedFiles) {
                if (-not (Test-Path -LiteralPath (Join-Path $ExpectedCorpusPath $fileName))) {
                    throw "Expected source file is missing from corpus: $fileName"
                }
            }
        }
    }
    if ($Cases.Count -ne 100) { throw "V1.6b RAG benchmark requires exactly 100 cases" }
    $keys = @($Cases | ForEach-Object { $_.caseKey })
    if (@($keys | Sort-Object -Unique).Count -ne $keys.Count) { throw "caseKey values must be unique" }
    foreach ($category in $requiredCounts.Keys) {
        $actual = @($Cases | Where-Object { $_.category -eq $category }).Count
        if ($actual -ne $requiredCounts[$category]) {
            throw "V1.6b requires $($requiredCounts[$category]) $category cases, got $actual"
        }
    }
}

try {
    $parsed = Get-Content -Raw -Encoding UTF8 $ManifestPath | ConvertFrom-Json
    $cases = @($parsed | ForEach-Object { $_ })
    Test-Manifest $cases $CorpusPath
    if ($ValidateOnly) { Write-Host "Validated $($cases.Count) RAG evaluation cases"; return }
    if (-not $KbId) { throw "KbId is required unless ValidateOnly is set" }
    $headers = @{}
    if ($ApiKey) { $headers["X-Dupi-API-Key"] = $ApiKey }
    $base = "$BaseUrl/api/v1/knowledge-bases/$KbId/rag-eval/cases"
    if (-not $SkipCorpusSync) {
        $documentsUrl = "$BaseUrl/api/v1/knowledge-bases/$KbId/documents"
        $documents = @(Invoke-RestMethod -Method Get -Uri $documentsUrl -Headers $headers)
        foreach ($file in Get-ChildItem -File $CorpusPath) {
            $existingDocument = $documents | Where-Object { $_.fileName -eq $file.Name } | Select-Object -First 1
            if ($existingDocument) {
                if ([long]$existingDocument.fileSize -ne [long]$file.Length) {
                    throw "Corpus file differs for $($file.Name): knowledge base has $($existingDocument.fileSize) bytes, V1.6b corpus has $($file.Length) bytes. Refresh the benchmark knowledge base before syncing."
                }
                continue
            }
            $curlArgs = @("-sS", "--fail", "-X", "POST", $documentsUrl, "-F", "file=@$($file.FullName)")
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
    $manifestKeys = @($cases | ForEach-Object { $_.caseKey })
    $unexpected = @($existing | Where-Object { $manifestKeys -notcontains $_.caseKey })
    if ($unexpected.Count -gt 0) {
        throw "Knowledge base contains evaluation cases outside the V1.6b manifest: $($unexpected.caseKey -join ', ')"
    }
    foreach ($case in $cases) {
        $body = [ordered]@{
            caseKey = $case.caseKey; category = $case.category; query = $case.query; minHits = $case.minHits; topK = $case.topK
            expectedFileName = $case.expectedFileName; expectedFileNames = @($case.expectedFileNames); mustContainAny = @($case.mustContainAny)
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
