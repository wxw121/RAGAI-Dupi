# dupi-RAG RAG regression evaluation.
# Creates or reuses a KB, uploads sample knowledge, then validates retrieval cases.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$CasesFile = "$PSScriptRoot\..\examples\rag-eval-cases.json",
    [string]$SampleFile = "$PSScriptRoot\..\examples\sample-knowledge.md",
    [string]$KbId = "",
    [int]$PollSeconds = 120,
    [int]$PollInterval = 3
)

$ErrorActionPreference = "Stop"
$results = [System.Collections.Generic.List[object]]::new()
$script:kbId = $KbId
$script:docId = $null

function Invoke-Json($Method, $Uri, $Body = $null) {
    $params = @{
        Method = $Method
        Uri = $Uri
        TimeoutSec = 60
    }
    if ($null -ne $Body) {
        $params.ContentType = "application/json; charset=utf-8"
        $params.Body = ($Body | ConvertTo-Json -Depth 10)
    }
    Invoke-RestMethod @params
}

function Step($Name, [scriptblock]$Action) {
    Write-Host "`n==> $Name" -ForegroundColor Cyan
    try {
        $out = & $Action
        $results.Add([ordered]@{ step = $Name; status = "PASS"; detail = "$out" })
        Write-Host "PASS: $Name - $out" -ForegroundColor Green
        return $out
    } catch {
        $msg = $_.Exception.Message
        $results.Add([ordered]@{ step = $Name; status = "FAIL"; detail = $msg })
        Write-Host "FAIL: $Name - $msg" -ForegroundColor Red
        throw
    }
}

function Join-HitText($Hits) {
    (($Hits | ForEach-Object {
        @($_.fileName, $_.content, ($_.metadata | ConvertTo-Json -Depth 6 -Compress)) -join " "
    }) -join "`n")
}

try {
    Step "1. Load cases" {
        if (-not (Test-Path $CasesFile)) { throw "missing cases file: $CasesFile" }
        if (-not (Test-Path $SampleFile)) { throw "missing sample file: $SampleFile" }
        $script:cases = Get-Content $CasesFile -Raw | ConvertFrom-Json
        if (-not $script:cases -or $script:cases.Count -lt 1) { throw "no eval cases" }
        "cases=$($script:cases.Count)"
    }

    Step "2. Health" {
        $health = Invoke-Json "GET" "$BaseUrl/actuator/health"
        if ($health.status -ne "UP") { throw "health not UP" }
        "UP"
    }

    if (-not $script:kbId) {
        Step "3. Create eval KB" {
            $kb = Invoke-Json "POST" "$BaseUrl/api/v1/knowledge-bases" @{
                name = "rag-eval"
                description = "Created by scripts/rag-regression-eval.ps1"
                chunkSize = 512
                chunkOverlap = 64
                topK = 5
            }
            if (-not $kb.id) { throw ($kb | ConvertTo-Json -Depth 8) }
            $script:kbId = $kb.id
            "kbId=$($script:kbId)"
        }

        Step "4. Upload sample" {
            $raw = curl.exe -s -X POST "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/documents" -F "file=@$SampleFile"
            $doc = $raw | ConvertFrom-Json
            if (-not $doc.id) { throw $raw }
            $script:docId = $doc.id
            "docId=$($script:docId) status=$($doc.status)"
        }

        Step "5. Poll ingest" {
            $deadline = (Get-Date).AddSeconds($PollSeconds)
            $last = ""
            while ((Get-Date) -lt $deadline) {
                $docs = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/documents"
                $doc = $docs | Where-Object { $_.id -eq $script:docId } | Select-Object -First 1
                if (-not $doc) { throw "document not found" }
                $last = $doc.status
                if ($doc.status -eq "COMPLETED") { return "COMPLETED" }
                if ($doc.status -eq "FAILED") { throw "ingest FAILED: $($doc.errorMessage)" }
                Start-Sleep -Seconds $PollInterval
            }
            throw "timeout last=$last"
        }
    } else {
        Step "3. Reuse eval KB" {
            $kb = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases/$script:kbId"
            if ($kb.id -ne $script:kbId) { throw "KB detail mismatch" }
            "kbId=$script:kbId"
        }
    }

    foreach ($case in $script:cases) {
        Step "Eval: $($case.id)" {
            $topK = if ($case.topK) { [int]$case.topK } else { 5 }
            $retrieval = Invoke-Json "POST" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/retrieve" @{
                query = $case.query
                topK = $topK
            }
            $hitCount = if ($retrieval.hits) { $retrieval.hits.Count } else { 0 }
            if ($hitCount -lt [int]$case.minHits) {
                throw "expected at least $($case.minHits) hits, got $hitCount"
            }
            if ($case.expectedFileName) {
                $matchedFile = $retrieval.hits | Where-Object { $_.fileName -eq $case.expectedFileName } | Select-Object -First 1
                if (-not $matchedFile) { throw "missing expected file $($case.expectedFileName)" }
            }
            if ($case.mustContainAny) {
                $joined = Join-HitText $retrieval.hits
                $matched = $false
                foreach ($token in $case.mustContainAny) {
                    if ($joined -like "*$token*") {
                        $matched = $true
                        break
                    }
                }
                if (-not $matched) {
                    throw "hits did not contain any expected token: $($case.mustContainAny -join ', ')"
                }
            }
            "hits=$hitCount"
        }
    }

    Write-Host "`n=== RAG REGRESSION EVAL PASSED ===" -ForegroundColor Green
    $reportPath = Join-Path $PSScriptRoot "rag-regression-eval-last-run.json"
    [ordered]@{
        runAt = (Get-Date).ToUniversalTime().ToString("o")
        baseUrl = $BaseUrl
        kbId = $script:kbId
        docId = $script:docId
        casesFile = $CasesFile
        steps = $results
    } | ConvertTo-Json -Depth 8 | Set-Content -Path $reportPath -Encoding utf8
    Write-Host "Report: $reportPath"
    exit 0
} catch {
    Write-Host "`n=== RAG REGRESSION EVAL FAILED ===" -ForegroundColor Red
    $reportPath = Join-Path $PSScriptRoot "rag-regression-eval-last-run.json"
    [ordered]@{
        runAt = (Get-Date).ToUniversalTime().ToString("o")
        baseUrl = $BaseUrl
        kbId = $script:kbId
        docId = $script:docId
        casesFile = $CasesFile
        steps = $results
    } | ConvertTo-Json -Depth 8 | Set-Content -Path $reportPath -Encoding utf8
    exit 1
}
