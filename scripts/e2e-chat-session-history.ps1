# dupi-RAG E2E: verifies persisted chat session history APIs via Nginx/API.
# Steps: health -> create KB -> upload doc -> poll ingest -> chat SSE -> list/detail/rename/delete/batch-delete sessions.
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
$script:chatSessionId = $null
$script:batchSessionId = $null

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

function Assert-SessionMissing($Sessions, $SessionId) {
    $found = $Sessions | Where-Object { $_.id -eq $SessionId } | Select-Object -First 1
    if ($found) { throw "deleted session still listed: $SessionId" }
}

$tmpChat = Join-Path $env:TEMP "dupi-e2e-chat-session-history-chat.json"
$tmpSse = Join-Path $env:TEMP "dupi-e2e-chat-session-history.sse"

try {
    Step "1. Health" {
        $r = Invoke-Json "GET" "$BaseUrl/actuator/health"
        if ($r.status -ne "UP") { throw "health not UP" }
        "UP"
    }

    Step "2. Create KB" {
        $kb = Invoke-Json "POST" "$BaseUrl/api/v1/knowledge-bases" @{
            name = "e2e-chat-history"
            description = "E2E from scripts/e2e-chat-session-history.ps1"
            chunkSize = 512
            chunkOverlap = 64
            topK = 5
        }
        if (-not $kb.id) { throw ($kb | ConvertTo-Json -Depth 8) }
        $script:kbId = $kb.id
        "kbId=$($kb.id)"
    }

    if (-not $script:kbId) { throw "kbId not set" }

    Step "3. Upload" {
        if (-not (Test-Path $SampleFile)) { throw "missing $SampleFile" }
        $raw = curl.exe -s -X POST "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/documents" -F "file=@$SampleFile"
        $d = $raw | ConvertFrom-Json
        if (-not $d.id) { throw $raw }
        $script:docId = $d.id
        "docId=$($d.id) status=$($d.status)"
    }

    Step "4. Poll ingest" {
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

    Step "5. Chat SSE creates persisted session" {
        @{
            query = "What are the core capabilities of dupi-RAG"
            stream = $true
        } | ConvertTo-Json -Depth 8 | Out-File -FilePath $tmpChat -Encoding utf8 -NoNewline

        curl.exe -s -N -X POST "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat" `
            -H "Content-Type: application/json" -H "Accept: text/event-stream" `
            -d "@$tmpChat" `
            -o $tmpSse --max-time 60

        $content = Get-Content $tmpSse -Raw -ErrorAction SilentlyContinue
        if (-not $content) { throw "empty SSE response" }
        if ($content -match 'event:error') {
            throw ($content.Substring(0, [Math]::Min(500, $content.Length)))
        }
        if ($content -notmatch 'event:token') { throw "no token events in SSE" }
        if ($content -notmatch 'event:done') { throw "no done event in SSE" }
        if ($content -match '"sessionId"\s*:\s*"([^"]+)"') {
            $script:chatSessionId = $Matches[1]
        }
        "sse_bytes=$($content.Length) sessionId=$($script:chatSessionId)"
    }

    Step "6. Chat session list" {
        $sessions = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat-sessions"
        if (-not $sessions -or $sessions.Count -lt 1) { throw "no chat sessions persisted" }
        if (-not $script:chatSessionId) {
            $script:chatSessionId = $sessions[0].id
        }
        $listed = $sessions | Where-Object { $_.id -eq $script:chatSessionId } | Select-Object -First 1
        if (-not $listed) { throw "created session not listed: $($script:chatSessionId)" }
        "sessions=$($sessions.Count) first=$($script:chatSessionId)"
    }

    Step "7. Chat session detail" {
        $detail = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat-sessions/$($script:chatSessionId)"
        if ($detail.session.id -ne $script:chatSessionId) { throw "session detail id mismatch" }
        if ($detail.messages.Count -lt 2) { throw "expected user and assistant messages" }
        $user = $detail.messages | Where-Object { $_.role -eq "USER" } | Select-Object -First 1
        $assistant = $detail.messages | Where-Object { $_.role -eq "ASSISTANT" } | Select-Object -First 1
        if (-not $user) { throw "missing persisted user message" }
        if (-not $assistant) { throw "missing persisted assistant message" }
        "messages=$($detail.messages.Count)"
    }

    Step "8. Rename chat session" {
        $renamed = Invoke-Json "PATCH" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat-sessions/$($script:chatSessionId)" @{
            title = "e2e-renamed-session"
        }
        if ($renamed.title -ne "e2e-renamed-session") { throw "rename failed" }
        $renamed.title
    }

    Step "9. Delete chat session" {
        Invoke-WebRequest -UseBasicParsing -Method DELETE -Uri "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat-sessions/$($script:chatSessionId)" -TimeoutSec 30 | Out-Null
        $sessions = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat-sessions"
        Assert-SessionMissing $sessions $script:chatSessionId
        "deleted=$($script:chatSessionId)"
    }

    Step "10. Create session for batch delete" {
        $created = Invoke-Json "POST" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat-sessions" @{
            title = "e2e-batch-delete-session"
        }
        if (-not $created.id) { throw ($created | ConvertTo-Json -Depth 8) }
        $script:batchSessionId = $created.id
        "batchSessionId=$($script:batchSessionId)"
    }

    Step "11. Batch delete chat session" {
        Invoke-Json "POST" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat-sessions/batch-delete" @{
            sessionIds = @($script:batchSessionId)
        } | Out-Null
        $sessions = Invoke-Json "GET" "$BaseUrl/api/v1/knowledge-bases/$($script:kbId)/chat-sessions"
        Assert-SessionMissing $sessions $script:batchSessionId
        "batch_deleted=$($script:batchSessionId)"
    }

    Write-Host "`n=== ALL CHAT SESSION HISTORY STEPS PASSED ===" -ForegroundColor Green
    $reportPath = Join-Path $PSScriptRoot "e2e-chat-session-history-last-run.json"
    $report = [ordered]@{
        runAt = (Get-Date).ToUniversalTime().ToString("o")
        baseUrl = $BaseUrl
        kbId = $script:kbId
        docId = $script:docId
        chatSessionId = $script:chatSessionId
        batchSessionId = $script:batchSessionId
        steps = $results
    }
    $report | ConvertTo-Json -Depth 4 | Set-Content -Path $reportPath -Encoding utf8
    Write-Host "Report: $reportPath"
    exit 0
} catch {
    Write-Host "`n=== CHAT SESSION HISTORY E2E ABORTED ===" -ForegroundColor Red
    $reportPath = Join-Path $PSScriptRoot "e2e-chat-session-history-last-run.json"
    [ordered]@{
        runAt = (Get-Date).ToUniversalTime().ToString("o")
        baseUrl = $BaseUrl
        kbId = $script:kbId
        docId = $script:docId
        chatSessionId = $script:chatSessionId
        batchSessionId = $script:batchSessionId
        steps = $results
    } | ConvertTo-Json -Depth 4 | Set-Content -Path $reportPath -Encoding utf8
    exit 1
} finally {
    Remove-Item $tmpChat, $tmpSse -ErrorAction SilentlyContinue
}
