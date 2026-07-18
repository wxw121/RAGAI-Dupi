param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ApiKey = "",
    [string]$OutFile = (Join-Path $PSScriptRoot "..\evidence\governance-summary-smoke.json"),
    [int]$TimeoutSec = 30,
    [switch]$ValidateSampleOnly,
    [string]$SampleJson = "",
    [string]$SampleJsonBase64 = ""
)

$ErrorActionPreference = "Stop"
$endpoint = "/api/v1/ops/governance-summary"
$requiredFields = @(
    "generatedAt",
    "uploadQuota",
    "ingestJobs",
    "ingestOutbox",
    "failureNotifications",
    "vectorCleanup",
    "alerts"
)

function Assert-GovernanceSummary($Summary) {
    if (-not $Summary) { throw "Governance summary response is empty" }
    $names = @($Summary.PSObject.Properties.Name)
    foreach ($field in $requiredFields) {
        if ($names -notcontains $field) {
            throw "Governance summary is missing required top-level field: $field"
        }
    }
    if (-not ($Summary.alerts -is [array])) {
        throw "Governance summary alerts must be an array"
    }
}

function Invoke-GovernanceSummary {
    $headers = @{}
    if ($ApiKey) { $headers["X-Dupi-API-Key"] = $ApiKey }
    Invoke-RestMethod -Method GET -Uri "$($BaseUrl.TrimEnd('/'))$endpoint" -Headers $headers -UseBasicParsing -TimeoutSec $TimeoutSec
}

function Get-Count($Value) {
    if ($null -eq $Value) { return 0 }
    if ($Value -is [array]) { return $Value.Count }
    return @($Value).Count
}

function New-SmokeEvidence($Summary, [string]$Source) {
    [ordered]@{
        schemaVersion = 1
        endpoint = $endpoint
        baseUrl = $BaseUrl.TrimEnd("/")
        source = $Source
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        responseGeneratedAt = $Summary.generatedAt
        requiredFields = $requiredFields
        requiredFieldsValid = $true
        apiKeyConfigured = -not [string]::IsNullOrWhiteSpace($ApiKey)
        uploadQuotaFields = @($Summary.uploadQuota.PSObject.Properties.Name)
        ingestJobFields = @($Summary.ingestJobs.PSObject.Properties.Name)
        ingestOutboxFields = @($Summary.ingestOutbox.PSObject.Properties.Name)
        failureNotificationFields = @($Summary.failureNotifications.PSObject.Properties.Name)
        vectorCleanupFields = @($Summary.vectorCleanup.PSObject.Properties.Name)
        alertCount = Get-Count $Summary.alerts
    }
}

try {
    $source = "live"
    $summary = $null
    if ($ValidateSampleOnly) {
        if (-not [string]::IsNullOrWhiteSpace($SampleJsonBase64)) {
            $SampleJson = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($SampleJsonBase64))
        }
        if ([string]::IsNullOrWhiteSpace($SampleJson)) { throw "SampleJson or SampleJsonBase64 is required when ValidateSampleOnly is used" }
        $summary = $SampleJson | ConvertFrom-Json
        $source = "sample"
    } else {
        $summary = Invoke-GovernanceSummary
    }

    Assert-GovernanceSummary $summary
    $evidence = New-SmokeEvidence $summary $source
    $json = $evidence | ConvertTo-Json -Depth 20

    $directory = Split-Path -Parent $OutFile
    if ($directory -and -not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    Set-Content -LiteralPath $OutFile -Value $json -Encoding UTF8
    Write-Output $json
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
