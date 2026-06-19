# dupi-RAG E2E — mirrors Web console button flows (via Nginx :8080)
# Steps: health → list KB → create KB → upload doc → poll ingest → retrieve → chat SSE
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

function Step($name, [scriptblock]$Action) {
    Write-Host "`n==> $name" -ForegroundColor Cyan
    try {
        $out = & $Action
        $results.Add([ordered]@{ step = $name; status = "PASS"; detail = "$out" })
        Write-Host "PASS: $name — $out" -ForegroundColor Green
        return $out
    } catch {
        $msg = $_.Exception.Message
        $results.Add([ordered]@{ step = $name; status = "FAIL"; detail = $msg })
        Write-Host "FAIL: $name — $msg" -ForegroundColor Red
        throw
    }
}

$tmpCreate = Join-Path $env:TEMP "dupi-e2e-create.json"
$tmpRetrieve = Join-Path $env:TEMP "dupi-e2e-retrieve.json"

try {
    Step "1. Health (AppLayout 服务指示灯)" {
        $r = curl.exe -s "$BaseUrl/actuator/health" | ConvertFrom-Json
        if ($r.status -ne "UP") { throw "health not UP" }
        "UP"
    }

    Step "2. List KB (首页加载)" {
        $list = curl.exe -s "$BaseUrl/api/v1/knowledge-bases" | ConvertFrom-Json
        "count=$($list.Count)"
    }

    Step "3. Create KB (新建知识库)" {
        '{"name":"e2e-auto","description":"E2E from scripts/e2e-main-flow.ps1","chunkSize":512,"chunkOverlap":64,"topK":5}' |
            Out-File -FilePath $tmpCreate -Encoding utf8 -NoNewline
        $raw = curl.exe -s -X POST "$BaseUrl/api/v1/knowledge-bases" -H "Content-Type: application/json" -d "@$tmpCreate"
        $kb = $raw | ConvertFrom-Json
        if (-not $kb.id) { throw $raw }
        $script:kbId = $kb.id
        "kbId=$($kb.id)"
    }

    if (-not $script:kbId) { throw "kbId not set" }

    Step "4. KB detail (进入知识库 / 管理文档)" {
        $d = curl.exe -s "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)" | ConvertFrom-Json
        if ($d.id -ne $script:kbId) { throw "detail mismatch" }
        $d.name
    }

    Step "5. Upload (文档管理 上传)" {
        if (-not (Test-Path $SampleFile)) { throw "missing $SampleFile" }
        $raw = curl.exe -s -X POST "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/documents" -F "file=@$SampleFile"
        $d = $raw | ConvertFrom-Json
        if (-not $d.id) { throw $raw }
        $script:docId = $d.id
        "docId=$($d.id) status=$($d.status)"
    }

    Step "6. Poll ingest (等待 COMPLETED)" {
        $deadline = (Get-Date).AddSeconds($PollSeconds)
        $last = ""
        while ((Get-Date) -lt $deadline) {
            $docs = curl.exe -s "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/documents" | ConvertFrom-Json
            $d = $docs | Where-Object { $_.id -eq $script:docId } | Select-Object -First 1
            if (-not $d) { throw "document not found" }
            $last = $d.status
            if ($d.status -eq "COMPLETED") { return "COMPLETED" }
            if ($d.status -eq "FAILED") {
                $job = curl.exe -s "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/documents/$($script:docId)/ingest-job" | ConvertFrom-Json
                throw "ingest FAILED: $($d.errorMessage) job=$($job.errorMessage)"
            }
            Start-Sleep -Seconds $PollInterval
        }
        throw "timeout last=$last"
    }

    Step "7. Retrieve (检索调试 API)" {
        '{"query":"dupi-RAG 支持哪些文档格式","topK":3}' |
            Out-File -FilePath $tmpRetrieve -Encoding utf8 -NoNewline
        $raw = curl.exe -s -X POST "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/retrieve" `
            -H "Content-Type: application/json" -d "@$tmpRetrieve"
        if ($raw -match '"error"') { throw $raw }
        $r = $raw | ConvertFrom-Json
        "hits=$($r.hits.Count)"
    }

    Step "8. Chat SSE (智能问答 去问答)" {
        $outFile = Join-Path $env:TEMP "dupi-e2e-chat.sse"
        $chatBody = Join-Path $env:TEMP "dupi-e2e-chat.json"
        '{"query":"dupi-RAG 的核心能力有哪些","stream":true}' |
            Out-File -FilePath $chatBody -Encoding utf8 -NoNewline
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
