param(
    [string]$ApiBaseUrl = $env:DUPI_API_BASE_URL,
    [string]$KnowledgeBaseId = $env:DUPI_RAG_KB_ID,
    [string]$BearerToken = $env:DUPI_BEARER_TOKEN,
    [string]$ApiKey = $env:DUPI_API_KEY,
    [string]$RunResponsePath,
    [string]$PolicyResponsePath,
    [switch]$UseRerank
)

$ErrorActionPreference = "Stop"

if (-not $ApiBaseUrl) {
    $ApiBaseUrl = "http://localhost:8080"
}
if (($RunResponsePath -and -not $PolicyResponsePath) -or ($PolicyResponsePath -and -not $RunResponsePath)) {
    [Console]::Error.WriteLine("RunResponsePath and PolicyResponsePath must be provided together.")
    exit 2
}
if (-not $RunResponsePath -and -not $KnowledgeBaseId) {
    [Console]::Error.WriteLine("KnowledgeBaseId or DUPI_RAG_KB_ID is required.")
    exit 2
}

$headers = @{}
if ($BearerToken) {
    $headers.Authorization = "Bearer $BearerToken"
} elseif ($ApiKey) {
    $headers["X-Dupi-API-Key"] = $ApiKey
}

$base = $ApiBaseUrl.TrimEnd("/")
$resource = "$base/api/v1/knowledge-bases/$KnowledgeBaseId/rag-eval"

try {
    if ($RunResponsePath) {
        $policy = Get-Content -Raw $PolicyResponsePath | ConvertFrom-Json
        $run = Get-Content -Raw $RunResponsePath | ConvertFrom-Json
    } else {
        $policy = Invoke-RestMethod -Method Get -Uri "$resource/policy" -Headers $headers
        $body = @{ useRerank = [bool]$UseRerank } | ConvertTo-Json -Compress
        $run = Invoke-RestMethod -Method Post -Uri "$resource/runs" -Headers $headers `
            -ContentType "application/json" -Body $body
    }

    $run | ConvertTo-Json -Depth 20
    switch ([string]$run.gateStatus) {
        "PASS" { exit 0 }
        "WARN" { exit 0 }
        "BLOCKED" { exit 1 }
        "UNBASELINED" {
            if ([bool]$policy.blockWhenUnbaselined) { exit 1 }
            exit 0
        }
        default {
            [Console]::Error.WriteLine("Unexpected or missing gateStatus: $($run.gateStatus)")
            exit 2
        }
    }
} catch {
    [Console]::Error.WriteLine("RAG quality gate request failed: $($_.Exception.Message)")
    exit 2
}
