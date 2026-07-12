# dupi-RAG Web maintenance E2E: mirrors browser-side document and index maintenance actions via Nginx/API.
# Steps: health -> create KB -> batch upload -> poll ingest -> reindex -> list/retry ingest jobs -> list/retry vector cleanup tasks.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$SampleFile = "$PSScriptRoot\..\examples\sample-knowledge.md",
    [int]$PollSeconds = 120,
    [int]$PollInterval = 3
)

$ErrorActionPreference = "Stop"
$results = [System.Collections.Generic.List[object]]::new()
$script:kbId = $null
$script:docIds = @()

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

function Wait-ForDocsCompleted() {
    $deadline = (Get-Date).AddSeconds($PollSeconds)
    $last = ""
    while ((Get-Date) -lt $deadline) {
        $docs = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/documents"
        $tracked = $docs | Where-Object { $script:docIds -contains $_.id }
        if ($tracked.Count -lt $script:docIds.Count) { throw "some uploaded docs are missing" }
        $last = (($tracked | ForEach-Object { "$($_.id.Substring(0, 8))=$($_.status)" }) -join ",")
        $failed = $tracked | Where-Object { $_.status -eq "FAILED" } | Select-Object -First 1
        if ($failed) { throw "ingest FAILED: $($failed.errorMessage)" }
        $allDone = ($tracked | Where-Object { $_.status -ne "COMPLETED" }).Count -eq 0
        if ($allDone) { return "COMPLETED count=$($tracked.Count)" }
        Start-Sleep -Seconds $PollInterval
    }
    throw "timeout last=$last"
}

$tempCopy = Join-Path $env:TEMP "dupi-web-maintenance-copy.md"

try {
    Step "1. Health" {
        $health = Invoke-Json "GET" "$BaseUrl/actuator/health"
        if ($health.status -ne "UP") { throw "health not UP" }
        "UP"
    }

    Step "2. Create KB" {
        $kb = Invoke-Json "POST" "$BaseUrl/api/v1/knowledge-bases" @{
            name = "e2e-web-maintenance"
            description = "E2E from scripts/e2e-web-maintenance-flow.ps1"
            chunkSize = 512
            chunkOverlap = 64
            topK = 5
        }
        if (-not $kb.id) { throw ($kb | ConvertTo-Json -Depth 8) }
        $script:kbId = $kb.id
        "kbId=$($script:kbId)"
    }

    Step "3. Batch upload" {
        if (-not (Test-Path $SampleFile)) { throw "missing $SampleFile" }
        Copy-Item -LiteralPath $SampleFile -Destination $tempCopy -Force
        $raw = curl.exe -s -X POST "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/documents/batch" `
            -F "files=@$SampleFile" `
            -F "files=@$tempCopy"
        $batch = $raw | ConvertFrom-Json
        if ($batch.total -ne 2 -or $batch.succeeded -lt 1) { throw $raw }
        $script:docIds = @($batch.results | Where-Object { $_.success -eq $true -and $_.document.id } | ForEach-Object { $_.document.id })
        if ($script:docIds.Count -lt 1) { throw "no uploaded document ids in batch response" }
        "total=$($batch.total) succeeded=$($batch.succeeded)"
    }

    Step "4. Poll batch ingest" {
        Wait-ForDocsCompleted
    }

    Step "5. Reindex" {
        $jobs = Invoke-Json "POST" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/reindex"
        if (-not $jobs -or $jobs.Count -lt $script:docIds.Count) { throw "expected reindex jobs for uploaded docs" }
        "jobs=$($jobs.Count)"
    }

    Step "6. List and retry eligible ingest jobs" {
        $jobs = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/ingest-jobs"
        if (-not $jobs) { throw "no ingest jobs returned" }
        $eligible = $jobs | Where-Object { $_.status -eq "FAILED" -or $_.status -eq "DEAD_LETTER" } | Select-Object -First 1
        if ($eligible) {
            Invoke-Json "POST" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/ingest-jobs/$($eligible.id)/retry" | Out-Null
            return "retried=$($eligible.id)"
        }
        "eligible=0 jobs=$($jobs.Count)"
    }

    Step "7. List and retry vector cleanup tasks" {
        $tasks = Invoke-Json "GET" "$BaseUrl/api/v1/ops/vector-cleanup-tasks"
        $count = if ($tasks) { $tasks.Count } else { 0 }
        $eligible = $tasks | Where-Object { $_.status -eq "PENDING" -or $_.status -eq "FAILED" } | Select-Object -First 1
        if ($eligible) {
            Invoke-Json "POST" "$BaseUrl/api/v1/ops/vector-cleanup-tasks/$($eligible.id)/retry" | Out-Null
            return "tasks=$count retried=$($eligible.id)"
        }
        "tasks=$count eligible=0"
    }

    Write-Host "`n=== WEB MAINTENANCE FLOW PASSED ===" -ForegroundColor Green
    $reportPath = Join-Path $PSScriptRoot "e2e-web-maintenance-last-run.json"
    [ordered]@{
        runAt = (Get-Date).ToUniversalTime().ToString("o")
        baseUrl = $BaseUrl
        kbId = $script:kbId
        docIds = $script:docIds
        steps = $results
    } | ConvertTo-Json -Depth 8 | Set-Content -Path $reportPath -Encoding utf8
    Write-Host "Report: $reportPath"
    exit 0
} catch {
    Write-Host "`n=== WEB MAINTENANCE FLOW FAILED ===" -ForegroundColor Red
    $reportPath = Join-Path $PSScriptRoot "e2e-web-maintenance-last-run.json"
    [ordered]@{
        runAt = (Get-Date).ToUniversalTime().ToString("o")
        baseUrl = $BaseUrl
        kbId = $script:kbId
        docIds = $script:docIds
        steps = $results
    } | ConvertTo-Json -Depth 8 | Set-Content -Path $reportPath -Encoding utf8
    exit 1
} finally {
    Remove-Item -LiteralPath $tempCopy -ErrorAction SilentlyContinue
}
