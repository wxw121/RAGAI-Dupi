# dupi-RAG E2E: mirrors Web console button flows via Nginx/API.
# Steps: health -> list KB -> create KB -> upload doc -> poll ingest -> retrieve -> chat SSE.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$SampleFile = "$PSScriptRoot\..\examples\sample-knowledge.md",
    [int]$PollSeconds = 120,
    [int]$PollInterval = 3
)

$ErrorActionPreference = "Stop"
$results = [System.Collections.Generic.List[object]]::new()
$script:kbId = $null
$script:docId = $null

function Invoke-Json($Method, $Uri, $Body = $null) {
    $params = @{
        Method = $Method
        Uri = $Uri
        TimeoutSec = 60
    }

    if ($null -ne $Body) {
        $params.ContentType = "application/json; charset=utf-8"
        $params.Body = ($Body | ConvertTo-Json -Depth 8)
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

$tmpCreate = Join-Path $env:TEMP "dupi-e2e-create.json"
$tmpRetrieve = Join-Path $env:TEMP "dupi-e2e-retrieve.json"

try {
    Step "1. Health" {
        $r = Invoke-Json "GET" "$BaseUrl/actuator/health"
        if ($r.status -ne "UP") { throw "health not UP" }
        "UP"
    }

    Step "2. List KB" {
        $list = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases"
        "count=$($list.Count)"
    }

    Step "3. Create KB" {
        $kb = Invoke-Json "POST" "$BaseUrl/api/v1/knowledge-bases" @{
            name = "e2e-auto"
            description = "E2E from scripts/e2e-main-flow.ps1"
            chunkSize = 512
            chunkOverlap = 64
            topK = 5
        }
        if (-not $kb.id) { throw ($kb | ConvertTo-Json -Depth 8) }
        $script:kbId = $kb.id
        "kbId=$($kb.id)"
    }

    if (-not $script:kbId) { throw "kbId not set" }

    Step "4. KB detail" {
        $d = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)"
        if ($d.id -ne $script:kbId) { throw "detail mismatch" }
        $d.name
    }

    Step "5. Upload" {
        if (-not (Test-Path $SampleFile)) { throw "missing $SampleFile" }
        $raw = curl.exe -s -X POST "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/documents" -F "file=@$SampleFile"
        $d = $raw | ConvertFrom-Json
        if (-not $d.id) { throw $raw }
        $script:docId = $d.id
        "docId=$($d.id) status=$($d.status)"
    }

    Step "6. Poll ingest" {
        $deadline = (Get-Date).AddSeconds($PollSeconds)
        $last = ""
        while ((Get-Date) -lt $deadline) {
            $docs = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/documents"
            $d = $docs | Where-Object { $_.id -eq $script:docId } | Select-Object -First 1
            if (-not $d) { throw "document not found" }
            $last = $d.status
            if ($d.status -eq "COMPLETED") { return "COMPLETED" }
            if ($d.status -eq "FAILED") {
                $job = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/documents/$($script:docId)/ingest-job"
                throw "ingest FAILED: $($d.errorMessage) job=$($job.errorMessage)"
            }
            Start-Sleep -Seconds $PollInterval
        }
        throw "timeout last=$last"
    }

    Step "7. Retrieve" {
        $r = Invoke-Json "POST" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/retrieve" @{
            query = "dupi-RAG supports which document formats"
            topK = 3
        }
        "hits=$($r.hits.Count)"
    }

    Step "8. Chat SSE" {
        $outFile = Join-Path $env:TEMP "dupi-e2e-chat.sse"
        $chatBody = Join-Path $env:TEMP "dupi-e2e-chat.json"
        @{
            query = "What are the core capabilities of dupi-RAG"
            stream = $true
        } | ConvertTo-Json -Depth 8 | Out-File -FilePath $chatBody -Encoding utf8 -NoNewline

        curl.exe -s -N -X POST "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat" `
            -H "Content-Type: application/json" -H "Accept: text/event-stream" `
            -d "@$chatBody" `
            -o $outFile --max-time 60

        $content = Get-Content $outFile -Raw -ErrorAction SilentlyContinue
        if (-not $content) { throw "empty SSE response" }
        if ($content -match 'event:error') {
            throw ($content.Substring(0, [Math]::Min(500, $content.Length)))
        }
        if ($content -notmatch 'event:token') { throw "no token events in SSE" }
        "sse_bytes=$($content.Length)"
    }

    Write-Host "`n=== ALL STEPS PASSED ===" -ForegroundColor Green
    $reportPath = Join-Path $PSScriptRoot "e2e-last-run.json"
    $report = [ordered]@{
        runAt = (Get-Date).ToUniversalTime().ToString("o")
        baseUrl = $BaseUrl
        kbId = $script:kbId
        docId = $script:docId
        steps = $results
    }
    $report | ConvertTo-Json -Depth 4 | Set-Content -Path $reportPath -Encoding utf8
    Write-Host "Report: $reportPath"
    exit 0
} catch {
    Write-Host "`n=== E2E ABORTED ===" -ForegroundColor Red
    $reportPath = Join-Path $PSScriptRoot "e2e-last-run.json"
    [ordered]@{
        runAt = (Get-Date).ToUniversalTime().ToString("o")
        baseUrl = $BaseUrl
        kbId = $script:kbId
        docId = $script:docId
        steps = $results
    } | ConvertTo-Json -Depth 4 | Set-Content -Path $reportPath -Encoding utf8
    exit 1
} finally {
    Remove-Item $tmpCreate, $tmpRetrieve -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $env:TEMP "dupi-e2e-chat.json") -ErrorAction SilentlyContinue
}
