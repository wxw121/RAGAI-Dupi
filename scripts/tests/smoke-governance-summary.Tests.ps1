Describe "Governance summary smoke script" {
    BeforeAll {
        $script:scriptPath = Join-Path $PSScriptRoot "..\smoke-governance-summary.ps1"
        $script:validSample = '{"generatedAt":"2026-07-18T10:15:30Z","uploadQuota":{},"ingestJobs":{},"ingestOutbox":{},"failureNotifications":{},"vectorCleanup":{},"alerts":[]}'
        $script:validSampleBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($script:validSample))
    }

    It "declares safe parameters and the governance summary endpoint" {
        Test-Path -LiteralPath $script:scriptPath | Should -BeTrue
        $content = Get-Content -Raw -LiteralPath $script:scriptPath

        $content | Should -Match '\[string\]\$BaseUrl'
        $content | Should -Match '\[string\]\$ApiKey'
        $content | Should -Match '\[string\]\$OutFile'
        $content | Should -Match '/api/v1/ops/governance-summary'
        $content | Should -Not -Match 'Write-Host\s+.*\$ApiKey'
        $content | Should -Not -Match 'Write-Output\s+.*\$ApiKey'
    }

    It "accepts a complete governance summary sample" {
        $outFile = Join-Path $TestDrive "sample-governance-summary-smoke.json"
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ValidateSampleOnly -SampleJsonBase64 $script:validSampleBase64 -OutFile $outFile
        $LASTEXITCODE | Should -Be 0
        Test-Path -LiteralPath $outFile | Should -BeTrue
    }

    It "rejects governance summary samples missing required top-level fields" {
        $missingFields = '{"generatedAt":"2026-07-18T10:15:30Z","uploadQuota":{},"ingestJobs":{},"alerts":[]}'
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ValidateSampleOnly -SampleJson $missingFields 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "writes redacted evidence without leaking the API key" {
        $outFile = Join-Path $TestDrive "governance-summary-smoke.json"
        $json = & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ValidateSampleOnly -SampleJsonBase64 $script:validSampleBase64 -ApiKey "do-not-leak" -OutFile $outFile

        $LASTEXITCODE | Should -Be 0
        $json | Should -Not -Match "do-not-leak"
        Test-Path -LiteralPath $outFile | Should -BeTrue
        $artifactText = Get-Content -Raw -LiteralPath $outFile
        $artifactText | Should -Not -Match "do-not-leak"
        $artifact = $artifactText | ConvertFrom-Json
        $artifact.endpoint | Should -Be "/api/v1/ops/governance-summary"
        $artifact.requiredFieldsValid | Should -BeTrue
        ($artifact.requiredFields -join ",") | Should -Match "failureNotifications"
    }
}
